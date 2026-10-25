package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.ByteSource
import com.deepak.photovault.core.drive.BytesSource
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.drive.DrivePaths
import com.deepak.photovault.core.household.HouseholdStore
import com.deepak.photovault.core.media.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * Backs up one person's phone photos into their own folder on the drive (docs/BACKUP_ENGINE.md).
 *
 * For every photo:
 * 1. Stream it to a private temporary file in users/<id>/.incoming, working out its SHA-256 on the way.
 * 2. Read the temporary file back from the drive and work out its SHA-256 again.
 * 3. Only if both match, publish it with a rename that never replaces anything (albums/<album>/YYYY/MM/name).
 *    If the name is taken by the very same file it counts as already there; a different file gets "name (2)".
 * 4. Only then is it recorded as backed up: in this phone's index and in a manifest file on the drive.
 *
 * Nothing is ever deleted from the phone here, and the only things deleted on the drive are this phone's own
 * temporary files.
 */
class BackupEngine(
    private val drive: DriveFileSystem,
    private val userId: String,
    private val deviceId: String,
    private val reader: MediaReader,
    private val index: BackupIndex,
    private val now: () -> Long,
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
    /** Turns a platform error (SMB) into a reason a person can act on. */
    private val classify: (Throwable) -> BackupFailure = { BackupFailure(FailureReason.OTHER, it.message) },
    /** Set for a Private person: everything uploaded is encrypted with their key first. */
    private val vault: PrivateVault? = null,
) {
    private val userFolder = HouseholdStore.userFolder(userId)
    private val incoming = DrivePaths.join(userFolder, INCOMING)

    /**
     * Backs up whichever of [items] aren't backed up yet, newest first, staying within [limitBytes] of space.
     * Never throws except for cancellation (after saving what was finished); problems end up in the result.
     */
    suspend fun run(
        items: List<MediaItem>,
        settings: BackupSettings,
        limitBytes: Long,
        trigger: BackupTrigger,
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupRun {
        val started = now()
        var uploaded = 0
        var failed = 0
        var bytes = 0L
        var already = 0
        var processed = 0
        var failure: BackupFailure? = null
        val pending = mutableListOf<BackupRecord>()
        try {
            syncIndexIfEmpty()
            val done = index.keys()
            val todo = items.filter { BackupSelection.key(it) !in done }.sortedByDescending { it.takenAtMillis }
            already = items.size - todo.size
            onProgress(BackupProgress(todo.size, 0, 0, 0, 0))
            if (todo.isNotEmpty()) {
                drive.mkdirs(incoming)
                cleanUpOwnTemporaryFiles()
                var used = drive.folderSize(userFolder)
                var free = drive.space().freeBytes
                for (item in todo) {
                    currentCoroutineContext().ensureActive()
                    onProgress(BackupProgress(todo.size, processed, uploaded, failed, bytes, item.displayName))
                    val opened = try {
                        reader.open(item, settings.storageSaver && !item.isVideo)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Deleted or moved on the phone since the list was made: nothing to back up.
                        processed++
                        continue
                    }
                    val outcome = opened.use { prepared ->
                        when {
                            used + prepared.sizeBytes > limitBytes -> Outcome.Stop(FailureReason.SPACE_LIMIT_FULL)
                            free - prepared.sizeBytes < DRIVE_MARGIN_BYTES -> Outcome.Stop(FailureReason.DRIVE_FULL)
                            else -> backUpOne(item, prepared)
                        }
                    }
                    if (outcome !is Outcome.Stop) processed++
                    when (outcome) {
                        is Outcome.Saved -> {
                            index.add(listOf(outcome.record))
                            pending += outcome.record
                            uploaded++
                            bytes += outcome.record.sizeBytes
                            used += outcome.record.sizeBytes
                            free -= outcome.record.sizeBytes
                            if (pending.size >= MANIFEST_BATCH) flush(pending)
                        }
                        Outcome.Mismatch -> failed++
                        is Outcome.Stop -> {
                            failure = BackupFailure(outcome.reason)
                            break
                        }
                    }
                }
                onProgress(BackupProgress(todo.size, processed, uploaded, failed, bytes))
            }
            if (failure == null && failed > 0) failure = BackupFailure(FailureReason.CHECK_FAILED)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { flush(pending) } }
            throw e
        } catch (e: Throwable) {
            failure = classify(e)
        } finally {
            withContext(NonCancellable) { runCatching { flush(pending) } }
        }
        return BackupRun(
            trigger = trigger,
            startedAtMillis = started,
            finishedAtMillis = now(),
            eligible = items.size,
            alreadyBackedUp = already,
            uploaded = uploaded,
            failedItems = failed,
            bytes = bytes,
            failure = failure?.reason,
            failureDetail = failure?.detail,
        )
    }

    private sealed interface Outcome {
        data class Saved(val record: BackupRecord) : Outcome
        data object Mismatch : Outcome
        data class Stop(val reason: FailureReason) : Outcome
    }

    private suspend fun backUpOne(item: MediaItem, prepared: PreparedFile): Outcome =
        if (vault != null) backUpPrivate(vault, item, prepared) else backUpNormal(item, prepared)

    /**
     * Private: the same safety as Normal, with encryption in between. The photo is encrypted as it streams, the
     * stored copy is read back, decrypted and checked against the plain SHA-256, and only then published under a
     * random name. A thumbnail is stored encrypted beside it.
     */
    private suspend fun backUpPrivate(vault: PrivateVault, item: MediaItem, prepared: PreparedFile): Outcome {
        val temp = DrivePaths.join(incoming, "$deviceId-${Uuid.random()}$TEMP_SUFFIX")
        val hasher = newSha256()
        var size = 0L
        val hashing = object : ByteSource {
            override fun read(buffer: ByteArray): Int {
                val n = prepared.source.read(buffer)
                if (n > 0) {
                    hasher.update(buffer, 0, n)
                    size += n
                }
                return n
            }

            override fun close() = Unit
        }
        val id = vault.newObjectId()
        try {
            check(drive.writeNew(temp, vault.encrypt(hashing, ObjectKind.MEDIA, id))) { "Temporary name already in use" }
            val local = hasher.hex()
            val checker = newSha256()
            var checkedSize = 0L
            try {
                vault.decrypt(ObjectKind.MEDIA, id, { sink -> drive.readStream(temp, sink) }) { b, n ->
                    checker.update(b, 0, n)
                    checkedSize += n
                }
            } catch (_: ObjectAuthenticationFailed) {
                drive.delete(temp)
                return Outcome.Mismatch
            }
            if (checker.hex() != local || checkedSize != size) {
                drive.delete(temp)
                return Outcome.Mismatch
            }
            val path = PrivatePaths.path(userFolder, ObjectKind.MEDIA, id)
            drive.mkdirs(DrivePaths.parent(path))
            check(drive.rename(temp, path)) { "Encrypted name already in use" }
            writeThumbnail(vault, item, id)
            return Outcome.Saved(
                BackupRecord(
                    key = BackupSelection.key(item),
                    drivePath = path,
                    sha256 = local,
                    sizeBytes = size,
                    originalName = safeName(prepared.name),
                    takenAtMillis = item.takenAtMillis,
                    mimeType = prepared.mimeType,
                    isVideo = item.isVideo,
                    savedAtMillis = now(),
                    deviceId = deviceId,
                    storageSaver = prepared.compressed,
                    album = item.albumName.ifBlank { null },
                    encrypted = true,
                ),
            )
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { drive.delete(temp) } }
            throw e
        }
    }

    /** Best effort: a photo without a thumbnail still backs up, and shows as a plain tile until it is opened. */
    private suspend fun writeThumbnail(vault: PrivateVault, item: MediaItem, id: String) {
        val bytes = try {
            reader.thumbnail(item)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        val path = PrivatePaths.path(userFolder, ObjectKind.THUMB, id)
        try {
            drive.mkdirs(DrivePaths.parent(path))
            drive.writeNew(path, vault.encrypt(BytesSource(bytes), ObjectKind.THUMB, id))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            runCatching { drive.delete(path) }
        }
    }

    private suspend fun backUpNormal(item: MediaItem, prepared: PreparedFile): Outcome {
        val temp = DrivePaths.join(incoming, "$deviceId-${Uuid.random()}$TEMP_SUFFIX")
        val hasher = newSha256()
        var size = 0L
        val hashing = object : ByteSource {
            override fun read(buffer: ByteArray): Int {
                val n = prepared.source.read(buffer)
                if (n > 0) {
                    hasher.update(buffer, 0, n)
                    size += n
                }
                return n
            }

            override fun close() = Unit
        }
        try {
            check(drive.writeNew(temp, hashing)) { "Temporary name already in use" }
            val local = hasher.hex()
            val (remote, remoteSize) = checksumOnDrive(temp)
            if (remote != local || remoteSize != size) {
                drive.delete(temp)
                return Outcome.Mismatch
            }
            val folder = folderFor(item)
            drive.mkdirs(folder)
            val path = publish(temp, folder, safeName(prepared.name), local, size)
            val record = BackupRecord(
                key = BackupSelection.key(item),
                drivePath = path,
                sha256 = local,
                sizeBytes = size,
                originalName = item.displayName,
                takenAtMillis = item.takenAtMillis,
                mimeType = prepared.mimeType,
                isVideo = item.isVideo,
                savedAtMillis = now(),
                deviceId = deviceId,
                storageSaver = prepared.compressed,
                album = item.albumName.ifBlank { null },
            )
            return Outcome.Saved(record)
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { drive.delete(temp) } }
            throw e
        }
    }

    /** Renames [temp] to [name] in [folder] without replacing; "name (2).jpg" and so on when taken. */
    private suspend fun publish(temp: String, folder: String, name: String, sha256: String, size: Long): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        for (n in 1..MAX_NAME_TRIES) {
            val candidate = DrivePaths.join(folder, if (n == 1) name else "$base ($n)$ext")
            if (drive.rename(temp, candidate)) return candidate
            if (isSameFile(candidate, sha256, size)) {
                // Backed up before (by this phone before a reinstall, or another phone of the same person).
                drive.delete(temp)
                return candidate
            }
        }
        error("Too many files with the name $name")
    }

    private suspend fun isSameFile(path: String, sha256: String, size: Long): Boolean {
        val entry = drive.list(DrivePaths.parent(path)).firstOrNull { it.name == path.substringAfterLast('/') } ?: return false
        if (entry.isFolder || entry.size != size) return false
        return checksumOnDrive(path).first == sha256
    }

    private suspend fun checksumOnDrive(path: String): Pair<String, Long> {
        val hasher = newSha256()
        var size = 0L
        drive.readStream(path) { buffer, n ->
            hasher.update(buffer, 0, n)
            size += n
        }
        return hasher.hex() to size
    }

    /** users/<id>/albums/<phone album>/YYYY/MM (24 Oct: the drive mirrors the phone's albums). */
    private fun folderFor(item: MediaItem): String {
        val date = Instant.fromEpochMilliseconds(item.takenAtMillis).toLocalDateTime(zone).date
        return DrivePaths.join(userFolder, ALBUMS, albumFolder(item.albumName), date.year.toString(), date.monthNumber.toString().padStart(2, '0'))
    }

    /** Leftovers from a backup that was cut off (this phone's only; another phone may be uploading now). */
    private suspend fun cleanUpOwnTemporaryFiles() {
        drive.list(incoming).filter { !it.isFolder && it.name.startsWith("$deviceId-") && it.name.endsWith(TEMP_SUFFIX) }
            .forEach { runCatching { drive.delete(DrivePaths.join(incoming, it.name)) } }
    }

    /** Saves finished records as a new manifest file on the drive. */
    private suspend fun flush(pending: MutableList<BackupRecord>) {
        if (pending.isEmpty()) return
        val manifest = Manifest(deviceId = deviceId, createdAtMillis = now(), records = pending.toList())
        val folder = DrivePaths.join(userFolder, MANIFESTS, deviceId)
        drive.mkdirs(folder)
        val bytes = encodeManifest(vault, manifest)
        while (!drive.createNew(DrivePaths.join(folder, manifestName(manifest.createdAtMillis, vault != null)), bytes)) Unit
        pending.clear()
    }

    /** A new phone, or the app reinstalled: learn what is already on the drive before uploading anything. */
    private suspend fun syncIndexIfEmpty() {
        if (index.keys().isNotEmpty()) return
        val records = BackupCatalog(drive, userId, vault).records()
        if (records.isNotEmpty()) index.add(records)
    }

    companion object {
        const val INCOMING = ".incoming"
        const val MANIFESTS = "manifests"

        /** Each phone album gets its own folder under this one (24 Oct). */
        const val ALBUMS = "albums"

        /** A phone album's name made safe as a folder name on any drive. */
        fun albumFolder(name: String): String {
            val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').trim().take(60)
            return clean.ifEmpty { "No album" }
        }
        const val TEMP_SUFFIX = ".part"
        /** Stop before the drive is completely full, so the router and other phones still work. */
        const val DRIVE_MARGIN_BYTES = 1_000_000_000L
        const val MANIFEST_BATCH = 25
        private const val MAX_NAME_TRIES = 200

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** A Private person's manifests are sealed and end in .pxm; Normal ones are readable .json. */
        fun manifestName(millis: Long, sealed: Boolean = false) =
            "m-${millis.toString().padStart(14, '0')}-${Uuid.random().toString().take(8)}.${if (sealed) "pxm" else "json"}"

        fun manifestTime(name: String): Long? = Regex("""m-(\d{14})-[0-9a-f]{8}\.(json|pxm)""").matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

        internal const val MANIFEST_PURPOSE = "manifest"
        internal const val NOTE_PURPOSE = "trash-note"

        internal fun encodeManifest(vault: PrivateVault?, manifest: Manifest): ByteArray {
            val plain = json.encodeToString(Manifest.serializer(), manifest).encodeToByteArray()
            return vault?.sealRecord(plain, MANIFEST_PURPOSE) ?: plain
        }

        /** Characters that SMB drives refuse, replaced; empty names get a stand-in. */
        fun safeName(name: String): String {
            val cleaned = name.map { c -> if (c < ' ' || c in "\\/:*?\"<>|") '_' else c }.joinToString("").trim().trimEnd('.')
            val limited = if (cleaned.length > 120) cleaned.take(100) + cleaned.takeLast(20) else cleaned
            return limited.ifEmpty { "photo" }.let { if (it.startsWith('.')) "_$it" else it }
        }
    }
}

/** Reads what a person has on the drive: every manifest from every one of their phones. */
class BackupCatalog(private val drive: DriveFileSystem, userId: String, private val vault: PrivateVault? = null) {
    private val userFolder = HouseholdStore.userFolder(userId)
    private val manifests = DrivePaths.join(userFolder, BackupEngine.MANIFESTS)

    suspend fun records(): List<BackupRecord> {
        // Every phone's manifests in time order (their names start with the time), so a deletion only hides
        // what was saved before it, and a photo backed up again afterwards shows again.
        val files = drive.list(manifests).filter { it.isFolder }.flatMap { device ->
            val folder = DrivePaths.join(manifests, device.name)
            drive.list(folder).filter { !it.isFolder && (it.name.endsWith(".json") || it.name.endsWith(".pxm")) }.map { DrivePaths.join(folder, it.name) }
        }.sortedBy { it.substringAfterLast('/') }
        val out = LinkedHashMap<String, BackupRecord>()
        for (path in files) {
            val manifest = try {
                readManifest(path) ?: continue
            } catch (_: DriveNotFound) {
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue // A damaged manifest never stops a backup.
            }
            manifest.records.forEach { out[it.drivePath] = it }
            manifest.removed.forEach { out.remove(it) }
        }
        return out.values.toList()
    }

    private suspend fun readManifest(path: String): Manifest? {
        val raw = drive.read(path)
        val plain = if (path.endsWith(".pxm")) (vault?.openRecord(raw, BackupEngine.MANIFEST_PURPOSE) ?: return null) else raw
        return BackupEngine.json.decodeFromString(Manifest.serializer(), plain.decodeToString())
    }

    /**
     * Deletes backups from the drive (23 Oct, Settings > Storage, after a warning). Only files inside this
     * person's own folder. The deletion is written down first, then the files go, so the list never shows a
     * file that is gone. The phone itself is never touched.
     */
    suspend fun remove(records: List<BackupRecord>, deviceId: String, now: Long) {
        if (records.isEmpty()) return
        val own = records.filter { it.drivePath.startsWith("$userFolder/") && !it.drivePath.contains("..") }
        require(own.size == records.size) { "Only your own backups can be deleted" }
        val folder = DrivePaths.join(manifests, deviceId)
        drive.mkdirs(folder)
        val note = Manifest(deviceId = deviceId, createdAtMillis = now, records = emptyList(), removed = own.map { it.drivePath })
        val bytes = BackupEngine.encodeManifest(vault, note)
        while (!drive.createNew(DrivePaths.join(folder, BackupEngine.manifestName(now, vault != null)), bytes)) Unit
        for (r in own) {
            try {
                drive.delete(r.drivePath)
            } catch (_: DriveNotFound) {
                // Already gone.
            }
            deleteThumb(r.drivePath)
        }
    }

    // ---- Recently deleted on the drive (Phase 5A, 25 Oct): 30 days to change your mind ----

    private val trashFolder = DrivePaths.join(userFolder, "trash")
    private val trashFiles = DrivePaths.join(trashFolder, "files")

    private fun own(records: List<BackupRecord>): List<BackupRecord> {
        val own = records.filter { it.drivePath.startsWith("$userFolder/") && !it.drivePath.contains("..") }
        require(own.size == records.size) { "Only your own backups can be changed" }
        return own
    }

    private suspend fun writeManifest(deviceId: String, now: Long, records: List<BackupRecord>, removed: List<String>) {
        val folder = DrivePaths.join(manifests, deviceId)
        drive.mkdirs(folder)
        val note = Manifest(deviceId = deviceId, createdAtMillis = now, records = records, removed = removed)
        val bytes = BackupEngine.encodeManifest(vault, note)
        var at = now
        // Manifest names start with the time; a clash means another note landed in the same millisecond.
        while (!drive.createNew(DrivePaths.join(folder, BackupEngine.manifestName(at, vault != null)), bytes)) at++
    }

    /**
     * Moves backups into the drive's Recently deleted instead of deleting them. A small note beside each file keeps
     * its record, so it can come back exactly as it was. The photo leaves the list at once.
     */
    suspend fun trash(records: List<BackupRecord>, deviceId: String, now: Long) {
        val chosen = own(records)
        if (chosen.isEmpty()) return
        drive.mkdirs(trashFiles)
        val moved = mutableListOf<BackupRecord>()
        for ((i, r) in chosen.withIndex()) {
            // An encrypted file keeps its random ID in the trash, so nothing about the photo is readable there.
            val target = DrivePaths.join(trashFiles, if (r.encrypted) "$now-$i-${PrivatePaths.idOf(r.drivePath)}.pxo" else "$now-$i-${r.fileName}")
            val note = TrashedBackup(r, target, now)
            val noteBytes = BackupEngine.json.encodeToString(TrashedBackup.serializer(), note).encodeToByteArray()
            val noteFile = DrivePaths.join(trashFolder, "$now-$i.${if (vault != null) "pxn" else "json"}")
            // The note first, so a file in the trash can never be without its record.
            if (!drive.createNew(noteFile, vault?.sealRecord(noteBytes, BackupEngine.NOTE_PURPOSE) ?: noteBytes)) continue
            try {
                if (drive.rename(r.drivePath, target)) moved += r else drive.delete(noteFile)
            } catch (_: DriveNotFound) {
                drive.delete(noteFile)
            }
        }
        if (moved.isNotEmpty()) writeManifest(deviceId, now, emptyList(), moved.map { it.drivePath })
    }

    /** What is in the drive's Recently deleted, newest first. */
    suspend fun trashed(): List<TrashedBackup> =
        drive.list(trashFolder).filter { !it.isFolder && (it.name.endsWith(".json") || it.name.endsWith(".pxn")) }.mapNotNull { f ->
            try {
                val raw = drive.read(DrivePaths.join(trashFolder, f.name))
                val plain = if (f.name.endsWith(".pxn")) (vault?.openRecord(raw, BackupEngine.NOTE_PURPOSE) ?: return@mapNotNull null) else raw
                BackupEngine.json.decodeFromString(TrashedBackup.serializer(), plain.decodeToString())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.deletedAtMillis }

    /** Puts backups back where they were. A file whose old place is taken stays in the trash. */
    suspend fun restore(items: List<TrashedBackup>, deviceId: String, now: Long) {
        val back = mutableListOf<BackupRecord>()
        for (t in items) {
            own(listOf(t.record))
            val ok = try {
                drive.mkdirs(t.record.drivePath.substringBeforeLast('/'))
                drive.rename(t.trashPath, t.record.drivePath)
            } catch (_: DriveNotFound) {
                false
            }
            if (ok) {
                back += t.record
                forgetNote(t)
            }
        }
        if (back.isNotEmpty()) writeManifest(deviceId, now, back, emptyList())
    }

    /** Deletes backups from the trash for good. */
    suspend fun deleteForever(items: List<TrashedBackup>) {
        for (t in items) {
            own(listOf(t.record))
            try {
                drive.delete(t.trashPath)
            } catch (_: DriveNotFound) {
            }
            deleteThumb(t.record.drivePath)
            forgetNote(t)
        }
    }

    /** Clears what has been in the trash for [TRASH_DAYS] days or more. Returns what is left. */
    suspend fun purgeExpired(now: Long): List<TrashedBackup> {
        val all = trashed()
        val (old, fresh) = all.partition { now - it.deletedAtMillis >= TRASH_DAYS * DAY_MILLIS }
        deleteForever(old)
        return fresh
    }

    private suspend fun forgetNote(t: TrashedBackup) {
        // The note is named after the file in the trash: "<time>-<n>-<name>" -> "<time>-<n>.json".
        val stem = t.trashPath.substringAfterLast('/').split('-', limit = 3).take(2).joinToString("-")
        for (ext in listOf("json", "pxn")) {
            try {
                drive.delete(DrivePaths.join(trashFolder, "$stem.$ext"))
            } catch (_: DriveNotFound) {
            }
        }
    }

    private suspend fun deleteThumb(objectPath: String) {
        val thumb = PrivatePaths.thumbFor(objectPath) ?: return
        try {
            drive.delete(thumb)
        } catch (_: DriveNotFound) {
        }
    }

    companion object {
        const val TRASH_DAYS = 30
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }

    /** When each of the person's phones last saved something: deviceId to time. */
    suspend fun lastBackups(): Map<String, Long> =
        drive.list(manifests).filter { it.isFolder }.mapNotNull { device ->
            val latest = drive.list(DrivePaths.join(manifests, device.name)).mapNotNull { BackupEngine.manifestTime(it.name) }.maxOrNull()
            latest?.let { device.name to it }
        }.toMap()
}
