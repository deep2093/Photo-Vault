package com.deepak.photovault.google

import android.accounts.AccountManager
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.lifecycleScope
import com.deepak.photovault.MainActivity
import com.deepak.photovault.core.backup.BackupRecord
import com.deepak.photovault.ui.shell.GoogleAccount
import com.deepak.photovault.ui.shell.GooglePlatform
import com.deepak.photovault.ui.shell.GoogleStatus
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Google Drive for the screens (Phase 10, 13 Nov). Adding an account: Android's own "choose an account" list first
 * (so a second or third account can be picked), then Google's own page asking to allow access to files Pixallery
 * makes. [onChanged] tells the screens to read the accounts again.
 */
class AndroidGoogle(
    private val activity: MainActivity,
    private val chooseAccount: (Intent) -> Unit,
    private val askConsent: (IntentSenderRequest) -> Unit,
    private val onChanged: () -> Unit,
) : GooglePlatform {
    private val app = activity.applicationContext
    private var pending: String? = null

    override val supported: Boolean get() = true

    override fun addAccount() {
        @Suppress("DEPRECATION")
        val intent = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
        runCatching { chooseAccount(intent) }.onFailure { activity.toast("Couldn't open the list of Google accounts.") }
    }

    /** The account picked in Android's list. */
    fun onAccountChosen(data: Intent?) {
        val email = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return
        authorize(email)
    }

    override fun signInAgain(email: String) = authorize(email)

    private fun authorize(email: String) {
        pending = email
        Identity.getAuthorizationClient(activity).authorize(GoogleAccounts.request(email))
            .addOnSuccessListener { result ->
                val ask = result.pendingIntent
                if (result.hasResolution() && ask != null) {
                    askConsent(IntentSenderRequest.Builder(ask.intentSender).build())
                } else if (result.accessToken != null) {
                    added(email)
                } else {
                    activity.toast("Google didn't allow access.")
                }
            }
            .addOnFailureListener { e ->
                activity.toast(
                    if ("10:" in (e.message ?: "") || "DEVELOPER_ERROR" in (e.message ?: "")) "Google sign-in isn't set up for this app yet (see Phase 10 setup)."
                    else "Couldn't reach Google. Check the internet and try again.",
                )
            }
    }

    /** Google's own "allow access" page closed. */
    fun onConsent(data: Intent?) {
        val email = pending ?: return
        val result = runCatching { Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data) }.getOrNull()
        if (result?.accessToken != null) added(email) else activity.toast("Google Drive wasn't added.")
    }

    private fun added(email: String) {
        pending = null
        GoogleAccounts.add(app, email)
        GoogleAccounts.setNeedsSignIn(app, email, false)
        activity.toast("$email added.")
        GoogleBackup.schedule(app)
        activity.lifecycleScope.launch {
            runCatching { GoogleBackup.refreshSpace(app) }
            onChanged()
        }
    }

    override suspend fun remove(email: String) {
        GoogleAccounts.remove(app, email)
        GoogleBackup.schedule(app)
    }

    override suspend fun accounts(): List<GoogleAccount> = GoogleAccounts.list(app).map { email ->
        val (used, limit) = GoogleAccounts.space(app, email)
        GoogleAccount(email, used, limit, GoogleAccounts.isFull(app, email), GoogleAccounts.needsSignIn(app, email))
    }

    override suspend fun refreshSpace() = GoogleBackup.refreshSpace(app)

    override suspend fun records(): List<BackupRecord> = GoogleBackup.records(app)

    override suspend fun keys(): Set<String> = GoogleBackup.keys(app)

    override fun backUpNow() = GoogleBackup.backUpNow(app)

    override fun reschedule() = GoogleBackup.schedule(app)

    override val status: StateFlow<GoogleStatus> get() = GoogleBackup.status
}
