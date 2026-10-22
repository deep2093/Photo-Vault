package com.deepak.photovault.ui.shell

/**
 * Sample content for the screens that don't have real data yet (Backup): replaced by backup (Phase 4). Photos and albums are real since Phase 2.
 */
data class SampleAlbum(val name: String, val count: Int, val shared: Boolean, val coverHues: List<Int>)

data class SamplePhone(val name: String, val progress: Float, val detail: String)

object SampleData {
    val albums = listOf(
        SampleAlbum("Favourites", 48, shared = false, coverHues = listOf(0, 2, 4, 1)),
        SampleAlbum("Camera", 1_184, shared = false, coverHues = listOf(1, 3, 0, 2)),
        SampleAlbum("Screenshots", 212, shared = false, coverHues = listOf(4, 4, 3, 0)),
        SampleAlbum("Family holiday", 136, shared = true, coverHues = listOf(2, 0, 1, 3)),
        SampleAlbum("WhatsApp Images", 530, shared = false, coverHues = listOf(3, 1, 4, 2)),
        SampleAlbum("Birthday 2026", 64, shared = true, coverHues = listOf(0, 4, 2, 1)),
    )

    val phones = listOf(
        SamplePhone("This phone", 0.35f, "42 of 120 uploaded"),
        SamplePhone("Tablet", 1f, "Up to date"),
    )
}
