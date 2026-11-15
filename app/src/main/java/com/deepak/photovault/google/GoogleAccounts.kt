package com.deepak.photovault.google

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The Google accounts this phone backs up to (Phase 10, 13 Nov), in the order they fill up, and their access.
 *
 * Pixallery asks Google only for "files this app made" (drive.file): it can't see anything else in the account.
 * Google keeps the permission; each run asks Google for a fresh token, which works in the background once the
 * person said yes. If Google wants the person again (they took access back), the account is marked and the app asks.
 */
object GoogleAccounts {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    private const val PREFS = "google_accounts"
    private const val KEY_LIST = "emails"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Signed-in accounts, first to fill first. */
    fun list(context: Context): List<String> =
        prefs(context).getString(KEY_LIST, null).orEmpty().split('\n').filter { it.isNotBlank() }

    fun add(context: Context, email: String) {
        val now = list(context)
        if (now.any { it.equals(email, ignoreCase = true) }) return
        prefs(context).edit().putString(KEY_LIST, (now + email).joinToString("\n")).apply()
        setNeedsSignIn(context, email, false)
    }

    fun remove(context: Context, email: String) {
        prefs(context).edit()
            .putString(KEY_LIST, list(context).filterNot { it.equals(email, ignoreCase = true) }.joinToString("\n"))
            .remove("root:$email").remove("full:$email").remove("signin:$email").remove("used:$email").remove("limit:$email")
            .apply()
        tokens.remove(email)
    }

    fun rootId(context: Context, email: String): String? = prefs(context).getString("root:$email", null)

    fun setRootId(context: Context, email: String, id: String) = prefs(context).edit().putString("root:$email", id).apply()

    fun isFull(context: Context, email: String) = prefs(context).getBoolean("full:$email", false)

    fun setFull(context: Context, email: String, full: Boolean) = prefs(context).edit().putBoolean("full:$email", full).apply()

    fun needsSignIn(context: Context, email: String) = prefs(context).getBoolean("signin:$email", false)

    fun setNeedsSignIn(context: Context, email: String, needs: Boolean) = prefs(context).edit().putBoolean("signin:$email", needs).apply()

    /** Space last read from Google: used and the account's limit (bytes), or nulls. */
    fun space(context: Context, email: String): Pair<Long?, Long?> {
        val p = prefs(context)
        return (if (p.contains("used:$email")) p.getLong("used:$email", 0) else null) to (if (p.contains("limit:$email")) p.getLong("limit:$email", 0) else null)
    }

    fun setSpace(context: Context, email: String, used: Long, limit: Long) =
        prefs(context).edit().putLong("used:$email", used).putLong("limit:$email", limit).apply()

    /** A short, file-safe name for the account (used in the phone's own list of what is backed up there). */
    fun slug(email: String): String = email.lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == '@' }

    fun request(email: String?): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE)))
        if (email != null) b.setAccount(Account(email, "com.google"))
        return b.build()
    }

    private class Token(val value: String, val until: Long)

    private val tokens = java.util.concurrent.ConcurrentHashMap<String, Token>()

    /** Forget the token Google refused, so the next one is fresh. */
    fun refused(email: String) {
        tokens.remove(email)
    }

    /**
     * A current token for [email], asked of Google without showing anything. Throws [GoogleSignInNeeded] when Google
     * wants the person (then the app shows "Sign in again").
     */
    suspend fun token(context: Context, email: String): String {
        tokens[email]?.takeIf { it.until > System.currentTimeMillis() }?.let { return it.value }
        val result: AuthorizationResult = withContext(Dispatchers.IO) {
            Tasks.await(Identity.getAuthorizationClient(context.applicationContext).authorize(request(email)), 30, TimeUnit.SECONDS)
        }
        if (result.hasResolution()) {
            setNeedsSignIn(context, email, true)
            throw GoogleSignInNeeded("Google asked $email to sign in again")
        }
        val value = result.accessToken ?: throw GoogleSignInNeeded("Google gave no access for $email")
        setNeedsSignIn(context, email, false)
        // Google's tokens last an hour; a new one is asked for a little before.
        tokens[email] = Token(value, System.currentTimeMillis() + 45 * 60_000L)
        return value
    }

    /** The drive of [email], as a folder the backup engine understands. */
    fun fs(context: Context, email: String): GoogleDriveFs {
        val app = context.applicationContext
        return GoogleDriveFs(
            token = { token(app, email) },
            tokenRefused = { refused(email) },
            rootId = rootId(app, email),
            onRootId = { setRootId(app, email, it) },
        )
    }
}
