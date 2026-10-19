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
    @Test fun repeatRestoreMergesBookmarksAndKeepsUnrelatedBooks() = runBlocking {
        val saved = book()
        database.dao().bookmark(Bookmark(bookId = saved.id, position = 1, offset = 4, label = "Saved"))
        val entries = archive()
        database.dao().clearBookmarks(saved.id)
        database.dao().bookmark(Bookmark(bookId = saved.id, position = 2, offset = 40, label = "Phone"))
        val unrelated = book("Another book")
        database.dao().bookmark(Bookmark(bookId = unrelated.id, position = 0, offset = 0, label = "Other"))
        restore(entries); restore(entries)
        assertEquals(setOf(saved.id, unrelated.id), database.dao().snapshotBooks().map { it.id }.toSet())
        val marks = database.dao().snapshotBookmarks()
        assertEquals(setOf("Saved", "Phone"), marks.filter { it.bookId == saved.id }.map { it.label }.toSet())
        assertEquals(3, marks.size)
        assertEquals("Other", marks.single { it.bookId == unrelated.id }.label)
    }
    @Test fun newerPhoneReadingStateSurvivesOlderBackup() = runBlocking {
        val saved = book().copy(position = 1, offset = 8, progress = .1f, lastRead = 100)
        database.dao().updateBook(saved)
        val entries = archive()
        val further = saved.copy(position = 4, offset = 55, progress = .5f, lastRead = 200)
        database.dao().updateBook(further)
        restore(entries)
        assertEquals(further, database.dao().book(saved.id))
        // Newer means last saved, even if the reader moved back in the book.
        val phone = saved.copy(position = 0, offset = 3, progress = .02f, lastRead = 200)
        database.dao().updateBook(phone)
        restore(entries)
        assertEquals(phone, database.dao().book(saved.id))
    }
    @Test fun newerBackupReadingStateReplacesPhoneState() = runBlocking {
        val saved = book().copy(position = 3, offset = 44, progress = .7f, lastRead = 200)
        database.dao().updateBook(saved)
        val entries = archive()
        database.dao().updateBook(saved.copy(position = 1, offset = 2, progress = .1f, lastRead = 100))
        restore(entries)
        assertEquals(saved, database.dao().book(saved.id))
    }
    @Test fun equalSaveTimesUseBackupReadingState() = runBlocking {
        val saved = book().copy(position = 2, offset = 31, progress = .4f, lastRead = 100)
        database.dao().updateBook(saved)
        val entries = archive()
        database.dao().updateBook(saved.copy(position = 0, offset = 0, progress = 0f))
        restore(entries)
        assertEquals(saved, database.dao().book(saved.id))
    }
    @Test fun bookmarkMergeUsesReaderOffsetBoundaryAndKeepsPhoneLabels() = runBlocking {
        val saved = book()
        val entries = archive()
        database.dao().bookmark(Bookmark(bookId = saved.id, position = 1, offset = 100, label = "Phone", created = 123))
        changeMetadata(entries) { json ->
            val marks = json.getJSONArray("books").getJSONObject(0).getJSONArray("bookmarks")
            fun mark(position: Int, offset: Int, label: String) {
                marks.put(JSONObject().put("position", position).put("offset", offset)
                    .put("label", label).put("created", 456))
            }
            mark(1, 100, "Exact duplicate")
            mark(1, 81, "19 below")
            mark(1, 119, "19 above")
            mark(1, 80, "20 below")
            mark(1, 120, "20 above")
            mark(2, 100, "Different passage")
            mark(2, 110, "Duplicate within backup")
        }
        restore(entries); restore(entries)
        val marks = database.dao().snapshotBookmarks()
        assertEquals(setOf("Phone", "20 below", "20 above", "Different passage"), marks.map { it.label }.toSet())
        assertEquals(4, marks.size)
        assertEquals(123L, marks.single { it.label == "Phone" }.created)
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
    @Test fun changedBookLayoutStillRestoresWithApproximateSpots() = runBlocking {
        val saved = book()
        assertTrue(saved.units >= 2)
        database.dao().updateBook(saved.copy(position = 0, offset = 30, progress = .5f, lastRead = 123))
        val entries = archive()
        repository.delete(saved)
        // Simulate a backup from an app version that split this book into twice as many passages.
        changeMetadata(entries) { json ->
            val record = json.getJSONArray("books").getJSONObject(0)
            record.put("units", saved.units * 2).put("position", saved.units).put("offset", 30)
            record.getJSONArray("bookmarks").put(JSONObject().put("position", 2).put("offset", 9)
                .put("label", "Older layout").put("created", 1))
        }
        assertEquals(1, restore(entries))
        val restored = database.dao().snapshotBooks().single()
        assertEquals(saved.units, restored.units)
        assertEquals(saved.units / 2, restored.position)
        assertEquals(0, restored.offset)
        assertEquals(.5f, restored.progress)
        val mark = database.dao().snapshotBookmarks().single()
        assertEquals(1, mark.position)
        assertEquals(0, mark.offset)
        assertEquals("Older layout", mark.label)
    }
    @Test fun unsupportedVersionAndUnsafePreferencesAreRejected() = runBlocking {
        val entries = archive()
        changeMetadata(entries) { it.put("version", 2) }; rejected(entries)
        changeMetadata(entries) { it.put("version", 1).getJSONObject("preferences").put("fontSize", 10000) }; rejected(entries)
    }
}
