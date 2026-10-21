package com.deepak.photovault.designsystem

/**
 * Which navigation a window of this width gets. Phones: a floating glass bar at the bottom.
 * Tablets and PC windows: a fixed side menu (never collapsible), as in Veyra.
 */
enum class NavigationLayout { BOTTOM_BAR, SIDE_RAIL }

object AdaptiveLayout {
    const val SIDE_RAIL_MIN_WIDTH_DP = 600

    fun navigationFor(widthDp: Int): NavigationLayout =
        if (widthDp >= SIDE_RAIL_MIN_WIDTH_DP) NavigationLayout.SIDE_RAIL else NavigationLayout.BOTTOM_BAR

    /** Fewest columns a pinch can reach (largest photos). */
    const val MIN_COLUMNS = 2

    /**
     * Smallest photo tile when pinched all the way out. Sized by width, not a fixed column count, so a tablet
     * zooms out as far as a phone does (about 48 dp per photo, close to Google Photos' densest grid).
     */
    const val MIN_TILE_DP = 48

    fun maxColumns(contentWidthDp: Int): Int = (contentWidthDp / MIN_TILE_DP).coerceAtLeast(4)

    /** Photo grid columns at the default density, before the user pinches. */
    fun defaultColumns(contentWidthDp: Int): Int = (contentWidthDp / 110).coerceIn(3, maxColumns(contentWidthDp))

    /** Keeps a saved column count valid after rotation or a window resize. */
    fun clampColumns(columns: Int, contentWidthDp: Int): Int = columns.coerceIn(MIN_COLUMNS, maxColumns(contentWidthDp))
}
