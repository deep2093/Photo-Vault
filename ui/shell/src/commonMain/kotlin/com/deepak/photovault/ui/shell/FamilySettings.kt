package com.deepak.photovault.ui.shell

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.HowToReg
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Password
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.household.OwnerPin
import com.deepak.photovault.designsystem.CollapsibleSection
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassDialog
import com.deepak.photovault.designsystem.GlassSwitchRow
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.coroutines.launch

/**
 * The owner's family settings (moved from Settings to Family on 24 Oct, owner only): owner PIN, approving new
 * phones, space for new members, changing the PIN, and locking the owner controls again. Closed by default.
 */
@Composable
internal fun OwnerSettingsCard(drive: DriveState) {
    val scope = rememberCoroutineScope()
    val joined = drive.joined ?: return
    val settings = joined.household.settings
    var pinFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var changePin by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun asOwner(action: () -> Unit) {
        if (drive.ownerUnlocked) action() else pinFor = action
    }

    CollapsibleSection(
        "Family settings",
        Icons.Rounded.AdminPanelSettings,
        Modifier.cardWidth(),
        summary = listOf(
            if (settings.requirePin) "PIN on" else "No PIN",
            if (settings.approveNewPhones) "you approve phones" else "phones join freely",
            "${settings.defaultLimitGb} GB each",
        ).joinToString(" · "),
    ) {
        GlassSwitchRow(
            "Owner PIN",
            settings.requirePin,
            { on -> asOwner { scope.launch { message = if (on && joined.household.ownerPin == null) "Set a PIN first" else drive.updateSettings { it.copy(requirePin = on) } } } },
            icon = Icons.Rounded.Lock,
        )
        GlassSwitchRow(
            "Approve new phones",
            settings.approveNewPhones,
            { on -> asOwner { scope.launch { message = drive.updateSettings { it.copy(approveNewPhones = on) } } } },
            icon = Icons.Rounded.HowToReg,
        )
        SectionHeader("Space for new members")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(25, 50, 100, 200, 500).forEach { gb ->
                GlassChip("$gb GB", selected = settings.defaultLimitGb == gb, onClick = {
                    asOwner { scope.launch { message = drive.updateSettings { it.copy(defaultLimitGb = gb) } } }
                })
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(
                if (joined.household.ownerPin == null) "Set PIN" else "Change PIN",
                icon = Icons.Rounded.Password,
                onClick = { asOwner { changePin = true } },
            )
            if (settings.requirePin && drive.ownerUnlocked) {
                GlassButton("Lock", icon = Icons.Rounded.LockOpen, onClick = drive::lockOwner)
            }
        }
        message?.let { WarningNote(it) }
    }

    pinFor?.let { action ->
        PinDialog(drive, onDismiss = { pinFor = null }, onUnlocked = {
            pinFor = null
            action()
        })
    }
    if (changePin) {
        NewPinDialog(onDismiss = { changePin = false }) { pin ->
            scope.launch {
                message = drive.setPin(pin)
                changePin = false
            }
        }
    }
}

@Composable
private fun NewPinDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    GlassDialog(
        title = "New owner PIN",
        onDismiss = onDismiss,
        buttons = {
            GlassButton("Cancel", onClick = onDismiss)
            GlassButton("Save", primary = true, onClick = {
                error = when {
                    !OwnerPin.isValid(pin) -> "The PIN must be 4 to 8 digits."
                    pin != confirm -> "The PINs don't match."
                    else -> null
                }
                if (error == null) onSave(pin)
            })
        },
    ) {
        GlassTextField(pin, { if (it.length <= OwnerPin.MAX_DIGITS) pin = it.filter(Char::isDigit) }, "New PIN", password = true, keyboardType = KeyboardType.NumberPassword)
        GlassTextField(confirm, { if (it.length <= OwnerPin.MAX_DIGITS) confirm = it.filter(Char::isDigit) }, "Confirm", password = true, keyboardType = KeyboardType.NumberPassword)
        error?.let { WarningNote(it) }
    }
}

