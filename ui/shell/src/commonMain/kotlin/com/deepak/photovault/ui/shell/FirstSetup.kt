package com.deepak.photovault.ui.shell

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.backup.BackupPrivacy
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.glassPanel
import com.deepak.photovault.designsystem.rememberHaptics

/**
 * First-time backup questions (10 Nov, Deepak): right after this phone starts a family or joins one, with or without
 * an invite, three short popups in the app's own glass: 1) Normal or Private, 2) original quality or Space saver,
 * 3) videos too or photos only. Private's key is set up at the end. "Later" asks again next time; backup waits until
 * Normal or Private is picked, as before.
 */
@Composable
internal fun FirstSetupPopups(state: ShellState, drive: DriveState, backup: BackupState) {
    val settings = backup.settings
    // 10 Nov: only after this phone starts a family or asks to join one, never just because the app opened.
    // Remembered, so a phone approved later (or that tapped Later) is asked once it is in the family.
    androidx.compose.runtime.LaunchedEffect(drive.setupWanted) {
        if (drive.setupWanted) {
            backup.update { it.copy(askSetup = true) }
            drive.setupWanted = false
        }
    }
    // Google Drive as the first place to back up (Phase 10): the same questions when the first account is added.
    val googleAccounts = state.google.accounts.size
    androidx.compose.runtime.LaunchedEffect(googleAccounts) {
        if (googleAccounts > 0 && settings.privacy == BackupPrivacy.UNSET && !settings.firstSetupDone) backup.update { it.copy(askSetup = true) }
    }
    var later by remember { mutableStateOf(false) }
    val show = (drive.joined != null || googleAccounts > 0) && settings.askSetup && !later
    if (!show) return
    var step by remember { mutableStateOf(0) }
    var private by remember { mutableStateOf(false) }
    var saver by remember { mutableStateOf(false) }
    fun finish(videos: Boolean) {
        backup.update {
            it.copy(
                storageSaver = saver,
                videos = videos,
                privacy = if (private) (if (state.encryption.keyOnPhone) BackupPrivacy.PRIVATE else it.privacy) else BackupPrivacy.NORMAL,
                firstSetupDone = true,
                askSetup = false,
            )
        }
        // Private needs its own key (and recovery words) first: its setup page opens now.
        if (private && !state.encryption.keyOnPhone) state.open(Overlay.PrivateSetup)
    }
    val laterButton: @Composable () -> Unit = { GlassButton("Later", onClick = { later = true }) }
    when (step) {
        0 -> GlassDialog(title = "How should your backup be kept?", onDismiss = { later = true }, buttons = laterButton) {
            Hint("1 of 3. Only you decide; you can change it later in Settings > Backup.")
            Choice("Normal", "Saved on the family drive as ordinary pictures. You can open them from a computer or TV. Anyone who can open the drive can see them.") {
                private = false
                step = 1
            }
            Choice("Private", "Locked on your phone before they leave it. Only your phones can open them, not the drive's owner. You get 24 recovery words to keep safe.") {
                private = true
                step = 1
            }
        }
        1 -> GlassDialog(title = "Photo quality", onDismiss = { later = true }, buttons = laterButton) {
            Hint("2 of 3.")
            Choice("Original", "Every photo saved exactly as it is. Uses the most space.") {
                saver = false
                step = 2
            }
            Choice("Space saver", "Photos saved at 16 MP: still sharp for prints up to 24 × 16 in, and much smaller. Videos are never made smaller.") {
                saver = true
                step = 2
            }
        }
        else -> GlassDialog(title = "Back up videos too?", onDismiss = { later = true }, buttons = laterButton) {
            Hint("3 of 3. Videos are big: they back up on Wi-Fi only unless you allow mobile data.")
            Choice("Photos and videos", "Everything in the folders you back up.") { finish(videos = true) }
            Choice("Photos only", "Videos stay on the phone only. You can turn them on later.") { finish(videos = false) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    val theme = LocalGlassTheme.current
    Text(text, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
}

/** One answer: a glass card with a title and a line, tapped to pick it. */
@Composable
private fun Choice(title: String, line: String, onClick: () -> Unit) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .glassPanel(shape)
            .border(1.dp, theme.accent.copy(alpha = 0.35f), shape)
            .clip(shape)
            .clickable(role = Role.Button) { haptics.tap(); onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(line, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
    }
}
