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
        // A key already on this phone (made with a PIN for Google, 15 Nov) is kept: it only gets its 24 words and its
        // recovery file for the family drive. A new key would lock the person out of what they already backed up.
        val keys = root() ?: MemberKeys.generate()
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

    override fun createPinKey(pin: String): ByteArray {
        val keys = MemberKeys.generate()
        pending = keys
        return com.deepak.photovault.core.crypto.PinEnvelope.wrap(keys.root, pin)
    }

    override fun restorePinKey(pin: String, envelope: ByteArray): String? {
        val rootBytes = com.deepak.photovault.core.crypto.PinEnvelope.unwrap(envelope, pin)
            ?: return "That password doesn't open your key. Check it and try again."
        return if (keep(MemberKeys(rootBytes))) null else "The key couldn't be kept on this phone."
    }

    override fun rewrapPinKey(pin: String): ByteArray? = root()?.let { com.deepak.photovault.core.crypto.PinEnvelope.wrap(it.root, pin) }

    override fun pinStrong(): Boolean = prefs.getBoolean(PIN_STRONG, false)

    override fun markPinStrong(strong: Boolean) {
        prefs.edit().putBoolean(PIN_STRONG, strong).apply()
    }

    /** The one-time key for asking the recovery helper, while a request is open (sealed in the Keystore like the root). */
    private fun requestKeys(): MemberKeys? =
        prefs.getString(REQUEST, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.let { box.open(it) }?.takeIf { it.size == 32 }?.let { MemberKeys(it) }

    override fun startKeyRequest(): ByteArray? = runCatching {
        val keys = requestKeys() ?: MemberKeys.generate().also { fresh ->
            prefs.edit().putString(REQUEST, Base64.encodeToString(box.seal(fresh.root), Base64.NO_WRAP)).commit()
        }
        MemberIdentity(keys).exchangePublic
    }.getOrNull()

    override fun finishKeyRequest(answer: ByteArray, householdId: String, memberId: String, expectedFingerprint: String): String? {
        val asking = requestKeys() ?: return "This request was cancelled on this phone. Ask again."
        val root = runCatching { HelperWrap.unwrap(answer, MemberIdentity(asking), KeyContext(householdId, memberId)) }.getOrNull()
            ?: return "Your helper's answer couldn't be opened. Ask again."
        val keys = MemberKeys(root)
        // Only your own key is kept: it must match your public identity on the drive.
        if (expectedFingerprint.isNotBlank() && MemberIdentity(keys).fingerprint != expectedFingerprint) {
            return "That isn't your key, so it wasn't kept. Ask your helper again."
        }
        if (!keep(keys)) return "The key couldn't be kept on this phone."
        cancelKeyRequest()
        return null
    }

    override fun cancelKeyRequest() {
        prefs.edit().remove(REQUEST).apply()
    }

    override fun answerKeyRequest(wrapped: ByteArray, requestPublic: ByteArray, householdId: String, ownerId: String): ByteArray? {
        val mine = root() ?: return null
        return runCatching {
            val context = KeyContext(householdId, ownerId)
            val theirs = HelperWrap.unwrap(wrapped, MemberIdentity(mine), context) ?: return null
            HelperWrap.wrap(theirs, requestPublic, context)
        }.getOrNull()
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
        const val PIN_STRONG = "pin_strong"
        const val REQUEST = "helper_request"
    }
}
