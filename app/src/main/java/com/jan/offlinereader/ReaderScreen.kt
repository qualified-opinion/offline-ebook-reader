package com.jan.offlinereader

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(book: Book, vm: ReaderViewModel, preferences: Preferences) {
    var document by remember { mutableStateOf<TextDocument?>(null) }
    var pdf by remember { mutableStateOf<PdfSource?>(null) }
    var ratios by remember { mutableStateOf<List<Float>>(emptyList()) }
    val textCache = remember(book.id) { TextBlockCache() }
    val imageCache = remember(book.id) {
        CoverCache(minOf(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(1))
    }
    var failure by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(true) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var zoomPage by remember { mutableStateOf<Int?>(null) }
    val list = rememberLazyListState(book.position, book.offset)
    val scope = rememberCoroutineScope()
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val currentIndex by remember { derivedStateOf { list.firstVisibleItemIndex } }
    val currentProgress by remember { derivedStateOf { readingProgress(list, book.units) } }
    BackHandler { vm.close() }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, activity.window.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()); vm.flush() }
    }
    LaunchedEffect(book.id) {
        var opened: PdfSource? = null
        try {
            if (book.format == "PDF") {
                withContext(Dispatchers.IO) { opened = PdfSource(File(book.directory, "original.pdf")) }
                pdf = opened
                ratios = withContext(Dispatchers.IO) {
                    val values = org.json.JSONArray(File(book.directory, "pages.json").readText())
                    (0 until values.length()).map { values.getDouble(it).toFloat() }
                }
            } else {
                val loaded = vm.repository.document(book)
                document = loaded
                textCache.preload(list.firstVisibleItemIndex, loaded.blocks) { vm.repository.text(book, it) }
            }
            ready = true
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = "This book couldn't be opened. It may be damaged or protected. ${e.message.orEmpty()}" }
        finally { withContext(NonCancellable) { opened?.release() } }
    }
    
    DisposableEffect(lifecycleOwner, ready) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && ready) {
                vm.position(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, readingProgress(list, book.units))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer)
            if (ready) vm.position(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, readingProgress(list, book.units))
        }
    }
    LaunchedEffect(ready) {
        if (ready) snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, readingProgress(list, book.units)) }
            .filter { list.layoutInfo.totalItemsCount > 0 }.distinctUntilChanged().collect { (index, offset, progress) -> vm.position(index, offset, progress) }
    }
    LaunchedEffect(ready, document) {
        val blocks = document?.blocks
        if (ready && blocks != null) {
            snapshotFlow { list.firstVisibleItemIndex }.collectLatest { index ->
                textCache.preload(index, blocks) { vm.repository.text(book, it) }
            }
        }
    }
    LaunchedEffect(controls, panel, list.isScrollInProgress) {
        if (controls && panel == null) { delay(7000); controls = false }
    }
    fun jump(index: Int, offset: Int = 0) {
        jumpJob?.cancel()
        jumpJob = scope.launch {
            val target = index.coerceIn(0, book.units - 1)
            // Restore bookmark offsets against real text, rather than a loading placeholder.
            document?.let { loaded -> textCache.preload(target, loaded.blocks) { vm.repository.text(book, it) } }
            list.scrollToItem(target, offset)
            panel = null
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val margin = if (maxWidth >= 700.dp) 64.dp else 24.dp
        if (failure != null) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(40.dp))
                Text(failure!!, Modifier.padding(vertical = 20.dp))
                Button(onClick = { vm.close() }) { Text("Back to library") }
            }
        } else if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
        else {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { controls = !controls }) },
                contentPadding = PaddingValues(top = 76.dp, bottom = 132.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (book.format == "PDF") 12.dp else 12.dp)) {
                if (book.format == "PDF") {
                    items(book.units, key = { it }) { index ->
                        PdfPage(pdf!!, index, ratios[index], preferences.theme, Modifier.widthIn(max = 1000.dp).fillMaxWidth().padding(horizontal = if (maxWidth > 700.dp) 36.dp else 8.dp))
                    }
                } else {
                    itemsIndexed(document!!.blocks, key = { index, _ -> index }) { index, block ->
                        Box(Modifier.widthIn(max = 800.dp).fillMaxWidth().padding(horizontal = margin)) {
                            if (block.image != null) LocalImage(File(book.directory, block.image), Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 500.dp), "Book illustration", imageCache)
                            else TextBlock(book, block, index, textCache, vm, preferences)
                        }
                    }
                }
            }
            AnimatedVisibility(controls, Modifier.align(Alignment.TopCenter)) {
                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .98f), tonalElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.close() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to library") }
                        Text(book.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif)
                        IconButton(onClick = { controls = false }) { Icon(Icons.Default.CloseFullscreen, "Hide reading controls") }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                AnimatedVisibility(controls) {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .98f), tonalElevation = 2.dp) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                                IconButton(onClick = { panel = "Contents" }) { Icon(Icons.AutoMirrored.Filled.List, "Table of contents or go to page") }
                                IconButton(onClick = { vm.position(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, currentProgress); vm.bookmark(document?.contents.orEmpty()) }) { Icon(Icons.Default.BookmarkAdd, "Add bookmark") }
                                IconButton(onClick = { panel = "Bookmarks" }) { Icon(Icons.Default.Bookmarks, "View bookmarks") }
                                IconButton(onClick = { panel = "Appearance" }) { Icon(Icons.Default.TextFields, "Reading appearance") }
                                if (book.format == "PDF") IconButton(onClick = { zoomPage = currentIndex }) { Icon(Icons.Default.ZoomIn, "Zoom current page") }
                            }
                            Slider(value = currentProgress, onValueChange = { jump((it * (book.units - 1)).toInt()) }, modifier = Modifier.fillMaxWidth().height(28.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (book.format == "PDF") "Page ${currentIndex + 1} of ${book.units}" else "${(currentProgress * 100).toInt()}% read", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    IconButton(onClick = { controls = !controls }, modifier = Modifier.size(48.dp)) { Icon(if (controls) Icons.Default.ExpandMore else Icons.Default.ExpandLess, if (controls) "Hide reading controls" else "Show reading controls") }
                }
            }
        }
    }
    if (panel != null && ready) ModalBottomSheet(onDismissRequest = { panel = null }) {
        when (panel) {
            "Appearance" -> AppearancePanel(preferences, book.format == "PDF", vm::settings)
            "Bookmarks" -> {
                Text("Bookmarks", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                if (bookmarks.isEmpty()) Text("Save a spot with the bookmark button while reading.", Modifier.padding(24.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        ListItem(headlineContent = { Text(bookmark.label) }, modifier = Modifier.clickable { jump(bookmark.position, bookmark.offset) },
                            trailingContent = { IconButton(onClick = { vm.deleteBookmark(bookmark) }) { Icon(Icons.Default.DeleteOutline, "Delete bookmark") } })
                    }
                }
            }
            "Contents" -> {
                Text(if (book.format == "PDF") "Go to page" else "Contents", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
                if (book.format == "PDF") {
                    var page by remember { mutableStateOf((currentIndex + 1).toString()) }
                    OutlinedTextField(page, { page = it.filter(Char::isDigit) }, label = { Text("Page (1–${book.units})") }, singleLine = true, modifier = Modifier.padding(horizontal = 24.dp))
                    Button(onClick = { page.toIntOrNull()?.let { jump(it - 1) } }, enabled = page.toIntOrNull() in 1..book.units, modifier = Modifier.padding(24.dp)) { Text("Go") }
                } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(document!!.contents) { entry -> ListItem(headlineContent = { Text(entry.title.ifBlank { "Section" }) }, modifier = Modifier.clickable { jump(entry.position) }) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    zoomPage?.let { index ->
        Dialog(onDismissRequest = { zoomPage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            PdfZoom(pdf!!, index, ratios[index], preferences.theme, onDone = { zoomPage = null })
        }
    }
}

private fun readingProgress(list: LazyListState, units: Int): Float {
    if (list.layoutInfo.totalItemsCount == 0) return 0f
    val first = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
    val fraction = if (first != null && first.size > 0) list.firstVisibleItemScrollOffset.toFloat() / first.size else 0f
    if (!list.canScrollForward && list.firstVisibleItemIndex > 0) return 1f
    return ((list.firstVisibleItemIndex + fraction) / units.coerceAtLeast(1)).coerceIn(0f, 1f)
}
@Composable
private fun TextBlock(book: Book, block: Block, index: Int, cache: TextBlockCache, vm: ReaderViewModel, preferences: Preferences) {
    var failed by remember(book.id, index, block) { mutableStateOf(false) }
    val text by produceState(cache.cached(index), book.id, index, block) {
        try { value = cache.load(index) { vm.repository.text(book, block) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed = true; vm.error(e) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthSp = with(density) { maxWidth.toSp().value }
        val estimate = textPlaceholderHeight(block, widthSp, preferences.fontSize, preferences.lineSpacing)
        if (text == null) Box(Modifier.fillMaxWidth().height(if (failed) 60.dp else with(density) { estimate.sp.toDp() }),
            contentAlignment = Alignment.Center) {
            if (failed) Text("This passage couldn't be loaded.") else CircularProgressIndicator(Modifier.size(20.dp))
        }
        else Text(text!!, fontFamily = FontFamily.Serif, fontSize = (preferences.fontSize * if (block.heading) 1.25f else 1f).sp,
            lineHeight = (preferences.fontSize * preferences.lineSpacing).sp,
            fontWeight = if (block.heading) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground)
    }
}
@Composable
internal fun PdfPage(source: PdfSource, index: Int, ratio: Float, theme: String, modifier: Modifier) {
    var failed by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.aspectRatio(1f / ratio).background(if (theme == "Dark") Color(0xFF222222) else Color.White)) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<Bitmap?>(null, source, index, width) {
            try { value = source.render(index, width) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        val filter = pdfColorFilter(theme)
        bitmap?.let { Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = filter) }
            ?: if (failed) Text("Page ${index + 1} couldn't be rendered. Try reopening this book.", Modifier.align(Alignment.Center).padding(20.dp)) else CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}
internal fun pdfColorFilter(theme: String): ColorFilter? = when (theme) {
            "Dark" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(-.85f,0f,0f,0f,240f, 0f,-.85f,0f,0f,240f, 0f,0f,-.85f,0f,240f, 0f,0f,0f,1f,0f)))
            "Sepia" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(.96f,0f,0f,0f,0f, 0f,.93f,0f,0f,0f, 0f,0f,.86f,0f,0f, 0f,0f,0f,1f,0f)))
            else -> null
        }

@Composable
private fun AppearancePanel(value: Preferences, isPdf: Boolean, save: (Preferences) -> Unit) {
    var font by remember(value.fontSize) { mutableFloatStateOf(value.fontSize) }
    var spacing by remember(value.lineSpacing) { mutableFloatStateOf(value.lineSpacing) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text("Reading appearance", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        Text("Paper")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Light", "Sepia", "Dark").forEach { theme -> FilterChip(selected = value.theme == theme, onClick = { save(value.copy(theme = theme)) }, label = { Text(theme) }) }
        }
        Spacer(Modifier.height(20.dp))
        if (isPdf) Text("PDF pages keep their original layout. Use Zoom to enlarge a page.")
        else {
            Text("Font size · ${font.toInt()} sp")
            Slider(font, { font = it }, valueRange = 14f..34f, steps = 19, onValueChangeFinished = { save(value.copy(fontSize = font)) })
            Text("Line spacing · ${"%.1f".format(spacing)}")
            Slider(spacing, { spacing = it }, valueRange = 1.2f..2.2f, steps = 9, onValueChangeFinished = { save(value.copy(lineSpacing = spacing)) })
            Text("A quiet page, a good book, and a little time.", Modifier.padding(vertical = 16.dp), fontFamily = FontFamily.Serif, fontSize = font.sp, lineHeight = (font * spacing).sp)
        }
    }
}
