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
 */
@Composable
internal fun JoinRequestPrompt(drive: DriveState, foreground: Boolean) {
    if (!drive.isOwner || !foreground) return
    val shown = remember { mutableStateListOf<String>() }
    val request = drive.requests.firstOrNull { it.deviceId !in shown } ?: return
    val joined = drive.joined ?: return
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
        if (drive.ownerUnlocked) action() else askPin = action
    }
    askPin?.let { action ->
        PinDialog(drive, onDismiss = { askPin = null }, onUnlocked = {
            askPin = null
            action()
        })
        return
    }
    GlassDialog(
        title = "Request to join",
        onDismiss = { shown += request.deviceId },
        buttons = {
            GlassButton("Later", onClick = { shown += request.deviceId })
            GlassButton("Decline", onClick = {
                owner {
                    working = true
                    scope.launch {
                        failed = drive.decline(request)
                        working = false
                        if (failed == null) { shown += request.deviceId; toast("Request declined.") }
                    }
                }
            })
            GlassButton(if (working) "Working…" else if (failed != null) "Try again" else "Approve", primary = true, onClick = {
                if (!working) owner {
                    working = true
                    scope.launch {
                        failed = drive.approve(request, viewOnly = request.existingUserId != null && viewOnly)
                        working = false
                        if (failed == null) { shown += request.deviceId; toast("Added to the family.") }
                    }
                }
            })
        },
    ) {
        Text(
            if (request.newName != null) "$who wants to join your family on \"${request.deviceName}\"." else "$who is adding another phone, \"${request.deviceName}\".",
            color = theme.onGlass,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (request.newName != null) {
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
