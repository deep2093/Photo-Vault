package com.deepak.photovault

import android.content.ContentValues
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Base64
import android.view.WindowManager
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.lifecycleScope
import com.deepak.photovault.backup.AndroidMediaReader
import com.deepak.photovault.core.backup.SecuredPaths
import com.deepak.photovault.core.backup.SecuredStore
import com.deepak.photovault.core.backup.TinkVault
import com.deepak.photovault.core.backup.newSha256
import com.deepak.photovault.core.crypto.KeyContext
import com.deepak.photovault.core.crypto.MemberKeys
import com.deepak.photovault.core.crypto.RecoveryCode
import com.deepak.photovault.core.crypto.RecoveryEnvelope
import com.deepak.photovault.core.security.SecretBox
import com.deepak.photovault.ui.shell.PinGuard
import com.deepak.photovault.ui.shell.PinOutcome
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.ui.shell.SecuredItem
import com.deepak.photovault.ui.shell.SecuredPlatform
import com.deepak.photovault.ui.shell.SecuredStored
import com.deepak.photovault.ui.shell.SecuredRules
import com.deepak.photovault.ui.shell.SecuredSetup
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Secured folder's lock and key on Android (Phase 8A, 1 Nov).
 *
 * The folder's own 32-byte key is sealed by a non-exportable Android Keystore key (not in Android's cloud backup), the
 * same way the Private backup key is. The PIN is only kept as a slow salted hash (PBKDF2); wrong guesses make the
 * person wait longer each time. The recovery (24 words, or the person's own code run through a very slow PBKDF2) seals
 * a copy of the key, so a forgotten PIN can be reset. The lock is a gate in the app; the stored photos are protected
 * by the Keystore-held key.
 */
class AndroidSecured(private val activity: MainActivity) : SecuredPlatform {
    private val prefs = activity.getSharedPreferences("pixallery_secured", Context.MODE_PRIVATE)
    private val box = SecretBox("pixallery_secured_root_v1")
    private val context = KeyContext("secured", "folder")
    private val random = SecureRandom()

    private class Pending(val keys: MemberKeys, val pinRecord: String, val envelope: ByteArray, val salt: ByteArray?)

    private var pending: Pending? = null

    override val supported: Boolean get() = true

    override fun isSetUp(): Boolean = root() != null

    private fun root(): MemberKeys? =
        prefs.getString(ROOT, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.let { box.open(it) }?.takeIf { it.size == 32 }?.let { MemberKeys(it) }

    // ---- PIN ----

    private fun pbkdf2(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(secret, salt, iterations, 256)).encoded

    private fun pinRecord(pin: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val hash = pbkdf2(pin.toCharArray(), salt, PIN_ITERATIONS)
        return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    override fun tryPin(pin: String): PinOutcome {
        val now = System.currentTimeMillis()
        val until = prefs.getLong(UNTIL, 0L)
        if (now < until) return PinOutcome(false, until - now)
        val parts = prefs.getString(PIN, null)?.split(':') ?: return PinOutcome(false)
        if (parts.size != 2) return PinOutcome(false)
        val salt = runCatching { Base64.decode(parts[0], Base64.NO_WRAP) }.getOrNull() ?: return PinOutcome(false)
        val want = runCatching { Base64.decode(parts[1], Base64.NO_WRAP) }.getOrNull() ?: return PinOutcome(false)
        val got = pbkdf2(pin.toCharArray(), salt, PIN_ITERATIONS)
        if (MessageDigest.isEqual(want, got)) {
            prefs.edit().putInt(FAILED, 0).putLong(UNTIL, 0L).apply()
            return PinOutcome(true)
        }
        val failed = prefs.getInt(FAILED, 0) + 1
        val wait = PinGuard.waitMillis(failed)
        prefs.edit().putInt(FAILED, failed).putLong(UNTIL, if (wait > 0) now + wait else 0L).apply()
        return PinOutcome(false, wait)
    }

    // ---- Fingerprint or face ----

    override val biometricReady: Boolean
        get() = runCatching {
            activity.getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)

    override fun askBiometric(onResult: (Boolean) -> Unit) {
        runCatching {
            val executor = activity.mainExecutor
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle("Secured folder")
                .setNegativeButton("Use PIN", executor) { _, _ -> onResult(false) }
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onResult(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(false)
            })
        }.onFailure { onResult(false) }
    }

    // ---- Making the folder ----

    /** The secret that seals the recovery: the 24 words' bytes, or the person's own code made slow to guess. */
    private fun ownSecret(code: String, salt: ByteArray): ByteArray = pbkdf2(SecuredRules.normalizedCode(code).toCharArray(), salt, CODE_ITERATIONS)

    override suspend fun prepare(pin: String, ownCode: String?): SecuredSetup? = withContext(Dispatchers.Default) {
        runCatching {
            val keys = MemberKeys.generate()
            val record = pinRecord(pin)
            if (ownCode == null) {
                val code = RecoveryCode.generate()
                pending = Pending(keys, record, RecoveryEnvelope.wrap(keys.root, code, context), null)
                SecuredSetup(RecoveryCode.toWords(code))
            } else {
                val salt = ByteArray(16).also { random.nextBytes(it) }
                pending = Pending(keys, record, RecoveryEnvelope.wrap(keys.root, ownSecret(ownCode, salt), context), salt)
                SecuredSetup(emptyList())
            }
        }.getOrNull()
    }

    override fun commit(): Boolean {
        val p = pending ?: return false
        val ok = runCatching {
            prefs.edit()
                .putString(ROOT, Base64.encodeToString(box.seal(p.keys.root), Base64.NO_WRAP))
                .putString(PIN, p.pinRecord)
                .putString(RECOVERY, Base64.encodeToString(p.envelope, Base64.NO_WRAP))
                .putString(CODE_SALT, p.salt?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                .putInt(FAILED, 0)
                .putLong(UNTIL, 0L)
                .commit()
        }.getOrDefault(false) && root() != null
        if (ok) pending = null
        return ok
    }

    override fun discard() { pending = null }

    // ---- Forgot the PIN ----

    override suspend fun resetPin(code: String, newPin: String): String? = withContext(Dispatchers.Default) {
        val envelope = prefs.getString(RECOVERY, null)?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?: return@withContext "This phone has no recovery to open."
        val saltText = prefs.getString(CODE_SALT, null)
        val secret: ByteArray = if (saltText == null) {
            when (val parsed = RecoveryCode.parse(code)) {
                is RecoveryCode.Parsed.Ok -> parsed.secret
                is RecoveryCode.Parsed.WrongCount -> return@withContext "There should be 24 words; you typed ${parsed.found}."
                is RecoveryCode.Parsed.UnknownWord -> return@withContext "Word ${parsed.position} (\"${parsed.word}\") isn't one of the recovery words. Check the spelling."
                RecoveryCode.Parsed.BadCheck -> return@withContext "These words don't add up. A word is wrong or out of order."
            }
        } else {
            val salt = runCatching { Base64.decode(saltText, Base64.NO_WRAP) }.getOrNull() ?: return@withContext "The recovery is damaged."
            ownSecret(code, salt)
        }
        val opened = RecoveryEnvelope.unwrap(envelope, secret, context)
            ?: return@withContext "That doesn't open the Secured folder's recovery."
        val local = root()
        if (local != null && !MessageDigest.isEqual(local.root, opened)) return@withContext "That belongs to a different Secured folder."
        runCatching {
            prefs.edit()
                .putString(PIN, pinRecord(newPin))
                .putString(ROOT, Base64.encodeToString(box.seal(opened), Base64.NO_WRAP))
                .putInt(FAILED, 0)
                .putLong(UNTIL, 0L)
                .commit()
        }.getOrDefault(false).let { if (it) null else "The new PIN couldn't be saved." }
    }

    override fun saveWordsToPasswordManager(words: List<String>, onResult: (Boolean) -> Unit) {
        activity.lifecycleScope.launch {
            val ok = runCatching {
                CredentialManager.create(activity).createCredential(activity, CreatePasswordRequest("Pixallery Secured folder recovery words", words.joinToString(" ")))
            }.isSuccess
            onResult(ok)
        }
    }

    private var clearSecure: Runnable? = null

    override fun protectScreen(on: Boolean) {
        val decor = activity.window.decorView
        clearSecure?.let { decor.removeCallbacks(it) }
        clearSecure = null
        if (on) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            // Changing a window flag makes Android rebuild the window's surface; doing it as the page slides away dropped
            // frames (1 Nov), so it waits until the slide is over.
            val later = Runnable { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            clearSecure = later
            decor.postDelayed(later, 900)
        }
    }

    // ---- Photos in the folder ----

    private var cachedStore: SecuredStore? = null

    private fun store(): SecuredStore? {
        cachedStore?.let { return it }
        val keys = root() ?: return null
        return SecuredStore(File(activity.filesDir, "secured"), TinkVault(keys, context)).also { cachedStore = it }
    }

    override suspend fun items(): List<SecuredItem>? = withContext(Dispatchers.IO) {
        val st = store() ?: return@withContext null
        runCatching {
            st.list().sortedByDescending { it.takenAtMillis }.map { SecuredItem(it.id, it.name, it.takenAtMillis, it.sizeBytes, it.width, it.height, it.isVideo, it.durationMillis) }
        }.getOrNull()
    }

    override suspend fun moveIn(items: List<MediaItem>, onProgress: (Int, Int) -> Unit): List<SecuredStored> = withContext(Dispatchers.IO) {
        val st = store() ?: return@withContext emptyList()
        val reader = AndroidMediaReader(activity)
        val stored = mutableListOf<SecuredStored>()
        items.forEachIndexed { i, item ->
            withContext(Dispatchers.Main) { onProgress(i, items.size) }
            val entry = runCatching {
                st.add(
                    name = item.displayName,
                    mimeType = item.mimeType.ifBlank { if (item.isVideo) "video/mp4" else "image/jpeg" },
                    takenAtMillis = item.takenAtMillis,
                    width = item.width,
                    height = item.height,
                    album = item.albumName,
                    thumbnail = reader.thumbnail(item),
                    isVideo = item.isVideo,
                    durationMillis = item.durationMillis,
                ) { activity.contentResolver.openInputStream(Uri.parse(item.uri)) ?: throw IOException("The photo can't be opened") }
            }.getOrNull()
            when {
                entry == null -> Unit
                // The copy must be exactly as big as the photo Android lists; if not, it is thrown away.
                item.sizeBytes > 0 && entry.sizeBytes != item.sizeBytes -> st.remove(listOf(entry.id))
                else -> stored += SecuredStored(item, entry.id)
            }
        }
        stored
    }

    override suspend fun thumbnail(id: String): ByteArray? = withContext(Dispatchers.IO) { store()?.thumbnail(id) }

    override suspend fun photo(id: String): ByteArray? = withContext(Dispatchers.IO) { store()?.photo(id) }

    override suspend fun moveOut(ids: List<String>): Int = withContext(Dispatchers.IO) {
        val st = store() ?: return@withContext 0
        val entries = runCatching { st.list() }.getOrNull()?.associateBy { it.id } ?: return@withContext 0
        val resolver = activity.contentResolver
        var done = 0
        for (id in ids) {
            val entry = entries[id] ?: continue
            // Back to the album it came from (Camera goes to DCIM/Camera; a missing album is made again).
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
                put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, SecuredPaths.relativePath(entry.album, entry.isVideo))
                put(MediaStore.MediaColumns.DATE_TAKEN, entry.takenAtMillis)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (entry.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: continue
            val ok = try {
                val hasher = newSha256()
                var size = 0L
                val wrote = resolver.openOutputStream(uri)?.use { out ->
                    st.copyOut(id) { b, n ->
                        out.write(b, 0, n)
                        hasher.update(b, 0, n)
                        size += n
                    }
                } ?: false
                wrote && size == entry.sizeBytes && hasher.hex() == entry.sha256
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            } catch (_: Exception) {
                false
            }
            if (ok) {
                val release = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (runCatching { resolver.update(uri, release, null, null) }.getOrDefault(0) > 0) {
                    st.remove(listOf(id))
                    done++
                    continue
                }
            }
            runCatching { resolver.delete(uri, null, null) }
        }
        done
    }

    override suspend fun remove(ids: List<String>) = withContext(Dispatchers.IO) { runCatching { store()?.remove(ids) }; Unit }

    private val playDir get() = File(activity.cacheDir, "secured_play")
    private val shareDir get() = File(activity.cacheDir, "secured_share")

    init {
        // Anything left from an earlier run (a crash, or the app closed while a video played) is erased at start.
        clearTemp()
        runCatching { shareDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > SHARE_KEEP_MS }?.forEach { it.delete() } }
    }

    override suspend fun videoFile(id: String): String? = withContext(Dispatchers.IO) {
        val st = store() ?: return@withContext null
        val entry = runCatching { st.list() }.getOrNull()?.firstOrNull { it.id == id } ?: return@withContext null
        val ext = entry.name.substringAfterLast('.', "mp4").take(5).filter { it.isLetterOrDigit() }.ifEmpty { "mp4" }
        val target = File(playDir, "$id.$ext")
        if (!target.exists() && !st.decryptTo(id, target)) return@withContext null
        Uri.fromFile(target).toString()
    }

    override suspend fun share(ids: List<String>): Boolean = withContext(Dispatchers.IO) {
        val st = store() ?: return@withContext false
        val entries = runCatching { st.list() }.getOrNull()?.filter { it.id in ids } ?: return@withContext false
        if (entries.isEmpty()) return@withContext false
        // Short-lived plain copies, shared once; erased on the next start or after a quarter of an hour.
        runCatching { shareDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > SHARE_KEEP_MS }?.forEach { it.delete() } }
        val uris = entries.mapNotNull { e ->
            val dir = File(shareDir, e.id)
            val file = File(dir, e.name.replace('/', '_'))
            if (!file.exists() && !st.decryptTo(e.id, file)) return@mapNotNull null
            androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        }
        if (uris.isEmpty()) return@withContext false
        withContext(Dispatchers.Main) {
            val type = when {
                entries.all { it.isVideo } -> "video/*"
                entries.none { it.isVideo } -> "image/*"
                else -> "*/*"
            }
            val send = if (uris.size == 1) {
                android.content.Intent(android.content.Intent.ACTION_SEND).putExtra(android.content.Intent.EXTRA_STREAM, uris.first())
            } else {
                android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            }.apply {
                setType(type)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) } }
            }
            activity.startActivity(android.content.Intent.createChooser(send, null))
        }
        true
    }

    override fun clearTemp() {
        runCatching { playDir.deleteRecursively() }
    }

    override suspend fun changePin(oldPin: String, newPin: String): String? = withContext(Dispatchers.Default) {
        val tried = tryPin(oldPin)
        if (!tried.ok) {
            return@withContext if (tried.waitMillis > 0) "Too many tries. Wait ${(tried.waitMillis + 999) / 1000} seconds." else "That isn't your current PIN."
        }
        if (runCatching { prefs.edit().putString(PIN, pinRecord(newPin)).commit() }.getOrDefault(false)) null else "The new PIN couldn't be saved."
    }

    private companion object {
        const val ROOT = "root"
        const val PIN = "pin"
        const val RECOVERY = "recovery"
        const val CODE_SALT = "code_salt"
        const val FAILED = "failed"
        const val UNTIL = "until"
        const val PIN_ITERATIONS = 150_000
        const val CODE_ITERATIONS = 600_000
        const val SHARE_KEEP_MS = 15 * 60 * 1000L
    }
}
