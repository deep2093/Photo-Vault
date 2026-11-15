package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.VerifiedUser
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.backup.FreeUpSpace
import com.deepak.photovault.core.media.DetailsFormat
import com.deepak.photovault.core.media.LibraryCounts
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassProgressBar
import com.deepak.photovault.designsystem.GlassSegmented
import com.deepak.photovault.designsystem.GlassSheet
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.StatTile
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Remove phone copies (29 Oct, Phase 6B-2; its own pop-up since 14 Nov; redesigned 15 Nov as a floating sheet). Off by
 * default. When on, a phone copy may move to the phone's Recently deleted once its backup is N days old, every file has
 * been read back from the backup again and the backup has a preview of it. The photo stays in the backup and in the grid.
 *
 * The sheet, top to bottom: how long to wait (one short pill), what is ready at a glance (three tiles), the one thing
 * to do next (a full-width button), then the three promises in a line each instead of a paragraph.
 */
@Composable
fun FreeUpDialog(backup: BackupState, actions: MediaActions, onDismiss: () -> Unit) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    val days = backup.settings.freeUpDays
    val result = backup.freeUp
    var confirm by remember { mutableStateOf<List<MediaItem>?>(null) }
    var confirmSmaller by remember { mutableStateOf(false) }
    val automatic = actions.canDeleteWithoutAsking()
    val checking = backup.freeUpChecking

    GlassSheet(
        title = "Remove phone copies",
        subtitle = "Make room once photos are safely backed up",
        icon = Icons.Rounded.PhoneAndroid,
        onDismiss = onDismiss,
    ) {
        Column(
            Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // How long to wait. Short labels so five choices fit one pill on a narrow phone (they ran together before).
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Days after backup",
                        color = theme.onGlassSecondary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Text(
                        if (days == 0) "Off" else "$days days",
                        color = theme.onGlass,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val choices = listOf(0) + FreeUpSpace.DAY_CHOICES
                GlassSegmented(
                    options = choices.map { if (it == 0) "Off" else "$it" },
                    selectedIndex = choices.indexOf(days).coerceAtLeast(0),
                    onSelect = { i -> backup.update { it.copy(freeUpDays = choices[i]) }; backup.clearFreeUp() },
                )
            }

            if (days == 0) {
                Text(
                    "Nothing is removed right now. Pick a number of days and photos that are already backed up leave this phone that long after their backup.",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                // At a glance: ready, still waiting, and the room it makes. Dashes until the first check.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ready = result?.verified?.size
                    StatTile(ready?.let { LibraryCounts.grouped(it) } ?: "–", "Ready", Modifier.weight(1f), strong = ready != null && ready > 0 && !checking)
                    StatTile(result?.let { LibraryCounts.grouped(it.waiting) } ?: "–", "Waiting", Modifier.weight(1f))
                    StatTile(result?.let { DetailsFormat.size(it.bytes) } ?: "–", "To free", Modifier.weight(1f))
                }

                val progress = backup.freeUpProgress
                val line = when {
                    checking -> progress?.let { "Checking ${it.first} of ${it.second} against your backup…" } ?: "Looking at what is backed up…"
                    result == null -> "Check to see which photos are ready. Nothing moves until you say so."
                    else -> summary(result)
                }
                Text(line, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
                if (checking && progress != null && progress.second > 0) {
                    GlassProgressBar(progress.first / progress.second.toFloat(), label = "Checking ${progress.first} of ${progress.second}")
                }
                if (!checking && result != null) {
                    val notes = listOfNotNull(
                        result.message,
                        if (result.waiting > 0) "${result.waiting} more are backed up but not $days days old yet." else null,
                        if (result.failedCheck > 0) "${result.failedCheck} didn't pass the check, so they stay on the phone." else null,
                    )
                    notes.forEach { Text(it, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall) }
                }

                // One clear next step: move what is ready, otherwise check.
                val canMove = result != null && result.verified.isNotEmpty() && !checking
                if (canMove) {
                    GlassButton(
                        "Move ${result!!.verified.size} to Recently deleted",
                        primary = true,
                        centered = true,
                        icon = Icons.Rounded.DeleteSweep,
                        onClick = { confirm = result.verified },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!checking) {
                    GlassButton(
                        if (result == null) "Check now" else "Check again",
                        primary = !canMove,
                        centered = true,
                        icon = Icons.Rounded.Refresh,
                        onClick = { scope.launch { backup.checkFreeUp() } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!automatic && actions.automaticCleanupAvailable()) {
                    GlassButton("Allow without asking each time", centered = true, onClick = actions::askDeletePermission, modifier = Modifier.fillMaxWidth())
                }

                if (result != null && result.smaller.isNotEmpty() && !checking) {
                    WarningNote(
                        "${result.smaller.size} photos (${DetailsFormat.size(result.smallerBytes)} on the phone) were saved smaller by Storage saver. The backup has only the smaller copy, so Pixallery never removes these by itself.",
                    )
                    GlassButton("Also move these ${result.smaller.size}", centered = true, onClick = { confirmSmaller = true }, modifier = Modifier.fillMaxWidth())
                }

                // The three promises, a line each.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(theme.onGlass.copy(alpha = 0.05f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Promise(Icons.Rounded.VerifiedUser, "Each photo is read back from your backup and checked first")
                    Promise(Icons.Rounded.Restore, "Phone copies wait 30 days in Recently deleted")
                    Promise(Icons.Rounded.CloudDone, "They stay in your backup and in Photos")
                }
            }
        }
    }

    confirm?.let { items ->
        ConfirmDialog(
            title = "Move ${items.size} photos off this phone?",
            body = "They stay in your backup. The phone copies go to Recently deleted for 30 days, where you can put them back.",
            confirm = "Move",
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                actions.moveToRecentlyDeleted(items) { ok -> if (ok) { backup.clearFreeUp(); actions.toast("Moved ${items.size} photos to Recently deleted.") } }
            },
        )
    }
    if (confirmSmaller) {
        val items = result?.smaller.orEmpty()
        ConfirmDialog(
            title = "Remove full-quality originals?",
            body = "The backup has only the smaller Storage saver copy of these ${items.size} photos. Once they are deleted for good from Recently deleted, the full-quality originals are gone.",
            confirm = "Move anyway",
            onDismiss = { confirmSmaller = false },
            onConfirm = {
                confirmSmaller = false
                actions.moveToRecentlyDeleted(items) { ok -> if (ok) { backup.clearFreeUp(); actions.toast("Moved ${items.size} photos to Recently deleted.") } }
            },
        )
    }
}

/** One promise in the sheet: a small icon and a short line. */
@Composable
private fun Promise(icon: ImageVector, text: String) {
    val theme = LocalGlassTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = theme.onGlassSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

private fun summary(r: com.deepak.photovault.core.backup.FreeUpResult): String = when {
    r.message != null && r.nothingToDo -> "Nothing was checked."
    r.verified.isEmpty() && r.smaller.isEmpty() -> "Nothing is ready to move yet."
    r.verified.isEmpty() -> "Only Storage saver photos are ready."
    else -> "${r.verified.size} photos are ready: read back, checked and with a preview."
}

/**
 * Automatic cleanup: only with Android's "Media management" allowed. A few seconds after the app opens and after each
 * finished backup, checks and moves the phone copies without asking. Storage saver photos are never moved here.
 */
@Composable
fun FreeUpAuto(backup: BackupState, actions: MediaActions) {
    val days = backup.settings.freeUpDays
    val lastRun = backup.history.firstOrNull()?.finishedAtMillis
    LaunchedEffect(days, lastRun) {
        if (days <= 0 || !actions.canDeleteWithoutAsking()) return@LaunchedEffect
        delay(4_000)
        if (backup.running || backup.restoring) return@LaunchedEffect
        val result = backup.checkFreeUp() ?: return@LaunchedEffect
        if (result.verified.isNotEmpty()) {
            actions.moveToRecentlyDeleted(result.verified) { ok ->
                if (ok) {
                    backup.clearFreeUp()
                    actions.toast("Freed ${DetailsFormat.size(result.bytes)}: moved ${result.verified.size} photos to Recently deleted.")
                }
            }
        }
    }
}
