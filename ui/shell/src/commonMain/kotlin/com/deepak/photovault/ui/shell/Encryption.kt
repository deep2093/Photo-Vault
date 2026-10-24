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
