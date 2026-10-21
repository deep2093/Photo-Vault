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
import androidx.compose.material.icons.rounded.Storage
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
                SectionHeader("Glass themes")
                ThemeRow(GlassThemes.fixed, state)
                SectionHeader("Adaptive themes")
                Text(
                    "The background takes its colours from the photos on screen.",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                ThemeRow(GlassThemes.adaptiveThemes, state)
                Text(
                    "Coming next: Adaptive Light, Translucent and Frosted.",
                    color = theme.onGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
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
                InfoRow(Icons.Rounded.Storage, "Space per person ($DEFAULT_STORAGE_LIMIT_GB GB)", "Phase 3")
                InfoRow(Icons.Rounded.Schedule, "Backup rules", "Phase 4")
                InfoRow(Icons.Rounded.Lock, "Secured folder", "Phase 8")
                InfoRow(Icons.Rounded.VpnKey, "Remote access (VPN)", "Phase 9")
            }
        }
    }
}

@Composable
private fun ThemeRow(themes: List<com.deepak.photovault.designsystem.GlassTheme>, state: ShellState) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        themes.forEach { option ->
            ThemeSwatch(
                option,
                selected = option.id == state.theme.id,
                onClick = { state.updateAppearance { it.copy(themeId = option.id.name) } },
            )
        }
    }
}
