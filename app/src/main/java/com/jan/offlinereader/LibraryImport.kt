package com.jan.offlinereader

import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Reads our metadata-first archive without using ZIP paths as local file names. */
internal class LibraryImport(private val database: ReaderDatabase, private val repository: BookRepository,
    private val cache: File) {
    suspend fun read(input: InputStream): Int = withContext(Dispatchers.IO) {
        val staging = File(cache, "restore-${java.util.UUID.randomUUID()}")
        val added = mutableListOf<Book>()
        var committed = false
        try {
            check(staging.mkdirs()) { "Could not prepare temporary storage." }
            val restored = mutableListOf<Pair<Book, List<Bookmark>>>()
            val preferences: Preferences
            ZipInputStream(input.buffered()).use { zip ->
                require(zip.nextEntry?.name == "library.json") { "Choose an Offline Reader library export." }
                val metadata = ByteArrayOutputStream()
                copyLimited(zip, metadata, 8L * 1024 * 1024)
                val json = metadata.toString("UTF-8")
                // Bound JSON nesting before the recursive platform parser sees untrusted input.
                var depth = 0; var quoted = false; var escaped = false
                for (c in json) {
                    if (escaped) { escaped = false; continue }
                    if (quoted && c == '\\') { escaped = true; continue }
                    if (c == '"') quoted = !quoted
                    if (!quoted) {
                        if (c == '{' || c == '[') depth++
                        if (c == '}' || c == ']') depth--
                        require(depth in 0..16) { "Library metadata is invalid." }
                    }
                }
                val manifest = JSONObject(json)
                require(manifest.getInt("version") == 1) { "This library version is not supported." }
                val settings = manifest.getJSONObject("preferences")
                preferences = Preferences(fontSize = settings.getDouble("fontSize").toFloat(),
                    lineSpacing = settings.getDouble("lineSpacing").toFloat(), theme = settings.getString("theme"))
                require(preferences.fontSize in 14f..34f && preferences.lineSpacing in 1.2f..2.2f &&
                    preferences.theme in setOf("Light", "Sepia", "Dark")) { "Reading preferences are invalid." }
                val records = manifest.getJSONArray("books")
                require(records.length() <= 10_000) { "Too many books in this library." }
                val expected = linkedMapOf<String, JSONObject>()
                val hashes = mutableSetOf<String>()
                var marks = 0
                repeat(records.length()) { index ->
                    val record = records.getJSONObject(index)
                    val hash = record.getString("sha256")
                    val extension = record.getString("format").lowercase()
                    val path = "books/$hash/original.$extension"
                    require(hash.matches(Regex("[0-9a-f]{64}")) && extension in
                        setOf("epub", "pdf", "txt", "md", "html", "htm", "fb2") && record.getString("file") == path &&
                        hashes.add(hash)) { "Invalid or repeated book record." }
                    marks += record.getJSONArray("bookmarks").length()
                    require(marks <= 100_000) { "Too many bookmarks in this library." }
                    expected[path] = record
                }
                val files = mutableListOf<Pair<File, JSONObject>>()
                var total = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val record = expected.remove(entry.name) ?: error("Unexpected or repeated archive entry.")
                    val file = File(staging, "${files.size}.${record.getString("format").lowercase()}")
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.outputStream().use { output ->
                        total += copyLimited(zip, java.security.DigestOutputStream(output, digest),
                            minOf(1024L * 1024 * 1024, 8L * 1024 * 1024 * 1024 - total))
                    }
                    require(digest.digest().joinToString("") { "%02x".format(it) } == record.getString("sha256")) {
                        "A book in this archive is damaged."
                    }
                    files += file to record
                }
                require(expected.isEmpty()) { "The archive is missing original books." }
                for ((file, record) in files) {
                    // Finish bookkeeping even if the caller is cancelled as import returns.
                    val book = withContext(NonCancellable) {
                        repository.import(Uri.fromFile(file), "${record.getString("title")}.${file.extension}").also { if (!it.alreadyPresent) added += it.book }.book
                    }
                    coroutineContext.ensureActive()
                    val savedUnits = record.getInt("units")
                    require(savedUnits > 0) { "The saved book layout is invalid." }
                    // A newer app version may split the same book into a different number of passages.
                    // Keep approximate reading spots rather than refusing the whole backup.
                    val sameLayout = book.units == savedUnits
                    fun position(value: JSONObject): Int = value.getInt("position").let {
                        require(it in 0 until savedUnits) { "A reading position is invalid." }
                        if (sameLayout) it else (it.toLong() * book.units / savedUnits).toInt()
                    }
                    fun offset(value: JSONObject): Int = value.getInt("offset").let {
                        require(it >= 0) { "A reading offset is invalid." }
                        if (sameLayout) it else 0
                    }
                    val progress = record.getDouble("progress").toFloat()
                    require(progress in 0f..1f) { "Reading progress is invalid." }
                    val bookmarks = record.getJSONArray("bookmarks")
                    restored += book.copy(title = record.getString("title"), author = record.getString("author"),
                        position = position(record), offset = offset(record), progress = progress,
                        lastRead = record.getLong("lastRead"), imported = record.getLong("imported")) to
                        List(bookmarks.length()) { index -> bookmarks.getJSONObject(index).let {
                            Bookmark(bookId = book.id, position = position(it), offset = offset(it),
                                label = it.getString("label"), created = it.getLong("created"))
                        } }
                }
            }
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                database.withTransaction {
                    val dao = database.dao()
                    val addedIds = added.map { it.id }.toSet()
                    val existingMarks = dao.snapshotBookmarks().groupBy { it.bookId }
                    for ((book, marks) in restored) {
                        val current = if (book.id in addedIds) null else dao.book(book.id)
                        val merged = if (current != null && current.lastRead > book.lastRead) {
                            book.copy(position = current.position, offset = current.offset,
                                progress = current.progress, lastRead = current.lastRead)
                        } else book
                        dao.updateBook(merged)
                        if (current == null) {
                            dao.clearBookmarks(book.id)
                            marks.forEach { dao.bookmark(it) }
                        } else {
                            // Match the reader's duplicate rule, including marks added in this restore.
                            val offsets = mutableMapOf<Int, java.util.TreeSet<Long>>()
                            fun remember(mark: Bookmark) {
                                offsets.getOrPut(mark.position) { java.util.TreeSet() }.add(mark.offset.toLong())
                            }
                            existingMarks[book.id].orEmpty().forEach(::remember)
                            for (mark in marks) {
                                val nearby = offsets[mark.position]?.ceiling(mark.offset.toLong() - 19)
                                if (nearby == null || nearby >= mark.offset.toLong() + 20) {
                                    dao.bookmark(mark)
                                    remember(mark)
                                }
                            }
                        }
                    }
                    dao.preferences(preferences)
                }
                committed = true
            }
            restored.size
        } finally {
            runCatching { input.close() }
            withContext(NonCancellable) {
                if (!committed) added.forEach { repository.delete(it) }
                staging.deleteRecursively()
            }
        }
    }

    private suspend fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) return size
            size += count
            require(size <= limit) { "The archive exceeds the library size limits." }
            output.write(buffer, 0, count)
        }
    }
}
