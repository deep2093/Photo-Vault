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
    val toast = LocalToast.current
    val theme = LocalGlassTheme.current
    // Family shows the family remembered on this phone and does not read the drive by itself (30 Oct): pull down for
    // fresh numbers and new requests (the dot on Family still comes from the hourly check). Only a phone that has
    // never read its family asks once.
    val onScreen = state.destination == Destination.FAMILY
    val keyRequests = rememberKeyRequests(drive, onScreen, haveMyKey = state.encryption.keyOnPhone)
    // 18 Nov: someone's new device asks their own phone to let it in; while Family is open here, that is checked every
    // 30 seconds (the owner's phone already checks all the time), so the request shows quickly.
    LaunchedEffect(onScreen, drive.isOwner, drive.joined?.me?.userId) {
        while (onScreen && !drive.isOwner && drive.joined != null) {
            drive.checkRequests()
            kotlinx.coroutines.delay(FAMILY_REQUESTS_MS)
        }
    }
    LaunchedEffect(drive.connection, onScreen) {
        if (onScreen && drive.isSetUp && drive.membership == null) {
            drive.refresh()
            drive.refreshUsage()
        }
    }
    var pinFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // 18 Nov: another device of someone in the family, waiting for the owner to choose what it may do.
    var approving by remember { mutableStateOf<JoinRequest?>(null) }
    fun asOwner(action: () -> Unit) {
        if (drive.ownerUnlocked) action() else pinFor = action
    }

    val joined = drive.joined
    GlassListScreen(
        title = null,
        subtitle = null,
        onRefresh = {
            if (drive.isSetUp) {
                drive.refresh()
                drive.refreshUsage(force = true)
            }
        },
    ) {
        if (joined == null) {
            // The drive is still being asked for the first time: don't offer setup yet (30 Oct).
            if (drive.isSetUp && drive.membership == null && drive.checkedAtMillis == 0L) {
                // 13 Nov: soft placeholder rows instead of a line of text.
                item { GlassCard(Modifier.cardWidth()) { com.deepak.photovault.designsystem.SkeletonRows(3, label = "Looking for your family") } }
                return@GlassListScreen
            }
            item { NotConnectedCard(state, drive) }
            item { PrivacyCard() }
            return@GlassListScreen
        }
        message?.let { item { WarningNote(it, Modifier.cardWidth()) } }
        drive.problem?.let { item { WarningNote("Can't reach the drive: $it", Modifier.cardWidth()) } }
        // 18 Nov: a device was added to this person since this one last looked. Not theirs? The owner can remove it.
        if (drive.newDevices.isNotEmpty()) item(key = "new-devices") { NewDevicesCard(drive) }
        // 18 Nov (A1): someone who chose this person as their recovery helper is asking for their key back.
        keyRequests.value.forEach { r ->
            item(key = "key-request-${r.userId}") { KeyRequestCard(state, drive, r) { keyRequests.value = keyRequests.value.filter { it.userId != r.userId } } }
        }
        // The owner sees every request; anyone else (18 Nov) sees another of their own devices asking to join as them.
        if (drive.requests.isNotEmpty() && joined != null) {
            item {
                GlassCard(Modifier.cardWidth()) {
                    SectionHeader("Waiting for you")
                    drive.requests.forEach { r ->
                        val own = drive.isOwnDeviceRequest(r)
                        RequestRow(
                            r, joined, own,
                            onApprove = {
                                // 18 Nov: another device of someone in the family asks first what it may do.
                                if (r.existingUserId != null) approving = r
                                else asOwner { scope.launch { message = drive.approve(r); if (message == null) toast("Added to the family.") } }
                            },
                            onDecline = {
                                if (own) scope.launch { message = drive.declineOwnDevice(r); if (message == null) toast("Request declined.") }
                                else asOwner { scope.launch { message = drive.decline(r); if (message == null) toast("Request declined.") } }
                            },
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
        // 13 Nov: on a tablet the people and invites sit on the left, settings and space on the right.
        // 13 Nov (Deepak's tablet photo): two columns only for the owner, who has enough cards to fill them; a
        // member's page has two small cards, which look better one under the other in the middle.
        if (drive.isOwner) {
            item {
                TabletColumns(
                    left = {
                        MembersCard(state, drive)
                        InviteCard(state, drive, ::asOwner)
                    },
                    right = {
                        OwnerSettingsCard(drive)
                        DriveSpaceCard(drive)
                        PrivacyCard()
                    },
                )
            }
        } else {
            item { MembersCard(state, drive) }
            item { PrivacyCard() }
        }
    }

    approving?.let { r ->
        val who = joined?.household?.member(r.existingUserId ?: "")?.name ?: "Someone"
        val own = drive.isOwnDeviceRequest(r)
        var viewOnly by remember(r.deviceId) { mutableStateOf(true) }
        GlassDialog(
            title = "Add \"${r.deviceName}\"",
            onDismiss = { approving = null },
            buttons = {
                GlassButton("Cancel", onClick = { approving = null })
                GlassButton("Approve", primary = true, onClick = {
                    approving = null
                    // One's own device needs no owner PIN (18 Nov); the owner approving for someone else does.
                    if (own) scope.launch { message = drive.approveOwnDevice(r, viewOnly); if (message == null) toast("Added to the family.") }
                    else asOwner { scope.launch { message = drive.approve(r, viewOnly); if (message == null) toast("Added to the family.") } }
                })
            },
        ) {
            if (own) {
                Text("\"${r.deviceName}\" wants to join the family as you. Approve it only if it shows this code:", color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
                Text(joinCode(r), color = theme.onGlass, style = MaterialTheme.typography.displaySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                OwnDevicePermissionChoice(viewOnly) { viewOnly = it }
            } else {
                Text("$who is adding another device. Usually $who lets it in from their own phone; approve here if they can't.", color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
                Text("It shows the code ${joinCode(r)}.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                DevicePermissionChoice(who, viewOnly) { viewOnly = it }
            }
        }
    }
    pinFor?.let { action ->
        PinDialog(drive, onDismiss = { pinFor = null }, onUnlocked = {
            pinFor = null
            action()
        })
    }
}

/** A device was added to this person (18 Nov), so nobody becomes "you" on another phone unnoticed. */
@Composable
private fun NewDevicesCard(drive: DriveState) {
    val theme = LocalGlassTheme.current
    val (mine, others) = drive.newDevices.partition { it.person == null }
    GlassCard(Modifier.cardWidth()) {
        SectionHeader(if (drive.newDevices.size == 1) "A new device" else "New devices")
        if (mine.isNotEmpty()) {
            Text(
                "${mine.joinToString(", ") { "\"${it.device.name}\"" }} was added to you and sees your backups. If that isn't " +
                    (if (drive.isOwner) "yours, remove it on your page in Family." else "yours, ask the owner to remove it in Family."),
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // The owner (18 Nov): people now let their own new devices in, so the owner hears about each one.
        others.forEach {
            Text(
                "${it.person} added \"${it.device.name}\".",
                color = theme.onGlassSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        GlassButton("OK", onClick = drive::newDevicesSeen, modifier = Modifier.padding(top = 10.dp))
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
                    // Family roles (16 Nov): the owner sees who is View only at a glance.
                    if (drive.isOwner && member.role == com.deepak.photovault.core.household.MemberRole.VIEW_ONLY && member.userId != joined.household.ownerUserId) {
                        Text("View only", color = theme.onGlassSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

private val AVATAR_TILE = 80.dp

@Composable
private fun BackingUpDot(modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    // 20 Nov: it holds still with reduce motion (a steady dot says the same).
    val still = com.deepak.photovault.designsystem.LocalGlassSettings.current.reduceMotion
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "backing up")
    val breathing by pulse.animateFloat(
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
            .graphicsLayer { this.alpha = if (still) 0.9f else breathing }
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
        // Removed by the owner (16 Nov): said plainly, once, on this phone.
        if (drive.removedFromFamily) {
            WarningNote("The owner removed this phone from the family, so it no longer keeps the drive's details. To join again, ask the owner for a new invite.")
        }
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

/** How often Family looks for one's own new device asking to join, while it is open (18 Nov). */
private const val FAMILY_REQUESTS_MS = 30_000L

@Composable
private fun RequestRow(request: JoinRequest, joined: Membership.Joined, own: Boolean, onApprove: () -> Unit, onDecline: () -> Unit) {
    val theme = LocalGlassTheme.current
    val who = request.newName ?: joined.household.member(request.existingUserId ?: "")?.name ?: "Someone"
    Row(Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        GlassIcon(Icons.Rounded.PhoneAndroid, tint = theme.onGlassSecondary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(request.deviceName, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    own -> "Wants to join as you · code ${joinCode(request)}"
                    request.newName != null -> "New person: $who"
                    else -> "Another device of $who (usually $who approves it)"
                },
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
fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    /** Red confirm button (13 Nov). By default worked out from the button's words: Delete, Remove, Leave, Erase... */
    danger: Boolean = isDangerous(confirm),
    onConfirm: () -> Unit,
) {
    val theme = LocalGlassTheme.current
    GlassDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton(confirm, primary = !danger, danger = danger, onClick = onConfirm)
        },
    ) {
        Text(body, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
    }
}

private val dangerWords = listOf("delete", "remove", "leave", "erase", "forget", "empty", "clear", "reset")

/**
 * True when a button's words mean something is lost or can't be undone, so it is drawn red everywhere (13 Nov).
 * Clearing a cache loses nothing, so that one stays an ordinary button.
 */
fun isDangerous(label: String): Boolean = label.lowercase().let { l -> "cache" !in l && dangerWords.any { l.startsWith(it) } }
