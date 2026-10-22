package com.deepak.photovault

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.household.AlreadySetUp
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
        val profile = SmbProfile(
            host = connection.host.trim(),
            share = connection.share.trim(),
            rootPath = "",
            username = connection.username.trim(),
            password = connection.password,
        )
        val fs = SmbDriveFileSystem(profile, connection.folder.trim().trim('/'))
        try {
            fs.check()
        } catch (e: Throwable) {
            fs.close()
            throw e
        }
        closeOpened()
        opened = fs
        return fs
    }

    /** A separate connection for background backup, so it never closes the one the screens are using. */
    suspend fun openDetached(connection: DriveConnection): SmbDriveFileSystem {
        val fs = SmbDriveFileSystem(profileOf(connection), connection.folder.trim().trim('/'))
        try {
            fs.check()
        } catch (e: Throwable) {
            fs.close()
            throw e
        }
        return fs
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
    }
}
