package com.deepak.photovault.core.household

import com.deepak.photovault.core.drive.BytesSource
import com.deepak.photovault.core.drive.MemoryDrive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackBoxTest {
    private val drive = MemoryDrive()

    private fun note(id: String, user: String, at: Long) =
        FeedbackNote(id, FeedbackKind.BUG, "Video flashes", user, "Sam", "Pixel 9", "1.0", at)

    @Test
    fun notesLandInTheSendersOwnFolderWithTheirFilesAndTheOwnerSeesEveryonesNewestFirst() = runTest {
        val box = FeedbackBox(drive)
        val saved = box.send(note("n1", "sam", 10), listOf("clip.mp4" to BytesSource(ByteArray(5)), "clip.mp4" to BytesSource(ByteArray(3))))
        assertEquals(listOf("clip.mp4", "clip (2).mp4"), saved.attachments)
        assertTrue(drive.exists("users/sam/feedback/n1/clip (2).mp4"))
        assertTrue(drive.exists("users/sam/feedback/n1/note.json"))
        box.send(note("n2", "deepak", 20), emptyList())
        // A note without its note.json (cut off half way) doesn't show.
        drive.mkdirs("users/sam/feedback/broken")
        val inbox = box.inbox(listOf("deepak", "sam", "nobody"))
        assertEquals(listOf("n2", "n1"), inbox.map { it.note.id })
        assertEquals("users/sam/feedback/n1", inbox.last().folder)
    }

    @Test
    fun fileNamesAreMadeSafe() {
        assertEquals("a_b.jpg", FeedbackBox.cleanName("a/b.jpg"))
        assertEquals("_note.json", FeedbackBox.cleanName("note.json"))
        assertEquals("photo (3).jpg", FeedbackBox.uniqueName("photo.jpg", listOf("photo.jpg", "photo (2).jpg")))
    }
}
