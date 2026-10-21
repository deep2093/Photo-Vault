package com.deepak.photovault.core.vpn

import android.content.Context
import com.deepak.photovault.core.security.SecretBox
import java.io.File
import java.io.IOException

/**
 * Holds the imported WireGuard `.conf`, which contains a private key. Ported from Veyra; the
 * duplicated Keystore code now lives in [SecretBox] with the same on-disk layout.
 *
 * - Encrypted with AES-256-GCM under a non-exportable Android Keystore key.
 * - Written under [Context.getNoBackupFilesDir], which Android never backs up or transfers.
 * - The plain text is only ever held in memory for the moment a tunnel is built.
 * - Nothing here logs configuration content.
 */
class WireGuardConfigStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.noBackupFilesDir, "wireguard")
    private val file = File(dir, "tunnel.conf.enc")
    private val box = SecretBox(KEY_ALIAS)

    @Synchronized
    fun hasConfig(): Boolean = file.isFile && file.length() > 0

    /** Returns the stored configuration text, or null when none exists or the key was lost. */
    @Synchronized
    fun load(): String? {
        if (!hasConfig()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return box.open(bytes)?.toString(Charsets.UTF_8)
    }

    /** Replaces any existing configuration atomically. */
    @Synchronized
    @Throws(IOException::class)
    fun save(text: String) {
        val encrypted = box.seal(text.toByteArray(Charsets.UTF_8))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create storage folder")
        val tmp = File(dir, "tunnel.conf.enc.tmp")
        tmp.writeBytes(encrypted)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not save configuration")
        }
    }

    /** Deletes the file and the Keystore key so no key material remains. */
    @Synchronized
    fun clear() {
        file.delete()
        File(dir, "tunnel.conf.enc.tmp").delete()
        box.destroyKey()
    }

    private companion object {
        const val KEY_ALIAS = "photovault_wireguard_config"
    }
}

/** Non-secret VPN preferences. Defaults match Veyra: auto-connect off, auto-disconnect on, app-only on. */
class VpnPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vpn_settings", Context.MODE_PRIVATE)

    var autoConnect: Boolean
        get() = prefs.getBoolean("auto_connect", false)
        set(value) { prefs.edit().putBoolean("auto_connect", value).apply() }

    /** Turn the tunnel off when the app is closed, or 5 minutes after it is left, unless a hold is open. */
    var autoDisconnect: Boolean
        get() = prefs.getBoolean("auto_disconnect", true)
        set(value) { prefs.edit().putBoolean("auto_disconnect", value).apply() }

    /** Route only this app through the tunnel (VpnService.Builder.addAllowedApplication). */
    var appOnly: Boolean
        get() = prefs.getBoolean("app_only", true)
        set(value) { prefs.edit().putBoolean("app_only", value).apply() }
}
