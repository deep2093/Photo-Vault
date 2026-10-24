package com.deepak.photovault.core.household

import com.deepak.photovault.core.drive.ByteSource
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.drive.DrivePaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a note is about (23 Oct): an idea or comment, or something that went wrong. */
enum class FeedbackKind(val label: String) { FEEDBACK("Feedback"), BUG("Bug") }

/** One note sent from Settings > Send feedback. Attachments are files next to it on the drive. */
@Serializable
data class FeedbackNote(
    val id: String,
    val kind: FeedbackKind,
    val text: String,
    val fromUserId: String,
    val fromName: String,
    val deviceName: String,
    val appVersion: String,
    val createdAtMillis: Long,
    val attachments: List<String> = emptyList(),
)

/** A note as the owner's inbox shows it, with the folder its attachments are in. */
data class FeedbackEntry(val note: FeedbackNote, val folder: String)

/**
 * Feedback without any email address (23 Oct): each note is saved on the family drive in the sender's own
 * folder (users/<id>/feedback/<note>/), so everyone still writes only inside their own folder. The owner reads
 * everyone's in the app. Before a Play Store release this is joined by a web inbox (Phase 13).
 *
 * The attachments are written first and note.json last, so a note only appears once it is complete.
 */
class FeedbackBox(private val drive: DriveFileSystem) {
    suspend fun send(note: FeedbackNote, files: List<Pair<String, ByteSource>>): FeedbackNote {
        val folder = DrivePaths.join(folderOf(note.fromUserId), note.id)
        drive.mkdirs(folder)
        val names = mutableListOf<String>()
        for ((name, source) in files) {
            val clean = uniqueName(cleanName(name), names)
            val path = DrivePaths.join(folder, clean)
            // A retry after a cut-off send replaces the half-written copy.
            if (drive.exists(path)) drive.delete(path)
            source.use { drive.writeNew(path, it) }
            names += clean
        }
        val saved = note.copy(attachments = names)
        drive.createNew(DrivePaths.join(folder, NOTE), json.encodeToString(FeedbackNote.serializer(), saved).encodeToByteArray())
        return saved
    }

    /** Everyone's notes, newest first (the owner's inbox). Unfinished or damaged notes are skipped. */
    suspend fun inbox(userIds: List<String>): List<FeedbackEntry> = userIds.flatMap { userId ->
        val root = folderOf(userId)
        val folders = try {
            drive.list(root).filter { it.isFolder }
        } catch (_: DriveNotFound) {
            emptyList()
        }
        folders.mapNotNull { f ->
            val folder = DrivePaths.join(root, f.name)
            runCatching { json.decodeFromString(FeedbackNote.serializer(), drive.read(DrivePaths.join(folder, NOTE)).decodeToString()) }
                .getOrNull()?.let { FeedbackEntry(it, folder) }
        }
    }.sortedByDescending { it.note.createdAtMillis }

    /**
     * The owner clears received notes (25 Oct): the note and its attached files are deleted. The empty folder stays
     * (a drive can't delete folders from here); without its note it no longer shows in the inbox.
     */
    suspend fun clear(entries: List<FeedbackEntry>) {
        for (entry in entries) {
            val files = try {
                drive.list(entry.folder).filter { !it.isFolder }
            } catch (_: DriveNotFound) {
                emptyList()
            }
            // The note goes last, so a half-cleared one still shows until it is fully gone.
            files.filter { it.name != NOTE }.forEach { drive.delete(DrivePaths.join(entry.folder, it.name)) }
            drive.delete(DrivePaths.join(entry.folder, NOTE))
        }
    }

    companion object {
        const val FOLDER = "feedback"
        const val NOTE = "note.json"

        /** Biggest total of attachments in one note. */
        const val MAX_ATTACHMENT_BYTES = 300L * 1024 * 1024

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun folderOf(userId: String) = DrivePaths.join(HouseholdStore.userFolder(userId), FOLDER)

        fun cleanName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "file" }.let { if (it == NOTE) "_$it" else it }

        fun uniqueName(name: String, taken: List<String>): String {
            if (name !in taken) return name
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
            var n = 2
            while ("$base ($n)$ext" in taken) n++
            return "$base ($n)$ext"
        }
    }
}
