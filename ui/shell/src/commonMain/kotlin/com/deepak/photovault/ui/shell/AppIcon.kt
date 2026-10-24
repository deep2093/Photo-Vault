package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The artwork. Four Seasons comes from the icon kit (22 Oct 2026); Leaf and Petals are the Phase 1 icons. Bloom was
 * removed on 24 Oct (its launcher entries remain, switched off, so a phone that used it moves to Four Seasons). [key] names the activity alias ("IconFourSeasonsLight") and the mipmap ("ic_four_seasons_light").
 */
enum class IconGlyph(val label: String, val key: String) {
    FOUR_SEASONS("Four Seasons", "FourSeasons"),
    LEAF("Leaf", "Leaf"),
    PETALS("Petals", "Petals"),
}

enum class IconBackground(val label: String) { LIGHT("Light"), DARK("Dark"), COLOURFUL("Colourful"), CLEAR("Clear") }

/** One of the app's twelve launcher icons. Leaf on Light is the default (23 Oct; was Four Seasons). */
data class AppIconChoice(val glyph: IconGlyph = IconGlyph.LEAF, val background: IconBackground = IconBackground.LIGHT) {
    /** Matches the activity-alias names in AndroidManifest.xml, e.g. "IconFourSeasonsLight". */
    val aliasName: String
        get() = "Icon" + glyph.key + background.name.lowercase().replaceFirstChar { it.uppercase() }

    /** The launcher icon resource, e.g. "ic_four_seasons_light". */
    val resourceName: String
        get() = "ic_${glyph.name.lowercase()}_${background.name.lowercase()}"

    companion object {
        val all: List<AppIconChoice> = IconGlyph.entries.flatMap { g -> IconBackground.entries.map { AppIconChoice(g, it) } }

        /** Launcher entries of removed icons, and the icon that replaces each one. */
        val retired: Map<String, AppIconChoice> = IconBackground.entries.associate { bg ->
            "IconBloom" + bg.name.lowercase().replaceFirstChar { it.uppercase() } to AppIconChoice(IconGlyph.FOUR_SEASONS, bg)
        }
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
