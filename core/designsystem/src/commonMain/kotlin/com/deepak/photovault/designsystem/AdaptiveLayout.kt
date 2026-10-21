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

    /** Photo grid columns at the default density, before the user pinches or zooms. */
    fun defaultColumns(contentWidthDp: Int): Int = (contentWidthDp / 120).coerceIn(3, 10)
}
