package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class IconGlyph(val label: String) { LEAF("Leaf"), PETALS("Petals") }

enum class IconBackground(val label: String) { LIGHT("Light"), DARK("Dark"), COLOURFUL("Colourful"), CLEAR("Clear") }

/** One of the app's eight launcher icons. Leaf on Light is the default. */
data class AppIconChoice(val glyph: IconGlyph = IconGlyph.LEAF, val background: IconBackground = IconBackground.LIGHT) {
    /** Matches the activity-alias names in AndroidManifest.xml, e.g. "IconLeafLight". */
    val aliasName: String
        get() = "Icon" + glyph.name.lowercase().replaceFirstChar { it.uppercase() } +
            background.name.lowercase().replaceFirstChar { it.uppercase() }

    companion object {
        val all: List<AppIconChoice> = IconGlyph.entries.flatMap { g -> IconBackground.entries.map { AppIconChoice(g, it) } }
    }
}

/** Changes the home screen icon. Android switches launcher aliases; other platforms may do nothing. */
interface AppIconSwitcher {
    fun current(): AppIconChoice
    fun set(choice: AppIconChoice)
}

object NoAppIconSwitcher : AppIconSwitcher {
    override fun current() = AppIconChoice()
    override fun set(choice: AppIconChoice) = Unit
}

/** The real launcher icon for [choice], as the home screen shows it. */
@Composable
expect fun AppIconPreview(choice: AppIconChoice, modifier: Modifier = Modifier)
