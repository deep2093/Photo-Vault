package com.deepak.photovault

import com.deepak.photovault.ui.shell.AppIconChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every icon in Settings has a launcher entry and artwork, and only the default is on out of the box. */
class LauncherIconManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun alias(name: String): String {
        val start = manifest.indexOf("android:name=\".$name\"")
        assertTrue("missing alias $name", start >= 0)
        return manifest.substring(start, manifest.indexOf("</activity-alias>", start))
    }

    @Test
    fun everyChoiceHasAnAliasAndIcon() {
        AppIconChoice.all.forEach { choice ->
            val entry = alias(choice.aliasName)
            assertTrue(entry.contains("@mipmap/${choice.resourceName}\""))
            assertTrue(File("src/main/res/mipmap-anydpi-v26/${choice.resourceName}.xml").exists())
            val glyph = choice.glyph.name.lowercase()
            assertTrue(File("src/main/res/drawable-nodpi/ic_${glyph}_foreground.png").exists())
            assertTrue(File("src/main/res/drawable-nodpi/ic_${glyph}_monochrome.png").exists())
        }
    }

    @Test
    fun onlyTheDefaultIsEnabledAndTheActivityHasNoLauncherOfItsOwn() {
        val enabled = AppIconChoice.all.filter { alias(it.aliasName).contains("android:enabled=\"true\"") }
        assertEquals(listOf(AppIconChoice()), enabled)
        assertEquals(AppIconChoice.all.size, Regex("android.intent.category.LAUNCHER").findAll(manifest).count())
    }
}
