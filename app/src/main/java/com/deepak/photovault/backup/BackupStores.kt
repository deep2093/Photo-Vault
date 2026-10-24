package com.deepak.photovault.backup

import android.content.Context
import com.deepak.photovault.core.backup.BackupIndex
import com.deepak.photovault.core.backup.BackupRecord
import com.deepak.photovault.core.backup.BackupRun
import com.deepak.photovault.core.backup.BackupSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

internal val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** This phone's backup choices (not secret, so plain SharedPreferences). */
class BackupSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("backup", Context.MODE_PRIVATE)

    fun load(): BackupSettings = prefs.getString(KEY, null)
        ?.let { runCatching { backupJson.decodeFromString(BackupSettings.serializer(), it) }.getOrNull() }
        ?: BackupSettings()

    fun save(settings: BackupSettings) {
        prefs.edit().putString(KEY, backupJson.encodeToString(BackupSettings.serializer(), settings)).apply()
    }

    private companion object {
        const val KEY = "settings"
    }
}

/** The last backups, newest first, in the app's private storage. */
class BackupHistoryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "backup/history.json")
    private val serializer = ListSerializer(BackupRun.serializer())

    @Synchronized
    fun load(): List<BackupRun> = runCatching { backupJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun add(run: BackupRun, keep: Int) {
        val all = (listOf(run) + load()).take(keep)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "history.tmp")
        temp.writeText(backupJson.encodeToString(serializer, all))
        temp.renameTo(file)
    }
}

/**
 * What this phone knows is backed up: one JSON line per verified file, in the app's private storage. Only ever
 * appended to; if it is lost (app data cleared) the engine rebuilds it from the drive's manifests.
 */
class FileBackupIndex(context: Context) : BackupIndex {
    private val file = File(context.applicationContext.filesDir, "backup/index.jsonl")
    private val lock = Mutex()
    private var cache: MutableSet<String>? = null

    override suspend fun keys(): Set<String> = lock.withLock { loaded().toSet() }

    override suspend fun add(records: List<BackupRecord>) = lock.withLock {
        if (records.isEmpty()) return@withLock
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.appendText(records.joinToString("") { backupJson.encodeToString(BackupRecord.serializer(), it) + "\n" })
        }
        loaded().addAll(records.map { it.key })
    }

    override suspend fun remove(keys: Set<String>) = lock.withLock {
        if (keys.isEmpty() || !file.exists()) return@withLock
        withContext(Dispatchers.IO) {
            val kept = file.readLines().filter { line ->
                val key = runCatching { backupJson.decodeFromString(BackupRecord.serializer(), line).key }.getOrNull()
                key != null && key !in keys
            }
            val tmp = File(file.parentFile, "index.jsonl.tmp")
            tmp.writeText(kept.joinToString("") { it + "\n" })
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
        loaded().removeAll(keys)
    }

    private suspend fun loaded(): MutableSet<String> {
        cache?.let { return it }
        val keys = withContext(Dispatchers.IO) {
            if (!file.exists()) {
                mutableSetOf()
            } else {
                file.useLines { lines ->
                    lines.mapNotNull { line -> runCatching { backupJson.decodeFromString(BackupRecord.serializer(), line).key }.getOrNull() }.toMutableSet()
                }
            }
        }
        cache = keys
        return keys
    }
}
