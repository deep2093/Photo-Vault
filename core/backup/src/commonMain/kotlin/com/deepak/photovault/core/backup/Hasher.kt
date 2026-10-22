package com.deepak.photovault.core.backup

/** SHA-256, fed chunk by chunk while a file streams. Android uses the system's own implementation. */
interface Hasher {
    fun update(bytes: ByteArray, offset: Int, length: Int)

    /** The finished checksum as lowercase hex. Call once. */
    fun hex(): String
}

expect fun newSha256(): Hasher
