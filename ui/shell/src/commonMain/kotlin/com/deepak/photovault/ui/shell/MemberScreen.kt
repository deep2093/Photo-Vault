package com.deepak.photovault.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.deepak.photovault.core.household.Member
import com.deepak.photovault.core.media.DetailsFormat
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.GlassIconButton
import com.deepak.photovault.designsystem.GlassProgressBar
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.coroutines.launch

/**
 * One family member (24 Oct): a big picture (tap it to add, change or remove it), their space as a full bar with
 * "used of total, left" beside it, and their phones. The owner sees everyone's space and can change it, remove
 * phones or remove the person; everyone else sees their own space only.
 */
@Composable
fun MemberScreen(userId: String, state: ShellState, drive: DriveState, actions: MediaActions) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    val joined = drive.joined
    val member = joined?.household?.member(userId)
    if (joined == null || member == null) {
        GlassListScreen(title = "Family member", subtitle = null, onBack = { state.back() }) {
            item { Text("This person is no longer in the family.", color = theme.onGlassSecondary, modifier = Modifier.cardWidth()) }
        }
        return
    }
    val isMe = member.userId == joined.me.userId
    val isOwnerMember = member.userId == joined.household.ownerUserId
    val canSeeSpace = drive.isOwner || isMe
    var message by remember { mutableStateOf<String?>(null) }
    var pictureMenu by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<PickedImage?>(null) }
    var pinFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var limitOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var removePhone by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun asOwner(action: () -> Unit) {
        if (drive.ownerUnlocked) action() else pinFor = action
    }
    // Whose picture changes (24 Oct): your own, everyone's view of you; the owner, everyone's view of anyone
    // (with the PIN); anyone else, a private picture only they see.
    val sharedChange = drive.canChangePhoto(member.userId)
    fun forPicture(action: () -> Unit) = if (isMe || !sharedChange) action() else asOwner(action)

    GlassListScreen(title = member.name, subtitle = if (isOwnerMember) "Owner" else null, onBack = { state.back() }) {
        item {
            Column(Modifier.cardWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Avatar(
                        member, drive, size = 132.dp,
                        modifier = Modifier.clickable(onClickLabel = "Picture options") { forPicture { pictureMenu = true } },
                    )
                    if (true) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(36.dp).clip(CircleShape).background(theme.onGlass),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = theme.glassFallback.copy(alpha = 1f), modifier = Modifier.size(20.dp)) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(member.name, color = theme.onGlass, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(if (isMe) "You" else null, if (isOwnerMember) "Owner" else null, "${member.devices.size} ${if (member.devices.size == 1) "phone" else "phones"}").joinToString(" · "),
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        message?.let { item { WarningNote(it, Modifier.cardWidth()) } }
        if (canSeeSpace) {
            item {
                GlassCard(Modifier.cardWidth()) {
                    SectionHeader("Space")
                    val usage = drive.usage[member.userId]
                    val limit = usage?.limitBytes ?: (member.limitGb * DriveState.GB)
                    val used = usage?.usedBytes
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SpaceBar(usage, member, Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (used == null) "Counting..." else "${DetailsFormat.size(used)} of ${member.limitGb} GB",
                            color = theme.onGlass,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (used != null) {
                        Text(
                            "${DetailsFormat.size((limit - used).coerceAtLeast(0))} left",
                            color = theme.onGlassSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (drive.isOwner) {
                        GlassButton("Change space", icon = Icons.Rounded.Storage, modifier = Modifier.padding(top = 10.dp), onClick = { asOwner { limitOpen = true } })
                    }
                }
            }
        }
        item {
            // Each phone with its last backup (moved here from Settings > Backup, 24 Oct).
            var last by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
            LaunchedEffect(member.userId, canSeeSpace) { if (canSeeSpace) last = drive.lastBackups(member.userId) }
            GlassCard(Modifier.cardWidth()) {
                SectionHeader("Phones")
                member.devices.forEach { d ->
                    Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = theme.onGlassSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(
                                d.name + if (d.deviceId == drive.deviceId) " (this phone)" else "",
                                color = theme.onGlass,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (canSeeSpace) {
                                Text(
                                    last[d.deviceId]?.let { "Last backup ${whenText(it, drive.now())}" } ?: "No backups yet",
                                    color = theme.onGlassSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (drive.isOwner && d.deviceId != drive.deviceId) {
                            GlassIconButton(Icons.Rounded.Close, "Remove ${d.name}", onClick = { asOwner { removePhone = d.deviceId to d.name } })
                        }
                    }
                }
            }
        }
        if (isMe) {
            item {
                // Your own backups on the drive: see, sort, save back or delete them (24 Oct).
                GlassButton(
                    "Manage my backup",
                    icon = Icons.Rounded.Storage,
                    modifier = Modifier.cardWidth(),
                    onClick = { state.open(Overlay.Settings(SettingsPage.STORAGE, startOnDrive = true)) },
                )
            }
        }
        if (drive.isOwner && !isOwnerMember) {
            item {
                GlassButton("Remove from family", icon = Icons.Rounded.PersonRemove, modifier = Modifier.cardWidth(), onClick = { asOwner { confirmRemove = true } })
            }
        }
    }

    if (pictureMenu) {
        val privateOnly = !sharedChange
        PictureOptions(
            member = member,
            privateOnly = privateOnly,
            hasPicture = if (privateOnly) member.userId in drive.privatePhotos else member.photo != null,
            onDismiss = { pictureMenu = false },
            onChoose = {
                pictureMenu = false
                actions.pickProfilePhoto { image -> picked = image }
            },
            onRemove = {
                pictureMenu = false
                scope.launch { message = if (privateOnly) drive.clearPrivatePhoto(member.userId) else drive.clearPhoto(member.userId) }
            },
        )
    }
    picked?.let { image ->
        PhotoCropDialog(
            image = image,
            onDismiss = { picked = null },
            onSave = { left, top, side ->
                picked = null
                actions.cropProfilePhoto(image, left, top, side) { jpeg ->
                    scope.launch { message = if (sharedChange) drive.setPhoto(member.userId, jpeg) else drive.setPrivatePhoto(member.userId, jpeg) }
                }
            },
        )
    }
    pinFor?.let { action ->
        PinDialog(drive, onDismiss = { pinFor = null }, onUnlocked = {
            pinFor = null
            action()
        })
    }
    if (limitOpen) {
        LimitDialog(member, onDismiss = { limitOpen = false }) { gb ->
            // Closes at once; the drive is updated in the background (23 Oct: the dialog hung while it saved).
            limitOpen = false
            scope.launch { message = drive.setLimit(member.userId, gb) }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove ${member.name}?",
            body = "${member.name}'s phones stop backing up to this drive. Their photos already on the drive are kept.",
            confirm = "Remove",
            onDismiss = { confirmRemove = false },
        ) {
            scope.launch {
                message = drive.removeMember(member.userId)
                confirmRemove = false
                if (message == null) state.back()
            }
        }
    }
    removePhone?.let { (id, name) ->
        ConfirmDialog(
            title = "Remove $name?",
            body = "This phone stops backing up to the drive. Photos already backed up are kept.",
            confirm = "Remove",
            onDismiss = { removePhone = null },
        ) {
            scope.launch {
                message = drive.removeDevice(id)
                removePhone = null
            }
        }
    }
}

/** Add (no picture yet) or change and remove: full-width buttons, one under another, so nothing wraps. */
@Composable
private fun PictureOptions(member: Member, privateOnly: Boolean, hasPicture: Boolean, onDismiss: () -> Unit, onChoose: () -> Unit, onRemove: () -> Unit) {
    val theme = LocalGlassTheme.current
    GlassDialog(
        title = "${member.name}'s picture",
        onDismiss = onDismiss,
        buttons = { GlassButton("Cancel", onClick = onDismiss) },
    ) {
        Text(
            if (privateOnly) "Only you will see this picture. Without one, you see the family's picture." else "Shown to everyone in the family.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        if (!hasPicture) {
            GlassButton("Add picture", primary = true, onClick = onChoose, modifier = Modifier.fillMaxWidth())
        } else {
            GlassButton("Change picture", primary = true, onClick = onChoose, modifier = Modifier.fillMaxWidth())
            GlassButton("Remove picture", onClick = onRemove, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Position the picked photo in the circle: pinch to zoom, drag to move (24 Oct). Saves the square inside the circle.
 */
@Composable
private fun PhotoCropDialog(image: PickedImage, onDismiss: () -> Unit, onSave: (Int, Int, Int) -> Unit) {
    val theme = LocalGlassTheme.current
    var box by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val w = image.width.toFloat()
    val h = image.height.toFloat()
    fun crop(): Triple<Int, Int, Int>? = ProfileCrop.square(w, h, box.width.toFloat(), scale, offset)?.let { (l, t, s) ->
        Triple(l.toInt(), t.toInt(), s.toInt())
    }
    GlassDialog(
        title = "Move and zoom",
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton("Save", primary = true, onClick = { crop()?.let { (l, t, s) -> onSave(l, t, s) } })
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(260.dp)
                    .clip(CircleShape)
                    .background(theme.onGlass.copy(alpha = 0.08f))
                    .border(2.dp, theme.accent, CircleShape)
                    .onSizeChanged { box = it }
                    .pointerInput(image) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, ProfileCrop.MAX_ZOOM)
                            scale = nextScale
                            offset = ProfileCrop.clamp(offset + pan, w, h, box.width.toFloat(), nextScale)
                        }
                    },
            ) {
                AsyncImage(
                    model = image.jpeg,
                    contentDescription = "Picked photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }
        }
        Text(
            "Pinch to zoom, drag to move.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The maths behind [PhotoCropDialog], kept pure so it is tested. */
object ProfileCrop {
    const val MAX_ZOOM = 5f

    /** Keeps the photo covering the whole circle: it can't be dragged so far that an edge shows. */
    fun clamp(offset: Offset, w: Float, h: Float, box: Float, scale: Float): Offset {
        if (box <= 0f || w <= 0f || h <= 0f) return Offset.Zero
        val k = box / minOf(w, h) * scale
        val maxX = ((w * k - box) / 2f).coerceAtLeast(0f)
        val maxY = ((h * k - box) / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    /** The square of the photo (left, top, side, in its pixels) that shows inside the circle. */
    fun square(w: Float, h: Float, box: Float, scale: Float, offset: Offset): Triple<Float, Float, Float>? {
        if (box <= 0f || w <= 0f || h <= 0f) return null
        val k = box / minOf(w, h) * scale
        val side = box / k
        val cx = w / 2f - offset.x / k
        val cy = h / 2f - offset.y / k
        val left = (cx - side / 2f).coerceIn(0f, w - side)
        val top = (cy - side / 2f).coerceIn(0f, h - side)
        return Triple(left, top, side)
    }
}
