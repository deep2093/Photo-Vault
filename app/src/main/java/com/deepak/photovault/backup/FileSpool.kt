package com.deepak.photovault.backup

import android.content.Context
import com.deepak.photovault.core.backup.Spool
import com.deepak.photovault.core.drive.ByteSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * One decrypted file held in the app's private cache for a moment while a Private backup is made normal (16 Nov).
 * Only this app can read that folder; the file is deleted as soon as it has been written to the drive.
 */
internal class FileSpool(context: Context) : Spool {
    private val file = File.createTempFile("convert-", ".tmp", File(context.cacheDir, "convert").apply { mkdirs() })
    private val out = FileOutputStream(file)
    private var input: FileInputStream? = null

    override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)

    override fun source(): ByteSource {
        out.close()
        val stream = FileInputStream(file).also { input = it }
        return object : ByteSource {
            override fun read(buffer: ByteArray): Int = stream.read(buffer)
            override fun close() = stream.close()
        }
    }

    override fun close() {
        runCatching { out.close() }
        runCatching { input?.close() }
        file.delete()
    }
}
