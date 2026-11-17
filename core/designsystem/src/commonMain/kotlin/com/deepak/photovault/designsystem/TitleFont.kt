package com.deepak.photovault.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * The magazine serif for titles and dates (18 Nov, Deepak: "I loved the magazine style font"): Libre Bodoni, bundled
 * by the app. Null where the platform has none (the PC app for now); then titles keep the normal font.
 */
val LocalTitleFont = staticCompositionLocalOf<FontFamily?> { null }

/**
 * [style] set in the title serif. Only for titles and dates (the floating date, Years and Months, album, Memories and
 * page titles); buttons, settings and running text keep the normal font.
 */
@Composable
fun titled(style: TextStyle): TextStyle = LocalTitleFont.current?.let { style.copy(fontFamily = it) } ?: style
