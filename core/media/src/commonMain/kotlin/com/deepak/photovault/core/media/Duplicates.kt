package com.deepak.photovault.core.media

/**
 * The duplicate finder's rules (Phase 11 part 3, 15 Nov). Two kinds:
 *
 * - **Exact copies**: the same file content twice (saved again from WhatsApp, downloaded twice). Found by size, then
 *   by a hash of the whole file, worked out only for files that share a size.
 * - **Similar photos**: bursts and near-identical shots. Each photo gets a small picture fingerprint (a 64-bit
 *   "difference hash" of a tiny grey copy); two photos are similar when their fingerprints differ in only a few
 *   bits and they were taken within a few minutes of each other.
 *
 * In each group one is picked to keep: the sharpest and biggest for similar photos; for exact copies, the one in the
 * camera folder, else the oldest. Nothing is ever removed here: the page shows the groups and the person decides.
 */
object Duplicates {
    /** Similar photos differ in at most this many of the 64 bits. */
    const val MAX_BITS = 10

    /** ...and were taken no further apart than this. */
    const val WINDOW_MS = 10 * 60 * 1000L

    enum class Kind { EXACT, SIMILAR }

    /** What the phone worked out about one photo or video. */
    data class Print(
        /** A hash of the whole file; only for files that share their size with another one. */
        val content: String? = null,
        /** The picture fingerprint (photos only). */
        val picture: Long? = null,
        /** How sharp it is (bigger is sharper); 0 when unknown. */
        val sharpness: Float = 0f,
    )

    data class Group(val kind: Kind, val ids: List<Long>, val keep: Long) {
        val others: List<Long> get() = ids.filter { it != keep }
    }

    /** Files that could be exact copies: those sharing their size with another (the only ones worth hashing). */
    fun sameSize(items: List<MediaItem>): List<MediaItem> =
        items.filter { it.sizeBytes > 0 }.groupBy { it.sizeBytes }.values.filter { it.size > 1 }.flatten()

    /** Exact copies first, then similar photos among what is left (each exact group counts once, by its keeper). */
    fun find(items: List<MediaItem>, prints: Map<Long, Print>): List<Group> {
        val exact = exact(items, prints)
        val inExact = exact.flatMap { it.others }.toHashSet()
        val similar = similar(items.filter { it.id !in inExact }, prints)
        return exact + similar
    }

    fun exact(items: List<MediaItem>, prints: Map<Long, Print>): List<Group> =
        items.filter { prints[it.id]?.content != null }
            .groupBy { it.sizeBytes to prints.getValue(it.id).content }
            .values
            .filter { it.size > 1 }
            .map { same ->
                val keep = same.minWith(compareBy<MediaItem>({ if (inCamera(it)) 0 else 1 }, { it.takenAtMillis }, { it.id }))
                Group(Kind.EXACT, order(same, keep), keep.id)
            }
            .sortedByDescending { g -> g.ids.maxOf { id -> items.first { it.id == id }.takenAtMillis } }

    fun similar(items: List<MediaItem>, prints: Map<Long, Print>): List<Group> {
        val photos = items.filter { !it.isVideo && prints[it.id]?.picture != null }.sortedBy { it.takenAtMillis }
        if (photos.size < 2) return emptyList()
        // Joined in pairs (only photos close in time are compared), then gathered into groups.
        val parent = IntArray(photos.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val n = parent[j]; parent[j] = r; j = n }
            return r
        }
        for (i in photos.indices) {
            val a = photos[i]
            val pa = prints.getValue(a.id).picture!!
            var j = i + 1
            while (j < photos.size && photos[j].takenAtMillis - a.takenAtMillis <= WINDOW_MS) {
                val pb = prints.getValue(photos[j].id).picture!!
                if (distance(pa, pb) <= MAX_BITS) parent[root(j)] = root(i)
                j++
            }
        }
        return photos.indices.groupBy { root(it) }.values
            .filter { it.size > 1 }
            .map { idx ->
                val same = idx.map { photos[it] }
                val keep = best(same, prints)
                Group(Kind.SIMILAR, order(same, keep), keep.id)
            }
            .sortedByDescending { g -> photos.first { it.id == g.keep }.takenAtMillis }
    }

    /** The one to keep among similar photos: sharpest, then most pixels, then newest. */
    fun best(same: List<MediaItem>, prints: Map<Long, Print>): MediaItem {
        val sharpest = same.maxOf { prints[it.id]?.sharpness ?: 0f }.coerceAtLeast(1e-6f)
        return same.maxWith(
            compareBy<MediaItem>(
                // Within 10% of the sharpest counts as just as sharp, so size decides between those.
                { if ((prints[it.id]?.sharpness ?: 0f) >= sharpest * 0.9f) 1 else 0 },
                { it.width.toLong() * it.height },
                { prints[it.id]?.sharpness ?: 0f },
                { it.takenAtMillis },
            ),
        )
    }

    /** How many of the 64 bits differ. */
    fun distance(a: Long, b: Long): Int = (a xor b).countOneBits()

    /**
     * The picture fingerprint of a 9 x 8 grey picture ([grey] row by row, 0 to 255): one bit for each pair of
     * neighbours across, set when the left one is brighter. The same photo saved smaller or a little brighter keeps
     * nearly the same bits.
     */
    fun differenceHash(grey: IntArray): Long {
        require(grey.size == 72) { "needs 9 x 8" }
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (grey[y * 9 + x] > grey[y * 9 + x + 1]) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    /** How sharp a small grey picture is: the spread of its edges (variance of a 3 x 3 Laplacian). */
    fun sharpness(grey: IntArray, w: Int, h: Int): Float {
        if (w < 3 || h < 3) return 0f
        var sum = 0.0
        var sq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val c = grey[y * w + x]
            val l = 4 * c - grey[y * w + x - 1] - grey[y * w + x + 1] - grey[(y - 1) * w + x] - grey[(y + 1) * w + x]
            sum += l
            sq += l.toDouble() * l
            n++
        }
        val mean = sum / n
        return (sq / n - mean * mean).toFloat()
    }

    /** Bytes freed by removing [remove] (each counted once). */
    fun bytes(items: List<MediaItem>, remove: Set<Long>): Long = items.filter { it.id in remove }.sumOf { it.sizeBytes }

    private fun inCamera(item: MediaItem): Boolean = item.folder?.startsWith("DCIM/Camera", ignoreCase = true) == true ||
        item.albumName.equals("Camera", ignoreCase = true)

    /** The keeper first, then the rest by time. */
    private fun order(same: List<MediaItem>, keep: MediaItem): List<Long> =
        listOf(keep.id) + same.filter { it.id != keep.id }.sortedBy { it.takenAtMillis }.map { it.id }

    /** A cache key for what was worked out about [item]: changes when the file does. */
    fun cacheKey(item: MediaItem): String = "${item.id}:${item.sizeBytes}:${item.takenAtMillis}"
}
