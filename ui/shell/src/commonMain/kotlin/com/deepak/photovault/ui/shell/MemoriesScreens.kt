package com.deepak.photovault.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.core.media.Thumb
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.core.media.TimelineGrouping
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.motionSpec
import com.deepak.photovault.designsystem.glassPanel
import com.deepak.photovault.designsystem.rememberHaptics
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/** Today's memories, worked out once per photo list and day. */
@Composable
internal fun rememberTodayMemories(gallery: GalleryState): Pair<List<Memory>, List<Memory>> {
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Timeline.localDate(Clock.System.now().toEpochMilliseconds(), zone)
    return remember(gallery.items, today) {
        val day = Memories.find(gallery.items, today, zone)
        day to Memories.thisMonth(gallery.items, today, zone, shown = day.flatMap { it.ids }.toHashSet())
    }
}

/** Today's date as "2026-11-08", for the dot on the Memories button. */
internal fun todayKey(): String = Timeline.localDate(Clock.System.now().toEpochMilliseconds(), TimeZone.currentSystemDefault()).toString()

/**
 * The Memories button on Photos (8 Nov, in place of the row at the top): always there, with a small dot on days that
 * have On this day photos you haven't looked at yet.
 */
@Composable
internal fun MemoriesButton(state: ShellState, gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    val (day, _) = rememberTodayMemories(gallery)
    val fresh = day.isNotEmpty() && state.appearance.memoriesSeen != todayKey()
    val dot by animateFloatAsState(if (fresh) 1f else 0f, motionSpec(), label = "memoriesDot")
    Box {
        SmallPillButton(Icons.Rounded.History, if (fresh) "Memories, new today" else "Memories") { state.open(Overlay.Memories) }
        if (dot > 0f) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(8.dp)
                    .graphicsLayer { scaleX = dot; scaleY = dot; alpha = dot }
                    .clip(CircleShape)
                    .background(theme.accent),
            )
        }
    }
}

/**
 * Albums > Memories, also the Memories button on Photos (8 Nov): On this day (today's date, or this week, in earlier
 * years) and This month in other years. Tap a card for its photos.
 */
@Composable
fun MemoriesScreen(state: ShellState, gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    val (day, month) = rememberTodayMemories(gallery)
    // Looking counts as seen: the dot on Photos goes until tomorrow.
    LaunchedEffect(Unit) {
        val key = todayKey()
        if (state.appearance.memoriesSeen != key) state.updateAppearance { it.copy(memoriesSeen = key) }
    }
    val byId = remember(gallery.items) { gallery.items.associateBy { it.id } }
    GlassListScreen(
        title = "Memories",
        subtitle = when (val n = day.size + month.size) { 0 -> null; 1 -> "1 memory"; else -> "$n memories" },
        onBack = { state.back() },
    ) {
        if (day.isEmpty() && month.isEmpty()) {
            item(key = "empty") {
                GlassCard(Modifier.cardWidth().padding(horizontal = SCREEN_MARGIN)) {
                    Text("No memories today", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Photos you took on this day, or this month, in earlier years show here. Come back another day.",
                        color = theme.onGlassSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (day.isNotEmpty()) {
            item(key = "dayTitle") { SectionTitle("On this day") }
            // The newest year large, the rest in a row below.
            item(key = "dayHero") {
                MemoryCard(day.first(), byId, width = null, height = 260.dp, big = true, modifier = Modifier.cardWidth().padding(horizontal = SCREEN_MARGIN)) {
                    state.open(Overlay.Photos(day.first().title, day.first().ids))
                }
            }
            if (day.size > 1) item(key = "dayRow") { MemoryRow(day.drop(1), byId, state) }
        }
        if (month.isNotEmpty()) {
            item(key = "monthTitle") { SectionTitle("This month in other years") }
            item(key = "monthRow") { MemoryRow(month, byId, state) }
        }
        item(key = "end") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val theme = LocalGlassTheme.current
    Text(
        text,
        color = theme.onGlass,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.cardWidth().padding(horizontal = SCREEN_MARGIN + 4.dp).padding(top = 4.dp),
    )
}

@Composable
private fun MemoryRow(list: List<Memory>, byId: Map<Long, MediaItem>, state: ShellState) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = SCREEN_MARGIN),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(list, key = { "${it.year}-${it.month}" }) { m ->
            MemoryCard(m, byId, width = 150.dp, height = 200.dp, big = false, modifier = Modifier.animateItem()) {
                state.open(Overlay.Photos(m.title, m.ids))
            }
        }
    }
}

/** A photo card with the words on a soft dark fade; presses in a little, like the app's other cards. */
@Composable
private fun MemoryCard(
    m: Memory,
    byId: Map<Long, MediaItem>,
    width: Dp?,
    height: Dp,
    big: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, motionSpec(140), label = "memoryPress")
    val cover = m.ids.firstNotNullOfOrNull { byId[it] }
    val shape = RoundedCornerShape(if (big) 26.dp else 20.dp)
    Box(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(theme.onGlass.copy(alpha = 0.08f))
            .combinedClickable(interactionSource = press, indication = null, role = Role.Button, onClick = { haptics.tap(); onClick() }),
    ) {
        if (cover != null) {
            coil3.compose.AsyncImage(
                model = Thumb(cover.uri, cover.isVideo, if (big) 720 else 384),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.68f))))
        Column(Modifier.align(Alignment.BottomStart).padding(if (big) 16.dp else 12.dp)) {
            Text(
                if (m.month > 0) m.title else m.ago,
                color = Color.White,
                style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val count = if (m.ids.size == 1) "1 photo" else "${m.ids.size} photos"
            Text(
                if (m.month > 0) "${m.ago} · $count" else if (m.sameDay) "${m.year} · $count" else "This week in ${m.year} · $count",
                color = Color.White.copy(alpha = 0.85f),
                style = if (big) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Albums > Tags (7C-2, 8 Nov): every tag with its newest photo and count, most used first. Tap for its photos; hold
 * to rename or delete it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TagsScreen(state: ShellState, gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    val tags = state.tags
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (!tags.loaded) tags.refresh() }
    val list = remember(tags.byKey, gallery.items) { tags.list(gallery.items) }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val haptics = rememberHaptics()
    GlassListScreen(
        title = "Tags",
        subtitle = when (list.size) { 0 -> null; 1 -> "1 tag"; else -> "${list.size} tags" },
        onBack = { state.back() },
    ) {
        if (list.isEmpty()) {
            item(key = "empty") {
                GlassCard(Modifier.cardWidth().padding(horizontal = SCREEN_MARGIN)) {
                    Text("No tags yet", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Open a photo, swipe up for its details and tap Add tag. Your tags stay on your phone, and go with your backup to the family drive, locked like People.",
                        color = theme.onGlassSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        items(list.size, key = { "tag:" + list[it].first.lowercase() }) { i ->
            val (tag, photos) = list[i]
            val cover = photos.firstOrNull()
            Row(
                Modifier
                    .cardWidth()
                    .padding(horizontal = SCREEN_MARGIN)
                    .animateItem()
                    .glassPanel(com.deepak.photovault.designsystem.GlassCardShape)
                    .clip(com.deepak.photovault.designsystem.GlassCardShape)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = { state.open(Overlay.Tag(tag)) },
                        onLongClick = { haptics.longPress(); editing = tag },
                    )
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(theme.onGlass.copy(alpha = 0.08f))) {
                    if (cover != null) {
                        coil3.compose.AsyncImage(Thumb(cover.uri, cover.isVideo, 192), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(tag, color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (photos.size == 1) "1 photo" else "${photos.size} photos", color = theme.onGlassSecondary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (list.isNotEmpty()) {
            item(key = "hint") {
                Text(
                    "Hold a tag to rename or delete it.",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.cardWidth().padding(horizontal = SCREEN_MARGIN + 4.dp, vertical = 4.dp),
                )
            }
        }
    }
    editing?.let { tag ->
        var name by remember(tag) { mutableStateOf(tag) }
        GlassDialog(
            title = "Tag",
            onDismiss = { editing = null },
            buttons = {
                GlassButton("Delete", onClick = { editing = null; deleting = tag })
                GlassButton("Cancel", onClick = { editing = null })
                GlassButton("Save", primary = true, onClick = {
                    val clean = TagsState.clean(name)
                    editing = null
                    if (clean != null && clean != tag) scope.launch { tags.rename(tag, clean) }
                })
            },
        ) {
            GlassTextField(name, { name = it }, "Name", capitals = androidx.compose.ui.text.input.KeyboardCapitalization.Words)
        }
    }
    deleting?.let { tag ->
        ConfirmDialog(
            title = "Delete \"$tag\"?",
            body = "The tag comes off every photo. The photos themselves stay.",
            confirm = "Delete tag",
            onDismiss = { deleting = null },
        ) {
            deleting = null
            scope.launch { tags.delete(tag) }
        }
    }
}

/** One tag's photos, newest first, like an album (7C-2). */
@Composable
fun TagScreen(tag: String, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val zone = remember { TimeZone.currentSystemDefault() }
    LaunchedEffect(Unit) { if (!state.tags.loaded) state.tags.refresh() }
    val items = remember(tag, state.tags.byKey, gallery.items) { state.tags.photosWith(tag, gallery.items).sortedByDescending { it.takenAtMillis } }
    val sections = remember(items) { Timeline.group(items, TimelineGrouping.MONTH, zone, today(zone)) }
    SelectableScreen(
        title = tag,
        subtitle = if (items.size == 1) "1 item" else "${items.size} items",
        state = state,
        gallery = gallery,
        actions = actions,
        onBack = { state.back() },
        selectAllItems = items,
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(title = "Nothing here now", body = "No photos have this tag any more.")
        } else {
            MediaGrid(
                sections, state, gallery, padding,
                onOpen = { item -> state.open(Overlay.Viewer(sections.flatMap { s -> s.items.map { it.id } }, item.id)) },
            )
        }
    }
}

/**
 * The photo details' Tags (7C-2): its tags as small chips (tap the cross to take one off) and Add tag. Drawn in the
 * viewer's dark details panel, so its own dark colours.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailsTags(item: MediaItem, tags: TagsState, white: Color, grey: Color) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (!tags.loaded) tags.refresh() }
    val mine = remember(item.id, tags.byKey) { tags.tagsOf(item) }
    var adding by remember { mutableStateOf(false) }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        mine.forEach { tag ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1C1C21)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tag, color = white, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .combinedClickable(role = Role.Button, onClickLabel = "Remove tag $tag", onClick = { scope.launch { tags.remove(item, tag) } }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, "Remove tag $tag", tint = grey, modifier = Modifier.size(16.dp))
                }
            }
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFF1C1C21))
                .combinedClickable(role = Role.Button, onClick = { adding = true })
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, null, tint = white, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add tag", color = white, style = MaterialTheme.typography.labelLarge)
        }
    }
    if (adding) {
        AddTagDialog(tags, already = mine, onDismiss = { adding = false }) { tag ->
            adding = false
            scope.launch { tags.add(listOf(item), tag) }
        }
    }
}

/** Write a new tag, or tap one you've used before (the closest matches first). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddTagDialog(tags: TagsState, already: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val theme = LocalGlassTheme.current
    var text by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val taken = already.map { it.lowercase() }.toSet()
    val q = text.trim().lowercase()
    val suggestions = tags.all().filter { it.lowercase() !in taken && (q.isEmpty() || it.lowercase().contains(q)) }
        .sortedBy { if (it.lowercase().startsWith(q)) 0 else 1 }
        .take(12)
    GlassDialog(
        title = "Add tag",
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton("Add", primary = true, onClick = { TagsState.clean(text)?.let(onAdd) })
        },
    ) {
        GlassTextField(
            text,
            { text = it.take(TagsState.MAX_LENGTH) },
            "Tag",
            placeholder = "Birthday, Holiday, Receipts…",
            capitals = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
            focus = focus,
            onDone = { TagsState.clean(text)?.let(onAdd) },
        )
        if (suggestions.isNotEmpty()) {
            Text(
                if (q.isEmpty()) "Your tags" else "Matching tags",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { s -> GlassChip(s, selected = false, onClick = { onAdd(s) }) }
            }
        }
    }
}
