package com.deepak.photovault.core.backup

import java.security.MessageDigest

actual fun newSha256(): Hasher = object : Hasher {
    private val digest = MessageDigest.getInstance("SHA-256")

    override fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)

    override fun hex(): String = digest.digest().joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
}
