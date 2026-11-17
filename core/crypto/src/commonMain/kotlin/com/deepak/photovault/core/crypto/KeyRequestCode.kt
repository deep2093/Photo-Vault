package com.deepak.photovault.core.crypto

/**
 * The six-digit code for asking the recovery helper for a key back (18 Nov, A1). The asking phone makes a one-time
 * public key and leaves it on the family drive; both phones show this code, worked out from that key, so the helper
 * can check by talking to the person that the request really is theirs before handing anything over. Anyone else
 * who left a request would show a different code.
 */
object KeyRequestCode {
    fun of(requestPublic: ByteArray): String {
        val h = Hkdf.derive(requestPublic, "pixallery/key-request-code", 4)
        val n = ((h[0].toLong() and 0xFF) shl 24 or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)) % 1_000_000
        val digits = n.toString().padStart(6, '0')
        return digits.substring(0, 3) + " " + digits.substring(3)
    }
}
