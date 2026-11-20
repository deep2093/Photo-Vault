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
    /**
     * Something refused (a wrong PIN, 21 Nov, Deepak: "make it a bit stronger than the others so the user will know"):
     * two firm double knocks, stronger and longer than any other feedback in the app.
     */
    suspend fun wrong() {
        repeat(2) {
            feedback.performHapticFeedback(HapticFeedbackType.Reject)
            kotlinx.coroutines.delay(70)
            feedback.performHapticFeedback(HapticFeedbackType.LongPress)
            kotlinx.coroutines.delay(160)
        }
    }

    /** A button or row pressed. */
    fun tap() = feedback.performHapticFeedback(HapticFeedbackType.ContextClick)

    /** A switch turned on or off. */
    fun toggle(on: Boolean) = feedback.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** One step: a chip, a tab, a pinch step, a wheel notch. */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** A light tick while a slider moves (15 Nov). */
    fun slide() = feedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)

    /** A firmer click: a slider reaching 0 or its end, or being reset (15 Nov). */
    fun detent() = feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    /** Something long-pressed. */
    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}

/**
 * [onClick] with the light touch feedback every button gives (21 Nov, Deepak: "other parts of the app give haptic
 * feedback"): for rows, cards and pills that are tapped but aren't one of the shared buttons.
 */
@Composable
fun hapticClick(onClick: () -> Unit): () -> Unit {
    val haptics = rememberHaptics()
    return {
        haptics.tap()
        onClick()
    }
}
