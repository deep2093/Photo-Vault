package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * The Places map (7B-2, 7 Nov): OpenStreetMap data drawn by MapLibre with OpenFreeMap's free tiles (no key, no
 * account), a dark style for dark themes, map squares kept on the phone once seen. Photos sit on it as round
 * thumbnails that merge into bubbles: countries when zoomed far out, then towns and cities, then nearby photos.
 */

/** What the map shows right now: zoom, the visible box, and where a spot is on screen (pixels, or null when off it). */
class MapViewport(
    val zoom: Double,
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double,
    val widthPx: Float,
    val heightPx: Float,
    private val project: (Double, Double) -> Offset?,
) {
    fun toScreen(lat: Double, lon: Double): Offset? = project(lat, lon)

    fun contains(lat: Double, lon: Double): Boolean {
        if (lat > north || lat < south) return false
        return if (west <= east) lon in west..east else lon >= west || lon <= east
    }
}

/** Moves the map from outside (a bubble tapped, the photo details' small map). */
class MapController {
    internal var flyTo: ((Double, Double, Double) -> Unit)? = null

    fun fly(lat: Double, lon: Double, zoom: Double) { flyTo?.invoke(lat, lon, zoom) }
}

/** Where the map starts. */
data class MapStart(val lat: Double, val lon: Double, val zoom: Double)

/**
 * The map itself. [interactive] false: a still map (the photo details' small map). [onViewport] is called as the map
 * moves (idle false) and once more when it stops (idle true). [dark] is ignored (7 Nov: the coloured map only).
 */
@Composable
expect fun PhotoMap(
    modifier: Modifier,
    dark: Boolean,
    start: MapStart?,
    interactive: Boolean,
    controller: MapController?,
    onViewport: (MapViewport, Boolean) -> Unit,
)

/** A photo on the map. */
class MapPoint(val id: Long, val lat: Double, val lon: Double, val takenAt: Long, val place: Place?)

/** One bubble: where it sits on screen, its photos, its cover (the newest), and a label at the country and city levels. */
class MapBubble(val key: String, val at: Offset, val ids: List<Long>, val cover: Long, val label: String?, val lat: Double, val lon: Double)

object MapClusters {
    /** Zoomed out further than this: one bubble per country. */
    const val COUNTRY_BELOW = 4.0

    /** Then one bubble per town or city, until this zoom; nearer in, photos close together on screen. */
    const val CITY_BELOW = 9.0

    /**
     * Groups the photos in view into bubbles. Countries and towns use the place names; closer in, the screen is cut
     * into squares of [cellPx] and each square's photos make one bubble.
     */
    fun bubbles(points: List<MapPoint>, view: MapViewport, cellPx: Float): List<MapBubble> {
        val visible = points.filter { view.contains(it.lat, it.lon) }
        val groups: Map<String, List<MapPoint>> = when {
            view.zoom < COUNTRY_BELOW -> visible.filter { it.place != null }.groupBy { "c:" + it.place!!.country }
            view.zoom < CITY_BELOW -> visible.filter { it.place != null }.groupBy { "p:" + it.place!!.key }
            else -> visible.groupBy { p ->
                val s = view.toScreen(p.lat, p.lon) ?: return@groupBy "off"
                "g:${(s.x / cellPx).toInt()}:${(s.y / cellPx).toInt()}"
            }.filterKeys { it != "off" }
        }
        return groups.mapNotNull { (key, list) ->
            val lat = list.sumOf { it.lat } / list.size
            val lon = list.sumOf { it.lon } / list.size
            val at = view.toScreen(lat, lon) ?: return@mapNotNull null
            val label = when {
                key.startsWith("c:") -> list.first().place?.country
                key.startsWith("p:") -> list.first().place?.name
                else -> null
            }
            MapBubble(key, at, list.sortedByDescending { it.takenAt }.map { it.id }, list.maxBy { it.takenAt }.id, label, lat, lon)
        }.sortedBy { it.ids.size }
    }

    /** Where to fly when a bubble is tapped: in two levels closer, centred on it. */
    fun zoomInto(bubble: MapBubble, zoom: Double): Double = when {
        zoom < COUNTRY_BELOW -> 5.5
        zoom < CITY_BELOW -> 11.0
        else -> (zoom + 2.0).coerceAtMost(17.0)
    }
}

/** A trip: photos taken far from home over a few days in a row (7B-2). */
class Trip(val title: String, val ids: List<Long>, val start: Long, val end: Long)

object Trips {
    /** Further than this from home counts as away. */
    const val AWAY_KM = 100.0

    /** A gap longer than this between away photos starts a new trip. */
    const val GAP_MS = 2 * 86_400_000L

    const val YEAR_MS = 365 * 86_400_000L

    /** Fewer photos than this away is not a trip. */
    const val MIN_PHOTOS = 5

    /**
     * Home is the town with the most photos. Away photos, in time order, make one trip until a gap of more than two
     * days; each trip is named after its most photographed place and its month ("Sydney, March 2026" is made by the
     * screen from [Trip.title] and the date). Newest trip first.
     */
    fun find(points: List<MapPoint>): List<Trip> {
        val placed = points.filter { it.place != null }
        if (placed.isEmpty()) return emptyList()
        // Home is where you are now (7 Nov): the most photographed town of your last year of photos, not of all time
        // (someone who moved countries had their new home shown as trips).
        val newest = placed.maxOf { it.takenAt }
        val recent = placed.filter { it.takenAt >= newest - YEAR_MS }
        val homeKey = recent.groupingBy { it.place!!.key }.eachCount().maxBy { it.value }.key
        val home = placed.filter { it.place!!.key == homeKey }
        val homeLat = home.sumOf { it.lat } / home.size
        val homeLon = home.sumOf { it.lon } / home.size
        val away = placed.filter { km(it.lat, it.lon, homeLat, homeLon) > AWAY_KM }.sortedBy { it.takenAt }
        val trips = mutableListOf<Trip>()
        var run = mutableListOf<MapPoint>()
        fun close() {
            if (run.size >= MIN_PHOTOS) {
                val top = run.groupingBy { it.place!!.name }.eachCount().maxBy { it.value }.key
                trips += Trip(top, run.sortedByDescending { it.takenAt }.map { it.id }, run.first().takenAt, run.last().takenAt)
            }
            run = mutableListOf()
        }
        for (p in away) {
            if (run.isNotEmpty() && p.takenAt - run.last().takenAt > GAP_MS) close()
            run += p
        }
        close()
        return trips.sortedByDescending { it.end }
    }

    fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = (bLat - aLat) * kotlin.math.PI / 180
        val dLon = (bLon - aLon) * kotlin.math.PI / 180
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(aLat * kotlin.math.PI / 180) * kotlin.math.cos(bLat * kotlin.math.PI / 180) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }
}
