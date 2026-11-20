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
import com.deepak.photovault.core.drive.DrivePaths
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
        /** New drives use this folder (25 Oct); nobody is asked for a name any more. */
        const val DEFAULT_FOLDER = "Pixallery"

        /** Families set up before the rename to Pixallery use this folder; a phone joining one finds it by itself. */
        const val LEGACY_FOLDER = "PhotoVault"
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

    /**
     * A second connection, apart from [open]'s (21 Nov): photos and thumbnails being fetched use it, so lists, deletes
     * and restores never wait behind them. Null where there is none (the one connection is shared then).
     */
    suspend fun openSecond(connection: DriveConnection): DriveFileSystem? = null

    /** SMB servers answering on this Wi-Fi. */
    suspend fun discover(knownHost: String): List<String>

    /**
     * Whether a drive that stops answering at its saved address is looked for at nearby addresses (17 Nov: a
     * switch, explained during set-up, instead of always on).
     */
    fun findMovedDrive(): Boolean = true
    fun setFindMovedDrive(on: Boolean) = Unit

    fun rememberedUserId(): String?
    fun rememberUserId(userId: String?)

    /** The family as last read from the drive (30 Oct), so Family can show it at once while the drive wakes up. */
    fun rememberedHousehold(): com.deepak.photovault.core.household.Household? = null
    fun rememberHousehold(household: com.deepak.photovault.core.household.Household?) = Unit

    /** This person's backup list as last read (10 Nov), so Storage and Photos show it at once while the drive wakes up. */
    fun rememberedBackupRecords(userId: String): List<com.deepak.photovault.core.backup.BackupRecord>? = null
    fun rememberBackupRecords(userId: String, records: List<com.deepak.photovault.core.backup.BackupRecord>) = Unit

    /** When the kept list was read from the drive (18 Nov), so "read again after three hours" works after a restart. */
    fun rememberedBackupRecordsAt(userId: String): Long = 0L

    /** This person's other devices this one has seen (18 Nov, to say when one is added); null before the first look. */
    fun seenDevices(userId: String): Set<String>? = null
    fun rememberSeenDevices(userId: String, deviceIds: Set<String>) = Unit

    /** Drive photos deleted in the app, still to go to the drive's Recently deleted (18 Nov). Kept on the phone. */
    fun pendingTrash(userId: String): Set<String> = emptySet()
    fun rememberPendingTrash(userId: String, paths: Set<String>) = Unit

    /** The newest feedback note the owner has looked at (25 Oct), for the dot on Settings. */
    fun feedbackSeenAt(): Long = 0L
    fun setFeedbackSeenAt(millis: Long) = Unit

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

    /** Set-up and Settings > Drive: look for the drive at nearby addresses if its address changes (17 Nov). */
    var findMoved by mutableStateOf(platform.findMovedDrive())
        private set

    fun updateFindMoved(on: Boolean) {
        platform.setFindMovedDrive(on)
        findMoved = on
    }

    /** Starts as the family last seen on this phone, if any; it is replaced as soon as the drive answers. */
    var membership by mutableStateOf<Membership?>(rememberedMembership())
        private set

    /** True while [membership] is only the remembered copy and the drive has not been asked yet. */
    var membershipStale by mutableStateOf(membership != null)
        private set

    var usage by mutableStateOf<Map<String, MemberUsage>>(emptyMap())
        private set

    /**
     * Requests to join this device can answer: every one for the owner; for anyone else (18 Nov), only another of their
     * own devices asking to join as them.
     */
    var requests by mutableStateOf<List<JoinRequest>>(emptyList())
        private set

    private fun answerable(all: List<JoinRequest>, m: Membership.Joined): List<JoinRequest> =
        if (m.isOwner) all else all.filter { it.existingUserId == m.me.userId }

    var space by mutableStateOf<DriveSpace?>(null)
        private set

    /** Last problem reaching the drive, in plain words; null when all is well. */
    var problem by mutableStateOf<String?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    private fun rememberedMembership(): Membership? {
        val h = runCatching { platform.rememberedHousehold() }.getOrNull() ?: return null
        val me = platform.rememberedUserId()?.let { h.member(it) } ?: h.memberForDevice(platform.deviceId) ?: return null
        return Membership.Joined(h, me)
    }

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

    /** This person may add photos to the Family Album (Family roles, 16 Nov). */
    val canAddToFamilyAlbum: Boolean get() = joined?.let { it.household.canAddToAlbum(it.me.userId) } == true

    /**
     * True after this phone found it had been removed from the family (16 Nov), so Family can say so. The drive's
     * details were forgotten at that moment.
     */
    var removedFromFamily by mutableStateOf(false)
        private set

    /** Owner controls are open: PIN entered in the last few minutes, or no PIN required. */
    val ownerUnlocked: Boolean
        get() {
            val j = joined ?: return false
            if (!j.isOwner) return false
            if (!j.household.settings.requirePin || j.household.ownerPin == null) return true
            return ownerPin != null && platform.now() - ownerUnlockedAt < OWNER_UNLOCK_MS
        }


    // ---- Connecting ----

    /** Tries [candidate]; on success saves it and loads the family. Returns an error message or null. */
    suspend fun connect(candidate: DriveConnection): String? = guarded {
        removedFromFamily = false
        var use = candidate
        var fs = platform.open(use)
        if (use.folder == DriveConnection.DEFAULT_FOLDER) {
            // No family in the new folder, but one in the old "PhotoVault" folder: this phone is joining that one.
            val here = runCatching { HouseholdStore(fs, platform::now).load() != null }.getOrDefault(false)
            if (!here) {
                val old = use.copy(folder = DriveConnection.LEGACY_FOLDER)
                val oldFs = runCatching { platform.open(old) }.getOrNull()
                if (oldFs != null && runCatching { HouseholdStore(oldFs, platform::now).load() != null }.getOrDefault(false)) {
                    use = old
                    fs = oldFs
                }
            }
        }
        fs.mkdirs("")
        platform.saveConnection(use)
        connection = use
        drive = fs
        store = HouseholdStore(fs, platform::now)
        dropMediaDrive()
        refreshLocked()
    }

    suspend fun discover(): List<String> = runCatching { platform.discover(connection?.host.orEmpty()) }.getOrDefault(emptyList())

    fun forget() {
        platform.forgetConnection()
        platform.rememberUserId(null)
        platform.rememberHousehold(null)
        membershipStale = false
        connection = null
        drive = null
        store = null
        dropMediaDrive()
        catalogCache.clear()
        membership = null
        usage = emptyMap()
        requests = emptyList()
        space = null
        lockOwner()
    }

    // ---- Reading ----

    suspend fun refresh(): String? = guarded { refreshLocked() }.also { checkedAtMillis = platform.now() }

    /** When the drive was last checked (28 Oct); 0 until this run of the app has checked. Used to check less often. */
    var checkedAtMillis by mutableStateOf(0L)
        private set

    /** When the list of photos on the drive was last read in full; 0 until read. */
    var libraryReadAtMillis = 0L
        private set

    private suspend fun refreshLocked() {
        val s = ensureStore() ?: return
        val lastSeen = runCatching { platform.rememberedHousehold() }.getOrNull()
        val m = s.membership(platform.deviceId, platform.rememberedUserId())
        // Removed by the owner (16 Nov): the phone forgets the drive's details, so it can't reach the drive again
        // without a new invite. Only when the family on the drive is newer than the one this phone was last part of,
        // so an old copy of the family file read by mistake never wipes anything.
        if (m is Membership.Removed && lastSeen != null && m.household.householdId == lastSeen.householdId && m.household.revision > lastSeen.revision) {
            platform.rememberedUserId()?.let { forgetBackupList(it) }
            forget()
            removedFromFamily = true
            return
        }
        // Someone new in the family may have used an invite (10 Nov): Family reads its invite list again.
        if ((m as? Membership.Joined)?.household?.members?.size != joined?.household?.members?.size) invitesChanged++
        membership = m
        membershipStale = false
        platform.rememberHousehold((m as? Membership.Joined)?.household)
        if (m is Membership.Joined) platform.rememberUserId(m.me.userId)
        if (m is Membership.Removed) platform.rememberUserId(null)
        requests = if (m is Membership.Joined) answerable(s.pendingRequests(), m) else emptyList()
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
            noticeNewDevices(current)
            loadPendingTrash(current.me.userId)
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
        setupWanted = true
        refreshLocked()
    }.also { if (it == null) refreshUsage(force = true) }

    /** Backing up alone: a family of one with this person as owner and no PIN (25 Oct). Family can be added later. */
    suspend fun startJustMe(): String? = createFamily(platform.deviceName.ifBlank { "Me" }, null)

    suspend fun join(newName: String? = null, existingUserId: String? = null, ownerPin: String? = null): String? {
        if (ownerPin != null) {
            val locked = pinAttempts.lockedForMillis()
            if (locked > 0) return "Too many tries. Wait ${(locked / 1000) + 1} seconds."
        }
        return guarded {
            try {
                ensureStore()!!.requestJoin(platform.deviceId, platform.deviceName, newName, existingUserId, ownerPin)
                setupWanted = true
                if (ownerPin != null) {
                    pinAttempts.recordSuccess()
                    unlock(ownerPin)
                }
            } catch (e: NotAllowed) {
                if (ownerPin != null) pinAttempts.recordFailure()
                throw e
            }
            refreshLocked()
        }.also { if (it == null && joined != null) refreshUsage(force = true) }
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

    /** [viewOnly] (18 Nov): another device of someone in the family that may look at, not delete, their other devices' backups. */
    suspend fun approve(request: JoinRequest, viewOnly: Boolean = false) = ownerAction { s ->
        s.approve(auth(), request, viewOnly)
        // 10 Nov: an invite made for this person isn't waiting any more once they are in.
        val name = request.newName?.trim()?.lowercase()
        val fs = drive
        if (!name.isNullOrEmpty() && fs != null) {
            val board = com.deepak.photovault.core.household.InviteBoard(fs, platform::now)
            runCatching { board.open().filter { it.forName.trim().lowercase() == name }.forEach { board.revoke(it.id) } }
        }
    }.also { if (it == null) invitesChanged++ }

    /** Goes up whenever an invite may have been used or approved, so Family reads its list again. */
    var invitesChanged by mutableStateOf(0)
        private set

    /**
     * Only the requests to join (10 Nov): one small folder, read every 30 s while the owner has the app open. Skipped
     * while other drive work is going on.
     */
    suspend fun checkRequests() {
        val m = joined ?: return
        if (!lock.tryLock()) return
        try {
            val s = runCatching { ensureStore() }.getOrNull() ?: return
            val fresh = runCatching { answerable(s.pendingRequests(), m) }.getOrNull() ?: return
            if (fresh != requests) {
                requests = fresh
                invitesChanged++
            }
        } finally {
            lock.unlock()
        }
    }

    suspend fun decline(request: JoinRequest) = ownerAction { it.decline(auth(), request) }

    /** True for a request this device answers as that person's own device, not as the owner (18 Nov). */
    fun isOwnDeviceRequest(request: JoinRequest): Boolean {
        val me = joined?.me ?: return false
        return request.existingUserId == me.userId
    }

    /** Another of this person's devices let in from this one (18 Nov); no owner PIN needed. */
    suspend fun approveOwnDevice(request: JoinRequest, viewOnly: Boolean): String? = guarded {
        ensureStore()!!.approveOwnDevice(platform.deviceId, request, viewOnly)
        refreshLocked()
    }

    suspend fun declineOwnDevice(request: JoinRequest): String? = guarded {
        ensureStore()!!.declineOwnDevice(platform.deviceId, request)
        refreshLocked()
    }

    suspend fun setLimit(userId: String, gb: Int) = ownerAction { it.setLimit(auth(), userId, gb) }

    // ---- Invites (25 Oct) ----

    /** Owner: makes an invite. Returns its text (to share as file or QR) or a problem. */
    suspend fun makeInvite(code: String?, validForMillis: Long, wireGuard: String?, forName: String): Pair<String?, String?> {
        var text: String? = null
        val error = guarded {
            if (!ownerUnlocked) throw NotAllowed("Enter the owner PIN first")
            val c = connection ?: throw NotAllowed("No drive connected")
            text = ensureStore()!!.createInvite(
                auth(),
                com.deepak.photovault.core.household.InviteDrive(c.host, c.share, c.folder, c.username, c.password),
                code, validForMillis, wireGuard, forName,
            )
        }
        return text to error
    }

    /** Invites not used yet and not run out. */
    suspend fun openInvites(): List<com.deepak.photovault.core.household.InviteMarker> {
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return emptyList()
        return runCatching { com.deepak.photovault.core.household.InviteBoard(fs, platform::now).open().filter { it.expiresAtMillis > platform.now() } }.getOrDefault(emptyList())
    }

    suspend fun revokeInvite(id: String) {
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return
        runCatching { com.deepak.photovault.core.household.InviteBoard(fs, platform::now).revoke(id) }
    }

    /**
     * A new person joins with an invite: unlock it, store the WireGuard file if it came with one, connect to the
     * drive it describes and join straight away. Returns a problem in plain words, or null.
     */
    suspend fun joinWithInvite(text: String, code: String, name: String, applyVpn: (String) -> Unit, startVpn: suspend () -> Boolean = { false }): String? {
        val invite = com.deepak.photovault.core.household.Invite.unlock(text, code)
            ?: return "That code doesn't open this invite. Check the code, or ask for a new invite."
        if (platform.now() > invite.expiresAtMillis) return "This invite has run out. Ask for a new one."
        invite.wireGuard?.let { runCatching { applyVpn(it) } }
        val d = invite.drive
        val link = DriveConnection(d.host, d.share, d.folder, d.username, d.password, true)
        var problem = connect(link)
        // Away from home the drive only answers through the VPN, so the invite's own VPN file is switched on here
        // (Android asks its one-time "Allow VPN?" question) and the connection is tried again (25 Oct).
        if (problem != null && invite.wireGuard != null && startVpn()) problem = connect(link)
        problem?.let {
            return if (invite.wireGuard != null) "$it If you are away from home, check the VPN is on in Settings > Remote access, then try again." else it
        }
        return guarded {
            ensureStore()!!.joinWithInvite(invite, platform.deviceId, platform.deviceName, name)
            setupWanted = true
            refreshLocked()
        }.also { if (it == null) refreshUsage(force = true) }
    }

    // ---- Private backup keys (Phase 4A, 25 Oct) ----

    class KeyFiles(val recovery: ByteArray, val identity: String)

    /** Household and member IDs the keys are bound to, or null when this phone isn't in a family. */
    /** Gives the Private person's vault (their key on this phone), or null for Normal / no key. Set by the app. */
    var vaultProvider: ((householdId: String, memberId: String) -> com.deepak.photovault.core.backup.PrivateVault?)? = null

    fun myVault(): com.deepak.photovault.core.backup.PrivateVault? {
        val (house, me) = keyContext() ?: return null
        return vaultProvider?.invoke(house, me)
    }

    fun keyContext(): Pair<String, String>? = joined?.let { it.household.householdId to it.me.userId }

    private fun keysFolder(userId: String) = "${com.deepak.photovault.core.household.HouseholdStore.userFolder(userId)}/keys"

    /** The recovery file and public identity this person already has on the drive, or null when none. */
    suspend fun readKeyFiles(userId: String? = null): KeyFiles? {
        val who = userId ?: joined?.me?.userId ?: return null
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return null
        return runCatching {
            KeyFiles(fs.read("${keysFolder(who)}/recovery-v1.bin"), fs.read("${keysFolder(who)}/identity-v1.txt").decodeToString())
        }.getOrNull()
    }

    /** The drive was read and has no recovery file for this person (15 Nov). False when the drive can't be reached. */
    suspend fun keyFilesMissing(): Boolean {
        val who = joined?.me?.userId ?: return false
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return false
        return runCatching { !fs.exists("${keysFolder(who)}/recovery-v1.bin") }.getOrDefault(false)
    }

    /** Writes the person's recovery file and public identity. Never replaces what is there. Returns a problem, or null. */
    suspend fun publishKeys(recovery: ByteArray, identity: String): String? = guarded {
        val me = joined?.me?.userId ?: throw NotAllowed("Join the family first")
        val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
        fs.mkdirs(keysFolder(me))
        if (!fs.createNew("${keysFolder(me)}/recovery-v1.bin", recovery)) {
            throw NotAllowed("Private backup is already set up for you on the drive. Use \"Restore my key\" with your 24 words instead.")
        }
        fs.createNew("${keysFolder(me)}/identity-v1.txt", identity.encodeToByteArray())
    }

    /** Family members who also use Private backup, so one of them can be chosen as the recovery helper. */
    class HelperCandidate(val userId: String, val name: String, val identity: PublicIdentity)

    suspend fun helperCandidates(): List<HelperCandidate> {
        val j = joined ?: return emptyList()
        return j.household.members.filter { it.userId != j.me.userId }.mapNotNull { m ->
            val text = readKeyFiles(m.userId)?.identity ?: return@mapNotNull null
            PublicIdentity.parse(text)?.let { HelperCandidate(m.userId, m.name, it) }
        }
    }

    /** Stores the person's key sealed to [helperUserId]'s public key. Only that person can open it. */
    suspend fun publishHelperWrap(helperUserId: String, wrapped: ByteArray): String? = guarded {
        val me = joined?.me?.userId ?: throw NotAllowed("Join the family first")
        val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
        fs.mkdirs(keysFolder(me))
        fs.createNew("${keysFolder(me)}/helper-$helperUserId.bin", wrapped)
    }

    // ---- The recovery helper hands the key back (18 Nov, A1) ----
    // users/<person>/keys/helper-<helper>.bin   the person's key sealed to the helper (made at Private set-up)
    // users/<person>/keys/key-request.bin       a one-time public key from the person's new phone, asking for it back
    // users/<person>/keys/key-answer.bin        the helper's reply: the key sealed to that one-time key

    /** The names of the family members holding a helper copy of my key. */
    suspend fun myHelpers(): List<String> {
        val j = joined ?: return emptyList()
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return emptyList()
        return j.household.members.filter { it.userId != j.me.userId }.filter { m ->
            runCatching { fs.exists("${keysFolder(j.me.userId)}/helper-${m.userId}.bin") }.getOrDefault(false)
        }.map { it.name }
    }

    /** Leaves my one-time public key on the drive, asking my helper for my key. Returns a problem, or null. */
    suspend fun postKeyRequest(requestPublic: ByteArray): String? = guarded {
        val me = joined?.me?.userId ?: throw NotAllowed("Join the family first")
        val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
        fs.mkdirs(keysFolder(me))
        val path = "${keysFolder(me)}/$KEY_REQUEST"
        // The same request again keeps its file; a new one replaces an old one. An old answer is dropped.
        val same = runCatching { fs.exists(path) && fs.read(path).contentEquals(requestPublic) }.getOrDefault(false)
        if (!same) {
            if (fs.exists(path)) fs.delete(path)
            if (fs.exists("${keysFolder(me)}/$KEY_ANSWER")) fs.delete("${keysFolder(me)}/$KEY_ANSWER")
            if (!fs.createNew(path, requestPublic)) throw IllegalStateException("The request couldn't be saved")
        }
    }

    /** My helper's answer, once there is one. */
    suspend fun readKeyAnswer(): ByteArray? {
        val me = joined?.me?.userId ?: return null
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return null
        return runCatching { "${keysFolder(me)}/$KEY_ANSWER".let { if (fs.exists(it)) fs.read(it) else null } }.getOrNull()
    }

    /** Removes my request and its answer (done, or given up). */
    suspend fun clearKeyRequest() {
        val me = joined?.me?.userId ?: return
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return
        listOf(KEY_REQUEST, KEY_ANSWER).forEach { name ->
            runCatching { "${keysFolder(me)}/$name".let { if (fs.exists(it)) fs.delete(it) } }
        }
    }

    /** Someone asking me, as their recovery helper, for their key back. */
    /**
     * A key request: [wrapped] is my helper copy of their key; null (18 Nov) when it is my own request from another of my
     * devices, which this device answers with the key it already holds.
     */
    class KeyRequest(val userId: String, val name: String, val requestPublic: ByteArray, val wrapped: ByteArray?) {
        val own: Boolean get() = wrapped == null
    }

    /**
     * Requests waiting for me: I hold their helper copy, they left a request, and nobody has answered it yet. 18 Nov:
     * also my own, left by another of my devices, when [haveMyKey] (this device holds my private key).
     */
    suspend fun keyRequestsForMe(haveMyKey: Boolean = false): List<KeyRequest> {
        val j = joined ?: return emptyList()
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return emptyList()
        val theirs = j.household.members.filter { it.userId != j.me.userId }.mapNotNull { m ->
            runCatching {
                val folder = keysFolder(m.userId)
                val wrap = "$folder/helper-${j.me.userId}.bin"
                val request = "$folder/$KEY_REQUEST"
                if (!fs.exists(wrap) || !fs.exists(request) || fs.exists("$folder/$KEY_ANSWER")) return@runCatching null
                KeyRequest(m.userId, m.name, fs.read(request), fs.read(wrap))
            }.getOrNull()
        }
        val mine = if (!haveMyKey) null else runCatching {
            val folder = keysFolder(j.me.userId)
            val request = "$folder/$KEY_REQUEST"
            if (!fs.exists(request) || fs.exists("$folder/$KEY_ANSWER")) null else KeyRequest(j.me.userId, j.me.name, fs.read(request), null)
        }.getOrNull()
        return listOfNotNull(mine) + theirs
    }

    /** Leaves my answer for [ownerId]: their key, sealed to their request. Returns a problem, or null. */
    suspend fun answerKeyRequest(ownerId: String, answer: ByteArray): String? = guarded {
        val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
        val path = "${keysFolder(ownerId)}/$KEY_ANSWER"
        if (fs.exists(path)) fs.delete(path)
        if (!fs.createNew(path, answer)) throw IllegalStateException("The answer couldn't be saved")
    }

    // ---- Another device of the same person (18 Nov) ----

    /** The owner let this device look at, but not delete, the backups made by this person's other devices. */
    val viewOnlyHere: Boolean
        get() = joined?.me?.devices?.firstOrNull { it.deviceId == platform.deviceId }?.viewOnly == true

    /** True when this device may delete all of [records]: always its own backups; others' unless it is view only. */
    fun mayDelete(records: Collection<com.deepak.photovault.core.backup.BackupRecord>): Boolean =
        !viewOnlyHere || records.all { it.deviceId == platform.deviceId }

    /** The owner changes what another device of a person may do with their other devices' backups. */
    suspend fun setDeviceViewOnly(deviceId: String, viewOnly: Boolean) = ownerAction { s -> s.setDeviceViewOnly(auth(), deviceId, viewOnly) }

    /** This person's devices this one has already seen, to say when a new one is added (18 Nov). */
    var newDevices by mutableStateOf<List<NewDevice>>(emptyList())
        private set

    /** A device added since this one last looked: [person] is null for one's own, else whose it is (the owner sees all). */
    class NewDevice(val device: com.deepak.photovault.core.household.Device, val person: String?)

    /** Where this device keeps the devices it has seen: one's own, or (the owner, 18 Nov) everyone's. */
    private fun seenKey(j: Membership.Joined) = if (j.isOwner) "all-${j.me.userId}" else j.me.userId

    private fun noticeNewDevices(j: Membership.Joined) {
        // 18 Nov: people let their own new devices in, so the owner is told about everyone's.
        val watched = if (j.isOwner) j.household.members else listOf(j.me)
        val others = watched.flatMap { m ->
            m.devices.filter { it.deviceId != platform.deviceId }.map { NewDevice(it, if (m.userId == j.me.userId) null else m.name) }
        }
        val key = seenKey(j)
        val seen = platform.seenDevices(key)
        if (seen == null) {
            // The first look on this device: what is there now is known, nothing is new.
            platform.rememberSeenDevices(key, others.map { it.device.deviceId }.toSet())
            return
        }
        newDevices = others.filter { it.device.deviceId !in seen }
    }

    // ---- Deleting drive photos without waiting (18 Nov) ----

    /**
     * Drive photos deleted in the app that the drive hasn't moved to its Recently deleted yet (Deepak: "delete it from the
     * app at once; the drive can catch up in the background, or the next time it is reachable"). Kept on the phone, so
     * they stay gone from the app after a restart, and are finished then.
     */
    var pendingTrash by mutableStateOf<Set<String>>(joined?.me?.userId?.let { runCatching { platform.pendingTrash(it) }.getOrNull() }.orEmpty())
        private set
    private var pendingTrashUser: String? = joined?.me?.userId
    private val trashLock = kotlinx.coroutines.sync.Mutex()

    private fun loadPendingTrash(userId: String) {
        if (pendingTrashUser == userId) return
        pendingTrashUser = userId
        pendingTrash = runCatching { platform.pendingTrash(userId) }.getOrDefault(emptySet())
    }

    /** Takes [paths] off the app now and keeps them to delete on the drive. Null, or why it can't (a view-only device). */
    fun trashLater(paths: Set<String>): String? {
        val me = joined?.me ?: return "Join your family drive first."
        if (!mayDelete(backupRecords.orEmpty().filter { it.drivePath in paths })) return VIEW_ONLY_NOTE
        loadPendingTrash(me.userId)
        pendingTrash = pendingTrash + paths
        platform.rememberPendingTrash(me.userId, pendingTrash)
        return null
    }

    /**
     * Moves the waiting deletions to the drive's Recently deleted. True when the drive changed. If the drive can't be
     * reached they stay waiting for the next try.
     */
    suspend fun finishPendingTrash(): Boolean {
        trashLock.withLock {
            val me = joined?.me ?: return false
            loadPendingTrash(me.userId)
            val waiting = pendingTrash
            if (waiting.isEmpty()) return false
            val error = trashBackups(waiting)
            // Out of reach: try again later. A device made view only since can't finish them: they come back on screen.
            if (error != null && error != VIEW_ONLY_NOTE) return false
            pendingTrash = pendingTrash - waiting
            platform.rememberPendingTrash(me.userId, pendingTrash)
            return error == null
        }
    }

    /** The new-device note was read: those devices count as seen. */
    fun newDevicesSeen() {
        val j = joined ?: return
        val key = seenKey(j)
        platform.rememberSeenDevices(key, (platform.seenDevices(key).orEmpty() + newDevices.map { it.device.deviceId }).toSet())
        newDevices = emptyList()
    }

    /**
     * The person leaves the family from this phone. [deleteBackup] (10 Nov): their backup is deleted from the drive
     * too (every file in their own folder, nobody else's); otherwise it stays and the owner can still see it.
     */
    suspend fun leaveFamily(deleteBackup: Boolean = false): String? = guarded {
        val me = joined?.me ?: return@guarded
        if (isOwner) return@guarded
        if (deleteBackup && viewOnlyHere) throw NotAllowed(VIEW_ONLY_NOTE)
        val s = ensureStore() ?: return@guarded
        s.leave(platform.deviceId)
        platform.rememberUserId(null)
        if (deleteBackup) deleteFilesUnder(com.deepak.photovault.core.household.HouseholdStore.userFolder(me.userId))
        forgetBackupList(me.userId)
        refreshLocked()
    }

    /**
     * The owner, alone in the family, closes it (10 Nov): the family's own files on the drive go, so the drive is free
     * for a new family; with [deleteBackup] the owner's backup goes too. Not possible while anyone else is in it.
     */
    suspend fun deleteFamily(deleteBackup: Boolean): String? = guarded {
        val j = joined ?: return@guarded
        if (!j.isOwner) throw NotAllowed("Only the owner can do this")
        if (j.household.members.size > 1) throw NotAllowed("Remove everyone else first")
        if (!ownerUnlocked) throw NotAllowed("Enter the owner PIN first")
        ensureStore() ?: return@guarded
        if (deleteBackup) deleteFilesUnder(com.deepak.photovault.core.household.HouseholdStore.userFolder(j.me.userId))
        // The family itself last, so a stop half way still leaves a family that works.
        deleteFilesUnder("system")
        platform.rememberUserId(null)
        platform.rememberHousehold(null)
        forgetBackupList(j.me.userId)
        lockOwner()
        store = null
        refreshLocked()
    }

    /** Files deleted so far by [deleteFilesUnder], shown while it works; null when nothing is being deleted. */
    var deletingFiles by mutableStateOf<Int?>(null)
        private set

    /** Deletes every file in [folder] and the folders inside it (folders themselves stay, empty). */
    private suspend fun deleteFilesUnder(folder: String) {
        val fs = drive ?: return
        require(folder == "system" || folder.startsWith("users/")) { "Not a Pixallery folder" }
        if (!fs.exists(folder)) return
        deletingFiles = 0
        val folders = mutableListOf(folder)
        try {
            val todo = ArrayDeque(listOf(folder))
            while (todo.isNotEmpty()) {
                val dir = todo.removeFirst()
                for (entry in runCatching { fs.list(dir) }.getOrDefault(emptyList())) {
                    if (entry.name == "." || entry.name == ".." || '/' in entry.name || '\\' in entry.name) continue
                    val path = DrivePaths.join(dir, entry.name)
                    if (entry.isFolder) { todo.addLast(path); folders += path } else {
                        runCatching { fs.delete(path) }
                        deletingFiles = (deletingFiles ?: 0) + 1
                    }
                }
            }
            // Then the emptied folders, deepest first, and the folder itself (10 Nov: none left behind).
            folders.asReversed().forEach { runCatching { fs.deleteEmptyFolder(it) } }
        } finally {
            deletingFiles = null
        }
    }

    private fun forgetBackupList(userId: String) {
        backupRecords = null
        runCatching { platform.rememberBackupRecords(userId, emptyList()) }
    }

    /**
     * Removes a person (16 Nov: properly). Their phones stop backing up and forget the drive's details the next time
     * they look; the spare copies of anyone's Private key left for them to help with are deleted; their additions
     * leave the Family Album. With [deleteBackup] their whole backup on the drive is deleted too.
     */
    suspend fun removeMember(userId: String, deleteBackup: Boolean = false) = ownerAction { s ->
        s.removeMember(auth(), userId)
        runCatching { s.dropHelperKeysFor(userId) }
        if (deleteBackup) deleteFilesUnder(com.deepak.photovault.core.household.HouseholdStore.userFolder(userId))
    }

    /** View only or Can add, for the Family Album (Family roles, 16 Nov). */
    suspend fun setRole(userId: String, role: com.deepak.photovault.core.household.MemberRole) = ownerAction { it.setRole(auth(), userId, role) }

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
        val catalog = catalogFor(fs, me.userId)
        val list = runCatching { catalog.records() }.getOrNull() ?: return null
        lastUsedMillis = platform.now()
        // 10 Nov: lists that couldn't be opened (a Private backup's key not ready, or damaged) never show as "nothing
        // backed up": the last good list stays and the reason is said.
        if (list.isEmpty() && catalog.listsUnreadable > 0) {
            backupListProblem = "Couldn't open your backup list on the drive (${catalog.listsUnreadable} of ${catalog.listsFound} parts). " +
                if (myVault() == null) "Your Private backup key isn't ready on this phone yet." else "Try again on the home Wi-Fi."
            return null
        }
        backupListProblem = null
        return list
    }

    /** Why the backup list couldn't be read, or null. */
    var backupListProblem by mutableStateOf<String?>(null)
        private set

    // ---- Keeping the drive quick (21 Nov, Deepak: "deleted photo didn't appear in Recently deleted until I refreshed,
    // restored one didn't come back in Backup; manual refresh takes too long; sometimes only a restart showed them") ----

    /** List files and trash notes already read (they never change once written), so each is read only once. */
    private val catalogCache = com.deepak.photovault.core.backup.CatalogCache()
    private var catalogCacheUser: String? = null

    private fun catalogFor(fs: DriveFileSystem, userId: String): BackupCatalog {
        if (catalogCacheUser != userId) {
            catalogCache.clear()
            catalogCacheUser = userId
        }
        return BackupCatalog(fs, userId, myVault(), catalogCache)
    }

    /**
     * Bumped by every change this app makes to the backup list or the drive's Recently deleted. A read that started
     * before a change may have missed it, so it never replaces the newer list; it reads again instead.
     */
    private var recordsVersion = 0
    private var trashVersion = 0

    /** A read asked for while one is already under way runs again after it, instead of being skipped. */
    private var recordsAgain = false
    private var trashLoading = false
    private var trashAgain = false

    /** Photos and thumbnails being fetched use a connection of their own, so the lists never wait behind them. */
    private var mediaDrive: DriveFileSystem? = null
    private val mediaLock = Mutex()

    /** No second connection on this platform, or the last try failed this long ago (tried again after a while). */
    private var secondUnsupported = false
    private var secondFailedAt = 0L

    private suspend fun mediaFs(): DriveFileSystem? {
        mediaDrive?.let { return it }
        if (secondUnsupported || platform.now() - secondFailedAt < SECOND_RETRY_MS) return null
        val c = connection?.takeIf { it.isComplete } ?: return null
        return mediaLock.withLock {
            mediaDrive ?: try {
                platform.openSecond(c).also {
                    if (it == null) secondUnsupported = true
                    mediaDrive = it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                secondFailedAt = platform.now()
                null
            }
        }
    }

    private fun dropMediaDrive() {
        val old = mediaDrive ?: return
        mediaDrive = null
        runCatching { (old as? AutoCloseable)?.close() }
    }

    /** When this app last used the drive. */
    private var lastUsedMillis = 0L

    /**
     * After time away (the app in the background, the phone asleep) the drive has usually dropped the connection, and
     * using it would wait for a timeout first; it is opened afresh instead (21 Nov: "only a restart showed them").
     */
    fun freshenAfterIdle() {
        val now = platform.now()
        if (lastUsedMillis != 0L && now - lastUsedMillis > IDLE_FRESH_MS) {
            store = null
            drive = null
            dropMediaDrive()
        }
    }

    /**
     * Streams one file of the drive to [sink] (a drive-only photo being opened or shown as a thumbnail). Returns false
     * when the drive can't be reached or the file is gone. Runs next to other drive work, not inside its lock.
     */
    suspend fun readDriveFile(path: String, sink: (ByteArray, Int) -> Unit): Boolean {
        val fs = mediaFs() ?: runCatching { ensureStore(); drive }.getOrNull() ?: return false
        return try {
            val kind = when {
                path.endsWith(".pxo") -> com.deepak.photovault.core.backup.ObjectKind.MEDIA
                path.endsWith(".pxt") -> com.deepak.photovault.core.backup.ObjectKind.THUMB
                else -> null
            }
            if (kind == null) {
                fs.readStream(path, sink)
            } else {
                // An encrypted file is checked and decrypted on the way; without this person's key it can't be shown.
                val vault = myVault() ?: return false
                vault.decrypt(kind, com.deepak.photovault.core.backup.PrivatePaths.idOf(path), { cipher -> fs.readStream(path, cipher) }, sink)
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** This person's backups on the drive, for Settings > Storage; null until read (or if it can't be). */
    var backupRecords by mutableStateOf<List<com.deepak.photovault.core.backup.BackupRecord>?>(null)
        private set

    var backupRecordsLoading by mutableStateOf(false)
        private set

    /**
     * The list kept on the phone, shown the moment the app opens, without touching the drive (18 Nov, Deepak: "after a
     * restart I have to pull down to see my backup photos"). Before, it was only loaded with a fresh read, which the app
     * does after a backup or once the list is three hours old, and that clock started again at every restart.
     */
    fun showRememberedBackups() {
        if (backupRecords != null) return
        val me = joined?.me?.userId ?: return
        runCatching { platform.rememberedBackupRecords(me) }.getOrNull()?.let {
            backupRecords = it
            if (libraryReadAtMillis == 0L) libraryReadAtMillis = runCatching { platform.rememberedBackupRecordsAt(me) }.getOrDefault(0L)
        }
    }

    suspend fun loadBackupRecords(force: Boolean = false) {
        if (!force && backupRecords != null) return
        // 21 Nov: asked for while a read is under way, it reads again after that one (it was skipped, so a pull to
        // refresh could bring back nothing new).
        if (backupRecordsLoading) {
            recordsAgain = true
            return
        }
        // 10 Nov: the list last read shows straight away, then the drive's fresh one replaces it.
        val me = joined?.me?.userId
        if (backupRecords == null && me != null) runCatching { platform.rememberedBackupRecords(me) }.getOrNull()?.let { backupRecords = it }
        backupRecordsLoading = true
        try {
            var rounds = 0
            do {
                recordsAgain = false
                val version = recordsVersion
                // A failed read keeps the list already on screen: an empty folder after a hiccup was a bug (30 Oct).
                val fresh = myBackupRecords()
                if (fresh != null) {
                    if (version == recordsVersion) {
                        backupRecords = fresh
                        libraryReadAtMillis = platform.now()
                        if (me != null) runCatching { platform.rememberBackupRecords(me, fresh) }
                    } else {
                        // Something was deleted or brought back while this read was under way: read again.
                        recordsAgain = true
                    }
                }
            } while (recordsAgain && ++rounds < MAX_REREADS)
        } finally {
            backupRecordsLoading = false
        }
    }

    // ---- Family Album (Phase 5A, 25 Oct) ----

    /** Photos in the Family Album (everyone's), or null until read. */
    var familyAlbumRecords by mutableStateOf<List<com.deepak.photovault.core.backup.BackupRecord>?>(null)
        private set

    suspend fun loadFamilyAlbum() {
        val j = joined ?: return
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return
        runCatching { com.deepak.photovault.core.backup.FamilyAlbum(fs, j.me.userId).records(j.household.members.map { it.userId }) }
            .getOrNull()?.let { familyAlbumRecords = it }
    }

    /** What happened when photos were offered to the Family Album. */
    data class FamilyAddResult(val added: Int, val notBackedUp: Int, val error: String? = null)

    /**
     * Adds [items] (phone photos that are backed up, or drive photos of your own) to the Family Album. The album keeps
     * a pointer to the backed-up file, so a photo that isn't backed up yet has to wait for its backup.
     */
    suspend fun addToFamilyAlbum(items: List<MediaItem>): FamilyAddResult {
        val j = joined ?: return FamilyAddResult(0, items.size, "Join your family drive first.")
        // Family roles (16 Nov): View only can see the album but not add to it.
        if (!j.household.canAddToAlbum(j.me.userId)) return FamilyAddResult(0, items.size, "The owner has set you to View only, so you can't add photos to the Family Album.")
        val records = backupRecords ?: myBackupRecords() ?: return FamilyAddResult(0, items.size, "Couldn't read the drive.")
        val byPath = records.associateBy { it.drivePath }
        val byKey = records.associateBy { it.key }
        val mine = "${com.deepak.photovault.core.household.HouseholdStore.userFolder(j.me.userId)}/"
        val found = items.mapNotNull { item ->
            val path = com.deepak.photovault.core.backup.DriveTimeline.pathOf(item.uri)
            (if (path != null) byPath[path] else byKey[com.deepak.photovault.core.backup.BackupSelection.key(item)])
                ?.takeIf { it.drivePath.startsWith(mine) }
        }.distinctBy { it.drivePath }
        if (found.isEmpty()) return FamilyAddResult(0, items.size)
        if (found.any { it.encrypted }) {
            // A Private photo is readable only with its owner's key, so the others in the family couldn't open it.
            return FamilyAddResult(0, items.size, "Private photos can't be shared to the Family Album yet.")
        }
        val error = guarded {
            val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
            com.deepak.photovault.core.backup.FamilyAlbum(fs, j.me.userId).add(found, platform.now())
        }
        if (error == null) loadFamilyAlbum()
        return FamilyAddResult(if (error == null) found.size else 0, items.size - found.size, error)
    }

    /** Takes [items] you added out of the Family Album. Returns an error, or null. */
    suspend fun removeFromFamilyAlbum(items: List<MediaItem>): String? {
        val j = joined ?: return "Join your family drive first."
        val paths = items.mapNotNull { com.deepak.photovault.core.backup.DriveTimeline.pathOf(it.uri) }
        val error = guarded {
            val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
            com.deepak.photovault.core.backup.FamilyAlbum(fs, j.me.userId).remove(paths, platform.now())
        }
        if (error == null) loadFamilyAlbum()
        return error
    }

    /** What is in the drive's Recently deleted (30 days); null until read. */
    var driveTrash by mutableStateOf<List<com.deepak.photovault.core.backup.TrashedBackup>?>(null)
        private set

    /** Reads the drive's Recently deleted and clears what is older than 30 days. */
    suspend fun loadDriveTrash() {
        val me = joined?.me ?: return
        if (trashLoading) {
            trashAgain = true
            return
        }
        trashLoading = true
        try {
            var rounds = 0
            do {
                trashAgain = false
                val version = trashVersion
                val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return
                val fresh = runCatching { catalogFor(fs, me.userId).purgeExpired(platform.now()) }.getOrNull()
                if (fresh != null) {
                    lastUsedMillis = platform.now()
                    // A read that started before a delete or a restore here would put back the old list.
                    if (version == trashVersion) driveTrash = fresh else trashAgain = true
                }
            } while (trashAgain && ++rounds < MAX_REREADS)
        } finally {
            trashLoading = false
        }
    }

    /** Moves backups (by drive path) into the drive's Recently deleted. Returns an error, or null. */
    suspend fun trashBackups(paths: Set<String>): String? {
        val me = joined?.me ?: return "Join your family drive first."
        val records = (backupRecords ?: myBackupRecords() ?: return "Couldn't read the drive.").filter { it.drivePath in paths }
        if (!mayDelete(records)) return VIEW_ONLY_NOTE
        return guarded {
            val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
            val catalog = catalogFor(fs, me.userId)
            val notes = catalog.trash(records, platform.deviceId, platform.now())
            // 21 Nov: the lists change straight away from what was just done, instead of after reading the whole drive.
            // If not everything went as asked (a second try after a dropped connection), both are read again; the
            // files already read are remembered, so that is quick too.
            val shown = driveTrash
            if (notes.size == records.size && shown != null) {
                val gone = notes.map { it.record.drivePath }.toSet()
                backupRecords = backupRecords?.filterNot { it.drivePath in gone }
                driveTrash = (notes + shown).distinctBy { it.trashPath }.sortedByDescending { it.deletedAtMillis }
            } else {
                backupRecords = catalog.records()
                driveTrash = catalog.trashed()
            }
            recordsVersion++
            trashVersion++
        }
    }

    /** Brings backups back from the drive's Recently deleted ([trashPaths] are where they are now). */
    suspend fun restoreBackups(trashPaths: Set<String>): String? {
        val me = joined?.me ?: return "Join your family drive first."
        return guarded {
            val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
            val catalog = catalogFor(fs, me.userId)
            // The list on screen if there is one; otherwise read it (a restore never does nothing for want of it).
            val items = (driveTrash ?: catalog.trashed()).filter { it.trashPath in trashPaths }
            val back = catalog.restore(items, platform.deviceId, platform.now())
            // 21 Nov: back in the lists straight away (it read the whole backup list again first, so it came back late).
            if (back.size == items.size && driveTrash != null && backupRecords != null) {
                val returned = back.map { it.trashPath }.toSet()
                val paths = back.map { it.record.drivePath }.toSet()
                driveTrash = driveTrash?.filterNot { it.trashPath in returned }
                backupRecords = backupRecords?.let { list -> list.filterNot { it.drivePath in paths } + back.map { it.record } }
            } else {
                driveTrash = catalog.trashed()
                backupRecords = catalog.records()
            }
            trashVersion++
            recordsVersion++
        }
    }

    /** Deletes backups from the drive's Recently deleted for good. */
    suspend fun deleteTrashedForever(trashPaths: Set<String>): String? {
        val me = joined?.me ?: return "Join your family drive first."
        val items = driveTrash.orEmpty().filter { it.trashPath in trashPaths }
        if (!mayDelete(items.map { it.record })) return VIEW_ONLY_NOTE
        return guarded {
            val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
            catalogFor(fs, me.userId).deleteForever(items)
            val gone = items.map { it.trashPath }.toSet()
            driveTrash = driveTrash?.filterNot { it.trashPath in gone }
            trashVersion++
        }
    }

    /**
     * Deletes the person's own backups at [paths] from the drive (23 Oct, after a warning). The phone is not
     * touched. Returns an error, or null.
     */
    suspend fun deleteBackups(paths: Set<String>): String? {
        val me = joined?.me ?: return "Join your family drive first."
        val records = (backupRecords ?: myBackupRecords() ?: return "Couldn't read the drive.").filter { it.drivePath in paths }
        if (!mayDelete(records)) return VIEW_ONLY_NOTE
        return guarded {
            val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
            catalogFor(fs, me.userId).remove(records, platform.deviceId, platform.now())
            backupRecords = backupRecords?.filterNot { it.drivePath in paths }
            recordsVersion++
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

    /** How many received notes the owner hasn't looked at yet (the dot on Settings). */
    var feedbackNew by mutableStateOf(0)
        private set

    /** Owner only: counts notes newer than the last one looked at. */
    suspend fun refreshFeedbackDot() {
        if (!isOwner) {
            feedbackNew = 0
            return
        }
        val seen = platform.feedbackSeenAt()
        feedbackNew = feedbackInbox()?.count { it.note.createdAtMillis > seen } ?: return
    }

    /** The owner opened the inbox: everything shown is now seen. */
    fun markFeedbackSeen(entries: List<FeedbackEntry>) {
        val newest = entries.maxOfOrNull { it.note.createdAtMillis } ?: return
        if (newest > platform.feedbackSeenAt()) platform.setFeedbackSeenAt(newest)
        feedbackNew = 0
    }

    /** Everyone's feedback, for the owner; null if the drive can't be read. */
    suspend fun feedbackInbox(): List<FeedbackEntry>? {
        val household = joined?.takeIf { it.isOwner }?.household ?: return null
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return null
        return runCatching { FeedbackBox(fs).inbox(household.members.map { it.userId }) }.getOrNull()
    }

    /** The owner clears everything received. Returns a problem message, or null. */
    suspend fun clearFeedback(entries: List<FeedbackEntry>): String? = guarded {
        if (joined?.isOwner != true) return@guarded
        ensureStore()
        FeedbackBox(drive!!).clear(entries)
    }

    /** How many backed-up photos aren't on this phone; null if the drive can't be read. */
    suspend fun missingOnPhone(onPhone: List<MediaItem>): Int? {
        val me = joined?.me ?: return null
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return null
        return runCatching { RestoreEngine(fs, me.userId, vault = myVault()).missing(onPhone).size }.getOrNull()
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
        platform.loadConnection()?.takeIf { it.isComplete && it.host != c.host }?.let {
            connection = it
            // The photos' own connection still points at the old address.
            dropMediaDrive()
        }
        return HouseholdStore(fs, platform::now).also { store = it }
    }

    /** Runs [block] one at a time; returns null on success or a readable error. */
    private suspend fun guarded(block: suspend () -> Unit): String? = lock.withLock {
        busy = true
        try {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotAllowed) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (_: Throwable) {
                // 10 Nov: the connection the screens share can go stale (approving a phone failed until the app was
                // restarted). Drop it and try once more with a fresh one.
                store = null
                drive = null
                block()
            }
            lastUsedMillis = platform.now()
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

    /**
     * Set when this phone starts a family or asks to join one (10 Nov): the first-time backup questions are asked
     * then, and only then (not just because the app opened).
     */
    var setupWanted by mutableStateOf(false)

    companion object {
        const val GB = 1_000_000_000L

        /** Space used is recounted at most this often (it walks the whole drive). */
        const val USAGE_EVERY_MS = 5 * 60_000L
        const val OWNER_UNLOCK_MS = 5 * 60_000L

        /** After this long without using the drive, its connection is opened afresh rather than trusted (21 Nov). */
        const val IDLE_FRESH_MS = 5 * 60_000L

        /** How many times a list is read again when the app changed it during a read. */
        const val MAX_REREADS = 3

        /** A second connection that couldn't be opened is tried again after this long; the first one is used meanwhile. */
        const val SECOND_RETRY_MS = 30_000L
    }
}

/** Said when a view-only device tries to delete another device's backups (18 Nov). */
internal const val VIEW_ONLY_NOTE = "This device can look at the backups from your other devices, but not delete them. The owner can change that in Family."

private const val KEY_REQUEST = "key-request.bin"
private const val KEY_ANSWER = "key-answer.bin"
