package com.deepak.photovault

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.deepak.photovault.ui.shell.AppIconChoice
import com.deepak.photovault.ui.shell.IconGlyph

/** The status-bar icon follows the chosen app icon (6 Nov): the leaf for Leaf, the four petals for Petals. */
object NotificationIcon {
    fun of(context: Context): Int = if (glyph(context) == IconGlyph.LEAF) R.drawable.ic_notification_leaf else R.drawable.ic_notification

    private fun glyph(context: Context): IconGlyph = runCatching {
        val pm = context.packageManager
        fun state(c: AppIconChoice) = pm.getComponentEnabledSetting(ComponentName(context, "com.deepak.photovault.${c.aliasName}"))
        // A choice made in Settings wins over the manifest default (as the launcher switcher does).
        (AppIconChoice.all.firstOrNull { it != AppIconChoice() && state(it) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED } ?: AppIconChoice()).glyph
    }.getOrDefault(AppIconChoice().glyph)
}
