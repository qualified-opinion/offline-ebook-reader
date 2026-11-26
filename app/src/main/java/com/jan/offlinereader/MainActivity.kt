package com.jan.offlinereader

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ReaderApp() }
    }
}
/** Stored paper names. "System" (shown as "Match phone") follows the phone's dark mode. */
internal val paperThemes = listOf("Light", "Sepia", "Dark", "System")
internal fun paperLabel(theme: String) = if (theme == "System") "Match phone" else theme
/** The paper actually shown: "System" becomes Dark or Light; other names are kept. */
internal fun resolvePaper(theme: String, phoneIsDark: Boolean) =
    if (theme == "System") (if (phoneIsDark) "Dark" else "Light") else theme

fun readerColors(theme: String) = when (theme) {
    "Dark" -> darkColorScheme(primary = Color(0xFFBBCBB1), background = Color(0xFF181B18), surface = Color(0xFF20241F), onBackground = Color(0xFFD9DED5), onSurface = Color(0xFFD9DED5))
    "Light" -> lightColorScheme(primary = Color(0xFF526747), background = Color(0xFFFCFCF8), surface = Color(0xFFF5F5EE), onBackground = Color(0xFF292E26), onSurface = Color(0xFF292E26))
    else -> lightColorScheme(primary = Color(0xFF596B46), background = Color(0xFFF4EDDD), surface = Color(0xFFECE4D2), onBackground = Color(0xFF3D392F), onSurface = Color(0xFF3D392F))
}
internal fun applyReaderStatusBar(window: Window, theme: String) {
    // "Light status bars" means dark foreground icons on a light background.
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = theme != "Dark"
}

@Composable
fun ReaderApp(vm: ReaderViewModel = viewModel()) {
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current
    val paper = resolvePaper(preferences.theme, isSystemInDarkTheme())
    SideEffect {
        (view.context as? Activity)?.window?.let { applyReaderStatusBar(it, paper) }
    }
    MaterialTheme(colorScheme = readerColors(paper)) {
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            if (active == null) LibraryScreen(vm) else key(active!!.id) { ReaderScreen(active!!, vm, preferences) }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
/** The short status under a library card. */
internal fun readingStatus(book: Book): String = when {
    book.lastRead == 0L -> "Not started"
    book.progress >= .995f -> "Finished"
    else -> "${(book.progress * 100).toInt()}% read"
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(vm: ReaderViewModel) {
    val books by vm.books.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Book?>(null) }
    var details by remember { mutableStateOf<Book?>(null) }
    val context = LocalContext.current
    val viewPrefs = remember { context.getSharedPreferences("library_view", Context.MODE_PRIVATE) }
    var style by remember { mutableStateOf(ShelfStyle.entries.find { it.name == viewPrefs.getString("style", null) } ?: ShelfStyle.Covers) }
    var sort by remember { mutableStateOf(ShelfSort.entries.find { it.name == viewPrefs.getString("sort", null) } ?: ShelfSort.Recent) }
    // Spines and the colour sort need each book's main colour, worked out from its cover in the background.
    val needColours = style == ShelfStyle.Spines || sort == ShelfSort.Colour
    val colours by produceState(emptyMap<String, Int>(), books, needColours) {
        if (needColours) value = books.associate { it.id to BookColours.load(it) }
    }
    val colourOf: (Book) -> Int = { colours[it.id] ?: BookColours.now(it) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.import(it) }
    var libraryMenu by remember { mutableStateOf(false) }
    var restoring by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val restorer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoring = it }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::exportLibrary) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { TopAppBar(title = { Column { Text("Your library", fontFamily = FontFamily.Serif); Text("OFFLINE READER", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp) } },
            actions = {
                IconButton(onClick = { libraryMenu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, "Library actions") }
                DropdownMenu(expanded = libraryMenu, onDismissRequest = { libraryMenu = false }) {
                    DropdownMenuItem(text = { Text("Export library") }, onClick = { libraryMenu = false; exporter.launch(backupFileName(java.time.LocalDate.now())) })
                    DropdownMenuItem(text = { Text("Import library") }, onClick = { libraryMenu = false; restorer.launch(arrayOf("*/*")) })
                }
            }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { if (!busy) importer.launch(arrayOf("*/*")) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(if (busy) "Working…" else "Import books") }) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search title or author") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), shape = RoundedCornerShape(20.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            if (books.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(20.dp))
                    Text("A little space for your books", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
                    Spacer(Modifier.height(12.dp))
                    Text("Import a book from Files or Downloads. Your library, reading spots, and bookmarks stay on this device.", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp)); Text("EPUB · PDF · TXT · MD · HTML · FB2", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                val filtered = books.filter { it.title.contains(query, true) || it.author.contains(query, true) }
                ShelfControls(style, sort, onStyle = { style = it; viewPrefs.edit().putString("style", it.name).apply() },
                    onSort = { sort = it; viewPrefs.edit().putString("sort", it.name).apply() })
                if (filtered.isEmpty()) Text("No matching books.", Modifier.padding(24.dp))
                else Bookshelf(arrangeShelves(filtered, sort, colourOf), style, colourOf, enabled = !busy,
                    onOpen = { vm.open(it) }, onDetails = { details = it })
            }
        }
    }
    details?.let { book ->
        BookSheet(book, colourOf(book), enabled = !busy, onDismiss = { details = null },
            onOpen = { details = null; vm.open(book) }, onRemove = { details = null; deleting = book })
    }
    restoring?.let { uri -> AlertDialog(onDismissRequest = { restoring = null }, title = { Text("Restore library?") },
        text = { Text("Add missing books. For matching books, keep the most recently saved reading position and merge bookmarks. Reading preferences are replaced by the backup's. Other books stay. Export your current library first to keep a copy.") },
        confirmButton = { TextButton(onClick = { restoring = null; vm.importLibrary(uri) }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } }) }
    deleting?.let { book -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove book?") }, text = { Text("Remove “${book.title}” and its bookmarks from this library? The original file in Downloads or Files is kept.") }, confirmButton = { TextButton(onClick = { vm.delete(book); deleting = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
