package com.deepak.photovault.ui.shell

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.deepak.photovault.core.household.Membership
import com.deepak.photovault.core.household.OwnerPin
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassIcon
import com.deepak.photovault.designsystem.GlassSwitchRow
import com.deepak.photovault.designsystem.GlassTextField
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.WarningNote
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Connecting this phone to the family drive, then creating the family (first phone, becomes the owner) or
 * asking to join it (every other phone). See docs/HOUSEHOLD_PROTOCOL.md.
 */
@Composable
fun DriveSetupScreen(state: ShellState, drive: DriveState) {
    val membership = drive.membership
    // Reconnect quietly when opened with a saved drive, so the family step shows straight away.
    LaunchedEffect(Unit) { if (drive.isSetUp && membership == null) drive.refresh() }
    var editing by rememberSaveable { mutableStateOf(!drive.isSetUp) }
    GlassListScreen(
        title = if (editing || membership == null) "Connect your drive" else "Your family",
        subtitle = drive.connection?.takeIf { it.isComplete && !editing }?.let { "${it.host} / ${it.share} / ${it.folder}" },
        onBack = { state.back() },
    ) {
        if (editing || membership == null) {
            item { ConnectCard(drive, onConnected = { editing = false }) }
            item { HelpCard() }
        } else {
            item {
                when (membership) {
                    Membership.NoHousehold -> NoFamilyCard(drive, onChangeDrive = { editing = true })
                    is Membership.NotJoined -> JoinCard(drive, membership)
                    is Membership.Removed -> JoinCard(drive, Membership.NotJoined(membership.household), removed = true)
                    is Membership.Pending -> PendingCard(drive, membership)
                    is Membership.Joined -> JoinedCard(membership, onDone = { state.back() })
                }
            }
            item {
                GlassButton("Change drive", onClick = { editing = true }, icon = Icons.Rounded.Dns, modifier = Modifier.cardWidth())
            }
        }
    }
}

@Composable
private fun ConnectCard(drive: DriveState, onConnected: () -> Unit) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    val saved = drive.connection ?: DriveConnection()
    var host by rememberSaveable { mutableStateOf(saved.host) }
    var share by rememberSaveable { mutableStateOf(saved.share) }
    // The folder is not asked for any more: new drives use "Pixallery", and a saved connection keeps its own.
    val folder = saved.folder
    var user by rememberSaveable { mutableStateOf(saved.username) }
    var password by remember { mutableStateOf(saved.password) }
    var savePassword by rememberSaveable { mutableStateOf(saved.savePassword) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("Drive")
        Text(
            "The shared drive on your home network, for example the USB drive on your router.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (searching) "Searching..." else "Find drives", icon = Icons.Rounded.Search, onClick = {
                if (!searching) {
                    searching = true
                    scope.launch {
                        found = drive.discover()
                        searching = false
                    }
                }
            })
        }
        found?.let { hosts ->
            if (hosts.isEmpty()) {
                Text("No drives answered. Type the address instead.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    hosts.forEach { h -> GlassChip(h, selected = h == host, onClick = { host = h }) }
                }
            }
        }
        GlassTextField(host, { host = it.trim() }, "Address", placeholder = "192.168.1.1", keyboardType = KeyboardType.Uri)
        GlassTextField(share, { share = it.trim() }, "Shared folder", placeholder = "usb or media")
        GlassTextField(user, { user = it }, "Username", placeholder = "Leave empty for guest access")
        GlassTextField(password, { password = it }, "Password", password = true)
        GlassSwitchRow(
            "Remember password",
            savePassword,
            { savePassword = it },
            description = "Kept encrypted on this phone. Needed for automatic backups.",
        )
        error?.let { WarningNote(it) }
        GlassButton(
            if (drive.busy) "Connecting..." else "Connect",
            primary = true,
            onClick = {
                val candidate = DriveConnection(host, share, folder.ifBlank { DriveConnection.DEFAULT_FOLDER }, user, password, savePassword)
                if (!candidate.isComplete) {
                    error = "Enter the drive's address and shared folder."
                } else if (!drive.busy) {
                    scope.launch {
                        error = drive.connect(candidate)
                        if (error == null) onConnected()
                    }
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The drive has no family in this folder (24 Oct: a tablet joining landed straight on "Create family" because its
 * folder name differed). Warn first, show the folder, and only then offer to create a new family.
 */
@Composable
private fun NoFamilyCard(drive: DriveState, onChangeDrive: () -> Unit) {
    val theme = LocalGlassTheme.current
    var create by rememberSaveable { mutableStateOf(false) }
    if (create) {
        CreateFamilyCard(drive)
        return
    }
    val c = drive.connection
    GlassCard(Modifier.cardWidth()) {
        Text("No family found in this folder", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "If you already set up Pixallery on another phone, this phone is probably looking in a different folder. " +
                "Check that the address and shared folder match the other phone exactly (Settings > Drive on that phone).",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (c != null) {
            InfoRow(Icons.Rounded.Dns, "Address", c.host)
            InfoRow(Icons.Rounded.Folder, "Shared folder", c.share)
            InfoRow(Icons.Rounded.Folder, "Pixallery folder", c.folder)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Change folder", primary = true, onClick = onChangeDrive)
            GlassButton("Create a new family", onClick = { create = true })
        }
    }
}

/** Short tips before the full "How to set this up" guide arrives (Phase 13). */
@Composable
private fun HelpCard() {
    val theme = LocalGlassTheme.current
    var open by rememberSaveable { mutableStateOf(false) }
    GlassCard(Modifier.cardWidth()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            GlassIcon(Icons.AutoMirrored.Rounded.HelpOutline, tint = theme.accent)
            Spacer(Modifier.size(12.dp))
            Text("Need help?", color = theme.onGlass, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            GlassButton(if (open) "Hide" else "Show tips", onClick = { open = !open })
        }
        if (open) {
            listOf(
                "Plug the USB drive into the router and turn on file sharing (SMB, sometimes called \"Samba\" or \"USB sharing\") in the router's settings.",
                "Address: the router's address, often 192.168.0.1 or 192.168.1.1. Tap Find drives to look for it.",
                "Shared folder: the name the router gives the USB drive (for example \"usb\", \"G\" or \"volume1\").",
                "Username and password: the ones set for file sharing on the router. Leave empty if it allows guests.",
                "Every phone in the family must use the same address and shared folder, written exactly the same. Pixallery makes its own folder inside it.",
                "Be on the home Wi-Fi while setting up. Using the drive away from home (VPN) comes later.",
            ).forEach { tip ->
                Text("• $tip", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun CreateFamilyCard(drive: DriveState) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    GlassCard(Modifier.cardWidth()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            GlassIcon(Icons.Rounded.Groups, tint = theme.accent)
            Spacer(Modifier.size(12.dp))
            Text("Set up your family", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "This is the first phone on this drive, so you'll be the owner: you approve new phones and set " +
                "how much space each person gets (100 GB to start).",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        GlassTextField(name, { name = it }, "Your name")
        // Always needed (23 Oct): it protects owner settings, and it is how a new or replacement phone of the
        // owner joins as the owner if the old phone is lost.
        GlassTextField(
            pin,
            { if (it.length <= OwnerPin.MAX_DIGITS) pin = it.filter(Char::isDigit) },
            "Owner PIN",
            password = true,
            keyboardType = KeyboardType.NumberPassword,
            supporting = "4 to 8 digits. Keep it safe: it's how your next phone becomes the owner if this one is lost.",
        )
        GlassTextField(confirm, { if (it.length <= OwnerPin.MAX_DIGITS) confirm = it.filter(Char::isDigit) }, "Confirm PIN", password = true, keyboardType = KeyboardType.NumberPassword)
        WarningNote(FOLDER_PRIVACY_NOTE)
        error?.let { WarningNote(it) }
        GlassButton(
            if (drive.busy) "Setting up..." else "Create family",
            primary = true,
            onClick = {
                error = when {
                    name.isBlank() -> "Enter your name."
                    !OwnerPin.isValid(pin) -> "The PIN must be 4 to 8 digits."
                    pin != confirm -> "The PINs don't match."
                    else -> null
                }
                if (error == null && !drive.busy) scope.launch { error = drive.createFamily(name, pin) }
            },
        )
    }
}

@Composable
private fun JoinCard(drive: DriveState, notJoined: Membership.NotJoined, removed: Boolean = false) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    val household = notJoined.household
    var name by rememberSaveable { mutableStateOf("") }
    var existing by rememberSaveable { mutableStateOf<String?>(null) }
    var ownerPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val asOwner = existing != null && existing == household.ownerUserId
    val withPin = asOwner && ownerPin.isNotEmpty()
    GlassCard(Modifier.cardWidth()) {
        Text(
            "Join ${household.owner?.name ?: "the"}'s family",
            color = theme.onGlass,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (removed) WarningNote("This phone was removed from the family. You can ask to join again.")
        Text(
            if (household.settings.approveNewPhones) "The owner approves new phones, then you'll get your own folder." else "You'll get your own folder straight away.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        SectionHeader("Who is using this phone?")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("I'm new", selected = existing == null, onClick = { existing = null })
            household.members.forEach { m -> GlassChip(m.name, selected = existing == m.userId, onClick = { existing = m.userId }) }
        }
        if (existing == null) {
            GlassTextField(name, { name = it }, "Your name")
        } else {
            Text("This phone will back up into ${household.member(existing!!)?.name}'s folder.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
            // A new or replacement phone of the owner (23 Oct): the owner PIN lets it in straight away.
            if (asOwner && household.ownerPin != null) {
                GlassTextField(
                    ownerPin,
                    { if (it.length <= OwnerPin.MAX_DIGITS) ownerPin = it.filter(Char::isDigit) },
                    "Owner PIN",
                    password = true,
                    keyboardType = KeyboardType.NumberPassword,
                    supporting = "Joins as the owner straight away. Lost your old phone? Remove it in Family afterwards.",
                )
            } else if (asOwner) {
                WarningNote("Another of the owner's phones must approve this one.")
            }
        }
        WarningNote(FOLDER_PRIVACY_NOTE)
        error?.let { WarningNote(it) }
        GlassButton(
            if (withPin || (!household.settings.approveNewPhones && !asOwner)) "Join" else "Ask to join",
            primary = true,
            onClick = {
                error = when {
                    existing == null && name.isBlank() -> "Enter your name."
                    withPin && !OwnerPin.isValid(ownerPin) -> "The PIN must be 4 to 8 digits."
                    else -> null
                }
                if (error == null && !drive.busy) {
                    scope.launch {
                        error = when {
                            existing == null -> drive.join(newName = name)
                            withPin -> drive.join(existingUserId = existing, ownerPin = ownerPin)
                            else -> drive.join(existingUserId = existing)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun PendingCard(drive: DriveState, pending: Membership.Pending) {
    val theme = LocalGlassTheme.current
    val scope = rememberCoroutineScope()
    // Check every few seconds while this card is open, so approval shows up without tapping anything.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            drive.refresh()
        }
    }
    GlassCard(Modifier.cardWidth()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            GlassIcon(Icons.Rounded.HourglassTop, tint = theme.accent)
            Spacer(Modifier.size(12.dp))
            Text("Waiting for approval", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Ask ${pending.household.owner?.name ?: "the owner"} to open Pixallery and approve " +
                "\"${pending.request.deviceName}\" in Family. This page updates by itself.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        CircularProgressIndicator(color = theme.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        GlassButton("Cancel request", onClick = { scope.launch { drive.cancelJoin() } })
    }
}

@Composable
private fun JoinedCard(joined: Membership.Joined, onDone: () -> Unit) {
    val theme = LocalGlassTheme.current
    GlassCard(Modifier.cardWidth()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            GlassIcon(Icons.Rounded.CheckCircle, tint = theme.badgeBackedUp)
            Spacer(Modifier.size(12.dp))
            Column {
                Text("You're in, ${joined.me.name}", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (joined.isOwner) "You're the owner of this family." else "Your photos will go into your own folder.",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassButton("Done", primary = true, onClick = onDone)
    }
}
