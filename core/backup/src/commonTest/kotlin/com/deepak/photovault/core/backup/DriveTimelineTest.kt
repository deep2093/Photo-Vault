package com.deepak.photovault.core.backup

import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveTimelineTest {
    private fun phone(id: Long, name: String, size: Long, taken: Long, album: String = "Camera") =
        MediaItem(id, "content://$id", MediaType.PHOTO, name, taken, size, 10, 10, 0, "b-$album", album, "image/jpeg")

    private fun record(name: String, size: Long, taken: Long, album: String? = "Camera", video: Boolean = false) =
        BackupRecord(
            key = BackupSelection.keyOf(name, size, taken),
            drivePath = "users/u1/albums/${album ?: "x"}/2026/10/$name",
            sha256 = "x", sizeBytes = size, originalName = name, takenAtMillis = taken,
            mimeType = if (video) "video/mp4" else "image/jpeg", isVideo = video, savedAtMillis = 1, deviceId = "d",
            album = album,
        )

    @Test
    fun aPhotoStillOnThePhoneIsNotShownTwice() {
        val on = listOf(phone(1, "a.jpg", 100, 5))
        assertTrue(DriveTimeline.driveOnly(listOf(record("a.jpg", 100, 5)), on).isEmpty())
    }

    @Test
    fun aRestoredCopyWithAChangedDateIsNotShownTwice() {
        val on = listOf(phone(1, "a.jpg", 100, 999))
        assertTrue(DriveTimeline.driveOnly(listOf(record("a.jpg", 100, 5)), on).isEmpty())
    }

    @Test
    fun aPhotoDeletedFromThePhoneStaysInTheTimelineInItsOldAlbum() {
        val on = listOf(phone(1, "b.jpg", 50, 7))
        val out = DriveTimeline.driveOnly(listOf(record("a.jpg", 100, 5)), on)
        assertEquals(1, out.size)
        assertTrue(out[0].isDriveOnly)
        assertEquals("b-Camera", out[0].albumId)
        assertEquals(5, out[0].takenAtMillis)
        assertTrue(out[0].id < 0)
    }

    @Test
    fun anAlbumThatOnlyExistsOnTheDriveGetsItsOwnId() {
        val out = DriveTimeline.driveOnly(listOf(record("a.jpg", 100, 5, album = "Trip")), emptyList())
        assertEquals(DriveTimeline.ALBUM_PREFIX + "Trip", out[0].albumId)
        assertEquals("Trip", out[0].albumName)
    }

    @Test
    fun videosAndOldRecordsWithoutAnAlbumWork() {
        val out = DriveTimeline.driveOnly(listOf(record("v.mp4", 9, 1, album = null, video = true)), emptyList())
        assertTrue(out[0].isVideo)
        assertEquals("Backed up", out[0].albumName)
    }

    @Test
    fun idsAreStableAndPathsRoundTrip() {
        assertEquals(DriveTimeline.idOf("p/a"), DriveTimeline.idOf("p/a"))
        assertEquals("p/a", DriveTimeline.pathOf(DriveTimeline.uriOf("p/a")))
        assertEquals(null, DriveTimeline.pathOf("content://1"))
    }
}
