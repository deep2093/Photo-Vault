package com.deepak.photovault.ui.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.rememberHaptics
import kotlinx.coroutines.launch

/**
 * Owner, app open (25 Oct): a phone asks to join and a pop-up with a firm buzz lets the owner approve or decline
 * right away. "Later" leaves it waiting in Family, where the dot still shows.
 *
 * 18 Nov (Deepak): another device of someone already in the family asks that person, on their own phone or tablet,
 * not the owner: the same six-digit code shows on both, and no owner PIN is needed. The owner gets no pop-up for those
 * (they stay in Family, so the owner can still let one in for someone who has lost their only other device).
 */
@Composable
internal fun JoinRequestPrompt(drive: DriveState, foreground: Boolean) {
    if (!foreground) return
    val shown = remember { mutableStateListOf<String>() }
    val joined = drive.joined ?: return
    val request = drive.requests.firstOrNull { r ->
        r.deviceId !in shown && (r.existingUserId == null || r.existingUserId == joined.me.userId)
    } ?: return
    val own = drive.isOwnDeviceRequest(request)
    if (!own && !drive.isOwner) return
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current
    var askPin by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 10 Nov: the answer from the drive is shown; before, a failed Approve just closed the popup.
    var failed by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    // 18 Nov (Deepak): another device of someone already in the family; the owner picks what it may do.
    var viewOnly by remember(request.deviceId) { mutableStateOf(true) }
    LaunchedEffect(request.deviceId) { haptics.longPress() }
    val who = request.newName ?: joined.household.member(request.existingUserId ?: "")?.name ?: "Someone"
    fun owner(action: () -> Unit) {
        // One's own device needs no PIN: being that person's phone is the permission (18 Nov).
        if (own || drive.ownerUnlocked) action() else askPin = action
    }
    askPin?.let { action ->
        PinDialog(drive, onDismiss = { askPin = null }, onUnlocked = {
            askPin = null
            action()
        })
        return
    }
    GlassDialog(
        title = if (own) "Your other device" else "Request to join",
        onDismiss = { shown += request.deviceId },
        buttons = {
            GlassButton("Later", onClick = { shown += request.deviceId })
            GlassButton("Decline", onClick = {
                owner {
                    working = true
                    scope.launch {
                        failed = if (own) drive.declineOwnDevice(request) else drive.decline(request)
                        working = false
                        if (failed == null) { shown += request.deviceId; toast("Request declined.") }
                    }
                }
            })
            GlassButton(if (working) "Working…" else if (failed != null) "Try again" else "Approve", primary = true, onClick = {
                if (!working) owner {
                    working = true
                    scope.launch {
                        failed = if (own) drive.approveOwnDevice(request, viewOnly) else drive.approve(request, viewOnly = request.existingUserId != null && viewOnly)
                        working = false
                        if (failed == null) { shown += request.deviceId; toast("Added to the family.") }
                    }
                }
            })
        },
    ) {
        Text(
            when {
                own -> "\"${request.deviceName}\" wants to join the family as you. Approve it only if it shows this code:"
                request.newName != null -> "$who wants to join your family on \"${request.deviceName}\"."
                else -> "$who is adding another phone, \"${request.deviceName}\"."
            },
            color = theme.onGlass,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (own) {
            Text(joinCode(request), color = theme.onGlass, style = MaterialTheme.typography.displaySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            OwnDevicePermissionChoice(viewOnly) { viewOnly = it }
        } else if (request.newName != null) {
            Text("They get their own folder on the drive and ${com.deepak.photovault.core.household.DEFAULT_LIMIT_GB} GB to start.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            DevicePermissionChoice(who, viewOnly) { viewOnly = it }
        }
        failed?.let { com.deepak.photovault.designsystem.WarningNote("Couldn't do that: $it") }
    }
}

/**
 * What another device of someone already in the family may do (18 Nov, Deepak): it always sees their backups, and
 * the owner decides whether it may also delete the ones made by their other devices. Its own it can always delete.
 */
@Composable
internal fun DevicePermissionChoice(who: String, viewOnly: Boolean, onChange: (Boolean) -> Unit) {
    val theme = LocalGlassTheme.current
    Text(
        "It will see $who's backups. May it also delete the ones made on $who's other devices?",
        color = theme.onGlassSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
    com.deepak.photovault.designsystem.GlassSegmented(
        listOf("View only", "View and delete"),
        selectedIndex = if (viewOnly) 0 else 1,
        onSelect = { onChange(it == 0) },
    )
}

/**
 * The code a device asking to join as someone already in the family shows, and the phone that lets it in shows too
 * (18 Nov): the same six digits on both means it is really that device, not another one asking under the same name.
 */
internal fun joinCode(request: com.deepak.photovault.core.household.JoinRequest): String =
    com.deepak.photovault.core.crypto.KeyRequestCode.of(
        "pixallery-join|${request.deviceId}|${request.existingUserId}|${request.requestedAtMillis}".encodeToByteArray(),
    )

/** One's own new device (18 Nov): may it also delete what this person's other devices backed up? */
@Composable
internal fun OwnDevicePermissionChoice(viewOnly: Boolean, onChange: (Boolean) -> Unit) {
    val theme = LocalGlassTheme.current
    Text(
        "It will see all your backups. May it also delete the ones made on your other devices?",
        color = theme.onGlassSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
    com.deepak.photovault.designsystem.GlassSegmented(
        listOf("View only", "View and delete"),
        selectedIndex = if (viewOnly) 0 else 1,
        onSelect = { onChange(it == 0) },
    )
}
