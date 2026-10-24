package com.deepak.photovault.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassSwitchRow
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.WarningNote

/** Where the family drive is reached from when away from home (Phase 5B-1). */
enum class RemoteStatus { NOT_SUPPORTED, NO_CONFIG, OFF, CONNECTING, CONNECTED, NEEDS_PERMISSION, ERROR }

/** What the platform does for remote access. Android: WireGuard. */
interface RemotePlatform {
    /** Pick a WireGuard .conf file from the phone and store it (encrypted). */
    fun importConfig()

    /** Store a WireGuard file that came inside an invite. */
    fun importConfigText(text: String) = Unit
    fun connect()
    fun disconnect()
    fun removeConfig()
    fun setAutoConnect(on: Boolean)
    fun setAppOnly(on: Boolean)
}

object NoRemotePlatform : RemotePlatform {
    override fun importConfig() = Unit
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun removeConfig() = Unit
    override fun setAutoConnect(on: Boolean) = Unit
    override fun setAppOnly(on: Boolean) = Unit
}

/** Remote access as the screens see it. The platform updates the fields. */
@Stable
class RemoteAccessState(val platform: RemotePlatform = NoRemotePlatform, supported: Boolean = false) {
    var status by mutableStateOf(if (supported) RemoteStatus.NO_CONFIG else RemoteStatus.NOT_SUPPORTED)

    /** When connected: whether the drive answers through the tunnel (null while unknown). */
    var driveReachable by mutableStateOf<Boolean?>(null)

    /** A line from the last action: imported, failed, why. */
    var message by mutableStateOf<String?>(null)
    var autoConnect by mutableStateOf(false)
    var appOnly by mutableStateOf(true)

    /** A backup or restore is using the tunnel, so it will not be turned off yet. */
    var held by mutableStateOf(false)
}

/** Settings row summary. */
fun RemoteAccessState.summary(): String = when (status) {
    RemoteStatus.NOT_SUPPORTED -> "Not available here"
    RemoteStatus.NO_CONFIG -> "Not set up"
    RemoteStatus.OFF -> "Off"
    RemoteStatus.CONNECTING -> "Connecting"
    RemoteStatus.CONNECTED -> if (driveReachable == false) "Connected, drive not answering" else "Connected"
    RemoteStatus.NEEDS_PERMISSION -> "Needs permission"
    RemoteStatus.ERROR -> "Problem"
}

@Composable
internal fun RemoteAccessContent(remote: RemoteAccessState, backup: BackupState) {
    val theme = LocalGlassTheme.current
    com.deepak.photovault.designsystem.GlassCard(Modifier.cardWidth()) {
        Text(
            "Away from home, Pixallery can reach your family drive through the WireGuard VPN on your router. " +
                "It connects only while Pixallery is on screen and turns off as soon as you leave it.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            when (remote.status) {
                RemoteStatus.NOT_SUPPORTED -> "Remote access isn't available on this device."
                RemoteStatus.NO_CONFIG -> "Not set up. Export a WireGuard file for this phone from your router, then import it here."
                RemoteStatus.OFF -> "Off. You are using your home connection, or the VPN is not needed right now."
                RemoteStatus.CONNECTING -> "Connecting..."
                RemoteStatus.CONNECTED -> when (remote.driveReachable) {
                    true -> "Connected. Your drive answers through the VPN."
                    false -> "Connected, but the drive doesn't answer. Check the drive is on and the WireGuard file allows your home network."
                    null -> "Connected. Checking the drive..."
                }
                RemoteStatus.NEEDS_PERMISSION -> "Android needs your OK to let Pixallery use a VPN. Tap Connect and allow it once."
                RemoteStatus.ERROR -> "The VPN couldn't start."
            },
            color = theme.onGlass,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        remote.message?.let { Text(it, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        if (remote.held && remote.status == RemoteStatus.CONNECTED) {
            Text("A backup is using the VPN, so it stays on until that finishes.", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (remote.status) {
                RemoteStatus.NOT_SUPPORTED -> Unit
                RemoteStatus.NO_CONFIG -> GlassButton("Import WireGuard file", primary = true, onClick = remote.platform::importConfig)
                RemoteStatus.CONNECTED, RemoteStatus.CONNECTING -> GlassButton("Disconnect", onClick = remote.platform::disconnect)
                else -> GlassButton("Connect", primary = true, onClick = remote.platform::connect)
            }
            if (remote.status != RemoteStatus.NO_CONFIG && remote.status != RemoteStatus.NOT_SUPPORTED) {
                GlassButton("Replace file", onClick = remote.platform::importConfig)
            }
        }
        if (remote.status != RemoteStatus.NO_CONFIG && remote.status != RemoteStatus.NOT_SUPPORTED) {
            GlassButton("Remove VPN setup", onClick = remote.platform::removeConfig, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (remote.status != RemoteStatus.NOT_SUPPORTED) {
        com.deepak.photovault.designsystem.GlassCard(Modifier.cardWidth().padding(top = 12.dp)) {
            GlassSwitchRow(
                "Use VPN for this app only",
                remote.appOnly,
                { remote.platform.setAppOnly(it) },
                icon = Icons.Rounded.Shield,
                description = "Other apps keep using their normal connection",
            )
            GlassSwitchRow(
                "Connect automatically",
                remote.autoConnect,
                { remote.platform.setAutoConnect(it) },
                icon = Icons.Rounded.Bolt,
                description = "Opening Pixallery away from home turns the VPN on by itself. It stays off at home.",
            )
            GlassSwitchRow(
                "Back up away from home in background",
                backup.settings.awayBackup,
                { on -> backup.update { it.copy(awayBackup = on) } },
                icon = Icons.Rounded.Backup,
                description = "A scheduled backup may turn the VPN on by itself, then off right after it finishes. Needs the one-time Android permission.",
            )
            if (backup.settings.awayBackup && remote.status == RemoteStatus.NEEDS_PERMISSION) {
                WarningNote("Tap Connect once above and allow the Android VPN request, or away-from-home backup can't start.")
            }
        }
    }
}
