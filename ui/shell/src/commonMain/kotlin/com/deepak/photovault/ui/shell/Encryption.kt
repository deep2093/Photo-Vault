package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.io.encoding.Base64

/** A brand new private key, shown to the person once. Nothing is kept on the phone until [EncryptionPlatform.commitKey]. */
class NewPrivateKey(
    val words: List<String>,
    /** The root key sealed under the recovery words: goes to `users/<id>/keys/recovery-v1.bin` on the drive. */
    val recoveryEnvelope: ByteArray,
    /** Public keys only (see [PublicIdentity]): `users/<id>/keys/identity-v1.txt`. */
    val identity: String,
)

/** What others may see of a person's private-backup identity: public keys and a short fingerprint. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class PublicIdentity(val exchangePublic: ByteArray, val fingerprint: String) {
    companion object {
        fun text(signing: ByteArray, exchange: ByteArray, fingerprint: String): String =
            "pixallery-identity-1\nsigning=${Base64.UrlSafe.encode(signing)}\nexchange=${Base64.UrlSafe.encode(exchange)}\nfingerprint=$fingerprint\n"

        fun parse(text: String): PublicIdentity? {
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != "pixallery-identity-1") return null
            fun field(name: String) = lines.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.trim()
            val exchange = field("exchange")?.let { runCatching { Base64.UrlSafe.decode(it) }.getOrNull() } ?: return null
            return PublicIdentity(exchange, field("fingerprint").orEmpty())
        }
    }
}

/** What the phone does for private backup: keys, the Keystore, the password manager (Phase 4A, 25 Oct). Android: Tink + Keystore. */
interface EncryptionPlatform {
    val supported: Boolean get() = false

    /** This phone holds the person's private key. */
    fun hasLocalKey(): Boolean = false

    /** Makes a new key and its 24 words. Nothing is kept until [commitKey]. */
    fun createKey(householdId: String, memberId: String): NewPrivateKey? = null

    /** Keeps the key made by [createKey] on this phone (Android Keystore) once the drive has the recovery file. */
    fun commitKey(): Boolean = false

    fun discardPending() = Unit

    /** Opens [envelope] with the typed words and keeps the key on this phone. Null when it worked, else the problem in plain words. */
    fun restoreKey(words: String, envelope: ByteArray, householdId: String, memberId: String): String? = "Not available on this device."

    /** The person's key sealed to a helper's public key, or null when there is no key on this phone. */
    fun wrapForHelper(helperExchangePublic: ByteArray, householdId: String, memberId: String): ByteArray? = null

    /** Offers the words to the phone's password manager (Samsung Pass, Google Password Manager...). It may decline. */
    fun saveWordsToPasswordManager(words: List<String>, onResult: (Boolean) -> Unit) = onResult(false)

    /**
     * Google only (14 Nov): makes a new key (kept back until [commitKey]) and returns it sealed under [pin], for the
     * spare copy in the person's Google Drive. Slow on purpose (about a second).
     */
    fun createPinKey(pin: String): ByteArray? = null

    /** Opens the spare copy from Google with the PIN and keeps the key on this phone. Null when it worked. */
    fun restorePinKey(pin: String, envelope: ByteArray): String? = "Not available on this device."

    /** The key already on this phone sealed under a new Google password (18 Nov, A2: changing it). Null without a key. */
    fun rewrapPinKey(pin: String): ByteArray? = null

    /** The Google password was set, or last opened, under the stronger rules (18 Nov); false for an older short PIN. */
    fun pinStrong(): Boolean = true

    fun markPinStrong(strong: Boolean) = Unit

    // ---- The recovery helper hands the key back (18 Nov, A1) ----

    /**
     * Starts asking the recovery helper for the key: a one-time public key for the family drive. The matching private
     * part stays on this phone (locked in the Keystore) until [finishKeyRequest] or [cancelKeyRequest]; asking again
     * before then gives the same key, so the code on screen doesn't change.
     */
    fun startKeyRequest(): ByteArray? = null

    /**
     * Opens the helper's [answer] and keeps the key on this phone, after checking it really is this person's key
     * ([expectedFingerprint], from their public identity on the drive). Null when it worked, else the problem.
     */
    fun finishKeyRequest(answer: ByteArray, householdId: String, memberId: String, expectedFingerprint: String): String? = "Not available on this device."

    fun cancelKeyRequest() = Unit

    /**
     * As the helper: [ownerId]'s key, opened from the helper copy [wrapped] with this phone's own key and sealed to
     * their request [requestPublic], so only the asking phone can open it. Null when it can't be opened here.
     */
    fun answerKeyRequest(wrapped: ByteArray, requestPublic: ByteArray, householdId: String, ownerId: String): ByteArray? = null

    /** Forgets the key on this phone only. The drive's recovery file stays. */
    fun forgetKey() = Unit
}

object NoEncryptionPlatform : EncryptionPlatform

@Stable
class EncryptionState(val platform: EncryptionPlatform = NoEncryptionPlatform) {
    /** The key being set up, kept in memory only while the setup screens are open. */
    var pending by mutableStateOf<NewPrivateKey?>(null)

    var keyOnPhone by mutableStateOf(platform.hasLocalKey())

    fun refresh() { keyOnPhone = platform.hasLocalKey() }

    fun clearPending() {
        pending = null
        platform.discardPending()
    }
}
