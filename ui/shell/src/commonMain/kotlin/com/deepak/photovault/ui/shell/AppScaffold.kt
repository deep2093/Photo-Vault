package com.deepak.photovault.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import com.deepak.photovault.designsystem.PageEasing
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FiniteAnimationSpec
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
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

/** Sliding pages (24 Oct): a little longer than a grow-in, with a soft settle. */
private const val PAGE_MS = 640

/** The page underneath drifts a quarter of the screen and darkens by this much while another covers it. */
private const val DRIFT_DIVISOR = 4f
private const val PAGE_DIM = 0.28f
private val PAGE_SHADOW_WIDTH = 28.dp
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
    val familyDot: Boolean get() = drive.isOwner && (drive.requests.isNotEmpty() || drive.spaceRequests.isNotEmpty())
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
    phoneActions: MediaActions,
    drive: DriveState,
    backup: BackupState,
    signals: PlatformSignals = PlatformSignals(),
    iconSwitcher: AppIconSwitcher = NoAppIconSwitcher,
) {
    setSingletonImageLoaderFactory { context -> createImageLoader(context) }
    // Photos that are only on the drive go to the drive's Recently deleted; the rest go to the phone's.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val actions = remember(phoneActions, drive, gallery, backup) { DriveAwareActions(phoneActions, drive, gallery, backup, scope) }
    LaunchedEffect(state) { state.intro.start() }
    // Load the phone's photos, and reload whenever they change or access is granted.
    LaunchedEffect(gallery, gallery.access) { gallery.watch() }
    // Check the family drive when the app opens, then every minute while it is on screen, so Backup, Family and
    // Settings show real figures and the owner sees the dot on Family when a phone asks to join (24 Oct: no push).
    LaunchedEffect(drive, signals.foreground) {
        while (signals.foreground && drive.isSetUp) {
            drive.refresh()
            drive.refreshUsage()
            drive.flushFeedback(actions)
            kotlinx.coroutines.delay(DRIVE_REFRESH_MS)
        }
    }
    // One timeline (Phase 5): read what the drive holds when the app opens and after each backup, and let Photos
    // and Albums show what is only there. The last reading is kept on the phone, so it still shows offline.
    LaunchedEffect(drive, drive.joined?.me?.userId, backup.history.size, signals.foreground) {
        if (signals.foreground && drive.joined != null) {
            drive.loadBackupRecords(force = true)
            drive.loadDriveTrash()
            drive.loadFamilyAlbum()
        }
    }
    LaunchedEffect(gallery, drive.familyAlbumRecords) { drive.familyAlbumRecords?.let(gallery::updateFamilyAlbum) }
    LaunchedEffect(gallery, drive.backupRecords) { drive.backupRecords?.let(gallery::setDriveRecords) }
    LaunchedEffect(gallery, drive.driveTrash) { drive.driveTrash?.let(gallery::setDriveTrash) }
    PhotoVaultTheme(theme = state.theme, settings = state.glassSettings(signals)) {
        val hazeState = remember { HazeState() }
        val deleteFlight = remember { DeleteFlight() }
        val deletePrompt = remember { DeletePrompt() }
        CompositionLocalProvider(LocalHazeState provides hazeState, LocalDeleteFlight provides deleteFlight, LocalDeletePrompt provides deletePrompt, LocalFamilyAlbum provides (drive.joined != null)) {
            GlassBackground(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Bars and cut-outs only, not the keyboard (24 Oct): reading the keyboard here re-laid out the whole
                    // app on every frame of its slide, which made it stutter. Pages with text fields pad for it themselves.
                    val system = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
                    val screens = Screens(state, gallery, actions, drive, backup, iconSwitcher)
                    val layout = AdaptiveLayout.navigationFor(maxWidth.value.toInt())
                    // Phones and tablets alike: the floating bar at the bottom (24 Oct: no side menu on tablets).
                    CompositionLocalProvider(LocalTablet provides (layout == NavigationLayout.SIDE_RAIL)) {
                        BottomBarLayout(screens, system)
                    }
                    OverlayHost(state, gallery, actions, drive, backup, iconSwitcher, system)
                    // Deleted photos flying into the bin, above every page (23 Oct).
                    DeleteFlightLayer(LocalDeleteFlight.current)
                    DeletePromptHost(LocalDeletePrompt.current)
                    IntroHost(state.intro)
                }
            }
        }
    }
}

/** One open page and how far in it is (0 = away, 1 = in place). Each page owns its own movement. */
@Stable
private class PageEntry(val overlay: Overlay, shown: Float) {
    val p = Animatable(shown)
    var closing by mutableStateOf(false)
}

/**
 * Pages opened on top. Every page except the photo viewer slides in across the screen like a sliding door (24 Oct):
 * it comes from the left edge and goes back out to the left, while the page under it drifts the same way, more
 * slowly, and darkens. The photo viewer grows in and out.
 *
 * Each page has its own animation value and is taken away in the very frame it finishes. (Before, the pages were
 * switched by AnimatedContent and a one-frame flash of the whole page showed after it had gone: seen in the 24 Oct
 * recording.) The system back gesture instead pulls the page with the finger and holds it wherever the finger stops;
 * opening, closing by the arrow and the gesture all move the same page, so a page can be turned round half way.
 */
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
    val reduceMotion = LocalGlassSettings.current.reduceMotion
    val scope = rememberCoroutineScope()
    val entries = remember { mutableStateListOf<PageEntry>().also { list -> state.overlays.forEach { list.add(PageEntry(it, 1f)) } } }
    val wanted = state.overlays.toList()
    LaunchedEffect(wanted) {
        // The page the back gesture already moved away is removed at once; the rest slide.
        val skip = state.pageBackSkip
        state.pageBackSkip = false
        val live = entries.filter { !it.closing }
        var same = 0
        while (same < live.size && same < wanted.size && live[same].overlay == wanted[same]) same++
        live.drop(same).forEach { entry ->
            entry.closing = true
            if (skip || reduceMotion) {
                entries.remove(entry)
            } else {
                scope.launch {
                    entry.p.animateTo(0f, pageSpec(entry.overlay))
                    entries.remove(entry)
                }
            }
        }
        wanted.drop(same).forEach { overlay ->
            val entry = PageEntry(overlay, if (reduceMotion) 1f else 0f)
            entries.add(entry)
            if (!reduceMotion) {
                scope.launch {
                    // Let the new page be built first, so building it never eats into the movement.
                    withFrameNanos { }
                    withFrameNanos { }
                    entry.p.animateTo(1f, pageSpec(overlay))
                }
            }
        }
        if (skip) state.pageBack.snapTo(0f)
    }
    // The section under the first page follows it too (read only while drawing).
    SideEffect {
        state.sectionCover = {
            val first = entries.firstOrNull()
            if (first == null || !first.overlay.slidesSideways()) 0f
            else first.p.value * (1f - pulledAway(entries, state, first))
        }
    }
    Box(Modifier.fillMaxSize()) {
        entries.forEachIndexed { index, entry ->
            key(entry) {
                // A page fully covered by another is not built (as before), except the one a finger is uncovering.
                val covered by remember(entry) {
                    derivedStateOf {
                        val at = entries.indexOf(entry)
                        val full = entries.drop(at + 1).filter { !it.closing && it.p.value >= 1f }
                        val top = entries.lastOrNull { !it.closing }
                        full.isNotEmpty() && !(state.pageBack.value > 0f && full.size == 1 && full[0] === top)
                    }
                }
                if (!covered) {
                    val sideways = entry.overlay.slidesSideways()
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val width = size.width
                                if (sideways) {
                                    val top = entries.lastOrNull { !it.closing }
                                    val own = -(1f - entry.p.value) * width
                                    val pulled = if (entry === top) state.pageBackDir * state.pageBack.value * width else 0f
                                    val drift = coverAbove(entries, state, entries.indexOf(entry)) * width / DRIFT_DIVISOR
                                    translationX = own + pulled + drift
                                } else {
                                    val scale = OVERLAY_SCALE + (1f - OVERLAY_SCALE) * entry.p.value
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = entry.p.value
                                }
                            }
                            .dimmedBy { if (sideways) coverAbove(entries, state, entries.indexOf(entry)) * PAGE_DIM else 0f },
                    ) {
                        OverlayPage(entry.overlay, state, gallery, actions, drive, backup, iconSwitcher, system)
                        if (sideways) {
                            // A soft shadow runs down the page's leading edge.
                            Box(
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .offset(x = PAGE_SHADOW_WIDTH)
                                    .width(PAGE_SHADOW_WIDTH)
                                    .fillMaxHeight()
                                    .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent))),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun pageSpec(overlay: Overlay): androidx.compose.animation.core.FiniteAnimationSpec<Float> =
    if (overlay.slidesSideways()) tween(PAGE_MS, easing = PageEasing) else tween(OVERLAY_MS, easing = FastOutSlowInEasing)

/** How much of the page the finger has pulled away, when [entry] is the top page. */
private fun pulledAway(entries: List<PageEntry>, state: ShellState, entry: PageEntry): Float =
    if (entry === entries.lastOrNull { !it.closing }) state.pageBack.value else 0f

/** How much of the page at [index] is covered by the page on top of it (0 = not at all, 1 = fully). */
private fun coverAbove(entries: List<PageEntry>, state: ShellState, index: Int): Float {
    val next = entries.getOrNull(index + 1) ?: return 0f
    if (!next.overlay.slidesSideways()) return 0f
    return next.p.value * (1f - pulledAway(entries, state, next))
}

/** Darkens what it draws over by the fraction given (read only while drawing, so it never recomposes). */
private fun Modifier.dimmedBy(alpha: () -> Float): Modifier = drawWithContent {
    drawContent()
    val a = alpha()
    if (a > 0.001f) drawRect(Color.Black.copy(alpha = a))
}

/** One open page, with its own blur source and a soft shadow down its leading edge while it slides. */
@Composable
private fun OverlayPage(
    overlay: Overlay,
    state: ShellState,
    gallery: GalleryState,
    actions: MediaActions,
    drive: DriveState,
    backup: BackupState,
    iconSwitcher: AppIconSwitcher,
    system: PaddingValues,
) {
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
                        is Overlay.Album -> AlbumScreen(overlay.kind, state, gallery, actions, drive)
                        Overlay.RecentlyDeleted -> RecentlyDeletedScreen(state, gallery, actions)
                        Overlay.Search -> SearchScreen(state, gallery, actions)
                        Overlay.DriveSetup -> DriveSetupScreen(state, drive)
                        is Overlay.Member -> MemberScreen(overlay.userId, state, drive, actions)
                        Overlay.Backup -> BackupScreen(drive, backup, gallery, actions, onBack = { state.back() })
                        is Overlay.Settings -> SettingsPageScreen(overlay.page, state, drive, iconSwitcher, gallery, actions, backup, overlay.startOnDrive)
                        is Overlay.StorageFiles -> StorageFilesScreen(overlay, state, gallery, drive, backup, actions)
                        is Overlay.CoverPicker -> CoverPickerScreen(overlay, state, gallery)
                        is Overlay.Viewer -> Unit
                    }
                }
            }
        }
    }
}

/** Every page except the photo viewer slides in from the edge like a sliding door (24 Oct; before: only Settings pages). */
internal fun Overlay?.slidesSideways(): Boolean = this != null && this !is Overlay.Viewer

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
    // A Settings page rolls the bar down as it slides in, and back up as it slides out, at the page's speed.
    // The bar rolls down as a page slides over the section and back up as it leaves, with the page (or with the
    // finger on the back gesture); the other reasons to tuck it away animate on their own.
    val pinned = state.appearance.pinBottomBar
    val tucked = state.selecting || state.albumsSelected.isNotEmpty() || (state.chrome.hidden && !pinned && state.destination in Destination.hidesChrome)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val travel = with(density) { (barSpace + system.calculateBottomPadding() + 24.dp).toPx() }
    val shift = androidx.compose.animation.core.animateFloatAsState(if (tucked) travel else 0f, motionSpec(CHROME_MS), label = "bar")
    // The lens is always created and its modifiers always attached (they only draw while lens glass is on), so
    // switching to a lens theme while the app is open works at once instead of after a restart.
    val lensOn = LocalGlassSettings.current.lensEnabled && LocalGlassTheme.current.lens
    val lens = rememberLensGlass()
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalScreenInsets provides PaddingValues(
                start = system.calculateStartPadding(LayoutDirection.Ltr),
                top = system.calculateTopPadding(),
                end = system.calculateEndPadding(LayoutDirection.Ltr),
                bottom = system.calculateBottomPadding() + barSpace,
            ),
        ) {
            // The section under a page drifts the same way as the page, a quarter as far, and darkens (Samsung style).
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = state.sectionCover() * size.width / DRIFT_DIVISOR }
                    .dimmedBy { state.sectionCover() * PAGE_DIM },
            ) {
                // Lens glass (experimental): the sections are also recorded so the bar can look through them.
                Box(Modifier.fillMaxSize().then(lens?.source(lensOn) ?: Modifier)) {
                    SectionPager(nav.pager, screens)
                }
            }
        }
        // Only the bar gets the lens; anything inside the recorded sections would otherwise draw itself.
        CompositionLocalProvider(com.deepak.photovault.designsystem.LocalLensGlass provides lens) {
        FloatingNavigationBar(
            indicator = { nav.indicator.value },
            onSelect = state::tapSection,
            familyDot = screens.familyDot,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Moved by layout, not a layer translation: the lens glass must learn where the bar is (25 Oct fix for
                // the bar's lens showing the wrong part of the photos after it tucked away and came back).
                .offset { androidx.compose.ui.unit.IntOffset(0, maxOf(shift.value, travel * state.sectionCover()).toInt()) }
                .graphicsLayer {
                    val y = maxOf(shift.value, travel * state.sectionCover())
                    alpha = 1f - (y / travel).coerceIn(0f, 1f)
                }
                .padding(bottom = system.calculateBottomPadding() + 12.dp),
        )
        }
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
                state.albumsSelected = emptySet()
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
        userScrollEnabled = !state.selecting && state.albumsSelected.isEmpty(),
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val destination = Destination.entries[page]
        CompositionLocalProvider(
            LocalScrollToTop provides (state.reselects[destination] ?: 0),
            LocalChrome provides if (destination in Destination.hidesChrome) state.chrome else null,
        ) {
            when (destination) {
                Destination.PHOTOS -> PhotosScreen(state, screens.gallery, screens.actions, screens.drive)
                Destination.ALBUMS -> AlbumsScreen(state, screens.gallery, screens.actions, screens.drive)
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
            com.deepak.photovault.designsystem.DepthIcon(destination.icon, null, fg, Modifier.size(24.dp))
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
        com.deepak.photovault.designsystem.DepthText(destination.label, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Shared size for top bars, so screens line up. */
internal val TopBarHeight: Dp = MinTouchTarget + 8.dp
