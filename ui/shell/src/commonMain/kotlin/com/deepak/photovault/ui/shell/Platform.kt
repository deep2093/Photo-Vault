package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext

/**
 * The few pieces that differ per platform. Android implements them in androidMain; the paused PC app will
 * add its own when it resumes.
 */

/**
 * What the viewer's own video controls (24 Oct, Samsung-style) read and change: play/pause, mute, where it is,
 * and whether the first picture has been drawn (until then the still is shown, so nothing flashes).
 */
@androidx.compose.runtime.Stable
class VideoControls {
    var playing by androidx.compose.runtime.mutableStateOf(true)
    var muted by androidx.compose.runtime.mutableStateOf(false)
    var positionMs by androidx.compose.runtime.mutableLongStateOf(0L)
    var durationMs by androidx.compose.runtime.mutableLongStateOf(0L)
    var firstFrame by androidx.compose.runtime.mutableStateOf(false)

    /** Width / height of the video as shown (0 until the player knows), so the picture is laid out once. */
    var aspect by androidx.compose.runtime.mutableFloatStateOf(0f)

    /** A position asked for by the scrubber; the player takes it and clears it. */
    var seekRequest by androidx.compose.runtime.mutableStateOf<Long?>(null)

    fun seek(ms: Long) {
        val to = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        seekRequest = to
        positionMs = to
    }
}

/** Starts reading a video's strip frames ahead of time, so the frame strip is ready when it opens. */
@Composable
expect fun PreloadVideoFrames(uri: String, times: List<Long>, sizePx: Int)

/** Hides Android's status and navigation bars while [hidden] (the viewer, with its own buttons hidden). */
@Composable
expect fun SystemBarsHidden(hidden: Boolean)

/** Plays a video with no controls of its own; the viewer draws them. Released when it leaves the screen. */
@Composable
expect fun VideoPlayer(uri: String, controls: VideoControls, modifier: Modifier = Modifier)

/** One frame of a video for the scrubber, loaded like a thumbnail. */
data class VideoFrame(val uri: String, val timeMs: Long, val sizePx: Int)

/** The image loader, with the platform's fast thumbnail source for [com.deepak.photovault.core.media.Thumb]. */
expect fun createImageLoader(context: PlatformContext): ImageLoader

/** Main colour of a loaded thumbnail for adaptive themes (ARGB), or null if it can't be read. */
expect fun mainColorOf(image: Image): Int?

/**
 * Lens glass for the floating navigation bar (24 Oct, experimental): returns the platform's lens, or null where it
 * can't be drawn (Android 12 and older). The bar and the content behind it share it.
 */
@Composable
expect fun rememberLensGlass(): com.deepak.photovault.designsystem.LensGlass?

/** Whether this device can draw lens glass at all (Android 13 and newer), so Settings only offers it there. */
expect fun lensGlassSupported(): Boolean
