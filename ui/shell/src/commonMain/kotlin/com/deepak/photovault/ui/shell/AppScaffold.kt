package com.deepak.photovault.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.sign
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import com.deepak.photovault.designsystem.LocalGlassSettings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import coil3.compose.setSingletonImageLoaderFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.AdaptiveLayout
import com.deepak.photovault.designsystem.GlassBackground
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.LocalHazeState
import com.deepak.photovault.designsystem.MinTouchTarget
import com.deepak.photovault.designsystem.NavigationLayout
import com.deepak.photovault.designsystem.PhotoVaultTheme
import com.deepak.photovault.designsystem.glassChrome
import com.deepak.photovault.designsystem.glassPanel
import com.deepak.photovault.designsystem.GlassIcon
import com.deepak.photovault.designsystem.motionSpec
import dev.chrisbanes.haze.HazeState

private const val OVERLAY_MS = 320
private const val DRIVE_REFRESH_MS = 60_000L

/** Everything a section needs, passed around together. */
private class Screens(
    val state: ShellState,
    val gallery: GalleryState,
    val actions: MediaActions,
    val drive: DriveState,
    val backup: BackupState,
    val iconSwitcher: AppIconSwitcher,
) {
    /** A dot on Family while a phone is waiting for the owner. */
    val familyDot: Boolean get() = drive.isOwner && drive.requests.isNotEmpty()
}
private const val OVERLAY_SCALE = 0.9f

/** True on tablets and wide windows (the side-menu layout), whichever menu style is chosen. */
val LocalTablet = staticCompositionLocalOf { false }

/** Space each screen must leave around its scrolling content (system bars, floating navigation). */
val LocalScreenInsets = staticCompositionLocalOf { PaddingValues(0.dp) }

/**
 * The whole app: theme, wallpaper, adaptive navigation, the current section and any open page on top.
 * Same on Android and PC; the platform supplies [gallery] (its photo library) and [actions].
 */
@Composable
fun PhotoVaultApp(
    state: ShellState,
    gallery: GalleryState,
    actions: MediaActions,
    drive: DriveState,
    backup: BackupState,
    signals: PlatformSignals = PlatformSignals(),
    iconSwitcher: AppIconSwitcher = NoAppIconSwitcher,
) {
    setSingletonImageLoaderFactory { context -> createImageLoader(context) }
    // Load the phone's photos, and reload whenever they change or access is granted.
    LaunchedEffect(gallery, gallery.access) { gallery.watch() }
    // Check the family drive when the app opens, then every minute while it is on screen, so Backup, Family and
    // Settings show real figures and the owner sees the dot on Family when a phone asks to join (24 Oct: no push).
    LaunchedEffect(drive, signals.foreground) {
        while (signals.foreground && drive.isSetUp) {
            drive.refresh()
            kotlinx.coroutines.delay(DRIVE_REFRESH_MS)
        }
    }
    PhotoVaultTheme(theme = state.theme, settings = state.glassSettings(signals)) {
        val hazeState = remember { HazeState() }
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            GlassBackground(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val system = WindowInsets.safeDrawing.asPaddingValues()
                    val screens = Screens(state, gallery, actions, drive, backup, iconSwitcher)
                    val layout = AdaptiveLayout.navigationFor(maxWidth.value.toInt())
                    // Phones and tablets alike: the floating bar at the bottom (24 Oct: no side menu on tablets).
                    CompositionLocalProvider(LocalTablet provides (layout == NavigationLayout.SIDE_RAIL)) {
                        BottomBarLayout(screens, system)
                    }
                    OverlayHost(state, gallery, actions, drive, backup, iconSwitcher, system)
                }
            }
        }
    }
}

/** Pages opened on top (viewer, album, search, Recently deleted), animated in and out. */
@Composable
private fun OverlayHost(
    state: ShellState,
    gallery: GalleryState,
    actions: MediaActions,
    drive: DriveState,
    backup: BackupState,
    iconSwitcher: AppIconSwitcher,
    system: PaddingValues,
) {
    // Opening and closing mirror each other at the same speed (feedback 22 Oct: closing was too fast).
    val spec = motionSpec<Float>(OVERLAY_MS)
    AnimatedContent(
        targetState = state.overlays.size to state.overlays.lastOrNull(),
        contentKey = { it.second },
        transitionSpec = {
            val opening = targetState.first > initialState.first
            // The page underneath stays fully in place while the new one grows in over it, and is already there
            // while the top one shrinks away. Before (24 Oct) both faded, so for a moment the section underneath
            // (Albums) showed through when a video or photo opened or closed: the "flash".
            if (opening) {
                ((fadeIn(spec) + scaleIn(spec, initialScale = OVERLAY_SCALE)) togetherWith ExitTransition.KeepUntilTransitionsFinished)
                    .apply { targetContentZIndex = 1f }
            } else {
                (EnterTransition.None togetherWith (fadeOut(spec) + scaleOut(spec, targetScale = OVERLAY_SCALE)))
                    .apply { targetContentZIndex = -1f }
            }
        },
        label = "overlay",
    ) { (_, overlay) ->
        if (overlay == null) return@AnimatedContent
        // Each page blurs only its own content, never the section underneath it.
        CompositionLocalProvider(
            LocalHazeState provides remember { HazeState() },
            LocalScreenInsets provides system,
        ) {
          // Absorb every touch on the page, including its empty areas. Without this, touches on parts of a
          // page with no buttons fell through to the section underneath: a back swipe on the Camera album
          // could tap the Camera card on Albums and reopen it (bug found 22 Oct).
          Box(Modifier.fillMaxSize().blockTouchesBelow()) {
            if (overlay is Overlay.Viewer) {
                ViewerScreen(overlay, state, gallery, actions)
            } else {
                GlassBackground(Modifier.fillMaxSize()) {
                    when (overlay) {
                        is Overlay.Album -> AlbumScreen(overlay.kind, state, gallery, actions)
                        Overlay.RecentlyDeleted -> RecentlyDeletedScreen(state, gallery, actions)
                        Overlay.Search -> SearchScreen(state, gallery, actions)
                        Overlay.DriveSetup -> DriveSetupScreen(state, drive)
                        is Overlay.Member -> MemberScreen(overlay.userId, state, drive, actions)
                        Overlay.Backup -> BackupScreen(drive, backup, gallery, actions, onBack = { state.back() })
                        is Overlay.Settings -> SettingsPageScreen(overlay.page, state, drive, iconSwitcher, gallery, actions)
                        is Overlay.Viewer -> Unit
                    }
                }
            }
          }
        }
    }
}

/** Takes part in touch hit-testing everywhere it covers, so siblings drawn underneath never receive touches. */
private fun Modifier.blockTouchesBelow(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        // Observe only (Final pass, nothing consumed): the page's own buttons and gestures work as before.
        do {
            val event = awaitPointerEvent(PointerEventPass.Final)
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun BottomBarLayout(screens: Screens, system: PaddingValues) {
    val state = screens.state
    val barSpace = 88.dp
    val nav = rememberSectionNav(state)
    // Photos and Albums: the bar slides away while scrolling down and comes back on scrolling up (24 Oct).
    // Photos and Albums tuck it away while scrolling down; selecting photos rolls it down at once so the
    // selection actions can take its place at the bottom (24 Oct).
    val hidden = state.selecting || (state.chrome.hidden && state.destination in Destination.hidesChrome)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val travel = with(density) { (barSpace + system.calculateBottomPadding() + 24.dp).toPx() }
    // Read only while drawing, so the slide doesn't recompose the screens on every frame.
    val shift = androidx.compose.animation.core.animateFloatAsState(if (hidden) travel else 0f, motionSpec(CHROME_MS), label = "bar")
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalScreenInsets provides PaddingValues(
                start = system.calculateStartPadding(LayoutDirection.Ltr),
                top = system.calculateTopPadding(),
                end = system.calculateEndPadding(LayoutDirection.Ltr),
                bottom = system.calculateBottomPadding() + barSpace,
            ),
        ) {
            SectionPager(nav.pager, screens)
        }
        FloatingNavigationBar(
            indicator = { nav.indicator.value },
            onSelect = state::tapSection,
            familyDot = screens.familyDot,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    translationY = shift.value
                    alpha = 1f - (shift.value / travel).coerceIn(0f, 1f)
                }
                .padding(bottom = system.calculateBottomPadding() + 12.dp),
        )
    }
}

/**
 * The sections side by side: swipe left or right to move between them, or tap the navigation.
 * Swiping is off while photos are selected, so a drag-select is never mistaken for a swipe.
 *
 * Smoothness (feedback 22 Oct): a tap on a far section no longer scrolls through every section in between.
 * The pager jumps next to the target without animating and slides the last step, while the navigation
 * highlight glides straight across. During a finger swipe the highlight follows the finger exactly.
 */
@Stable
private class SectionNav(val pager: PagerState, initial: Int) {
    /** Highlight position in sections (0f = Photos, 1.5f = halfway between Albums and Backup). */
    val indicator = Animatable(initial.toFloat())

    /** True while a navigation tap is moving the pager; the highlight then animates on its own. */
    var jumping by mutableStateOf(false)

    /**
     * Which tap is moving the pager. A quick second tap cancels the first; the first must not clear
     * [jumping] while the second is still moving (that made the app jump back to a section, 23 Oct).
     */
    var jumpId = 0
}

@Composable
private fun rememberSectionNav(state: ShellState): SectionNav {
    val pager = rememberPagerState(initialPage = state.destination.ordinal) { Destination.entries.size }
    val nav = remember(pager) { SectionNav(pager, pager.currentPage) }
    val reduceMotion = LocalGlassSettings.current.reduceMotion
    // Navigation tap -> highlight glides, pager jumps next to the target and slides in.
    LaunchedEffect(state.destination) {
        val target = state.destination.ordinal
        // Already there and resting exactly on the page. (A cancelled slide can stop between two
        // sections; that page still counts as current, so check the offset too, or it stays stuck.)
        if (pager.currentPage == target && pager.currentPageOffsetFraction == 0f && !pager.isScrollInProgress) {
            nav.indicator.snapTo(target.toFloat())
            return@LaunchedEffect
        }
        val id = ++nav.jumpId
        nav.jumping = true
        try {
            coroutineScope {
                launch {
                    if (reduceMotion) nav.indicator.snapTo(target.toFloat())
                    else nav.indicator.animateTo(target.toFloat(), tween(SECTION_MS, easing = FastOutSlowInEasing))
                }
                if (reduceMotion) {
                    pager.scrollToPage(target)
                } else {
                    val distance = target - pager.currentPage
                    if (kotlin.math.abs(distance) > 1) pager.scrollToPage(target - distance.sign)
                    pager.animateScrollToPage(target, animationSpec = tween(SECTION_MS, easing = FastOutSlowInEasing))
                }
            }
        } finally {
            if (nav.jumpId == id) nav.jumping = false
        }
    }
    // Finger swipes -> the highlight follows the pager exactly.
    LaunchedEffect(nav) {
        snapshotFlow { pager.currentPage + pager.currentPageOffsetFraction }.collect { position ->
            if (!nav.jumping) nav.indicator.snapTo(position.coerceIn(0f, (Destination.entries.size - 1).toFloat()))
        }
    }
    // Swipe -> the settled page becomes the current section.
    LaunchedEffect(nav) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val landed = Destination.entries[page]
            if (landed != state.destination && !pager.isScrollInProgress && !nav.jumping) {
                state.selected = emptySet()
                state.chrome.hidden = false
                state.destination = landed
            }
        }
    }
    return nav
}

private const val SECTION_MS = 340

@Composable
private fun SectionPager(pager: PagerState, screens: Screens) {
    val state = screens.state
    HorizontalPager(
        state = pager,
        key = { it },
        // All four sections stay ready, so switching never builds a screen from scratch mid-slide
        // (quick taps between far sections stuttered, 23 Oct). Off-screen ones aren't drawn.
        beyondViewportPageCount = Destination.entries.size - 1,
        userScrollEnabled = !state.selecting,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val destination = Destination.entries[page]
        CompositionLocalProvider(
            LocalScrollToTop provides (state.reselects[destination] ?: 0),
            LocalChrome provides if (destination in Destination.hidesChrome) state.chrome else null,
        ) {
            when (destination) {
                Destination.PHOTOS -> PhotosScreen(state, screens.gallery, screens.actions, screens.drive)
                Destination.ALBUMS -> AlbumsScreen(state, screens.gallery, screens.actions)
                Destination.FAMILY -> FamilyScreen(state, screens.drive, screens.actions)
                Destination.SETTINGS -> SettingsScreen(state, screens.drive, screens.iconSwitcher, screens.backup, screens.gallery)
            }
        }
    }
}

/** Phones: a floating glass capsule with one highlight that slides between sections. */
@Composable
private fun FloatingNavigationBar(indicator: () -> Float, onSelect: (Destination) -> Unit, familyDot: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val shape = RoundedCornerShape(percent = 50)
    BoxWithConstraints(
        modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .glassChrome(shape)
            .padding(6.dp),
    ) {
        val itemWidth = maxWidth / Destination.entries.size
        Box(
            Modifier
                .offset { IntOffset((itemWidth * indicator()).roundToPx(), 0) }
                .size(itemWidth, NAV_ITEM_HEIGHT)
                .clip(RoundedCornerShape(24.dp))
                .background(theme.accent),
        )
        Row(Modifier.selectableGroup()) {
            Destination.entries.forEach { destination ->
                NavItem(destination, indicator(), { onSelect(destination) }, Modifier.weight(1f), dot = familyDot && destination == Destination.FAMILY)
            }
        }
    }
}

/** The app's name as people see it (renamed from Veyra Photos on 24 Oct). */
const val APP_NAME = "Pixallery"

private const val CHROME_MS = 220

private val NAV_ITEM_HEIGHT = 58.dp

/**
 * One section button. Its colours blend as the highlight passes over it, so nothing flickers between
 * "selected" and "not selected" mid-swipe.
 */
@Composable
private fun NavItem(
    destination: Destination,
    indicator: Float,
    onClick: () -> Unit,
    modifier: Modifier,
    height: Dp = NAV_ITEM_HEIGHT,
    dot: Boolean = false,
) {
    val theme = LocalGlassTheme.current
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    val closeness = (1f - kotlin.math.abs(indicator - destination.ordinal)).coerceIn(0f, 1f)
    val fg = lerp(theme.onGlass, theme.onAccent, closeness)
    val selected = closeness > 0.5f
    Column(
        modifier
            .height(height)
            .clip(RoundedCornerShape(24.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = {
                haptics.tick()
                onClick()
            })
            .then(if (dot) Modifier.semantics { stateDescription = "A phone is waiting for approval" } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            GlassIcon(destination.icon, tint = fg)
            if (dot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-2).dp)
                        .size(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(theme.badgePhoneOnly),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(destination.label, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Shared size for top bars, so screens line up. */
internal val TopBarHeight: Dp = MinTouchTarget + 8.dp
