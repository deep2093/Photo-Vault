package com.deepak.photovault.core.backup

/**
 * What happens after a backup (decided 24 Oct): a scheduled backup that fails tries once more an hour later;
 * if that fails too, the person gets a notification saying why. "Back up now" shows its result on screen.
 * A backup the person stopped is never retried.
 */
object RetryPolicy {
    const val RETRY_DELAY_MILLIS = 60 * 60_000L

    enum class Action { NOTHING, RETRY_IN_AN_HOUR, NOTIFY }

    fun after(run: BackupRun): Action = when {
        run.succeeded || run.failure == FailureReason.STOPPED -> Action.NOTHING
        run.trigger == BackupTrigger.SCHEDULED -> Action.RETRY_IN_AN_HOUR
        run.trigger == BackupTrigger.RETRY -> Action.NOTIFY
        else -> Action.NOTHING
    }
}
