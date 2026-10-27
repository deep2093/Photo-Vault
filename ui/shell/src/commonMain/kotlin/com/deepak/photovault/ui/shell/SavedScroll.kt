package com.deepak.photovault.ui.shell

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** The app's [ShellState], so a screen can keep its scroll place without being handed the state (28 Oct). */
val LocalShell = staticCompositionLocalOf<ShellState?> { null }

/**
 * Lists remember where they were scrolled to (28 Oct): open something from the end of a page, come back, and the page
 * is where you left it, not at the top. A page opened fresh still starts at the top (ShellState.open clears its place).
 * [part] tells apart two lists on the same screen.
 */
@Composable
fun rememberSavedListState(part: String = ""): LazyListState {
    val shell = LocalShell.current
    val key = remember { (shell?.scrollKey() ?: "none") + part }
    val saved = remember { shell?.savedScroll?.get(key) }
    val list = rememberLazyListState(saved?.first ?: 0, saved?.second ?: 0)
    DisposableEffect(list) {
        onDispose { shell?.savedScroll?.put(key, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset) }
    }
    return list
}

@Composable
fun rememberSavedGridState(part: String = ""): LazyGridState {
    val shell = LocalShell.current
    val key = remember { (shell?.scrollKey() ?: "none") + part }
    val saved = remember { shell?.savedScroll?.get(key) }
    val grid = rememberLazyGridState(saved?.first ?: 0, saved?.second ?: 0)
    DisposableEffect(grid) {
        onDispose { shell?.savedScroll?.put(key, grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset) }
    }
    return grid
}
