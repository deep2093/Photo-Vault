package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.rounded.PhotoCamera
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
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
    "Each person's photos go into their own folder, and Pixallery only shows you yours. Anyone who knows " +
        "the drive password can still open every folder from a computer, so this works for a family that trusts each other."

/**
 * Family (redesigned 24 Oct): the members as pictures, each with a small space bar under it (the owner sees
 * everyone's, others only their own), tapping a picture opens that person's page. The owner also gets the family
 * settings (moved here from Settings) and the drive's total space. Kept short and calm.
 */
@Composable
fun FamilyScreen(state: ShellState, drive: DriveState, actions: MediaActions = NoMediaActions) {
    val scope = rememberCoroutineScope()
    val theme = LocalGlassTheme.current
    // Fresh numbers when the screen opens, then every 20 seconds (new requests, space used). Only while
    // Family is on screen: sections stay ready in the background and this reads the drive (23 Oct).
    val onScreen = state.destination == Destination.FAMILY
    LaunchedEffect(drive.connection, onScreen) {
        while (onScreen && drive.isSetUp) {
            drive.refresh()
            drive.refreshUsage()
            delay(20_000)
        }
    }
    var pinFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun asOwner(action: () -> Unit) {
        if (drive.ownerUnlocked) action() else pinFor = action
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
                    drive.requests.forEach { r ->
                        RequestRow(
                            r, joined,
                            onApprove = { asOwner { scope.launch { message = drive.approve(r) } } },
                            onDecline = { asOwner { scope.launch { message = drive.decline(r) } } },
                        )
                    }
                }
            }
        }
        // Someone asked for more space (24 Oct): approve gives what they asked for.
        if (drive.isOwner && drive.spaceRequests.isNotEmpty()) {
            item {
                GlassCard(Modifier.cardWidth()) {
                    SectionHeader("Space requests")
                    drive.spaceRequests.forEach { r ->
                        val now = joined.household.member(r.userId)?.limitGb
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlassIcon(Icons.Rounded.Storage, tint = theme.onGlassSecondary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (now != null && r.wantGb > now) "${r.name} asks for ${r.wantGb - now} GB more" else "${r.name} asks for ${r.wantGb} GB", color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(now?.let { "Has $it GB now, would have ${r.wantGb} GB" }, r.note.ifBlank { null }).joinToString(" · "),
                                    color = theme.onGlassSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            GlassIconButton(Icons.Rounded.Close, "Decline", onClick = { asOwner { scope.launch { message = drive.declineSpace(r) } } })
                            GlassIconButton(Icons.Rounded.Check, "Give ${r.wantGb} GB", onClick = { asOwner { scope.launch { message = drive.approveSpace(r) } } })
                        }
                    }
                }
            }
        }
        item { MembersCard(state, drive) }
        if (drive.isOwner) {
            item { OwnerSettingsCard(drive) }
            item { DriveSpaceCard(drive) }
        }
        item { PrivacyCard() }
    }

    pinFor?.let { action ->
        PinDialog(drive, onDismiss = { pinFor = null }, onUnlocked = {
            pinFor = null
            action()
        })
    }
}

/** Everyone as a picture with their name; a bar under the picture shows space used where you may see it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MembersCard(state: ShellState, drive: DriveState) {
    val theme = LocalGlassTheme.current
    val joined = drive.joined ?: return
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("Family members")
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            joined.household.members.forEach { member ->
                val isMe = member.userId == joined.me.userId
                val canSee = drive.isOwner || isMe
                Column(
                    Modifier
                        .width(AVATAR_TILE)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = "Open ${member.name}") { state.open(Overlay.Member(member.userId)) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.foundation.layout.Box {
                        Avatar(member, drive, size = AVATAR_TILE - 8.dp)
                        // A small soft dot while this person's phone is backing up (24 Oct), gently pulsing.
                        if (member.userId in drive.backingUpNow) BackingUpDot(Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    if (canSee) {
                        SpaceBar(drive.usage[member.userId], member, Modifier.width(AVATAR_TILE - 8.dp))
                        Spacer(Modifier.height(4.dp))
                    }
                    // Just the name (24 Oct).
                    Text(
                        member.name,
                        color = theme.onGlass,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val AVATAR_TILE = 80.dp

@Composable
private fun BackingUpDot(modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "backing up")
    val alpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1200),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "dot",
    )
    androidx.compose.foundation.layout.Box(
        modifier
            .size(10.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(theme.accent)
            .semantics { contentDescription = "Backing up now" },
    )
}

/** A slim bar for someone's space; empty while it is still being counted. */
@Composable
internal fun SpaceBar(usage: MemberUsage?, member: Member, modifier: Modifier = Modifier) {
    val limit = usage?.limitBytes ?: (member.limitGb * DriveState.GB)
    val used = usage?.usedBytes
    GlassProgressBar(
        ((used ?: 0L) / limit.toFloat()).coerceIn(0f, 1f),
        modifier = modifier,
        label = "${member.name}: ${used?.let(DetailsFormat::size) ?: "counting"} of ${member.limitGb} GB used",
    )
}

/** A person's picture in a circle, or their first letter. */
@Composable
internal fun Avatar(member: Member, drive: DriveState, size: androidx.compose.ui.unit.Dp = 48.dp, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    // Your own picture of them if you set one, otherwise the shared one (24 Oct).
    val path = drive.photoPathFor(member)
    LaunchedEffect(path) { if (path != null) drive.loadPhoto(path) }
    val bytes = path?.let { drive.photos[it] }
    Box(modifier.size(size).clip(CircleShape).background(theme.accent), contentAlignment = Alignment.Center) {
        if (bytes != null) {
            coil3.compose.AsyncImage(
                model = bytes,
                contentDescription = "${member.name}'s picture",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                member.name.take(1).uppercase(),
                color = theme.onAccent,
                style = if (size >= 96.dp) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
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
            "Pixallery keeps each person's photos in their own folder on the shared drive at home.",
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

private const val KEYBOARD_SETTLE_MS = 220L

/** Asks for the owner PIN and opens the owner controls for 5 minutes. */
@Composable
fun PinDialog(drive: DriveState, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    GlassDialog(
        title = "Owner PIN",
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton(if (checking) "Checking..." else "Unlock", primary = true, onClick = {
                if (checking) return@GlassButton
                checking = true
                scope.launch {
                    error = drive.unlockOwner(pin)
                    if (error == null) {
                        // Let the keyboard slide away first, then close: the dialog closing while the keyboard
                        // was still moving made it stutter (23 Oct).
                        keyboard?.hide()
                        kotlinx.coroutines.delay(KEYBOARD_SETTLE_MS)
                        onUnlocked()
                    }
                    checking = false
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
internal fun LimitDialog(member: Member, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
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
