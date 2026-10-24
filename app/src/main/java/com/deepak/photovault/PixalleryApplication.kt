package com.deepak.photovault

import android.app.Application
import android.os.Build
import com.deepak.photovault.backup.BackupGraph
import java.io.File

/** Sets up backup when the app process starts (also when Android starts it just to run a scheduled backup). */
class PixalleryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashNote.install(this)
        BackupGraph.get(this).start()
    }
}

/**
 * If the app ever closes unexpectedly, what went wrong is written to a small file on the phone (24 Oct), so it
 * can be sent with Send feedback next time. Only the error and where in the app's code it happened: no photos,
 * names, passwords or drive details. Android still shows its own "keeps stopping" message.
 */
object CrashNote {
    fun file(app: Application) = File(app.filesDir, "last-crash.txt")

    private fun seen(app: Application) = File(app.filesDir, "last-crash.seen")

    /** A crash in the last 10 minutes that hasn't been shown yet: show it instead of the app. */
    fun shouldShow(app: Application): Boolean {
        val f = file(app)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 10 * 60_000L) return false
        return runCatching { seen(app).readText().toLong() }.getOrNull() != f.lastModified()
    }

    fun markSeen(app: Application) {
        runCatching { seen(app).writeText(file(app).lastModified().toString()) }
    }

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                file(app).writeText(
                    "Pixallery $version on Android ${Build.VERSION.RELEASE} (${Build.MODEL}), ${java.util.Date()}\n" +
                        error.stackTraceToString().take(12_000),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
