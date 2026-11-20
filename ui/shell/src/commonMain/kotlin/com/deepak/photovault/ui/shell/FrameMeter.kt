package com.deepak.photovault.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.solidGlass
import kotlinx.coroutines.delay

/*
 * The smoothness meter (Phase 12, 20 Nov; a test in Settings > About). Phase 12 is done when the main flows drop no
 * frames on a real phone, and this is how to check: a small counter at the top of the screen says how many of the
 * frames drawn since it was turned on came late. The Android app counts them with the system's own frame timings, so
 * the counting adds nothing to the frames it measures. A tap opens where they came from (20 Nov, Deepak: "make a meter
 * to show"): each page and section with its own numbers, the worst first, so the worst spot can be fixed.
 */

/** Frames counted since the meter started or was last reset. */
data class FrameCounts(
    /** Frames drawn. */
    val frames: Int = 0,
    /** Frames that missed the screen's deadline (a stutter you may see). */
    val slow: Int = 0,
    /** Frames that took longer than 0.7 seconds (the screen froze). */
    val frozen: Int = 0,
)

/** Counts drawn frames and late ones. Android: the window's frame timings; elsewhere there is none. */
interface FrameMeter {
    fun start()
    fun stop()
    fun counts(): FrameCounts

    /** The same counts for each place frames were drawn in (see [setPlace]). */
    fun places(): Map<String, FrameCounts>

    /** Where the frames drawn from now on belong: the page on top, or the section under it. */
    fun setPlace(place: String)
    fun reset()
}

/** The share of frames on time, as a whole percent (100 with nothing counted yet). */
internal fun smoothPercent(counts: FrameCounts): Int =
    if (counts.frames <= 0) 100 else ((counts.frames - counts.slow) * 100L / counts.frames).toInt()

/** The places, worst first: the most late frames for their number, then the most frames. */
internal fun worstPlaces(places: Map<String, FrameCounts>): List<Pair<String, FrameCounts>> =
    places.entries
        .filter { it.value.frames > 0 }
        .sortedWith(compareBy<Map.Entry<String, FrameCounts>>({ smoothPercent(it.value) }, { -it.value.slow }, { -it.value.frames }))
        .map { it.key to it.value }

/**
 * The counter itself: a small glass pill with the total. A tap opens the late frames by page (worst first) with Count
 * again; another tap closes it. [place] is the page on screen now, so each frame is counted where it was drawn.
 */
@Composable
internal fun FrameMeterBadge(meter: FrameMeter, place: String, modifier: Modifier = Modifier) {
    DisposableEffect(meter) {
        meter.start()
        onDispose { meter.stop() }
    }
    LaunchedEffect(meter, place) { meter.setPlace(place) }
    var counts by remember { mutableStateOf(FrameCounts()) }
    var byPlace by remember { mutableStateOf<List<Pair<String, FrameCounts>>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(meter) {
        while (true) {
            counts = meter.counts()
            byPlace = worstPlaces(meter.places())
            delay(METER_REFRESH_MS)
        }
    }
    val theme = LocalGlassTheme.current
    val words = buildString {
        append("${smoothPercent(counts)}% smooth · ${counts.slow} late of ${counts.frames}")
        if (counts.frozen > 0) append(" · ${counts.frozen} frozen")
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            words,
            color = theme.onGlass,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .solidGlass(RoundedCornerShape(percent = 50))
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide where they came from" else "Show where they came from") { open = !open }
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
        if (open) {
            Column(
                Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = METER_PANEL_WIDTH)
                    .solidGlass(RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Late frames by screen", color = theme.onGlass, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (byPlace.isEmpty()) {
                    Text("Nothing counted yet. Scroll, pinch or open a photo.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
                }
                byPlace.take(METER_ROWS).forEach { (name, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            color = theme.onGlass,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                        )
                        Text(
                            "${smoothPercent(c)}% · ${c.slow} of ${c.frames}" + if (c.frozen > 0) " · ${c.frozen} frozen" else "",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (smoothPercent(c) < 95) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
                Text(
                    "Count again",
                    color = theme.onGlass,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(role = Role.Button) {
                            meter.reset()
                            counts = meter.counts()
                            byPlace = emptyList()
                        }
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

/** What the meter calls the page on screen: the page on top, else the section (Photos with its Years, Months or All). */
internal fun meterPlace(state: ShellState, gallery: GalleryState): String = when (val top = state.overlays.lastOrNull()) {
    null -> if (state.destination == Destination.PHOTOS) "Photos · ${gallery.view.label}" else state.destination.label
    is Overlay.Viewer -> if (top.trash) "Recently deleted photo" else "Photo viewer"
    is Overlay.Story -> "Memory story"
    is Overlay.Album -> "Album"
    is Overlay.Photos -> "Photos: ${top.title}"
    is Overlay.Person -> "Person"
    is Overlay.Place -> "Place"
    is Overlay.Tag -> "Tag"
    is Overlay.Settings -> "Settings page"
    is Overlay.Edit -> "Editor"
    is Overlay.Living -> "Living photo"
    is Overlay.StorageFiles -> "Storage files"
    is Overlay.Member -> "Family member"
    Overlay.Search -> "Search"
    Overlay.RecentlyDeleted -> "Recently deleted"
    Overlay.DriveRecentlyDeleted -> "Drive's Recently deleted"
    Overlay.Secured -> "Secured folder"
    Overlay.People -> "People"
    Overlay.Places -> "Places"
    Overlay.Memories -> "Memories"
    Overlay.Tags -> "Tags"
    Overlay.Duplicates -> "Duplicates"
    Overlay.Backup -> "Backup"
    else -> top?.let { it::class.simpleName } ?: "Page"
}

/** How often the counter on screen is brought up to date. */
private const val METER_REFRESH_MS = 500L

/** How many pages the open meter lists, and how wide it gets. */
private const val METER_ROWS = 8
private val METER_PANEL_WIDTH = 320.dp
