package com.deepak.photovault.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepak.photovault.designsystem.LocalGlassTheme
import com.deepak.photovault.designsystem.solidGlass
import kotlinx.coroutines.delay

/*
 * The smoothness meter (Phase 12, 20 Nov; a test in Settings > About). Phase 12 is done when the main flows drop no
 * frames on a real phone, and this is how to check: a small counter at the top of the screen says how many of the
 * frames drawn since it was turned on (or last tapped) came late. The Android app counts them with the system's own
 * frame timings, so the counting adds nothing to the frames it measures.
 */

/** Frames counted since the meter started or was last reset. */
data class FrameCounts(
    /** Frames drawn. */
    val frames: Int = 0,
    /** Frames that missed the screen's deadline (a stutter you may see). */
    val slow: Int = 0,
    /** Frames that took longer than 0.7 seconds (the screen froze). */
    val frozen: Int = 0,
)

/** Counts drawn frames and late ones. Android: the window's frame timings; elsewhere there is none. */
interface FrameMeter {
    fun start()
    fun stop()
    fun counts(): FrameCounts
    fun reset()
}

/** The share of frames on time, as a whole percent (100 with nothing counted yet). */
internal fun smoothPercent(counts: FrameCounts): Int =
    if (counts.frames <= 0) 100 else ((counts.frames - counts.slow) * 100L / counts.frames).toInt()

/** The counter itself: a small glass pill; a tap starts counting again. */
@Composable
internal fun FrameMeterBadge(meter: FrameMeter, modifier: Modifier = Modifier) {
    DisposableEffect(meter) {
        meter.start()
        onDispose { meter.stop() }
    }
    var counts by remember { mutableStateOf(FrameCounts()) }
    LaunchedEffect(meter) {
        while (true) {
            delay(METER_REFRESH_MS)
            counts = meter.counts()
        }
    }
    val words = buildString {
        append("${smoothPercent(counts)}% smooth · ${counts.slow} late of ${counts.frames}")
        if (counts.frozen > 0) append(" · ${counts.frozen} frozen")
    }
    Text(
        words,
        color = LocalGlassTheme.current.onGlass,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .solidGlass(RoundedCornerShape(percent = 50))
            .clickable(role = Role.Button, onClickLabel = "Count again") {
                meter.reset()
                counts = meter.counts()
            }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** How often the counter on screen is brought up to date. */
private const val METER_REFRESH_MS = 500L
