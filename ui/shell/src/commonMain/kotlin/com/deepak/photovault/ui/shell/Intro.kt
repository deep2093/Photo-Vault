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
enum class IntroKind { TOUR, WHATS_NEW, PERMISSIONS }

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

/** What's new for the current build. Change [ID] and [items] with each update worth announcing. */
object WhatsNew {
    const val ID = "2026-10-26"
    const val TITLE = "What's new"
    val items: List<Pair<String, String>> = listOf(
        "Remote access" to "Settings > Remote access connects Pixallery to your home router with WireGuard, so the family drive works away from home. It is on only while Pixallery is on screen and can be limited to this app. Away-from-home backup in the background is optional and off by default.",
        "Family Album" to "A shared album the whole family sees. Select backed-up photos and tap the family button (also in the viewer). It holds links to each person's own backup, never copies, and you can only remove photos you added.",
        "Cloud badge" to "Photos that are only on the family drive show a small cloud on their thumbnail, and their Details say so.",
        "Recently deleted on the drive" to "Photos you delete that live only on the drive wait 30 days in Recently deleted before they are gone for good, and can be restored.",
        "Share drive photos" to "Photos that are only on the drive can be shared: Pixallery fetches them first.",
        "One timeline" to "Photos you backed up to the family drive now stay in Photos and Albums even after you delete them from the phone.",
        "Pages slide like a door" to "Settings and other pages slide in from the left edge and follow your finger when you swipe back.",
        "New theme sections" to "Themes are sorted into Light, Dark and Photo, and Photo Light is the new default. Aurora Frost and Transparent are gone.",
        "Lens glass (experimental)" to "In the Clear themes the bars and pills bend the photos behind them like thick glass. Turn it on in Settings > Appearance (Android 13+).",
        "Keep the bottom bar in place" to "A new switch in Settings > Appearance stops the bottom bar tucking away while you scroll.",
        "Animated storage rings" to "Both rings in Settings > Storage sweep round when the page opens.",
        "Smaller top pills" to "Years / Months / All and Search are more compact.",
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
    fun start() {
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
            Text("Android will ask for three things, one after another:", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            listOf(
                "Photos and videos" to "so Pixallery can show and back them up.",
                "Notifications" to "so you can see when a backup finishes or needs you.",
                "Run in the background" to "so backups can run on time while the app is closed.",
            ).forEach { (title, body) ->
                Column {
                    Text(title, color = theme.onGlass, style = MaterialTheme.typography.titleSmall)
                    Text(body, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("You can change any of these later in Settings.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
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
            items(WhatsNew.items) { (title, body) ->
                Column {
                    Text(title, color = theme.onGlass, style = MaterialTheme.typography.titleSmall)
                    Text(body, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
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
