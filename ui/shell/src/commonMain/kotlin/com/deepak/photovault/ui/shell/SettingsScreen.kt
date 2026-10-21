package com.deepak.photovault.ui.shell

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassSwitchRow
import com.deepak.photovault.designsystem.GlassThemes
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.ThemeSwatch

@Composable
fun SettingsScreen(state: ShellState) {
    val theme = LocalGlassTheme.current
    GlassListScreen(title = "Settings", subtitle = null) {
        item {
            GlassCard(Modifier.cardWidth()) {
                SectionHeader("Theme")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    GlassThemes.all.forEach { option ->
                        ThemeSwatch(
                            option,
                            selected = option.id == state.theme.id,
                            onClick = { state.updateAppearance { it.copy(themeId = option.id.name) } },
                        )
                    }
                }
                Text(theme.bestFor, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodyMedium)
                GlassSwitchRow(
                    "Glass blur",
                    state.appearance.blurOn,
                    { on -> state.updateAppearance { it.copy(blurOn = on) } },
                    icon = Icons.Rounded.BlurOn,
                    description = "Turns off by itself on battery saver",
                )
                GlassSwitchRow(
                    "Reduce motion",
                    state.appearance.reduceMotion,
                    { on -> state.updateAppearance { it.copy(reduceMotion = on) } },
                    icon = Icons.Rounded.Animation,
                    description = "Also follows the system setting",
                )
            }
        }
        item {
            GlassCard(Modifier.cardWidth()) {
                SectionHeader("Coming in later phases")
                InfoRow(Icons.Rounded.Dns, "Drive connection", "Phase 3")
                InfoRow(Icons.Rounded.Schedule, "Backup rules", "Phase 5")
                InfoRow(Icons.Rounded.VpnKey, "Remote access (VPN)", "Phase 7")
                InfoRow(Icons.Rounded.Lock, "Private mode (encryption)", "Later")
            }
        }
    }
}
