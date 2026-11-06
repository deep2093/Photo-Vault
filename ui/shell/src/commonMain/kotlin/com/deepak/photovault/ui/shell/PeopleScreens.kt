package com.deepak.photovault.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
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
    var naming by remember { mutableStateOf<Person?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // The AuraFace download and any problem live here now (6 Nov): no status box, only a card when something is wrong.
    var downloading by remember { mutableStateOf<Float?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val running = progress?.running == true
    var showBlocked by remember { mutableStateOf(false) }
    var cutShort by remember { mutableStateOf(false) }
    // A scan that Android stopped (6 Nov): said once, the next time People opens.
    LaunchedEffect(people.enabled, people.testModel) { if (platform.scanCutShort()) cutShort = true }
    fun afterSwitch(on: Boolean) {
        people.testModel = on
        people.enabled = platform.isEnabled()
        people.selected = emptySet()
        people.skipped = emptySet()
        people.skippedUnsure = emptySet()
        thumbs.clear()
        people.resetScroll()
    }
    // Switching only changes which list is shown (6 Nov); AuraFace has its own setup and never starts by itself.
    fun switchModel(on: Boolean) {
        if (downloading != null) return
        scope.launch {
            problem = null
            platform.setTestModel(on) { }
            afterSwitch(on)
            people.refresh()
        }
    }
    fun setUpAura() {
        if (downloading != null) return
        scope.launch {
            problem = null
            downloading = 0f
            val result = platform.setUpTestModel { downloading = it }
            downloading = null
            if (result == null) {
                afterSwitch(true)
                people.refresh()
                platform.scanNow()
            } else {
                problem = result
            }
        }
    }
    val question = if (!showHidden) people.nextQuestion() else null
    // Faces the app isn't sure about (6 Nov): asked after the "Same person?" questions; [picking] opens the people grid.
    val unsure = if (!showHidden && question == null) people.nextUnsure() else null
    var picking by remember { mutableStateOf<UnsureSet?>(null) }
    var pickingParked by remember { mutableStateOf(false) }
    val selecting = people.selected.isNotEmpty()
    // The place on the page lives in PeopleState (5 Nov): a person page scrolled to its end used to reset it.
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState(people.scrollIndex, people.scrollOffset)
    LaunchedEffect(grid) {
        androidx.compose.runtime.snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { (i, o) ->
            if (state.overlays.lastOrNull() == Overlay.People) {
                people.scrollIndex = i
                people.scrollOffset = o
            }
        }
    }

    GlassScreen(
        title = if (selecting) "${people.selected.size} selected" else if (showHidden) "Hidden people" else "People",
        subtitle = when {
            selecting || !people.enabled -> null
            downloading != null -> "Downloading AuraFace: ${((downloading ?: 0f) * 100).toInt()}%"
            else -> (if (all.size == 1) "1 person" else "${all.size} people") + if (people.testModel) " · AuraFace" else ""
        },
        onBack = if (selecting) null else ({ if (showHidden) showHidden = false else state.back() }),
        pinned = selecting,
        actions = {
            if (selecting) {
                GlassIconButton(Icons.Rounded.Close, "Cancel selection", onClick = { people.selected = emptySet() })
            } else if (people.enabled) {
                // Scan sits in the top bar (6 Nov); a ring around it fills as the scan (or the AuraFace download) goes.
                val p = progress
                val fraction = when {
                    downloading != null -> downloading
                    running && p != null && p.total > 0 -> p.done.toFloat() / p.total
                    else -> null
                }
                // Always the same size as the buttons next to it (6 Nov); the ring is drawn inside it, only the filled part.
                Box(Modifier.size(com.deepak.photovault.designsystem.MinTouchTarget), contentAlignment = Alignment.Center) {
                    if (fraction != null) {
                        val ring = theme.accent
                        androidx.compose.foundation.Canvas(Modifier.size(36.dp)) {
                            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            drawArc(ring, -90f, 360f * fraction.coerceIn(0.02f, 1f), false, style = stroke)
                        }
                    }
                    GlassIconButton(
                        if (running) Icons.Rounded.Pause else Icons.Rounded.Refresh,
                        if (running) "Pause looking for faces (${p?.done ?: 0} of ${p?.total ?: 0})" else "Scan now",
                        onClick = { if (running) platform.stopScan() else platform.scanNow() },
                    )
                }
                GlassIconButton(
                    if (showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    if (showHidden) "Show people" else "Show hidden people",
                    onClick = { showHidden = !showHidden },
                )
            }
            // The menu is there even when this list is off (6 Nov), so the other model's list can always be reached.
            if (!selecting) GlassIconButton(Icons.Rounded.MoreVert, "More options", onClick = { menuOpen = !menuOpen })
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
        overlay = {
            // A small menu (6 Nov): the AuraFace test and turning off.
            GlassMenuLayer(menuOpen && !selecting, onDismiss = { menuOpen = false }, solid = true) {
                GlassMenuItem(
                    Icons.Rounded.Science,
                    "AuraFace test model",
                    onClick = { menuOpen = false; switchModel(!people.testModel) },
                    selected = people.testModel,
                    trailing = if (people.testModel) Icons.Rounded.Check else null,
                )
                if (people.enabled) {
                    val blockedCount = people.people.count { it.blocked }
                    if (blockedCount > 0) {
                        GlassMenuItem(Icons.Rounded.Block, "Blocked people ($blockedCount)", onClick = { menuOpen = false; showBlocked = true })
                    }
                    GlassMenuDivider()
                    GlassMenuItem(
                        Icons.Rounded.PowerSettingsNew,
                        if (people.testModel) "Turn off AuraFace test" else "Turn off People",
                        onClick = { menuOpen = false; confirmOff = true },
                    )
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            // Coming back from a person keeps the place on the page (5 Nov).
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = SCREEN_MARGIN, end = SCREEN_MARGIN, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!people.enabled) {
                item(key = "off", span = { GridItemSpan(maxLineSpan) }) {
                    // Short and plain (6 Nov): where it runs, that it can be wrong, and the first scan while charging.
                    GlassCard(Modifier.fillMaxWidth()) {
                        if (people.testModel) {
                            Text("Try AuraFace", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            listOf(
                                "AuraFace is a second face model you can try. It makes its own list, with its own names; your usual people stay as they are.",
                                "It downloads once (about 66 MB, on Wi-Fi only).",
                                "It is slower than the usual model and may make more or fewer mistakes. Have a look through the people it finds.",
                                "Best started while charging: the first look takes a while, uses battery and the phone may get a bit warm.",
                                "Everything stays on this phone.",
                            ).forEach { Text("• $it", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                        } else {
                            Text("Find people in your photos", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            listOf(
                                "Pixallery finds faces in your photos and puts the same person together, so you can name them and search by name.",
                                "It all happens on this phone. No face, photo or name is sent anywhere.",
                                "It can get some faces wrong, so have a look through your people afterwards.",
                                "The first look takes a while. It is best done while charging: it uses battery and the phone may get a bit warm.",
                                "Turning it off later deletes its face data from this phone.",
                            ).forEach { Text("• $it", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                }
                item(key = "on", span = { GridItemSpan(maxLineSpan) }) {
                    // Only as wide as its words (5 Nov).
                    Box(Modifier.fillMaxWidth()) {
                        val label = when {
                            !platform.supported -> "Not available on this device"
                            people.testModel && downloading != null -> "Downloading: ${((downloading ?: 0f) * 100).toInt()}%"
                            people.testModel -> "Set up AuraFace"
                            else -> "Turn on People"
                        }
                        GlassButton(label, primary = true, onClick = {
                            if (!platform.supported) return@GlassButton
                            if (people.testModel) {
                                setUpAura()
                            } else {
                                scope.launch {
                                    platform.setEnabled(true)
                                    people.enabled = true
                                    people.refresh()
                                }
                            }
                        })
                    }
                }
                val trouble = problem
                if (trouble != null) {
                    item(key = "offProblem", span = { GridItemSpan(maxLineSpan) }) {
                        Text(trouble, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                // Only when something is wrong (6 Nov): the scan progress is the ring on the scan button.
                val trouble = problem ?: progress?.problem
                if (trouble != null) {
                    item(key = "problem", span = { GridItemSpan(maxLineSpan) }) {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text(trouble, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
                            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
                                GlassButton("OK", onClick = { problem = null; platform.clearProblem() })
                            }
                        }
                    }
                }
                if (question != null && !selecting) {
                    item(key = "question", span = { GridItemSpan(maxLineSpan) }) {
                        val a = people.find(question.a)
                        val b = people.find(question.b)
                        // Compact and centred (5 Nov): each face over its own count, the buttons under them.
                        GlassCard(Modifier.fillMaxWidth()) {
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Same person?", color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                val left = people.questions.count { it !in people.skipped } - 1
                                if (left > 0) Text("$left more", color = theme.onGlassSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                            androidx.compose.foundation.layout.Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.Top,
                            ) {
                                listOfNotNull(a, b).forEach { p -> PersonCircle(people, p, thumbs, 72, onPhone) }
                            }
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                                GlassButton("Yes", primary = true, onClick = { scope.launch { people.answer(question, true) } })
                                GlassButton("No", onClick = { scope.launch { people.answer(question, false) } })
                                GlassButton("Not sure", onClick = { people.skipped = people.skipped + question })
                            }
                        }
                    }
                }
                if (unsure != null && !selecting) {
                    item(key = "unsure", span = { GridItemSpan(maxLineSpan) }) {
                        val guess = unsure.ranked.firstOrNull()?.let { people.find(it.first) }
                        GlassCard(Modifier.fillMaxWidth()) {
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (guess?.name != null) "Is this ${guess.name}?" else "Is this the same person?",
                                    color = theme.onGlass,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                val photos = unsure.faces.size
                                Text(if (photos == 1) "1 photo" else "$photos photos", color = theme.onGlassSecondary, style = MaterialTheme.typography.labelSmall)
                            }
                            androidx.compose.foundation.layout.Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SetFaces(people, unsure, thumbs, 52)
                                Icon(Icons.Rounded.SwapHoriz, null, tint = theme.onGlassSecondary, modifier = Modifier.size(20.dp))
                                if (guess != null) PersonCircle(people, guess, thumbs, 64, onPhone)
                            }
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                                GlassButton("Yes", primary = true, onClick = { scope.launch { people.answerUnsure(unsure, guess?.id, true) } })
                                GlassButton("No", onClick = {
                                    val id = guess?.id ?: return@GlassButton
                                    scope.launch {
                                        people.answerUnsure(unsure, id, false)
                                        // Last guess said No: the people grid, closest first (6 Nov).
                                        if (unsure.guesses <= 1) {
                                            picking = people.unsure.firstOrNull { s -> s.faces.any { it in unsure.faces } }
                                                ?: unsure.copy(ranked = unsure.ranked.filter { it.first != id }, guesses = 0)
                                            pickingParked = false
                                        }
                                    }
                                })
                                GlassButton("Choose", onClick = { picking = unsure; pickingParked = false })
                                GlassButton("Skip", onClick = { unsure.faces.firstOrNull()?.let { people.skippedUnsure = people.skippedUnsure + it } })
                            }
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
                            // Tapping "Add a name" opens the name box straight away (6 Nov).
                            modifier = Modifier.padding(top = 6.dp).then(if (person.name == null && !selecting) Modifier.clickable { naming = person } else Modifier),
                            color = if (person.name != null) theme.onGlass else theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (person.name != null) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text((if (person.me) "You · " else "") + if (count == 1) "1 photo" else "$count photos", color = theme.onGlassSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (people.parked.isNotEmpty() && !showHidden && !selecting) {
                    item(key = "notSure", span = { GridItemSpan(maxLineSpan) }) {
                        // Faces put aside with "None of these" (6 Nov): sort them whenever you like.
                        val count = people.parked.sumOf { it.size }
                        GlassButton(
                            "Not sure ($count)",
                            onClick = {
                                picking = UnsureSet(people.parked.first(), emptyList(), 0)
                                pickingParked = true
                            },
                            modifier = Modifier.padding(top = 4.dp),
                        )
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

    picking?.let { set ->
        PickPersonDialog(
            people = people,
            set = set,
            thumbs = thumbs,
            noneLabel = if (pickingParked) "Leave for now" else "None of these",
            onDismiss = { picking = null },
            onPick = { id ->
                picking = null
                scope.launch { people.answerUnsure(set, id, yes = true) }
            },
            onNone = {
                picking = null
                if (!pickingParked) scope.launch { people.answerUnsure(set, null, yes = false, park = true) }
            },
        )
    }
    naming?.let { p ->
        NameDialog(p, onDismiss = { naming = null }) { name ->
            naming = null
            scope.launch { people.rename(p.id, name) }
        }
    }
    if (confirmOff) {
        ConfirmDialog(
            // One model at a time (6 Nov): the other model's list stays.
            title = if (people.testModel) "Turn off the AuraFace test?" else "Turn off People?",
            body = if (people.testModel) {
                "The faces and names AuraFace found are deleted, and your usual people come back. Your photos are not touched."
            } else {
                "The faces and names of your usual list are deleted from this phone (the AuraFace list stays). Your photos are not touched. You can turn it on again later."
            },
            confirm = "Turn off",
            onDismiss = { confirmOff = false },
            onConfirm = {
                confirmOff = false
                scope.launch {
                    platform.setEnabled(false)
                    people.enabled = platform.isEnabled()
                    people.testModel = platform.usesTestModel()
                    people.selected = emptySet()
                    people.skipped = emptySet()
                    people.resetScroll()
                    thumbs.clear()
                    people.refresh()
                }
            },
        )
    }
    if (cutShort) {
        // Generic for every phone brand (6 Nov): the app's own settings page, where battery use is set.
        ConfirmDialog(
            title = "Looking for faces stopped early",
            body = "Your phone stopped Pixallery while it was looking for faces, probably to save battery. To let it finish, open Pixallery's settings, then Battery, and choose Unrestricted (or turn off battery optimisation). Then tap Scan again.",
            confirm = "Open settings",
            onDismiss = { cutShort = false; platform.clearCutShort() },
            onConfirm = {
                cutShort = false
                platform.clearCutShort()
                platform.openBatterySettings()
            },
        )
    }
    if (showBlocked) {
        BlockedDialog(people, thumbs, onDismiss = { showBlocked = false }) { id ->
            scope.launch { people.setBlocked(setOf(id), false) }
        }
    }
}

/** Blocked people (6 Nov): each with Unblock. */
@Composable
private fun BlockedDialog(people: PeopleState, thumbs: MutableMap<Int, ByteArray>, onDismiss: () -> Unit, onUnblock: (Int) -> Unit) {
    val theme = LocalGlassTheme.current
    val blocked = people.people.filter { it.blocked }
    GlassDialog(
        title = "Blocked people",
        onDismiss = onDismiss,
        buttons = { GlassButton("Done", onClick = onDismiss) },
    ) {
        Text(
            "Blocked people don't show in People, search or questions, and new photos of them are left out.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (blocked.isEmpty()) Text("No one is blocked.", color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(blocked.size, key = { blocked[it].id }) { i ->
                val p = blocked[i]
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PersonCircle(people, p, thumbs, 44)
                    Text(
                        p.name ?: "${people.photosOf(p).size} photos",
                        color = theme.onGlass,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp).weight(1f),
                    )
                    GlassButton("Unblock", onClick = { onUnblock(p.id) })
                }
            }
        }
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
    var moving by remember { mutableStateOf<Set<Long>?>(null) }
    var blocking by remember { mutableStateOf(false) }
    val label = person.name ?: "this person"
    SelectableScreen(
        title = person.name ?: if (person.me) "Me" else "Add a name",
        subtitle = (if (person.me) "You · " else "") + if (items.size == 1) "1 photo" else "${items.size} photos",
        state = state,
        gallery = gallery,
        actions = actions,
        onBack = { state.back() },
        selectAllItems = items,
        normalActions = {
            GlassIconButton(Icons.Rounded.Edit, if (person.name == null) "Add a name" else "Change the name", onClick = { renaming = true })
            // "This is me" (6 Nov): filled when this person is you.
            GlassIconButton(
                if (person.me) Icons.Rounded.AccountCircle else Icons.Rounded.PersonOutline,
                if (person.me) "This is me (tap to undo)" else "This is me",
                onClick = {
                    val becoming = !person.me
                    scope.launch {
                        people.setMe(person.id)
                        actions.toast(if (becoming) "Marked as you. Search \"me\" to find your photos." else "No longer marked as you.")
                    }
                },
            )
            GlassIconButton(
                if (person.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                if (person.hidden) "Show this person again" else "Hide this person",
                onClick = { scope.launch { people.setHidden(setOf(person.id), !person.hidden) } },
            )
            // Block (6 Nov): gone from People, search and questions; undo from the People menu.
            GlassIconButton(Icons.Rounded.Block, "Block this person", onClick = { blocking = true })
        },
        selectionActions = { selected ->
            GlassIconButton(Icons.Rounded.Share, "Share", onClick = { actions.share(selected) })
            GlassIconButton(Icons.Rounded.SwapHoriz, "Move to another person", onClick = { moving = selected.map { it.id }.toSet() })
            GlassIconButton(Icons.Rounded.PersonRemove, "Not $label", onClick = {
                val ids = selected.map { it.id }.toSet()
                state.selected = emptySet()
                scope.launch { people.removePhotos(person.id, ids) }
            })
        },
    ) { padding ->
        MediaGrid(
            sections, state, gallery, padding,
            onOpen = { item -> state.open(Overlay.Viewer(sections.flatMap { s -> s.items.map { it.id } }, item.id)) },
            topButton = true,
            // Which face counted as this person, so a wrong group photo is easy to spot (5 Nov).
            tileMark = { item -> boxes[item.id] },
        )
    }
    moving?.let { photoIds ->
        MoveToPersonDialog(
            people = people,
            fromId = person.id,
            onDismiss = { moving = null },
            onPick = { toId ->
                moving = null
                state.selected = emptySet()
                scope.launch { people.moveTo(person.id, photoIds, toId) }
            },
        )
    }
    if (blocking) {
        ConfirmDialog(
            title = "Block ${person.name ?: "this person"}?",
            body = "They won't show in People, search or questions, and new photos of them are left out. Your photos are not touched. You can unblock them from the menu in People.",
            confirm = "Block",
            onDismiss = { blocking = false },
            onConfirm = {
                blocking = false
                scope.launch {
                    people.setBlocked(setOf(person.id), true)
                    state.back()
                }
            },
        )
    }
    if (renaming) {
        NameDialog(person, onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch { people.rename(person.id, name) }
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

/** A few faces of an unsure set, overlapping (6 Nov). */
@Composable
private fun SetFaces(people: PeopleState, set: UnsureSet, thumbs: MutableMap<Int, ByteArray>, sizeDp: Int) {
    val theme = LocalGlassTheme.current
    val shown = set.faces.mapNotNull { people.spots[it] }.take(FaceGrouping.SET_SAMPLES)
    Box(Modifier.width((sizeDp + (sizeDp / 2) * (shown.size - 1).coerceAtLeast(0)).dp).height(sizeDp.dp)) {
        shown.forEachIndexed { i, spot ->
            LaunchedEffect(spot.id) { if (thumbs[spot.id] == null) people.platform.faceThumb(spot)?.let { thumbs[spot.id] = it } }
            Box(
                Modifier
                    .offset(x = ((sizeDp / 2) * i).dp)
                    .size(sizeDp.dp)
                    .clip(CircleShape)
                    .background(theme.onGlass.copy(alpha = 0.1f)),
            ) {
                thumbs[spot.id]?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

/**
 * "Who is this?" (6 Nov): a grid like the People page, the closest match first down to the least, plus a new person
 * and "None of these".
 */
@Composable
private fun PickPersonDialog(
    people: PeopleState,
    set: UnsureSet,
    thumbs: MutableMap<Int, ByteArray>,
    noneLabel: String,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
    onNone: () -> Unit,
) {
    val theme = LocalGlassTheme.current
    val choices = remember(people.people, people.spots, set) {
        val shown = FaceGrouping.shown(people.people.filter { !it.hidden }, people.spots)
        val order = set.ranked.map { it.first }
        val ranked = order.mapNotNull { id -> shown.firstOrNull { it.id == id } }
        ranked + shown.filter { it.id !in order }
    }
    GlassDialog(
        title = "Who is this?",
        onDismiss = onDismiss,
        buttons = {
            GlassButton(noneLabel, onClick = onNone)
            GlassButton("Cancel", onClick = onDismiss)
        },
    ) {
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) { SetFaces(people, set, thumbs, 48) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(84.dp),
            modifier = Modifier.heightIn(max = 420.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(choices, key = { it.id }) { p ->
                Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable { onPick(p.id) }.padding(4.dp), contentAlignment = Alignment.Center) {
                    PersonCircle(people, p, thumbs, 60)
                }
            }
            item(key = "new") {
                Column(
                    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable { onPick(null) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(60.dp).clip(CircleShape).background(theme.onGlass.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Face, null, tint = theme.accent)
                    }
                    Text("New person", color = theme.onGlassSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** "Move to…" (5 Nov): every other person with their face, plus a new person. */
@Composable
private fun MoveToPersonDialog(people: PeopleState, fromId: Int, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    val theme = LocalGlassTheme.current
    val thumbs = remember { mutableStateMapOf<Int, ByteArray>() }
    val others = remember(people.people, people.spots) {
        FaceGrouping.shown(people.people.filter { it.id != fromId && !it.hidden }, people.spots)
    }
    GlassDialog(
        title = "Move to",
        onDismiss = onDismiss,
        buttons = { GlassButton("Cancel", onClick = onDismiss) },
    ) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item(key = "new") {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable { onPick(null) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(theme.onGlass.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Face, null, tint = theme.accent)
                    }
                    Text("New person", color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
            items(others.size, key = { others[it].id }) { i ->
                val p = others[i]
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable { onPick(p.id) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PersonCircle(people, p, thumbs, 44)
                    Text(
                        p.name ?: "${people.photosOf(p).size} photos",
                        color = theme.onGlass,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

/** Add or change a person's name. Using a name someone else already has puts the two together. */
@Composable
private fun NameDialog(person: Person, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(person.name.orEmpty()) }
    GlassDialog(
        title = if (person.name == null) "Add a name" else "Change the name",
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton("Save", primary = true, onClick = { onSave(name) })
        },
    ) {
        GlassTextField(name, { name = it }, "Name", capitals = androidx.compose.ui.text.input.KeyboardCapitalization.Words)
        Text(
            "Using a name you already gave someone else puts them together as one person.",
            color = LocalGlassTheme.current.onGlassSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
