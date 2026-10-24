package com.deepak.photovault.core.household

import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.drive.DrivePaths
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** Owner-only actions need the owner's phone and, when the PIN setting is on, the PIN. */
data class OwnerAuth(val deviceId: String, val pin: String?)

class NotAllowed(message: String) : Exception(message)

class AlreadySetUp : Exception("This drive already has a PhotoVault family")

/**
 * Reads and changes the family on the drive (docs/HOUSEHOLD_PROTOCOL.md).
 *
 * Every change writes a new numbered version with create-new, so two phones changing things at once can never
 * overwrite each other: the second one sees its number taken, reloads, re-applies its change and tries the
 * next number. Old versions are kept for a while as history.
 *
 * Owner checks run in the app. Anyone with the drive password could edit the files from a computer; v1
 * accepts that (folders are separate, not secret; SECURITY_MODEL.md).
 */
class HouseholdStore(
    private val drive: DriveFileSystem,
    private val now: () -> Long,
) {
    // ---- Reading ----

    /** The current family, or null when the drive has none. Skips damaged versions. */
    suspend fun load(): Household? {
        val versions = drive.list(HOUSEHOLD_DIR)
            .mapNotNull { e -> versionOf(e.name)?.let { it to e.name } }
            .sortedByDescending { it.first }
        for ((_, name) in versions) {
            val household = runCatching { decode(drive.read("$HOUSEHOLD_DIR/$name")) }.getOrNull()
            if (household != null) return household
        }
        return null
    }

    suspend fun membership(deviceId: String, rememberedUserId: String? = null): Membership {
        val household = load() ?: return Membership.NoHousehold
        household.memberForDevice(deviceId)?.let { return Membership.Joined(household, it) }
        request(deviceId)?.let { return Membership.Pending(household, it) }
        if (rememberedUserId != null && household.member(rememberedUserId) == null) return Membership.Removed(household)
        return Membership.NotJoined(household)
    }

    suspend fun pendingRequests(): List<JoinRequest> =
        drive.list(REQUESTS_DIR).filter { !it.isFolder && it.name.endsWith(".json") }
            .mapNotNull { runCatching { json.decodeFromString<JoinRequest>(drive.read("$REQUESTS_DIR/${it.name}").decodeToString()) }.getOrNull() }
            .sortedBy { it.requestedAtMillis }

    /** Bytes in a person's folder (photos, videos, recently deleted). */
    suspend fun usedBytes(userId: String): Long = drive.folderSize(userFolder(userId))

    // ---- Setting up and joining ----

    /** First phone on this drive: creates the family with this person as owner. */
    suspend fun create(ownerName: String, deviceId: String, deviceName: String, pin: String?): Household {
        val name = cleanName(ownerName)
        if (pin != null) require(OwnerPin.isValid(pin)) { "PIN must be 4 to 8 digits" }
        drive.mkdirs(HOUSEHOLD_DIR)
        drive.mkdirs(REQUESTS_DIR)
        val owner = Member(newId(), name, DEFAULT_LIMIT_GB, now(), listOf(Device(deviceId, deviceName, now())))
        val household = Household(
            householdId = newId(),
            revision = 1,
            ownerUserId = owner.userId,
            ownerPin = pin?.let(OwnerPin::hash),
            settings = HouseholdSettings(requirePin = pin != null),
            members = listOf(owner),
        )
        if (!drive.createNew(versionPath(1), encode(household))) throw AlreadySetUp()
        ensureFolders(owner.userId)
        return household
    }

    /**
     * A phone asks to join, as a new person or as another phone of someone already here. When the owner has
     * turned approval off it joins straight away; otherwise it waits for the owner.
     *
     * Joining as the owner (23 Oct, for a lost or replaced phone): with the right [ownerPin] the phone joins
     * straight away, even with approval on, because nobody else could approve it. Without the PIN it waits
     * for one of the owner's other phones, and it never joins as the owner unapproved.
     */
    suspend fun requestJoin(
        deviceId: String,
        deviceName: String,
        newName: String? = null,
        existingUserId: String? = null,
        ownerPin: String? = null,
    ): Membership {
        require((newName == null) != (existingUserId == null)) { "Join as a new person or as an existing one" }
        val household = load() ?: return Membership.NoHousehold
        household.memberForDevice(deviceId)?.let { return Membership.Joined(household, it) }
        val request = JoinRequest(deviceId, cleanDevice(deviceName), newName?.let(::cleanName), existingUserId, now())
        if (existingUserId != null) require(household.member(existingUserId) != null) { "That person is not in the family" }
        val asOwner = existingUserId != null && existingUserId == household.ownerUserId
        if (asOwner && ownerPin != null) {
            val stored = household.ownerPin ?: throw NotAllowed("This family has no owner PIN. Approve this phone from another of the owner's phones.")
            if (!OwnerPin.matches(ownerPin, stored)) throw NotAllowed("Wrong PIN")
            return admit(request)
        }
        if (!household.settings.approveNewPhones && !asOwner) return admit(request)
        drive.mkdirs(REQUESTS_DIR)
        drive.delete(requestPath(deviceId))
        drive.createNew(requestPath(deviceId), json.encodeToString(JoinRequest.serializer(), request).encodeToByteArray())
        return Membership.Pending(load() ?: household, request)
    }

    /** The phone gives up waiting. */
    suspend fun cancelRequest(deviceId: String) = drive.delete(requestPath(deviceId))

    // ---- Owner actions ----

    suspend fun approve(auth: OwnerAuth, request: JoinRequest): Household {
        authorize(auth)
        return admitAndClear(request)
    }

    suspend fun decline(auth: OwnerAuth, request: JoinRequest) {
        authorize(auth)
        drive.delete(requestPath(request.deviceId))
    }

    suspend fun setLimit(auth: OwnerAuth, userId: String, limitGb: Int): Household {
        authorize(auth)
        require(limitGb in MIN_LIMIT_GB..MAX_LIMIT_GB) { "Limit must be between $MIN_LIMIT_GB and $MAX_LIMIT_GB GB" }
        return commit { h ->
            require(h.member(userId) != null) { "That person is not in the family" }
            h.copy(members = h.members.map { if (it.userId == userId) it.copy(limitGb = limitGb) else it })
        }
    }

    /** Removes a person (or one of their phones) from the family. Their photos on the drive are kept. */
    suspend fun removeMember(auth: OwnerAuth, userId: String): Household {
        authorize(auth)
        return commit { h ->
            require(userId != h.ownerUserId) { "The owner can't be removed" }
            h.copy(members = h.members.filterNot { it.userId == userId })
        }
    }

    suspend fun removeDevice(auth: OwnerAuth, deviceId: String): Household {
        authorize(auth)
        require(deviceId != auth.deviceId) { "This phone can't remove itself" }
        return commit { h -> h.copy(members = h.members.map { m -> m.copy(devices = m.devices.filterNot { it.deviceId == deviceId }) }) }
    }

    suspend fun updateSettings(auth: OwnerAuth, change: (HouseholdSettings) -> HouseholdSettings): Household {
        authorize(auth)
        return commit { h ->
            val next = change(h.settings)
            require(next.defaultLimitGb in MIN_LIMIT_GB..MAX_LIMIT_GB) { "Limit out of range" }
            // The PIN can only be required once one is set.
            require(!next.requirePin || h.ownerPin != null) { "Set a PIN first" }
            h.copy(settings = next)
        }
    }

    /** Sets or changes the owner PIN. [auth] must carry the current PIN when one is required. */
    suspend fun setPin(auth: OwnerAuth, newPin: String): Household {
        authorize(auth)
        require(OwnerPin.isValid(newPin)) { "PIN must be 4 to 8 digits" }
        val hash = OwnerPin.hash(newPin)
        return commit { h -> h.copy(ownerPin = hash, settings = h.settings.copy(requirePin = true)) }
    }

    /** Anyone may rename themselves; the owner may rename anyone. */
    suspend fun rename(deviceId: String, userId: String, name: String, pin: String? = null): Household {
        val clean = cleanName(name)
        val household = load() ?: throw NotAllowed("No family on this drive")
        val actor = household.memberForDevice(deviceId) ?: throw NotAllowed("This phone isn't in the family")
        if (actor.userId != userId) authorize(OwnerAuth(deviceId, pin))
        return commit { h -> h.copy(members = h.members.map { if (it.userId == userId) it.copy(name = clean) else it }) }
    }

    /**
     * Sets a person's profile picture (24 Oct): anyone may set their own, the owner (with the PIN) anyone's.
     * [jpeg] is a small square picture; it is saved as a new file, never over an old one.
     */
    suspend fun setPhoto(deviceId: String, userId: String, jpeg: ByteArray, pin: String? = null): Household {
        require(jpeg.size in 1..MAX_PHOTO_BYTES) { "That picture is too big" }
        authorizeSelfOrOwner(deviceId, userId, pin)
        drive.mkdirs(PEOPLE_DIR)
        var path: String
        do {
            path = "$PEOPLE_DIR/$userId-${now()}-${newId().take(6)}.jpg"
        } while (!drive.createNew(path, jpeg))
        val old = load()?.member(userId)?.photo
        val household = commit { h -> h.copy(members = h.members.map { if (it.userId == userId) it.copy(photo = path) else it }) }
        if (old != null && old != path) runCatching { drive.delete(old) }
        return household
    }

    /** Back to the first letter. Same rule as [setPhoto]. */
    suspend fun clearPhoto(deviceId: String, userId: String, pin: String? = null): Household {
        authorizeSelfOrOwner(deviceId, userId, pin)
        val old = load()?.member(userId)?.photo
        val household = commit { h -> h.copy(members = h.members.map { if (it.userId == userId) it.copy(photo = null) else it }) }
        if (old != null) runCatching { drive.delete(old) }
        return household
    }

    /** Reads a profile picture. Only files in the people folder can be read this way. */
    suspend fun readPhoto(path: String): ByteArray {
        require(path.startsWith("$PEOPLE_DIR/") && !path.removePrefix("$PEOPLE_DIR/").contains('/')) { "Not a profile picture" }
        return drive.read(path)
    }

    // ---- Private pictures (24 Oct): your own picture for someone, seen only by you ----

    /**
     * Saves [jpeg] as [viewerUserId]'s own picture for [memberId], in the viewer's own folder (people/), so it shows
     * on all the viewer's phones and nobody else's. Older ones for that member are removed after.
     */
    suspend fun setPrivatePhoto(viewerUserId: String, memberId: String, jpeg: ByteArray): String {
        require(jpeg.size in 1..MAX_PHOTO_BYTES) { "That picture is too big" }
        val folder = DrivePaths.join(userFolder(viewerUserId), PRIVATE_PEOPLE)
        drive.mkdirs(folder)
        val before = privatePhotoFiles(viewerUserId, memberId)
        var path: String
        do {
            path = DrivePaths.join(folder, "${safeId(memberId)}-${now().toString().padStart(14, '0')}-${newId().take(6)}.jpg")
        } while (!drive.createNew(path, jpeg))
        before.forEach { runCatching { drive.delete(it) } }
        return path
    }

    suspend fun clearPrivatePhoto(viewerUserId: String, memberId: String) {
        privatePhotoFiles(viewerUserId, memberId).forEach { runCatching { drive.delete(it) } }
    }

    /** memberId to the viewer's newest own picture of them. */
    suspend fun privatePhotos(viewerUserId: String): Map<String, String> {
        val folder = DrivePaths.join(userFolder(viewerUserId), PRIVATE_PEOPLE)
        return drive.list(folder).filter { !it.isFolder && it.name.endsWith(".jpg") }
            .groupBy { it.name.substringBeforeLast('-').substringBeforeLast('-') }
            .mapValues { (_, files) -> DrivePaths.join(folder, files.maxBy { it.name }.name) }
    }

    /** Reads one of the viewer's own pictures. */
    suspend fun readPrivatePhoto(viewerUserId: String, path: String): ByteArray {
        val folder = DrivePaths.join(userFolder(viewerUserId), PRIVATE_PEOPLE)
        require(path.startsWith("$folder/") && !path.removePrefix("$folder/").contains('/')) { "Not a picture" }
        return drive.read(path)
    }

    private suspend fun privatePhotoFiles(viewerUserId: String, memberId: String): List<String> {
        val folder = DrivePaths.join(userFolder(viewerUserId), PRIVATE_PEOPLE)
        return drive.list(folder).filter { !it.isFolder && it.name.startsWith("$memberId-") && it.name.endsWith(".jpg") }
            .map { DrivePaths.join(folder, it.name) }
    }

    /** A phone keeps its own name up to date (e.g. "Galaxy S26 Ultra" instead of a model code). */
    suspend fun renameDevice(deviceId: String, name: String): Household? {
        val clean = cleanDevice(name)
        val current = load() ?: return null
        val device = current.members.flatMap { it.devices }.firstOrNull { it.deviceId == deviceId } ?: return null
        if (device.name == clean) return current
        return commit { h ->
            h.copy(members = h.members.map { m -> m.copy(devices = m.devices.map { if (it.deviceId == deviceId) it.copy(name = clean) else it }) })
        }
    }

    private suspend fun authorizeSelfOrOwner(deviceId: String, userId: String, pin: String?) {
        val household = load() ?: throw NotAllowed("No family on this drive")
        val actor = household.memberForDevice(deviceId) ?: throw NotAllowed("This phone isn't in the family")
        require(household.member(userId) != null) { "That person is not in the family" }
        if (actor.userId != userId) authorize(OwnerAuth(deviceId, pin))
    }

    /** Checks a PIN without changing anything (to unlock the owner controls on screen). */
    suspend fun checkOwner(auth: OwnerAuth) = authorize(auth)

    // ---- Internals ----

    private suspend fun authorize(auth: OwnerAuth) {
        val h = load() ?: throw NotAllowed("No family on this drive")
        val actor = h.memberForDevice(auth.deviceId)
        if (actor?.userId != h.ownerUserId) throw NotAllowed("Only the owner can do that")
        val stored = h.ownerPin
        if (h.settings.requirePin && stored != null) {
            if (auth.pin == null || !OwnerPin.matches(auth.pin, stored)) throw NotAllowed("Wrong PIN")
        }
    }

    private suspend fun admitAndClear(request: JoinRequest): Household {
        val household = (admit(request) as? Membership.Joined)?.household ?: load()!!
        drive.delete(requestPath(request.deviceId))
        return household
    }

    private suspend fun admit(request: JoinRequest): Membership {
        var joinedUserId: String? = null
        val household = commit { h ->
            val device = Device(request.deviceId, request.deviceName, now())
            // A phone belongs to exactly one person: remove it from anyone else first.
            val members = h.members.map { m -> m.copy(devices = m.devices.filterNot { it.deviceId == request.deviceId }) }
            if (request.existingUserId != null) {
                require(members.any { it.userId == request.existingUserId }) { "That person is not in the family" }
                joinedUserId = request.existingUserId
                h.copy(members = members.map { if (it.userId == request.existingUserId) it.copy(devices = it.devices + device) else it })
            } else {
                val member = Member(newId(), request.newName ?: "Family member", h.settings.defaultLimitGb, now(), listOf(device))
                joinedUserId = member.userId
                h.copy(members = members + member)
            }
        }
        val me = household.member(joinedUserId!!)!!
        ensureFolders(me.userId)
        return Membership.Joined(household, me)
    }

    /** Writes the next version; on a clash, reloads and re-applies [change]. */
    private suspend fun commit(change: (Household) -> Household): Household {
        repeat(MAX_ATTEMPTS) {
            val current = load() ?: throw NotAllowed("No family on this drive")
            val next = change(current).copy(revision = current.revision + 1, householdId = current.householdId)
            if (drive.createNew(versionPath(next.revision), encode(next))) {
                prune(next.revision)
                return next
            }
        }
        throw IllegalStateException("The family settings are busy; try again")
    }

    private suspend fun prune(latest: Int) {
        runCatching {
            drive.list(HOUSEHOLD_DIR).forEach { e ->
                val v = versionOf(e.name) ?: return@forEach
                if (v <= latest - KEEP_VERSIONS) drive.delete("$HOUSEHOLD_DIR/${e.name}")
            }
        }
    }

    suspend fun ensureFolders(userId: String) {
        USER_FOLDERS.forEach { drive.mkdirs(DrivePaths.join(userFolder(userId), it)) }
    }

    private suspend fun request(deviceId: String): JoinRequest? = try {
        json.decodeFromString<JoinRequest>(drive.read(requestPath(deviceId)).decodeToString())
    } catch (_: DriveNotFound) {
        null
    } catch (_: Exception) {
        null
    }

    private fun encode(h: Household) = json.encodeToString(Household.serializer(), h).encodeToByteArray()

    private fun decode(bytes: ByteArray): Household {
        val h = json.decodeFromString(Household.serializer(), bytes.decodeToString())
        require(h.formatVersion <= Household.FORMAT_VERSION) { "Made by a newer PhotoVault" }
        return h
    }

    companion object {
        const val HOUSEHOLD_DIR = "system/household"
        const val REQUESTS_DIR = "system/requests"
        const val PEOPLE_DIR = "system/people"
        /** Inside a person's own folder: their private pictures of other members. */
        const val PRIVATE_PEOPLE = "people"
        const val MAX_PHOTO_BYTES = 512 * 1024
        /** Backups go in albums/<phone album>/... (24 Oct; was originals/ and videos/). */
        val USER_FOLDERS = listOf("albums", "manifests", "thumbnails", "recently-deleted")
        private const val MAX_ATTEMPTS = 8
        private const val KEEP_VERSIONS = 20

        fun userFolder(userId: String) = "users/$userId"

        fun versionPath(revision: Int) = "$HOUSEHOLD_DIR/v${revision.toString().padStart(6, '0')}.json"

        fun requestPath(deviceId: String) = "$REQUESTS_DIR/${safeId(deviceId)}.json"

        fun versionOf(name: String): Int? = Regex("""v(\d{6,})\.json""").matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

        fun newId(): String = Uuid.random().toString()

        internal fun safeId(id: String): String {
            require(id.matches(Regex("[A-Za-z0-9-]{8,64}"))) { "Invalid ID" }
            return id
        }

        fun cleanName(name: String): String {
            val n = name.trim().replace(Regex("\\s+"), " ")
            require(n.length in 1..40) { "Name must be 1 to 40 characters" }
            return n
        }

        private fun cleanDevice(name: String) = name.trim().take(60).ifEmpty { "Phone" }

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
