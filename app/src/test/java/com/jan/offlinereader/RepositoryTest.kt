package com.jan.offlinereader

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReaderApplication::class)
class RepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication() as ReaderApplication
    private lateinit var database: ReaderDatabase
    private lateinit var sourceDir: File
    private lateinit var repository: BookRepository
    private val mimeTypes = mutableMapOf<String, String>()
    @Before fun setup() {
        database = Room.databaseBuilder(context, ReaderDatabase::class.java, "test.db").allowMainThreadQueries().build()
        sourceDir = File(context.cacheDir, "sources").apply { mkdirs() }
        repository = BookRepository(context, database.dao())
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = mimeTypes[uri.lastPathSegment] ?: "application/octet-stream"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(uri.lastPathSegment)) }
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(File(sourceDir, uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.books" })
        ShadowContentResolver.registerProviderInternal("test.books", provider)
    }
    @After fun teardown() { database.close(); context.deleteDatabase("test.db") }
    private fun uri(name: String) = Uri.parse("content://test.books/$name")

    @Test fun installedManifestRequestsNoInternetOrBroadStorageAccess() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertFalse("android.permission.INTERNET" in permissions)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in permissions)
        assertFalse("android.permission.READ_EXTERNAL_STORAGE" in permissions)
    }
    @Test fun importedCopySurvivesOriginalDeletionAndDuplicateIsNotAdded() = runBlocking {
        val source = File(sourceDir, "novel.txt").apply { writeText(("x".repeat(8191) + "😀\n" + "Hello, offline world!\n".repeat(2000))) }
        val book = repository.import(uri(source.name)).book
        val duplicate = repository.import(uri(source.name)).book
        assertEquals(book.id, duplicate.id)
        assertEquals(1, database.dao().books().first().size)
        source.delete()
        val document = repository.document(book)
        val reconstructed = document.blocks.map { repository.text(book, it) }.joinToString("")
        assertEquals(("x".repeat(8191) + "😀\n" + "Hello, offline world!\n".repeat(2000)), reconstructed)
    }
    @Test fun duplicateResultIsFlaggedAndStagingCopyIsRemoved() = runBlocking {
        File(sourceDir, "first.txt").writeText("The same book.")
        File(sourceDir, "renamed.txt").writeText("The same book.")
        val first = repository.import(uri("first.txt"))
        val root = File(context.filesDir, "books")
        val before = root.listFiles()!!.map { it.name }.toSet()
        val duplicate = repository.import(uri("renamed.txt"))
        assertFalse(first.alreadyPresent)
        assertTrue(duplicate.alreadyPresent)
        assertEquals(first.book.id, duplicate.book.id)
        assertEquals(before, root.listFiles()!!.map { it.name }.toSet())
        assertEquals("renamed.txt", repository.displayName(uri("renamed.txt")))
    }

    @Test fun progressPreferencesAndBookmarksSurviveDatabaseReopenAndCascadeOnRemoval() = runBlocking {
        File(sourceDir, "novel.txt").writeText("Long enough to scroll. ".repeat(500))
        val book = repository.import(uri("novel.txt")).book
        database.dao().position(book.id, 3, 84, .42f, 1234)
        database.dao().preferences(Preferences(fontSize = 25f, lineSpacing = 1.8f, theme = "Dark"))
        database.dao().bookmark(Bookmark(bookId = book.id, position = 3, offset = 84, label = "My spot"))
        database.close()
        database = Room.databaseBuilder(context, ReaderDatabase::class.java, "test.db").allowMainThreadQueries().build()
        val restored = database.dao().book(book.id)!!
        assertEquals(3, restored.position); assertEquals(84, restored.offset); assertEquals(.42f, restored.progress)
        assertEquals("Dark", database.dao().preferences().first()!!.theme)
        assertEquals(25f, database.dao().preferences().first()!!.fontSize)
        assertEquals(84, database.dao().bookmarks(book.id).first().single().offset)
        database.dao().deleteBook(book.id)
        assertTrue(database.dao().bookmarks(book.id).first().isEmpty())
    }
    @Test fun epubFollowsSpineAndResolvesNestedNavigationAnchors() = runBlocking {
        val entries = linkedMapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>""",
            "OPS/package.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>Quiet Book</dc:title><dc:creator>Jan</dc:creator></metadata><manifest><item id="two" href="text/two.xhtml"/><item id="one" href="text/one.xhtml"/><item id="nav" href="nav.xhtml" properties="nav"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""",
            "OPS/text/one.xhtml" to "<html><body><h1>First</h1><p>Alpha.</p><script>NEVER SHOW</script></body></html>",
            "OPS/text/two.xhtml" to "<html><body><h1>Second</h1><p id='spot'>Beta.</p></body></html>",
            "OPS/nav.xhtml" to """<html><body><nav epub:type="toc"><a href="text/one.xhtml">One</a><a href="text/two.xhtml#spot">The spot</a></nav></body></html>"""
        )
        writeEpub("test.epub", entries)
        val book = repository.import(uri("test.epub")).book
        assertEquals("Quiet Book", book.title); assertEquals("Jan", book.author)
        val document = repository.document(book)
        val text = document.blocks.map { repository.text(book, it) }.joinToString(" ")
        assertTrue(text.indexOf("Alpha") < text.indexOf("Beta")); assertFalse(text.contains("NEVER SHOW"))
        val spot = document.contents.single { it.title == "The spot" }
        assertEquals("Beta.", repository.text(book, document.blocks[spot.position]))
    }
    private fun writeEpub(filename: String, entries: Map<String, String>) {
        ZipOutputStream(File(sourceDir, filename).outputStream()).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }
    private suspend fun importedText(filename: String): String {
        val book = repository.import(uri(filename)).book
        return repository.document(book).blocks.map { repository.text(book, it) }.joinToString("")
    }

    @Test fun extensionlessEpubUsesProviderMimeType() = runBlocking {
        writeEpub("shared-book", linkedMapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""",
            "book.opf" to """<package><manifest><item id="chapter" href="chapter.xhtml"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            "chapter.xhtml" to "<html><body><p>Shared EPUB content.</p></body></html>"
        ))
        mimeTypes["shared-book"] = "application/epub+zip"
        val book = repository.import(uri("shared-book")).book
        assertEquals("EPUB", book.format)
        assertTrue(File(book.directory, "original.epub").exists())
        assertEquals("Shared EPUB content.", repository.text(book, repository.document(book).blocks.first()))
    }

    @Test fun mimeFallbackSupportsTextMarkdownHtmlAndFb2() = runBlocking {
        val cases = listOf(
            Triple("text/plain", "TXT", "Plain text."),
            Triple("text/markdown", "MD", "# Heading"),
            Triple("text/html", "HTML", "<html><body><p>Hello.</p></body></html>"),
            Triple("application/x-fictionbook+xml", "FB2", "<FictionBook><body><p>Story.</p></body></FictionBook>")
        )
        cases.forEachIndexed { index, (mime, format, text) ->
            val name = "shared-$index.unknown"
            File(sourceDir, name).writeText(text)
            mimeTypes[name] = mime
            assertEquals(format, repository.import(uri(name)).book.format)
        }
    }

    @Test fun supportedExtensionTakesPrecedenceOverMimeType() = runBlocking {
        File(sourceDir, "notes.txt").writeText("Read as plain text.")
        mimeTypes["notes.txt"] = "application/pdf"
        assertEquals("Read as plain text.", importedText("notes.txt"))
    }

    @Test fun unknownExtensionAndMimeRemainUnsupported() = runBlocking {
        File(sourceDir, "unknown").writeText("Not a supported book.")
        try {
            repository.import(uri("unknown"))
            fail("Unknown format was accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.startsWith("Choose an EPUB"))
        }
        assertTrue(database.dao().books().first().isEmpty())
    }

    @Test fun windows1252TextAndMarkdownPreserveAccentsAndSymbols() = runBlocking {
        listOf("txt", "md").forEach { extension ->
            val name = "legacy.$extension"
            val expected = "café – £5\nA different $extension book."
            File(sourceDir, name).writeBytes(expected.toByteArray(java.nio.charset.Charset.forName("windows-1252")))
            assertEquals(expected, importedText(name))
        }
    }

    @Test fun invalidUtf8NearEndTriggersWholeFileFallback() = runBlocking {
        val expected = "ASCII ".repeat(10000) + "café – £5"
        File(sourceDir, "long.txt").writeBytes(expected.toByteArray(java.nio.charset.Charset.forName("windows-1252")))
        assertEquals(expected, importedText("long.txt"))
    }

    @Test fun validUtf8KeepsUnicodeAndStripsLeadingBom() = runBlocking {
        val expected = "café – £5 😀 日本語"
        File(sourceDir, "unicode.txt").writeText("\uFEFF" + expected)
        assertEquals(expected, importedText("unicode.txt"))
    }

    @Test fun failedImportDoesNotLeaveLibraryRecordOrPrivateDirectory() = runBlocking {
        File(sourceDir, "broken.epub").writeText("not a zip archive")
        val root = File(context.filesDir, "books")
        val before = root.listFiles()?.size ?: 0
        try { repository.import(uri("broken.epub")); fail("Corrupt EPUB was accepted") } catch (_: java.io.IOException) { }
        assertTrue(database.dao().books().first().isEmpty())
        assertEquals(before, root.listFiles()?.size ?: 0)
    }
}
