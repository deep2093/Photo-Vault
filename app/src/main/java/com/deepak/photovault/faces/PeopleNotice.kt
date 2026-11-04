package com.deepak.photovault.faces

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.deepak.photovault.MainActivity
import com.deepak.photovault.R

/** "Same person?" questions waiting (7A-2): one quiet notification that opens People. */
object PeopleNotice {
    private const val CHANNEL = "people_questions"
    private const val ID = 61

    /** False when notifications aren't allowed (nothing is shown then). */
    fun show(context: Context, count: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "People: same person?", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            context,
            6,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PEOPLE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (count == 1) "1 person to check" else "$count people to check"
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Pixallery isn't sure if some faces are the same person. Tap to answer.")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(ID, n)
        return true
    }
}
