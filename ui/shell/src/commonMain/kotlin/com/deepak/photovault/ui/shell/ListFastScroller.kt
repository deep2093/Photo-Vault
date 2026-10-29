package com.deepak.photovault.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassPanel
import com.deepak.photovault.designsystem.motionSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The hidden side slider for lists of files (30 Oct), the same idea as on the Photos grid: it appears while the list
 * scrolls, fades away shortly after, and while it is dragged a label says where you are ([labelAt] gives the label
 * for a list position). Lists shorter than [minItems] don't get it.
 */
@Composable
internal fun ListFastScroller(
    list: LazyListState,
    top: Dp,
    bottom: Dp,
    labelAt: (Int) -> String,
    modifier: Modifier = Modifier,
    minItems: Int = 40,
) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val scrolling = list.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            visible = true
        } else {
            delay(1200)
            visible = false
        }
    }
    val total = list.layoutInfo.totalItemsCount
    if (total < minItems) return
    BoxWithConstraints(modifier.fillMaxHeight().padding(top = top, bottom = bottom).width(140.dp)) {
        val density = LocalDensity.current
        val thumbHeight = 44.dp
        val thumbPx = with(density) { thumbHeight.toPx() }
        val trackPx = with(density) { (maxHeight - thumbHeight).toPx() }.coerceAtLeast(1f)
        val fraction by remember {
            derivedStateOf {
                val layout = list.layoutInfo
                val visibleCount = layout.visibleItemsInfo.size.coerceAtLeast(1)
                (list.firstVisibleItemIndex.toFloat() / (layout.totalItemsCount - visibleCount).coerceAtLeast(1)).coerceIn(0f, 1f)
            }
        }
        val label by remember { derivedStateOf { labelAt(list.firstVisibleItemIndex) } }
        fun jumpTo(y: Float) {
            val f = ((y - thumbPx / 2) / trackPx).coerceIn(0f, 1f)
            scope.launch { list.scrollToItem((f * (list.layoutInfo.totalItemsCount - 1)).toInt()) }
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(motionSpec()) + slideInHorizontally(motionSpec()) { it / 3 },
            exit = fadeOut(motionSpec(300)) + slideOutHorizontally(motionSpec(300)) { it / 3 },
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .width(40.dp)
                        .pointerInput(total) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true; jumpTo(it.y) },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onVerticalDrag = { change, _ ->
                                    change.consume()
                                    jumpTo(change.position.y)
                                },
                            )
                        }
                        .semantics { contentDescription = "Fast scroll" },
                )
                val thumbY = with(density) { (fraction * trackPx).toDp() }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = thumbY, end = 4.dp)
                        .size(width = 8.dp, height = thumbHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (dragging) theme.accent else theme.accent.copy(alpha = 0.8f)),
                )
                if (dragging && label.isNotEmpty()) {
                    Text(
                        label,
                        color = theme.onGlass,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = thumbY + 6.dp, end = 24.dp)
                            .glassPanel(RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
