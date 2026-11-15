package com.deepak.photovault.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class PinEnvelopeTest {
    @Test
    fun theRightPinOpensItAndAWrongOneDoesNot() {
        val root = randomBytes(32)
        val sealed = PinEnvelope.wrap(root, "482913", rounds = 2_000)
        assertContentEquals(root, PinEnvelope.unwrap(sealed, "482913"))
        assertNull(PinEnvelope.unwrap(sealed, "482914"))
        // Bound to Google's context: it never opens as a family key.
        assertNull(PinEnvelope.unwrap(sealed, "482913", KeyContext("house", "me")))
        // A changed file doesn't open.
        val changed = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(PinEnvelope.unwrap(changed, "482913"))
    }
}
