package com.jan.offlinereader

import android.app.Activity
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
    SideEffect {
        (view.context as? Activity)?.window?.let { applyReaderStatusBar(it, preferences.theme) }
    }
    MaterialTheme(colorScheme = readerColors(preferences.theme)) {
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            if (active == null) LibraryScreen(vm) else key(active!!.id) { ReaderScreen(active!!, vm, preferences) }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(vm: ReaderViewModel) {
    val books by vm.books.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Book?>(null) }
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
                    DropdownMenuItem(text = { Text("Export library") }, onClick = { libraryMenu = false; exporter.launch("Offline-Reader-library.zip") })
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
                if (filtered.isEmpty()) Text("No matching books.", Modifier.padding(24.dp))
                LazyVerticalGrid(columns = GridCells.Adaptive(155.dp), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 104.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    items(filtered, key = { it.id }) { book ->
                        Column {
                            Card(onClick = { vm.open(book) }, enabled = !busy, shape = RoundedCornerShape(10.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(.72f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                    if (book.cover != null) CoverImage(File(book.directory, book.cover), Modifier.fillMaxSize(), book.title)
                                    else Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(32.dp)); Spacer(Modifier.height(20.dp))
                                        Text(book.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                                    }
                                    Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.background.copy(alpha = .9f)) { Text(book.format, Modifier.padding(5.dp), style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(book.title, Modifier.weight(1f).padding(top = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                IconButton(onClick = { deleting = book }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "Remove ${book.title}") }
                            }
                            if (book.author.isNotBlank()) Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.fillMaxWidth().height(3.dp))
                            Text(if (book.lastRead == 0L) "Not started" else "${(book.progress * 100).toInt()}% read", Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    restoring?.let { uri -> AlertDialog(onDismissRequest = { restoring = null }, title = { Text("Restore library?") },
        text = { Text("Add missing books. For matching books, keep the most recently saved reading position and merge bookmarks. Reading preferences are replaced by the backup's. Other books stay. Export your current library first to keep a copy.") },
        confirmButton = { TextButton(onClick = { restoring = null; vm.importLibrary(uri) }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } }) }
    deleting?.let { book -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Remove book?") }, text = { Text("Remove “${book.title}” and its bookmarks from this library? The original file in Downloads or Files is kept.") }, confirmButton = { TextButton(onClick = { vm.delete(book); deleting = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
