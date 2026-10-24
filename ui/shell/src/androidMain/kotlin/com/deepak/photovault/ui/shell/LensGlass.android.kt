package com.deepak.photovault.ui.shell

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.LensGlass

@Composable
actual fun rememberLensGlass(): LensGlass? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { AndroidLensGlass(layer) }
}

actual fun lensGlassSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * The scrolling content is drawn into [sourceLayer] as well as on screen. The bar draws that layer behind itself,
 * shifted so it lines up, through a small blur and an AGSL shader that bends the picture near the bar's edge, like
 * looking through the edge of a thick piece of glass. Only for Android 13 and newer (AGSL).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AndroidLensGlass(private val sourceLayer: GraphicsLayer) : LensGlass {
    private var sourceOrigin = Offset.Zero

    override fun source(active: Boolean): Modifier = Modifier
        .onGloballyPositioned { sourceOrigin = it.positionInRoot() }
        .drawWithContent {
            // Always in the chain; only records while lens glass is on, so a runtime switch needs no re-attach.
            if (active) {
                sourceLayer.record { this@drawWithContent.drawContent() }
                drawLayer(sourceLayer)
            } else {
                drawContent()
            }
        }

    @Composable
    override fun lens(shape: Shape, tint: Color, active: Boolean): Modifier {
        val effectLayer = rememberGraphicsLayer()
        val shader = remember { RuntimeShader(LENS_SHADER) }
        val barOrigin = remember { FloatArray(2) }
        val applied = remember { FloatArray(3) { -1f } }
        return Modifier
            .onGloballyPositioned {
                val o = it.positionInRoot()
                barOrigin[0] = o.x
                barOrigin[1] = o.y
            }
            .drawWithContent {
                if (!active) {
                    drawContent()
                    return@drawWithContent
                }
                val outline = shape.createOutline(size, layoutDirection, this)
                val radius = if (outline is Outline.Rounded) outline.roundRect.topLeftCornerRadius.x else 0f
                // The effect is rebuilt only when the bar's size changes, not every frame.
                if (applied[0] != size.width || applied[1] != size.height || applied[2] != radius) {
                    shader.setFloatUniform("size", size.width, size.height)
                    shader.setFloatUniform("radius", radius)
                    shader.setFloatUniform("bevel", minOf(size.height * 0.5f, 36.dp.toPx()))
                    effectLayer.renderEffect = RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(shader, "content"),
                        RenderEffect.createBlurEffect(6f, 6f, Shader.TileMode.CLAMP),
                    ).asComposeRenderEffect()
                    applied[0] = size.width
                    applied[1] = size.height
                    applied[2] = radius
                }
                effectLayer.record {
                    translate(sourceOrigin.x - barOrigin[0], sourceOrigin.y - barOrigin[1]) { drawLayer(sourceLayer) }
                }
                drawLayer(effectLayer)
                drawRect(tint)
                drawContent()
            }
    }
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
