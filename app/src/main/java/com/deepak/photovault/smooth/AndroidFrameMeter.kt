package com.deepak.photovault.smooth

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import com.deepak.photovault.ui.shell.FrameCounts
import com.deepak.photovault.ui.shell.FrameMeter
import java.util.concurrent.atomic.AtomicInteger

/**
 * The smoothness meter's counting (Phase 12, 20 Nov): Android reports how long each frame of the window took, on a
 * thread of its own, and a frame counts as late when it took longer than the screen allowed for it (its deadline on
 * Android 12 and newer, one refresh of the screen before that).
 */
class AndroidFrameMeter(private val activity: Activity) : FrameMeter {
    private val frames = AtomicInteger()
    private val slow = AtomicInteger()
    private val frozen = AtomicInteger()
    private var thread: HandlerThread? = null

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        // The very first frame of a window is always slow (it builds everything); it says nothing about smoothness.
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
        val took = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val allowed = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else refreshNanos()
        frames.incrementAndGet()
        if (allowed > 0 && took > allowed) slow.incrementAndGet()
        if (took > FROZEN_NANOS) frozen.incrementAndGet()
    }

    override fun start() {
        if (thread != null) return
        val t = HandlerThread("pixallery-frame-meter").also { it.start() }
        thread = t
        runCatching { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(t.looper)) }
    }

    override fun stop() {
        val t = thread ?: return
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
        t.quitSafely()
        thread = null
    }

    override fun counts(): FrameCounts = FrameCounts(frames.get(), slow.get(), frozen.get())

    override fun reset() {
        frames.set(0)
        slow.set(0)
        frozen.set(0)
    }

    /** One refresh of the screen in nanoseconds (Android 11, which has no per-frame deadline). */
    private fun refreshNanos(): Long {
        val hz = runCatching { activity.display?.refreshRate }.getOrNull() ?: 60f
        return (1_000_000_000.0 / hz.coerceAtLeast(30f)).toLong()
    }

    private companion object {
        const val FROZEN_NANOS = 700_000_000L
    }
}
