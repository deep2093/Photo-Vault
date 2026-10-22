package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The artwork. Four Seasons and Bloom come from the Veyra Photos icon kit (22 Oct 2026); Leaf and Petals are the
 * Phase 1 icons. [key] names the activity alias ("IconFourSeasonsLight") and the mipmap ("ic_four_seasons_light").
 */
enum class IconGlyph(val label: String, val key: String) {
    FOUR_SEASONS("Four Seasons", "FourSeasons"),
    BLOOM("Bloom", "Bloom"),
    LEAF("Leaf", "Leaf"),
    PETALS("Petals", "Petals"),
}

enum class IconBackground(val label: String) { LIGHT("Light"), DARK("Dark"), COLOURFUL("Colourful"), CLEAR("Clear") }

/** One of the app's sixteen launcher icons. Four Seasons on Light is the default. */
data class AppIconChoice(val glyph: IconGlyph = IconGlyph.FOUR_SEASONS, val background: IconBackground = IconBackground.LIGHT) {
    /** Matches the activity-alias names in AndroidManifest.xml, e.g. "IconFourSeasonsLight". */
    val aliasName: String
        get() = "Icon" + glyph.key + background.name.lowercase().replaceFirstChar { it.uppercase() }

    /** The launcher icon resource, e.g. "ic_four_seasons_light". */
    val resourceName: String
        get() = "ic_${glyph.name.lowercase()}_${background.name.lowercase()}"

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
