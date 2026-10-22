package com.deepak.photovault.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.GlassButton
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassChip
import com.deepak.photovault.designsystem.GlassProgressBar
import com.deepak.photovault.designsystem.GlassSwitchRow
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader

/** Shown whenever mobile-data backup is switched on (agreed wording, PhotoVault Plan v2). */
const val MOBILE_DATA_WARNING =
    "Backing up on mobile data uses about twice the size of each photo or video, because the app checks " +
        "every file after uploading. Large videos can use a lot of data."

private enum class Frequency(val label: String) { DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly") }

/** Sample dashboard. Every control here is local to the screen until the backup engine arrives in Phase 5. */
@Composable
fun BackupScreen(drive: DriveState) {
    val theme = LocalGlassTheme.current
    var frequency by rememberSaveable { mutableStateOf(Frequency.DAILY) }
    var homeWifiOnly by rememberSaveable { mutableStateOf(true) }
    var chargingOnly by rememberSaveable { mutableStateOf(false) }
    var mobileData by rememberSaveable { mutableStateOf(false) }
    var videosOnMobile by rememberSaveable { mutableStateOf(false) }
    var photos by rememberSaveable { mutableStateOf(true) }
    var videos by rememberSaveable { mutableStateOf(true) }

    GlassListScreen(title = "Backup", subtitle = "Last backup today, 2:04 am") {
        item {
            GlassCard(Modifier.cardWidth()) {
                Text("Backing up", color = theme.onGlass, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("42 of 120 checked and saved to the drive", color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                GlassProgressBar(0.35f, label = "Backup progress, 35 percent")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Back up now", onClick = {}, primary = true, icon = Icons.Rounded.Upload)
                    GlassButton("Pause", onClick = {}, icon = Icons.Rounded.Pause)
                }
            }
        }
        item {
            GlassCard(Modifier.cardWidth()) {
                SectionHeader("Schedule")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Frequency.entries.forEach { option ->
                        GlassChip(option.label, selected = frequency == option, onClick = { frequency = option })
                    }
                }
                InfoRow(Icons.Rounded.Schedule, "Time", "2:00 am")
                InfoRow(Icons.Rounded.Schedule, "Next backup", "Around 2:00 am tomorrow")
                Text(
                    "Android may start a little late to save battery. If the conditions below aren't met, the backup waits and runs as soon as they are.",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                GlassSwitchRow("Only on home Wi-Fi", homeWifiOnly, { homeWifiOnly = it }, icon = Icons.Rounded.Wifi,
                    description = "When the drive can be reached directly")
                GlassSwitchRow("Only while charging", chargingOnly, { chargingOnly = it }, icon = Icons.Rounded.BatteryChargingFull)
                GlassSwitchRow(
                    "Use mobile data",
                    mobileData,
                    { mobileData = it; if (!it) videosOnMobile = false },
                    icon = Icons.Rounded.SignalCellularAlt,
                    warning = MOBILE_DATA_WARNING,
                )
                if (mobileData) {
                    GlassSwitchRow("Videos on mobile data", videosOnMobile, { videosOnMobile = it }, icon = Icons.Rounded.Movie)
                }
            }
        }
        item {
            GlassCard(Modifier.cardWidth()) {
                SectionHeader("What to back up")
                GlassSwitchRow("Photos", photos, { photos = it }, icon = Icons.Rounded.Photo)
                GlassSwitchRow("Videos", videos, { videos = it }, icon = Icons.Rounded.Movie)
                InfoRow(Icons.Rounded.Photo, "Folders", "Camera, Screenshots, WhatsApp Images")
            }
        }
        item {
            GlassCard(Modifier.cardWidth()) {
                SectionHeader("Your space")
                YourSpace(drive)
            }
        }
        item {
            GlassCard(Modifier.cardWidth()) {
                SectionHeader("Your phones")
                SampleData.phones.forEach { phone ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row {
                            Text(phone.name, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(phone.detail, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        GlassProgressBar(phone.progress, label = "${phone.name}: ${phone.detail}")
                    }
                }
            }
        }
    }
}

@Composable
internal fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String) {
    val theme = LocalGlassTheme.current
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = theme.onGlassSecondary)
        Spacer(Modifier.width(16.dp))
        Text(title, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}


/** Real figures once this phone has joined the family (Phase 3); the backup itself arrives in Phase 4. */
@Composable
private fun YourSpace(drive: DriveState) {
    val theme = LocalGlassTheme.current
    val joined = drive.joined
    if (joined == null) {
        Text(
            if (drive.isSetUp) "Join your family on the drive to see your space." else "Connect your drive to see your space.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val mine = drive.usage[joined.me.userId]
    val limit = mine?.limitBytes ?: joined.me.limitGb * DriveState.GB
    val used = mine?.usedBytes
    if (used != null) {
        InfoRow(Icons.Rounded.Storage, "Used", "${DetailsFormat.size(used)} of ${joined.me.limitGb} GB")
        GlassProgressBar((used / limit.toFloat()).coerceIn(0f, 1f), label = "Your space, ${DetailsFormat.size(used)} of ${joined.me.limitGb} gigabytes used")
    } else {
        InfoRow(Icons.Rounded.Storage, "Your limit", "${joined.me.limitGb} GB")
    }
    Text(
        if (drive.isOwner) "Backup pauses and tells you before your space runs out. You can change everyone's space on the Family page."
        else "Backup pauses and tells you before your space runs out. The drive owner can change your space.",
        color = theme.onGlassSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    drive.space?.let { InfoRow(Icons.Rounded.Storage, "Whole drive", "${DetailsFormat.size(it.freeBytes)} free of ${DetailsFormat.size(it.totalBytes)}") }
}
