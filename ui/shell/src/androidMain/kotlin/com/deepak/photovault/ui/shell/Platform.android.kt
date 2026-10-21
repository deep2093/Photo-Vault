package com.deepak.photovault.ui.shell

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
actual fun VideoPlayer(uri: String, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = modifier,
    )
}

actual fun createImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(ThumbFetcher.Factory(context.contentResolver))
            add(ThumbKeyer)
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

/** Lets Coil keep thumbnails in its memory cache, so scrolling back is instant. */
private object ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options): String = "${data.uri}#${data.sizePx}"
}
