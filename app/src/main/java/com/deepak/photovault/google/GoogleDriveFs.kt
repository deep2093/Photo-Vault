package com.deepak.photovault.google

import com.deepak.photovault.core.drive.ByteSource
import com.deepak.photovault.core.drive.DriveEntry
import com.deepak.photovault.core.drive.DriveFileSystem
import com.deepak.photovault.core.drive.DriveNotFound
import com.deepak.photovault.core.drive.DrivePaths
import com.deepak.photovault.core.drive.DriveSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Google said the token is no longer good (signed out, access taken back): sign in again. */
class GoogleSignInNeeded(message: String) : IOException(message)

/** The Google account's storage is full. */
class GoogleStorageFull(message: String) : IOException(message)

/**
 * One Google account's Drive, seen as the same kind of folder the family drive is (Phase 10, 13 Nov), so the backup
 * engine, its checks, Private encryption and the backup lists all work unchanged.
 *
 * Everything lives in one folder Pixallery makes, "Pixallery", in the account's My Drive. Pixallery asks only for
 * access to files it made itself ("drive.file"), so it can never see the person's other files. Folders are found by
 * name and kept in memory, so a backup asks Google for each folder once.
 */
class GoogleDriveFs(
    /** A current access token for this account; throws [GoogleSignInNeeded] when one can't be had without the person. */
    private val token: suspend () -> String,
    /** Called when Google refused a token, so the next [token] is a fresh one. */
    private val tokenRefused: suspend () -> Unit = {},
    /** The id of the Pixallery folder found before, if any (saved by the caller, saves a look-up). */
    private var rootId: String? = null,
    private val onRootId: (String) -> Unit = {},
) : DriveFileSystem {
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = HashMap<String, String>()
    private val lock = Mutex()

    // ---- The folder Pixallery keeps everything in ----

    private suspend fun root(): String = lock.withLock {
        rootId?.let { id ->
            if (ids[""] == null) ids[""] = id
            return@withLock id
        }
        val found = query("name = '$ROOT_NAME' and 'root' in parents and mimeType = '$FOLDER' and trashed = false").firstOrNull()?.id
        val id = found ?: createFolder("root", ROOT_NAME)
        rootId = id
        ids[""] = id
        onRootId(id)
        id
    }

    /** The id of [path], or null when nothing is there. */
    private suspend fun resolve(path: String): String? {
        val p = DrivePaths.check(path)
        val start = root()
        if (p.isEmpty()) return start
        ids[p]?.let { return it }
        var parent = start
        var walked = ""
        for (part in p.split('/')) {
            walked = DrivePaths.join(walked, part)
            val known = ids[walked]
            parent = known ?: (child(parent, part)?.id?.also { ids[walked] = it } ?: return null)
        }
        return parent
    }

    private suspend fun child(parentId: String, name: String): Entry? =
        query("'$parentId' in parents and name = '${quote(name)}' and trashed = false").firstOrNull()

    private suspend fun ensureFolder(path: String): String {
        val p = DrivePaths.check(path)
        var parent = root()
        if (p.isEmpty()) return parent
        var walked = ""
        for (part in p.split('/')) {
            walked = DrivePaths.join(walked, part)
            parent = ids[walked] ?: (child(parent, part)?.id ?: createFolder(parent, part)).also { ids[walked] = it }
        }
        return parent
    }

    // ---- The folder interface ----

    override suspend fun list(path: String): List<DriveEntry> {
        val id = resolve(path) ?: return emptyList()
        // Files are remembered too (14 Nov), so reading each backup list right after costs one call, not two.
        return query("'$id' in parents and trashed = false").map { e ->
            ids[DrivePaths.join(DrivePaths.check(path), e.name)] = e.id
            DriveEntry(e.name, e.isFolder, e.size)
        }
    }

    override suspend fun exists(path: String): Boolean = resolve(path) != null

    override suspend fun mkdirs(path: String) {
        ensureFolder(path)
    }

    override suspend fun read(path: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        readStream(path) { b, n -> out.write(b, 0, n) }
        return out.toByteArray()
    }

    override suspend fun createNew(path: String, bytes: ByteArray): Boolean = writeNew(path, com.deepak.photovault.core.drive.BytesSource(bytes))

    override suspend fun writeNew(path: String, source: ByteSource): Boolean {
        val p = DrivePaths.check(path)
        if (resolve(p) != null) return false
        val parent = ensureFolder(DrivePaths.parent(p))
        val id = upload(parent, p.substringAfterLast('/'), source)
        ids[p] = id
        return true
    }

    override suspend fun readStream(path: String, sink: (ByteArray, Int) -> Unit) {
        val id = resolve(path) ?: throw DriveNotFound(path)
        request("GET", "$API/files/$id?alt=media") { c ->
            if (c.responseCode == 404) throw DriveNotFound(path)
            ok(c)
            c.inputStream.use { input -> pump(input, sink) }
        }
    }

    override suspend fun delete(path: String) {
        val p = DrivePaths.check(path)
        val id = resolve(p) ?: return
        request("DELETE", "$API/files/$id") { c ->
            if (c.responseCode != 404) ok(c)
        }
        ids.remove(p)
    }

    override suspend fun deleteEmptyFolder(path: String) {
        val p = DrivePaths.check(path)
        if (p.isEmpty()) return
        val id = resolve(p) ?: return
        if (query("'$id' in parents and trashed = false", limit = 1).isNotEmpty()) return
        request("DELETE", "$API/files/$id") { c -> if (c.responseCode != 404) ok(c) }
        ids.keys.filter { it == p || it.startsWith("$p/") }.forEach { ids.remove(it) }
    }

    override suspend fun rename(from: String, to: String): Boolean {
        val src = DrivePaths.check(from)
        val dst = DrivePaths.check(to)
        if (resolve(dst) != null) return false
        val id = resolve(src) ?: throw DriveNotFound(from)
        val oldParent = resolve(DrivePaths.parent(src)) ?: throw DriveNotFound(from)
        val newParent = ensureFolder(DrivePaths.parent(dst))
        val body = buildJsonObject { put("name", dst.substringAfterLast('/')) }.toString().toByteArray()
        val moves = if (oldParent != newParent) "&addParents=$newParent&removeParents=$oldParent" else ""
        request("PATCH", "$API/files/$id?fields=id$moves", body, "application/json; charset=UTF-8") { c -> ok(c) }
        ids.remove(src)
        ids[dst] = id
        return true
    }

    override suspend fun folderSize(path: String): Long {
        val id = resolve(path) ?: return 0L
        var total = 0L
        val todo = ArrayDeque(listOf(id))
        while (todo.isNotEmpty()) {
            for (e in query("'${todo.removeFirst()}' in parents and trashed = false")) {
                if (e.isFolder) todo.addLast(e.id) else total += e.size
            }
        }
        return total
    }

    /** The account's own storage: Google shares it between Drive, Gmail and Google Photos. */
    override suspend fun space(): DriveSpace = request("GET", "$API/about?fields=storageQuota") { c ->
        ok(c)
        val q = json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject["storageQuota"]?.jsonObject
        val limit = q?.get("limit")?.jsonPrimitive?.content?.toLongOrNull() ?: Long.MAX_VALUE
        val used = q?.get("usage")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        DriveSpace(limit, (limit - used).coerceAtLeast(0L))
    }

    // ---- Google Drive calls ----

    private class Entry(val id: String, val name: String, val isFolder: Boolean, val size: Long)

    private suspend fun query(q: String, limit: Int = 1000): List<Entry> {
        val out = mutableListOf<Entry>()
        var page: String? = null
        do {
            val url = "$API/files?spaces=drive&pageSize=$limit&fields=nextPageToken,files(id,name,mimeType,size)&q=${enc(q)}" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            page = request("GET", url) { c ->
                ok(c)
                val body = json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
                body["files"]?.jsonArray?.forEach { f ->
                    val o = f.jsonObject
                    out += Entry(
                        id = o.str("id") ?: return@forEach,
                        name = o.str("name") ?: return@forEach,
                        isFolder = o.str("mimeType") == FOLDER,
                        size = o.str("size")?.toLongOrNull() ?: 0L,
                    )
                }
                body.str("nextPageToken")
            }
        } while (page != null && out.size < limit)
        return out
    }

    private suspend fun createFolder(parentId: String, name: String): String {
        val body = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER)
            put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
        }.toString().toByteArray()
        return request("POST", "$API/files?fields=id", body, "application/json; charset=UTF-8") { c ->
            ok(c)
            json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject.str("id") ?: throw IOException("Google gave no folder id")
        }
    }

    /**
     * Uploads [source] as a new file, in pieces (Google's "resumable" upload), so a big video never has to be held in
     * memory and its size needn't be known first. Returns the new file's id.
     */
    private suspend fun upload(parentId: String, name: String, source: ByteSource): String {
        val meta = buildJsonObject {
            put("name", name)
            put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
        }.toString().toByteArray()
        val session = request("POST", "$UPLOAD/files?uploadType=resumable&fields=id", meta, "application/json; charset=UTF-8") { c ->
            ok(c)
            c.getHeaderField("Location") ?: throw IOException("Google gave no upload address")
        }
        return source.use { src: ByteSource ->
            val chunks = Chunks(src)
            var sent = 0L
            var current = chunks.next()
            while (true) {
                val next = if (current.size < CHUNK) ByteArray(0) else chunks.next()
                val last = next.isEmpty()
                val end = sent + current.size - 1
                val range = when {
                    current.isEmpty() -> "bytes */$sent"
                    last -> "bytes $sent-$end/${sent + current.size}"
                    else -> "bytes $sent-$end/*"
                }
                val id = request("PUT", session, current, "application/octet-stream", range = range) { c ->
                    when (c.responseCode) {
                        200, 201 -> json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject.str("id")
                        308 -> null
                        else -> {
                            ok(c)
                            null
                        }
                    }
                }
                if (id != null) return@use id
                if (last) throw IOException("Google didn't finish the upload")
                sent += current.size
                current = next
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("Upload ended early")
        }
    }

    /** Hands out the file in pieces of exactly [CHUNK] bytes (a multiple of 256 KB, as Google asks); the last is shorter. */
    private class Chunks(private val src: ByteSource) {
        private val piece = ByteArray(64 * 1024)
        private var carry = ByteArray(0)

        fun next(): ByteArray {
            val buf = ByteArray(CHUNK)
            var n = carry.size.coerceAtMost(CHUNK)
            carry.copyInto(buf, 0, 0, n)
            carry = carry.copyOfRange(n, carry.size)
            while (n < CHUNK) {
                val r = src.read(piece)
                if (r < 0) break
                if (r == 0) continue
                val take = minOf(r, CHUNK - n)
                piece.copyInto(buf, n, 0, take)
                n += take
                if (take < r) carry = piece.copyOfRange(take, r)
            }
            return if (n == CHUNK) buf else buf.copyOf(n)
        }
    }

    /**
     * One call to Google, with the account's token. A refused token is renewed once; "too many requests" and
     * Google's own hiccups (500s) are tried again after a short wait.
     */
    private suspend fun <T> request(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
        range: String? = null,
        read: (HttpURLConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        var attempt = 0
        var renewed = false
        while (true) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                // Google answers 308 to each finished upload piece: that isn't a redirect to follow.
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                // HttpURLConnection can't send PATCH: Google accepts it as POST with this header.
                if (method == "PATCH") {
                    c.requestMethod = "POST"
                    c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                } else {
                    c.requestMethod = method
                }
                c.setRequestProperty("Authorization", "Bearer ${token()}")
                if (range != null) c.setRequestProperty("Content-Range", range)
                if (body != null) {
                    c.doOutput = true
                    if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                } else if (method == "POST" || method == "PUT") {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(0)
                    c.outputStream.close()
                }
                val code = c.responseCode
                if (code == 401 && !renewed) {
                    renewed = true
                    tokenRefused()
                    continue
                }
                if (code == 401) throw GoogleSignInNeeded("Google asked to sign in again")
                // Google's answer to a 403 can be read only once: look at it here.
                val refused = if (code == 403) errorText(c) else ""
                val slowDown = code == 429 || code >= 500 || "rateLimitExceeded" in refused || "userRateLimitExceeded" in refused
                if (slowDown && attempt < 5) {
                    attempt++
                    delay(1000L shl attempt)
                    continue
                }
                if ("storageQuotaExceeded" in refused) throw GoogleStorageFull("Google Drive is full")
                if (code == 403) throw IOException("Google Drive said 403: ${refused.take(200)}")
                return@withContext read(c)
            } finally {
                c.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException()
    }

    private fun errorText(c: HttpURLConnection): String = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()

    private fun ok(c: HttpURLConnection) {
        val code = c.responseCode
        if (code in 200..299) return
        val text = errorText(c)
        if ("storageQuotaExceeded" in text) throw GoogleStorageFull("Google Drive is full")
        throw IOException("Google Drive said $code: ${text.take(200)}")
    }

    private fun pump(input: InputStream, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) sink(buf, n)
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun quote(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        const val ROOT_NAME = "Pixallery"
        private const val FOLDER = "application/vnd.google-apps.folder"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"

        /** Each upload piece: 8 MB, a multiple of 256 KB as Google requires. */
        private const val CHUNK = 8 * 1024 * 1024
    }
}
