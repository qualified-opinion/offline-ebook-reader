package com.jan.offlinereader

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReaderApplication::class)
class LibraryExportTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var database: ReaderDatabase
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ReaderDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { database.close() }

    private suspend fun addBook(): Book {
        val directory = folder.newFolder()
        val original = File(directory, "original.txt").apply { writeText("A quiet book. café 😀") }
        val hash = MessageDigest.getInstance("SHA-256").digest(original.readBytes()).joinToString("") { "%02x".format(it) }
        return Book("local-id", "Quiet Book", "Jan", "TXT", directory.absolutePath, hash, units = 10,
            position = 3, offset = 44, progress = .42f, lastRead = 123, imported = 456).also { database.dao().insert(it) }
    }
    private suspend fun archive(): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        LibraryExport(database).write(output)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    @Test fun exportPreservesOriginalMetadataPositionsBookmarksAndPreferences() = runBlocking {
        val book = addBook()
        database.dao().bookmark(Bookmark(bookId = book.id, position = 4, offset = 12, label = "My bookmark", created = 789))
        database.dao().preferences(Preferences(fontSize = 25f, lineSpacing = 1.8f, theme = "Dark"))
        val entries = archive()
        val originalPath = "books/${book.sha256}/original.txt"
        assertEquals(setOf("library.json", originalPath), entries.keys)
        assertArrayEquals(File(book.directory, "original.txt").readBytes(), entries[originalPath])
        val manifest = JSONObject(String(entries.getValue("library.json"), Charsets.UTF_8))
        assertEquals(1, manifest.getInt("version"))
        assertFalse(manifest.toString().contains(folder.root.absolutePath))
        val record = manifest.getJSONArray("books").getJSONObject(0)
        assertEquals(book.sha256, record.getString("sha256")); assertEquals(originalPath, record.getString("file"))
        assertEquals("Quiet Book", record.getString("title")); assertEquals("Jan", record.getString("author"))
        assertEquals(10, record.getInt("units"))
        assertEquals(3, record.getInt("position")); assertEquals(44, record.getInt("offset"))
        assertEquals(.42, record.getDouble("progress"), .0001)
        assertEquals(123L, record.getLong("lastRead")); assertEquals(456L, record.getLong("imported"))
        val bookmark = record.getJSONArray("bookmarks").getJSONObject(0)
        assertEquals(4, bookmark.getInt("position")); assertEquals(12, bookmark.getInt("offset"))
        assertEquals("My bookmark", bookmark.getString("label")); assertEquals(789L, bookmark.getLong("created"))
        val preferences = manifest.getJSONObject("preferences")
        assertEquals(25.0, preferences.getDouble("fontSize"), .001)
        assertEquals(1.8, preferences.getDouble("lineSpacing"), .001)
        assertEquals("Dark", preferences.getString("theme"))
    }

    @Test fun emptyLibraryStillExportsDefaultPreferences() = runBlocking {
        val entries = archive()
        assertEquals(setOf("library.json"), entries.keys)
        val manifest = JSONObject(String(entries.getValue("library.json"), Charsets.UTF_8))
        assertEquals(0, manifest.getJSONArray("books").length())
        assertEquals("Sepia", manifest.getJSONObject("preferences").getString("theme"))
    }

    @Test fun changedOriginalIsRejected() = runBlocking {
        val book = addBook()
        File(book.directory, "original.txt").writeText("Changed bytes")
        try { archive(); fail("Changed original accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("has changed")) }
    }

    @Test fun missingOriginalIsRejected() = runBlocking {
        val book = addBook()
        File(book.directory, "original.txt").delete()
        try { archive(); fail("Missing original accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("missing")) }
    }

    @Test fun originalsOverOneGigabyteAreRejectedBeforeCopying() = runBlocking {
        val book = addBook()
        RandomAccessFile(File(book.directory, "original.txt"), "rw").use { it.setLength(1024L * 1024 * 1024 + 1) }
        try { archive(); fail("Oversized original accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("exceeds 1 GB")) }
    }

    @Test fun failedDestinationIsClosedAndReported() = runBlocking {
        var closed = false
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("Destination unavailable") }
            override fun close() { closed = true }
        }
        try { LibraryExport(database).write(output); fail("Write failure ignored") }
        catch (_: IOException) { }
        assertTrue(closed)
    }
}
