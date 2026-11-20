package com.deepak.photovault.designsystem

import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Smallest touch target, per Android accessibility guidance. */
val MinTouchTarget = 48.dp

private val Capsule = RoundedCornerShape(percent = 50)

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, onWallpaper: Boolean = false) {
    Text(
        text,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
        // Photo dates on the page (onWallpaper) are set in the title serif (18 Nov); card headings keep the normal font.
        style = if (onWallpaper) titled(MaterialTheme.typography.titleMedium) else MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        // Primary colour: headers also sit straight on the wallpaper (photo dates), including adaptive wallpapers.
        color = if (onWallpaper) LocalGlassTheme.current.onWallpaper else LocalGlassTheme.current.onGlass,
    )
}

/** The red of buttons that delete or can't be undone (13 Nov), the same in every theme. */
val DangerColor = Color(0xFFD93025)

/**
 * Capsule button. [primary] fills with the accent; otherwise it is glass. [danger] (13 Nov): red, for deleting,
 * leaving and anything that can't be undone, so it never looks like an ordinary choice.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    centered: Boolean = false,
    danger: Boolean = false,
    /** Starting something (extra animations, 21 Nov): a small ring turns where the icon was, and taps wait. */
    busy: Boolean = false,
) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    val shape = Capsule
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // Clear Glass with Apple's motion (21 Nov): the button swells and bounces instead of dipping.
    val swell = theme.isClearGlass && LocalGlassSettings.current.iosMotion
    val prominent = theme.prominent
    Row(
        modifier
            .then(if (swell) Modifier.iosSwell(press) else Modifier.pressDip(press))
            .heightIn(min = MinTouchTarget)
            .then(
                when {
                    danger && theme.isLiquidGlass -> Modifier.clip(shape).background(DangerColor).solidButtonEdge(shape)
                    danger -> Modifier.clip(shape).background(DangerColor).glassDepth().glassRim(shape)
                    // Clear Glass (iOS's prominent glass): the main button is blue glass with white words.
                    primary && prominent != null -> Modifier.clip(shape).background(prominent).solidButtonEdge(shape)
                    // Calm glass (18 Nov, Deepak: "Move to bin isn't correct in any theme"): the main button is solid in
                    // the theme's accent colour, not a soft shade that looked greyed out. Liquid Glass (21 Nov, "Move to
                    // bin doesn't have the glass effect"): still solid, with the lit glass edge Cancel has beside it.
                    primary && GlassFinish.CALM && theme.isLiquidGlass -> Modifier.clip(shape).background(theme.accent).solidButtonEdge(shape)
                    primary && GlassFinish.CALM -> Modifier.clip(shape).background(theme.accent)
                    primary -> Modifier.clip(shape).background(theme.accentSurface).glassDepth().glassRim(shape)
                    else -> Modifier.glassPanel(shape)
                },
            )
            .liquidGlassTouch(press)
            .clickable(enabled = !busy, interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = {
                haptics.tap()
                onClick()
            })
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(8.dp),
    ) {
        val fg = if (danger || (primary && prominent != null)) Color.White else if (primary && GlassFinish.CALM) readableOn(theme.accent) else if (primary) theme.onAccent else theme.onGlass
        if (busy) Spinner(fg) else if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    val press = if (theme.isLiquidGlass) remember { androidx.compose.foundation.interaction.MutableInteractionSource() } else null
    Box(
        modifier
            .then(if (press != null) Modifier.iosSwell(press) else Modifier)
            .size(MinTouchTarget)
            .clip(CircleShape)
            .then(if (press != null) Modifier.liquidGlassTouch(press) else Modifier)
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = {
                haptics.tap()
                onClick()
            })
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        DepthIcon(icon, null, theme.onGlass, Modifier.size(24.dp))
    }
}

/** Single-choice chip (for example Day / Month / Year). */
@Composable
fun GlassChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    // 10 Nov: the theme's accent, like GlassSegmented's thumb, so the choice shows in every theme (the soft accent
    // surface was hard to see in some).
    val chosenFill = theme.prominent ?: theme.accent
    val background by animateColorAsState(if (selected) chosenFill else Color.Transparent, motionSpec())
    Box(
        modifier
            .heightIn(min = MinTouchTarget)
            .padding(vertical = 4.dp)
            .glassPanel(Capsule)
            .background(background)
            // Liquid Glass: the chosen chip has the same glass depth as a main button (21 Nov).
            .then(if (selected && theme.isLiquidGlass) Modifier.solidButtonEdge(Capsule) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = {
                haptics.tick()
                onClick()
            })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) (if (theme.prominent != null) Color.White else readableOn(theme.accent)) else theme.onGlass, style = MaterialTheme.typography.labelLarge)
    }
}

/** Dark ink or white, whichever reads better on [background]. */
fun readableOn(background: Color): Color =
    if (background.luminance() > 0.45f) Color(0xFF10141A) else Color.White

/**
 * A row of choices in one glass pill with a coloured thumb that slides to the chosen one (30 Oct). The thumb is the
 * theme's accent, so the choice shows in every theme; with Reduce motion it jumps.
 */
@Composable
fun GlassSegmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    var widthPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    val count = options.size.coerceAtLeast(1)
    val segment = widthPx / count
    // The thumb starts on the chosen option and only slides when the choice changes (14 Nov): it used to slide in
    // from the first option every time the control came back on screen (scrolled away and back).
    val target = segment * selectedIndex.coerceIn(0, count - 1)
    val slide = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    var placed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val spec = motionSpec<Float>()
    androidx.compose.runtime.LaunchedEffect(target, segment) {
        if (segment <= 0) return@LaunchedEffect
        if (placed) slide.animateTo(target.toFloat(), spec) else {
            slide.snapTo(target.toFloat())
            placed = true
        }
    }
    // Clear Glass (iOS): a grey strip with a raised marker on the chosen option, white by day and lighter grey at
    // night, under the usual words (21 Nov: the white marker used to sit on a white strip and couldn't be seen).
    val thumbFill = theme.segmentThumb
    val thumbText = if (theme.isClearGlass) theme.onGlass else readableOn(theme.accent)
    val track = theme.segmentTrack
    val raised = theme.isClearGlass
    // Liquid Glass and Clear Glass (21 Nov, Deepak: "these buttons don't have the glass depth effect"): the marker is a
    // glass droplet like the buttons: a soft light along its top, a little shade along its bottom and the lit edge.
    val glassy = theme.isLiquidGlass && LocalGlassSettings.current.blurEnabled && liquidGlassEdgeSupported()
    val glassDark = theme.isDark
    val glassLight = LocalGlassLight.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .then(if (track != null) Modifier.clip(Capsule).background(track) else Modifier.glassPanel(Capsule))
            .padding(3.dp)
            .onSizeChanged { widthPx = it.width }
            .drawBehind {
                if (segment > 0) {
                    val left = if (placed) slide.value else target.toFloat()
                    val corner = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                    if (raised) {
                        // A soft shadow under the marker, so it sits above the strip as iOS's does.
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.10f),
                            topLeft = androidx.compose.ui.geometry.Offset(left - 0.5.dp.toPx(), 1.5.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(segment + 1.dp.toPx(), size.height + 0.5.dp.toPx()),
                            cornerRadius = corner,
                        )
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.08f),
                            topLeft = androidx.compose.ui.geometry.Offset(left - 0.5.dp.toPx(), -0.5.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(segment + 1.dp.toPx(), size.height + 1.dp.toPx()),
                            cornerRadius = corner,
                        )
                    }
                    drawRoundRect(
                        color = thumbFill,
                        topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
                        size = androidx.compose.ui.geometry.Size(segment.toFloat(), size.height),
                        cornerRadius = corner,
                    )
                    if (glassy) {
                        androidx.compose.ui.graphics.drawscope.inset(left = left, top = 0f, right = (size.width - left - segment).coerceAtLeast(0f), bottom = 0f) {
                            drawRoundRect(SolidGlassSheen, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
                            drawLiquidGlassEdge(size.height / 2f, if (glassDark) 1f else 0f, glassLight?.value ?: DefaultGlassLight)
                        }
                    }
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEachIndexed { index, label ->
                val chosen = index == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = MinTouchTarget - 6.dp)
                        // No tap highlight: its square showed while the thumb slid (30 Oct); the thumb is the feedback.
                        .selectable(
                            selected = chosen,
                            interactionSource = null,
                            indication = null,
                            role = Role.RadioButton,
                            onClick = {
                                if (!chosen) {
                                    haptics.tick()
                                    onSelect(index)
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (chosen) thumbText else theme.onGlass,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (chosen) FontWeight.Bold else null,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * A setting with a switch. When [warning] is given it is shown while the switch is on, so the cost of the
 * choice is visible at the moment it is made (for example backing up on mobile data).
 */
@Composable
fun GlassSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    warning: String? = null,
    icon: ImageVector? = null,
) {
    val theme = LocalGlassTheme.current
    val haptics = rememberHaptics()
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(
                    value = checked,
                    interactionSource = press,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    role = Role.Switch,
                    onValueChange = { on ->
                        haptics.toggle(on)
                        onCheckedChange(on)
                    },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                // With a description under the title, the icon lines up with the title, not the middle (24 Oct).
                Icon(
                    icon,
                    contentDescription = null,
                    tint = theme.onGlassSecondary,
                    modifier = if (description != null) Modifier.align(Alignment.Top).padding(top = 2.dp) else Modifier,
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = theme.onGlass, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Text(description, color = theme.onGlassSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            // The row handles the click, so the switch itself is not separately focusable.
            GlassToggle(checked, press)
        }
        if (warning != null && checked) WarningNote(warning)
    }
}

/**
 * A switch made of glass (29 Oct): a frosted track with a fine edge, brighter when on, and a glass knob. No accent
 * colour. The row around it handles the touch.
 */
@Composable
private fun GlassToggle(checked: Boolean, press: androidx.compose.foundation.interaction.InteractionSource) {
    val theme = LocalGlassTheme.current
    val shape = RoundedCornerShape(percent = 50)
    // Clear Glass with Apple's motion (21 Nov): while pressed the knob stretches into a clear glass droplet, as iOS's does.
    val settings = LocalGlassSettings.current
    val droplet = theme.isClearGlass && settings.iosMotion && !settings.reduceMotion
    val pressed by press.collectIsPressedAsState()
    val held by animateFloatAsState(if (droplet && pressed) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 500f), label = "knob glass")
    val knobX by androidx.compose.animation.core.animateDpAsState(if (checked) 22.dp else 0.dp, motionSpec(180), label = "knob")
    // Easier to see (29 Oct): off is a darker track with a clear edge, on is a bright track. The knob is solid: a
    // see-through knob let its shadow show through.
    val dark = theme.isDark
    val ink = theme.onGlass
    // Clear Glass (21 Nov): green when on, as on iOS, with no dark edge around the green.
    val green = theme.switchOn
    val track = when {
        checked && green != null -> green
        checked -> if (dark) Color.White.copy(alpha = 0.55f) else ink.copy(alpha = 0.75f)
        else -> if (dark) Color.Black.copy(alpha = 0.30f) else ink.copy(alpha = 0.14f)
    }
    val edge = if (dark) Color.White else ink
    val edgeAlpha = if (checked && green != null) 0f else if (checked) 0.9f else 0.6f
    Box(
        Modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(shape)
            .background(track)
            // Liquid Glass (21 Nov): the track is a shallow groove (a little shade inside its top edge) and the knob a
            // glass droplet, so switches have the same depth as the buttons.
            .then(
                if (theme.isLiquidGlass && settings.blurEnabled) Modifier.drawBehind {
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.22f), 0.55f to Color.Transparent))
                } else Modifier,
            )
            .border(1.5.dp, edge.copy(alpha = edgeAlpha), shape)
            .padding(3.dp),
    ) {
        val knob = if (checked || !dark) Color.White else Color(0xFFE6E8EE)
        Box(
            Modifier
                // The droplet grows wider than the knob and stays centred on it.
                .offset(x = knobX - 5.dp * held)
                .size(width = 24.dp + 10.dp * held, height = 24.dp)
                .graphicsLayer { scaleX = 1f + 0.12f * held; scaleY = 1f + 0.12f * held }
                .clip(CircleShape)
                .background(knob.copy(alpha = 1f - 0.8f * held))
                .then(if (theme.isLiquidGlass) Modifier.solidButtonEdge(CircleShape) else Modifier)
                .then(if (dark || held > 0.5f) Modifier else Modifier.border(1.dp, ink.copy(alpha = 0.25f), CircleShape)),
        )
    }
}

@Composable
fun WarningNote(text: String, modifier: Modifier = Modifier) {
    val theme = LocalGlassTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(theme.badgePhoneOnly.copy(alpha = 0.12f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Warning, contentDescription = "Warning", tint = theme.onGlass, modifier = Modifier.size(20.dp))
        Text(text, color = theme.onGlass, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Linear progress on glass. Animates unless reduce motion is on. */
@Composable
fun GlassProgressBar(fraction: Float, modifier: Modifier = Modifier, label: String) {
    val theme = LocalGlassTheme.current
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), motionSpec(400))
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(Capsule)
            .background(theme.onGlass.copy(alpha = 0.12f))
            .semantics { contentDescription = label },
    ) {
        // Liquid Glass (21 Nov): the filled part has a soft light along its top, like the other solid glass.
        val glassy = theme.isLiquidGlass && LocalGlassSettings.current.blurEnabled
        Box(
            Modifier.fillMaxWidth(shown).height(10.dp).clip(Capsule).background(theme.accent)
                .then(if (glassy) Modifier.drawBehind { drawRect(SolidGlassSheen) } else Modifier),
        )
    }
}

/** Round swatch for the theme picker: just the colours, with a white ring and a tick when chosen. [theme] is spoken to screen readers. */
@Composable
fun ThemeSwatch(theme: GlassTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 13 Nov: the ring, the size and the tick ease in and out when the choice moves, and the swatch dips when
    // pressed. All read while drawing (graphicsLayer), so choosing a theme stays light.
    val still = LocalGlassSettings.current.reduceMotion
    val spec = if (still) androidx.compose.animation.core.snap<Float>() else androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f)
    val on = androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, spec, label = "swatch")
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val ring = LocalGlassTheme.current.onGlass
    Box(
        modifier
            .size(72.dp)
            .pressDip(press, to = 0.92f)
            .clip(CircleShape)
            .selectable(selected = selected, interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = theme.displayName },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(72.dp).graphicsLayer { alpha = on.value; val k = 0.86f + 0.14f * on.value; scaleX = k; scaleY = k }.border(3.dp, ring, CircleShape))
        Box(
            Modifier
                .size(64.dp)
                .graphicsLayer { val k = 1f - 0.1f * on.value; scaleX = k; scaleY = k }
                .clip(CircleShape)
                .background(Brush.linearGradient(theme.wallpaper + theme.glassRim))
                .border(1.dp, ring.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = theme.onGlass,
                modifier = Modifier.size(26.dp).graphicsLayer { alpha = on.value; scaleX = on.value; scaleY = on.value },
            )
        }
    }
}
