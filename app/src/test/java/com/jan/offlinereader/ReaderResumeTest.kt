package com.jan.offlinereader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReaderApplication::class)
class ReaderResumeTest {
    private lateinit var database: ReaderDatabase
    private lateinit var repository: BookRepository
    private lateinit var saver: PositionSaver
    private lateinit var worker: Job
    private val stores = mutableListOf<ViewModelStore>()
    private val app get() = RuntimeEnvironment.getApplication() as ReaderApplication

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(app, ReaderDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = BookRepository(app, database.dao())
        worker = SupervisorJob()
        saver = PositionSaver(CoroutineScope(worker + Dispatchers.IO)) { p ->
            database.dao().position(p.bookId, p.index, p.offset, p.progress, 1234)
        }
    }

    @After fun cleanup() = runBlocking {
        stores.forEach { it.clear() }
        withTimeout(5000) { saver.awaitPendingSaves() }
        worker.cancelAndJoin()
        database.close()
        Dispatchers.resetMain()
    }

    private fun reader(state: SavedStateHandle = SavedStateHandle()): Pair<ReaderViewModel, ViewModelStore> {
        val vm = ReaderViewModel(app, state, database.dao(), saver, repository)
        val store = ViewModelStore().also { it.put("reader", vm); stores += it }
        return vm to store
    }

    private suspend fun book(id: String = "one") = Book(id = id, title = id, format = "EPUB",
        directory = app.filesDir.absolutePath, sha256 = id, units = 100).also { database.dao().insert(it) }

    private suspend fun active(vm: ReaderViewModel): Book = withTimeout(5000) {
        vm.active.filterNotNull().first()
    }

    private fun assertSpot(book: Book, index: Int, offset: Int, progress: Float) {
        assertEquals(index, book.position)
        assertEquals(offset, book.offset)
        assertEquals(progress, book.progress)
    }

    @Test fun closeAndReopenRestoresSavedPassageOffsetAndProgress() = runBlocking {
        val book = book()
        val (vm) = reader()
        vm.open(book); active(vm)
        vm.position(42, 87, .42f)
        vm.close()
        assertNull(vm.active.value)
        withTimeout(5000) { saver.awaitPendingSaves() }
        assertSpot(database.dao().book(book.id)!!, 42, 87, .42f)
        // Opening another book discards the first book's in-memory latest position.
        val other = book("two")
        vm.open(other); active(vm)
        vm.close()
        vm.open(book)
        assertSpot(active(vm), 42, 87, .42f)
    }

    @Test fun clearingViewModelFlushesFinalSpotAndNewViewModelResumesIt() = runBlocking {
        val book = book()
        val state = SavedStateHandle()
        val (vm, store) = reader(state)
        vm.open(book); active(vm)
        vm.position(63, 109, .63f)
        withTimeout(5000) { saver.awaitPendingSaves() }
        // Reset the persisted row to isolate the extra save performed by onCleared.
        database.dao().position(book.id, 0, 0, 0f, 0)
        store.clear() // Deliberately do not call close().
        withTimeout(5000) { saver.awaitPendingSaves() }
        assertSpot(database.dao().book(book.id)!!, 63, 109, .63f)
        val restoredState = SavedStateHandle(mapOf("activeBook" to state.get<String>("activeBook")))
        val (recreated) = reader(restoredState)
        assertSpot(active(recreated), 63, 109, .63f)
    }

    @Test fun bookmarkPositionAndOffsetSurviveCloseAndFreshReader() = runBlocking {
        val book = book()
        val (vm, store) = reader()
        vm.open(book); active(vm)
        vm.position(24, 71, .24f)
        vm.bookmark(listOf(ContentsEntry("Chapter 2", 20)))
        val mark = withTimeout(5000) { database.dao().bookmarks(book.id).first { it.isNotEmpty() }.single() }
        vm.close(); store.clear()
        withTimeout(5000) { saver.awaitPendingSaves() }
        val (recreated) = reader()
        recreated.open(book)
        assertSpot(active(recreated), 24, 71, .24f)
        val restored = withTimeout(5000) { recreated.bookmarks.first { it.isNotEmpty() }.single() }
        assertEquals(mark, restored)
        assertEquals(24, restored.position)
        assertEquals(71, restored.offset)
        assertEquals("Chapter 2 · 24%", restored.label)
        // Follow the saved bookmark as the screen does, then reopen again.
        recreated.position(restored.position, restored.offset, .24f)
        recreated.close()
        recreated.open(book)
        assertSpot(active(recreated), 24, 71, .24f)
    }
}
