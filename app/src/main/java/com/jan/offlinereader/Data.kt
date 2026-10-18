package com.jan.offlinereader

import android.app.Application
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books", indices = [Index(value = ["sha256"], unique = true)])
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val format: String,
    val directory: String,
    val sha256: String,
    val cover: String? = null,
    val units: Int,
    val position: Int = 0,
    val offset: Int = 0,
    val progress: Float = 0f,
    val lastRead: Long = 0,
    val imported: Long = System.currentTimeMillis()
)
@Entity(tableName = "bookmarks", foreignKeys = [ForeignKey(entity = Book::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")])
data class Bookmark(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String, val position: Int, val offset: Int, val label: String,
    val created: Long = System.currentTimeMillis())
@Entity(tableName = "preferences")
data class Preferences(@PrimaryKey val id: Int = 1, val fontSize: Float = 20f,
    val lineSpacing: Float = 1.6f, val theme: String = "Sepia")

@Dao
interface ReaderDao {
    @Query("SELECT * FROM books ORDER BY lastRead DESC, imported DESC") fun books(): Flow<List<Book>>
    @Query("SELECT * FROM books ORDER BY id") suspend fun snapshotBooks(): List<Book>
    @Query("SELECT * FROM bookmarks ORDER BY id") suspend fun snapshotBookmarks(): List<Bookmark>
    @Query("SELECT * FROM preferences WHERE id=1") suspend fun snapshotPreferences(): Preferences?
    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): Book?
    @Query("SELECT * FROM books WHERE sha256 = :hash") suspend fun byHash(hash: String): Book?
    @Insert suspend fun insert(book: Book)
    @Query("UPDATE books SET position=:position, offset=:offset, progress=:progress, lastRead=:time WHERE id=:id")
    suspend fun position(id: String, position: Int, offset: Int, progress: Float, time: Long)
    @Query("DELETE FROM books WHERE id=:id") suspend fun deleteBook(id: String)
    @Query("SELECT * FROM bookmarks WHERE bookId=:id ORDER BY position, offset") fun bookmarks(id: String): Flow<List<Bookmark>>
    @Insert suspend fun bookmark(bookmark: Bookmark)
    @Query("DELETE FROM bookmarks WHERE id=:id") suspend fun deleteBookmark(id: Long)
    @Query("SELECT * FROM preferences WHERE id=1") fun preferences(): Flow<Preferences?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun preferences(value: Preferences)
}
@Database(entities = [Book::class, Bookmark::class, Preferences::class], version = 1, exportSchema = true)
abstract class ReaderDatabase : RoomDatabase() { abstract fun dao(): ReaderDao }
class ReaderApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, ReaderDatabase::class.java, "reader.db").build() }
    // Saves remain alive when a reader ViewModel is cleared during navigation.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val positionSaver by lazy {
        PositionSaver(appScope) { p -> database.dao().position(p.bookId, p.index, p.offset, p.progress, System.currentTimeMillis()) }
    }
    val repository by lazy { BookRepository(this, database.dao()) }
}
