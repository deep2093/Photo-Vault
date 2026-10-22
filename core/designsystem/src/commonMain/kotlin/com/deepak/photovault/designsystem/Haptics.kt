package com.deepak.photovault.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Small taps of the vibration motor across the app (24 Oct): buttons, switches, chips, the navigation bar, pinch
 * steps and wheels. Android plays them only when the phone's own "touch feedback" setting is on.
 */
@Stable
class Haptics(private val feedback: HapticFeedback) {
    /** A button or row pressed. */
    fun tap() = feedback.performHapticFeedback(HapticFeedbackType.ContextClick)

    /** A switch turned on or off. */
    fun toggle(on: Boolean) = feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** One step: a chip, a tab, a pinch step, a wheel notch. */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** Something long-pressed. */
    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
