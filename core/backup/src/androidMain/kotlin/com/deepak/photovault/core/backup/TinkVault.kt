package com.deepak.photovault.core.backup

import com.deepak.photovault.core.crypto.KeyContext
import com.deepak.photovault.core.crypto.MemberKeys
import com.deepak.photovault.core.crypto.ObjectCipher
import com.deepak.photovault.core.crypto.RecordSeal
import com.deepak.photovault.core.crypto.randomBytes
import com.deepak.photovault.core.drive.ByteSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** [PrivateVault] with Google Tink streaming encryption (core:crypto). Nothing is held whole in memory. */
class TinkVault(private val keys: MemberKeys, private val context: KeyContext) : PrivateVault {
    override fun newObjectId(): String = randomBytes(16).joinToString("") { "%02x".format(it) }

    private fun tag(kind: ObjectKind, id: String) = "${kind.name}|$id"

    override fun encrypt(plain: ByteSource, kind: ObjectKind, objectId: String): ByteSource {
        val tag = tag(kind, objectId)
        return EncryptingSource(plain, keys.objectKey(tag), MemberKeys.objectAad(context, tag))
    }

    override suspend fun decrypt(
        kind: ObjectKind,
        objectId: String,
        readCipher: suspend ((ByteArray, Int) -> Unit) -> Unit,
        out: (ByteArray, Int) -> Unit,
    ) = coroutineScope {
        val tag = tag(kind, objectId)
        val pipe = ChunkPipe()
        // The checking runs on its own thread, fed by the drive read here; a full pipe makes the drive read wait.
        var callerFailure: Throwable? = null
        val consumer = async(Dispatchers.IO) {
            try {
                ObjectCipher.decrypt(pipe, keys.objectKey(tag), MemberKeys.objectAad(context, tag)).use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        try {
                            out(buffer, n)
                        } catch (e: Throwable) {
                            callerFailure = e
                            throw e
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                pipe.close()
                // A drive error shows up in the pipe as the cause; anything else is a failed check.
                throw callerFailure ?: (e.cause as? DriveReadFailure)?.original ?: ObjectAuthenticationFailed()
            }
        }
        try {
            readCipher { bytes, n -> pipe.write(bytes, n) }
            pipe.finish()
        } catch (e: Throwable) {
            pipe.fail(e)
            val checked = runCatching { consumer.await() }.exceptionOrNull()
            if (checked is ObjectAuthenticationFailed && e !is CancellationException && e !is IOException) throw checked
            throw e
        }
        consumer.await()
    }

    override fun sealRecord(plain: ByteArray, purpose: String): ByteArray =
        RecordSeal.seal(keys.recordKey(purpose), plain, recordAad(purpose))

    override fun openRecord(sealed: ByteArray, purpose: String): ByteArray? =
        RecordSeal.open(keys.recordKey(purpose), sealed, recordAad(purpose))

    private fun recordAad(purpose: String) = "pixallery/record|${context.householdId}|${context.memberId}|$purpose".encodeToByteArray()
}

/** Encrypts as it is read: the plain bytes go into Tink's stream and the encrypted bytes come out. */
private class EncryptingSource(private val plain: ByteSource, key: ByteArray, aad: ByteArray) : ByteSource {
    private val sink = ByteArrayOutputStream()
    private val encrypting = ObjectCipher.encrypt(sink, key, aad)
    private val chunk = ByteArray(64 * 1024)
    private var ready = ByteArray(0)
    private var at = 0
    private var finished = false

    override fun read(buffer: ByteArray): Int {
        while (at >= ready.size) {
            if (finished) return -1
            sink.reset()
            val n = plain.read(chunk)
            if (n < 0) {
                encrypting.close()
                finished = true
            } else {
                encrypting.write(chunk, 0, n)
            }
            ready = sink.toByteArray()
            at = 0
        }
        val n = minOf(buffer.size, ready.size - at)
        ready.copyInto(buffer, 0, at, at + n)
        at += n
        return n
    }

    override fun close() = plain.close()
}

private class DriveReadFailure(val original: Throwable) : IOException(original)

/** A small blocking hand-over between the drive reader and the checker, so a whole video is never in memory. */
private class ChunkPipe : InputStream() {
    private val queue = LinkedBlockingQueue<Any>(32)
    private var current = ByteArray(0)
    private var at = 0
    @Volatile private var closed = false
    private object End

    fun write(bytes: ByteArray, n: Int) {
        val copy = bytes.copyOf(n)
        while (!closed) if (queue.offer(copy, 200, TimeUnit.MILLISECONDS)) return
        throw IOException("The check stopped")
    }

    fun finish() { while (!closed) if (queue.offer(End, 200, TimeUnit.MILLISECONDS)) return }

    fun fail(e: Throwable) { while (!closed) if (queue.offer(DriveReadFailure(e), 200, TimeUnit.MILLISECONDS)) return }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (at >= current.size) {
            if (closed && queue.isEmpty()) return -1
            when (val next = queue.poll(200, TimeUnit.MILLISECONDS)) {
                null -> Unit
                End -> return -1
                is DriveReadFailure -> throw next
                is ByteArray -> { current = next; at = 0 }
            }
        }
        val n = minOf(len, current.size - at)
        current.copyInto(b, off, at, at + n)
        at += n
        return n
    }

    override fun close() { closed = true }
}
