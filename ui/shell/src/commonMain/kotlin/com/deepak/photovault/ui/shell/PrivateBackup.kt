package com.deepak.photovault.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.backup.BackupPrivacy
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.coroutines.launch

/** The two big cards. Nothing is pre-selected: the person must pick. */
@Composable
internal fun PrivacyChoiceScreen(state: ShellState, drive: DriveState, backup: BackupState) {
    val theme = LocalGlassTheme.current
    var confirmNormal by remember { mutableStateOf(false) }
    val current = backup.settings.privacy
    GlassListScreen(title = "How is your backup kept?", subtitle = null, onBack = { state.back() }) {
        item {
            Text(
                "Pick one. It covers all of your backed-up photos and videos, and only you decide. The family owner can't pick for you.",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.cardWidth(),
            )
        }
        item {
            ChoiceCard(
                title = "Normal",
                tag = "Easy",
                selected = current == BackupPrivacy.NORMAL,
                lines = listOf(
                    "Your photos are saved on the family drive as ordinary pictures.",
                    "You can open them from a computer or a TV.",
                    "Anyone who can open the drive can see them.",
                ),
                onClick = { if (current == BackupPrivacy.PRIVATE) confirmNormal = true else { backup.update { it.copy(privacy = BackupPrivacy.NORMAL) }; state.back() } },
            )
        }
        item {
            ChoiceCard(
                title = "Private",
                tag = "Only you can open them",
                selected = current == BackupPrivacy.PRIVATE,
                lines = listOf(
                    "Photos are scrambled on your phone before they leave it. Nobody with the drive password, a computer or the family owner can open them.",
                    "You get 24 recovery words. If you lose your phone AND the words, the backup can't be opened by anyone.",
                    "Photos open only inside Pixallery, not straight from the drive.",
                ),
                onClick = {
                    // Already have the key on this phone: nothing to set up, just use it.
                    if (state.encryption.keyOnPhone) { backup.update { it.copy(privacy = BackupPrivacy.PRIVATE) }; state.back() } else state.open(Overlay.PrivateSetup)
                },
            )
        }
    }
    if (confirmNormal) {
        GlassDialog(
            title = "Switch to Normal?",
            onDismiss = { confirmNormal = false },
            buttons = {
                GlassButton("Cancel", onClick = { confirmNormal = false })
                GlassButton("Switch to Normal", primary = true, onClick = {
                    confirmNormal = false
                    backup.update { it.copy(privacy = BackupPrivacy.NORMAL) }
                    state.back()
                })
            },
        ) {
            Text(
                "New backups will be saved as ordinary pictures that anyone with the drive can see. Backups already kept private stay private. Your recovery words keep working for those.",
                color = theme.onGlass,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ChoiceCard(title: String, tag: String, selected: Boolean, lines: List<String>, onClick: () -> Unit) {
    val theme = LocalGlassTheme.current
    GlassCard(Modifier.cardWidth().clip(RoundedCornerShape(24.dp)).clickable(onClickLabel = title, onClick = onClick)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.Icon(if (title == "Private") Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = theme.onGlass)
            Column(Modifier.weight(1f)) {
                Text(title, color = theme.onGlass, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(tag, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (selected) Text("Chosen", color = theme.onGlass, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        lines.forEach {
            Text("• $it", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Understand, 24 words, check, optional helper, finish. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrivateSetupScreen(state: ShellState, drive: DriveState, backup: BackupState) {
    val theme = LocalGlassTheme.current
    val enc = state.encryption
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var alreadyOnDrive by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<Boolean?>(null) }
    val checkAt = remember { listOf(3, 9, 17, 21).shuffled().take(3).sorted() }
    val typed = remember { mutableStateListOf("", "", "") }
    var helper by remember { mutableStateOf<DriveState.HelperCandidate?>(null) }
    var candidates by remember { mutableStateOf<List<DriveState.HelperCandidate>>(emptyList()) }
    val key = enc.pending
    // After a screen turn or process restart the words are gone from memory; start the step again.
    LaunchedEffect(key == null && step in 1..3) { if (key == null && step in 1..3) step = 0 }
    LaunchedEffect(step) { if (step == 3) candidates = drive.helperCandidates() }
    fun leave() { enc.clearPending(); state.back() }

    GlassListScreen(title = "Private backup", subtitle = "Step ${step + 1} of 4", onBack = { leave() }) {
        when (step) {
            0 -> {
                item {
                    GlassCard(Modifier.cardWidth()) {
                        Text("Before you start", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        listOf(
                            "Your photos will be scrambled before they leave your phone. Only Pixallery, with your key, can unscramble them.",
                            "Photos can't be opened from a computer, a TV or other apps straight off the drive.",
                            "You will get 24 words. Write them down. If you lose your phone AND the words, your backup is gone for good. Nobody can reset it, not even the family owner.",
                        ).forEach { Text("• $it", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
                item { problem?.let { Text(it, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.cardWidth()) } }
                if (alreadyOnDrive) {
                    item {
                        GlassButton("Restore my key", primary = true, onClick = { state.open(Overlay.RestoreKeys) })
                    }
                } else item {
                    GlassButton("Make my key", primary = true, onClick = {
                        val ctx = drive.keyContext()
                        if (ctx == null) { problem = "Join the family first (Settings > Drive)."; return@GlassButton }
                        scope.launch {
                            working = true
                            val existing = drive.readKeyFiles()
                            working = false
                            if (existing != null) {
                                alreadyOnDrive = true
                                problem = "Private backup is already set up for you on the drive. Restore your key on this phone with your 24 words."
                            } else {
                                val made = enc.platform.createKey(ctx.first, ctx.second)
                                if (made == null) problem = "This phone can't make a private key." else { enc.pending = made; problem = null; step = 1 }
                            }
                        }
                    })
                }
            }
            1 -> {
                item {
                    GlassCard(Modifier.cardWidth()) {
                        Text("Your 24 recovery words", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Write them down on paper, in order, and keep the paper safe. Don't screenshot them or send them to anyone.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                        if (shown && key != null) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                key.words.forEachIndexed { i, w ->
                                    Text("${i + 1}. $w", color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 110.dp))
                                }
                            }
                        } else {
                            GlassButton("Show my words", primary = true, onClick = { shown = true })
                        }
                    }
                }
                if (shown) {
                    item {
                        GlassCard(Modifier.cardWidth()) {
                            Text("Also keep a copy in your password manager?", color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
                            Text("Your phone may offer Samsung Pass or Google Password Manager. It can say no; writing them down is always the safe way.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                            GlassButton("Save to password manager", onClick = { key?.let { enc.platform.saveWordsToPasswordManager(it.words) { ok -> saved = ok } } })
                            saved?.let { Text(if (it) "Saved. Still write them down too." else "Your phone didn't save them. Write them down.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                    item { GlassButton("I've written them down", primary = true, onClick = { step = 2 }) }
                }
            }
            2 -> {
                item {
                    GlassCard(Modifier.cardWidth()) {
                        Text("Check your words", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Type these words from your paper so we know you have them.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                        checkAt.forEachIndexed { i, pos ->
                            GlassTextField(typed[i], { typed[i] = it.trim().lowercase() }, "Word number $pos", modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                item { problem?.let { Text(it, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.cardWidth()) } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("Show words again", onClick = { shown = false; step = 1 })
                        GlassButton("Check", primary = true, onClick = {
                            val words = key?.words ?: return@GlassButton
                            if (checkAt.indices.all { typed[it] == words[checkAt[it] - 1] }) { problem = null; step = 3 } else problem = "Those don't match. Look at your paper and try again."
                        })
                    }
                }
            }
            else -> {
                item {
                    GlassCard(Modifier.cardWidth()) {
                        Text("A recovery helper (optional)", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Pick one trusted family member who could help you get your backup back if you lose both your phone and the words. They must use Private backup too. It is never the owner automatically, and you can skip it.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                        if (candidates.isEmpty()) {
                            Text("Nobody else in the family uses Private backup yet, so there is no one to pick. You can add a helper later.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        candidates.forEach { c ->
                            GlassButton(if (helper?.userId == c.userId) "${c.name} (chosen)" else c.name, onClick = { helper = if (helper?.userId == c.userId) null else c }, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                item { problem?.let { Text(it, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.cardWidth()) } }
                item {
                    GlassButton(if (working) "Saving..." else "Finish", primary = true, onClick = {
                        val made = key ?: return@GlassButton
                        val ctx = drive.keyContext() ?: return@GlassButton
                        if (working) return@GlassButton
                        scope.launch {
                            working = true
                            val failed = drive.publishKeys(made.recoveryEnvelope, made.identity)
                            if (failed == null) {
                                helper?.let { h -> enc.platform.wrapForHelper(h.identity.exchangePublic, ctx.first, ctx.second)?.let { drive.publishHelperWrap(h.userId, it) } }
                                if (enc.platform.commitKey()) {
                                    backup.update { it.copy(privacy = BackupPrivacy.PRIVATE) }
                                    enc.refresh()
                                    enc.clearPending()
                                    state.back()
                                    state.back()
                                } else problem = "The key couldn't be kept on this phone. Nothing was changed."
                            } else problem = failed
                            working = false
                        }
                    })
                }
            }
        }
    }
}

/** A new phone (or lost Keystore): the 24 words open the recovery file on the drive and the key comes back. */
@Composable
internal fun RestoreKeysScreen(state: ShellState, drive: DriveState, backup: BackupState) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    var words by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    GlassListScreen(title = "Restore my key", subtitle = null, onBack = { state.back() }) {
        item {
            GlassCard(Modifier.cardWidth()) {
                Text("Type your 24 recovery words, in order.", color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
                GlassTextField(words, { words = it }, "24 words", placeholder = "word1 word2 word3 ...", modifier = Modifier.padding(top = 8.dp))
            }
        }
        item { problem?.let { Text(it, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.cardWidth()) } }
        item {
            GlassButton(if (working) "Working..." else "Restore", primary = true, onClick = {
                val ctx = drive.keyContext() ?: run { problem = "Join the family first."; return@GlassButton }
                if (working) return@GlassButton
                scope.launch {
                    working = true
                    val files = drive.readKeyFiles()
                    problem = if (files == null) "No private backup was found for you on the drive." else state.encryption.platform.restoreKey(words, files.recovery, ctx.first, ctx.second)
                    if (problem == null) {
                        backup.update { it.copy(privacy = BackupPrivacy.PRIVATE) }
                        state.encryption.refresh()
                        state.back()
                    }
                    working = false
                }
            })
        }
    }
}

/** A card on the Backup screen: what is chosen, and the way to change it or to restore a key. */
@Composable
internal fun PrivacyCard(state: ShellState, drive: DriveState, backup: BackupState) {
    val theme = LocalGlassTheme.current
    val privacy = backup.settings.privacy
    var onDrive by remember { mutableStateOf(false) }
    LaunchedEffect(drive.joined?.me?.userId, state.encryption.keyOnPhone) {
        onDrive = drive.joined != null && drive.readKeyFiles() != null
    }
    val needsRestore = onDrive && !state.encryption.keyOnPhone
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("Backup privacy")
        Text(
            when {
                needsRestore -> "You use Private backup, but this phone doesn't have your key yet."
                privacy == BackupPrivacy.UNSET -> "Not chosen yet. Backup waits until you pick Normal or Private."
                privacy == BackupPrivacy.NORMAL -> "Normal: your photos are saved as ordinary pictures."
                else -> "Private: only you can open your backed-up photos."
            },
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (needsRestore) GlassButton("Restore my key", primary = true, onClick = { state.open(Overlay.RestoreKeys) })
            GlassButton(if (privacy == BackupPrivacy.UNSET) "Choose" else "Change", primary = privacy == BackupPrivacy.UNSET && !needsRestore, onClick = { state.open(Overlay.PrivacyChoice) })
        }
    }
}
