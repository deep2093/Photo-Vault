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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // Already turned for portrait videos (the decoder applies the rotation).
                if (videoSize.width > 0 && videoSize.height > 0) {
                    controls.aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
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
    // A TextureView, not a SurfaceView (23 Oct): a SurfaceView is a separate layer that can't fade or shrink, so
    // closing the viewer left the video standing solid over the page until it vanished. The viewer sizes this
    // box to the video's own shape, so nothing stretches and the picture doesn't move when playback starts.
    AndroidView(
        factory = { ctx -> android.view.TextureView(ctx).also { player.setVideoTextureView(it) } },
        modifier = modifier,
    )
}

@Composable
actual fun SystemBarsHidden(hidden: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(hidden) {
        val window = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val types = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) bars?.hide(types) else bars?.show(types)
        onDispose { bars?.show(types) }
    }
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

/**
 * Frames of the video being scrubbed (23 Oct). One open file per video instead of one per frame (opening the
 * file 60 times made the strip slow to fill), frames kept in a small cache, and filled in ahead of time
 * while the video plays so the strip is ready when it opens.
 */
private object VideoFrames {
    private val lock = kotlinx.coroutines.sync.Mutex()
    private var openUri: String? = null
    private var retriever: android.media.MediaMetadataRetriever? = null
    private var closer: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private val cache = object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun key(uri: String, timeMs: Long, sizePx: Int) = "$uri@$timeMs#$sizePx"

    suspend fun frame(context: android.content.Context, uri: String, timeMs: Long, sizePx: Int): Bitmap {
        cache.get(key(uri, timeMs, sizePx))?.let { return it }
        return lock.withLock {
            cache.get(key(uri, timeMs, sizePx))?.let { return@withLock it }
            if (openUri != uri) {
                retriever?.release()
                retriever = android.media.MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(uri)) }
                openUri = uri
            }
            val bitmap = retriever!!.getScaledFrameAtTime(
                timeMs * 1000,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                sizePx,
                sizePx,
            ) ?: error("No frame")
            cache.put(key(uri, timeMs, sizePx), bitmap)
            // Let go of the file once nobody has asked for a frame for a while.
            closer?.cancel()
            closer = scope.launch {
                kotlinx.coroutines.delay(30_000)
                lock.withLock {
                    retriever?.release()
                    retriever = null
                    openUri = null
                }
            }
            bitmap
        }
    }

    fun preload(context: android.content.Context, uri: String, times: List<Long>, sizePx: Int) = scope.launch {
        for (t in times) {
            runCatching { frame(context, uri, t, sizePx) }
            kotlinx.coroutines.yield()
        }
    }
}

@Composable
actual fun PreloadVideoFrames(uri: String, times: List<Long>, sizePx: Int) {
    val context = LocalContext.current.applicationContext
    DisposableEffect(uri, times, sizePx) {
        val job = VideoFrames.preload(context, uri, times, sizePx)
        onDispose { job.cancel() }
    }
}

/** A frame from a video at a given time, for the viewer's scrubber. */
private class VideoFrameFetcher(private val frame: VideoFrame, private val context: android.content.Context) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val bitmap = VideoFrames.frame(context, frame.uri, frame.timeMs, frame.sizePx)
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.MEMORY)
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
