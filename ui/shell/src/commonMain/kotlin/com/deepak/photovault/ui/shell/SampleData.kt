package com.deepak.photovault.ui.shell

import com.deepak.photovault.designsystem.BackupStatus

/**
 * Sample content for Phase 1 only. Phase 2 replaces it with the phone's real photos (MediaStore on
 * Android, chosen folders on a PC). Nothing here touches the network or real files.
 */
data class SamplePhoto(
    val id: Int,
    val status: BackupStatus,
    val isVideo: Boolean,
    val favourite: Boolean,
    /** Index into the theme's sample hues, so tiles recolour with the theme. */
    val hue: Int,
    /** 0..1, mixes two hues for variety. */
    val shade: Float,
)

data class SampleDay(val label: String, val photos: List<SamplePhoto>)

data class SampleAlbum(val name: String, val count: Int, val shared: Boolean, val coverHues: List<Int>)

data class SampleMember(val name: String, val isYou: Boolean, val photos: Int, val phones: List<String>, val lastBackup: String)

data class SamplePhone(val name: String, val progress: Float, val detail: String)

object SampleData {
    val days: List<SampleDay> = run {
        val labels = listOf("Today", "Yesterday", "Monday 19 October", "Saturday 17 October", "Friday 9 October", "September 2026")
        val counts = listOf(7, 12, 5, 16, 9, 24)
        var next = 0
        labels.zip(counts).map { (label, count) ->
            SampleDay(
                label,
                List(count) {
                    val id = next++
                    SamplePhoto(
                        id = id,
                        status = when {
                            id < 5 -> BackupStatus.PHONE_ONLY // newest photos still waiting
                            id % 11 == 0 -> BackupStatus.ON_DRIVE // cleaned up from the phone, kept on the drive
                            else -> BackupStatus.BACKED_UP
                        },
                        isVideo = id % 7 == 3,
                        favourite = id % 9 == 2,
                        hue = (id * 7 + id / 3) % 5,
                        shade = ((id * 37) % 100) / 100f,
                    )
                },
            )
        }
    }

    val albums = listOf(
        SampleAlbum("Favourites", 48, shared = false, coverHues = listOf(0, 2, 4, 1)),
        SampleAlbum("Camera", 1_184, shared = false, coverHues = listOf(1, 3, 0, 2)),
        SampleAlbum("Screenshots", 212, shared = false, coverHues = listOf(4, 4, 3, 0)),
        SampleAlbum("Family holiday", 136, shared = true, coverHues = listOf(2, 0, 1, 3)),
        SampleAlbum("WhatsApp Images", 530, shared = false, coverHues = listOf(3, 1, 4, 2)),
        SampleAlbum("Birthday 2026", 64, shared = true, coverHues = listOf(0, 4, 2, 1)),
    )

    val members = listOf(
        SampleMember("You", isYou = true, photos = 1_248, phones = listOf("Pixel phone", "Tablet"), lastBackup = "Today, 2:04 am"),
        SampleMember("Alex", isYou = false, photos = 2_310, phones = listOf("Galaxy phone"), lastBackup = "Yesterday, 2:11 am"),
        SampleMember("Sam", isYou = false, photos = 640, phones = listOf("Pixel phone"), lastBackup = "3 days ago"),
    )

    val phones = listOf(
        SamplePhone("This phone", 0.35f, "42 of 120 uploaded"),
        SamplePhone("Tablet", 1f, "Up to date"),
    )
}
