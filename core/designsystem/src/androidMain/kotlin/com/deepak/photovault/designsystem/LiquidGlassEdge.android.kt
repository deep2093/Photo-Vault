package com.deepak.photovault.designsystem

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope

internal actual fun liquidGlassEdgeSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && EdgeShader.ready()

internal actual fun DrawScope.drawLiquidGlassEdge(radius: Float, darkness: Float, light: Offset) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) EdgeShader.draw(this, radius, darkness, light)
}

/**
 * One edge shader for every panel and button (compiled once). Its sizes are set just before each one is drawn: the
 * drawing keeps the values it was drawn with, so sharing it is safe (all drawing happens on the main thread).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object EdgeShader {
    private val shader: RuntimeShader? by lazy {
        try {
            RuntimeShader(LiquidGlassAgsl.EDGE)
        } catch (_: RuntimeException) {
            null
        }
    }
    private val brush: ShaderBrush? by lazy { shader?.let { ShaderBrush(it) } }

    fun ready(): Boolean = shader != null

    fun draw(scope: DrawScope, radius: Float, darkness: Float, light: Offset) {
        val s = shader ?: return
        val b = brush ?: return
        if (scope.size.minDimension <= 1f) return
        s.setFloatUniform("size", scope.size.width, scope.size.height)
        s.setFloatUniform("radius", radius)
        s.setFloatUniform("dp", scope.density)
        s.setFloatUniform("darkMode", darkness)
        s.setFloatUniform("lightDir", light.x, light.y)
        scope.drawRect(b)
    }
}
