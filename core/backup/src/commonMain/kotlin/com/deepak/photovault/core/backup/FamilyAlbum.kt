package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.drive.DrivePaths
import com.deepak.photovault.core.household.HouseholdStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * A change one person made to the Family Album (Phase 5A, 25 Oct): photos they added (as the records of their own
 * backups, so the album holds pointers and never copies) and photos they took out. Saved as its own new file in
 * the person's own folder, never edited, so two phones can never overwrite each other.
 */
@Serializable
data class FamilyAlbumNote(
    val createdAtMillis: Long,
    val added: List<BackupRecord> = emptyList(),
    /** Drive paths this person took out of the album (the photos themselves stay in their backup). */
    val removed: List<String> = emptyList(),
)

/**
 * The album everyone in the family sees. Each person writes only into `users/<id>/family-album/`; the album is
 * every member's notes read together, in time order.
 */
class FamilyAlbum(private val drive: DriveFileSystem, private val myUserId: String) {
    private fun folderOf(userId: String) = DrivePaths.join(HouseholdStore.userFolder(userId), FOLDER)

    /** Adds photos from this person's own backups. Others' photos can't be added by someone else. */
    suspend fun add(records: List<BackupRecord>, now: Long) {
        val own = records.filter { it.drivePath.startsWith("${HouseholdStore.userFolder(myUserId)}/") && !it.drivePath.contains("..") }
        require(own.size == records.size) { "You can only add your own photos to the Family Album" }
        write(FamilyAlbumNote(now, added = own))
    }

    /** Takes photos out of the album: your own additions only. The photos stay where they are. */
    suspend fun remove(drivePaths: Collection<String>, now: Long) {
        val own = drivePaths.filter { it.startsWith("${HouseholdStore.userFolder(myUserId)}/") }
        if (own.isEmpty()) return
        write(FamilyAlbumNote(now, removed = own))
    }

    private suspend fun write(note: FamilyAlbumNote) {
        val folder = folderOf(myUserId)
        drive.mkdirs(folder)
        val bytes = BackupEngine.json.encodeToString(FamilyAlbumNote.serializer(), note).encodeToByteArray()
        var at = note.createdAtMillis
        while (!drive.createNew(DrivePaths.join(folder, BackupEngine.manifestName(at)), bytes)) at++
    }

    /**
     * Everything in the album, newest first. [members] are the family's user IDs. A photo whose backup has since
     * been deleted from its owner's drive folder is left out (its file is gone), and a person's removal only
     * hides photos from their own earlier additions.
     */
    suspend fun records(members: Collection<String>): List<BackupRecord> {
        val out = mutableListOf<BackupRecord>()
        for (user in members) {
            val folder = folderOf(user)
            val names = try {
                drive.list(folder).filter { !it.isFolder && it.name.endsWith(".json") }.map { it.name }.sorted()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val mine = LinkedHashMap<String, BackupRecord>()
            for (name in names) {
                val note = try {
                    BackupEngine.json.decodeFromString(FamilyAlbumNote.serializer(), drive.read(DrivePaths.join(folder, name)).decodeToString())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: DriveNotFound) {
                    continue
                } catch (_: Exception) {
                    continue // A damaged note never hides the rest.
                }
                note.added.forEach { mine[it.drivePath] = it }
                note.removed.forEach { mine.remove(it) }
            }
            if (mine.isNotEmpty()) {
                // Only photos still in their owner's backup.
                val alive = BackupCatalog(drive, user).records().map { it.drivePath }.toSet()
                out += mine.values.filter { it.drivePath in alive }
            }
        }
        return out.sortedByDescending { it.takenAtMillis }
    }

    companion object {
        const val FOLDER = "family-album"
    }
}
