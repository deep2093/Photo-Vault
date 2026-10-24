package com.deepak.photovault

import android.content.Context
import android.util.Base64
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.lifecycleScope
import com.deepak.photovault.core.crypto.HelperWrap
import com.deepak.photovault.core.crypto.KeyContext
import com.deepak.photovault.core.crypto.MemberIdentity
import com.deepak.photovault.core.crypto.MemberKeys
import com.deepak.photovault.core.crypto.RecoveryCode
import com.deepak.photovault.core.crypto.RecoveryEnvelope
import com.deepak.photovault.core.security.SecretBox
import com.deepak.photovault.ui.shell.EncryptionPlatform
import com.deepak.photovault.ui.shell.NewPrivateKey
import com.deepak.photovault.ui.shell.PublicIdentity
import kotlinx.coroutines.launch

/**
 * Private backup keys on Android (Phase 4A, 25 Oct). The root key is kept sealed by a non-exportable Android Keystore
 * key in app-private storage (not in Android's cloud backup), usable while the phone is locked so scheduled backups work.
 * The recovery words and the root key never leave this class except as the sealed recovery file.
 */
class AndroidEncryption(private val activity: MainActivity) : EncryptionPlatform {
    private val prefs = activity.getSharedPreferences("pixallery_private_keys", Context.MODE_PRIVATE)
    private val box = SecretBox("pixallery_member_root_v1")
    private var pending: MemberKeys? = null

    override val supported: Boolean get() = true

    private fun root(): MemberKeys? =
        prefs.getString(KEY, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.let { box.open(it) }?.takeIf { it.size == 32 }?.let { MemberKeys(it) }

    override fun hasLocalKey(): Boolean = root() != null

    override fun createKey(householdId: String, memberId: String): NewPrivateKey {
        val keys = MemberKeys.generate()
        val code = RecoveryCode.generate()
        val identity = MemberIdentity(keys)
        pending = keys
        return NewPrivateKey(
            words = RecoveryCode.toWords(code),
            recoveryEnvelope = RecoveryEnvelope.wrap(keys.root, code, KeyContext(householdId, memberId)),
            identity = PublicIdentity.text(identity.signingPublic, identity.exchangePublic, identity.fingerprint),
        )
    }

    private fun keep(keys: MemberKeys): Boolean = runCatching {
        prefs.edit().putString(KEY, Base64.encodeToString(box.seal(keys.root), Base64.NO_WRAP)).commit()
    }.getOrDefault(false) && root() != null

    override fun commitKey(): Boolean {
        val keys = pending ?: return false
        return keep(keys).also { if (it) pending = null }
    }

    override fun discardPending() { pending = null }

    override fun restoreKey(words: String, envelope: ByteArray, householdId: String, memberId: String): String? {
        val code = when (val parsed = RecoveryCode.parse(words)) {
            is RecoveryCode.Parsed.Ok -> parsed.secret
            is RecoveryCode.Parsed.WrongCount -> return "There should be 24 words; you typed ${parsed.found}."
            is RecoveryCode.Parsed.UnknownWord -> return "Word ${parsed.position} (\"${parsed.word}\") isn't one of the recovery words. Check the spelling."
            RecoveryCode.Parsed.BadCheck -> return "These words don't add up. A word is wrong or out of order."
        }
        val rootBytes = RecoveryEnvelope.unwrap(envelope, code, KeyContext(householdId, memberId))
            ?: return "These words don't open your backup key. They may belong to someone else or the key file was changed."
        return if (keep(MemberKeys(rootBytes))) null else "The key couldn't be kept on this phone."
    }

    override fun wrapForHelper(helperExchangePublic: ByteArray, householdId: String, memberId: String): ByteArray? {
        val keys = pending ?: root() ?: return null
        return runCatching { HelperWrap.wrap(keys.root, helperExchangePublic, KeyContext(householdId, memberId)) }.getOrNull()
    }

    override fun saveWordsToPasswordManager(words: List<String>, onResult: (Boolean) -> Unit) {
        activity.lifecycleScope.launch {
            val ok = runCatching {
                CredentialManager.create(activity).createCredential(activity, CreatePasswordRequest("Pixallery recovery words", words.joinToString(" ")))
            }.isSuccess
            onResult(ok)
        }
    }

    override fun forgetKey() {
        prefs.edit().remove(KEY).apply()
        box.destroyKey()
    }

    private companion object {
        const val KEY = "root"
    }
}
