package com.deepak.photovault.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.LocalGlassTheme
import kotlinx.coroutines.launch

/** What the intro shows: the Welcome tour (new people) or What's new (after an update). */
enum class IntroKind { TOUR, WHATS_NEW, PERMISSIONS, BACKGROUND }

/** What the phone remembers about the intro. Android: SharedPreferences. */
interface IntroStore {
    /** The What's new list the person has already seen ([WhatsNew.ID] of that time), or empty. */
    fun seenId(): String
    fun tourDone(): Boolean
    fun save(seenId: String, tourDone: Boolean)

    /** Whether this phone already had Pixallery set up before the intro existed (so no tour for them). */
    fun existingUser(): Boolean
}

class MemoryIntroStore(
    private var seen: String = "",
    private var tour: Boolean = false,
    private val existing: Boolean = false,
) : IntroStore {
    override fun seenId() = seen
    override fun tourDone() = tour
    override fun save(seenId: String, tourDone: Boolean) {
        seen = seenId
        tour = tourDone
    }

    override fun existingUser() = existing
}

/** Pure rules for what shows when the app opens (tested). */
object IntroLogic {
    data class Decision(val show: IntroKind?, val seenId: String, val tourDone: Boolean)

    /**
     * A new person gets the tour once (and no What's new right after it). Someone who already used the app gets no
     * tour, only What's new when the list has changed since they last saw it.
     */
    fun decide(seenId: String, tourDone: Boolean, existingUser: Boolean, currentId: String): Decision = when {
        !tourDone && !existingUser -> Decision(IntroKind.TOUR, currentId, true)
        seenId != currentId -> Decision(IntroKind.WHATS_NEW, currentId, true)
        else -> Decision(null, seenId, tourDone)
    }
}

/** Every update says what is new, what changed, what was removed and what was fixed (rule from 25 Oct, docs/ROADMAP.md). */
enum class ChangeKind(val heading: String) { NEW("New"), CHANGED("Changed"), REMOVED("Removed"), FIXED("Fixed") }

class Change(val kind: ChangeKind, val title: String, val body: String)

/** What's new for the current build. Change [ID] and [items] with EVERY build, covering all four kinds. */
object WhatsNew {
    const val ID = "2026-10-27-2"
    const val TITLE = "What's new"
    val items: List<Change> = listOf(
        Change(ChangeKind.NEW, "Private backup", "Settings > Backup lets you choose Normal or Private. Private scrambles photos on your phone before they leave it, and you get 24 recovery words. Even the family owner can't open them."),
        Change(ChangeKind.NEW, "Photos filter", "A cloud button in Photos shows both, only this phone, or only the drive. Prefer chips? Settings > Appearance > Photos filter as chips."),
        Change(ChangeKind.NEW, "Recently deleted on the drive", "Settings > Storage > Drive has its own Recently deleted at the bottom."),
        Change(ChangeKind.NEW, "Invite files and no-code invites", "An invite can skip the code, and an invite file opens in Pixallery from WhatsApp or Files. Joining away from home turns the VPN on by itself."),
        Change(ChangeKind.NEW, "Photos from other apps", "A photo opened from another app shows Share, Save and Details."),
        Change(ChangeKind.NEW, "More menu in the viewer", "The three dots at the top right of a photo hold Set as wallpaper (home screen, lock screen or both), Details and Move to Secured folder (not working yet)."),
        Change(ChangeKind.NEW, "Move to another album", "Select photos inside an album and tap the album button to move them to another album, or to a new one."),
        Change(ChangeKind.NEW, "App cache", "Tap App cache in Phone storage to see its size and clear it. Photos and backups are never deleted."),
        Change(ChangeKind.NEW, "Charts with depth", "Storage rings now look like thick 3D rings. Turn them off in Settings > Appearance for flat rings."),
        Change(ChangeKind.CHANGED, "Phone storage ring", "The ring shows Photos, Videos, App cache and Free."),
        Change(ChangeKind.NEW, "Back to top button in albums", "Inside an album, a round button appears when you are not at the top. It hides while you scroll down and glides you back up when tapped."),
        Change(ChangeKind.NEW, "Set as wallpaper button", "The photo toolbar has a wallpaper button between Favourite and Delete. Swipe up on a photo for Details."),
        Change(ChangeKind.NEW, "Move to Secured folder button", "When you select photos, a lock button sits between Favourite and Delete. It does nothing until the Secured folder arrives."),
        Change(ChangeKind.CHANGED, "Photos", "Photos always shows one timeline: your phone's photos and the ones only on the drive. A photo in both places shows once."),
        Change(ChangeKind.CHANGED, "Storage ring", "The 3D ring has clean, solid sides and no smudgy shadow. Parts touch with a thin line instead of a gap, and tiny parts like PNG and GIF always show as a small wedge (their real size is in the list)."),
        Change(ChangeKind.CHANGED, "Details button", "Details moved from the bottom bar of a photo to the three-dot menu."),
        Change(ChangeKind.CHANGED, "Storage groups", "Storage now counts Photos (JPEG and HEIC), Videos (every video type), PNG and GIF, and you can sort by Type too."),
        Change(ChangeKind.CHANGED, "Recently deleted", "Newest first, with a note that items go for good after 30 days."),
        Change(ChangeKind.CHANGED, "Settings order", "Open with Pixallery, Send feedback and About always come last; the rest are A to Z."),
        Change(ChangeKind.CHANGED, "App icon name", "The Four Seasons icon is now called Petals."),
        Change(ChangeKind.REMOVED, "Three-dot menu in the viewer", "Gone: wallpaper has its own button, Details opens by swiping up, and Secured folder moved to the selection bar."),
        Change(ChangeKind.REMOVED, "Photos filter", "The cloud button, the filter chips and their setting in Appearance are gone."),
        Change(ChangeKind.REMOVED, "Backup history", "The History card is gone from Backup."),
        Change(ChangeKind.REMOVED, "Old Petals icons", "Phones that used them now show the new Petals icon."),
        Change(ChangeKind.FIXED, "Holding a photo selected many", "A long-press now selects one photo; only swiping selects more."),
        Change(ChangeKind.FIXED, "Back from Years and Months", "Back steps up one level at a time before the app closes."),
        Change(ChangeKind.FIXED, "Places kept", "Albums keep their scroll place after you view a photo, and Storage stays on the Drive tab."),
        Change(ChangeKind.FIXED, "Blurry thumbnails", "iPhone JPEGs no longer look blurry. Their sharp thumbnails are saved once (up to 300 MB), on the phone and for the drive."),
        Change(ChangeKind.FIXED, "Scroll to top", "Tapping Photos while scrolled down now works like Samsung Gallery: from far down it jumps closer, scrolls up at a steady speed, then eases off earlier and earlier as the top gets close, so it settles at the top instead of hitting it."),
        Change(ChangeKind.FIXED, "Phone clock in the viewer", "The time and notification icons show while the toolbar shows (white on the black viewer), and hide with it."),
        Change(ChangeKind.FIXED, "Private setup", "Restore my key appears when your key already exists, and the Switch to Normal pop-up buttons line up."),
    )
}

/** The Welcome tour pages. */
object Tour {
    class Page(val icon: ImageVector, val title: String, val body: String)

    val pages = listOf(
        Page(Icons.Rounded.PhotoLibrary, "All your photos, one place", "Photos shows your phone's pictures and videos. Pinch to make them bigger or smaller, or switch between Years, Months and All."),
        Page(Icons.Rounded.PhotoAlbum, "Albums that are yours", "Pick covers, drag albums into your order and hide the ones you don't want to see."),
        Page(Icons.Rounded.CloudDone, "Backed up at home", "Pixallery copies your photos to the family drive on your router and checks every copy before counting it as saved. Nothing goes to the internet."),
        Page(Icons.Rounded.Groups, "Your family, your own space", "Everyone gets their own folder and a space limit on the shared drive. A Family Album holds what you all want to share."),
        Page(Icons.Rounded.Lock, "Private by design", "No accounts, no cloud, no ads. You can open this tour again any time from Settings > About."),
    )
}

/** Which intro is on screen, and what the phone has already shown. */
@Stable
class IntroState(private val store: IntroStore = MemoryIntroStore()) {
    var showing by mutableStateOf<IntroKind?>(null)
        private set

    /** Called once when the app opens. */
    private var started = false

    fun start() {
        if (started) return
        started = true
        val d = IntroLogic.decide(store.seenId(), store.tourDone(), store.existingUser(), WhatsNew.ID)
        autoTour = d.show == IntroKind.TOUR
        showing = d.show
        store.save(d.seenId, d.tourDone)
    }

    /** Opened again by hand from Settings > About. */
    fun open(kind: IntroKind) {
        showing = kind
    }

    /** Asks for photo access, notifications and background running, one after another (set by the platform). */
    var setUpAccess: (() -> Unit)? = null

    private var autoTour = false

    fun close() {
        // New people: after the tour comes the one-time setup of what backup needs (25 Oct).
        if (showing == IntroKind.TOUR && autoTour && setUpAccess != null) {
            autoTour = false
            showing = IntroKind.PERMISSIONS
        } else {
            showing = null
        }
    }

    /** Opens this app's own page in Android's settings, where battery use is set (set by the platform). */
    var openBackgroundSettings: (() -> Unit)? = null

    /** After photo access and notifications: how to let backups run in the background, step by step. */
    fun showBackgroundHelp() {
        showing = IntroKind.BACKGROUND
    }

    fun finishBackground(open: Boolean) {
        showing = null
        if (open) openBackgroundSettings?.invoke()
    }

    fun finishAccess(go: Boolean) {
        showing = null
        if (go) setUpAccess?.invoke()
    }
}

/** Placed once, above every page. */
@Composable
fun IntroHost(intro: IntroState) {
    when (intro.showing) {
        IntroKind.WHATS_NEW -> WhatsNewDialog(onClose = intro::close)
        IntroKind.TOUR -> WelcomeTour(onClose = intro::close)
        IntroKind.BACKGROUND -> BackgroundDialog(onSkip = { intro.finishBackground(false) }, onOpen = { intro.finishBackground(true) })
        IntroKind.PERMISSIONS -> AccessDialog(onLater = { intro.finishAccess(false) }, onContinue = { intro.finishAccess(true) })
        null -> Unit
    }
}

@Composable
private fun AccessDialog(onLater: () -> Unit, onContinue: () -> Unit) {
    val theme = LocalGlassTheme.current
    GlassDialog(
        title = "Let's set up backup",
        onDismiss = onLater,
        buttons = {
            GlassButton("Not now", onClick = onLater)
            GlassButton("Continue", primary = true, onClick = onContinue)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Android will ask for two things, one after another:", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            listOf(
                "Photos and videos" to "so Pixallery can show and back them up.",
                "Notifications" to "so you can see when a backup finishes or needs you.",
            ).forEach { (title, body) ->
                Column {
                    Text(title, color = theme.onGlass, style = MaterialTheme.typography.titleSmall)
                    Text(body, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("After that, one more screen shows how to let backups run on time while the app is closed. You can change all of this later in Settings.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BackgroundDialog(onSkip: () -> Unit, onOpen: () -> Unit) {
    val theme = LocalGlassTheme.current
    GlassDialog(
        title = "Let backups run on time",
        onDismiss = onSkip,
        buttons = {
            GlassButton("Skip", onClick = onSkip)
            GlassButton("Open app settings", primary = true, onClick = onOpen)
        },
    ) {
        LazyColumn(Modifier.heightInMax(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "Android puts apps to sleep to save battery, and a sleeping app can miss its backup time. " +
                        "Tell Android that Pixallery may run in the background:",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(
                listOf(
                    "1. Tap \"Open app settings\"" to "Pixallery's page in Android settings opens.",
                    "2. Tap Battery" to "It may be called \"App battery usage\", \"Battery usage\" or \"Power\".",
                    "3. Choose Unrestricted" to "Some phones say \"Don't optimise\", \"No restrictions\" or \"Allow background activity\".",
                    "4. Go back to Pixallery" to "Use the back arrow or swipe back. That's all.",
                ),
            ) { (title, body) ->
                Column {
                    Text(title, color = theme.onGlass, style = MaterialTheme.typography.titleSmall)
                    Text(body, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                Text(
                    "On Xiaomi, Oppo, Vivo, Huawei and OnePlus phones there can also be an \"Autostart\" or \"Allow background running\" switch in the same place: turn it on too. " +
                        "You can do this later from Backup, under \"Run on time in background\".",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun WhatsNewDialog(onClose: () -> Unit) {
    val theme = LocalGlassTheme.current
    GlassDialog(
        title = WhatsNew.TITLE,
        onDismiss = onClose,
        buttons = { GlassButton("Got it", primary = true, onClick = onClose) },
    ) {
        LazyColumn(Modifier.heightInMax(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChangeKind.entries.forEach { kind ->
                val list = WhatsNew.items.filter { it.kind == kind }
                if (list.isNotEmpty()) {
                    item { Text(kind.heading, color = theme.accent, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
                    items(list) { c ->
                        Column {
                            Text(c.title, color = theme.onGlass, style = MaterialTheme.typography.titleSmall)
                            Text(c.body, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.heightInMax(): Modifier = this.then(Modifier.height(340.dp))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WelcomeTour(onClose: () -> Unit) {
    val theme = LocalGlassTheme.current
    val pages = Tour.pages
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    Box(Modifier.fillMaxSize().background(theme.wallpaper.first())) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!last) GlassButton("Skip", onClick = onClose)
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                val page = pages[i]
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(112.dp).clip(CircleShape).background(theme.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(page.icon, contentDescription = null, tint = theme.accent, modifier = Modifier.size(56.dp))
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(page.title, color = theme.onGlass, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(page.body, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 16.dp)) {
                pages.indices.forEach { i ->
                    Box(Modifier.size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape).background(if (i == pager.currentPage) theme.accent else theme.onGlassSecondary.copy(alpha = 0.4f)))
                }
            }
            GlassButton(
                if (last) "Get started" else "Next",
                primary = true,
                onClick = { if (last) onClose() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                modifier = Modifier.width(176.dp),
                centered = true,
            )
        }
    }
}
