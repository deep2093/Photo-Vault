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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.GlassCard
import com.deepak.photovault.designsystem.GlassSwitchRow
import com.deepak.photovault.designsystem.GlassThemes
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.SectionHeader
import com.deepak.photovault.designsystem.ThemeSwatch

@Composable
fun SettingsScreen(state: ShellState, iconSwitcher: AppIconSwitcher = NoAppIconSwitcher) {
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
        item { AppIconCard(iconSwitcher) }
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

/**
 * App icon: Leaf (default) or Petals, each on a Light, Dark, Colourful or Clear background. On Android 13+
 * the home screen can also show either one tinted to match it ("Themed icons" in wallpaper settings).
 */
@Composable
private fun AppIconCard(switcher: AppIconSwitcher) {
    val theme = LocalGlassTheme.current
    var current by remember { mutableStateOf(switcher.current()) }
    GlassCard(Modifier.cardWidth()) {
        SectionHeader("App icon")
        IconGlyph.entries.forEach { glyph ->
            Text(glyph.label, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconBackground.entries.forEach { background ->
                    val choice = AppIconChoice(glyph, background)
                    val selected = choice == current
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                if (!selected) {
                                    switcher.set(choice)
                                    current = choice
                                }
                            })
                            .padding(6.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "${glyph.label} icon, ${background.label}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(60.dp)
                                .then(if (selected) Modifier.border(3.dp, theme.accent, CircleShape) else Modifier)
                                .padding(4.dp),
                        ) {
                            AppIconPreview(choice, Modifier.fillMaxSize())
                        }
                        Text(
                            background.label,
                            color = if (selected) theme.onGlass else theme.onGlassSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        Text(
            "Your home screen may take a moment to show the new icon. Turn on themed icons in your phone's " +
                "wallpaper settings for a version that matches your colours.",
            color = theme.onGlassSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
