package com.deepak.photovault.ui.shell

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.DefaultGlassLight
import com.deepak.photovault.designsystem.LensGlass
import com.deepak.photovault.designsystem.LiquidGlassAgsl
import com.deepak.photovault.designsystem.LiquidGlassOptics
import kotlin.math.pow

@Composable
actual fun rememberLensGlass(): LensGlass? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { AndroidLensGlass(layer) }
}

actual fun lensGlassSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * The scrolling content is drawn into [sourceLayer] as well as on screen. The bar draws that layer behind itself,
 * shifted so it lines up, through an AGSL shader that bends the picture near the bar's edge, like looking through the
 * edge of a thick piece of glass (the other themes' lens adds a small blur; Liquid Glass none, see LiquidGlassAgsl).
 * Only for Android 13 and newer (AGSL).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AndroidLensGlass(private val sourceLayer: GraphicsLayer) : LensGlass {
    private var sourceOrigin by mutableStateOf<Offset?>(null)

    override fun source(active: Boolean, background: Color): Modifier = Modifier
        .onGloballyPositioned { sourceOrigin = it.positionInRoot() }
        .drawWithContent {
            if (active && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                sourceLayer.record {
                    if (background.isSpecified) drawRect(background)
                    this@drawWithContent.drawContent()
                }
                drawLayer(sourceLayer)
            } else {
                drawContent()
            }
        }

    // ---- The lens ----

    override val drawLens: @Composable (Shape, Color, Boolean, LiquidGlassOptics?) -> Modifier =
        { shape, tint, active, liquid -> lensModifier(shape, tint, active, liquid) }

    @Composable
    private fun lensModifier(shape: Shape, tint: Color, active: Boolean, liquid: LiquidGlassOptics?): Modifier {
        val effectLayer = rememberGraphicsLayer()
        val shader = remember(active, liquid != null) {
            if (!active) null else try {
                RuntimeShader(if (liquid == null) LENS_SHADER else LiquidGlassAgsl.LENS)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        var barOrigin by remember { mutableStateOf<Offset?>(null) }
        val applied = remember(shader, liquid) { FloatArray(APPLIED_COUNT) { Float.NaN } }
        return Modifier
            .onGloballyPositioned { barOrigin = it.positionInRoot() }
            .drawWithContent {
                if (!active) {
                    drawContent()
                    return@drawWithContent
                }

                val source = sourceOrigin
                val bar = barOrigin
                if (shader == null || source == null || bar == null || size.minDimension <= 1f ||
                    !drawContext.canvas.nativeCanvas.isHardwareAccelerated
                ) {
                    drawRect(liquid?.fallback ?: tint)
                    drawContent()
                    return@drawWithContent
                }

                val outline = shape.createOutline(size, layoutDirection, this)
                val radius = if (outline is Outline.Rounded) outline.roundRect.topLeftCornerRadius.x else 0f
                // Liquid Glass is clear (21 Nov): no blur at all, only the bend and the light at its edge.
                val blurPx = if (liquid == null) 6f else 0f
                val dpPx = 1.dp.toPx()
                // Clear Glass: the light follows the phone's tilt, and a bar changing over goes smoothly from light to dark.
                val light = liquid?.light?.value ?: DefaultGlassLight
                val darkness = liquid?.darkness?.value ?: if (liquid?.dark == true) 1f else 0f
                val now = floatArrayOf(size.width, size.height, radius, blurPx, dpPx, light.x, light.y, darkness)
                if (!now.contentEquals(applied)) {
                    shader.setFloatUniform("size", size.width, size.height)
                    shader.setFloatUniform("radius", radius)
                    if (liquid == null) {
                        shader.setFloatUniform("bevel", minOf(size.height * 0.5f, 36.dp.toPx()))
                    } else {
                        shader.setFloatUniform("dp", dpPx)
                        shader.setFloatUniform("darkMode", darkness)
                        shader.setFloatUniform("lightDir", light.x, light.y)
                        shader.setFloatUniform(
                            "backdrop",
                            liquid.backdrop.red, liquid.backdrop.green, liquid.backdrop.blue,
                        )
                        shader.setFloatUniform("inkFloor", liquid.inkFloor)
                        shader.setFloatUniform("inkCeiling", liquid.inkCeiling)
                        shader.setFloatUniform("frost", liquid.frost)
                        shader.setFloatUniform(
                            "frostColor",
                            LINEAR[(liquid.frostColor.red * 255f + 0.5f).toInt().coerceIn(0, 255)].toFloat(),
                            LINEAR[(liquid.frostColor.green * 255f + 0.5f).toInt().coerceIn(0, 255)].toFloat(),
                            LINEAR[(liquid.frostColor.blue * 255f + 0.5f).toInt().coerceIn(0, 255)].toFloat(),
                        )
                    }
                    val refraction = RenderEffect.createRuntimeShaderEffect(shader, "content")
                    effectLayer.renderEffect = if (blurPx > 0f) {
                        RenderEffect.createChainEffect(
                            refraction,
                            RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP),
                        ).asComposeRenderEffect()
                    } else {
                        refraction.asComposeRenderEffect()
                    }
                    now.copyInto(applied)
                }
                effectLayer.record {
                    translate(source.x - bar.x, source.y - bar.y) { drawLayer(sourceLayer) }
                }
                drawLayer(effectLayer)
                if (liquid == null) drawRect(tint)
                drawContent()
            }
    }
}

/** What the lens last told the shader: size (2), radius, blur, dp, light (2) and darkness. */
private const val APPLIED_COUNT = 8

/** sRGB (0 to 255) to linear light, for the frost colour. */
private val LINEAR = DoubleArray(256) { i ->
    val v = i / 255.0
    if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/**
 * Near the edge of the rounded bar the picture behind is taken from further in (it looks magnified and bent, like the
 * thick edge of a lens); the whole lens also magnifies a little. Inside the bevel the picture is only slightly zoomed.
 */
private const val LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bevel;

float sdRound(float2 p, float2 hs, float r) {
    float2 q = abs(p) - hs + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 fragCoord) {
    float2 hs = size * 0.5;
    float2 p = fragCoord - hs;
    float d = sdRound(p, hs, radius);
    float depth = clamp(-d, 0.0, bevel);
    float t = 1.0 - depth / bevel;
    float2 g = float2(
        sdRound(p + float2(1.0, 0.0), hs, radius) - sdRound(p - float2(1.0, 0.0), hs, radius),
        sdRound(p + float2(0.0, 1.0), hs, radius) - sdRound(p - float2(0.0, 1.0), hs, radius));
    float2 n = g / max(length(g), 0.0001);
    float bend = t * t * bevel * 0.9;
    float2 samplePos = fragCoord - n * bend;
    samplePos = hs + (samplePos - hs) * 0.94;
    return content.eval(samplePos);
}
"""
