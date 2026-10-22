package com.deepak.photovault.core.security

import android.content.Context
import android.util.Base64

/** The drive connection as the user typed it. [password] is never logged. */
data class SmbSettings(
    val host: String = "",
    val share: String = "",
    val rootPath: String = "",
    val username: String = "",
    val password: String = "",
    val savePassword: Boolean = false,
    val domain: String = "",
    val requireSigning: Boolean = false,
    val requireEncryption: Boolean = false,
) {
    override fun toString(): String =
        "SmbSettings(host=$host, share=$share, rootPath=$rootPath, username=$username, password=***, savePassword=$savePassword)"
}

/**
 * Drive connection settings, ported from Veyra's SettingsStore (video-specific grouping options removed).
 *
 * The password reaches disk only when "Save password" is on, and then only as AES-GCM ciphertext under
 * a Keystore key. Otherwise it lives in memory for this process only. Backups that run in the
 * background (Phase 5) therefore need "Save password" on; the setup screen must say so.
 */
class SmbCredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val box = SecretBox(KEY_ALIAS)

    @Volatile private var sessionPassword: String = ""

    @Synchronized
    fun load(): SmbSettings {
        val save = prefs.getBoolean(KEY_SAVE, false)
        val password = if (save) {
            prefs.getString(KEY_PASSWORD, null)
                ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                ?.let { box.open(it) }
                ?.toString(Charsets.UTF_8)
                ?: ""
        } else {
            sessionPassword
        }
        return SmbSettings(
            host = prefs.getString(KEY_HOST, "") ?: "",
            share = prefs.getString(KEY_SHARE, "") ?: "",
            rootPath = prefs.getString(KEY_ROOT, "") ?: "",
            username = prefs.getString(KEY_USER, "") ?: "",
            password = password,
            savePassword = save,
            domain = prefs.getString(KEY_DOMAIN, "") ?: "",
            requireSigning = prefs.getBoolean(KEY_SIGNING, false),
            requireEncryption = prefs.getBoolean(KEY_ENCRYPTION, false),
        )
    }

    @Synchronized
    fun save(settings: SmbSettings) {
        // Encrypt before touching preferences, so a Keystore failure leaves the old settings intact.
        val sealed = if (settings.savePassword) {
            Base64.encodeToString(box.seal(settings.password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } else {
            null
        }
        val editor = prefs
            .edit()
            .putString(KEY_HOST, settings.host.trim())
            .putString(KEY_SHARE, settings.share.trim())
            .putString(KEY_ROOT, PathRules.normalizeRoot(settings.rootPath))
            .putString(KEY_USER, settings.username.trim())
            .putBoolean(KEY_SAVE, settings.savePassword)
            .putString(KEY_DOMAIN, settings.domain.trim())
            .putBoolean(KEY_SIGNING, settings.requireSigning)
            .putBoolean(KEY_ENCRYPTION, settings.requireEncryption)
        if (sealed != null) {
            editor.putString(KEY_PASSWORD, sealed)
            sessionPassword = ""
        } else {
            editor.remove(KEY_PASSWORD)
            sessionPassword = settings.password
        }
        editor.apply()
    }

    /** Forgets the drive on this phone, password included. Nothing on the drive changes. */
    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        sessionPassword = ""
    }

    /** Non-secret host only, for reachability checks. Needs no Keystore access. */
    fun host(): String = prefs.getString(KEY_HOST, "") ?: ""

    private companion object {
        const val PREFS = "smb_settings"
        const val KEY_ALIAS = "photovault_smb_password"
        const val KEY_HOST = "host"
        const val KEY_SHARE = "share"
        const val KEY_ROOT = "root"
        const val KEY_USER = "user"
        const val KEY_SAVE = "save_password"
        const val KEY_PASSWORD = "password_enc"
        const val KEY_DOMAIN = "domain"
        const val KEY_SIGNING = "require_signing"
        const val KEY_ENCRYPTION = "require_encryption"
    }
}
