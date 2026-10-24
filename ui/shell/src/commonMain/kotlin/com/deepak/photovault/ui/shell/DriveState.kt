package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepak.photovault.core.backup.BackupCatalog
import com.deepak.photovault.core.backup.BackupCoordinator
import com.deepak.photovault.core.backup.BackupSchedule
import com.deepak.photovault.core.backup.PublishedSchedule
import com.deepak.photovault.core.household.FeedbackBox
import com.deepak.photovault.core.household.FeedbackEntry
import com.deepak.photovault.core.household.FeedbackKind
import com.deepak.photovault.core.household.FeedbackNote
import com.deepak.photovault.core.backup.RestoreEngine
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.drive.DriveSpace
import com.deepak.photovault.core.household.HouseholdSettings
import com.deepak.photovault.core.household.HouseholdStore
import com.deepak.photovault.core.household.JoinRequest
import com.deepak.photovault.core.household.Membership
import com.deepak.photovault.core.household.NotAllowed
import com.deepak.photovault.core.household.OwnerAuth
import com.deepak.photovault.core.household.PinAttempts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How to reach the family drive. The password is kept encrypted on the phone (Keystore) when saved. */
data class DriveConnection(
    val host: String = "",
    val share: String = "",
    /** Folder inside the share for PhotoVault. */
    val folder: String = DEFAULT_FOLDER,
    val username: String = "",
    val password: String = "",
    /** Needed for scheduled backups (Phase 4), so on by default. */
    val savePassword: Boolean = true,
) {
    val isComplete: Boolean get() = host.isNotBlank() && share.isNotBlank()

    override fun toString() = "DriveConnection(host=$host, share=$share, folder=$folder, username=$username, password=***)"

    companion object {
        /** Kept as "PhotoVault" after the rename to Pixallery: families already set up use this folder. */
        const val DEFAULT_FOLDER = "PhotoVault"
    }
}

/** What only the phone can do: saved settings, the SMB client, finding drives, this phone's identity. */
interface DrivePlatform {
    val deviceId: String
    val deviceName: String

    fun loadConnection(): DriveConnection?
    fun saveConnection(connection: DriveConnection)
    fun forgetConnection()

    /** Opens the drive and checks the address, share and password; throws with a readable reason. */
    suspend fun open(connection: DriveConnection): DriveFileSystem

    /** SMB servers answering on this Wi-Fi. */
    suspend fun discover(knownHost: String): List<String>

    fun rememberedUserId(): String?
    fun rememberUserId(userId: String?)

    /** A message safe to show: never includes the password or raw server text. */
    fun describe(error: Throwable): String

    fun now(): Long
}

/** Someone's space on the drive, measured when the Family screen refreshes. */
data class MemberUsage(val usedBytes: Long?, val limitBytes: Long)

/**
 * The family drive as the screens see it: connection, membership, people, space and pending phones.
 * Owner actions are unlocked with the PIN for a few minutes at a time.
 */
@Stable
class DriveState(private val platform: DrivePlatform) {
    var connection by mutableStateOf(platform.loadConnection())
        private set

    var membership by mutableStateOf<Membership?>(null)
        private set

    var usage by mutableStateOf<Map<String, MemberUsage>>(emptyMap())
        private set

    var requests by mutableStateOf<List<JoinRequest>>(emptyList())
        private set

    var space by mutableStateOf<DriveSpace?>(null)
        private set

    /** Last problem reaching the drive, in plain words; null when all is well. */
    var problem by mutableStateOf<String?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    private var drive: DriveFileSystem? = null
    private var store: HouseholdStore? = null
    private val lock = Mutex()
    private var ownerPin by mutableStateOf<String?>(null)
    private var ownerUnlockedAt by mutableStateOf(0L)
    private val pinAttempts = PinAttempts { platform.now() }

    val deviceId: String get() = platform.deviceId

    fun now(): Long = platform.now()
    val isSetUp: Boolean get() = connection?.isComplete == true
    val joined: Membership.Joined? get() = membership as? Membership.Joined
    val isOwner: Boolean get() = joined?.isOwner == true

    /** Owner controls are open: PIN entered in the last few minutes, or no PIN required. */
    val ownerUnlocked: Boolean
        get() {
            val j = joined ?: return false
            if (!j.isOwner) return false
            if (!j.household.settings.requirePin || j.household.ownerPin == null) return true
            return ownerPin != null && platform.now() - ownerUnlockedAt < OWNER_UNLOCK_MS
        }

    fun pinLockedForMillis(): Long = pinAttempts.lockedForMillis()

    // ---- Connecting ----

    /** Tries [candidate]; on success saves it and loads the family. Returns an error message or null. */
    suspend fun connect(candidate: DriveConnection): String? = guarded {
        val fs = platform.open(candidate)
        fs.mkdirs("")
        platform.saveConnection(candidate)
        connection = candidate
        drive = fs
        store = HouseholdStore(fs, platform::now)
        refreshLocked()
    }

    suspend fun discover(): List<String> = runCatching { platform.discover(connection?.host.orEmpty()) }.getOrDefault(emptyList())

    fun forget() {
        platform.forgetConnection()
        platform.rememberUserId(null)
        connection = null
        drive = null
        store = null
        membership = null
        usage = emptyMap()
        requests = emptyList()
        space = null
        lockOwner()
    }

    // ---- Reading ----

    suspend fun refresh(): String? = guarded { refreshLocked() }

    private suspend fun refreshLocked() {
        val s = ensureStore() ?: return
        val m = s.membership(platform.deviceId, platform.rememberedUserId())
        membership = m
        if (m is Membership.Joined) platform.rememberUserId(m.me.userId)
        if (m is Membership.Removed) platform.rememberUserId(null)
        requests = if (m is Membership.Joined && m.isOwner) s.pendingRequests() else emptyList()
        // Keep this phone's name current in the family (older versions saved model codes like "SM-S948B").
        if (m is Membership.Joined) {
            val mine = m.me.devices.firstOrNull { it.deviceId == platform.deviceId }
            if (mine != null && mine.name != platform.deviceName) {
                runCatching { s.renameDevice(platform.deviceId, platform.deviceName) }.getOrNull()?.let { h ->
                    h.memberForDevice(platform.deviceId)?.let { membership = Membership.Joined(h, it) }
                }
            }
        }
        space = runCatching { drive?.space() }.getOrNull()
        refreshActive()
        refreshSpaceRequests(s)
        val current = membership
        if (current is Membership.Joined) {
            privatePhotos = runCatching { s.privatePhotos(current.me.userId) }.getOrDefault(privatePhotos)
            // Limits change at once; the space used is counted separately (below), it is slow.
            usage = current.household.members.associate { member ->
                member.userId to MemberUsage(usage[member.userId]?.usedBytes, member.limitGb * GB)
            }
        }
        problem = null
    }

    private val usageLock = Mutex()
    private var usageCountedAt = 0L

    /**
     * Counts how much of the drive each person uses. It walks every folder on the drive, which takes seconds
     * with thousands of backups, so it runs on its own (23 Oct: it held up the PIN and space dialogs, which
     * waited behind it) and at most every [USAGE_EVERY_MS] unless [force].
     */
    suspend fun refreshUsage(force: Boolean = false) {
        if (!force && platform.now() - usageCountedAt < USAGE_EVERY_MS && usage.values.all { it.usedBytes != null }) return
        if (usageLock.isLocked) return
        usageLock.withLock {
            // Never throws: this runs in the background and the drive may be out of reach (crash 24 Oct).
            val s = try { ensureStore() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null } ?: return
            val members = joined?.household?.members ?: return
            val counted = members.associate { member -> member.userId to runCatching { s.usedBytes(member.userId) }.getOrNull() }
            usage = (joined?.household?.members ?: members).associate { member ->
                member.userId to MemberUsage(counted[member.userId] ?: usage[member.userId]?.usedBytes, member.limitGb * GB)
            }
            usageCountedAt = platform.now()
        }
    }

    // ---- Setting up and joining ----

    suspend fun createFamily(name: String, pin: String?): String? = guarded {
        ensureStore()!!.create(name, platform.deviceId, platform.deviceName, pin)
        if (pin != null) unlock(pin)
        refreshLocked()
    }

    suspend fun join(newName: String? = null, existingUserId: String? = null, ownerPin: String? = null): String? {
        if (ownerPin != null) {
            val locked = pinAttempts.lockedForMillis()
            if (locked > 0) return "Too many tries. Wait ${(locked / 1000) + 1} seconds."
        }
        return guarded {
            try {
                ensureStore()!!.requestJoin(platform.deviceId, platform.deviceName, newName, existingUserId, ownerPin)
                if (ownerPin != null) {
                    pinAttempts.recordSuccess()
                    unlock(ownerPin)
                }
            } catch (e: NotAllowed) {
                if (ownerPin != null) pinAttempts.recordFailure()
                throw e
            }
            refreshLocked()
        }
    }

    suspend fun cancelJoin(): String? = guarded {
        ensureStore()!!.cancelRequest(platform.deviceId)
        refreshLocked()
    }

    // ---- Owner ----

    /** Checks the PIN and opens the owner controls for a few minutes. Returns an error message or null. */
    suspend fun unlockOwner(pin: String): String? {
        val locked = pinAttempts.lockedForMillis()
        if (locked > 0) return "Too many tries. Wait ${(locked / 1000) + 1} seconds."
        return guarded {
            try {
                ensureStore()!!.checkOwner(OwnerAuth(platform.deviceId, pin))
                pinAttempts.recordSuccess()
                unlock(pin)
            } catch (e: NotAllowed) {
                pinAttempts.recordFailure()
                throw e
            }
        }
    }

    fun lockOwner() {
        ownerPin = null
        ownerUnlockedAt = 0
    }

    private fun unlock(pin: String) {
        ownerPin = pin
        ownerUnlockedAt = platform.now()
    }

    private fun auth() = OwnerAuth(platform.deviceId, ownerPin)

    suspend fun approve(request: JoinRequest) = ownerAction { it.approve(auth(), request) }

    suspend fun decline(request: JoinRequest) = ownerAction { it.decline(auth(), request) }

    suspend fun setLimit(userId: String, gb: Int) = ownerAction { it.setLimit(auth(), userId, gb) }

    suspend fun removeMember(userId: String) = ownerAction { it.removeMember(auth(), userId) }

    suspend fun removeDevice(deviceId: String) = ownerAction { it.removeDevice(auth(), deviceId) }

    suspend fun updateSettings(change: (HouseholdSettings) -> HouseholdSettings) = ownerAction { it.updateSettings(auth(), change) }

    suspend fun setPin(newPin: String): String? = ownerAction {
        it.setPin(auth(), newPin)
        unlock(newPin)
    }

    // ---- Profile pictures (24 Oct) ----

    /** Pictures already read from the drive, by path. Paths change with every new picture, so this never goes stale. */
    val photos = androidx.compose.runtime.mutableStateMapOf<String, ByteArray>()

    /** Your own pictures of other members (memberId to path), seen only by you (24 Oct). */
    var privatePhotos by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** The picture to show for [member] on this phone: your own one if you set it, else the shared one. */
    fun photoPathFor(member: com.deepak.photovault.core.household.Member): String? = privatePhotos[member.userId] ?: member.photo

    /** Reads [path] once and keeps it; null while it is loading or if it can't be read. */
    suspend fun loadPhoto(path: String) {
        if (path in photos) return
        val s = runCatching { ensureStore() }.getOrNull() ?: return
        val me = joined?.me?.userId
        val bytes = if (path.startsWith("users/") && me != null) runCatching { s.readPrivatePhoto(me, path) }.getOrNull()
        else runCatching { s.readPhoto(path) }.getOrNull()
        bytes?.let { photos[path] = it }
    }

    /** Your own picture of [memberId], shown only on your phones. No PIN: it changes nothing for anyone else. */
    suspend fun setPrivatePhoto(memberId: String, jpeg: ByteArray): String? = guarded {
        val me = joined?.me?.userId ?: throw NotAllowed("Join the family first")
        ensureStore()!!.setPrivatePhoto(me, memberId, jpeg)
        refreshLocked()
    }

    suspend fun clearPrivatePhoto(memberId: String): String? = guarded {
        val me = joined?.me?.userId ?: throw NotAllowed("Join the family first")
        ensureStore()!!.clearPrivatePhoto(me, memberId)
        refreshLocked()
    }

    /** Someone's own picture, or the owner setting anyone's (owner controls must be open for someone else). */
    suspend fun setPhoto(userId: String, jpeg: ByteArray): String? = guarded {
        if (userId != joined?.me?.userId && !ownerUnlocked) throw NotAllowed("Enter the owner PIN first")
        ensureStore()!!.setPhoto(platform.deviceId, userId, jpeg, ownerPin)
        refreshLocked()
    }

    suspend fun clearPhoto(userId: String): String? = guarded {
        if (userId != joined?.me?.userId && !ownerUnlocked) throw NotAllowed("Enter the owner PIN first")
        ensureStore()!!.clearPhoto(platform.deviceId, userId, ownerPin)
        refreshLocked()
    }

    /** Can this phone change [userId]'s picture right now (own, or owner)? */
    fun canChangePhoto(userId: String): Boolean = joined?.me?.userId == userId || isOwner

    // ---- Backup figures from the drive ----

    /** When each of my phones last backed up (deviceId to time). Empty if the drive can't be read. */
    suspend fun lastBackups(userId: String? = null): Map<String, Long> {
        val who = userId ?: joined?.me?.userId ?: return emptyMap()
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return emptyMap()
        return runCatching { BackupCatalog(fs, who).lastBackups() }.getOrDefault(emptyMap())
    }

    /** Everything this person has backed up, from all their phones' manifests; null if the drive can't be read. */
    suspend fun myBackupRecords(): List<com.deepak.photovault.core.backup.BackupRecord>? {
        val me = joined?.me ?: return null
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return null
        return runCatching { BackupCatalog(fs, me.userId).records() }.getOrNull()
    }

    /** This person's backups on the drive, for Settings > Storage; null until read (or if it can't be). */
    var backupRecords by mutableStateOf<List<com.deepak.photovault.core.backup.BackupRecord>?>(null)
        private set

    var backupRecordsLoading by mutableStateOf(false)
        private set

    suspend fun loadBackupRecords(force: Boolean = false) {
        if (backupRecordsLoading || (!force && backupRecords != null)) return
        backupRecordsLoading = true
        try {
            backupRecords = myBackupRecords()
        } finally {
            backupRecordsLoading = false
        }
    }

    /**
     * Deletes the person's own backups at [paths] from the drive (23 Oct, after a warning). The phone is not
     * touched. Returns an error, or null.
     */
    suspend fun deleteBackups(paths: Set<String>): String? {
        val me = joined?.me ?: return "Join your family drive first."
        val records = (backupRecords ?: myBackupRecords() ?: return "Couldn't read the drive.").filter { it.drivePath in paths }
        return guarded {
            val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
            BackupCatalog(fs, me.userId).remove(records, platform.deviceId, platform.now())
            backupRecords = backupRecords?.filterNot { it.drivePath in paths }
        }
    }

    // ---- Asking for more space (24 Oct) ----

    /** Open requests for more space (the owner's to answer). */
    var spaceRequests by mutableStateOf<List<com.deepak.photovault.core.household.SpaceRequest>>(emptyList())
        private set

    /** This person's own open request, and whether their last one was declined. */
    var mySpaceRequest by mutableStateOf<com.deepak.photovault.core.household.SpaceRequest?>(null)
        private set
    var mySpaceDeclined by mutableStateOf(false)
        private set

    private fun spaceRequestsOn(fs: DriveFileSystem) = com.deepak.photovault.core.household.SpaceRequests(fs, platform::now)

    private suspend fun refreshSpaceRequests(s: HouseholdStore) {
        val j = membership as? Membership.Joined ?: return
        val fs = drive ?: return
        val requests = spaceRequestsOn(fs)
        spaceRequests = if (j.isOwner) runCatching { requests.open(j.household) }.getOrDefault(spaceRequests) else emptyList()
        mySpaceRequest = runCatching { requests.mine(j.household, j.me.userId) }.getOrNull()
        mySpaceDeclined = runCatching { requests.declined(j.household, j.me.userId) }.getOrDefault(false)
    }

    suspend fun askForSpace(wantGb: Int, note: String): String? = guarded {
        val j = joined ?: throw NotAllowed("Join your family drive first.")
        ensureStore()
        spaceRequestsOn(drive!!).ask(j.me, wantGb, note)
        refreshLocked()
    }

    suspend fun cancelSpaceRequest(): String? = guarded {
        val j = joined ?: return@guarded
        ensureStore()
        spaceRequestsOn(drive!!).cancel(j.me.userId)
        refreshLocked()
    }

    /** The owner gives what was asked (the request then closes by itself). */
    suspend fun approveSpace(request: com.deepak.photovault.core.household.SpaceRequest) = setLimit(request.userId, request.wantGb)

    suspend fun declineSpace(request: com.deepak.photovault.core.household.SpaceRequest) = ownerAction { s ->
        spaceRequestsOn(drive!!).decline(joined!!.household, request)
    }

    // ---- Taking turns (24 Oct) ----

    /** People whose phone is backing up right now (a subtle dot on their picture in Family). */
    var backingUpNow by mutableStateOf<Set<String>>(emptySet())
        private set

    private fun coordinator(fs: DriveFileSystem, me: com.deepak.photovault.core.household.Member) =
        BackupCoordinator(fs, me.userId, platform.deviceId, platform.deviceName, platform::now)

    private suspend fun refreshActive() {
        val j = joined ?: return
        val fs = drive ?: return
        backingUpNow = runCatching { coordinator(fs, j.me).activeUsers(j.household.members.map { it.userId }) }.getOrDefault(backingUpNow)
    }

    /** Every other family phone's backup time; null if the drive can't be read. */
    suspend fun otherSchedules(): List<PublishedSchedule>? {
        val j = joined ?: return null
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return null
        return runCatching { coordinator(fs, j.me).otherSchedules(j.household.members.map { it.userId }) }.getOrNull()
    }

    /** Tells the family when this phone backs up, so they can pick another hour. */
    suspend fun publishSchedule(schedule: BackupSchedule, zoneId: String) {
        val j = joined ?: return
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return
        runCatching { coordinator(fs, j.me).publish(schedule, j.me.name, zoneId) }
    }

    // ---- Send feedback (23 Oct) ----

    private val feedbackLock = Mutex()

    /** Notes on this phone still waiting for the drive. */
    var feedbackWaiting by mutableStateOf(0)
        private set

    /**
     * Saves a note on the drive in this person's folder. If the drive can't be reached it waits on the phone
     * and goes with the next drive check. Returns true if it went now.
     */
    suspend fun sendFeedback(kind: FeedbackKind, text: String, files: List<FeedbackFile>, actions: MediaActions): Boolean {
        val me = joined?.me ?: throw NotAllowed("Join your family drive first.")
        val note = FeedbackNote(
            id = "f-${platform.now()}-${kotlin.random.Random.nextInt(0x100000, 0xFFFFFF).toString(16)}",
            kind = kind,
            text = text.trim(),
            fromUserId = me.userId,
            fromName = me.name,
            deviceName = platform.deviceName,
            appVersion = actions.appVersion,
            createdAtMillis = platform.now(),
        )
        val sent = runCatching { deliver(note, files, actions) }.isSuccess
        if (!sent) {
            runCatching { actions.feedbackOutbox.add(note, files) }
                .onFailure { throw NotAllowed("Couldn't reach the drive or keep the note on this phone. Try again later.") }
        }
        feedbackWaiting = actions.feedbackOutbox.all().size
        return sent
    }

    /** Sends notes that were waiting for the drive (called with each drive check). */
    suspend fun flushFeedback(actions: MediaActions) {
        val waiting = actions.feedbackOutbox.all()
        feedbackWaiting = waiting.size
        for ((note, files) in waiting) {
            if (runCatching { deliver(note, files, actions) }.isFailure) break
            actions.feedbackOutbox.remove(note.id)
        }
        feedbackWaiting = actions.feedbackOutbox.all().size
    }

    private suspend fun deliver(note: FeedbackNote, files: List<FeedbackFile>, actions: MediaActions) = feedbackLock.withLock {
        val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
        val sources = files.map { f -> f.name to (actions.openFeedbackFile(f) ?: throw IllegalStateException("File gone")) }
        FeedbackBox(fs).send(note, sources)
    }

    /** Everyone's feedback, for the owner; null if the drive can't be read. */
    suspend fun feedbackInbox(): List<FeedbackEntry>? {
        val household = joined?.takeIf { it.isOwner }?.household ?: return null
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return null
        return runCatching { FeedbackBox(fs).inbox(household.members.map { it.userId }) }.getOrNull()
    }

    /** How many backed-up photos aren't on this phone; null if the drive can't be read. */
    suspend fun missingOnPhone(onPhone: List<MediaItem>): Int? {
        val me = joined?.me ?: return null
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return null
        return runCatching { RestoreEngine(fs, me.userId).missing(onPhone).size }.getOrNull()
    }

    suspend fun renameMe(name: String): String? = guarded {
        val me = joined?.me ?: return@guarded
        ensureStore()!!.rename(platform.deviceId, me.userId, name)
        refreshLocked()
    }

    private suspend fun ownerAction(block: suspend (HouseholdStore) -> Unit): String? = guarded {
        if (!ownerUnlocked) throw NotAllowed("Enter the owner PIN first")
        block(ensureStore()!!)
        refreshLocked()
    }

    // ---- Plumbing ----

    private suspend fun ensureStore(): HouseholdStore? {
        store?.let { return it }
        val c = connection?.takeIf { it.isComplete } ?: return null
        val fs = platform.open(c)
        drive = fs
        // The drive may have been found at a new address (24 Oct); show and use the one that worked.
        platform.loadConnection()?.takeIf { it.isComplete && it.host != c.host }?.let { connection = it }
        return HouseholdStore(fs, platform::now).also { store = it }
    }

    /** Runs [block] one at a time; returns null on success or a readable error. */
    private suspend fun guarded(block: suspend () -> Unit): String? = lock.withLock {
        busy = true
        try {
            block()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotAllowed) {
            e.message
        } catch (e: IllegalArgumentException) {
            e.message ?: "That isn't allowed"
        } catch (e: Throwable) {
            platform.describe(e).also { problem = it }
        } finally {
            busy = false
        }
    }

    companion object {
        const val GB = 1_000_000_000L

        /** Space used is recounted at most this often (it walks the whole drive). */
        const val USAGE_EVERY_MS = 5 * 60_000L
        const val OWNER_UNLOCK_MS = 5 * 60_000L
    }
}
