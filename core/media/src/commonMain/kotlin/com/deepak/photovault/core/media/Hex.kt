package com.deepak.photovault.core.media

/** Bytes as lower-case hex text ("0a ff" -> "0aff"), for checksums and file names made from them (shared 16 Nov). */
object Hex {
    fun of(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
