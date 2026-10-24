package com.deepak.photovault

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.household.AlreadySetUp
import com.deepak.photovault.core.household.HouseholdStore
import com.deepak.photovault.core.security.SmbCredentialStore
import com.deepak.photovault.core.security.SmbSettings
import com.deepak.photovault.core.smb.LanDiscovery
import com.deepak.photovault.core.smb.SmbDriveFileSystem
import com.deepak.photovault.core.smb.SmbErrors
import com.deepak.photovault.core.smb.SmbProfile
import com.deepak.photovault.ui.shell.DriveConnection
import com.deepak.photovault.ui.shell.DrivePlatform
import java.util.UUID

/**
 * The phone side of the family drive: the SMB client, the saved connection (password encrypted with a
 * Keystore key, and only when "Remember password" is on), and this phone's identity in the family.
 */
class AndroidDrivePlatform(context: Context) : DrivePlatform {
    private val context = context.applicationContext
    private val credentials = SmbCredentialStore(this.context)
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var opened: SmbDriveFileSystem? = null

    /** A random ID made on first use; stays the same until the app's data is cleared. */
    override val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }
    }

    /**
     * The phone's own name as its owner sees it in Android settings (for example "Galaxy S26 Ultra"), falling back
     * to maker and model code (24 Oct: "Samsung SM-S948B" meant nothing to people).
     */
    override val deviceName: String
        get() {
            val friendly = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() }
            if (friendly != null) return friendly.take(40)
            val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val name = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
            return name.take(40)
        }

    override fun loadConnection(): DriveConnection? {
        val s = credentials.load()
        if (s.host.isBlank() || s.share.isBlank()) return null
        return DriveConnection(
            host = s.host,
            share = s.share,
            folder = s.rootPath.ifBlank { DriveConnection.DEFAULT_FOLDER },
            username = s.username,
            password = s.password,
            savePassword = s.savePassword,
        )
    }

    override fun saveConnection(connection: DriveConnection) {
        credentials.save(
            SmbSettings(
                host = connection.host,
                share = connection.share,
                rootPath = connection.folder,
                username = connection.username,
                password = connection.password,
                savePassword = connection.savePassword,
            ),
        )
    }

    override fun forgetConnection() {
        closeOpened()
        credentials.clear()
    }

    override suspend fun open(connection: DriveConnection): DriveFileSystem {
        val fs = openChecked(connection)
        closeOpened()
        opened = fs
        return fs
    }

    /** A separate connection for background backup, so it never closes the one the screens are using. */
    suspend fun openDetached(connection: DriveConnection): SmbDriveFileSystem = openChecked(connection)

    /**
     * Opens the drive at its saved address. If nothing answers there (24 Oct: the drive sometimes moves between
     * 192.168.71.250 and .249), it looks for it on the same home network, nearest addresses first, and keeps
     * the new address once the same share and sign-in open there and the family's folder is on it.
     *
     * Only addresses in the same network as the saved one are tried, and only while the phone is on that
     * network, so the sign-in is never offered to servers on someone else's Wi-Fi.
     */
    private suspend fun openChecked(connection: DriveConnection): SmbDriveFileSystem {
        val first = try {
            return tryOpen(connection)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            e
        }
        val saved = connection.host.trim()
        if (!SmbErrors.isUnreachable(first) || !isIpv4(saved) || !onSameNetwork(saved)) throw first
        val found = runCatching { LanDiscovery.around(context, saved) }.getOrDefault(emptyList())
        for (host in SmbErrors.nearbyFirst(saved, found).take(MAX_TRIES)) {
            val moved = connection.copy(host = host)
            val fs = try {
                tryOpen(moved).also { fs ->
                    // The family's own folder must be there, or it isn't our drive.
                    if (!fs.exists(HouseholdStore.HOUSEHOLD_DIR)) {
                        fs.close()
                        throw IllegalStateException("Not the family drive")
                    }
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                continue
            }
            saveConnection(moved)
            return fs
        }
        throw first
    }

    private suspend fun tryOpen(connection: DriveConnection): SmbDriveFileSystem {
        val fs = SmbDriveFileSystem(profileOf(connection), connection.folder.trim().trim('/'))
        try {
            fs.check()
        } catch (e: Throwable) {
            fs.close()
            throw e
        }
        return fs
    }

    private fun isIpv4(host: String) = host.split('.').let { p -> p.size == 4 && p.all { (it.toIntOrNull() ?: -1) in 0..255 } }

    /**
     * The phone is on the same network as [host] right now (home Wi-Fi, or the VPN later), using the network's
     * real size: a home network like 192.168.68.0/22 covers .68.x to .71.x (24 Oct).
     */
    private fun onSameNetwork(host: String): Boolean {
        val target = ipv4ToInt(host) ?: return false
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        return networks.any { n ->
            cm.getLinkProperties(n)?.linkAddresses.orEmpty().any { la ->
                val mine = (la.address as? java.net.Inet4Address)?.hostAddress?.let(::ipv4ToInt) ?: return@any false
                // Never wider than /16, so a phone on some huge network doesn't count as "home".
                SmbErrors.sameNetwork(mine, target, la.prefixLength.coerceIn(16, 32))
            }
        }
    }

    private fun ipv4ToInt(ip: String): Int? {
        val p = ip.split('.').map { it.toIntOrNull() ?: return null }
        if (p.size != 4 || p.any { it !in 0..255 }) return null
        return (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
    }

    private fun profileOf(connection: DriveConnection) = SmbProfile(
        host = connection.host.trim(),
        share = connection.share.trim(),
        rootPath = "",
        username = connection.username.trim(),
        password = connection.password,
    )

    override suspend fun discover(knownHost: String): List<String> = LanDiscovery.find(context, knownHost).hosts

    override fun rememberedUserId(): String? = prefs.getString(KEY_USER, null)

    override fun rememberUserId(userId: String?) {
        prefs.edit().apply { if (userId == null) remove(KEY_USER) else putString(KEY_USER, userId) }.apply()
    }

    override fun describe(error: Throwable): String = when (error) {
        is AlreadySetUp -> "This drive already has a Pixallery family. Join it instead."
        is DriveNotFound -> "Something on the drive is missing. Refresh and try again."
        is IllegalStateException -> error.message?.takeIf { it.startsWith("The family settings are busy") } ?: SmbErrors.describe(error)
        else -> SmbErrors.describe(error)
    }

    override fun now(): Long = System.currentTimeMillis()

    private fun closeOpened() {
        runCatching { opened?.close() }
        opened = null
    }

    private companion object {
        const val PREFS = "family"
        const val KEY_DEVICE = "device_id"
        const val KEY_USER = "user_id"

        /** At most this many other addresses are tried before giving up. */
        const val MAX_TRIES = 6
    }
}
