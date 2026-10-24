package com.deepak.photovault.core.household

import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.drive.DrivePaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** How to reach the drive, as an invite carries it (25 Oct). The password is inside the locked part only. */
@Serializable
data class InviteDrive(
    val host: String,
    val share: String,
    val folder: String,
    val username: String = "",
    val password: String = "",
)

/** What is locked inside an invite. */
@Serializable
data class InvitePayload(
    val version: Int = 1,
    val id: String,
    val householdId: String,
    val familyName: String = "",
    val expiresAtMillis: Long,
    val drive: InviteDrive,
    /** The WireGuard file made for this person, if the owner attached one. */
    val wireGuard: String? = null,
    /** The name the owner meant it for (shown to the person; they can change it when joining). */
    val forName: String = "",
)

/**
 * An invite as text: a file or QR the owner shares (WhatsApp, say), locked with a code the owner tells the person
 * separately. Wrong code or a changed file simply doesn't open. Nothing inside is readable without the code, and the
 * password is never shown anywhere.
 */
object Invite {
    private const val PREFIX = "PXINV1."
    private const val ITERATIONS = 120_000
    private val json = Json { ignoreUnknownKeys = true }
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** A code the owner reads out or sends: 8 letters and numbers without look-alikes, like K7QD-M2XP. */
    fun newCode(): String {
        val bytes = secureRandomBytes(8)
        val chars = bytes.map { ALPHABET[(it.toInt() and 0xFF) % ALPHABET.length] }.joinToString("")
        return chars.take(4) + "-" + chars.drop(4)
    }

    /** Capitals, no spaces or dashes, so "k7qd m2xp" and "K7QD-M2XP" are the same code. */
    fun cleanCode(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    fun looksLikeInvite(text: String): Boolean = text.trim().startsWith(PREFIX)

    @OptIn(ExperimentalEncodingApi::class)
    fun lock(payload: InvitePayload, code: String): String {
        val clean = cleanCode(code)
        require(clean.length >= 6) { "The code needs at least 6 letters or numbers" }
        val salt = secureRandomBytes(16)
        val nonce = secureRandomBytes(12)
        val key = pbkdf2Sha256(clean, salt, ITERATIONS, 32)
        val sealed = aesGcmSeal(key, nonce, json.encodeToString(InvitePayload.serializer(), payload).encodeToByteArray())
        return PREFIX + Base64.UrlSafe.encode(salt + nonce + sealed)
    }

    /** The invite's contents, or null when the code is wrong or the text is damaged. */
    @OptIn(ExperimentalEncodingApi::class)
    fun unlock(text: String, code: String): InvitePayload? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        val raw = runCatching { Base64.UrlSafe.decode(trimmed.removePrefix(PREFIX)) }.getOrNull() ?: return null
        if (raw.size < 16 + 12 + 16) return null
        val key = pbkdf2Sha256(cleanCode(code), raw.copyOfRange(0, 16), ITERATIONS, 32)
        val plain = aesGcmOpen(key, raw.copyOfRange(16, 28), raw.copyOfRange(28, raw.size)) ?: return null
        return runCatching { json.decodeFromString(InvitePayload.serializer(), plain.decodeToString()) }.getOrNull()
    }
}

@Serializable
data class InviteMarker(val id: String, val createdAtMillis: Long, val expiresAtMillis: Long, val forName: String = "")

enum class InviteClaim { OK, EXPIRED, ALREADY_USED, NOT_FOUND }

/** The drive's record of invites, so each works once, runs out, and can be taken back by the owner. */
class InviteBoard(private val drive: DriveFileSystem, private val now: () -> Long) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun issue(marker: InviteMarker) {
        drive.mkdirs(DIR)
        drive.createNew(markerPath(marker.id), json.encodeToString(InviteMarker.serializer(), marker).encodeToByteArray())
    }

    /** The owner takes an invite back (or clears it once used). */
    suspend fun revoke(id: String) {
        drive.delete(markerPath(id))
        drive.delete(usedPath(id))
    }

    suspend fun open(): List<InviteMarker> = try {
        drive.list(DIR).filter { !it.isFolder && it.name.endsWith(".json") }.mapNotNull { e ->
            runCatching { json.decodeFromString(InviteMarker.serializer(), drive.read(DrivePaths.join(DIR, e.name)).decodeToString()) }.getOrNull()
        }.filter { !drive.exists(usedPath(it.id)) }.sortedByDescending { it.createdAtMillis }
    } catch (_: DriveNotFound) {
        emptyList()
    }

    /** Uses the invite. Only the first caller gets [InviteClaim.OK]: the used mark is created atomically. */
    suspend fun claim(id: String): InviteClaim {
        val marker = runCatching { json.decodeFromString(InviteMarker.serializer(), drive.read(markerPath(id)).decodeToString()) }.getOrNull()
            ?: return InviteClaim.NOT_FOUND
        if (now() > marker.expiresAtMillis) return InviteClaim.EXPIRED
        return if (drive.createNew(usedPath(id), now().toString().encodeToByteArray())) InviteClaim.OK else InviteClaim.ALREADY_USED
    }

    /** Lets the invite be used again, when joining failed after it was claimed. */
    suspend fun release(id: String) = drive.delete(usedPath(id))

    private fun markerPath(id: String) = DrivePaths.join(DIR, "${HouseholdStore.safeId(id)}.json")
    private fun usedPath(id: String) = DrivePaths.join(DIR, "${HouseholdStore.safeId(id)}.used")

    companion object {
        const val DIR = "system/invites"
    }
}
