package com.deepak.photovault.feedback

import android.content.Context
import android.net.Uri
import com.deepak.photovault.core.household.FeedbackNote
import com.deepak.photovault.ui.shell.FeedbackFile
import com.deepak.photovault.ui.shell.FeedbackOutbox
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Feedback waiting for the drive (23 Oct), in the app's own storage: one folder per note with note.json and
 * copies of its photos and videos (picked files can stop being readable later). Removed once sent.
 */
class FileFeedbackOutbox(context: Context) : FeedbackOutbox {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "feedback-outbox")
    private val json = Json { ignoreUnknownKeys = true }

    override fun add(note: FeedbackNote, files: List<FeedbackFile>) {
        val folder = File(root, safe(note.id)).apply { mkdirs() }
        val kept = files.mapIndexedNotNull { i, f ->
            val copy = File(folder, "$i-${safe(f.name)}")
            runCatching {
                app.contentResolver.openInputStream(Uri.parse(f.uri))?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                f.copy(uri = Uri.fromFile(copy).toString())
            }.getOrNull()?.takeIf { copy.exists() }
        }
        // One line per file: where, name, size, video. Written before note.json, which marks the note complete.
        File(folder, "files.tsv").writeText(kept.joinToString("") { "${it.uri}\t${it.name.replace('\t', ' ')}\t${it.sizeBytes}\t${it.isVideo}\n" })
        File(folder, "note.json").writeText(json.encodeToString(FeedbackNote.serializer(), note))
    }

    override fun all(): List<Pair<FeedbackNote, List<FeedbackFile>>> =
        (root.listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { folder ->
            runCatching {
                val note = json.decodeFromString(FeedbackNote.serializer(), File(folder, "note.json").readText())
                val files = File(folder, "files.tsv").takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 4) null else FeedbackFile(p[0], p[1], p[2].toLongOrNull() ?: 0L, p[3] == "true")
                }
                note to files
            }.getOrNull()
        }

    override fun remove(id: String) {
        File(root, safe(id)).deleteRecursively()
    }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
}
