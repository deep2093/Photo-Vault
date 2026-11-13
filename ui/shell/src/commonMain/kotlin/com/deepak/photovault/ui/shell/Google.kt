package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepak.photovault.core.backup.BackupRecord
import kotlinx.coroutines.flow.StateFlow

/** One Google account Pixallery backs up to (Phase 10, 13 Nov). Space is Google's own, shared with Gmail and Photos. */
data class GoogleAccount(
    val email: String,
    val usedBytes: Long? = null,
    val limitBytes: Long? = null,
    val full: Boolean = false,
    /** Google wants the person to sign in again (access was taken back, or the password changed). */
    val needsSignIn: Boolean = false,
)

/** How the Google backup is doing. */
data class GoogleStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val lastAtMillis: Long? = null,
    val message: String? = null,
    /** Every account is full and photos are waiting: the person is told (notification, popup). */
    val allFull: Boolean = false,
    /** Photos still waiting for Google after the last run. */
    val left: Int = 0,
    /** The account being filled now. */
    val account: String? = null,
)

interface GooglePlatform {
    val supported: Boolean get() = false

    /** Asks which Google account to add, then Google's own "allow access" page. [refresh] the state afterwards. */
    fun addAccount() = Unit

    /** Shows Google's sign-in again for an account that needs it. */
    fun signInAgain(email: String) = Unit

    /** Stops backing up to [email]. What is already there stays in that account. */
    suspend fun remove(email: String) = Unit

    suspend fun accounts(): List<GoogleAccount> = emptyList()

    /** Reads every account's space from Google. */
    suspend fun refreshSpace() = Unit

    /** What is saved to Google, for the timeline (paths start "gdrive/"). */
    suspend fun records(): List<BackupRecord> = emptyList()

    /** Keys of everything backed up to any Google account. */
    suspend fun keys(): Set<String> = emptySet()

    fun backUpNow() = Unit

    /** Called when the backup settings change, so Google's schedule follows. */
    fun reschedule() = Unit

    val status: StateFlow<GoogleStatus>? get() = null

    /** The PIN-locked spare copy of the Private key from the first account that has one (14 Nov), or null. */
    suspend fun readPinKey(): ByteArray? = null

    /** Saves the PIN-locked key to every account. Null when at least one has it, else the problem in plain words. */
    suspend fun savePinKey(envelope: ByteArray): String? = "Not available here."

    /** What is in each account, for Settings > Storage (14 Nov). */
    suspend fun storage(): List<GoogleUsage> = emptyList()

    /** Deletes these backups (timeline paths) from Google Drive. Null when it worked, else the problem in plain words. */
    suspend fun deleteBackups(paths: Set<String>): String? = "Not available here."
}

/**
 * One Google account's space for Settings > Storage (14 Nov). Google tells only the total it uses for everything;
 * Pixallery knows its own photos and videos, so "other" is Gmail, Google Photos and other Drive files.
 */
data class GoogleUsage(
    val email: String,
    val limitBytes: Long?,
    val usedBytes: Long?,
    val photoBytes: Long,
    val videoBytes: Long,
    val photos: Int,
    val videos: Int,
    /** What Pixallery saved there, for By year and Albums. */
    val records: List<BackupRecord>,
    /** False when the list of photos couldn't be read from Google yet (the space still shows). */
    val listRead: Boolean = true,
) {
    val pixalleryBytes: Long get() = photoBytes + videoBytes
    val otherBytes: Long get() = ((usedBytes ?: pixalleryBytes) - pixalleryBytes).coerceAtLeast(0L)
    val freeBytes: Long? get() = limitBytes?.let { l -> (l - (usedBytes ?: pixalleryBytes)).coerceAtLeast(0L) }

    companion object {
        /** Every account added up, for the "All" view. */
        fun all(list: List<GoogleUsage>): GoogleUsage = GoogleUsage(
            email = "All",
            limitBytes = if (list.isNotEmpty() && list.all { it.limitBytes != null }) list.sumOf { it.limitBytes!! } else null,
            usedBytes = if (list.isNotEmpty() && list.all { it.usedBytes != null }) list.sumOf { it.usedBytes!! } else null,
            photoBytes = list.sumOf { it.photoBytes },
            videoBytes = list.sumOf { it.videoBytes },
            photos = list.sumOf { it.photos },
            videos = list.sumOf { it.videos },
            records = list.flatMap { it.records },
        )

        fun of(email: String, limit: Long?, used: Long?, records: List<BackupRecord>): GoogleUsage {
            val (v, p) = records.partition { it.isVideo }
            return GoogleUsage(email, limit, used, p.sumOf { it.sizeBytes }, v.sumOf { it.sizeBytes }, p.size, v.size, records)
        }
    }
}

@Stable
class GoogleState(val platform: GooglePlatform = object : GooglePlatform {}) {
    var accounts by mutableStateOf<List<GoogleAccount>>(emptyList())
        private set

    /** Google files for the timeline; null until read. */
    var records by mutableStateOf<List<BackupRecord>?>(null)
        private set

    var keys by mutableStateOf<Set<String>>(emptySet())
        private set

    var loading by mutableStateOf(false)
        private set

    /** Each account's space and what Pixallery keeps there, for Settings > Storage (14 Nov); null until read. */
    var usage by mutableStateOf<List<GoogleUsage>?>(null)
        private set

    var usageLoading by mutableStateOf(false)
        private set

    suspend fun loadUsage() {
        if (usageLoading) return
        usageLoading = true
        try {
            val list = runCatching { platform.storage() }.getOrNull()
            if (list != null) {
                usage = list
                records = list.flatMap { it.records }
                accounts = platform.accounts()
            }
        } finally {
            usageLoading = false
        }
    }

    /** Deletes backups from Google Drive and takes them off the lists at once (14 Nov). */
    suspend fun deleteBackups(paths: Set<String>): String? {
        val error = platform.deleteBackups(paths)
        if (error == null) {
            records = records?.filterNot { it.drivePath in paths }
            usage = usage?.map { u ->
                val gone = u.records.filter { it.drivePath in paths }
                if (gone.isEmpty()) u else GoogleUsage.of(u.email, u.limitBytes, u.usedBytes?.let { (it - gone.sumOf { r -> r.sizeBytes }).coerceAtLeast(0L) }, u.records - gone.toSet())
            }
            keys = runCatching { platform.keys() }.getOrDefault(keys)
        }
        return error
    }

    /** The "Google Drive is full" popup was closed this time the app is open. */
    var fullSeen by mutableStateOf(false)

    suspend fun refresh(readSpace: Boolean = false, readRecords: Boolean = true) {
        loading = true
        try {
            if (readSpace) runCatching { platform.refreshSpace() }
            accounts = platform.accounts()
            keys = runCatching { platform.keys() }.getOrDefault(keys)
            if (readRecords && accounts.isNotEmpty()) records = runCatching { platform.records() }.getOrNull() ?: records
            if (accounts.isEmpty()) records = emptyList()
        } finally {
            loading = false
        }
    }

    suspend fun remove(email: String) {
        platform.remove(email)
        refresh()
    }
}

/**
 * The family drive's and Google's backups together for the timeline (13 Nov): a photo in both places shows once
 * (the family drive's copy is used).
 */
fun timelineRecords(drive: List<BackupRecord>?, google: List<BackupRecord>?): List<BackupRecord>? {
    if (drive == null && google == null) return null
    val d = drive.orEmpty()
    if (google.isNullOrEmpty()) return d
    val have = d.map { it.key }.toHashSet()
    return d + google.filter { it.key !in have }.distinctBy { it.key }
}

/** True for a timeline path of a file saved to Google. */
fun isGooglePath(path: String): Boolean = path.startsWith("gdrive/")

/**
 * "Google Drive is full" (Phase 10, Deepak 13 Nov): when every Google account is full and backup to Google is on,
 * a popup once each time the app opens (with the notification from the background backup).
 */
@androidx.compose.runtime.Composable
internal fun GoogleFullPopup(state: ShellState, backup: BackupState) {
    val google = state.google
    val allFull = google.accounts.isNotEmpty() && google.accounts.all { it.full } && backup.settings.destination.usesGoogle
    if (!allFull || google.fullSeen) return
    com.deepak.photovault.designsystem.GlassDialog(
        title = "Google Drive is full",
        onDismiss = { google.fullSeen = true },
        buttons = {
            com.deepak.photovault.designsystem.GlassButton("OK", onClick = { google.fullSeen = true })
            com.deepak.photovault.designsystem.GlassButton("Add account", primary = true, onClick = {
                google.fullSeen = true
                google.platform.addAccount()
            })
        },
    ) {
        androidx.compose.material3.Text(
            "Your Google accounts are full, so new photos aren't being backed up to Google. Add another Google account, or make space in Google (Gmail and Google Photos share the same space).",
            color = com.deepak.photovault.designsystem.LocalGlassTheme.current.onGlass,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
}
