package com.deepak.photovault.ui.shell

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.toBitmap
import com.deepak.photovault.core.media.MainColor
import com.deepak.photovault.core.media.Thumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun VideoPlayer(uri: String, controls: VideoControls, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            prepare()
            playWhenReady = controls.playing
        }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() {
                controls.firstFrame = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) controls.playing = false
                if (player.duration > 0) controls.durationMs = player.duration
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Viewer -> player.
    LaunchedEffect(player) {
        snapshotFlow { controls.playing }.collect { play ->
            if (play && player.playbackState == androidx.media3.common.Player.STATE_ENDED) player.seekTo(0)
            player.playWhenReady = play
        }
    }
    LaunchedEffect(player) { snapshotFlow { controls.muted }.collect { player.volume = if (it) 0f else 1f } }
    LaunchedEffect(player) {
        snapshotFlow { controls.seekRequest }.collect { ms ->
            if (ms != null) {
                player.seekTo(ms)
                controls.seekRequest = null
            }
        }
    }
    // Player -> viewer: where it is, ten times a second.
    LaunchedEffect(player) {
        while (true) {
            if (controls.seekRequest == null) controls.positionMs = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0) controls.durationMs = player.duration
            kotlinx.coroutines.delay(100)
        }
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                // The viewer draws its own controls (Samsung-style strip), and nothing black shows before the
                // first picture (24 Oct: the screen flashed for half a second).
                useController = false
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        modifier = modifier,
    )
}

actual fun createImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(ThumbFetcher.Factory(context.contentResolver))
            add(ThumbKeyer)
            add(VideoFrameFetcher.Factory(context))
            add(VideoFrameKeyer)
        }
        .build()

actual fun mainColorOf(image: Image): Int? = try {
    var bitmap = image.toBitmap()
    // Hardware bitmaps can't be read pixel by pixel; copy to a readable one first (tiny cost: it is a thumbnail).
    if (bitmap.config == Bitmap.Config.HARDWARE) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    val small = Bitmap.createScaledBitmap(bitmap, 6, 6, true)
    val pixels = IntArray(36)
    small.getPixels(pixels, 0, 6, 0, 0, 6, 6)
    MainColor.pick(pixels)
} catch (_: Exception) {
    null
}

/**
 * Thumbnails from Android's own thumbnail cache (ContentResolver.loadThumbnail): fast, shared with the
 * system gallery, and it works for videos too.
 */
private class ThumbFetcher(private val thumb: Thumb, private val resolver: ContentResolver) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val bitmap = resolver.loadThumbnail(Uri.parse(thumb.uri), Size(thumb.sizePx, thumb.sizePx), null)
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val resolver: ContentResolver) : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher = ThumbFetcher(data, resolver)
    }
}

/** A frame from a video at a given time, for the viewer's scrubber. */
private class VideoFrameFetcher(private val frame: VideoFrame, private val context: android.content.Context) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(frame.uri))
            val bitmap = retriever.getScaledFrameAtTime(
                frame.timeMs * 1000,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                frame.sizePx,
                frame.sizePx,
            ) ?: error("No frame")
            ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
        } finally {
            retriever.release()
        }
    }

    class Factory(private val context: android.content.Context) : Fetcher.Factory<VideoFrame> {
        override fun create(data: VideoFrame, options: Options, imageLoader: ImageLoader): Fetcher = VideoFrameFetcher(data, context.applicationContext)
    }
}

private object VideoFrameKeyer : Keyer<VideoFrame> {
    override fun key(data: VideoFrame, options: Options): String = "${data.uri}@${data.timeMs}#${data.sizePx}"
}

/** Lets Coil keep thumbnails in its memory cache, so scrolling back is instant. */
private object ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options): String = "${data.uri}#${data.sizePx}"
}

@Composable
actual fun AppIconPreview(choice: AppIconChoice, modifier: Modifier) {
    val context = LocalContext.current
    val name = choice.resourceName
    val bitmap = remember(name) {
        val id = context.resources.getIdentifier(name, "mipmap", context.packageName)
        if (id == 0) {
            null
        } else {
            context.getDrawable(id)?.let { drawable ->
                // Draw it inside the launcher's circular mask, the way most home screens show it.
                val size = 192
                val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(full)
                val inset = (size * 18f / 108f).toInt()
                drawable.setBounds(-inset, -inset, size + inset, size + inset)
                val path = android.graphics.Path().apply { addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW) }
                canvas.clipPath(path)
                drawable.draw(canvas)
                full.asImageBitmap()
            }
        }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(bitmap, contentDescription = null, modifier = modifier)
    }
}
