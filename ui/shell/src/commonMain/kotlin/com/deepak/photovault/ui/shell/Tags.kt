package com.deepak.photovault.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deepak.photovault.core.backup.BackupSelection
import com.deepak.photovault.core.media.MediaItem

/**
 * Tags (7C-2, 8 Nov): your own words on photos ("Birthday", "Diwali", "Receipts"). Kept on the phone, by each photo's
 * lasting key (name, size and time), so a tag stays with the photo on the family drive and after a new phone.
 */
interface TagsPlatform {
    /** Photo key to its tags. */
    suspend fun load(): Map<String, List<String>> = emptyMap()

    suspend fun save(tags: Map<String, List<String>>) = Unit
}

@Stable
class TagsState(val platform: TagsPlatform = object : TagsPlatform {}) {
    /** Photo key to its tags, in the order they were added. */
    var byKey by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    var loaded by mutableStateOf(false)
        private set

    suspend fun refresh() {
        byKey = platform.load()
        loaded = true
    }

    fun tagsOf(item: MediaItem): List<String> = byKey[BackupSelection.key(item)].orEmpty()

    /** Adds [tag] to every photo in [items] (spelling of an existing tag wins, so "birthday" joins "Birthday"). */
    suspend fun add(items: List<MediaItem>, tag: String) {
        val clean = clean(tag) ?: return
        val name = all().firstOrNull { it.equals(clean, ignoreCase = true) } ?: clean
        val next = byKey.toMutableMap()
        items.forEach { item ->
            val key = BackupSelection.key(item)
            val now = next[key].orEmpty()
            if (now.none { it.equals(name, ignoreCase = true) }) next[key] = now + name
        }
        commit(next)
    }

    suspend fun remove(item: MediaItem, tag: String) {
        val key = BackupSelection.key(item)
        val next = byKey.toMutableMap()
        val left = next[key].orEmpty().filterNot { it.equals(tag, ignoreCase = true) }
        if (left.isEmpty()) next.remove(key) else next[key] = left
        commit(next)
    }

    /** Renames a tag on every photo; joins it with a tag of the new name if there is one. */
    suspend fun rename(from: String, to: String) {
        val clean = clean(to) ?: return
        val target = all().firstOrNull { it.equals(clean, ignoreCase = true) && !it.equals(from, ignoreCase = true) } ?: clean
        commit(byKey.mapValues { (_, tags) -> tags.map { if (it.equals(from, ignoreCase = true)) target else it }.distinctBy { it.lowercase() } })
    }

    /** Takes a tag off every photo (the photos stay). */
    suspend fun delete(tag: String) {
        commit(byKey.mapValues { (_, tags) -> tags.filterNot { it.equals(tag, ignoreCase = true) } }.filterValues { it.isNotEmpty() })
    }

    private suspend fun commit(next: Map<String, List<String>>) {
        byKey = next
        platform.save(next)
    }

    /** Every tag in use, A to Z. */
    fun all(): List<String> = byKey.values.flatten().distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /** Tags with the photos that have them, among [items]; most photos first, then A to Z. */
    fun list(items: List<MediaItem>): List<Pair<String, List<MediaItem>>> {
        val out = LinkedHashMap<String, MutableList<MediaItem>>()
        val names = HashMap<String, String>()
        items.forEach { item ->
            byKey[BackupSelection.key(item)]?.forEach { tag ->
                val low = tag.lowercase()
                val name = names.getOrPut(low) { tag }
                out.getOrPut(name) { mutableListOf() } += item
            }
        }
        return out.entries.map { it.key to it.value.sortedByDescending { i -> i.takenAtMillis } }
            .sortedWith(compareByDescending<Pair<String, List<MediaItem>>> { it.second.size }.thenBy { it.first.lowercase() })
    }

    /** Photos among [items] with [tag]. */
    fun photosWith(tag: String, items: List<MediaItem>): List<MediaItem> =
        items.filter { item -> byKey[BackupSelection.key(item)]?.any { it.equals(tag, ignoreCase = true) } == true }

    /** Search: photos with a tag that starts with, or has a word starting with, [query] (2 letters or more). */
    fun photosMatching(query: String, items: List<MediaItem>): Set<Long> {
        val q = query.trim().lowercase()
        if (q.length < 2 || byKey.isEmpty()) return emptySet()
        val matching = all().filter { tag -> tag.lowercase().split(' ').any { it.startsWith(q) } || tag.lowercase().startsWith(q) }.map { it.lowercase() }.toSet()
        if (matching.isEmpty()) return emptySet()
        return items.filter { item -> byKey[BackupSelection.key(item)]?.any { it.lowercase() in matching } == true }.map { it.id }.toSet()
    }

    companion object {
        const val MAX_LENGTH = 30

        /** A tidy tag: spaces trimmed and joined, at most [MAX_LENGTH] letters, no tabs or new lines. Null when empty. */
        fun clean(raw: String): String? =
            raw.replace(Regex("[\\t\\r\\n\\u001F\\u001E]"), " ").trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).trim().ifEmpty { null }
    }
}
