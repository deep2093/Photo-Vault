package com.deepak.photovault.ui.shell

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf

/**
 * Counts taps on the section that is already open. Each new value asks that section to scroll to the top
 * (feedback 22 Oct). 0 means "never asked".
 */
val LocalScrollToTop = compositionLocalOf { 0 }

@Composable
fun ScrollToTopEffect(state: LazyGridState) {
    val signal = LocalScrollToTop.current
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        // One continuous glide: Compose itself skips the middle of a long distance without a pause (25 Oct).
        state.animateScrollToItem(0)
    }
}

@Composable
fun ScrollToTopEffect(state: LazyListState) {
    val signal = LocalScrollToTop.current
    LaunchedEffect(signal) {
        if (signal == 0) return@LaunchedEffect
        state.animateScrollToItem(0)
    }
}
