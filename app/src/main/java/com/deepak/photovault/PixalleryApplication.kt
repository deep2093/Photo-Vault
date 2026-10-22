package com.deepak.photovault

import android.app.Application
import com.deepak.photovault.backup.BackupGraph

/** Sets up backup when the app process starts (also when Android starts it just to run a scheduled backup). */
class PixalleryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BackupGraph.get(this).start()
    }
}
