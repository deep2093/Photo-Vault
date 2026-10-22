package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepak.photovault.core.drive.DriveFileSystem
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
        space = runCatching { drive?.space() }.getOrNull()
        if (m is Membership.Joined) {
            usage = m.household.members.associate { member ->
                member.userId to MemberUsage(runCatching { s.usedBytes(member.userId) }.getOrNull(), member.limitGb * GB)
            }
        }
        problem = null
    }

    // ---- Setting up and joining ----

    suspend fun createFamily(name: String, pin: String?): String? = guarded {
        ensureStore()!!.create(name, platform.deviceId, platform.deviceName, pin)
        if (pin != null) unlock(pin)
        refreshLocked()
    }

    suspend fun join(newName: String? = null, existingUserId: String? = null): String? = guarded {
        ensureStore()!!.requestJoin(platform.deviceId, platform.deviceName, newName, existingUserId)
        refreshLocked()
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
        const val OWNER_UNLOCK_MS = 5 * 60_000L
    }
}
