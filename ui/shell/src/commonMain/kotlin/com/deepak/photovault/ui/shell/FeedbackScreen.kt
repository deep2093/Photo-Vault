package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.household.FeedbackBox
import com.deepak.photovault.core.household.FeedbackEntry
import com.deepak.photovault.core.household.FeedbackKind
import com.deepak.photovault.core.household.NotAllowed
import com.deepak.photovault.core.media.DetailsFormat
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.coroutines.launch

/**
 * Settings > Send feedback (23 Oct): an idea or a bug, in your own words, with up to five photos or videos.
 * It goes to the family drive (no email address anywhere) and the owner reads it here. If the drive is out of
 * reach, it waits on the phone and goes by itself with the next drive check.
 */
@Composable
internal fun FeedbackContent(drive: DriveState, actions: MediaActions) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(FeedbackKind.FEEDBACK) }
    var text by rememberSaveable { mutableStateOf("") }
    var files by remember { mutableStateOf<List<FeedbackFile>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val total = files.sumOf { it.sizeBytes }
    val tooBig = total > FeedbackBox.MAX_ATTACHMENT_BYTES

    var crash by remember { mutableStateOf(actions.lastCrash()) }
    Column(Modifier.cardWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // After an unexpected close: offer to send what went wrong (24 Oct).
        crash?.let { report ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Pixallery closed unexpectedly last time", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "The details of what went wrong were saved on this phone (no photos, names or passwords). Add them to a bug report?",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Add to report", primary = true, onClick = {
                        kind = FeedbackKind.BUG
                        text = (if (text.isBlank()) "The app closed unexpectedly.\n\n" else "$text\n\n") + report.take(3000)
                        actions.clearLastCrash()
                        crash = null
                    })
                    GlassButton("Dismiss", onClick = {
                        actions.clearLastCrash()
                        crash = null
                    })
                }
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeedbackKind.entries.forEach { k -> GlassChip(k.label, selected = kind == k, onClick = { kind = k }) }
            }
            GlassTextField(
                text,
                { text = it.take(MAX_TEXT) },
                if (kind == FeedbackKind.BUG) "What went wrong?" else "Your feedback",
                placeholder = if (kind == FeedbackKind.BUG) "What did you do just before it happened?" else "An idea, something you like or don't",
                minLines = 5,
            )
            SectionHeader("Photos or videos (optional)")
            files.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.onGlass.copy(alpha = 0.06f)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (f.isVideo) Icons.Rounded.Movie else Icons.Rounded.Image, null, tint = theme.onGlassSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(DetailsFormat.size(f.sizeBytes), color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    GlassIconButton(Icons.Rounded.Close, "Remove ${f.name}", onClick = { files = files - f })
                }
            }
            if (files.size < MAX_FILES) {
                GlassButton("Add photo or video", icon = Icons.Rounded.AddPhotoAlternate, onClick = {
                    actions.pickFeedbackFiles { picked -> files = (files + picked).distinctBy { it.uri }.take(MAX_FILES) }
                })
            }
            if (tooBig) WarningNote("That's ${DetailsFormat.size(total)}. Please keep it under ${DetailsFormat.size(FeedbackBox.MAX_ATTACHMENT_BYTES)}: a shorter video, or fewer files.")
            status?.let { WarningNote(it) }
            GlassButton(
                if (sending) "Sending..." else "Send",
                primary = true,
                icon = if (kind == FeedbackKind.BUG) Icons.Rounded.BugReport else Icons.AutoMirrored.Rounded.Send,
                onClick = {
                    if (sending) return@GlassButton
                    if (text.isBlank()) {
                        status = "Write a few words first."
                        return@GlassButton
                    }
                    if (tooBig) return@GlassButton
                    sending = true
                    scope.launch {
                        status = try {
                            val now = drive.sendFeedback(kind, text, files, actions)
                            text = ""
                            files = emptyList()
                            if (now) "Sent. Thank you!" else "Saved on this phone. It goes to the drive by itself next time the drive is reachable."
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: NotAllowed) {
                            e.message
                        } catch (_: Throwable) {
                            "Couldn't send it. Try again later."
                        }
                        sending = false
                    }
                },
            )
            Text(
                "Goes to your family drive, into your own folder. Only the family owner reads it. No email address is used or shown.",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            if (drive.feedbackWaiting > 0) {
                Text("${drive.feedbackWaiting} waiting to be sent.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (drive.isOwner) FeedbackInbox(drive)
    }
}

/** The owner's inbox: everyone's notes, newest first. */
@Composable
private fun FeedbackInbox(drive: DriveState) {
    val theme = LocalGlassTheme.current
    var notes by remember { mutableStateOf<List<FeedbackEntry>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(drive.joined?.household?.revision) {
        notes = drive.feedbackInbox()
        loaded = true
    }
    if (confirmClear) ClearFeedbackDialog(notes.orEmpty().size, { confirmClear = false }) {
        confirmClear = false
        val entries = notes.orEmpty()
        scope.launch {
            problem = drive.clearFeedback(entries)
            notes = drive.feedbackInbox()
        }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Inbox, null, tint = theme.accent)
            Spacer(Modifier.width(10.dp))
            Text("Feedback received", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!notes.isNullOrEmpty()) GlassButton("Clear", icon = Icons.Rounded.DeleteSweep, onClick = { confirmClear = true })
        }
        problem?.let { WarningNote(it) }
        val list = notes
        when {
            !loaded -> Text("Reading the drive...", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            list == null -> Text("Couldn't read the drive.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            list.isEmpty() -> Text("Nothing yet.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            else -> list.forEach { entry ->
                val n = entry.note
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(theme.onGlass.copy(alpha = 0.06f)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "${n.kind.label} · ${n.fromName} · ${whenText(n.createdAtMillis, drive.now())}",
                        color = if (n.kind == FeedbackKind.BUG) theme.accent else theme.onGlassSecondary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(n.text, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
                    Text("${n.deviceName}${if (n.appVersion.isNotEmpty()) " · version ${n.appVersion}" else ""}", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
                    if (n.attachments.isNotEmpty()) {
                        Text(
                            "${n.attachments.size} attached: ${n.attachments.joinToString()}. On the drive in Pixallery/${entry.folder}.",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClearFeedbackDialog(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = "Clear all feedback?",
        body = "$count received ${if (count == 1) "note is" else "notes are"} deleted from the drive, with their photos and videos. This can't be undone. Nothing else is touched.",
        confirm = "Clear",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

private const val MAX_FILES = 5
private const val MAX_TEXT = 4000
