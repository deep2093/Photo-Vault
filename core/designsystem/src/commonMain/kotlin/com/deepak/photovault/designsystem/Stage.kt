package com.deepak.photovault.designsystem

import androidx.compose.ui.graphics.Color

/**
 * The photo viewer's and editor's own dark colours (18 Nov, named instead of written out in each screen). The same in
 * every style: photos are looked at on black, so their colours read true.
 */
object Stage {
    /** The details panel under a photo. */
    val panel = Color(0xFF0B0B0D)

    /** A card on the panel ("On the family drive", tags). */
    val card = Color(0xFF1C1C21)

    val text = Color(0xFFF5F5F7)
    val textSecondary = Color(0xFFB8B8C0)

    /** The drag handle at the top of the panel. */
    val handle = Color(0x55FFFFFF)

    /** Tappable words on the panel (Open in Maps). */
    val link = Color(0xFF8AB4FF)

    /** The spot on the small map. */
    val pin = Color(0xFF3B82F6)
}
