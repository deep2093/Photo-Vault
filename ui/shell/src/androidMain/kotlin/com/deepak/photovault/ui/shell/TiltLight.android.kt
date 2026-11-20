package com.deepak.photovault.ui.shell

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import com.deepak.photovault.designsystem.DefaultGlassLight
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The light on Clear Glass follows the phone's tilt (21 Nov). The gravity sensor says which way is down; a slowly
 * following rest position is taken away, so only tilting moves the light, and holding the phone still at any angle lets
 * it drift back to the top left. Listens only while [enabled] (the app on screen, Clear Glass, motion allowed).
 */
@Composable
actual fun rememberTiltLight(enabled: Boolean): State<Offset>? {
    if (!enabled) return null
    val context = LocalContext.current
    val light = remember { mutableStateOf(DefaultGlassLight) }
    DisposableEffect(context) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensors == null || sensor == null) return@DisposableEffect onDispose { }
        var restX = Float.NaN
        var restY = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val g = sqrt(gx * gx + gy * gy + gz * gz)
                if (g < 1f) return
                val nx = gx / g
                val ny = gy / g
                if (restX.isNaN()) {
                    restX = nx
                    restY = ny
                }
                restX += (nx - restX) * REST_FOLLOW
                restY += (ny - restY) * REST_FOLLOW
                val dx = (nx - restX).coerceIn(-MAX_TILT, MAX_TILT)
                val dy = (ny - restY).coerceIn(-MAX_TILT, MAX_TILT)
                // Tilting the right side down slides the shine right; tilting the top back slides it up.
                val target = Offset(
                    (DefaultGlassLight.x - dx * TILT_GAIN).coerceIn(-1f, 0.6f),
                    (DefaultGlassLight.y + dy * TILT_GAIN).coerceIn(-1f, 0.2f),
                )
                val now = light.value
                val next = Offset(now.x + (target.x - now.x) * SMOOTHING, now.y + (target.y - now.y) * SMOOTHING)
                if (abs(next.x - now.x) + abs(next.y - now.y) > MIN_STEP) light.value = next
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors.unregisterListener(listener) }
    }
    return light
}

/** How fast the rest position follows the phone (per reading, about 16 a second): a couple of seconds. */
private const val REST_FOLLOW = 0.03f

/** How far tilting can move the light, and how much it moves for a tilt. */
private const val MAX_TILT = 0.35f
private const val TILT_GAIN = 1.8f

/** Smoothing of the light between readings, and the smallest change worth drawing again for. */
private const val SMOOTHING = 0.35f
private const val MIN_STEP = 0.004f
