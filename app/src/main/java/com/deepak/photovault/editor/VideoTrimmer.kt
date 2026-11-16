package com.deepak.photovault.editor

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import com.deepak.photovault.core.media.MediaItem
import com.deepak.photovault.ui.shell.EditSaved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Video trim and mute (Phase 11 part 4, 16 Nov). The chosen part of the video is copied into a new file as it is,
 * without re-making the picture: quick, no loss of quality, and HDR video stays HDR. A video can only start on one
 * of its "whole picture" frames (about one a second), so the copy starts at the nearest one at or before the chosen
 * start. Sound off leaves the sound out. The original is only ever read; the copy goes beside it.
 */
internal class VideoTrimmer(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    suspend fun save(item: MediaItem, startMs: Long, endMs: Long, mute: Boolean): EditSaved = withContext(Dispatchers.IO) {
        val from = Uri.parse(item.uri)
        val file = File(File(app.cacheDir, "edits").apply { mkdirs() }, "trim-${System.nanoTime()}.mp4")
        try {
            val made = copyPart(from, file, startMs * 1000, endMs * 1000, mute)
                ?: return@withContext EditSaved(false, "This video can't be trimmed here. The original is unchanged.")
            if (!intoGallery(file, item, made)) return@withContext EditSaved(false, "Couldn't save the copy. The original is unchanged.")
            EditSaved(true, if (mute && made.wholeLength) "Saved a copy without sound next to the original." else "Saved a trimmed copy next to the original.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            EditSaved(false, "Couldn't trim this video. The original is unchanged.")
        } finally {
            file.delete()
        }
    }

    /** What was written: the container, and whether the whole video was kept (only the sound taken out). */
    private class Made(val webm: Boolean, val wholeLength: Boolean)

    /** Copies the samples from [startUs] to [endUs] into [to]; null when the video's kind can't be copied this way. */
    private suspend fun copyPart(from: Uri, to: File, startUs: Long, endUs: Long, mute: Boolean): Made? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(app, from, null)
            // The picture and (unless muted) the sound; anything else in the file (captions, motion data) is left out.
            val wanted = (0 until extractor.trackCount).mapNotNull { i ->
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
                if (mime.startsWith("video/") || (!mute && mime.startsWith("audio/"))) i to format else null
            }
            val video = wanted.firstOrNull { (_, f) -> f.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") } ?: return null
            val webm = wanted.any { (_, f) -> (f.getString(MediaFormat.KEY_MIME) ?: "") in WEBM_KINDS }
            val mux = MediaMuxer(to.path, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val details = details(from)
            if (!webm) {
                mux.setOrientationHint(details.rotation)
                details.place?.let { (lat, lon) -> runCatching { mux.setLocation(lat, lon) } }
            }
            val into = wanted.associate { (i, f) -> i to mux.addTrack(f) }

            // Where the copy really starts: the picture's whole frame at or before the chosen start.
            extractor.selectTrack(video.first)
            extractor.seekTo(startUs.coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val firstUs = extractor.sampleTime.coerceAtLeast(0)
            wanted.forEach { (i, _) -> if (i != video.first) extractor.selectTrack(i) }
            extractor.seekTo(firstUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val biggest = wanted.maxOf { (_, f) -> if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 }
            val buffer = ByteBuffer.allocate(maxOf(biggest, MIN_BUFFER))
            val info = MediaCodec.BufferInfo()
            val finished = HashSet<Int>()
            mux.start()
            var written = 0
            while (finished.size < wanted.size) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val time = extractor.sampleTime
                when {
                    track in finished -> Unit
                    time > endUs -> finished += track
                    time >= firstUs -> {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size > 0) {
                            info.set(0, size, time - firstUs, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            mux.writeSampleData(into.getValue(track), buffer, info)
                            written++
                        }
                    }
                }
                if (!extractor.advance()) break
            }
            mux.stop()
            if (written == 0) return null
            val whole = firstUs <= 0 && details.durationUs in 1..endUs
            return Made(webm, whole)
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private class Details(val rotation: Int, val place: Pair<Float, Float>?, val durationUs: Long)

    /** Which way up the video is, where it was filmed and how long it is, from the original. */
    private fun details(from: Uri): Details {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(app, from)
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            // "+37.8136+144.9631/" style.
            val place = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let { text ->
                Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(text)?.let { m ->
                    val lat = m.groupValues[1].toFloatOrNull()
                    val lon = m.groupValues[2].toFloatOrNull()
                    if (lat != null && lon != null && lat in -90f..90f && lon in -180f..180f) lat to lon else null
                }
            }
            Details(((rotation % 360) + 360) % 360, place, duration)
        } catch (e: Exception) {
            Details(0, null, 0L)
        } finally {
            runCatching { r.release() }
        }
    }

    /** Into the original's own folder (so it sits beside it), else Movies > Pixallery. */
    private fun intoGallery(file: File, item: MediaItem, made: Made): Boolean {
        val name = item.displayName.substringBeforeLast('.').ifBlank { "Video" }.removeSuffix("_trimmed") + "_trimmed" + if (made.webm) ".webm" else ".mp4"
        val folders = listOfNotNull(item.folder?.trim('/')?.takeIf { it.isNotEmpty() }, "Movies/Pixallery").distinct()
        return com.deepak.photovault.GallerySave.save(
            resolver, com.deepak.photovault.GallerySave.videos, name, if (made.webm) "video/webm" else "video/mp4", folders, item.takenAtMillis,
        ) { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private companion object {
        const val MIN_BUFFER = 8 * 1024 * 1024
        val WEBM_KINDS = setOf(MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS)
    }
}
