package com.deepak.photovault.faces

import android.content.Context
import com.deepak.photovault.SecuredKeys
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DrivePaths

/**
 * People's encrypted copy on the family drive (7A-3, 6 Nov): names, faces and photo keys of each model, sealed with a
 * key made from the Secured folder's key, so nothing readable reaches the drive and a new phone that brings the Secured
 * folder back can carry on without scanning from scratch. Drive layout: `users/<id>/people/<model>-<time>.pxp`, the
 * newest one is used.
 */
object PeopleDriveCopy {
    private const val PURPOSE = "people-copy"
    private const val FOLDER = "people"

    private fun folder(userId: String) = DrivePaths.join(com.deepak.photovault.core.household.HouseholdStore.userFolder(userId), FOLDER)

    private fun model(aura: Boolean) = if (aura) "aura" else "standard"

    /** Copies both models' lists up (each only if it has faces). Never throws on a missing key: just does nothing. */
    suspend fun upload(context: Context, drive: DriveFileSystem, userId: String) {
        val vault = SecuredKeys.peopleVault(context) ?: return
        val dir = folder(userId)
        drive.mkdirs(dir)
        for (aura in listOf(false, true)) {
            val plain = FaceLibrary.exportCopy(context, aura) ?: continue
            val sealed = vault.sealRecord(plain, PURPOSE)
            val prefix = model(aura) + "-"
            val old = drive.list(dir).filter { !it.isFolder && it.name.startsWith(prefix) && it.name.endsWith(".pxp") }.map { it.name }
            var at = System.currentTimeMillis()
            while (!drive.createNew(DrivePaths.join(dir, "$prefix$at.pxp"), sealed)) at++
            old.forEach { drive.delete(DrivePaths.join(dir, it)) }
        }
    }

    /** Brings the newest copy of the model in use home when this phone has none. A short line for the screen, or null. */
    suspend fun restore(context: Context, drive: DriveFileSystem, userId: String): String? {
        if (!FaceLibrary.emptyHere(context)) return null
        val vault = SecuredKeys.peopleVault(context) ?: return null
        val dir = folder(userId)
        if (!drive.exists(dir)) return null
        val prefix = model(FaceLibrary.useAura(context)) + "-"
        val newest = drive.list(dir).filter { !it.isFolder && it.name.startsWith(prefix) && it.name.endsWith(".pxp") }.maxByOrNull { it.name } ?: return null
        val bytes = java.io.ByteArrayOutputStream()
        drive.readStream(DrivePaths.join(dir, newest.name)) { b, n -> bytes.write(b, 0, n) }
        val plain = vault.openRecord(bytes.toByteArray(), PURPOSE) ?: return null
        return if (FaceLibrary.importCopy(context, plain)) "Your people came back from the family drive." else null
    }
}
