package com.deepak.photovault

import android.content.Context

/** Settings > Notifications (8 Nov): which notifications Pixallery may show. Kept with the other app settings. */
object NotifyPrefs {
    const val BACKUP_PROBLEMS = "notify_backup_problems"
    const val PEOPLE = "notify_people"
    const val FAMILY = "notify_family"
    const val FEEDBACK = "notify_feedback"

    fun on(context: Context, key: String): Boolean =
        context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE).getBoolean(key, true)
}
