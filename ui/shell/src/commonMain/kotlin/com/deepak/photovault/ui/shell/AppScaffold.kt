package com.deepak.photovault.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.AdaptiveLayout
import com.deepak.photovault.designsystem.GlassBackground
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.LocalHazeState
import com.deepak.photovault.designsystem.MinTouchTarget
import com.deepak.photovault.designsystem.NavigationLayout
import com.deepak.photovault.designsystem.PhotoVaultTheme
import com.deepak.photovault.designsystem.glassChrome
import com.deepak.photovault.designsystem.glassPanel
import com.deepak.photovault.designsystem.motionSpec
import dev.chrisbanes.haze.HazeState

/** Space each screen must leave around its scrolling content (system bars, floating navigation). */
val LocalScreenInsets = staticCompositionLocalOf { PaddingValues(0.dp) }

/** The whole app: theme, wallpaper, adaptive navigation and the current screen. Same on Android and PC. */
@Composable
fun PhotoVaultApp(state: ShellState, signals: PlatformSignals = PlatformSignals()) {
    PhotoVaultTheme(theme = state.theme, settings = state.glassSettings(signals)) {
        val hazeState = remember { HazeState() }
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            GlassBackground(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val system = WindowInsets.safeDrawing.asPaddingValues()
                    when (AdaptiveLayout.navigationFor(maxWidth.value.toInt())) {
                        NavigationLayout.BOTTOM_BAR -> BottomBarLayout(state, system)
                        NavigationLayout.SIDE_RAIL -> SideRailLayout(state, system)
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBarLayout(state: ShellState, system: PaddingValues) {
    val barSpace = 88.dp
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalScreenInsets provides PaddingValues(
                start = system.calculateStartPadding(LayoutDirection.Ltr),
                top = system.calculateTopPadding(),
                end = system.calculateEndPadding(LayoutDirection.Ltr),
                bottom = system.calculateBottomPadding() + barSpace,
            ),
        ) {
            ScreenHost(state)
        }
        FloatingNavigationBar(
            selected = state.destination,
            onSelect = { state.destination = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = system.calculateBottomPadding() + 12.dp),
        )
    }
}

@Composable
private fun SideRailLayout(state: ShellState, system: PaddingValues) {
    Row(Modifier.fillMaxSize()) {
        SideRail(
            selected = state.destination,
            onSelect = { state.destination = it },
            modifier = Modifier
                .fillMaxHeight()
                .padding(
                    start = system.calculateStartPadding(LayoutDirection.Ltr) + 12.dp,
                    top = system.calculateTopPadding() + 12.dp,
                    bottom = system.calculateBottomPadding() + 12.dp,
                ),
        )
        CompositionLocalProvider(
            LocalScreenInsets provides PaddingValues(
                top = system.calculateTopPadding(),
                end = system.calculateEndPadding(LayoutDirection.Ltr),
                bottom = system.calculateBottomPadding(),
            ),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight()) { ScreenHost(state) }
        }
    }
}

@Composable
private fun ScreenHost(state: ShellState) {
    val enter = motionSpec<Float>()
    val exit = motionSpec<Float>(150)
    AnimatedContent(
        targetState = state.destination,
        transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
        label = "destination",
    ) { destination ->
        when (destination) {
            Destination.PHOTOS -> PhotosScreen(state)
            Destination.ALBUMS -> AlbumsScreen()
            Destination.BACKUP -> BackupScreen()
            Destination.FAMILY -> FamilyScreen()
            Destination.SETTINGS -> SettingsScreen(state)
        }
    }
}

/** Phones: a floating glass capsule that blurs the photos scrolling underneath. */
@Composable
private fun FloatingNavigationBar(selected: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .glassChrome(shape)
            .padding(6.dp)
            .selectableGroup(),
    ) {
        Destination.entries.forEach { destination ->
            NavItem(destination, destination == selected, { onSelect(destination) }, Modifier.weight(1f), showLabel = true)
        }
    }
}

/** Tablets and PC: a fixed glass side menu. Never collapses. */
@Composable
private fun SideRail(selected: Destination, onSelect: (Destination) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    Column(
        modifier
            .width(112.dp)
            .glassPanel(RoundedCornerShape(28.dp))
            .padding(vertical = 16.dp, horizontal = 8.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "PhotoVault",
            color = theme.onGlass,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Destination.entries.forEach { destination ->
            NavItem(destination, destination == selected, { onSelect(destination) }, Modifier.fillMaxWidth(), showLabel = true)
        }
    }
}

@Composable
private fun NavItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    showLabel: Boolean,
) {
    val theme = LocalGlassTheme.current
    val fill by animateColorAsState(if (selected) theme.accent else Color.Transparent, motionSpec())
    val fg = if (selected) theme.onAccent else theme.onGlass
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(fill)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(destination.icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
        if (showLabel) {
            Spacer(Modifier.height(2.dp))
            Text(destination.label, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** Shared size for top bars, so screens line up. */
internal val TopBarHeight: Dp = MinTouchTarget + 32.dp
