package com.deepak.photovault.ui.shell

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.deepak.photovault.designsystem.LocalGlassSettings

/*
 * Reduce motion everywhere (Phase 12, 20 Nov): small helpers so lists and grids stop gliding when the person (or
 * Android's "Remove animations") asks for less movement. Each change then simply happens at once.
 */

/** A list item glides to its new place when the list changes, unless motion is reduced. */
@Composable
internal fun LazyItemScope.calmItem(): Modifier =
    if (LocalGlassSettings.current.reduceMotion) Modifier else Modifier.animateItem()

/** A grid item glides to its new place when the grid changes, unless motion is reduced. */
@Composable
internal fun LazyGridItemScope.calmItem(): Modifier =
    if (LocalGlassSettings.current.reduceMotion) Modifier else Modifier.animateItem()
