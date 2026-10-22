package com.deepak.photovault.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.deepak.photovault.core.backup.BackupRun
import com.deepak.photovault.core.backup.BackupSettings
import com.deepak.photovault.ui.shell.BackupPlatform
import com.deepak.photovault.ui.shell.BackupState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One backup state for the whole app process, shared by the screens and the background worker, so progress
 * shows live on the Backup screen.
 */
class BackupGraph private constructor(context: Context) {
    private val app = context.applicationContext
    val settings = BackupSettingsStore(app)
    private val history = BackupHistoryStore(app)
    private val index = FileBackupIndex(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val platform = object : BackupPlatform {
        override fun loadSettings(): BackupSettings = settings.load()

        override fun saveSettings(settings: BackupSettings) {
            this@BackupGraph.settings.save(settings)
            scope.launch { runCatching { BackupScheduler.reschedule(app, settings) } }
        }

        override fun loadHistory(): List<BackupRun> = history.load()

        override suspend fun backedUpKeys(): Set<String> = index.keys()

        override fun backUpNow() = BackupScheduler.backUpNow(app, settings.load())

        override fun stop() = runner.stop()

        override fun restoreMissing() = BackupScheduler.restore(app)

        override fun onMobileData(): Boolean = runner.onMobileData()

        override fun notificationsAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        override fun batteryRestricted(): Boolean =
            app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == false

        override fun now(): Long = System.currentTimeMillis()
    }

    val state = BackupState(platform)
    val runner = BackupRunner(app, state, settings, history, index)

    /** At app start: notification channels, and a scheduled backup waiting if the schedule is on. */
    fun start() {
        BackupNotifications.createChannels(app)
        scope.launch { runCatching { BackupScheduler.ensureScheduled(app, settings.load()) } }
    }

    companion object {
        @Volatile private var instance: BackupGraph? = null

        fun get(context: Context): BackupGraph = instance ?: synchronized(this) {
            instance ?: BackupGraph(context).also { instance = it }
        }
    }
}
