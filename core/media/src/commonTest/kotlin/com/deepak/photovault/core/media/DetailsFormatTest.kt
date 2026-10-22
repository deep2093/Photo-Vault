package com.deepak.photovault.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The details panel's labels match Samsung Gallery's style (feedback 22 Oct). */
class DetailsFormatTest {
    @Test
    fun cameraNameAvoidsRepeatingTheMake() {
        assertEquals("Galaxy S26 Ultra", DetailsFormat.camera("samsung", "Galaxy S26 Ultra"))
        assertEquals("Pixel 9", DetailsFormat.camera("Google", "Pixel 9"))
        assertEquals("Canon EOS R6", DetailsFormat.camera("Canon", "Canon EOS R6"))
        assertEquals("Sony ILCE-7M4", DetailsFormat.camera("sony", "ILCE-7M4"))
        assertNull(DetailsFormat.camera(null, " "))
    }

    @Test
    fun exposureLabels() {
        assertEquals("1/50 s", DetailsFormat.exposure(0.02))
        assertEquals("2 s", DetailsFormat.exposure(2.0))
        assertEquals("F1.4", DetailsFormat.aperture(1.4))
        assertEquals("0.0ev", DetailsFormat.exposureBias(0.0))
        assertEquals("+0.7ev", DetailsFormat.exposureBias(0.67))
        assertEquals("23mm", DetailsFormat.focal(23, 6.3))
        assertEquals("6.3mm", DetailsFormat.focal(null, 6.3))
    }

    @Test
    fun lensSizeAndMegapixels() {
        assertEquals("Wide-angle lens", DetailsFormat.lensKind(23))
        assertEquals("Ultra wide-angle lens", DetailsFormat.lensKind(13))
        assertEquals("Telephoto lens", DetailsFormat.lensKind(70))
        assertEquals("12MP", DetailsFormat.megapixels(3000, 4000))
        assertEquals("24MP", DetailsFormat.megapixels(4284, 5712))
        assertEquals("2.73 MB", DetailsFormat.size(2_730_000))
        assertEquals("2.88 MB", DetailsFormat.size(2_880_000))
    }

    @Test
    fun coordinatesReadNaturally() {
        assertEquals("37.76210° S, 144.81230° E", DetailsFormat.coordinates(-37.7621, 144.8123))
    }
}
