package com.deepak.photovault.core.crypto

/**
 * The recovery code: 256 random bits shown as 24 simple words (24 x 11 bits = 264 = 256 bits + an 8-bit check).
 * A mistyped or swapped word is caught before anything is unwrapped.
 */
object RecoveryCode {
    const val WORD_COUNT = 24
    private const val BYTES = 32

    sealed interface Parsed {
        class Ok(val secret: ByteArray) : Parsed
        data class WrongCount(val found: Int) : Parsed
        /** [position] counts from 1. */
        data class UnknownWord(val position: Int, val word: String) : Parsed
        data object BadCheck : Parsed
    }

    fun generate(): ByteArray = randomBytes(BYTES)

    fun toWords(secret: ByteArray): List<String> {
        require(secret.size == BYTES) { "A recovery code is 32 bytes" }
        val bits = StringBuilder()
        for (b in secret) bits.append((b.toInt() and 0xFF).toString(2).padStart(8, '0'))
        bits.append((sha256(secret)[0].toInt() and 0xFF).toString(2).padStart(8, '0'))
        return (0 until WORD_COUNT).map { i -> WordList.words[bits.substring(i * 11, i * 11 + 11).toInt(2)] }
    }

    /** Accepts the words separated by spaces, commas or new lines, in any letter case. */
    fun parse(text: String): Parsed = parseWords(text.split(Regex("[\\s,]+")).filter { it.isNotBlank() })

    fun parseWords(words: List<String>): Parsed {
        if (words.size != WORD_COUNT) return Parsed.WrongCount(words.size)
        val bits = StringBuilder()
        words.forEachIndexed { i, w ->
            val index = WordList.indexOf(w) ?: return Parsed.UnknownWord(i + 1, w)
            bits.append(index.toString(2).padStart(11, '0'))
        }
        val secret = ByteArray(BYTES) { i -> bits.substring(i * 8, i * 8 + 8).toInt(2).toByte() }
        val check = bits.substring(BYTES * 8, BYTES * 8 + 8).toInt(2)
        return if (check == (sha256(secret)[0].toInt() and 0xFF)) Parsed.Ok(secret) else Parsed.BadCheck
    }
}
