package com.deepak.photovault.faces

import androidx.lifecycle.lifecycleScope
import com.deepak.photovault.MainActivity
import com.deepak.photovault.ui.shell.FaceSpot
import com.deepak.photovault.ui.shell.PeoplePlatform
import com.deepak.photovault.ui.shell.PeopleSnapshot
import com.deepak.photovault.ui.shell.Person
import com.deepak.photovault.ui.shell.SamePersonQuestion
import com.deepak.photovault.ui.shell.ScanProgress
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** People on Android (7A): faces found on the phone only, scanned while charging or when asked. */
class AndroidPeople(private val activity: MainActivity) : PeoplePlatform {
    private val app = activity.applicationContext

    override val supported: Boolean get() = true

    override fun isEnabled(): Boolean = FaceLibrary.enabled(app)

    override suspend fun setEnabled(on: Boolean) {
        FaceLibrary.setEnabled(app, on)
        if (on) {
            // "Same person?" notifications are always on (6 Nov): ask for permission now if it was never given.
            activity.requestNotifications()
            FaceScanWorker.schedule(app)
        } else if (FaceLibrary.useAura(app)) {
            // Off in the test model (6 Nov): only AuraFace's list goes, and the usual people come back.
            FaceLibrary.forgetModel(app)
            FaceLibrary.setEnabled(app, true)
            FaceLibrary.switchModel(app, false)
            FaceScanWorker.schedule(app)
        } else {
            FaceScanWorker.cancel(app)
            FaceLibrary.forgetModel(app)
        }
    }

    override suspend fun load(): PeopleSnapshot = FaceLibrary.load(app)

    override suspend fun edit(change: (List<Person>) -> List<Person>) = FaceLibrary.edit(app, change)

    override fun scanNow() {
        activity.lifecycleScope.launch { FaceLibrary.scan(app) }
    }

    override fun stopScan() = FaceLibrary.stop()

    override fun clearProblem() { if (FaceLibrary.progress.value?.problem != null) FaceLibrary.progress.value = null }

    override val progress: StateFlow<ScanProgress?> get() = FaceLibrary.progress

    override suspend fun faceThumb(spot: FaceSpot): ByteArray? = FaceLibrary.faceThumb(app, spot)

    override suspend fun move(fromId: Int, faceIds: Set<Int>, toId: Int?) = FaceLibrary.move(app, fromId, faceIds, toId)

    override suspend fun tidy() = FaceLibrary.tidy(app)

    override suspend fun answer(question: SamePersonQuestion, same: Boolean) = FaceLibrary.answer(app, question, same)

    override fun usesTestModel(): Boolean = FaceLibrary.useAura(app)

    override suspend fun setTestModel(on: Boolean, onProgress: (Float) -> Unit): String? {
        if (on) {
            FaceLibrary.downloadAura(app, onProgress)?.let { return it }
        }
        FaceLibrary.switchModel(app, on)
        FaceScanWorker.schedule(app)
        return null
    }

    override fun askByNotification(): Boolean = FaceLibrary.askByNotification(app)

    override fun setAskByNotification(on: Boolean) = FaceLibrary.setAskByNotification(app, on)
}
