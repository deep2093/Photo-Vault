package com.deepak.photovault.ui.shell

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import com.deepak.photovault.designsystem.LocalGlassSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import com.deepak.photovault.designsystem.GlassIconButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.LocalHazeState
import com.deepak.photovault.designsystem.glassChrome
import dev.chrisbanes.haze.hazeSource

/**
 * A screen: scrolling content under a compact floating glass top bar. The content is the blur source, and the
 * top bar and navigation bar are drawn outside it, so they can blur whatever scrolls beneath them.
 *
 * The top bar slides away while scrolling down and returns on any scroll up (feedback 22 Oct). It stays put
 * while photos are selected, because the selection actions live in it.
 *
 * [content] receives the padding it must apply so nothing hides under the bars.
 */
@Composable
fun GlassScreen(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)? = null,
    pinned: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val insets = LocalScreenInsets.current
    val haze = LocalHazeState.current
    val density = LocalDensity.current
    val hidePx = with(density) { (insets.calculateTopPadding() + TopBarHeight + 24.dp).toPx() }
    var offset by remember { mutableFloatStateOf(0f) }
    val reduceMotion = LocalGlassSettings.current.reduceMotion
    val connection = remember(hidePx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Follow the finger: content moving up hides the bar, moving down shows it. Nothing is consumed.
                offset = (offset + available.y).coerceIn(-hidePx, 0f)
                return Offset.Zero
            }
        }
    }
    // Bring the bar back when it must be visible: selection mode, or tapping the section again (scroll to top).
    val scrollToTop = LocalScrollToTop.current
    LaunchedEffect(pinned, scrollToTop) {
        if (pinned || scrollToTop > 0) {
            if (reduceMotion) offset = 0f else animate(offset, 0f, animationSpec = tween(250)) { v, _ -> offset = v }
        }
    }
    Box(Modifier.fillMaxSize()) {
        val contentPadding = PaddingValues(
            start = insets.calculateStartPadding(LayoutDirection.Ltr) + 12.dp,
            end = insets.calculateEndPadding(LayoutDirection.Ltr) + 12.dp,
            top = insets.calculateTopPadding() + TopBarHeight + 20.dp,
            bottom = insets.calculateBottomPadding() + 24.dp,
        )
        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(connection)
                .then(if (haze != null) Modifier.hazeSource(haze) else Modifier),
        ) {
            content(contentPadding)
        }
        // A soft fade behind the status bar, so the clock and icons stay readable over scrolling photos.
        val theme = LocalGlassTheme.current
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(insets.calculateTopPadding() + if (theme.previewGlass != null) TopBarHeight + 40.dp else 20.dp)
                .background(
                    if (theme.previewGlass != null) Brush.verticalGradient(
                        0f to theme.wallpaper.first().copy(alpha = 0.96f),
                        0.80f to theme.wallpaper.first().copy(alpha = 0.92f),
                        1f to theme.wallpaper.first().copy(alpha = 0f),
                    ) else Brush.verticalGradient(listOf(theme.glassFallback, theme.glassFallback.copy(alpha = 0f))),
                ),
        )
        GlassTopBar(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            actions = actions,
            compact = pinned,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    translationY = offset
                    alpha = 1f - (-offset / hidePx).coerceIn(0f, 1f) * 0.6f
                }
                .padding(
                    start = insets.calculateStartPadding(LayoutDirection.Ltr) + 12.dp,
                    end = insets.calculateEndPadding(LayoutDirection.Ltr) + 12.dp,
                    top = insets.calculateTopPadding() + 8.dp,
                ),
        )
    }
}

@Composable
private fun GlassTopBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    val theme = LocalGlassTheme.current
    val openHeader = theme.previewGlass != null && !compact
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = TopBarHeight)
            .then(if (openHeader) Modifier else Modifier.glassChrome(RoundedCornerShape(24.dp), elevation = 10.dp))
            .padding(start = if (onBack != null || openHeader) 4.dp else 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = theme.onGlass,
                style = if (openHeader) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/** A vertically scrolling list screen (Backup, Family, Settings). Cards are capped in width on wide windows. */
@Composable
fun GlassListScreen(title: String, subtitle: String?, onBack: (() -> Unit)? = null, items: LazyListScope.() -> Unit) {
    GlassScreen(title, subtitle, onBack = onBack) { padding ->
        val listState = rememberLazyListState()
        ScrollToTopEffect(listState)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = items,
        )
    }
}

/** Width cap for cards on tablets and PC windows, so lines stay readable. */
internal val CardMaxWidth = 720.dp

internal fun Modifier.cardWidth(): Modifier = this.widthIn(max = CardMaxWidth).fillMaxWidth()
