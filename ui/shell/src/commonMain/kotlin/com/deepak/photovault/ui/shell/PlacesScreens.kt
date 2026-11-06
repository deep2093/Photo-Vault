package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.core.media.TimelineGrouping
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassPanel
import kotlinx.datetime.TimeZone

/**
 * Albums > Places (7B-2, 7 Nov): a map with your photos on it (like Apple Photos and Samsung Gallery). Zoomed far out,
 * one bubble per country; then towns and cities; closer in, photos near each other. Tap a bubble to zoom in, or, when it
 * can't split any more, to see its photos. A sheet at the bottom shows the photos in view and your trips; it can be
 * pulled down to a small bar, or up to see them all.
 *
 * Smooth (7 Nov fixes): bubbles are grouped again only when the map stops moving; while it moves they just follow the
 * map, which only moves them on screen (no rebuilding).
 */
@Composable
fun PlacesScreen(state: ShellState, gallery: GalleryState) {
    val places = state.places
    val theme = LocalGlassTheme.current
    val platform = places.platform
    val reading by platform.progress.collectAsState()
    LaunchedEffect(Unit) {
        platform.readNow()
        places.refresh()
    }
    // Read again as more photos get their place.
    LaunchedEffect(reading == null) { places.refresh() }
    val shown = remember(gallery.items) { gallery.items.associateBy { it.id } }
    val points = remember(places.photos, shown) {
        places.photos.mapNotNull { (id, p) -> shown[id]?.let { MapPoint(id, p.latitude, p.longitude, it.takenAtMillis, p.place) } }
    }
    val trips = remember(points) { Trips.find(points) }
    val zone = remember { TimeZone.currentSystemDefault() }
    val controller = remember { MapController() }
    // [live] follows every frame of movement (only moves bubbles); [settled] is where the map stopped (groups them).
    val live = remember { mutableStateOf<MapViewport?>(null) }
    var settled by remember { mutableStateOf<MapViewport?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cellPx = with(density) { 72.dp.toPx() }
    val bubbles = remember(settled, points) { settled?.let { MapClusters.bubbles(points, it, cellPx) }.orEmpty() }
    val inView = remember(settled, points) { settled?.let { v -> points.filter { v.contains(it.lat, it.lon) }.sortedByDescending { it.takenAt }.map { it.id } }.orEmpty() }
    GlassScreen(
        title = "Places",
        subtitle = when {
            reading != null -> "Finding places: ${reading!!.done} of ${reading!!.total}"
            points.isEmpty() -> null
            else -> "${points.size} photos with a place"
        },
        onBack = { state.back() },
        pinned = true,
    ) { padding ->
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenHeight = maxHeight
            // The map waits for the photos, so it opens on your newest photo (zoomed in), not somewhere empty.
            if (places.loaded) {
                val start = remember {
                    places.focus?.also { places.focus = null }
                        ?: points.maxByOrNull { it.takenAt }?.let { MapStart(it.lat, it.lon, 11.0) }
                        ?: MapStart(0.0, 0.0, 1.5)
                }
                PhotoMap(
                    modifier = Modifier.fillMaxSize(),
                    dark = false,
                    start = start,
                    interactive = true,
                    controller = controller,
                    onViewport = { v, idle ->
                        live.value = v
                        if (idle || settled == null) settled = v
                    },
                )
            }
            // The bubbles, drawn over the map where their photos are.
            val bubbleSize = 54.dp
            val half = with(density) { (bubbleSize / 2).toPx() }
            bubbles.forEach { b ->
                androidx.compose.runtime.key(b.key) {
                    val item = shown[b.cover]
                    Column(
                        Modifier
                            .offset {
                                // Read while placing only: the map moving just shifts the bubble.
                                val at = live.value?.toScreen(b.lat, b.lon) ?: b.at
                                androidx.compose.ui.unit.IntOffset((at.x - half).toInt(), (at.y - half).toInt())
                            }
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) {
                                val zoom = settled?.zoom ?: 0.0
                                if (b.ids.size > 1 && zoom < 16.5) {
                                    controller.fly(b.lat, b.lon, MapClusters.zoomInto(b, zoom))
                                } else {
                                    state.open(Overlay.Photos(b.label ?: "Photos here", b.ids))
                                }
                            },
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        Box {
                            Box(
                                Modifier
                                    .size(bubbleSize)
                                    .shadow(8.dp, androidx.compose.foundation.shape.CircleShape)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(androidx.compose.ui.graphics.Color.White)
                                    .padding(3.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape),
                            ) {
                                if (item != null) {
                                    coil3.compose.AsyncImage(
                                        model = Thumb(item.uri, item.isVideo, 160),
                                        contentDescription = b.label,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            if (b.ids.size > 1) {
                                Text(
                                    if (b.ids.size > 999) "999+" else "${b.ids.size}",
                                    color = androidx.compose.ui.graphics.Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .align(androidx.compose.ui.Alignment.TopEnd)
                                        .offset(x = 8.dp, y = (-4).dp)
                                        .shadow(3.dp, RoundedCornerShape(10.dp))
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(theme.accent)
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                        if (b.label != null) {
                            Text(
                                b.label,
                                color = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .shadow(3.dp, RoundedCornerShape(10.dp))
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(androidx.compose.ui.graphics.Color.White)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            if (!platform.canReadLocations()) {
                GlassCard(Modifier.align(androidx.compose.ui.Alignment.Center).padding(horizontal = SCREEN_MARGIN)) {
                    Text("See where your photos were taken", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Android keeps photo locations hidden until you allow it. Places are worked out on this phone.",
                        color = theme.onGlassSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                    )
                    GlassButton("Allow", primary = true, onClick = { platform.askForLocations() })
                }
            }
            PlacesSheet(
                state = state,
                shown = shown,
                inView = inView,
                trips = trips,
                zone = zone,
                screenHeight = screenHeight,
                bottomPadding = padding.calculateBottomPadding(),
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
            )
        }
    }
}

/**
 * The sheet under the map (7B-2; 7 Nov: drag it). Three resting heights: a small bar (just the title, so the map is
 * free), the middle (a strip of the photos in view and your trips), and almost the whole screen (every photo in view).
 * It follows the finger and settles with a soft spring; only its position changes while moving, so it stays smooth.
 */
@Composable
private fun PlacesSheet(
    state: ShellState,
    shown: Map<Long, com.deepak.photovault.core.media.MediaItem>,
    inView: List<Long>,
    trips: List<Trip>,
    zone: TimeZone,
    screenHeight: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    val theme = LocalGlassTheme.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val full = screenHeight * 0.78f
    val small = 64.dp + bottomPadding
    val middle = (if (trips.isEmpty()) 190.dp else 360.dp) + bottomPadding
    val fullPx = with(density) { full.toPx() }
    val stops = listOf(with(density) { small.toPx() }, with(density) { middle.toPx() }, fullPx)
    // How much of the sheet shows, in pixels.
    val shownPx = remember { androidx.compose.animation.core.Animatable(stops[1]) }
    LaunchedEffect(stops[1]) { if (!shownPx.isRunning && shownPx.value != stops[0] && shownPx.value != fullPx) shownPx.snapTo(stops[1]) }
    fun settle(velocity: Float) {
        val now = shownPx.value
        // A flick goes on to the next stop in its direction; otherwise the nearest stop.
        val target = when {
            velocity < -800f -> stops.firstOrNull { it > now + 1f } ?: stops.last()
            velocity > 800f -> stops.lastOrNull { it < now - 1f } ?: stops.first()
            else -> stops.minBy { kotlin.math.abs(it - now) }
        }
        scope.launch {
            shownPx.animateTo(target, androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 380f), initialVelocity = -velocity)
        }
    }
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { delta ->
        scope.launch { shownPx.snapTo((shownPx.value - delta).coerceIn(stops.first(), stops.last())) }
    }
    val grab = Modifier.draggable(
        drag,
        androidx.compose.foundation.gestures.Orientation.Vertical,
        onDragStopped = { v -> settle(v) },
    )
    Column(
        modifier
            .fillMaxWidth()
            .height(full)
            .graphicsLayer { translationY = fullPx - shownPx.value }
            .padding(horizontal = 6.dp)
            .shadow(16.dp, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            // Solid, so photos and words are easy to see over the map.
            .background(if (theme.isDark) androidx.compose.ui.graphics.Color(0xFF1C1C1F) else androidx.compose.ui.graphics.Color(0xFFFAFAFC)),
    ) {
        Column(grab) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .align(androidx.compose.ui.Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(theme.onGlassSecondary.copy(alpha = 0.45f)),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    if (inView.size == 1) "1 photo here" else "${inView.size} photos here",
                    color = theme.onGlass,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (inView.isNotEmpty()) {
                    Text(
                        "See all",
                        color = theme.accent,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { state.open(Overlay.Photos("Photos here", inView)) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(inView.take(40), key = { it }) { id ->
                    val item = shown[id] ?: return@items
                    coil3.compose.AsyncImage(
                        model = Thumb(item.uri, item.isVideo, 256),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .size(92.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { state.open(Overlay.Viewer(inView, id)) },
                    )
                }
            }
            if (trips.isNotEmpty()) {
                Text(
                    "Trips",
                    color = theme.onGlass,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(trips, key = { it.start }) { trip ->
                        val title = tripTitle(trip, zone)
                        val cover = shown[trip.ids.first()]
                        Column(
                            Modifier
                                .width(150.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { state.open(Overlay.Photos(title, trip.ids)) },
                        ) {
                            Box(Modifier.size(150.dp, 100.dp).clip(RoundedCornerShape(16.dp))) {
                                if (cover != null) {
                                    coil3.compose.AsyncImage(
                                        model = Thumb(cover.uri, cover.isVideo, 256),
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Text(title, color = theme.onGlass, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text("${trip.ids.size} photos", color = theme.onGlassSecondary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Text(
                "All photos here",
                color = theme.onGlass,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        // Every photo in view, newest first (seen when the sheet is pulled up).
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = bottomPadding + 12.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(inView, key = { it }) { id ->
                val item = shown[id] ?: return@items
                coil3.compose.AsyncImage(
                    model = Thumb(item.uri, item.isVideo, 256),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { state.open(Overlay.Viewer(inView, id)) },
                )
            }
        }
    }
}

/** "Sydney, March 2026" (a trip over two months: "Sydney, Mar–Apr 2026"). */
private fun tripTitle(trip: Trip, zone: TimeZone): String {
    val a = kotlinx.datetime.Instant.fromEpochMilliseconds(trip.start).toLocalDateTime(zone)
    val b = kotlinx.datetime.Instant.fromEpochMilliseconds(trip.end).toLocalDateTime(zone)
    val when_ = if (a.month == b.month && a.year == b.year) {
        "${Timeline.monthName(a.month)} ${a.year}"
    } else if (a.year == b.year) {
        "${Timeline.monthName(a.month).take(3)}–${Timeline.monthName(b.month).take(3)} ${b.year}"
    } else {
        "${Timeline.monthName(a.month).take(3)} ${a.year}–${Timeline.monthName(b.month).take(3)} ${b.year}"
    }
    return "${trip.title}, $when_"
}

/** A set of photos with a title (a map bubble, a trip; 7B-2). */
@Composable
fun PhotoSetScreen(title: String, ids: List<Long>, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val wanted = remember(ids) { ids.toHashSet() }
    val items = remember(wanted, gallery.items) { gallery.items.filter { it.id in wanted } }
    val sections = remember(items) { Timeline.group(items, TimelineGrouping.MONTH, zone, today(zone)) }
    SelectableScreen(
        title = title,
        subtitle = if (items.size == 1) "1 item" else "${items.size} items",
        state = state,
        gallery = gallery,
        actions = actions,
        onBack = { state.back() },
        selectAllItems = items,
    ) { padding ->
        MediaGrid(
            sections, state, gallery, padding,
            onOpen = { item -> state.open(Overlay.Viewer(sections.flatMap { s -> s.items.map { it.id } }, item.id)) },
        )
    }
}

/** One place's photos and videos, newest first, like an album. */
@Composable
fun PlaceScreen(key: String, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val place = remember(key) { Place.fromKey(key) }
    val ids = remember(key, state.places.photos) { state.places.photosAt(key) }
    val items = remember(ids, gallery.items) { gallery.items.filter { it.id in ids } }
    val sections = remember(items) { Timeline.group(items, TimelineGrouping.MONTH, zone, today(zone)) }
    SelectableScreen(
        title = place?.name ?: "Place",
        subtitle = listOfNotNull(place?.let { it.region.ifBlank { it.country } }, if (items.size == 1) "1 item" else "${items.size} items").joinToString(" · "),
        state = state,
        gallery = gallery,
        actions = actions,
        onBack = { state.back() },
        selectAllItems = items,
    ) { padding ->
        MediaGrid(
            sections, state, gallery, padding,
            onOpen = { item -> state.open(Overlay.Viewer(sections.flatMap { s -> s.items.map { it.id } }, item.id)) },
        )
    }
}
