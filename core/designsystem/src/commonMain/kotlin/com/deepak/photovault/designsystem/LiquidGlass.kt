package com.deepak.photovault.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * Liquid Glass, made like iOS's (21 Nov, Deepak: "transparent glossy elements, like water droplets, with depth. No
 * frost or blur of any kind"). The floating bars look through to the photos with nothing blurred: the Android lens
 * (LiquidGlassAgsl.LENS, Android 13+) bends the picture at their rounded edges like a droplet and lights the edge.
 * Panels and buttons on the page get the same lit edge ([liquidGlassEdge]). The selected tab is a small droplet of
 * brighter glass ([liquidGlassDroplet]). Clear Glass, the copy of iOS (21 Nov), adds [AdaptiveGlass] (bars turn dark
 * over dark photos and light over light ones), light that follows the phone's tilt ([LocalGlassLight]) and Tinted.
 */

/**
 * How one Liquid Glass theme keeps its words readable without frost (see [LiquidGlassInk]): the least light kept behind
 * dark words by day, the most behind white words by night, and the same for the chosen tab's droplet. Linear luminance.
 */
@Immutable
data class GlassInk(
    val dayFloor: Float,
    val nightCeiling: Float,
    val selectedDayFloor: Float,
    val selectedNightCeiling: Float,
) {
    companion object {
        /** Liquid Glass (21 Nov). */
        val LIQUID = GlassInk(LiquidGlassInk.DAY_FLOOR, LiquidGlassInk.NIGHT_CEILING, LiquidGlassInk.SELECTED_DAY_FLOOR, LiquidGlassInk.SELECTED_NIGHT_CEILING)

        /**
         * Clear Glass: the same rule for its black and white words. Its chosen tab is iOS blue, so the tab's droplet is
         * near-white glass by day and dark grey glass by night (as on iOS), where the blue reads at 3:1 like iOS's.
         */
        val CLEAR = GlassInk(0.27f, 0.14f, 0.80f, 0.042f)
    }
}

/**
 * How Liquid Glass keeps its words readable without frost (21 Nov). Behind the middle of the glass, by day the photo
 * is brightened just enough for dark words (keeping its colour where it can); by night it is dimmed like smoked glass
 * for white words. The Android lens does exactly this to every pixel (toneForInk in LiquidGlassAgsl), and
 * ThemeContrastTest checks each theme's words against [throughGlass].
 */
object LiquidGlassInk {
    /** By day: the least light kept behind dark words (linear luminance). Black photos show as mid grey, not white. */
    const val DAY_FLOOR = 0.27f

    /** By night: the most light kept behind white words (linear luminance). White photos show as smoked grey. */
    const val NIGHT_CEILING = 0.15f

    /** The selected tab's droplet: a little brighter glass by day, a little lighter smoke by night. */
    const val SELECTED_DAY_FLOOR = 0.42f
    const val SELECTED_NIGHT_CEILING = 0.17f

    /**
     * Clear Glass lays a light film of the glass's own colour over the picture (never a blur), as iOS's glass is a
     * little milky: the photo's own words and lines behind a bar are fainter than the bar's (21 Nov, Deepak's
     * recordings: a screenshot's text showed through the bar's words). Clear is this much film; Tinted (iOS 26.1) is
     * nearly solid, so the two are easy to tell apart (they were 0 and 0.55, hard to tell apart by day).
     */
    const val CLEAR_FILM = 0.40f
    const val TINTED_FROST = 0.86f

    /** Clear Glass's chosen tab: a light film by day, iOS's dark grey glass by night. */
    const val SELECTED_FROST_DAY = 0.30f
    const val SELECTED_FROST_NIGHT = 0.65f

    /**
     * A photo's colour as seen through the middle of the glass: toned for the words, then [frost] of [frostColor] laid
     * over it (Tinted, and Clear Glass's chosen tab).
     */
    fun throughGlass(
        photo: Color,
        dark: Boolean,
        floor: Float = DAY_FLOOR,
        ceiling: Float = NIGHT_CEILING,
        frost: Float = 0f,
        frostColor: Color = Color.White,
    ): Color {
        val r = toLinear(photo.red)
        val g = toLinear(photo.green)
        val b = toLinear(photo.blue)
        val luma = max(0.2126 * r + 0.7152 * g + 0.0722 * b, 0.00001)
        val toned = if (dark) {
            val target = ceiling * (1.0 - exp(-luma / ceiling))
            val k = target / luma
            doubleArrayOf(r * k, g * k, b * k)
        } else {
            val target = floor + (1.0 - floor) * luma
            val brightest = max(max(r, max(g, b)), 0.00001)
            val gain = min(min(target / luma, 1.0 / brightest), 3.0)
            val lifted = luma * gain
            val toWhite = max(0.0, (target - lifted) / max(1.0 - lifted, 0.00001))
            doubleArrayOf(r * gain, g * gain, b * gain).map { it + (1.0 - it) * toWhite }.toDoubleArray()
        }
        val f = frost.toDouble()
        val fr = toLinear(frostColor.red)
        val fg = toLinear(frostColor.green)
        val fb = toLinear(frostColor.blue)
        return Color(
            toSrgb(toned[0] + (fr - toned[0]) * f),
            toSrgb(toned[1] + (fg - toned[1]) * f),
            toSrgb(toned[2] + (fb - toned[2]) * f),
        )
    }

    /** The film Clear Glass lays over its chosen tab: white by day, iOS's dark grey (3A3A3C) by night. */
    fun selectedFrostColor(dark: Boolean): Color = if (dark) Color(0xFF3A3A3C) else Color.White

    /** The film of Clear and Tinted: white by day, iOS's dark glass (1C1C1E) by night. */
    fun tintedFrostColor(dark: Boolean): Color = if (dark) Color(0xFF1C1C1E) else Color.White

    /** The same film while a bar changes over ([darkness] 0 light glass .. 1 dark glass). */
    fun tintedFrostColor(darkness: Float): Color = lerp(tintedFrostColor(false), tintedFrostColor(true), darkness.coerceIn(0f, 1f))

    private fun toLinear(c: Float): Double {
        val v = c.toDouble().coerceIn(0.0, 1.0)
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun toSrgb(c: Double): Float {
        val v = c.coerceIn(0.0, 1.0)
        return (if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055).toFloat()
    }
}

/** Where the light on Liquid Glass comes from, on the screen: the top left, a little above. */
val DefaultGlassLight = Offset(-0.42f, -0.76f)

/** Clear Glass: the light's direction, moved by tilting the phone (read while drawing). Null: [DefaultGlassLight]. */
val LocalGlassLight = staticCompositionLocalOf<State<Offset>?> { null }

/** Clear Glass ([AdaptiveGlass]): 0 light glass .. 1 dark glass, moving smoothly when a bar flips. */
val LocalGlassDarkness = staticCompositionLocalOf<State<Float>?> { null }

/** Clear Glass ([AdaptiveGlass]): how bright the photos behind this bar are (linear, 0 to 1), once known. */
val LocalBackdropLuma = staticCompositionLocalOf<State<Float?>?> { null }

/** The page's own colour behind see-through content, kept when a bar flips to the other look. */
val LocalGlassBackdrop = staticCompositionLocalOf<Color?> { null }

/** Android 13+ draws the lit glass edge with a shader; older phones keep the gradient edge. */
internal expect fun liquidGlassEdgeSupported(): Boolean

/** Draws the glass edge over this area (its corners [radius] round), under whatever is drawn next. */
internal expect fun DrawScope.drawLiquidGlassEdge(radius: Float, darkness: Float, light: Offset)

/**
 * The lit edge of Liquid Glass on a panel or button (21 Nov): a thin bright line where the rounded edge catches the
 * light, a soft gloss at the top left and a glint at the bottom right, and a faint shade inside the edge for its
 * thickness. Drawn under the words. The area must already be clipped to [shape].
 */
@Composable
internal fun Modifier.liquidGlassEdge(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    if (!liquidGlassEdgeSupported()) return gradientRim(theme, shape)
    val dark = theme.isDark
    val light = LocalGlassLight.current
    val darkness = LocalGlassDarkness.current
    return drawWithCache {
        val radius = when (val outline = shape.createOutline(size, layoutDirection, this)) {
            is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
            is Outline.Rectangle -> 0f
            else -> size.minDimension / 2f
        }
        onDrawWithContent {
            drawLiquidGlassEdge(radius, darkness?.value ?: if (dark) 1f else 0f, light?.value ?: DefaultGlassLight)
            drawContent()
        }
    }
}

/**
 * The selected tab in Liquid Glass (21 Nov): a small droplet of brighter glass that magnifies what passes under it,
 * as iOS shows the selected tab. Without a lens (Android 12 and older, or the glass effect off) it is the frosted
 * accent film with the lit edge. Clear Glass's droplet is iOS's: near-white glass by day, dark grey glass at night.
 */
@Composable
fun Modifier.liquidGlassDroplet(shape: Shape): Modifier {
    val theme = LocalGlassTheme.current
    val lens = LocalLensGlass.current
    val ink = theme.glassInk ?: GlassInk.LIQUID
    if (lens != null && theme.isLiquidGlass && lensGlassActive()) {
        val film = theme.accentSurface.compositeOver(theme.glassFallback)
        val clear = theme.isClearGlass
        return clip(shape).then(
            lens.lens(
                shape = shape,
                tint = film,
                active = true,
                liquid = LiquidGlassOptics(
                    dark = theme.isDark,
                    backdrop = LocalGlassBackdrop.current ?: theme.wallpaper.first(),
                    fallback = film,
                    inkFloor = ink.selectedDayFloor,
                    inkCeiling = ink.selectedNightCeiling,
                    frost = if (!clear) 0f else if (theme.isDark) LiquidGlassInk.SELECTED_FROST_NIGHT else LiquidGlassInk.SELECTED_FROST_DAY,
                    frostColor = LiquidGlassInk.selectedFrostColor(theme.isDark),
                    light = LocalGlassLight.current,
                    darkness = LocalGlassDarkness.current,
                ),
            ),
        )
    }
    return clip(shape).background(theme.accentSurface).frostedEdge(shape)
}

/**
 * Clear Glass: bars that sit together change over together (21 Nov, Deepak's recordings: one pill light and its
 * neighbour dark). Each bar in the group reports how bright the photos behind it are; the group decides once, from
 * their average, and every bar and the fade under them follow its [darkness].
 */
@Stable
class AdaptiveGlassGroup internal constructor(private val home: Boolean) {
    private val lumas = HashMap<Any, Float>()

    /** True while the group's glass is dark. Starts as the page is (day: light, night: dark). */
    var night by mutableStateOf(home)
        private set

    /** 0 light glass .. 1 dark glass, moving smoothly when the group changes over. Read while drawing. */
    lateinit var darkness: State<Float>
        internal set

    internal fun report(member: Any, luma: Float) {
        lumas[member] = luma
        night = adaptNight(night, lumas.values.sum() / lumas.size, home)
    }

    internal fun leave(member: Any) {
        lumas.remove(member)
    }
}

/** The group the bars below belong to; null outside Clear Glass (each [AdaptiveGlass] then decides for itself). */
val LocalAdaptiveGlassGroup = staticCompositionLocalOf<AdaptiveGlassGroup?> { null }

/**
 * A group for [LocalAdaptiveGlassGroup], or null when the theme isn't Clear Glass or the glass effect is off. A new one
 * for each theme, so changing between day and night starts from the page's own look.
 */
@Composable
fun rememberAdaptiveGlassGroup(): AdaptiveGlassGroup? {
    val page = LocalGlassTheme.current
    val settings = LocalGlassSettings.current
    if (!page.isClearGlass || !settings.blurEnabled) return null
    val group = remember(page.id) { AdaptiveGlassGroup(page.isDark) }
    group.darkness = animateFloatAsState(
        if (group.night) 1f else 0f,
        if (settings.reduceMotion) snap() else tween(ADAPT_FLIP_MS),
        label = "glass flip",
    )
    return group
}

/**
 * Whether glass should be dark now, given how bright the photos behind it are ([luma], linear, 0 to 1). Glass keeps
 * the page's own look ([home]: true on a night page) unless the photos are clearly the other way, and there is a gap
 * before it changes back, so it never flickers over mid-grey photos (21 Nov: at night the bar turned white over grey
 * album cards).
 */
internal fun adaptNight(night: Boolean, luma: Float, home: Boolean): Boolean = when {
    home -> if (night) luma < NIGHT_PAGE_TO_LIGHT else luma < NIGHT_PAGE_BACK_TO_DARK
    else -> if (night) luma < DAY_PAGE_BACK_TO_LIGHT else luma < DAY_PAGE_TO_DARK
}

/** A day page: light glass turns dark below the first (clearly dark photos) and light again above the second. */
internal const val DAY_PAGE_TO_DARK = 0.09f
internal const val DAY_PAGE_BACK_TO_LIGHT = 0.17f

/** A night page: dark glass turns light above the first (clearly light photos) and dark again below the second. */
internal const val NIGHT_PAGE_TO_LIGHT = 0.42f
internal const val NIGHT_PAGE_BACK_TO_DARK = 0.30f

/**
 * Clear Glass (21 Nov, like iOS): a bar turns dark over dark photos and light over light ones, words and all. It looks
 * at the photos behind it through the screen's lens recording, a few times a second while they move, and changes over
 * smoothly. The caller puts the given Modifier on the bar itself (it learns where the bar is from it); nothing else is
 * laid out. Other themes, Android 12 and older, or the glass effect off: the content as it is.
 *
 * [grouped]: join the [LocalAdaptiveGlassGroup] around it, if there is one, and change over with the other bars there.
 * A menu or anything else that floats on its own decides for itself.
 */
@Composable
fun AdaptiveGlass(grouped: Boolean = true, content: @Composable (Modifier) -> Unit) {
    val page = LocalGlassTheme.current
    val settings = LocalGlassSettings.current
    val lens = LocalLensGlass.current
    if (!page.isClearGlass || lens == null || !settings.blurEnabled) {
        content(Modifier)
        return
    }
    val shared = if (grouped) LocalAdaptiveGlassGroup.current else null
    val group = shared ?: rememberAdaptiveGlassGroup()
    if (group == null) {
        content(Modifier)
        return
    }
    val family = remember(page.id) { GlassThemes.familyOf(page.id.name) }
    val backdrop = page.wallpaper.first()
    val member = remember { Any() }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val luma = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(group, member) { onDispose { group.leave(member) } }
    LaunchedEffect(lens, group) {
        var seenVersion = -1L
        var seenBounds: Rect? = null
        var quietTicks = 0
        while (true) {
            val b = bounds
            if (b == null) {
                // Off the screen (tucked away, hidden, or on a page that isn't showing): no say in the group.
                group.leave(member)
                seenBounds = null
            } else {
                val changed = lens.version != seenVersion || b != seenBounds
                // Once more a little after everything has stopped moving, from a fresh copy: the last frames of a
                // scroll or a page change are the ones that count.
                quietTicks = if (changed) 0 else quietTicks + 1
                val settle = quietTicks == ADAPT_SETTLE_TICKS || quietTicks == ADAPT_SETTLE_TICKS * 3
                if (changed || settle) {
                    val l = lens.brightnessBehind(b, backdrop, fresh = settle)
                    // The answer may be a moment old (see measuredVersion): until it has caught up, look again.
                    seenVersion = lens.measuredVersion
                    seenBounds = b
                    if (l != null) {
                        luma.value = l
                        group.report(member, l)
                    }
                }
            }
            delay(ADAPT_EVERY_MS)
        }
    }
    val darkness = group.darkness
    val t = darkness.value
    val shown = when {
        t >= 0.999f -> family.night
        t <= 0.001f -> family.day
        else -> lerpGlassTheme(family.day, family.night, t)
    }
    CompositionLocalProvider(
        LocalGlassTheme provides shown,
        LocalGlassDarkness provides darkness,
        LocalBackdropLuma provides luma,
        LocalGlassBackdrop provides backdrop,
    ) {
        content(
            Modifier.onGloballyPositioned {
                // Only the part that is on the screen counts; none of it: null.
                val root = it.findRootCoordinates().size
                val seen = it.boundsInRoot().intersect(Rect(0f, 0f, root.width.toFloat(), root.height.toFloat()))
                bounds = if (seen.width >= 1f && seen.height >= 1f) seen else null
            },
        )
    }
}

/** How often a bar looks behind itself while the photos move, and how long it takes to change over. */
private const val ADAPT_EVERY_MS = 250L
private const val ADAPT_FLIP_MS = 320

/** This many quiet looks after the photos stop (and three times as many), a bar looks once more from a fresh copy. */
private const val ADAPT_SETTLE_TICKS = 2

/** Halfway between a theme's day and night versions, for a bar changing over (only the colours words and glass use). */
internal fun lerpGlassTheme(day: GlassTheme, night: GlassTheme, t: Float): GlassTheme {
    val base = if (t < 0.5f) day else night
    fun mix(a: Color?, b: Color?): Color? = if (a != null && b != null) lerp(a, b, t) else if (t < 0.5f) a else b
    return base.copy(
        onGlass = lerp(day.onGlass, night.onGlass, t),
        onGlassSecondary = lerp(day.onGlassSecondary, night.onGlassSecondary, t),
        onWallpaper = lerp(day.onWallpaper, night.onWallpaper, t),
        accent = lerp(day.accent, night.accent, t),
        onAccent = lerp(day.onAccent, night.onAccent, t),
        glassTint = lerp(day.glassTint, night.glassTint, t),
        glassFallback = lerp(day.glassFallback, night.glassFallback, t),
        accentFill = mix(day.accentFill, night.accentFill),
        panelFill = mix(day.panelFill, night.panelFill),
    )
}

/** Reuse the host button's interactions, so the highlight needs no competing gesture detector. */
@Composable
fun Modifier.liquidGlassTouch(source: InteractionSource): Modifier {
    val settings = LocalGlassSettings.current
    if (!LocalGlassTheme.current.isLiquidGlass || !settings.blurEnabled || settings.reduceMotion) return this

    var press by remember(source) { mutableStateOf<PressInteraction.Press?>(null) }
    var point by remember(source) { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(source) {
        source.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    point = event.pressPosition
                    press = event
                }
                is PressInteraction.Release -> if (press == event.press) press = null
                is PressInteraction.Cancel -> if (press == event.press) press = null
                else -> Unit
            }
        }
    }
    val alpha = animateFloatAsState(
        targetValue = if (press == null) 0f else 0.30f,
        animationSpec = tween(if (press == null) 220 else 90),
        label = "glass reflection",
    )
    return drawWithContent {
        if (alpha.value > 0f && size.minDimension > 0f) {
            val center = if (point == Offset.Unspecified) this.center else point
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                    center = center,
                    radius = size.minDimension,
                ),
                alpha = alpha.value,
            )
        }
        drawContent()
    }
}

/**
 * Clear Glass with Apple's motion (21 Nov, Settings > About > Test: iOS motion): a press makes the control swell a
 * little and settle with a bounce, as iOS's glass does. Nothing in other themes, with the test off, or Reduce motion.
 */
@Composable
fun Modifier.iosSwell(source: InteractionSource): Modifier {
    val theme = LocalGlassTheme.current
    val settings = LocalGlassSettings.current
    if (!theme.isClearGlass || !settings.iosMotion || settings.reduceMotion) return this
    return pressDip(source, to = IOS_SWELL)
}

/** How much a pressed control grows with Apple's motion. */
internal const val IOS_SWELL = 1.08f
