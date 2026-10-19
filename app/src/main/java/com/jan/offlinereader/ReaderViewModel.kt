package com.jan.offlinereader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReaderViewModel internal constructor(application: Application, private val savedState: SavedStateHandle,
    private val dao: ReaderDao, private val saver: PositionSaver,
    val repository: BookRepository) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(application, savedState,
        (application as ReaderApplication).database.dao(), application.positionSaver, application.repository)

    private val app = application as ReaderApplication
    val books = dao.books().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val preferences = dao.preferences().map { it ?: Preferences() }.stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())
    private val _active = MutableStateFlow<Book?>(null)
    val active = _active.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks = _bookmarks.asStateFlow()
    private var bookmarkJob: Job? = null
    private val importMutex = Mutex()
    private var latest: ReadingPosition? = null

    init {
        viewModelScope.launch { saver.errors.collect { _message.value = it } }
        savedState.get<String>("activeBook")?.let { id -> viewModelScope.launch { dao.book(id)?.let { activate(it) } } }
    }
    private fun activate(book: Book) {
        _active.value = book
        latest = ReadingPosition(book.id, book.position, book.offset, book.progress)
        savedState["activeBook"] = book.id
        bookmarkJob?.cancel()
        bookmarkJob = viewModelScope.launch { dao.bookmarks(book.id).collect { _bookmarks.value = it } }
    }
    fun open(book: Book) { viewModelScope.launch {
        dao.book(book.id)?.let { stored ->
            val p = latest?.takeIf { it.bookId == book.id }
            activate(if (p == null) stored else stored.copy(position = p.index, offset = p.offset, progress = p.progress))
        }
    } }
    fun close() { flush(); _active.value = null; savedState["activeBook"] = null; bookmarkJob?.cancel() }
    fun import(uris: List<Uri>) {
        viewModelScope.launch {
            importMutex.withLock {
                _busy.value = true
                var imported = 0
                var duplicates = 0
                val errors = mutableListOf<String>()
                try {
                    uris.forEach { uri ->
                        var name = "Selected file"
                        try {
                            name = repository.displayName(uri)
                            val result = repository.import(uri, name)
                            if (result.alreadyPresent) duplicates++ else imported++
                        }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { errors += "Couldn’t import “$name”: ${friendly(e)}" }
                    }
                    _message.value = importSummary(imported, duplicates, errors)
                } finally { _busy.value = false }
            }
        }
    }
    fun exportLibrary(uri: Uri) { viewModelScope.launch {
        importMutex.withLock {
            _busy.value = true
            var complete = false
            try {
                saver.awaitPendingSaves()
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(uri, "w") ?: error("The export file could not be opened.")
                    LibraryExport(app.database).write(output)
                }
                complete = true
                _message.value = "Library exported."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reason = when (e) {
                    is SecurityException -> "Access to the destination was denied. Choose another folder."
                    is java.io.IOException -> "Check the destination and your device's free storage."
                    else -> e.message ?: "Please choose another destination and try again."
                }
                _message.value = "Couldn't export the library. $reason"
            }
            finally {
                if (!complete) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
                }
                _busy.value = false
            }
        }
    } }
    fun importLibrary(uri: Uri) { viewModelScope.launch {
        importMutex.withLock {
            _busy.value = true
            try {
                saver.awaitPendingSaves()
                latest = null
                val count = withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri) ?: error("The archive could not be opened.")
                    LibraryImport(app.database, repository, app.cacheDir).read(input)
                }
                _message.value = "Library restored: $count books."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _message.value = "Couldn't restore the library. ${e.message ?: "Check the file and free storage, then try again."}" }
            finally { _busy.value = false }
        }
    } }
    fun position(index: Int, offset: Int, progress: Float) {
        val book = _active.value ?: return
        latest = ReadingPosition(book.id, index.coerceIn(0, book.units - 1), offset.coerceAtLeast(0), progress.coerceIn(0f, 1f))
        flush()
    }
    fun flush() { latest?.let { saver.enqueue(it) } }
    override fun onCleared() { flush(); super.onCleared() }
    fun bookmark(contents: List<ContentsEntry> = emptyList()) {
        val p = latest ?: return
        if (_bookmarks.value.any { it.position == p.index && kotlin.math.abs(it.offset - p.offset) < 20 }) {
            _message.value = "This spot is already bookmarked."; return
        }
        val label = bookmarkLabel(_active.value?.format, p.index, p.progress, contents)
        viewModelScope.launch {
            dao.bookmark(Bookmark(bookId = p.bookId, position = p.index, offset = p.offset,
                label = label))
            _message.value = "Bookmark saved."
        }
    }
    fun deleteBookmark(bookmark: Bookmark) { viewModelScope.launch { dao.deleteBookmark(bookmark.id) } }
    fun settings(value: Preferences) { viewModelScope.launch { dao.preferences(value) } }
    fun delete(book: Book) { viewModelScope.launch {
        importMutex.withLock {
            try { repository.delete(book) } catch (e: Exception) { _message.value = friendly(e) }
        }
    } }
    fun error(e: Exception) { _message.value = friendly(e) }
    fun clearMessage() { _message.value = null }
    private fun friendly(e: Exception): String = when (e) {
        is SecurityException -> "This book may be password-protected, or access was denied. Try an unprotected local copy."
        is java.io.IOException -> "We couldn't read this book. Check that the file is complete and your device has free storage."
        else -> e.message ?: "We couldn't open this book. It may be damaged or protected. Try another copy."
    }
}
