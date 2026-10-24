package com.deepak.photovault.core.crypto

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel

/**
 * Photo and video encryption: Google Tink's streaming AEAD (AES-256-GCM, HKDF per stream, 1 MiB segments). Each
 * segment is authenticated, segments cannot be reordered, dropped or cut short without failing, and a viewer can
 * jump into the middle of a video without reading it all. Never held whole in memory.
 */
object ObjectCipher {
    const val SEGMENT_BYTES = 1 shl 20

    private fun stream(objectKey: ByteArray) = AesGcmHkdfStreaming(objectKey, "HmacSha256", 32, SEGMENT_BYTES, 0)

    /** Close the returned stream to finish; closing writes the last authenticated segment. */
    fun encrypt(sink: OutputStream, objectKey: ByteArray, aad: ByteArray): OutputStream =
        stream(objectKey).newEncryptingStream(sink, aad)

    /** Reads fail with an IOException on any change, wrong key or wrong [aad]. */
    fun decrypt(source: InputStream, objectKey: ByteArray, aad: ByteArray): InputStream =
        stream(objectKey).newDecryptingStream(source, aad)

    /** Random access for seeking inside a big video (Phase 5 playback). */
    fun decryptSeekable(channel: SeekableByteChannel, objectKey: ByteArray, aad: ByteArray): SeekableByteChannel =
        stream(objectKey).newSeekableDecryptingChannel(channel, aad)
}
