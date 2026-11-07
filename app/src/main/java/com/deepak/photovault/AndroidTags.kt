package com.deepak.photovault

import android.content.Context
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DrivePaths
import com.deepak.photovault.ui.shell.TagsPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tags on Android (7C-2, 8 Nov): one small file on the phone, `files/tags/tags.tsv` (photo key, tab, tags). A new
 * phone with no tags brings them back once from the family drive's encrypted copy (see [TagsDriveCopy]).
 */
class AndroidTags(context: Context) : TagsPlatform {
    private val app = context.applicationContext

    override suspend fun load(): Map<String, List<String>> {
        val here = TagsFile.read(app)
        if (here.isNotEmpty() || TagsFile.triedDrive(app)) return here
        TagsFile.markTriedDrive(app)
        runCatching {
            com.deepak.photovault.backup.BackupGraph.get(app).runner.onMyDrive { fs, userId ->
                TagsDriveCopy.restore(app, fs, userId)
                ""
            }
        }
        return TagsFile.read(app)
    }

    override suspend fun save(tags: Map<String, List<String>>) = TagsFile.write(app, tags)
}

internal object TagsFile {
    private val lock = Mutex()
    private const val SEP = '\u001F'

    private fun file(context: Context) = File(context.filesDir, "tags/tags.tsv").apply { parentFile?.mkdirs() }

    private fun prefs(context: Context) = context.getSharedPreferences("tags", Context.MODE_PRIVATE)

    fun triedDrive(context: Context) = prefs(context).getBoolean("tried_drive", false)

    fun markTriedDrive(context: Context) = prefs(context).edit().putBoolean("tried_drive", true).apply()

    fun changedSinceUpload(context: Context) = prefs(context).getBoolean("changed", false)

    fun setChanged(context: Context, changed: Boolean) = prefs(context).edit().putBoolean("changed", changed).apply()

    suspend fun read(context: Context): Map<String, List<String>> = lock.withLock {
        withContext(Dispatchers.IO) { parse(runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()) }
    }

    suspend fun write(context: Context, tags: Map<String, List<String>>) = lock.withLock {
        withContext(Dispatchers.IO) {
            val f = file(context)
            val tmp = File(f.parentFile, "tags.tsv.tmp")
            tmp.writeText(text(tags))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            setChanged(context, true)
        }
    }

    fun text(tags: Map<String, List<String>>): String = buildString {
        tags.forEach { (key, list) ->
            if (list.isEmpty() || '\t' in key || '\n' in key) return@forEach
            append(key).append('\t').append(list.joinToString(SEP.toString())).append('\n')
        }
    }

    fun parse(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        text.lineSequence().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val list = line.substring(tab + 1).split(SEP).filter { it.isNotBlank() }
            if (list.isNotEmpty()) out[line.substring(0, tab)] = list
        }
        return out
    }
}

/**
 * Tags' encrypted copy on the family drive (7C-2): sealed with a key made from the Secured folder's key, like People,
 * so the drive never holds them readable. `users/<id>/tags/tags-<time>.pxt`, the newest one is used. Without a Secured
 * folder there is no key, and the tags stay on the phone only.
 */
object TagsDriveCopy {
    private const val PURPOSE = "tags-copy"

    private fun folder(userId: String) = DrivePaths.join(com.deepak.photovault.core.household.HouseholdStore.userFolder(userId), "tags")

    /** Sends the tags up when they changed since the last time. Never throws for a missing key: does nothing. */
    suspend fun upload(context: Context, drive: DriveFileSystem, userId: String) {
        if (!TagsFile.changedSinceUpload(context)) return
        val vault = SecuredKeys.peopleVault(context) ?: return
        val plain = TagsFile.text(TagsFile.read(context)).toByteArray()
        val dir = folder(userId)
        drive.mkdirs(dir)
        val old = drive.list(dir).filter { !it.isFolder && it.name.startsWith("tags-") && it.name.endsWith(".pxt") }.map { it.name }
        var at = System.currentTimeMillis()
        while (!drive.createNew(DrivePaths.join(dir, "tags-$at.pxt"), vault.sealRecord(plain, PURPOSE))) at++
        old.forEach { drive.delete(DrivePaths.join(dir, it)) }
        TagsFile.setChanged(context, false)
    }

    /** Brings the newest copy home when this phone has no tags. */
    suspend fun restore(context: Context, drive: DriveFileSystem, userId: String) {
        if (TagsFile.read(context).isNotEmpty()) return
        val vault = SecuredKeys.peopleVault(context) ?: return
        val dir = folder(userId)
        if (!drive.exists(dir)) return
        val newest = drive.list(dir).filter { !it.isFolder && it.name.startsWith("tags-") && it.name.endsWith(".pxt") }.maxByOrNull { it.name } ?: return
        val bytes = java.io.ByteArrayOutputStream()
        drive.readStream(DrivePaths.join(dir, newest.name)) { b, n -> bytes.write(b, 0, n) }
        val plain = vault.openRecord(bytes.toByteArray(), PURPOSE) ?: return
        val tags = TagsFile.parse(plain.decodeToString())
        if (tags.isNotEmpty()) {
            TagsFile.write(context, tags)
            TagsFile.setChanged(context, false)
        }
    }
}
