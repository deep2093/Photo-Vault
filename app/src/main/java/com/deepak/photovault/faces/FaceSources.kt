package com.deepak.photovault.faces

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.deepak.photovault.core.backup.BackupRecord
import com.deepak.photovault.core.backup.BackupSelection
import com.deepak.photovault.core.backup.DriveTimeline
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaStoreLibrary
import java.io.File

/**
 * Which photos People looks at (7A-3 / 7A-4, 6 Nov): photos on this phone outside hidden albums, exact duplicates
 * once, and photos that are only on the family drive (from their small previews, only on the home Wi-Fi). Photos in
 * the Secured folder are not in the phone's library at all, so they are never looked at.
 */
internal object FaceSources {
    class Photos(
        /** Phone photos to look at (hidden albums and extra copies of exact duplicates left out), newest first. */
        val phone: List<MediaItem>,
        /** Drive-only photos (not on this phone), newest first. */
        val drive: List<MediaItem>,
        /** Each photo's lasting key (phone: name, size and time; drive: the key of the phone photo it was backed up from). */
        val keys: Map<Long, String>,
        /** The photo with each lasting key, phone first. */
        val byKey: Map<String, Long>,
        /** False when the phone's library came back empty (no access): nothing is cleaned up then. */
        val trusted: Boolean,
    ) {
        val all: List<MediaItem> get() = phone + drive
    }

    suspend fun load(context: Context): Photos {
        val everything = MediaStoreLibrary(context).loadAll()
        val hidden = hiddenAlbums(context)
        val phone = once(everything.filter { !it.isVideo && it.albumId !in hidden }).sortedByDescending { it.takenAtMillis }
        // Photos moved into the Secured folder may still have a normal backup on the drive: those are left out too.
        val secured = runCatching { com.deepak.photovault.SecuredKeys.store(context)?.list() }.getOrNull().orEmpty()
            .map { BackupSelection.keyOf(it.name, it.sizeBytes, it.takenAtMillis) }.toHashSet()
        val records = driveRecords(context).filter { it.key !in secured }
        val drive = DriveTimeline.driveOnly(records, everything)
            .filter { !it.isVideo && it.albumId !in hidden }
            .sortedByDescending { it.takenAtMillis }
        val recordKey = records.associate { DriveTimeline.idOf(it.drivePath) to it.key }
        val keys = HashMap<Long, String>()
        phone.forEach { keys[it.id] = keyOf(it) }
        drive.forEach { keys[it.id] = recordKey[it.id] ?: keyOf(it) }
        val byKey = HashMap<String, Long>()
        drive.forEach { byKey[keys.getValue(it.id)] = it.id }
        phone.forEach { byKey[keys.getValue(it.id)] = it.id }
        return Photos(phone, drive, keys, byKey, trusted = everything.isNotEmpty())
    }

    /**
     * Exact duplicates (the same photo saved twice, e.g. a copy in another album): same file size and the same width and
     * height. Only the first (lowest id, usually the original) is kept.
     */
    fun once(items: List<MediaItem>): List<MediaItem> {
        val seen = HashSet<String>()
        return items.sortedBy { it.id }.filter { item ->
            if (item.sizeBytes <= 0L || item.width <= 0 || item.height <= 0) true else seen.add("${item.sizeBytes}|${item.width}|${item.height}")
        }
    }

    /** The key a photo keeps wherever it is (phone or drive): its name, size and capture time. */
    fun keyOf(item: MediaItem): String = BackupSelection.key(item)

    private fun hiddenAlbums(context: Context): Set<String> =
        context.applicationContext.getSharedPreferences("albums", Context.MODE_PRIVATE).getStringSet("hidden", emptySet()).orEmpty().toSet()

    /** The drive's list as last read by the app (kept on the phone, so this works without a connection). */
    fun driveRecords(context: Context): List<BackupRecord> {
        val file = File(context.applicationContext.filesDir, "backup/drive-records.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            com.deepak.photovault.backup.backupJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(BackupRecord.serializer()),
                file.readText(),
            )
        }.getOrDefault(emptyList())
    }

    /** The drive path of a drive-only photo by its id, or null. */
    fun drivePath(context: Context, photoId: Long): String? {
        if (photoId >= 0L) return null
        return driveRecords(context).firstOrNull { DriveTimeline.idOf(it.drivePath) == photoId }?.drivePath
    }

    /**
     * Home Wi-Fi (6 Nov): Wi-Fi, and not through a VPN (neither the phone's nor Pixallery's own), so drive previews are
     * read on the home network only, never through the tunnel or mobile data.
     */
    fun onHomeWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        return runCatching { !com.deepak.photovault.core.vpn.WireGuardManager.get(context).isActive() }.getOrDefault(true)
    }

    /** The phone is warm (Android's own thermal status, moderate or worse): scans pause (6 Nov). */
    fun warm(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
    }
}
