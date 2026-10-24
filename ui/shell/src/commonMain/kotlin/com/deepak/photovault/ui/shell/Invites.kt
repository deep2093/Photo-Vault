package com.deepak.photovault.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.household.Invite
import com.deepak.photovault.core.household.InviteMarker
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.coroutines.launch

/** What the phone does for invites: picking and sharing files, drawing and scanning QR codes (25 Oct, Phase 5B-2). */
interface InvitePlatform {
    /** Pick a text file (a WireGuard .conf or an invite). Null when cancelled. */
    fun pickTextFile(onText: (String?) -> Unit) = Unit

    /** Share the invite as a file (WhatsApp, email...). */
    fun shareInviteFile(text: String) = Unit

    /** QR squares (true = dark), or null when the text is too long for a QR code. */
    fun qr(text: String): List<List<Boolean>>? = null

    val canScan: Boolean get() = false

    /** Scan a QR code with the camera. Null when cancelled. */
    fun scanQr(onText: (String?) -> Unit) = Unit
}

object NoInvitePlatform : InvitePlatform

private val VALID_FOR = listOf("1 day" to 24L * 60 * 60 * 1000, "3 days" to 3L * 24 * 60 * 60 * 1000, "7 days" to 7L * 24 * 60 * 60 * 1000)

/** Family > Invite someone: owner only. Makes a locked invite (file and QR), good for one person and one use. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InviteCard(state: ShellState, drive: DriveState, asOwner: (() -> Unit) -> Unit) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    val platform = state.invites
    var open by rememberSaveable { mutableStateOf(false) }
    var forName by rememberSaveable { mutableStateOf("") }
    var validFor by rememberSaveable { mutableStateOf(1) }
    var code by rememberSaveable { mutableStateOf(Invite.newCode()) }
    var wireGuard by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var made by remember { mutableStateOf<String?>(null) }
    var openInvites by remember { mutableStateOf<List<InviteMarker>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(open, made) { if (drive.isOwner) openInvites = drive.openInvites() }
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("Invite someone")
        val text = made
        when {
            text != null -> {
                Text("Invite ready. Send the file or QR, and tell them this code another way (a call or a different chat):", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(code, color = theme.onGlass, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Share invite file", primary = true, icon = Icons.Rounded.Share, onClick = { platform.shareInviteFile(text) })
                }
                platform.qr(text)?.let { QrView(it, Modifier.padding(top = 12.dp)) }
                    ?: Text("This invite is too big for a QR code (the WireGuard file is long). Send the file instead.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Text("Works once, then it's used up. It runs out after ${VALID_FOR[validFor].first}.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                GlassButton("Done", onClick = { made = null; open = false; code = Invite.newCode(); wireGuard = null; forName = "" }, modifier = Modifier.padding(top = 8.dp))
            }
            !open -> {
                Text(
                    "Add someone without waiting to approve them. They get a file or QR and a code, and join in one step, even away from home.",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                GlassButton("Invite someone", icon = Icons.Rounded.PersonAdd, onClick = { open = true }, modifier = Modifier.padding(top = 10.dp))
            }
            else -> {
                GlassTextField(forName, { forName = it.take(40) }, "Who is it for? (optional)")
                Text("Runs out after", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VALID_FOR.forEachIndexed { i, (label, _) -> GlassChip(label, selected = validFor == i, onClick = { validFor = i }) }
                }
                GlassTextField(code, { code = it.uppercase().take(12) }, "Code", supporting = "Tell them this code another way. Letters and numbers, at least 6.")
                GlassButton(
                    if (wireGuard == null) "Attach WireGuard file (for away from home)" else "WireGuard file attached. Replace",
                    icon = Icons.Rounded.AttachFile,
                    onClick = { platform.pickTextFile { picked -> if (picked != null) wireGuard = picked } },
                )
                problem?.let { WarningNote(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    GlassButton("Cancel", onClick = { open = false })
                    GlassButton(if (drive.busy) "Making..." else "Make invite", primary = true, onClick = {
                        problem = null
                        if (Invite.cleanCode(code).length < 6) {
                            problem = "The code needs at least 6 letters or numbers."
                        } else asOwner {
                            scope.launch {
                                val (text, error) = drive.makeInvite(code, VALID_FOR[validFor].second, wireGuard, forName)
                                problem = error
                                made = text
                            }
                        }
                    })
                }
            }
        }
        if (openInvites.isNotEmpty() && made == null) {
            Text("Waiting to be used", color = theme.onGlass, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp))
            openInvites.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(m.forName.ifBlank { "Invite" } + " · runs out in " + leftText(m.expiresAtMillis - drive.now()), color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    GlassButton("Take back", onClick = { asOwner { scope.launch { drive.revokeInvite(m.id); openInvites = drive.openInvites() } } })
                }
            }
        }
    }
}

@Composable
private fun QrView(squares: List<List<Boolean>>, modifier: Modifier = Modifier) {
    val n = squares.size
    Canvas(modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
        val quiet = 2
        val cell = size.width / (n + quiet * 2)
        for (y in 0 until n) for (x in 0 until n) {
            if (squares[y][x]) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** Joining with an invite (25 Oct): choose the file or scan the QR, type the code and your name. */
@Composable
internal fun InviteJoinCard(state: ShellState, drive: DriveState, onDone: () -> Unit, onCancel: () -> Unit) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    val platform = state.invites
    var text by remember { mutableStateOf<String?>(null) }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("Join with an invite")
        if (text == null) {
            Text("Choose the invite file you were sent" + if (platform.canScan) ", or scan its QR code." else ".", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                GlassButton("Choose file", icon = Icons.Rounded.AttachFile, primary = true, onClick = {
                    platform.pickTextFile { picked ->
                        if (picked != null && Invite.looksLikeInvite(picked)) { text = picked.trim(); error = null } else if (picked != null) error = "That file isn't a Pixallery invite."
                    }
                })
                if (platform.canScan) GlassButton("Scan QR", icon = Icons.Rounded.QrCodeScanner, onClick = {
                    platform.scanQr { scanned ->
                        if (scanned != null && Invite.looksLikeInvite(scanned)) { text = scanned.trim(); error = null } else if (scanned != null) error = "That QR code isn't a Pixallery invite."
                    }
                })
            }
        } else {
            Text("Invite found. Type the code you were given, and your name.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            GlassTextField(code, { code = it.uppercase().take(12) }, "Code")
            GlassTextField(name, { name = it.take(40) }, "Your name")
        }
        error?.let { WarningNote(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            GlassButton("Cancel", onClick = onCancel)
            if (text != null) GlassButton(if (drive.busy) "Joining..." else "Join", primary = true, onClick = {
                error = when {
                    Invite.cleanCode(code).length < 6 -> "Enter the code."
                    name.isBlank() -> "Enter your name."
                    else -> null
                }
                val invite = text
                if (error == null && invite != null && !drive.busy) scope.launch {
                    error = drive.joinWithInvite(invite, code, name) { wg -> state.remote.platform.importConfigText(wg) }
                    if (error == null) onDone()
                }
            })
        }
    }
}

private fun leftText(millis: Long): String {
    val hours = (millis / 3_600_000L).coerceAtLeast(0)
    return if (hours >= 48) "${hours / 24} days" else if (hours >= 1) "$hours h" else "under an hour"
}
