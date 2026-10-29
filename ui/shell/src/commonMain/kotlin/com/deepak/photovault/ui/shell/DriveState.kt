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

    /** SMB servers answering on this Wi-Fi. */
    suspend fun discover(knownHost: String): List<String>

    fun rememberedUserId(): String?
    fun rememberUserId(userId: String?)

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

    suspend fun refresh(): String? = guarded { refreshLocked() }.also { checkedAtMillis = platform.now() }

    /** When the drive was last checked (28 Oct); 0 until this run of the app has checked. Used to check less often. */
    var checkedAtMillis = 0L
        private set

    /** When the list of photos on the drive was last read in full; 0 until read. */
    var libraryReadAtMillis = 0L
        private set

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
            refreshLocked()
        }
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

    /** The person leaves the family from this phone. Their photos on the drive stay. */
    suspend fun leaveFamily(): String? = guarded {
        if (joined == null || isOwner) return@guarded
        val s = ensureStore() ?: return@guarded
        s.leave(platform.deviceId)
        platform.rememberUserId(null)
        refreshLocked()
    }

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
        return runCatching { BackupCatalog(fs, me.userId, myVault()).records() }.getOrNull()
    }

    /**
     * Streams one file of the drive to [sink] (a drive-only photo being opened or shown as a thumbnail). Returns false
     * when the drive can't be reached or the file is gone. Runs next to other drive work, not inside its lock.
     */
    suspend fun readDriveFile(path: String, sink: (ByteArray, Int) -> Unit): Boolean {
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return false
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

    suspend fun loadBackupRecords(force: Boolean = false) {
        if (backupRecordsLoading || (!force && backupRecords != null)) return
        backupRecordsLoading = true
        try {
            // A failed read keeps the list already on screen: an empty folder after a hiccup was a bug (30 Oct).
            myBackupRecords()?.let {
                backupRecords = it
                libraryReadAtMillis = platform.now()
            }
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
        val fs = runCatching { ensureStore(); drive }.getOrNull() ?: return
        runCatching { BackupCatalog(fs, me.userId, myVault()).purgeExpired(platform.now()) }.getOrNull()?.let { driveTrash = it }
    }

    /** Moves backups (by drive path) into the drive's Recently deleted. Returns an error, or null. */
    suspend fun trashBackups(paths: Set<String>): String? {
        val me = joined?.me ?: return "Join your family drive first."
        val records = (backupRecords ?: myBackupRecords() ?: return "Couldn't read the drive.").filter { it.drivePath in paths }
        return guarded {
            val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
            val catalog = BackupCatalog(fs, me.userId, myVault())
            catalog.trash(records, platform.deviceId, platform.now())
            backupRecords = backupRecords?.filterNot { it.drivePath in paths }
            driveTrash = catalog.trashed()
        }
    }

    /** Brings backups back from the drive's Recently deleted ([trashPaths] are where they are now). */
    suspend fun restoreBackups(trashPaths: Set<String>): String? {
        val me = joined?.me ?: return "Join your family drive first."
        val items = driveTrash.orEmpty().filter { it.trashPath in trashPaths }
        return guarded {
            val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
            val catalog = BackupCatalog(fs, me.userId, myVault())
            catalog.restore(items, platform.deviceId, platform.now())
            driveTrash = catalog.trashed()
            backupRecords = catalog.records()
        }
    }

    /** Deletes backups from the drive's Recently deleted for good. */
    suspend fun deleteTrashedForever(trashPaths: Set<String>): String? {
        val me = joined?.me ?: return "Join your family drive first."
        val items = driveTrash.orEmpty().filter { it.trashPath in trashPaths }
        return guarded {
            val fs = ensureStore()?.let { drive } ?: throw IllegalStateException("No drive")
            val catalog = BackupCatalog(fs, me.userId, myVault())
            catalog.deleteForever(items)
            driveTrash = catalog.trashed()
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
            BackupCatalog(fs, me.userId, myVault()).remove(records, platform.deviceId, platform.now())
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
