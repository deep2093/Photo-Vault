package com.deepak.photovault.core.household

import kotlinx.serialization.Serializable

/**
 * The family on one drive. Stored on the drive as numbered versions (system/household/v000001.json, ...);
 * the highest number is current. See docs/HOUSEHOLD_PROTOCOL.md.
 *
 * Names are for people to read; folders and links always use the random IDs.
 */
@Serializable
data class Household(
    val formatVersion: Int = FORMAT_VERSION,
    val householdId: String,
    /** Matches the file number it was written as. */
    val revision: Int,
    val ownerUserId: String,
    val ownerPin: PinHash? = null,
    val settings: HouseholdSettings = HouseholdSettings(),
    val members: List<Member> = emptyList(),
) {
    val owner: Member? get() = members.firstOrNull { it.userId == ownerUserId }

    fun member(userId: String): Member? = members.firstOrNull { it.userId == userId }

    fun memberForDevice(deviceId: String): Member? = members.firstOrNull { m -> m.devices.any { it.deviceId == deviceId } }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** Owner choices (Settings > Family). Defaults follow Deepak's picks of 22 Oct 2026. */
@Serializable
data class HouseholdSettings(
    /** The owner enters a PIN before changing limits, removing people, approving phones or changing these. */
    val requirePin: Boolean = true,
    /** New phones wait until the owner approves them. */
    val approveNewPhones: Boolean = true,
    /** Space each new person gets on the drive. */
    val defaultLimitGb: Int = DEFAULT_LIMIT_GB,
)

@Serializable
data class Member(
    val userId: String,
    val name: String,
    val limitGb: Int = DEFAULT_LIMIT_GB,
    val joinedAtMillis: Long,
    val devices: List<Device> = emptyList(),
    /** Profile picture on the drive (system/people/...jpg), about 256 px, or null for the first letter. */
    val photo: String? = null,
)

@Serializable
data class Device(val deviceId: String, val name: String, val addedAtMillis: Long)

/** PBKDF2-SHA256 of the owner PIN. Stops casual changes on a borrowed phone; see SECURITY_MODEL.md for limits. */
@Serializable
data class PinHash(val saltHex: String, val hashHex: String, val iterations: Int)

/**
 * A phone asking to join (system/requests/<deviceId>.json). Either as a new person ([newName]) or as another
 * phone of someone already in the family ([existingUserId]).
 */
@Serializable
data class JoinRequest(
    val deviceId: String,
    val deviceName: String,
    val newName: String? = null,
    val existingUserId: String? = null,
    val requestedAtMillis: Long,
)

const val DEFAULT_LIMIT_GB = 100
const val MIN_LIMIT_GB = 1
const val MAX_LIMIT_GB = 100_000

/** Where this phone stands with the family on the drive. */
sealed interface Membership {
    /** The drive has no PhotoVault family yet: this phone can create one and become the owner. */
    data object NoHousehold : Membership

    /** A family exists; this phone has not asked to join. */
    data class NotJoined(val household: Household) : Membership

    /** Asked to join; waiting for the owner. */
    data class Pending(val household: Household, val request: JoinRequest) : Membership

    data class Joined(val household: Household, val me: Member) : Membership {
        val isOwner: Boolean get() = household.ownerUserId == me.userId
    }

    /** This phone was in the family but has been removed by the owner. */
    data class Removed(val household: Household) : Membership
}
