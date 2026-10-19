package com.jan.offlinereader

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** Portable version-1 archive: JSON metadata and validated original files, never private paths. */
internal class LibraryExport(private val database: ReaderDatabase) {
    suspend fun write(output: OutputStream) = withContext(Dispatchers.IO) {
        ZipOutputStream(output.buffered()).use { zip ->
            val dao = database.dao()
            val (books, bookmarks, preferences) = database.withTransaction {
                Triple(dao.snapshotBooks(), dao.snapshotBookmarks(), dao.snapshotPreferences() ?: Preferences())
            }
            require(books.size <= 10_000 && bookmarks.size <= 100_000) { "This library is too large to export." }
            val marks = bookmarks.groupBy { it.bookId }
            val records = JSONArray()
            val sources = mutableListOf<Pair<Book, File>>()
            for (book in books) {
                val extension = book.format.lowercase()
                require(extension in setOf("epub", "pdf", "txt", "md", "html", "htm", "fb2") &&
                    book.sha256.matches(Regex("[0-9a-f]{64}"))) { "A library record is invalid." }
                val original = File(book.directory, "original.$extension")
                require(original.isFile && original.length() <= 1024L * 1024 * 1024) { "An original book is missing or exceeds 1 GB." }
                sources += book to original
                val record = JSONObject().put("sha256", book.sha256).put("title", book.title).put("author", book.author)
                    .put("format", book.format).put("file", "books/${book.sha256}/original.$extension")
                    .put("units", book.units).put("position", book.position).put("offset", book.offset).put("progress", book.progress)
                    .put("lastRead", book.lastRead).put("imported", book.imported)
                record.put("bookmarks", JSONArray().apply { marks[book.id].orEmpty().forEach {
                    put(JSONObject().put("position", it.position).put("offset", it.offset).put("label", it.label).put("created", it.created))
                } })
                records.put(record)
            }
            val manifest = JSONObject().put("version", 1).put("books", records).put("preferences", JSONObject()
                .put("fontSize", preferences.fontSize).put("lineSpacing", preferences.lineSpacing).put("theme", preferences.theme))
                .toString().toByteArray(Charsets.UTF_8)
            require(manifest.size <= 8 * 1024 * 1024) { "Library metadata exceeds the 8 MB export limit." }
            zip.putNextEntry(ZipEntry("library.json")); zip.write(manifest); zip.closeEntry()
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            for ((book, original) in sources) {
                coroutineContext.ensureActive()
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry("books/${book.sha256}/${original.name}"))
                var size = 0L
                original.inputStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count; total += count
                        require(size <= 1024L * 1024 * 1024 && total <= 8L * 1024 * 1024 * 1024) { "Library files exceed the export limits." }
                        digest.update(buffer, 0, count); zip.write(buffer, 0, count)
                    }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == book.sha256) { "An original book has changed. Export was cancelled." }
                zip.closeEntry()
            }
        }
    }
}
