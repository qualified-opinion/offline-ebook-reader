package com.jan.offlinereader

import android.net.Uri
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
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReaderApplication::class)
class LibraryImportTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var database: ReaderDatabase
    private lateinit var repository: BookRepository
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = BookRepository(RuntimeEnvironment.getApplication(), database.dao())
    }
    @After fun close() { database.close() }
    private suspend fun book(text: String = "A quiet book. café 😀\n".repeat(300)): Book {
        val file = folder.newFile().apply { writeText(text) }
        return repository.import(Uri.fromFile(file), "Quiet Book.txt").book
    }
    private suspend fun archive(): LinkedHashMap<String, ByteArray> {
        val output = ByteArrayOutputStream()
        LibraryExport(database).write(output)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() }
        }
        return entries
    }
    private suspend fun restore(entries: Map<String, ByteArray>): Int {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        return LibraryImport(database, repository, folder.root).read(ByteArrayInputStream(output.toByteArray()))
    }
    private fun changeMetadata(entries: MutableMap<String, ByteArray>, change: (JSONObject) -> Unit) {
        val json = JSONObject(String(entries.getValue("library.json"), Charsets.UTF_8))
        change(json); entries["library.json"] = json.toString().toByteArray(Charsets.UTF_8)
    }
    private suspend fun rejected(entries: Map<String, ByteArray>) {
        try { restore(entries); fail("Invalid archive accepted") } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        assertFalse(folder.root.listFiles()!!.any { it.name.startsWith("restore-") })
    }
    @Test fun exportWipeFilesAndDatabaseThenRestoreAllReadingState() = runBlocking {
        val original = book()
        val saved = original.copy(position = 2, offset = 44, progress = .42f, lastRead = 123, imported = 456)
        database.dao().updateBook(saved)
        val mark = Bookmark(bookId = saved.id, position = 3, offset = 12, label = "Remember", created = 789)
        database.dao().bookmark(mark)
        val preferences = Preferences(fontSize = 25f, lineSpacing = 1.8f, theme = "Dark")
        database.dao().preferences(preferences)
        val entries = archive()
        repository.delete(saved)
        assertFalse(File(saved.directory).exists())
        database.close(); setup()
        assertEquals(1, restore(entries))
        val restored = database.dao().snapshotBooks().single()
        assertEquals(saved.copy(id = restored.id, directory = restored.directory), restored)
        val restoredMark = database.dao().snapshotBookmarks().single()
        assertEquals(mark.copy(id = restoredMark.id, bookId = restored.id), restoredMark)
        assertEquals(preferences, database.dao().snapshotPreferences())
        assertArrayEquals(entries.getValue("books/${saved.sha256}/original.txt"), File(restored.directory, "original.txt").readBytes())
        assertEquals(saved.title, repository.document(restored).contents.first().title)
    }
    @Test fun repeatRestoreReplacesBookmarksAndKeepsUnrelatedBooks() = runBlocking {
        val saved = book()
        database.dao().bookmark(Bookmark(bookId = saved.id, position = 1, offset = 4, label = "Saved"))
        val entries = archive()
        val unrelated = book("Another book")
        restore(entries); restore(entries)
        assertEquals(setOf(saved.id, unrelated.id), database.dao().snapshotBooks().map { it.id }.toSet())
        assertEquals(1, database.dao().snapshotBookmarks().size)
    }
    @Test fun invalidLaterRecordRollsBackNewBooksAndPreservesExistingState() = runBlocking {
        val first = book(); val second = book("Different text. ".repeat(300))
        val entries = archive()
        repository.delete(first); repository.delete(second)
        val existing = book("Keep this book")
        changeMetadata(entries) { it.getJSONArray("books").getJSONObject(1).put("position", Int.MAX_VALUE) }
        rejected(entries)
        assertEquals(listOf(existing), database.dao().snapshotBooks())
        assertNull(database.dao().snapshotPreferences())
    }
    @Test fun traversalMissingAndCorruptOriginalsAreRejected() = runBlocking {
        val saved = book(); val entries = archive(); repository.delete(saved)
        val path = entries.keys.single { it != "library.json" }
        rejected(LinkedHashMap(entries).apply { put("../escape.txt", byteArrayOf(1)) })
        rejected(LinkedHashMap(entries).apply { remove(path) })
        rejected(LinkedHashMap(entries).apply { put(path, "Damaged".toByteArray()) })
        assertTrue(database.dao().snapshotBooks().isEmpty())
        assertFalse(File(folder.root.parentFile, "escape.txt").exists())
    }
    @Test fun unsupportedVersionAndUnsafePreferencesAreRejected() = runBlocking {
        val entries = archive()
        changeMetadata(entries) { it.put("version", 2) }; rejected(entries)
        changeMetadata(entries) { it.put("version", 1).getJSONObject("preferences").put("fontSize", 10000) }; rejected(entries)
    }
}
