package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Storage
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.household.JoinRequest
import com.deepak.photovault.core.household.MAX_LIMIT_GB
import com.deepak.photovault.core.household.MIN_LIMIT_GB
import com.deepak.photovault.core.household.Member
import com.deepak.photovault.core.household.Membership
import com.deepak.photovault.core.household.OwnerPin
import com.deepak.photovault.core.media.DetailsFormat
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.GlassIcon
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.GlassProgressBar
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shown in setup and on this screen: v1 keeps folders apart, it does not keep them secret. */
const val FOLDER_PRIVACY_NOTE =
    "Each person's photos go into their own folder, and PhotoVault only shows you yours. Anyone who knows " +
        "the drive password can still open every folder from a computer, so this works for a family that trusts each other."

/** Owner-only actions waiting for the PIN. */
private sealed interface OwnerTask {
    /** Just open the owner controls. */
    data object Unlock : OwnerTask

    data class Approve(val request: JoinRequest) : OwnerTask
    data class Decline(val request: JoinRequest) : OwnerTask
    data class Limit(val member: Member) : OwnerTask
    data class Remove(val member: Member) : OwnerTask
    data class RemovePhone(val deviceId: String, val name: String) : OwnerTask
}

@Composable
fun FamilyScreen(state: ShellState, drive: DriveState) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    // Fresh numbers when the screen opens, then every 20 seconds (new requests, space used).
    LaunchedEffect(drive.connection) {
        while (drive.isSetUp) {
            drive.refresh()
            delay(20_000)
        }
    }
    var pinFor by remember { mutableStateOf<OwnerTask?>(null) }
    var task by remember { mutableStateOf<OwnerTask?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(t: OwnerTask) {
        if (!drive.ownerUnlocked) {
            pinFor = t
            return
        }
        when (t) {
            is OwnerTask.Approve -> scope.launch { message = drive.approve(t.request) }
            is OwnerTask.Decline -> scope.launch { message = drive.decline(t.request) }
            is OwnerTask.Limit, is OwnerTask.Remove, is OwnerTask.RemovePhone -> task = t
            OwnerTask.Unlock -> Unit
        }
    }

    val joined = drive.joined
    GlassListScreen(title = null, subtitle = null) {
        if (joined == null) {
            item { NotConnectedCard(state, drive) }
            item { PrivacyCard() }
            return@GlassListScreen
        }
        message?.let { item { WarningNote(it, Modifier.cardWidth()) } }
        drive.problem?.let { item { WarningNote("Can't reach the drive: $it", Modifier.cardWidth()) } }
        if (drive.isOwner && drive.requests.isNotEmpty()) {
            item {
                GlassCard(Modifier.cardWidth()) {
                    SectionHeader("Waiting for you")
                    drive.requests.forEach { r -> RequestRow(r, joined, onApprove = { run(OwnerTask.Approve(r)) }, onDecline = { run(OwnerTask.Decline(r)) }) }
                }
            }
        }
        item {
            GlassCard(Modifier.cardWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("People", Modifier.weight(1f))
                    if (drive.isOwner && joined.household.settings.requirePin) {
                        GlassIconButton(
                            if (drive.ownerUnlocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                            if (drive.ownerUnlocked) "Lock owner controls" else "Unlock owner controls",
                            onClick = { if (drive.ownerUnlocked) drive.lockOwner() else pinFor = OwnerTask.Unlock },
                        )
                    }
                }
                Text(
                    "Each person gets ${joined.household.settings.defaultLimitGb} GB to start." +
                        if (drive.isOwner) " Tap a person's space to change it." else " The owner can change it.",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                joined.household.members.forEach { member ->
                    MemberRow(
                        member = member,
                        isOwner = member.userId == joined.household.ownerUserId,
                        isMe = member.userId == joined.me.userId,
                        thisDevice = drive.deviceId,
                        usage = drive.usage[member.userId],
                        ownerControls = drive.isOwner,
                        onLimit = { run(OwnerTask.Limit(member)) },
                        onRemove = { run(OwnerTask.Remove(member)) },
                        onRemovePhone = { id, name -> run(OwnerTask.RemovePhone(id, name)) },
                    )
                }
            }
        }
        item { DriveSpaceCard(drive) }
        item { PrivacyCard() }
    }

    pinFor?.let { pending ->
        PinDialog(
            drive = drive,
            onDismiss = { pinFor = null },
            onUnlocked = {
                pinFor = null
                run(pending)
            },
        )
    }
    when (val t = task) {
        is OwnerTask.Limit -> LimitDialog(t.member, onDismiss = { task = null }) { gb ->
            scope.launch {
                message = drive.setLimit(t.member.userId, gb)
                task = null
            }
        }
        is OwnerTask.Remove -> ConfirmDialog(
            title = "Remove ${t.member.name}?",
            body = "${t.member.name}'s phones stop backing up to this drive. Their photos already on the drive are kept.",
            confirm = "Remove",
            onDismiss = { task = null },
        ) { scope.launch { message = drive.removeMember(t.member.userId); task = null } }
        is OwnerTask.RemovePhone -> ConfirmDialog(
            title = "Remove ${t.name}?",
            body = "This phone stops backing up to the drive. Photos already backed up are kept.",
            confirm = "Remove",
            onDismiss = { task = null },
        ) { scope.launch { message = drive.removeDevice(t.deviceId); task = null } }
        else -> Unit
    }
}

@Composable
private fun NotConnectedCard(state: ShellState, drive: DriveState) {
    val theme = LocalGlassTheme.current
    GlassCard(Modifier.cardWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIcon(Icons.Rounded.Dns, tint = theme.accent)
            Spacer(Modifier.width(12.dp))
            Text(
                when (drive.membership) {
                    is Membership.Pending -> "Waiting for the owner to approve this phone"
                    is Membership.NotJoined, is Membership.Removed -> "Join the family on your drive"
                    Membership.NoHousehold -> "Set up your family"
                    else -> "Connect your family drive"
                },
                color = theme.onGlass,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            "PhotoVault keeps each person's photos in their own folder on the shared drive at home.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        drive.problem?.let { WarningNote(it) }
        GlassButton(if (drive.isSetUp) "Continue" else "Connect drive", primary = true, onClick = { state.open(Overlay.DriveSetup) })
    }
}

@Composable
private fun PrivacyCard() {
    val theme = LocalGlassTheme.current
    GlassCard(Modifier.cardWidth()) {
        Row {
            GlassIcon(Icons.Rounded.Info, tint = theme.accent)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("How privacy works", color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(FOLDER_PRIVACY_NOTE, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RequestRow(request: JoinRequest, joined: Membership.Joined, onApprove: () -> Unit, onDecline: () -> Unit) {
    val theme = LocalGlassTheme.current
    val who = request.newName ?: joined.household.member(request.existingUserId ?: "")?.name ?: "Someone"
    Row(Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        GlassIcon(Icons.Rounded.PhoneAndroid, tint = theme.onGlassSecondary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(request.deviceName, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (request.newName != null) "New person: $who" else "Another phone of $who",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        GlassIconButton(Icons.Rounded.Close, "Decline", onClick = onDecline)
        GlassIconButton(Icons.Rounded.Check, "Approve", onClick = onApprove)
    }
}

@Composable
private fun MemberRow(
    member: Member,
    isOwner: Boolean,
    isMe: Boolean,
    thisDevice: String,
    usage: MemberUsage?,
    ownerControls: Boolean,
    onLimit: () -> Unit,
    onRemove: () -> Unit,
    onRemovePhone: (String, String) -> Unit,
) {
    val theme = LocalGlassTheme.current
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(theme.accent), contentAlignment = Alignment.Center) {
                Text(member.name.take(1).uppercase(), color = theme.onAccent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    member.name + listOfNotNull(if (isMe) "you" else null, if (isOwner) "owner" else null).let { if (it.isEmpty()) "" else " (${it.joinToString(", ")})" },
                    color = theme.onGlass,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text("${member.devices.size} ${if (member.devices.size == 1) "phone" else "phones"}", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            if (ownerControls && !isOwner) GlassIconButton(Icons.Rounded.PersonRemove, "Remove ${member.name}", onClick = onRemove)
        }
        Spacer(Modifier.height(8.dp))
        val limit = usage?.limitBytes ?: (member.limitGb * DriveState.GB)
        val used = usage?.usedBytes
        GlassProgressBar((used ?: 0L) / limit.toFloat(), label = "${member.name}: ${used?.let(DetailsFormat::size) ?: "unknown"} of ${member.limitGb} GB used")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${used?.let(DetailsFormat::size) ?: "..."} of ${member.limitGb} GB",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (ownerControls) GlassButton("Change space", onClick = onLimit, icon = Icons.Rounded.Storage)
        }
        member.devices.forEach { d ->
            Row(Modifier.padding(start = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.name + if (d.deviceId == thisDevice) " (this phone)" else "",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (ownerControls && d.deviceId != thisDevice) GlassIconButton(Icons.Rounded.Close, "Remove ${d.name}", onClick = { onRemovePhone(d.deviceId, d.name) })
            }
        }
    }
}

@Composable
private fun DriveSpaceCard(drive: DriveState) {
    val theme = LocalGlassTheme.current
    val space = drive.space ?: return
    val joined = drive.joined ?: return
    val allocated = joined.household.members.sumOf { it.limitGb.toLong() } * DriveState.GB
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("Drive space")
        GlassProgressBar(1f - space.freeBytes / space.totalBytes.toFloat(), label = "Drive ${DetailsFormat.size(space.freeBytes)} free")
        Text(
            "${DetailsFormat.size(space.freeBytes)} free of ${DetailsFormat.size(space.totalBytes)}",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (allocated > space.totalBytes) {
            WarningNote(
                "Everyone's space adds up to ${DetailsFormat.size(allocated)}, more than this drive holds. " +
                    "Backups will stop when the drive is full, whatever each person's limit says.",
            )
        }
    }
}

/** Asks for the owner PIN and opens the owner controls for 5 minutes. */
@Composable
fun PinDialog(drive: DriveState, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    GlassDialog(
        title = "Owner PIN",
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton("Unlock", primary = true, onClick = {
                scope.launch {
                    error = drive.unlockOwner(pin)
                    if (error == null) onUnlocked()
                }
            })
        },
    ) {
        GlassTextField(
            pin,
            { if (it.length <= OwnerPin.MAX_DIGITS) pin = it.filter(Char::isDigit) },
            "PIN",
            password = true,
            keyboardType = KeyboardType.NumberPassword,
            supporting = "Owner controls stay open for 5 minutes.",
        )
        error?.let { WarningNote(it) }
    }
}

@Composable
private fun LimitDialog(member: Member, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val theme = LocalGlassTheme.current
    var gb by remember { mutableStateOf(member.limitGb) }
    var custom by remember { mutableStateOf(member.limitGb.toString()) }
    val valid = gb in MIN_LIMIT_GB..MAX_LIMIT_GB
    GlassDialog(
        title = "${member.name}'s space",
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton("Save", primary = true, onClick = { if (valid) onSave(gb) })
        },
    ) {
        Text("How much of the drive ${member.name}'s photos and videos can use.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(25, 50, 100, 200, 500, 1000).forEach { option ->
                GlassChip(if (option >= 1000) "${option / 1000} TB" else "$option GB", selected = gb == option, onClick = {
                    gb = option
                    custom = option.toString()
                })
            }
        }
        GlassTextField(custom, {
            custom = it.filter(Char::isDigit).take(6)
            gb = custom.toIntOrNull() ?: 0
        }, "Or enter GB", keyboardType = KeyboardType.Number)
        if (!valid) WarningNote("Choose between $MIN_LIMIT_GB and $MAX_LIMIT_GB GB.")
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val theme = LocalGlassTheme.current
    GlassDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton(confirm, primary = true, onClick = onConfirm)
        },
    ) {
        Text(body, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
    }
}
