package com.deepak.photovault.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.backup.FreeUpSpace
import com.deepak.photovault.core.media.DetailsFormat
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings > Free up space (29 Oct, Phase 6B-2). Off by default. When on, a phone copy may move to the phone's
 * Recently deleted once its backup is N days old, every file has been read back from the drive again and the drive has a
 * preview of it. The photo stays on the family drive and in the grid.
 */
@Composable
fun FreeUpContent(backup: BackupState, actions: MediaActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { FreeUpCards(backup, actions) }
}

@Composable
private fun FreeUpCards(backup: BackupState, actions: MediaActions) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    val days = backup.settings.freeUpDays
    val result = backup.freeUp
    var confirm by remember { mutableStateOf<List<MediaItem>?>(null) }
    var confirmSmaller by remember { mutableStateOf(false) }
    val automatic = actions.canDeleteWithoutAsking()

    GlassCard(Modifier.cardWidth()) {
        Text(
            "Takes the copy off this phone once the photo has been safely on the family drive for a while. The photo stays on the drive and still shows in Photos.",
            color = theme.onGlass,
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionHeader("Remove phone copies after", Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("Off", selected = days == 0, onClick = { backup.update { it.copy(freeUpDays = 0) }; backup.clearFreeUp() })
            FreeUpSpace.DAY_CHOICES.forEach { d ->
                GlassChip("$d days", selected = days == d, onClick = { backup.update { it.copy(freeUpDays = d) }; backup.clearFreeUp() })
            }
        }
        if (days == 0) {
            Text("Off. Nothing is removed from this phone.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        } else {
            Text(
                "Each photo is read back from the drive and compared again before its phone copy goes, and the drive must have a preview of it. Anything that doesn't pass stays on the phone. Photos go to your phone's Recently deleted for 30 days, so you can still undo. This covers every album and favourites.",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            WarningNote("Once the phone copy is gone, the family drive holds the only copy. Keep a second copy of the drive if these photos matter.")
        }
    }

    if (days > 0) {
        GlassCard(Modifier.cardWidth()) {
            SectionHeader("Who asks")
            if (automatic) {
                Text(
                    "Pixallery can remove them by itself: when you open the app and after each backup, it checks and moves the phone copies to Recently deleted without asking.",
                    color = theme.onGlass,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "Android asks you each time before anything moves. To let Pixallery do it by itself, allow \"Media management\" for Pixallery in Android's settings.",
                    color = theme.onGlass,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (actions.automaticCleanupAvailable()) {
                    GlassButton("Open Android permission screen", onClick = actions::askDeletePermission, modifier = Modifier.padding(top = 10.dp))
                } else {
                    Text("This version of Android always asks.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        GlassCard(Modifier.cardWidth()) {
            SectionHeader("Check now")
            val progress = backup.freeUpProgress
            if (backup.freeUpChecking) {
                Text(
                    progress?.let { "Checking ${it.first} of ${it.second} against the drive..." } ?: "Looking at what is on the drive...",
                    color = theme.onGlass,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    if (result == null) "See which photos are ready. Nothing moves until you say so." else summary(result),
                    color = theme.onGlass,
                    style = MaterialTheme.typography.bodyMedium,
                )
                result?.message?.let { Text(it, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (result != null && result.waiting > 0) {
                    Text("${result.waiting} more are backed up but not $days days old yet.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                if (result != null && result.failedCheck > 0) {
                    Text("${result.failedCheck} didn't pass the check, so they stay on the phone.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(if (result == null) "Check now" else "Check again", onClick = { scope.launch { backup.checkFreeUp() } })
                if (result != null && result.verified.isNotEmpty() && !backup.freeUpChecking) {
                    GlassButton("Move ${result.verified.size} to Recently deleted", primary = true, onClick = { confirm = result.verified })
                }
            }
            if (result != null && result.smaller.isNotEmpty() && !backup.freeUpChecking) {
                WarningNote(
                    "${result.smaller.size} photos (${DetailsFormat.size(result.smallerBytes)} on the phone) were saved smaller by Storage saver. The drive has only the smaller copy, so Pixallery never removes these by itself.",
                    Modifier.padding(top = 10.dp),
                )
                GlassButton("Also move these ${result.smaller.size}", onClick = { confirmSmaller = true })
            }
        }
    }

    confirm?.let { items ->
        ConfirmDialog(
            title = "Move ${items.size} photos off this phone?",
            body = "They stay on the family drive. The phone copies go to Recently deleted for 30 days, where you can put them back.",
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
            body = "The drive has only the smaller Storage saver copy of these ${items.size} photos. Once they are deleted for good from Recently deleted, the full-quality originals are gone.",
            confirm = "Move anyway",
            onDismiss = { confirmSmaller = false },
            onConfirm = {
                confirmSmaller = false
                actions.moveToRecentlyDeleted(items) { ok -> if (ok) { backup.clearFreeUp(); actions.toast("Moved ${items.size} photos to Recently deleted.") } }
            },
        )
    }
}

private fun summary(r: com.deepak.photovault.core.backup.FreeUpResult): String = when {
    r.message != null && r.nothingToDo -> "Nothing was checked."
    r.verified.isEmpty() && r.smaller.isEmpty() -> "Nothing is ready to move right now."
    r.verified.isEmpty() -> "Only Storage saver photos are ready."
    else -> "${r.verified.size} photos are ready: on the drive, read back and checked, with a preview. Moving them frees ${DetailsFormat.size(r.bytes)}."
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
