package com.deepak.photovault.core.backup

import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.drive.DrivePaths
import com.deepak.photovault.core.household.HouseholdStore
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

/**
 * "I'm backing up now" (24 Oct: only one phone in the family backs up at a time, so they don't slow the drive
 * and the internet for each other). Each phone writes it in its own person's folder (users/<id>/active/), and
 * refreshes it every minute while it works; one older than [BackupCoordinator.STALE_MS] is ignored (a phone
 * that was switched off half way).
 */
@Serializable
data class BackupLease(
    val deviceId: String,
    val deviceName: String,
    val userId: String,
    val startedAtMillis: Long,
    val heartbeatAtMillis: Long,
)

/**
 * "I'm waiting for my turn" (28 Oct, Backup now queue): a phone that wants to back up while another is busy writes
 * this in users/<id>/queue/ and refreshes it while it waits. Phones go in the order they asked, so the line is fair
 * and each phone can show "you're number N". Older than [BackupCoordinator.STALE_MS] means that phone gave up.
 */
@Serializable
data class QueueTicket(
    val deviceId: String,
    val deviceName: String,
    val userId: String,
    val requestedAtMillis: Long,
    val heartbeatAtMillis: Long,
)

/** The line for the drive. Pure maths, tested. */
object QueueOrder {
    /** Waiting phones that asked before [me] (same moment: the smaller device ID goes first). Ignores [me] and stale tickets. */
    fun waitingAhead(me: QueueTicket, all: List<QueueTicket>, now: Long): List<QueueTicket> =
        all.filter {
            it.deviceId != me.deviceId && now - it.heartbeatAtMillis < BackupCoordinator.STALE_MS &&
                (it.requestedAtMillis < me.requestedAtMillis || (it.requestedAtMillis == me.requestedAtMillis && it.deviceId < me.deviceId))
        }

    /** 1 means "you're next" (nobody running, nobody ahead); 2 means one phone is ahead, and so on. */
    fun place(running: Int, waitingAhead: Int): Int = 1 + running + waitingAhead
}

/** A phone's backup time, published so the others can pick a different hour (users/<id>/schedules/). */
@Serializable
data class PublishedSchedule(
    val deviceId: String,
    val deviceName: String,
    val userId: String,
    val userName: String,
    /** The phone's own time zone (family abroad: 2 am in India isn't 2 am in Melbourne). */
    val zoneId: String,
    val schedule: BackupSchedule,
)

/** Keeping backup times at least an hour apart, wherever each phone is. Pure maths, tested. */
object SlotRules {
    const val GAP_MS = 60 * 60_000L
    private const val LOOK_AHEAD_MS = 35L * 24 * 60 * 60_000L

    /** Whether [a] in [zoneA] and [b] in [zoneB] would ever start within an hour of each other (next five weeks). */
    fun clash(a: BackupSchedule, zoneA: TimeZone, b: BackupSchedule, zoneB: TimeZone, now: Long): Boolean {
        if (!a.enabled || !b.enabled) return false
        val ta = runs(a, zoneA, now)
        val tb = runs(b, zoneB, now)
        var j = 0
        for (t in ta) {
            while (j < tb.size && tb[j] < t - GAP_MS) j++
            if (j < tb.size && kotlin.math.abs(tb[j] - t) < GAP_MS) return true
            if (j + 1 < tb.size && kotlin.math.abs(tb[j + 1] - t) < GAP_MS) return true
        }
        return false
    }

    /** The other phones [mine] would clash with. */
    fun clashes(mine: BackupSchedule, myZone: TimeZone, others: List<PublishedSchedule>, now: Long): List<PublishedSchedule> =
        others.filter { o -> clash(mine, myZone, o.schedule, zoneOf(o.zoneId), now) }

    /** The nearest time (same days) at least an hour from everyone else, or null if the day is full. */
    fun nearestFree(mine: BackupSchedule, myZone: TimeZone, others: List<PublishedSchedule>, now: Long): BackupSchedule? {
        for (step in 1..23) {
            for (sign in listOf(1, -1)) {
                val minutes = ((mine.hour * 60 + mine.minute + sign * step * 60) % 1440 + 1440) % 1440
                val candidate = mine.copy(hour = minutes / 60, minute = minutes % 60)
                if (clashes(candidate, myZone, others, now).isEmpty()) return candidate
            }
        }
        return null
    }

    fun zoneOf(id: String): TimeZone = runCatching { TimeZone.of(id) }.getOrDefault(TimeZone.UTC)

    private fun runs(s: BackupSchedule, zone: TimeZone, now: Long): List<Long> {
        val out = ArrayList<Long>()
        var t = now - GAP_MS
        while (true) {
            t = runCatching { s.nextRun(t, zone) }.getOrNull() ?: break
            if (t > now + LOOK_AHEAD_MS) break
            out += t
        }
        return out
    }
}

/**
 * The family's backups taking turns on the drive (24 Oct). Reads everyone's folders, writes only this person's.
 */
class BackupCoordinator(
    private val drive: DriveFileSystem,
    private val userId: String,
    private val deviceId: String,
    private val deviceName: String,
    private val now: () -> Long,
) {
    private val leasePath = DrivePaths.join(HouseholdStore.userFolder(userId), ACTIVE, "$deviceId.json")
    private var startedAt = 0L
    private val ticketPath = DrivePaths.join(HouseholdStore.userFolder(userId), QUEUE, "$deviceId.json")
    private var ticket: QueueTicket? = null

    /** Someone else's backup running now, if any. */
    suspend fun othersRunning(userIds: List<String>): List<BackupLease> =
        leases(userIds).filter { it.deviceId != deviceId && now() - it.heartbeatAtMillis < STALE_MS }

    /**
     * Takes the turn if nobody else has it. If two phones take it at the same moment, the one that started first
     * (then the smaller device ID) keeps it and the other lets go. Returns false when it must wait.
     */
    suspend fun tryStart(userIds: List<String>): Boolean {
        if (othersRunning(userIds).isNotEmpty()) return false
        // A phone that joined the line waits for the phones that asked before it.
        if (ticket != null && waitingAhead(userIds).isNotEmpty()) return false
        startedAt = now()
        write(startedAt)
        val rivals = othersRunning(userIds)
        if (rivals.any { it.startedAtMillis < startedAt || (it.startedAtMillis == startedAt && it.deviceId < deviceId) }) {
            finish()
            return false
        }
        return true
    }

    /** Gets in line (and keeps the place while it waits: call [refreshTicket] on every wait step). */
    suspend fun joinQueue() {
        val t = QueueTicket(deviceId, deviceName, userId, now(), now())
        ticket = t
        runCatching { writeTicket(t) }
    }

    suspend fun refreshTicket() {
        val t = ticket ?: return
        val fresh = t.copy(heartbeatAtMillis = now())
        ticket = fresh
        runCatching { writeTicket(fresh) }
    }

    suspend fun leaveQueue() {
        ticket = null
        try {
            drive.delete(ticketPath)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Waiting phones in front of this one. */
    suspend fun waitingAhead(userIds: List<String>): List<QueueTicket> {
        val me = ticket ?: return emptyList()
        return QueueOrder.waitingAhead(me, tickets(userIds), now())
    }

    /** This phone's place in line: 1 = next. */
    suspend fun place(userIds: List<String>): Int =
        QueueOrder.place(othersRunning(userIds).size, waitingAhead(userIds).size)

    /** "Still working" (call about once a minute while backing up). */
    suspend fun heartbeat() {
        if (startedAt != 0L) runCatching { write(startedAt) }
    }

    suspend fun finish() {
        startedAt = 0L
        try {
            drive.delete(leasePath)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Gone already, or the drive is out of reach: it goes stale on its own.
        }
    }

    /** People whose phone is backing up right now (the dot on Family). */
    suspend fun activeUsers(userIds: List<String>): Set<String> =
        leases(userIds).filter { now() - it.heartbeatAtMillis < STALE_MS }.map { it.userId }.toSet()

    suspend fun publish(schedule: BackupSchedule, userName: String, zoneId: String) {
        val folder = DrivePaths.join(HouseholdStore.userFolder(userId), SCHEDULES)
        drive.mkdirs(folder)
        val path = DrivePaths.join(folder, "$deviceId.json")
        val bytes = BackupEngine.json.encodeToString(
            PublishedSchedule.serializer(),
            PublishedSchedule(deviceId, deviceName, userId, userName, zoneId, schedule),
        ).encodeToByteArray()
        replace(path, bytes)
    }

    /** Every other phone's published backup time. */
    suspend fun otherSchedules(userIds: List<String>): List<PublishedSchedule> = userIds.flatMap { id ->
        readAll(DrivePaths.join(HouseholdStore.userFolder(id), SCHEDULES)) { BackupEngine.json.decodeFromString(PublishedSchedule.serializer(), it) }
    }.filter { it.deviceId != deviceId }

    private suspend fun leases(userIds: List<String>): List<BackupLease> = userIds.flatMap { id ->
        readAll(DrivePaths.join(HouseholdStore.userFolder(id), ACTIVE)) { BackupEngine.json.decodeFromString(BackupLease.serializer(), it) }
    }

    private suspend fun tickets(userIds: List<String>): List<QueueTicket> = userIds.flatMap { id ->
        readAll(DrivePaths.join(HouseholdStore.userFolder(id), QUEUE)) { BackupEngine.json.decodeFromString(QueueTicket.serializer(), it) }
    }

    private suspend fun writeTicket(t: QueueTicket) {
        drive.mkdirs(DrivePaths.join(HouseholdStore.userFolder(userId), QUEUE))
        replace(ticketPath, BackupEngine.json.encodeToString(QueueTicket.serializer(), t).encodeToByteArray())
    }

    private suspend fun write(started: Long) {
        drive.mkdirs(DrivePaths.join(HouseholdStore.userFolder(userId), ACTIVE))
        val bytes = BackupEngine.json.encodeToString(
            BackupLease.serializer(),
            BackupLease(deviceId, deviceName, userId, started, now()),
        ).encodeToByteArray()
        replace(leasePath, bytes)
    }

    private suspend fun replace(path: String, bytes: ByteArray) {
        if (!drive.createNew(path, bytes)) {
            drive.delete(path)
            drive.createNew(path, bytes)
        }
    }

    private suspend fun <T> readAll(folder: String, parse: (String) -> T): List<T> {
        val files = try {
            drive.list(folder).filter { !it.isFolder && it.name.endsWith(".json") }
        } catch (e: CancellationException) {
            throw e
        } catch (_: DriveNotFound) {
            return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        return files.mapNotNull { f -> runCatching { parse(drive.read(DrivePaths.join(folder, f.name)).decodeToString()) }.getOrNull() }
    }

    companion object {
        const val ACTIVE = "active"
        const val SCHEDULES = "schedules"
        const val QUEUE = "queue"

        /** A turn not refreshed for this long is treated as over. */
        const val STALE_MS = 3 * 60_000L
    }
}
