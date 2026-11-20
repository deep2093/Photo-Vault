package com.deepak.photovault.designsystem

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal actual fun screenCornerRadius(): Dp {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0.dp
    val px = bottomCorner(LocalView.current) ?: return 0.dp
    return with(LocalDensity.current) { px.toDp() }
}

/** The bottom corners' radius in pixels, as the screen reports it (Android 12+). */
@RequiresApi(Build.VERSION_CODES.S)
private fun bottomCorner(view: View): Int? =
    view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.takeIf { it > 0 }
