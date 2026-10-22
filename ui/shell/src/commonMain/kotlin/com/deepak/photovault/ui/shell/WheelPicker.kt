package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.LocalGlassTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.math.abs

/**
 * A time picker of rolling wheels: hour, minute, AM/PM (24 Oct, like the one on iPhone). Each step gives a small
 * tick of the phone's vibration. The new time is saved when the wheel comes to rest.
 */
@Composable
fun WheelTimePicker(hour24: Int, minute: Int, onChange: (hour24: Int, minute: Int) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val pm = hour24 >= 12
    val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
    val current by rememberUpdatedState(Triple(hour12, minute, pm))
    val change by rememberUpdatedState(onChange)
    fun commit(h12: Int, m: Int, isPm: Boolean) = change(WheelTime.to24(h12, isPm), m)
    Box(modifier.fillMaxWidth().height(WHEEL_ITEM * VISIBLE), contentAlignment = Alignment.Center) {
        // The band that marks the chosen row.
        Box(
            Modifier
                .fillMaxWidth()
                .height(WHEEL_ITEM)
                .clip(RoundedCornerShape(12.dp))
                .background(theme.onGlass.copy(alpha = 0.08f)),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Wheel(values = (1..12).map { it.toString() }, selected = hour12 - 1, looping = true, width = 64.dp) { i ->
                commit(i + 1, current.second, current.third)
            }
            Wheel(values = (0..59).map { it.toString().padStart(2, '0') }, selected = minute, looping = true, width = 64.dp) { i ->
                commit(current.first, i, current.third)
            }
            Wheel(values = listOf("AM", "PM"), selected = if (pm) 1 else 0, looping = false, width = 64.dp) { i ->
                commit(current.first, current.second, i == 1)
            }
        }
    }
}

/** Pure conversions behind the picker, tested. */
object WheelTime {
    fun to24(hour12: Int, pm: Boolean): Int = (hour12 % 12) + if (pm) 12 else 0
}

private val WHEEL_ITEM = 40.dp
private const val VISIBLE = 5
/** Looping wheels repeat their values this many times, starting in the middle. */
private const val LOOPS = 200

@Composable
private fun Wheel(values: List<String>, selected: Int, looping: Boolean, width: Dp, onSelected: (Int) -> Unit) {
    val theme = LocalGlassTheme.current
    val haptics = LocalHapticFeedback.current
    val count = if (looping) values.size * LOOPS else values.size
    val start = if (looping) values.size * (LOOPS / 2) + selected else selected
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val itemPx = with(LocalDensity.current) { WHEEL_ITEM.toPx() }
    val centre by remember {
        derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0 }
    }
    val select by rememberUpdatedState(onSelected)
    val chosen by rememberUpdatedState(selected)
    // A tick for every value that passes the middle.
    LaunchedEffect(state) {
        snapshotFlow { centre }.distinctUntilChanged().collect {
            if (state.isScrollInProgress) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    // Saved once the wheel stops.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.distinctUntilChanged().filter { !it }.collect {
            val value = centre.coerceIn(0, count - 1) % values.size
            if (value != chosen) select(value)
        }
    }
    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = WHEEL_ITEM * (VISIBLE / 2)),
        modifier = Modifier.width(width).height(WHEEL_ITEM * VISIBLE),
    ) {
        items(count) { index ->
            val distance = abs(index - centre)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ITEM)
                    .graphicsLayer {
                        alpha = when (distance) { 0 -> 1f; 1 -> 0.55f; else -> 0.25f }
                        val s = if (distance == 0) 1f else 0.9f
                        scaleX = s
                        scaleY = s
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    values[index % values.size],
                    color = theme.onGlass,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (distance == 0) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
