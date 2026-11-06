package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Places, part 1 (7B-1, 7 Nov): where each photo was taken, read from the photo itself on this phone, named after the
 * nearest town or city from a list kept inside the app (GeoNames), so nothing is looked up online. The map comes in
 * part 2.
 */

/** A town or city. [key] is stable: "name|region|country". */
data class Place(val name: String, val region: String, val country: String) {
    val key: String get() = "$name|$region|$country"

    /** "Melbourne, Victoria" or "Melbourne, Australia" when the region is missing. */
    val label: String get() = listOf(name, region.ifBlank { country }).filter { it.isNotBlank() }.distinct().joinToString(", ")

    companion object {
        fun fromKey(key: String): Place? = key.split('|').takeIf { it.size == 3 }?.let { Place(it[0], it[1], it[2]) }
    }
}

/** Where one photo was taken. */
data class PhotoPlace(val latitude: Double, val longitude: Double, val place: Place?)

class PlacesSnapshot(
    /** Photos with a known location (photo id to where). */
    val photos: Map<Long, PhotoPlace> = emptyMap(),
    /** True while photos are still being read for their location. */
    val reading: Boolean = false,
)

class PlacesProgress(val done: Int, val total: Int)

interface PlacesPlatform {
    val supported: Boolean get() = false

    /** False until the phone allows reading where photos were taken (Android asks for it). */
    fun canReadLocations(): Boolean = false

    fun askForLocations() = Unit

    suspend fun load(): PlacesSnapshot = PlacesSnapshot()

    /** Reads locations of photos not read yet (a quick look at each photo's details), in the background. */
    fun readNow() = Unit

    val progress: StateFlow<PlacesProgress?> get() = MutableStateFlow(null)

    /** The street address, asked of the phone's own lookup only when tapped; remembered. Null when none is found. */
    suspend fun address(latitude: Double, longitude: Double): String? = null
}

@Stable
class PlacesState(val platform: PlacesPlatform = object : PlacesPlatform {}) {
    var photos by mutableStateOf<Map<Long, PhotoPlace>>(emptyMap())
        private set
    var loaded by mutableStateOf(false)
        private set

    suspend fun refresh() {
        photos = platform.load().photos
        loaded = true
    }

    /** Places with their photos, the place with the most photos first. Only photos in [shown] count. */
    fun places(shown: Set<Long>): List<Pair<Place, List<Long>>> =
        photos.entries.filter { it.key in shown && it.value.place != null }
            .groupBy({ it.value.place!! }, { it.key })
            .entries.map { it.key to it.value }
            .sortedByDescending { it.second.size }

    fun photosAt(key: String): Set<Long> = photos.entries.filter { it.value.place?.key == key }.map { it.key }.toSet()

    /** Search (7B-1): photos taken in a town, region or country whose name contains [query]. */
    fun photosMatching(query: String): Set<Long> {
        val q = query.trim()
        if (q.length < 3) return emptySet()
        return photos.entries.filter { (_, p) ->
            val place = p.place ?: return@filter false
            place.name.contains(q, ignoreCase = true) || place.region.contains(q, ignoreCase = true) || place.country.contains(q, ignoreCase = true)
        }.map { it.key }.toSet()
    }
}
