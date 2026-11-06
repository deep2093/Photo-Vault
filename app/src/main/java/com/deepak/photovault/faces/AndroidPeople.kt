package com.deepak.photovault.faces

import androidx.lifecycle.lifecycleScope
import com.deepak.photovault.MainActivity
import com.deepak.photovault.ui.shell.FaceSpot
import com.deepak.photovault.ui.shell.PeoplePlatform
import com.deepak.photovault.ui.shell.PeopleSnapshot
import com.deepak.photovault.ui.shell.Person
import com.deepak.photovault.ui.shell.SamePersonQuestion
import com.deepak.photovault.ui.shell.ScanProgress
import com.deepak.photovault.ui.shell.UnsureSet
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** People on Android (7A): faces found on the phone only, scanned while charging or when asked. */
class AndroidPeople(private val activity: MainActivity) : PeoplePlatform {
    private val app = activity.applicationContext

    override val supported: Boolean get() = true

    override fun isEnabled(): Boolean = FaceLibrary.enabled(app)

    override suspend fun setEnabled(on: Boolean) {
        if (on) {
            FaceLibrary.setEnabled(app, true)
            // "Same person?" notifications are always on (6 Nov): ask for permission now if it was never given.
            activity.requestNotifications()
            bringBackFromDrive()
            FaceScanWorker.schedule(app)
        } else if (FaceLibrary.useAura(app)) {
            // Off in the test model (6 Nov): only AuraFace's list goes, and the usual list comes back as it was.
            FaceLibrary.forgetModel(app)
            FaceLibrary.setEnabled(app, false)
            FaceLibrary.switchModel(app, false)
            if (FaceLibrary.enabled(app)) FaceScanWorker.schedule(app)
        } else {
            // Off in the usual list: only its faces and names go; AuraFace's list stays (6 Nov).
            FaceScanWorker.cancel(app)
            FaceLibrary.forgetModel(app)
            FaceLibrary.setEnabled(app, false)
        }
    }

    /** A new phone (7A-3): names and faces come back from the family drive's encrypted copy, if there is one. */
    private suspend fun bringBackFromDrive() {
        if (FaceLibrary.everUsed(app)) return
        FaceLibrary.markUsed(app)
        if (!FaceLibrary.emptyHere(app)) return
        runCatching {
            com.deepak.photovault.backup.BackupGraph.get(activity).runner.onMyDrive { fs, userId ->
                PeopleDriveCopy.restore(app, fs, userId).orEmpty()
            }
        }
    }

    override suspend fun setUpTestModel(onProgress: (Float) -> Unit): String? {
        FaceLibrary.downloadAura(app, onProgress)?.let { return it }
        FaceLibrary.switchModel(app, true)
        FaceLibrary.setEnabled(app, true)
        bringBackFromDrive()
        FaceScanWorker.schedule(app)
        return null
    }

    override fun scanCutShort(): Boolean = FaceLibrary.scanCutShort(app)

    override fun clearCutShort() = FaceLibrary.clearCutShort(app)

    override fun openBatterySettings() {
        runCatching {
            activity.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", app.packageName, null)),
            )
        }
    }

    override suspend fun load(): PeopleSnapshot = FaceLibrary.load(app)

    override suspend fun edit(change: (List<Person>) -> List<Person>) = FaceLibrary.edit(app, change)

    override fun scanNow() {
        // Shown as a notification while it runs (6 Nov), so it carries on with the screen off.
        FaceScanWorker.scanNow(app)
    }

    override fun stopScan() = FaceLibrary.stop()

    override suspend fun answerUnsure(set: UnsureSet, personId: Int?, yes: Boolean, park: Boolean) =
        FaceLibrary.answerUnsure(app, set, personId, yes, park)

    override fun clearProblem() { if (FaceLibrary.progress.value?.problem != null) FaceLibrary.progress.value = null }

    override val progress: StateFlow<ScanProgress?> get() = FaceLibrary.progress

    override suspend fun faceThumb(spot: FaceSpot): ByteArray? = FaceLibrary.faceThumb(app, spot)

    override suspend fun move(fromId: Int, faceIds: Set<Int>, toId: Int?) = FaceLibrary.move(app, fromId, faceIds, toId)

    override suspend fun tidy() = FaceLibrary.tidy(app)

    override suspend fun answer(question: SamePersonQuestion, same: Boolean) = FaceLibrary.answer(app, question, same)

    override fun usesTestModel(): Boolean = FaceLibrary.useAura(app)

    /** Only switches which list is shown (6 Nov): AuraFace never starts by itself; it has its own setup. */
    override suspend fun setTestModel(on: Boolean, onProgress: (Float) -> Unit): String? {
        FaceLibrary.switchModel(app, on)
        if (FaceLibrary.enabled(app)) FaceScanWorker.schedule(app)
        return null
    }

    override fun askByNotification(): Boolean = FaceLibrary.askByNotification(app)

    override fun setAskByNotification(on: Boolean) = FaceLibrary.setAskByNotification(app, on)
}
