package com.deepak.photovault.ui.shell

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
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
import com.deepak.photovault.designsystem.withDepth
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.imePadding
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
 * The main sections have no top bar at all (feedback 23 Oct): pass a null [title]. Photos instead floats a small
 * glass pill of [floatingActions] at the top right, as in Samsung Gallery; it stays in place while scrolling.
 *
 * [content] receives the padding it must apply so nothing hides under the bars.
 */
@Composable
fun GlassScreen(
    title: String?,
    subtitle: String?,
    onBack: (() -> Unit)? = null,
    pinned: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActions: (@Composable RowScope.() -> Unit)? = null,
    /** A second pill at the top left (Photos: Years / Months / All). */
    floatingStartActions: (@Composable RowScope.() -> Unit)? = null,
    /**
     * Actions shown in a floating bar at the bottom (selection: share, favourite, delete). It rises in while the
     * navigation bar rolls down (24 Oct); null hides it.
     */
    bottomActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    // Photos and Albums: scrolling down tucks the pills and the navigation bar away; scrolling up brings them back.
    val chrome = LocalChrome.current
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
                if (chrome != null && source == NestedScrollSource.UserInput) {
                    if (available.y < -CHROME_SCROLL_PX) chrome.hidden = true
                    if (available.y > CHROME_SCROLL_PX) chrome.hidden = false
                }
                return Offset.Zero
            }
        }
    }
    // Bring the bar back when it must be visible: selection mode, or tapping the section again (scroll to top).
    val scrollToTop = LocalScrollToTop.current
    LaunchedEffect(pinned, scrollToTop) {
        if (pinned || scrollToTop > 0) chrome?.hidden = false
        if (pinned || scrollToTop > 0) {
            if (reduceMotion) offset = 0f else animate(offset, 0f, animationSpec = tween(250)) { v, _ -> offset = v }
        }
    }
    // Lens glass for this screen's pills and top bar: this screen's own content is recorded (the pills sit inside the
    // navigation bar's recording, so they cannot use that layer without drawing themselves). Always attached; it only
    // draws while lens glass is on.
    val lensOn = LocalGlassSettings.current.lensEnabled && LocalGlassTheme.current.lens
    val lens = com.deepak.photovault.ui.shell.rememberLensGlass()
    Box(Modifier.fillMaxSize()) {
        // The pill and the bar take the same room, so starting a selection on Photos doesn't shift the grid.
        val barRoom = if (title != null || floatingActions != null || floatingStartActions != null) TopBarHeight + 20.dp else 12.dp
        val contentPadding = PaddingValues(
            start = insets.calculateStartPadding(LayoutDirection.Ltr) + SCREEN_MARGIN,
            end = insets.calculateEndPadding(LayoutDirection.Ltr) + SCREEN_MARGIN,
            top = insets.calculateTopPadding() + barRoom,
            bottom = insets.calculateBottomPadding() + 24.dp,
        )
        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(connection)
                .then(if (haze != null) Modifier.hazeSource(haze) else Modifier)
                .then(lens?.source(lensOn) ?: Modifier),
        ) {
            content(contentPadding)
        }
        // A soft fade behind the status bar, so the clock and icons stay readable over scrolling photos.
        val theme = LocalGlassTheme.current
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(insets.calculateTopPadding() + 20.dp)
                .background(Brush.verticalGradient(listOf(theme.glassFallback, theme.glassFallback.copy(alpha = 0f)))),
        )
        // Only the chrome gets the lens, never the content: anything inside the recorded content would draw itself.
        CompositionLocalProvider(com.deepak.photovault.designsystem.LocalLensGlass provides lens) {
        val barPadding = Modifier.padding(
            start = insets.calculateStartPadding(LayoutDirection.Ltr) + 12.dp,
            end = insets.calculateEndPadding(LayoutDirection.Ltr) + 12.dp,
            top = insets.calculateTopPadding() + 8.dp,
        )
        // The selection bar at the bottom, above the system bar (the navigation bar has rolled away).
        val systemBottom = androidx.compose.foundation.layout.WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        var lastBottom by remember { androidx.compose.runtime.mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
        if (bottomActions != null) lastBottom = bottomActions
        androidx.compose.animation.AnimatedVisibility(
            visible = bottomActions != null,
            enter = androidx.compose.animation.slideInVertically(com.deepak.photovault.designsystem.motionSpec(260)) { it } + androidx.compose.animation.fadeIn(com.deepak.photovault.designsystem.motionSpec(260)),
            exit = androidx.compose.animation.slideOutVertically(com.deepak.photovault.designsystem.motionSpec(220)) { it } + androidx.compose.animation.fadeOut(com.deepak.photovault.designsystem.motionSpec(220)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = systemBottom + 12.dp),
        ) {
            lastBottom?.let { actions -> FloatingPill(Modifier, content = actions) }
        }
        if (title == null) {
            // The top pills (Years / Months / All, Search, an album's back button) always stay in view (24 Oct);
            // only the navigation bar tucks away while scrolling.
            val pillMotion = Modifier
            if (floatingStartActions != null) {
                FloatingPill(Modifier.align(Alignment.TopStart).then(pillMotion).then(barPadding), compact = true, content = floatingStartActions)
            }
            if (floatingActions != null) {
                FloatingPill(Modifier.align(Alignment.TopEnd).then(pillMotion).then(barPadding), compact = true, content = floatingActions)
            }
        } else {
        // With a bottom bar, the top bar is only the count and Cancel; it doesn't slide away.
        GlassTopBar(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            actions = actions,
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
    }
}

/** A small floating glass pill of buttons (Samsung Gallery style), used instead of a top bar on Photos. */
@Composable
private fun FloatingPill(modifier: Modifier, compact: Boolean = false, content: @Composable RowScope.() -> Unit) {
    // Compact (24 Oct: the pills were too big; the top ones were made smaller again the same evening).
    Row(
        modifier
            .heightIn(min = if (compact) 34.dp else 44.dp)
            .glassChrome(RoundedCornerShape(percent = 50), elevation = 8.dp)
            .padding(horizontal = if (compact) 3.dp else 4.dp, vertical = if (compact) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** How far a finger must move in one step before the pills and bar react, so tiny jitters don't flicker them. */
private const val CHROME_SCROLL_PX = 6f

@Composable
private fun GlassTopBar(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier,
) {
    val theme = LocalGlassTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = TopBarHeight)
            .glassChrome(RoundedCornerShape(24.dp), elevation = 10.dp)
            .padding(start = if (onBack != null) 4.dp else 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
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
                style = MaterialTheme.typography.titleMedium.withDepth(),
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

/**
 * A vertically scrolling list screen. Main sections (Backup, Family, Settings) pass a null [title] and have no top
 * bar; pages opened on top keep theirs, with a back button. Cards are capped in width on wide windows.
 */
@Composable
fun GlassListScreen(title: String?, subtitle: String?, onBack: (() -> Unit)? = null, items: LazyListScope.() -> Unit) {
    GlassScreen(title, subtitle, onBack = onBack) { padding ->
        val listState = rememberLazyListState()
        ScrollToTopEffect(listState)
        LazyColumn(
            state = listState,
            // Only pages that are lists pad for the keyboard, so a text field stays above it.
            modifier = Modifier.fillMaxSize().imePadding(),
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

/** Side margin of screen content. The photo grid ignores it and runs edge to edge. */
internal val SCREEN_MARGIN = 12.dp
