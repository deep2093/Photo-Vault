package com.deepak.photovault.core.household

import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DrivePaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Someone asking the owner for more space on the drive (24 Oct). Saved in their own folder. */
@Serializable
data class SpaceRequest(
    val userId: String,
    val name: String,
    /** The space they ask for in total, in GB. */
    val wantGb: Int,
    val note: String = "",
    val requestedAtMillis: Long,
)

/** The owner's "no" to a request, saved in the owner's own folder (nobody writes in someone else's). */
@Serializable
data class SpaceDecision(val userId: String, val requestedAtMillis: Long, val decidedAtMillis: Long)

/**
 * Asking for more space and answering (24 Oct). A request is open until the owner gives at least that much
 * (it then disappears by itself) or declines it. Everyone writes only in their own folder.
 */
class SpaceRequests(private val drive: DriveFileSystem, private val now: () -> Long) {
    suspend fun ask(me: Member, wantGb: Int, note: String = "") {
        require(wantGb in MIN_LIMIT_GB..MAX_LIMIT_GB) { "Choose between $MIN_LIMIT_GB and $MAX_LIMIT_GB GB" }
        require(wantGb > me.limitGb) { "That's not more than you have now" }
        val request = SpaceRequest(me.userId, me.name, wantGb, note.trim().take(300), now())
        val folder = DrivePaths.join(HouseholdStore.userFolder(me.userId), FOLDER)
        drive.mkdirs(folder)
        replace(DrivePaths.join(folder, FILE), json.encodeToString(SpaceRequest.serializer(), request).encodeToByteArray())
    }

    suspend fun cancel(userId: String) {
        runCatching { drive.delete(DrivePaths.join(HouseholdStore.userFolder(userId), FOLDER, FILE)) }
    }

    /** This person's own request, if still open. */
    suspend fun mine(household: Household, userId: String): SpaceRequest? = open(household).firstOrNull { it.userId == userId }

    /** Was this person's latest request declined? */
    suspend fun declined(household: Household, userId: String): Boolean {
        val request = read(userId) ?: return false
        return decisions(household)[userId]?.requestedAtMillis == request.requestedAtMillis
    }

    /** Requests waiting for the owner: not yet given, not declined. */
    suspend fun open(household: Household): List<SpaceRequest> {
        val declined = decisions(household)
        return household.members.mapNotNull { m ->
            read(m.userId)?.takeIf { r ->
                r.wantGb > m.limitGb && declined[m.userId]?.requestedAtMillis != r.requestedAtMillis
            }
        }.sortedBy { it.requestedAtMillis }
    }

    /** The owner says no (the owner PIN is checked by the app, like other owner actions). */
    suspend fun decline(household: Household, request: SpaceRequest) {
        val folder = DrivePaths.join(HouseholdStore.userFolder(household.ownerUserId), FOLDER)
        drive.mkdirs(folder)
        val path = DrivePaths.join(folder, "declined-${request.userId}.json")
        replace(path, json.encodeToString(SpaceDecision.serializer(), SpaceDecision(request.userId, request.requestedAtMillis, now())).encodeToByteArray())
    }

    private suspend fun read(userId: String): SpaceRequest? = runCatching {
        json.decodeFromString(SpaceRequest.serializer(), drive.read(DrivePaths.join(HouseholdStore.userFolder(userId), FOLDER, FILE)).decodeToString())
    }.getOrNull()

    private suspend fun decisions(household: Household): Map<String, SpaceDecision> {
        val folder = DrivePaths.join(HouseholdStore.userFolder(household.ownerUserId), FOLDER)
        val files = runCatching { drive.list(folder) }.getOrDefault(emptyList()).filter { it.name.startsWith("declined-") }
        return files.mapNotNull { f ->
            runCatching { json.decodeFromString(SpaceDecision.serializer(), drive.read(DrivePaths.join(folder, f.name)).decodeToString()) }.getOrNull()
        }.associateBy { it.userId }
    }

    private suspend fun replace(path: String, bytes: ByteArray) {
        if (!drive.createNew(path, bytes)) {
            drive.delete(path)
            drive.createNew(path, bytes)
        }
    }

    companion object {
        const val FOLDER = "requests"
        private const val FILE = "space.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
