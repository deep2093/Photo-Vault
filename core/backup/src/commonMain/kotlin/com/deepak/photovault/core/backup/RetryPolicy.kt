package com.deepak.photovault.core.backup

/**
 * What happens after a backup (decided 24 Oct): a scheduled backup that fails tries once more an hour later;
 * if that fails too, the person gets a notification saying why. "Back up now" shows its result on screen.
 * A backup the person stopped is never retried.
 */
object RetryPolicy {
    const val RETRY_DELAY_MILLIS = 60 * 60_000L

    /** Waiting for another family phone to finish (24 Oct): try again after this, quietly. */
    const val TURN_DELAY_MILLIS = 30 * 60_000L

    enum class Action { NOTHING, RETRY_IN_AN_HOUR, NOTIFY, TRY_AFTER_TURN }

    fun after(run: BackupRun): Action = when {
        run.succeeded || run.failure == FailureReason.STOPPED -> Action.NOTHING
        // Another phone had its turn: not a failure, so no notification and the one-hour rule isn't used up.
        run.failure == FailureReason.BUSY && run.trigger != BackupTrigger.MANUAL -> Action.TRY_AFTER_TURN
        // Waiting an hour changes nothing here: the person has to act, so they are told at once.
        run.failure == FailureReason.PRIVACY_NOT_CHOSEN && run.trigger == BackupTrigger.SCHEDULED -> Action.NOTIFY
        run.failure == FailureReason.NO_PRIVATE_KEY && run.trigger == BackupTrigger.SCHEDULED -> Action.NOTIFY
        run.trigger == BackupTrigger.SCHEDULED -> Action.RETRY_IN_AN_HOUR
        run.trigger == BackupTrigger.RETRY -> Action.NOTIFY
        else -> Action.NOTHING
    }
}
