package com.deepak.photovault.ui.shell

import androidx.compose.runtime.collectAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import com.deepak.photovault.designsystem.accentSurface
import com.deepak.photovault.designsystem.frostedEdge
import com.deepak.photovault.designsystem.PageEasing
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
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
import com.deepak.photovault.core.backup.isDriveOnly
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.liveRegion
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
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
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
/** While the app is open the drive is checked once an hour (28 Oct; was every minute, which kept a hard disk awake). */
private const val DRIVE_REFRESH_MS = 60 * 60_000L

/** Family and requests while the app is on screen (10 Nov). */
private const val DRIVE_REQUESTS_MS = 15 * 60_000L

/** The owner's app looks at the requests to join this often while on screen (10 Nov). */
private const val DRIVE_REQUESTS_FAST_MS = 30_000L

/** A phone waiting to be approved checks this often while the app is open (10 Nov). */
private const val DRIVE_WAITING_MS = 15_000L


/** The full list of photos on the drive is read again at most this often, unless a backup finished or you pull down. */
private const val DRIVE_LIBRARY_MS = 3 * 60 * 60_000L

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
    val settingsDot: Boolean get() = drive.isOwner && drive.feedbackNew > 0
    val familyDot: Boolean get() = drive.isOwner && (drive.requests.isNotEmpty() || drive.spaceRequests.isNotEmpty())
}
private const val OVERLAY_SCALE = 0.9f

/** True on tablets and wide windows (the side-menu layout), whichever menu style is chosen. */
val LocalTablet = staticCompositionLocalOf { false }

/** True only on a tablet (both sides of the window tablet-sized), not a phone turned sideways (18 Nov). */
val LocalTabletDevice = staticCompositionLocalOf { false }

/** A short message at the bottom of the screen (13 Nov): any screen can say "done" after an action. */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

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
    val actions = remember(phoneActions, drive, gallery, backup) { DriveAwareActions(phoneActions, drive, gallery, backup, scope, state.google) }
    // Load the phone's photos, and reload whenever they change or access is granted.
    LaunchedEffect(gallery, gallery.access) { gallery.watch() }
    // Check the family drive when the app opens, then every minute while it is on screen, so Backup, Family and
    // Settings show real figures and the owner sees the dot on Family when a phone asks to join (24 Oct: no push).
    LaunchedEffect(drive, signals.foreground, state.appearance.autoDriveCheck) {
        if (!signals.foreground || !drive.isSetUp) return@LaunchedEffect
        suspend fun check() {
            drive.refresh()
            drive.refreshUsage()
            drive.flushFeedback(actions)
            drive.refreshFeedbackDot()
        }
        val auto = state.appearance.autoDriveCheck
        // Opening the app does not wake the drive (30 Oct): the family and the photo list are the ones remembered on this
        // phone. Only a phone that doesn't know its family yet asks. After that: every hour while on screen, on pull
        // down, when a drive page opens, after a backup, or when a drive photo is opened.
        if (drive.joined == null) check()
        // 10 Nov: the family and requests (small) every 15 minutes, so a phone asking to join shows up fast; space used
        // and feedback (slower) every hour as before.
        var rounds = 0
        while (auto) {
            kotlinx.coroutines.delay(DRIVE_REQUESTS_MS)
            rounds++
            if (rounds % (DRIVE_REFRESH_MS / DRIVE_REQUESTS_MS).toInt() == 0) check() else drive.refresh()
        }
    }
    // The owner, app on screen: the requests folder every 30 seconds (10 Nov), so a phone asking to join pops up fast.
    LaunchedEffect(drive.isOwner, signals.foreground) {
        while (drive.isOwner && signals.foreground) {
            kotlinx.coroutines.delay(DRIVE_REQUESTS_FAST_MS)
            drive.checkRequests()
        }
    }
    // Just let into the family (or started one): count the space at once, not "Counting..." until the next hour.
    LaunchedEffect(drive.joined?.me?.userId) {
        if (drive.joined != null) drive.refreshUsage()
    }
    // A phone waiting to be let in checks every 15 seconds while the app is open (10 Nov), so it is in the family as
    // soon as the owner approves, without restarting the app.
    val waiting = drive.membership is com.deepak.photovault.core.household.Membership.Pending
    LaunchedEffect(waiting, signals.foreground) {
        while (waiting && signals.foreground) {
            kotlinx.coroutines.delay(DRIVE_WAITING_MS)
            drive.refresh()
        }
    }
    // Joining a family moves this phone's backup time to its own slot, unless it was already moved (25 Oct).
    LaunchedEffect(drive.joined?.me?.userId, drive.joined?.household?.members?.sumOf { it.devices.size }) {
        val j = drive.joined ?: return@LaunchedEffect
        if (backup.settings.staggered) return@LaunchedEffect
        val phones = j.household.members.sortedBy { it.joinedAtMillis }.flatMap { m -> m.devices.sortedBy { it.addedAtMillis } }
        val index = phones.indexOfFirst { it.deviceId == drive.deviceId }
        if (index < 0) return@LaunchedEffect
        val (hour, minute) = com.deepak.photovault.core.backup.BackupSchedule.slotTime(index)
        backup.update { it.copy(staggered = true, schedule = it.schedule.copy(hour = hour, minute = minute)) }
    }
    // One timeline (Phase 5): read what the drive holds when the app opens and after each backup, and let Photos
    // and Albums show what is only there. The last reading is kept on the phone, so it still shows offline.
    // 28 Oct: read in full at the start, after each backup, after three hours, or when you pull down; not on every return.
    var seenHistory by remember { mutableStateOf(-1) }
    // The "all my backups" test (18 Nov): off, or on with All my backups, shows everything as before.
    LaunchedEffect(state.appearance.mirrorTest, state.appearance.showAllBackups) {
        gallery.showDeviceOnly(state.appearance.mirrorTest && !state.appearance.showAllBackups)
    }
    LaunchedEffect(drive, drive.joined?.me?.userId, backup.history.size, signals.foreground) {
        if (signals.foreground && drive.joined != null) {
            val backupDone = seenHistory != -1 && seenHistory != backup.history.size
            seenHistory = backup.history.size
            // Not read when the app opens (30 Oct): read after a backup, or after three hours once it has been read.
            val old = drive.libraryReadAtMillis > 0 && platform_now(drive) - drive.libraryReadAtMillis > DRIVE_LIBRARY_MS
            if (backupDone || old) {
                drive.loadBackupRecords(force = true)
                drive.loadDriveTrash()
                drive.loadFamilyAlbum()
            }
        }
    }
    LaunchedEffect(gallery, drive.familyAlbumRecords) { drive.familyAlbumRecords?.let(gallery::updateFamilyAlbum) }
    // 18 Nov (Deepak): drive photos deleted in the app stay off screen until the drive has them in its Recently deleted;
    // that is tried now, when the app comes back, when the drive answers again, and every few minutes meanwhile.
    LaunchedEffect(gallery, drive.pendingTrash) { gallery.setWaitingToDelete(drive.pendingTrash) }
    val deletesWaiting = drive.pendingTrash.isNotEmpty()
    LaunchedEffect(drive.joined?.me?.userId, signals.foreground, deletesWaiting, drive.problem) {
        while (deletesWaiting && signals.foreground && drive.joined != null) {
            if (actions.finishDriveDeletes()) break
            kotlinx.coroutines.delay(DRIVE_DELETE_RETRY_MS)
        }
    }
    // The family drive's and Google's backups in one timeline (Phase 10, 13 Nov).
    LaunchedEffect(gallery, drive.backupRecords, state.google.records) {
        timelineRecords(drive.backupRecords, state.google.records)?.let(gallery::setDriveRecords)
    }
    // Google: read the accounts and what is saved there when the app opens; "backed up" marks follow.
    LaunchedEffect(state.google) {
        if (state.google.platform.supported) {
            // Last time's Google photos first, so they're in the timeline straight away; then check Google.
            state.google.loadCached()
            state.google.refresh(readSpace = true)
        }
    }
    LaunchedEffect(state.google.keys, backup.settings.destination) { backup.googleKeys = state.google.keys }
    // A Google backup that just finished: read the lists again.
    val googleStatus = state.google.platform.status?.collectAsState()
    LaunchedEffect(googleStatus?.value?.lastAtMillis) { if (googleStatus?.value?.lastAtMillis != null) state.google.refresh() }
    LaunchedEffect(gallery, drive.driveTrash) { drive.driveTrash?.let(gallery::setDriveTrash) }
    // Day or night (18 Nov): the phone's dark mode, for themes set to follow it.
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    androidx.compose.runtime.SideEffect { if (state.systemDark != systemDark) state.systemDark = systemDark }
    PhotoVaultTheme(theme = state.theme, settings = state.glassSettings(signals)) {
        val hazeState = remember { HazeState() }
        val deleteFlight = remember { DeleteFlight() }
        val deletePrompt = remember {
            DeletePrompt().also { prompt ->
                // 18 Nov: after moving photos to Recently deleted, a message with Undo, which puts them back (Android may
                // ask again). Not for photos only on the drive, which go back from the drive's own Recently deleted.
                prompt.onTrashed = { trashActions, items ->
                    val words = if (items.size == 1) (if (items[0].isVideo) "1 video" else "1 photo") else "${items.size} items"
                    if (items.none { it.isDriveOnly }) state.say("Moved $words to Recently deleted.", "Undo delete") { trashActions.restore(items) }
                    else state.say("Moved $words to Recently deleted.")
                }
            }
        }
        CompositionLocalProvider(LocalHazeState provides hazeState, LocalDeleteFlight provides deleteFlight, LocalDeletePrompt provides deletePrompt, LocalFamilyAlbum provides drive.canAddToFamilyAlbum, LocalToast provides actions::toast) {
            GlassBackground(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Bars and cut-outs only, not the keyboard (24 Oct): reading the keyboard here re-laid out the whole
                    // app on every frame of its slide, which made it stutter. Pages with text fields pad for it themselves.
                    val system = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
                    val screens = Screens(state, gallery, actions, drive, backup, iconSwitcher)
                    val layout = AdaptiveLayout.navigationFor(maxWidth.value.toInt())
                    // A real tablet (18 Nov): its shorter side is tablet-sized too, so a phone turned sideways (wide but
                    // short) still counts as a phone and keeps the bottom bar.
                    val tabletDevice = minOf(maxWidth, maxHeight) >= AdaptiveLayout.SIDE_RAIL_MIN_WIDTH_DP.dp
                    CompositionLocalProvider(LocalTablet provides (layout == NavigationLayout.SIDE_RAIL), LocalTabletDevice provides tabletDevice) {
                        // 18 Nov (Deepak): tablets get the side bar by default; Settings > Appearance can switch it back
                        // to the floating bar at the bottom. Phones always have the bottom bar.
                        if (tabletDevice && state.appearance.tabletSideBar) SideBarLayout(screens, system) else BottomBarLayout(screens, system)
                    }
                    // First-time backup questions after joining a family drive (10 Nov).
                    FirstSetupPopups(state, drive, backup)
                    GoogleFullPopup(state, backup)
                    GoogleDeletePopup(state)
                    // Photos and dates fade out under the status bar instead of showing through its clock and icons.
                    val top = system.calculateTopPadding()
                    // Neutral dark, not the wallpaper's first colour: that showed as a pink band on Vivid Night (29 Oct).
                    val wall = if (LocalGlassTheme.current.isDark) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White
                    androidx.compose.foundation.layout.Box(
                        Modifier.align(androidx.compose.ui.Alignment.TopStart).fillMaxWidth().height(top + 8.dp)
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to wall.copy(alpha = 0.6f), 0.7f to wall.copy(alpha = 0.3f), 1f to androidx.compose.ui.graphics.Color.Transparent)),
                    )
                    OverlayHost(state, gallery, actions, drive, backup, iconSwitcher, system)
                    // Free up space by itself, only when Android's "Media management" is allowed (29 Oct).
                    FreeUpAuto(backup, actions)
                    // Deleted photos flying into the bin, above every page (23 Oct).
                    DeleteFlightLayer(LocalDeleteFlight.current)
                    DeletePromptHost(LocalDeletePrompt.current)
                    IntroHost(state.intro)
                    JoinRequestPrompt(drive, signals.foreground)
                    MessageBar(state, Modifier.align(Alignment.BottomCenter).padding(bottom = system.calculateBottomPadding() + 96.dp))
                }
            }
        }
    }
}

/**
 * The app's short messages (18 Nov, replacing Android's toasts): a glass bar above the navigation and the viewer's
 * buttons. It goes after a few seconds, or at a tap; screen readers read it out.
 */
@Composable
private fun MessageBar(state: ShellState, modifier: Modifier = Modifier) {
    val message = state.message
    var last by remember { mutableStateOf<AppMessage?>(null) }
    if (message != null) last = message
    LaunchedEffect(message) {
        val shown = message ?: return@LaunchedEffect
        // Longer with an Undo, so there is time to reach it.
        kotlinx.coroutines.delay(MESSAGE_MS + shown.text.length * 40L + (if (shown.action != null) UNDO_EXTRA_MS else 0L))
        state.dismissMessage(shown)
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = message != null,
        enter = androidx.compose.animation.fadeIn(motionSpec(200)) + androidx.compose.animation.slideInVertically(motionSpec(200)) { it / 2 },
        exit = androidx.compose.animation.fadeOut(motionSpec(160)),
        modifier = modifier,
    ) {
        val shown = last ?: return@AnimatedVisibility
        val theme = LocalGlassTheme.current
        val act = shown.action
        val label = shown.actionLabel
        if (act != null && label != null) {
            // 18 Nov (Deepak: "just Undo delete, short, with an undo arrow, in the centre"): a small pill in the middle,
            // the whole pill the button. TalkBack reads the full sentence, then the button.
            Row(
                Modifier
                    .glassChrome(RoundedCornerShape(percent = 50), elevation = 8.dp)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        state.dismissMessage(shown)
                        act()
                    }
                    .semantics(mergeDescendants = true) {
                        liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                        contentDescription = "${shown.text} $label"
                    }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Undo, null, tint = theme.onGlass, modifier = Modifier.size(20.dp))
                Text(label, color = theme.onGlass, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            return@AnimatedVisibility
        }
        val shape = RoundedCornerShape(24.dp)
        Row(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .glassChrome(shape, elevation = 8.dp)
                .clickable(onClickLabel = "Close message") { state.dismissMessage(shown) }
                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
                .heightIn(min = 52.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shown.text, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
        }
    }
}

private const val MESSAGE_MS = 2800L
private const val UNDO_EXTRA_MS = 3000L

/** One open page and how far in it is (0 = away, 1 = in place). Each page owns its own movement. */
@Stable
private class PageEntry(val overlay: Overlay, shown: Float) {
    val p = Animatable(shown)
    var closing by mutableStateOf(false)

    /** The slide in progress. Closing cancels it, so a quick open-then-close can't have the old slide-in win. */
    var slide: kotlinx.coroutines.Job? = null

    /**
     * The photo viewer only (16 Nov): the copy of the photo that flies between its tile and its place in the viewer.
     * Null: no flying copy (reduce motion, or the photo can't be found), and the page grows and fades as before.
     */
    var fly by mutableStateOf<Fly?>(null)

    /**
     * Smoothness (16 Nov, from Deepak's recordings beside Google Photos): building the viewer during the flight made
     * the phone drop frames, so the flight looked like a jump. With a flying copy the viewer is only built once the
     * copy has [landed], then fades in under it ([reveal]); the copy stays on top ([copyOn]) until then. On closing,
     * the viewer stays built but hidden while the copy flies back, and is only taken apart after the flight.
     */
    var landed by mutableStateOf(true)
    val reveal = Animatable(1f)
    var copyOn by mutableStateOf(false)
}

/**
 * A copy of a photo's small picture, flown between its tile in the grid and where the photo sits in the viewer.
 * [tile] is in the page host's own coordinates; null when the tile isn't on screen (the copy then just grows a
 * little and fades). [aspect] is the photo's width / height (0 = unknown). [px] is the picture size the grid already
 * has in memory, so the copy shows at once.
 */
private data class Fly(val uri: String, val isVideo: Boolean, val px: Int, val aspect: Float, val tile: androidx.compose.ui.geometry.Rect?) {
    /** Where the copy is at [p] (0 = on the tile, 1 = in the viewer), on a page [w] by [h]. */
    fun rect(p: Float, w: Float, h: Float): androidx.compose.ui.geometry.Rect {
        val page = w / h
        val a = if (aspect > 0f) aspect else page
        val fitW = if (a >= page) w else h * a
        val fitH = fitW / a
        val left = (w - fitW) / 2f
        val top = (h - fitH) / 2f
        val t = tile
        if (t == null) {
            val k = OVERLAY_SCALE + (1f - OVERLAY_SCALE) * p
            return androidx.compose.ui.geometry.Rect(w / 2f - fitW * k / 2f, h / 2f - fitH * k / 2f, w / 2f + fitW * k / 2f, h / 2f + fitH * k / 2f)
        }
        fun mix(from: Float, to: Float) = from + (to - from) * p
        return androidx.compose.ui.geometry.Rect(mix(t.left, left), mix(t.top, top), mix(t.right, left + fitW), mix(t.bottom, top + fitH))
    }
}

/** True on a page that is closing (17 Nov): an HDR video takes itself away at once, as it can't fade with the page. */
val LocalPageLeaving = staticCompositionLocalOf { false }

/** How deep the page being drawn is: 0 for a section, 1 for the first page on top, and so on (16 Nov). */
val LocalPageDepth = staticCompositionLocalOf { 0 }

/**
 * Pages opened on top. Every page except the photo viewer slides in across the screen like a sliding door (24 Oct):
 * it comes in from the right edge and goes back out to the right (6 Nov: was the left), while the page under it drifts
 * the same way, more slowly, and darkens. The photo viewer grows in and out.
 *
 * A closing page is only a picture of itself while it slides away (6 Nov): its last frame is kept and drawn, and the
 * page itself is gone, so a tap during the slide reaches the page underneath instead of the one leaving.
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
    // Photos open out of their tile and close back into it (16 Nov; rebuilt the same day after Deepak's recording:
    // the page itself used to be shrunk, and went black because its photo is drawn on layers that are freed when the
    // page closes). Now a copy of the photo's small picture flies between the tile and the photo's place in the
    // viewer while black fades in or out behind it; the real viewer is only shown once the copy has landed.
    val hostOrigin = remember { floatArrayOf(0f, 0f) }
    val platformContext = coil3.compose.LocalPlatformContext.current
    fun tileOf(id: Long, depthBelow: Int): androidx.compose.ui.geometry.Rect? =
        state.tileRect(id, depthBelow)?.takeIf { it.width >= 8f && it.height >= 8f }?.translate(-hostOrigin[0], -hostOrigin[1])
    fun flyFor(id: Long, tile: androidx.compose.ui.geometry.Rect?, px: Int): Fly? {
        val item = gallery.find(id) ?: return null
        // The shape its tile's picture was drawn in (right way up); the phone's listed width and height only when
        // that isn't known, as they can be sideways for a photo taken upright.
        val aspect = thumbShapes[id] ?: if (item.width > 0 && item.height > 0) item.width / item.height.toFloat() else 0f
        return Fly(item.uri, item.isVideo, px, aspect, tile)
    }
    LaunchedEffect(wanted) {
        // The page the back gesture already moved away is removed at once; the rest slide.
        val skip = state.pageBackSkip
        state.pageBackSkip = false
        val commit = state.pageBackCommit
        state.pageBackCommit = false
        val live = entries.filter { !it.closing }
        var same = 0
        while (same < live.size && same < wanted.size && live[same].overlay == wanted[same]) same++
        val leaving = live.drop(same)
        leaving.forEachIndexed { i, entry ->
            entry.closing = true
            if (skip || reduceMotion) {
                entries.remove(entry)
            } else if (commit && i == leaving.lastIndex && entry.overlay.slidesSideways()) {
                // Let go by the back gesture: carries on from where the finger left it, always out to the right.
                val from = (1f - state.pageBack.value).coerceIn(0f, 1f)
                entry.slide?.cancel()
                entry.p.snapTo(from)
                state.pageBack.snapTo(0f)
                entry.slide = scope.launch {
                    entry.p.animateTo(0f, tween((PAGE_MS * from).toInt().coerceAtLeast(60), easing = PageEasing))
                    entries.remove(entry)
                }
            } else {
                entry.slide?.cancel()
                val depthBelow = same + i
                // The viewer: the copy of the photo showing now takes the photo's place at once (the page's own
                // photo is gone the moment it closes), in the same spot, before anything moves.
                val viewer = entry.overlay as? Overlay.Viewer
                val showing = viewer?.let { v -> state.viewerShowing?.takeIf { it in v.ids } ?: v.startId } ?: 0L
                // The page under a viewer is kept built, so its tile can be asked for at once.
                if (viewer != null) {
                    entry.fly = flyFor(showing, tileOf(showing, depthBelow), entry.fly?.px ?: 256)
                    entry.copyOn = entry.fly != null
                }
                entry.slide = scope.launch {
                    // The viewer's buttons fade away quickly under the copy; the viewer itself is taken apart only
                    // after the flight, so nothing heavy happens while the photo moves.
                    if (entry.fly != null) launch { entry.reveal.animateTo(0f, tween(90)) }
                    entry.p.animateTo(0f, pageSpec(entry.overlay))
                    entries.remove(entry)
                }
            }
        }
        wanted.drop(same).forEachIndexed { i, overlay ->
            // The full photo starts loading now, while it flies open (17 Nov).
            if (overlay is Overlay.Viewer) gallery.find(overlay.startId)?.let { FullPhoto.preload(platformContext, it) }
            val entry = PageEntry(overlay, if (reduceMotion) 1f else 0f)
            if (overlay is Overlay.Viewer && !reduceMotion) {
                val tile = tileOf(overlay.startId, same + i)
                entry.fly = flyFor(overlay.startId, tile, tile?.let { thumbBucket(it.width.toInt()) } ?: 256)
            }
            val flying = entry.fly != null
            if (flying) {
                entry.landed = false
                entry.copyOn = true
                entry.reveal.snapTo(0f)
            }
            entries.add(entry)
            if (!reduceMotion) {
                entry.slide = scope.launch {
                    if (!flying) {
                        // Let the new page be built first, so building it never eats into the movement.
                        withFrameNanos { }
                        withFrameNanos { }
                    }
                    // Closed again before it even started (a very quick tap in and out): leave it to the closing slide.
                    if (!entry.closing) entry.p.animateTo(1f, pageSpec(overlay))
                    if (flying && !entry.closing) {
                        // Landed: now the viewer is built (out of sight, under the copy), given a few frames to
                        // draw itself, and faded in; then the copy steps aside.
                        entry.landed = true
                        withFrameNanos { }
                        withFrameNanos { }
                        withFrameNanos { }
                        entry.reveal.animateTo(1f, tween(140))
                        if (!entry.closing) entry.copyOn = false
                    }
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
    Box(
        Modifier.fillMaxSize().onGloballyPositioned {
            val o = it.positionInRoot()
            hostOrigin[0] = o.x
            hostOrigin[1] = o.y
            FullPhoto.width = it.size.width
            FullPhoto.height = it.size.height
        },
    ) {
        entries.forEachIndexed { index, entry ->
            key(entry) {
                val picture = androidx.compose.ui.graphics.rememberGraphicsLayer()
                // A page fully covered by another is not built (as before), except the one a finger is uncovering.
                val covered by remember(entry) {
                    derivedStateOf {
                        val at = entries.indexOf(entry)
                        // A viewer doesn't count as covering (16 Nov): the page under it stays built, so closing a
                        // photo has nothing to rebuild and its tile can be found at once.
                        val full = entries.drop(at + 1).filter { !it.closing && it.p.value >= 1f && it.overlay !is Overlay.Viewer }
                        val top = entries.lastOrNull { !it.closing }
                        full.isNotEmpty() && !(state.pageBack.value > 0f && full.size == 1 && full[0] === top)
                    }
                }
                if (!covered) {
                    val sideways = entry.overlay.slidesSideways()
                    // Black comes in behind the flying photo (and goes out again on closing).
                    // It also takes the taps while the viewer isn't built yet, so they don't reach the grid.
                    if (entry.fly != null) Box(Modifier.fillMaxSize().blockTouchesBelow().drawBehind { drawRect(Color.Black, alpha = entry.p.value.coerceIn(0f, 1f)) })
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val width = size.width
                                if (sideways) {
                                    val top = entries.lastOrNull { !it.closing }
                                    val own = (1f - entry.p.value) * width
                                    // The finger always pulls the page out to the right (6 Nov), whichever edge it starts from.
                                    val pulled = if (entry === top) state.pageBack.value * width else 0f
                                    val drift = -coverAbove(entries, state, entries.indexOf(entry)) * width / DRIFT_DIVISOR
                                    translationX = own + pulled + drift
                                } else {
                                    val p = entry.p.value
                                    if (entry.fly != null) {
                                        // The viewer with a flying copy: the page stays put and only fades, in
                                        // after the copy has landed and out as it leaves, under the copy.
                                        alpha = entry.reveal.value
                                    } else {
                                        val scale = OVERLAY_SCALE + (1f - OVERLAY_SCALE) * p
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = p
                                    }
                                }
                            }
                            .dimmedBy { if (sideways) coverAbove(entries, state, entries.indexOf(entry)) * PAGE_DIM else 0f },
                    ) {
                        if (entry.fly != null) {
                            // The viewer with a flying copy: built only once the copy has landed, and kept built
                            // (hidden) while the copy flies back.
                            if (entry.landed) {
                                CompositionLocalProvider(LocalPageDepth provides index + 1, LocalPageLeaving provides entry.closing) {
                                    OverlayPage(entry.overlay, state, gallery, actions, drive, backup, iconSwitcher, system)
                                }
                            }
                        } else if (!entry.closing) {
                            // Drawn through a picture that is kept, so the closing slide can show it without the page.
                            Box(
                                Modifier.fillMaxSize().drawWithContent {
                                    picture.record { this@drawWithContent.drawContent() }
                                    drawLayer(picture)
                                },
                            ) {
                                // Its depth, so a grid on it can say which page its tiles belong to (16 Nov).
                                CompositionLocalProvider(LocalPageDepth provides index + 1) {
                                    OverlayPage(entry.overlay, state, gallery, actions, drive, backup, iconSwitcher, system)
                                }
                            }
                        } else {
                            // Leaving: only its last picture, which takes no taps.
                            Box(Modifier.fillMaxSize().drawBehind { drawLayer(picture) })
                        }
                        if (sideways) {
                            // A soft shadow runs down the page's leading edge (its left side now).
                            Box(
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = -PAGE_SHADOW_WIDTH)
                                    .width(PAGE_SHADOW_WIDTH)
                                    .fillMaxHeight()
                                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)))),
                            )
                        }
                    }
                    // The flying copy, on top until it has landed (then the viewer's own photo is under it, in place).
                    val fly = entry.fly
                    if (fly != null && entry.copyOn) FlyingCopy(fly) { entry.p.value }
                }
            }
        }
    }
}

/** The photo's small picture at its place for now: cropped to the tile's shape at one end, whole at the other. */
@Composable
private fun FlyingCopy(fly: Fly, progress: () -> Float) {
    coil3.compose.AsyncImage(
        model = com.deepak.photovault.core.media.Thumb(fly.uri, fly.isVideo, fly.px),
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier
            .layout { measurable, constraints ->
                // Sized and placed while laying out, so moving it never rebuilds anything.
                val r = fly.rect(progress().coerceIn(0f, 1f), constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                val w = r.width.toInt().coerceAtLeast(1)
                val h = r.height.toInt().coerceAtLeast(1)
                val placed = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                layout(constraints.maxWidth, constraints.maxHeight) { placed.place(r.left.toInt(), r.top.toInt()) }
            }
            .graphicsLayer {
                val p = progress().coerceIn(0f, 1f)
                // With no tile to go to it fades as it shrinks a little; with one it stays solid all the way.
                if (fly.tile == null) alpha = p
                clip = true
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp * (1f - p))
            },
    )
}

/** A photo's flight between its tile and the viewer: short, quick off the mark and soft at the end, like Google Photos. */
private const val VIEWER_MS = 250
private val ViewerEasing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun pageSpec(overlay: Overlay): androidx.compose.animation.core.FiniteAnimationSpec<Float> =
    if (overlay is Overlay.Viewer) tween(VIEWER_MS, easing = ViewerEasing)
    else if (overlay.slidesSideways()) tween(PAGE_MS, easing = PageEasing) else tween(OVERLAY_MS, easing = FastOutSlowInEasing)

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
            } else if (overlay is Overlay.Story) {
                StoryScreen(overlay, state, gallery, actions)
            } else {
                GlassBackground(Modifier.fillMaxSize()) {
                    when (overlay) {
                        is Overlay.Album -> AlbumScreen(overlay.kind, state, gallery, actions, drive)
                        Overlay.RecentlyDeleted -> RecentlyDeletedScreen(state, gallery, actions)
                        Overlay.DriveRecentlyDeleted -> RecentlyDeletedScreen(state, gallery, actions, driveOnly = true)
                        Overlay.Search -> SearchScreen(state, gallery, actions)
                        Overlay.DriveSetup -> DriveSetupScreen(state, drive)
                        is Overlay.Member -> MemberScreen(overlay.userId, state, drive, actions)
                        Overlay.Backup -> BackupScreen(drive, backup, gallery, actions, state, onBack = { state.back() })
                        Overlay.PrivacyChoice -> PrivacyChoiceScreen(state, drive, backup)
                        Overlay.PrivateSetup -> PrivateSetupScreen(state, drive, backup)
                        Overlay.RestoreKeys -> RestoreKeysScreen(state, drive, backup)
                        Overlay.PinKey -> PinKeyScreen(state, backup)
                        Overlay.ChangeGooglePassword -> ChangeGooglePasswordScreen(state)
                        Overlay.AskHelper -> AskHelperScreen(state, drive)
                        is Overlay.Settings -> SettingsPageScreen(overlay.page, state, drive, iconSwitcher, gallery, actions, backup, overlay.startOnDrive)
                        is Overlay.StorageFiles -> StorageFilesScreen(overlay, state, gallery, drive, backup, actions)
                        is Overlay.CoverPicker -> CoverPickerScreen(overlay, state, gallery)
                        is Overlay.CropCover -> CoverCropScreen(overlay, state, gallery)
                        Overlay.Secured -> SecuredFolderScreen(state)
                        Overlay.People -> PeopleScreen(state, gallery)
                        is Overlay.Person -> PersonScreen(overlay.personId, state, gallery, actions)
                        Overlay.Places -> PlacesScreen(state, gallery)
                        is Overlay.Place -> PlaceScreen(overlay.key, state, gallery, actions)
                        is Overlay.Photos -> PhotoSetScreen(overlay.title, overlay.ids, state, gallery, actions)
                        Overlay.Memories -> MemoriesScreen(state, gallery)
                        Overlay.Tags -> TagsScreen(state, gallery)
                        is Overlay.Tag -> TagScreen(overlay.name, state, gallery, actions)
                        is Overlay.Living -> LivingPhotoScreen(overlay.photoId, state, gallery, actions)
                        is Overlay.Edit -> EditorScreen(overlay.photoId, state, gallery, actions)
                        Overlay.Duplicates -> DuplicatesScreen(state, gallery, actions)
                        Overlay.HiddenAlbums -> HiddenAlbumsScreen(state, gallery)
                        is Overlay.Viewer -> Unit
                        is Overlay.Story -> Unit
                    }
                }
            }
        }
    }
}

/** Every page except the photo viewer slides in from the edge like a sliding door (24 Oct; before: only Settings pages). */
internal fun Overlay?.slidesSideways(): Boolean = this != null && this !is Overlay.Viewer && this !is Overlay.Story

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
                    .graphicsLayer { translationX = -state.sectionCover() * size.width / DRIFT_DIVISOR }
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
            settingsDot = screens.settingsDot,
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
 * Tablets (18 Nov, Deepak: "side bar on tablet instead of the bottom bar", now the default; second pass the same day:
 * "is it supposed to sit in the middle? make it proper"). A navigation rail as Material 3 lays it out on tablets: one
 * glass panel the full height of the left edge, the same frosted fill in every section (it no longer depends on what
 * blurs behind it, which made it clear in Photos and white elsewhere), the sections at the top in line with the
 * pages' top buttons, and a pill behind the chosen icon that slides as you swipe between sections. It stays put while
 * scrolling; while photos or albums are selected it dims and ignores taps, so a tap can't leave a selection.
 */
@Composable
private fun SideBarLayout(screens: Screens, system: PaddingValues) {
    val state = screens.state
    val nav = rememberSectionNav(state)
    val start = system.calculateStartPadding(LayoutDirection.Ltr)
    val railWidth = start + RAIL_WIDTH
    val selecting = state.selecting || state.albumsSelected.isNotEmpty()
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = -state.sectionCover() * size.width / DRIFT_DIVISOR }
            .dimmedBy { state.sectionCover() * PAGE_DIM },
    ) {
        CompositionLocalProvider(
            LocalScreenInsets provides PaddingValues(
                start = 0.dp,
                top = system.calculateTopPadding(),
                end = system.calculateEndPadding(LayoutDirection.Ltr),
                bottom = system.calculateBottomPadding(),
            ),
        ) {
            // The pages take the rest of the width, so nothing sits under the rail.
            Box(Modifier.fillMaxSize().padding(start = railWidth)) { SectionPager(nav.pager, screens) }
        }
        SideRail(
            indicator = { nav.indicator.value },
            onSelect = state::tapSection,
            enabled = !selecting,
            familyDot = screens.familyDot,
            settingsDot = screens.settingsDot,
            modifier = Modifier.fillMaxHeight().width(railWidth),
            // The first section lines up with the pages' top buttons (Years / Months / All, Search).
            contentPadding = PaddingValues(start = start, top = system.calculateTopPadding() + 8.dp),
        )
    }
}

/** How often drive photos deleted in the app are tried again while the drive can't be reached (18 Nov). */
private const val DRIVE_DELETE_RETRY_MS = 5 * 60 * 1000L

/** The tablet's rail: a full-height glass panel, the sections from the top, a sliding pill behind the chosen icon. */
@Composable
private fun SideRail(
    indicator: () -> Float,
    onSelect: (Destination) -> Unit,
    enabled: Boolean,
    familyDot: Boolean,
    settingsDot: Boolean,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val theme = LocalGlassTheme.current
    // The same frosted film as the cards (or solid with the glass effect off), drawn the same way in every section.
    val film = theme.panelFill
    val fill = if (film != null && com.deepak.photovault.designsystem.LocalGlassSettings.current.blurEnabled) film else theme.glassFallback
    val edge = if (theme.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    val dim by androidx.compose.animation.core.animateFloatAsState(if (enabled) 1f else 0.38f, motionSpec(CHROME_MS), label = "railDim")
    Box(
        modifier
            .background(fill)
            .drawBehind { drawRect(edge, topLeft = androidx.compose.ui.geometry.Offset(size.width - 1.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height)) }
            .padding(contentPadding)
            .graphicsLayer { alpha = dim },
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, ((RAIL_ITEM_HEIGHT + RAIL_ITEM_GAP) * indicator()).roundToPx()) }
                .size(RAIL_INDICATOR_WIDTH, RAIL_INDICATOR_HEIGHT)
                .clip(RoundedCornerShape(percent = 50))
                .background(theme.accentSurface)
                .frostedEdge(RoundedCornerShape(percent = 50)),
        )
        Column(
            Modifier.align(Alignment.TopCenter).selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RAIL_ITEM_GAP),
        ) {
            Destination.entries.forEach { destination ->
                RailItem(
                    destination,
                    indicator(),
                    { onSelect(destination) },
                    enabled = enabled,
                    dot = (familyDot && destination == Destination.FAMILY) || (settingsDot && destination == Destination.SETTINGS),
                )
            }
        }
    }
}

/** One section in the rail: the icon in its pill-sized space, the name under it (bolder when chosen). */
@Composable
private fun RailItem(destination: Destination, indicator: Float, onClick: () -> Unit, enabled: Boolean, dot: Boolean) {
    val theme = LocalGlassTheme.current
    val haptics = com.deepak.photovault.designsystem.rememberHaptics()
    val closeness = (1f - kotlin.math.abs(indicator - destination.ordinal)).coerceIn(0f, 1f)
    val selected = closeness > 0.5f
    Column(
        Modifier
            .width(RAIL_ITEM_WIDTH)
            .height(RAIL_ITEM_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = {
                haptics.tick()
                onClick()
            })
            .then(if (dot) Modifier.semantics { stateDescription = "A phone is waiting for approval" } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(RAIL_INDICATOR_WIDTH, RAIL_INDICATOR_HEIGHT), contentAlignment = Alignment.Center) {
            com.deepak.photovault.designsystem.DepthIcon(destination.icon, null, lerp(theme.onGlass, theme.onAccent, closeness), Modifier.size(24.dp))
            if (dot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-12).dp, y = 3.dp)
                        .size(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(theme.badgePhoneOnly),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        com.deepak.photovault.designsystem.DepthText(
            destination.label,
            color = theme.onGlass,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** The rail's sizes: Material 3's 80 dp rail with a little more room for the names, its 56 x 32 dp pill. */
private val RAIL_WIDTH = 88.dp
private val RAIL_ITEM_WIDTH = 80.dp
private val RAIL_ITEM_HEIGHT = 60.dp
private val RAIL_ITEM_GAP = 12.dp
private val RAIL_INDICATOR_WIDTH = 56.dp
private val RAIL_INDICATOR_HEIGHT = 32.dp

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
            LocalShell provides state,
            LocalScrollToTop provides (state.reselects[destination] ?: 0),
            LocalChrome provides if (destination in Destination.hidesChrome) state.chrome else null,
        ) {
            when (destination) {
                Destination.PHOTOS -> PhotosScreen(state, screens.gallery, screens.actions, screens.drive)
                Destination.ALBUMS -> AlbumsScreen(state, screens.gallery, screens.actions, screens.drive)
                Destination.FAMILY -> FamilyScreen(state, screens.drive, screens.actions)
                Destination.SETTINGS -> SettingsScreen(state, screens.drive, screens.iconSwitcher, screens.backup, screens.gallery, screens.actions)
            }
        }
    }
}

/** Phones: a floating glass capsule with one highlight that slides between sections. */
@Composable
private fun FloatingNavigationBar(indicator: () -> Float, onSelect: (Destination) -> Unit, familyDot: Boolean, settingsDot: Boolean, modifier: Modifier = Modifier) {
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
                .clip(RoundedCornerShape(percent = 50))
                .background(theme.accentSurface)
                .frostedEdge(RoundedCornerShape(percent = 50)),
        )
        Row(Modifier.selectableGroup()) {
            Destination.entries.forEach { destination ->
                NavItem(destination, indicator(), { onSelect(destination) }, Modifier.weight(1f), dot = (familyDot && destination == Destination.FAMILY) || (settingsDot && destination == Destination.SETTINGS))
            }
        }
    }
}

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

private fun platform_now(@Suppress("UNUSED_PARAMETER") drive: DriveState): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
