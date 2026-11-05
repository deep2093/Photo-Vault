package com.deepak.photovault.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deepak.photovault.core.media.Timeline
import com.deepak.photovault.core.media.TimelineGrouping
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.LocalGlassTheme
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * People (Phase 7A, 5 Nov): faces found on this phone, grouped into people. Off until turned on; then it scans while
 * the phone charges (or now, when asked). Press and hold to pick people to merge or hide.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeopleScreen(state: ShellState, gallery: GalleryState) {
    val theme = LocalGlassTheme.current
    val people = state.people
    val platform = people.platform
    val scope = rememberCoroutineScope()
    val progress by platform.progress.collectAsState()
    var showHidden by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    val thumbs = remember { mutableStateMapOf<Int, ByteArray>() }
    LaunchedEffect(Unit) { people.refresh() }
    // New people appear while a scan runs (it saves every 40 photos).
    LaunchedEffect(progress?.running, (progress?.done ?: 0) / 40) { if (people.enabled) people.refresh() }
    val onPhone = remember(gallery.items) { gallery.items.map { it.id }.toHashSet() }
    val takenAt = remember(gallery.items) { gallery.items.associate { it.id to it.takenAtMillis } }
    val all = remember(people.people, people.spots, showHidden, onPhone) {
        FaceGrouping.shown(people.people.filter { it.hidden == showHidden }, people.spots, newest = { p -> people.photosOf(p).maxOfOrNull { takenAt[it] ?: 0L } ?: 0L })
            .filter { p -> p.name != null || people.photosOf(p).any { it in onPhone } }
    }
    // Strangers (5 Nov): unnamed people in only a few photos wait under "Others" at the bottom.
    val (list, others) = remember(all, onPhone) {
        all.partition { p -> p.name != null || people.photosOf(p).count { it in onPhone } >= FaceGrouping.OTHERS_BELOW }
    }
    var showOthers by remember { mutableStateOf(false) }
    val question = if (!showHidden) people.nextQuestion() else null
    val selecting = people.selected.isNotEmpty()

    GlassScreen(
        title = if (selecting) "${people.selected.size} selected" else if (showHidden) "Hidden people" else "People",
        subtitle = if (selecting || !people.enabled) null else if (all.size == 1) "1 person" else "${all.size} people",
        onBack = if (selecting) null else ({ if (showHidden) showHidden = false else state.back() }),
        pinned = selecting,
        actions = {
            if (selecting) {
                GlassIconButton(Icons.Rounded.Close, "Cancel selection", onClick = { people.selected = emptySet() })
            } else if (people.enabled) {
                GlassIconButton(
                    if (showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    if (showHidden) "Show people" else "Show hidden people",
                    onClick = { showHidden = !showHidden },
                )
                GlassIconButton(Icons.Rounded.PowerSettingsNew, "Turn off People", onClick = { confirmOff = true })
            }
        },
        bottomActions = if (!selecting) null else ({
            if (people.selected.size >= 2) GlassIconButton(Icons.Rounded.CallMerge, "Merge into one person", onClick = {
                val ids = people.selected.toList()
                people.selected = emptySet()
                scope.launch { people.merge(ids) }
            })
            GlassIconButton(
                if (showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                if (showHidden) "Show again" else "Hide",
                onClick = {
                    val ids = people.selected
                    people.selected = emptySet()
                    scope.launch { people.setHidden(ids, !showHidden) }
                },
            )
        }),
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            // Coming back from a person keeps the place on the page (5 Nov).
            state = rememberSavedGridState("|people"),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = SCREEN_MARGIN, end = SCREEN_MARGIN, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!people.enabled) {
                item(key = "off", span = { GridItemSpan(maxLineSpan) }) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Find people in your photos", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        listOf(
                            "Pixallery looks for faces on this phone and groups the same person together. Then you can name them and search by name.",
                            "Everything stays on this phone. No face, picture or name is sent anywhere.",
                            "It scans while the phone is charging, so it doesn't use your battery. You can also scan now.",
                            "Turning it off later deletes all face data from this phone.",
                        ).forEach { Text("• $it", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
                item(key = "on", span = { GridItemSpan(maxLineSpan) }) {
                    GlassButton(if (platform.supported) "Turn on People" else "Not available on this device", primary = true, onClick = {
                        if (platform.supported) scope.launch {
                            platform.setEnabled(true)
                            people.enabled = true
                            people.refresh()
                        }
                    })
                }
            } else {
                item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                    val p = progress
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            when {
                                p == null -> "Looked at ${people.scanned} photos. More are looked at while the phone charges."
                                p.running -> "Looking for faces: ${p.done} of ${p.total} photos."
                                p.done >= p.total -> "All ${p.total} photos looked at."
                                else -> "Looked at ${p.done} of ${p.total} photos. The rest are looked at while the phone charges."
                            },
                            color = theme.onGlass,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (people.lookingAgain) Text(
                            "Looking at every photo once more to leave out blurred and background faces. Your names and merges stay.",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        val running = p?.running == true
                        GlassButton(if (running) "Pause" else "Scan now", onClick = { if (running) platform.stopScan() else platform.scanNow() }, modifier = Modifier.padding(top = 8.dp))
                        var ask by remember { mutableStateOf(platform.askByNotification()) }
                        com.deepak.photovault.designsystem.GlassSwitchRow(
                            title = "Ask me by notification",
                            checked = ask,
                            onCheckedChange = { ask = it; platform.setAskByNotification(it) },
                            description = "When Pixallery isn't sure two faces are the same person (at most once a day).",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                if (question != null && !selecting) {
                    item(key = "question", span = { GridItemSpan(maxLineSpan) }) {
                        val a = people.find(question.a)
                        val b = people.find(question.b)
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text("Same person?", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            androidx.compose.foundation.layout.Row(
                                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                listOfNotNull(a, b).forEach { p -> PersonCircle(people, p, thumbs, 96, onPhone) }
                            }
                            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassButton("Yes", primary = true, onClick = { scope.launch { people.answer(question, true) } })
                                GlassButton("No", onClick = { scope.launch { people.answer(question, false) } })
                                GlassButton("Not sure", onClick = { people.skipped = people.skipped + question })
                            }
                            val left = people.questions.count { it !in people.skipped } - 1
                            if (left > 0) Text("$left more to check", color = theme.onGlassSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                if (list.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (showHidden) "No hidden people." else "No people yet. They appear here as photos are looked at; someone needs to be in at least two photos.",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
                val visible = if (showOthers) list + others else list
                items(visible, key = { it.id }) { person ->
                    val picked = person.id in people.selected
                    val cover = people.coverOf(person)
                    LaunchedEffect(cover?.id) {
                        if (cover != null && thumbs[cover.id] == null) platform.faceThumb(cover)?.let { thumbs[cover.id] = it }
                    }
                    val count = people.photosOf(person).count { it in onPhone }
                    Column(
                        Modifier.combinedClickable(
                            onClick = {
                                if (selecting) people.selected = if (picked) people.selected - person.id else people.selected + person.id
                                else state.open(Overlay.Person(person.id))
                            },
                            onLongClick = { people.selected = if (picked) people.selected - person.id else people.selected + person.id },
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(84.dp).clip(CircleShape).background(theme.onGlass.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                            val bytes = cover?.let { thumbs[it.id] }
                            if (bytes != null) {
                                AsyncImage(model = bytes, contentDescription = person.name ?: "A person", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(Icons.Rounded.Face, null, tint = theme.onGlassSecondary, modifier = Modifier.size(36.dp))
                            }
                            if (picked) {
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                                Icon(Icons.Rounded.CheckCircle, "Selected", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                        Text(
                            person.name ?: "Add a name",
                            color = if (person.name != null) theme.onGlass else theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (person.name != null) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(if (count == 1) "1 photo" else "$count photos", color = theme.onGlassSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (others.isNotEmpty()) {
                    item(key = "others", span = { GridItemSpan(maxLineSpan) }) {
                        GlassButton(
                            if (showOthers) "Hide others" else "Others (${others.size})",
                            onClick = { showOthers = !showOthers },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmOff) {
        ConfirmDialog(
            title = "Turn off People?",
            body = "All faces and names found on this phone are deleted. Your photos are not touched. You can turn it on again later, and the phone looks for faces again.",
            confirm = "Turn off",
            onDismiss = { confirmOff = false },
            onConfirm = {
                confirmOff = false
                scope.launch {
                    platform.setEnabled(false)
                    people.enabled = false
                    people.selected = emptySet()
                    thumbs.clear()
                    people.refresh()
                }
            },
        )
    }
}

/** One person's photos: name them, hide them, or take out photos that aren't them. */
@Composable
fun PersonScreen(personId: Int, state: ShellState, gallery: GalleryState, actions: MediaActions) {
    val people = state.people
    val scope = rememberCoroutineScope()
    val zone = remember { TimeZone.currentSystemDefault() }
    val person = people.find(personId)
    if (person == null) {
        LaunchedEffect(Unit) { state.back() }
        return
    }
    val photoIds = remember(person, people.spots) { people.photosOf(person) }
    val boxes = remember(person, people.spots) { people.boxesOf(person) }
    val items = remember(photoIds, gallery.items) { gallery.items.filter { it.id in photoIds } }
    val sections = remember(items) { Timeline.group(items, TimelineGrouping.MONTH, zone, today(zone)) }
    var renaming by remember { mutableStateOf(false) }
    val label = person.name ?: "this person"
    SelectableScreen(
        title = person.name ?: "Add a name",
        subtitle = if (items.size == 1) "1 photo" else "${items.size} photos",
        state = state,
        gallery = gallery,
        actions = actions,
        onBack = { state.back() },
        selectAllItems = items,
        normalActions = {
            GlassIconButton(Icons.Rounded.Edit, if (person.name == null) "Add a name" else "Change the name", onClick = { renaming = true })
            GlassIconButton(
                if (person.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                if (person.hidden) "Show this person again" else "Hide this person",
                onClick = { scope.launch { people.setHidden(setOf(person.id), !person.hidden) } },
            )
        },
        selectionActions = { selected ->
            GlassIconButton(Icons.Rounded.Share, "Share", onClick = { actions.share(selected) })
            GlassIconButton(Icons.Rounded.PersonRemove, "Not $label", onClick = {
                val ids = selected.map { it.id }.toSet()
                state.selected = emptySet()
                scope.launch { people.removePhotos(person.id, ids) }
            })
        },
    ) { padding ->
        MediaGrid(
            sections, state, gallery, padding,
            onOpen = { item -> state.open(Overlay.Viewer(items.map { it.id }, item.id)) },
            topButton = true,
            // Which face counted as this person, so a wrong group photo is easy to spot (5 Nov).
            tileMark = { item -> boxes[item.id] },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(person.name.orEmpty()) }
        GlassDialog(
            title = if (person.name == null) "Add a name" else "Change the name",
            onDismiss = { renaming = false },
            buttons = {
                GlassButton("Cancel", onClick = { renaming = false })
                GlassButton("Save", primary = true, onClick = {
                    renaming = false
                    scope.launch { people.rename(person.id, name) }
                })
            },
        ) {
            GlassTextField(name, { name = it }, "Name")
            Text(
                "Using a name you already gave someone else puts them together as one person.",
                color = LocalGlassTheme.current.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** A person's round face picture (the clearest front face), loaded once into [thumbs]. */
@Composable
internal fun PersonCircle(people: PeopleState, person: Person, thumbs: MutableMap<Int, ByteArray>, sizeDp: Int, onPhone: Set<Long>? = null) {
    val theme = LocalGlassTheme.current
    val cover = people.coverOf(person)
    LaunchedEffect(cover?.id) {
        if (cover != null && thumbs[cover.id] == null) people.platform.faceThumb(cover)?.let { thumbs[cover.id] = it }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(theme.onGlass.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            val bytes = cover?.let { thumbs[it.id] }
            if (bytes != null) {
                AsyncImage(model = bytes, contentDescription = person.name ?: "A person", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Rounded.Face, null, tint = theme.onGlassSecondary, modifier = Modifier.size((sizeDp * 0.4f).dp))
            }
        }
        if (onPhone != null) {
            val count = people.photosOf(person).count { it in onPhone }
            Text(person.name ?: (if (count == 1) "1 photo" else "$count photos"), color = theme.onGlassSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
