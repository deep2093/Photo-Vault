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
    var askPin by remember { mutableStateOf<(() -> Unit)?>(null) }
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
                    shown += request.deviceId
                    scope.launch { drive.decline(request) }
                }
            })
            GlassButton("Approve", primary = true, onClick = {
                owner {
                    shown += request.deviceId
                    scope.launch { drive.approve(request) }
                }
            })
        },
    ) {
        Text(
            if (request.newName != null) "$who wants to join your family on \"${request.deviceName}\"." else "$who is adding another phone, \"${request.deviceName}\".",
            color = theme.onGlass,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("They get their own folder on the drive and ${com.deepak.photovault.core.household.DEFAULT_LIMIT_GB} GB to start.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}
