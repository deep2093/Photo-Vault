package com.deepak.photovault.core.backup

import com.deepak.photovault.core.media.MediaItem
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class Frequency(val label: String) { DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly") }

/**
 * When scheduled backups run. Default (Deepak, 24 Oct 2026): every Sunday at 2 am.
 * Android starts it at or after this time, once the conditions are met (see [BackupSettings.chargingOnly]):
 * a phone not on charge at 2 am backs up as soon as it is charging on Wi-Fi.
 */
@Serializable
data class BackupSchedule(
    val enabled: Boolean = true,
    val frequency: Frequency = Frequency.WEEKLY,
    val hour: Int = 2,
    val minute: Int = 0,
    /** ISO day: 1 = Monday ... 7 = Sunday. Kept for settings saved before several days could be chosen. */
    val dayOfWeek: Int = 7,
    /** Weekly: every chosen day (24 Oct: several days a week). Null means just [dayOfWeek]. */
    val daysOfWeek: Set<Int>? = null,
    /** 1 to 28, so every month has it. */
    val dayOfMonth: Int = 1,
) {
    /** The first scheduled time strictly after [afterMillis]. */
    fun nextRun(afterMillis: Long, zone: TimeZone): Long {
        val after = Instant.fromEpochMilliseconds(afterMillis)
        var date = after.toLocalDateTime(zone).date
        repeat(70) {
            val matches = when (frequency) {
                Frequency.DAILY -> true
                Frequency.WEEKLY -> date.dayOfWeek.isoDayNumber in weekDays
                Frequency.MONTHLY -> date.dayOfMonth == dayOfMonth.coerceIn(1, 28)
            }
            if (matches) {
                val at = LocalDateTime(date, LocalTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))).toInstant(zone)
                if (at > after) return at.toEpochMilliseconds()
            }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        error("No scheduled time found")
    }

    /** The weekdays a weekly backup runs on (never empty). */
    val weekDays: Set<Int> get() = daysOfWeek?.filter { it in 1..7 }?.toSet()?.ifEmpty { null } ?: setOf(dayOfWeek)

    /** "Sun", or "Mon, Wed, Sun"; "Every day" when all seven are chosen. */
    fun daysLabel(): String = if (weekDays.size == 7) "Every day" else weekDays.sorted().joinToString(", ") { DAY_NAMES[it - 1].take(3) }

    fun describe(): String {
        val time = timeLabel(hour, minute)
        return when (frequency) {
            Frequency.DAILY -> "Every day at $time"
            Frequency.WEEKLY -> if (weekDays.size == 1) "Every ${DAY_NAMES[weekDays.first() - 1]} at $time" else "${daysLabel()} at $time"
            Frequency.MONTHLY -> "On day $dayOfMonth of every month at $time"
        }
    }

    companion object {
        val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        /**
         * Each phone in the family gets its own start time (25 Oct): 2:00, 2:30, 3:00 ... so they don't all start at once
         * and wait for one another. [index] is this phone's place among all phones, in the order they joined.
         */
        fun slotTime(index: Int): Pair<Int, Int> {
            val minutes = 2 * 60 + (index.coerceAtLeast(0) % 12) * 30
            return minutes / 60 to minutes % 60
        }

        fun timeLabel(hour: Int, minute: Int): String {
            val h = if (hour % 12 == 0) 12 else hour % 12
            return "$h:${minute.toString().padStart(2, '0')} ${if (hour < 12) "am" else "pm"}"
        }
    }
}

/** How this person's backup is kept (Phase 4A, 25 Oct). Chosen once, on purpose; nothing is pre-selected. */
@Serializable
enum class BackupPrivacy { UNSET, NORMAL, PRIVATE }

/** This phone's backup choices. Saved on the phone (each phone chooses its own). */
@Serializable
data class BackupSettings(
    val schedule: BackupSchedule = BackupSchedule(),
    val photos: Boolean = true,
    /** Off by default (Deepak, 24 Oct): only photos back up until videos are switched on. */
    val videos: Boolean = false,
    /** Phone folders (album IDs) to back up. Null means the default: Camera only (24 Oct). */
    val folders: Set<String>? = null,
    /** Scheduled backups wait for a charger (default on). "Back up now" never waits. */
    val chargingOnly: Boolean = true,
    /** Off: Wi-Fi only (any Wi-Fi). On: mobile data too, after a warning. */
    val mobileData: Boolean = false,
    val videosOnMobile: Boolean = false,
    /** Photos are compressed to 16 MP JPEG before upload (24 Oct). Videos are never compressed. */
    val storageSaver: Boolean = false,
    /** A scheduled backup may turn the VPN on by itself when away from home, and off right after (Phase 5B-1; off by default). */
    val awayBackup: Boolean = false,
    /** This phone's backup time was moved to its own slot when it joined the family (25 Oct), so it only happens once. */
    val staggered: Boolean = false,
    /** Normal or Private (encrypted) backup. UNSET until the person picks; backup does not run before that. */
    val privacy: BackupPrivacy = BackupPrivacy.UNSET,
)

/** Which phone photos are backed up, and how a photo is recognised again later. */
object BackupSelection {
    fun isCamera(albumName: String) = albumName.equals("Camera", ignoreCase = true)

    fun includesFolder(settings: BackupSettings, albumId: String, albumName: String): Boolean =
        settings.folders?.contains(albumId) ?: isCamera(albumName)

    fun eligible(items: List<MediaItem>, settings: BackupSettings, onMobileData: Boolean = false): List<MediaItem> =
        items.filter { item ->
            val typeOn = if (item.isVideo) settings.videos && (!onMobileData || settings.videosOnMobile) else settings.photos
            typeOn && includesFolder(settings, item.albumId, item.albumName)
        }

    /**
     * Identity of a photo across app reinstalls and Android re-indexing: name, size and capture time. An edited
     * photo changes size, so it is backed up again as a new version.
     */
    fun key(item: MediaItem): String = keyOf(item.displayName, item.sizeBytes, item.takenAtMillis)

    fun keyOf(name: String, size: Long, takenAtMillis: Long) = "$name|$size|$takenAtMillis"
}
