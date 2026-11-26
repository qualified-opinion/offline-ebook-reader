package com.jan.offlinereader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Wood and wall colours for the current paper. */
private data class Wood(val wall: Color, val top: Color, val face: Color, val edge: Color)

@Composable
private fun wood(): Wood {
    val background = MaterialTheme.colorScheme.background
    return if (background.luminance() < .5f)
        Wood(Color(0xFF1F2320), Color(0xFF74533A), Color(0xFF5A3E29), Color(0xFF3A281A))
    else Wood(lerpTo(background, Color(0xFF8A6A40), .08f), Color(0xFFC99A6A), Color(0xFFA8754A), Color(0xFF7D5434))
}
private fun lerpTo(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

/** Dark ink on light bindings, light ink on dark ones. */
private fun inkOn(argb: Int) = if (luminance(argb) > .55f) Color(0xFF2A251C) else Color(0xFFF6F0E2)

private fun describe(book: Book) =
    listOf(book.title, book.author.takeIf { it.isNotBlank() }?.let { "by $it" }, readingStatus(book)).filterNotNull().joinToString(", ")

/** Sort menu and the cover/spine switch, shown under the search box. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfControls(style: ShelfStyle, sort: ShelfSort, onStyle: (ShelfStyle) -> Unit, onSort: (ShelfSort) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            var open by remember { mutableStateOf(false) }
            TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "Sort by ${sort.label}" }) {
                Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(sort.label)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ShelfSort.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = { open = false; onSort(option) },
                        trailingIcon = if (option == sort) { { Icon(Icons.Default.Check, "Selected") } } else null)
                }
            }
        }
        SingleChoiceSegmentedButtonRow {
            ShelfStyle.entries.forEachIndexed { i, option ->
                SegmentedButton(selected = style == option, onClick = { onStyle(option) },
                    shape = SegmentedButtonDefaults.itemShape(i, ShelfStyle.entries.size), icon = {}) {
                    if (option == ShelfStyle.Covers) Icon(Icons.Default.GridView, "Show covers")
                    else Icon(Icons.Default.ViewWeek, "Show spines")
                }
            }
        }
    }
}

/**
 * The library drawn as a bookcase. Covers: tap opens the book, long-press shows details.
 * Spines: tap shows details (with the cover), since the cover isn't visible on the shelf.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bookshelf(groups: List<ShelfGroup>, style: ShelfStyle, colourOf: (Book) -> Int, enabled: Boolean,
                       onOpen: (Book) -> Unit, onDetails: (Book) -> Unit, modifier: Modifier = Modifier) {
    val wood = wood()
    BoxWithConstraints(modifier.fillMaxSize().background(wood.wall)) {
        val inner = maxWidth - 32.dp
        val perShelf = coversPerShelf(inner.value)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 104.dp)) {
            groups.forEach { group ->
                group.label?.let { label ->
                    item(key = "label-$label", contentType = "label") {
                        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp), verticalAlignment = Alignment.Bottom) {
                            Text(label, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            Text("${group.books.size}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .65f))
                        }
                    }
                }
                val shelves = if (style == ShelfStyle.Covers) group.books.chunked(perShelf)
                    else packShelves(group.books.map { spineThickness(it.units) }, inner.value, 2f).map { it.map(group.books::get) }
                itemsIndexed(shelves, key = { _, shelf -> "$style-${shelf.first().id}" }, contentType = { _, _ -> style }) { i, shelf ->
                    if (style == ShelfStyle.Covers) CoverShelf(shelf, perShelf, colourOf, enabled, onOpen, onDetails, wood)
                    else SpineShelf(shelf, inner, i == shelves.lastIndex, colourOf, enabled, onDetails, wood)
                }
            }
        }
    }
}

@Composable
private fun Plank(wood: Wood) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(12.dp).clip(RoundedCornerShape(2.dp)).background(
        Brush.verticalGradient(0f to wood.top, .25f to wood.top, .25f to wood.face, .75f to wood.face, .75f to wood.edge, 1f to wood.edge)))
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(8.dp).background(
        Brush.verticalGradient(listOf(Color.Black.copy(alpha = .16f), Color.Transparent))))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverShelf(books: List<Book>, perShelf: Int, colourOf: (Book) -> Int, enabled: Boolean,
                       onOpen: (Book) -> Unit, onDetails: (Book) -> Unit, wood: Wood) {
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            books.forEach { book ->
                val shape = RoundedCornerShape(topStart = 1.dp, bottomStart = 1.dp, topEnd = 3.dp, bottomEnd = 3.dp)
                Box(Modifier.weight(1f).aspectRatio(.68f).shadow(3.dp, shape).clip(shape)
                    .combinedClickable(enabled = enabled, onClickLabel = "Open book", onLongClickLabel = "Book details",
                        onLongClick = { onDetails(book) }, onClick = { onOpen(book) })
                    .semantics { contentDescription = describe(book) }) {
                    BookFace(book, colourOf(book))
                    // The hinge: a soft shadow down the left edge, like a hardback.
                    Box(Modifier.fillMaxHeight().width(5.dp).background(
                        Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .22f), Color.Transparent))))
                    if (readingState(book) == ReadingState.Reading)
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(book.progress.coerceIn(.04f, 1f)).height(3.dp)
                            .background(MaterialTheme.colorScheme.primary))
                }
            }
            repeat(perShelf - books.size) { Spacer(Modifier.weight(1f)) }
        }
        Plank(wood)
    }
}

/** Draws text top-to-bottom, the way English spines read. */
private fun Modifier.sideways() = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
    layout(placeable.height, placeable.width) {
        placeable.place(-(placeable.width - placeable.height) / 2, (placeable.width - placeable.height) / 2)
    }
}.rotate(90f)

private val shelfHeight = 168.dp

@Composable
private fun SpineShelf(books: List<Book>, inner: Dp, last: Boolean, colourOf: (Book) -> Int, enabled: Boolean,
                       onDetails: (Book) -> Unit, wood: Wood) {
    val used = books.sumOf { spineThickness(it.units).toDouble() } + 2.0 * (books.size - 1)
    Column {
        Row(Modifier.fillMaxWidth().height(shelfHeight + 16.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            books.forEach { Spine(it, colourOf(it), enabled, onDetails) }
            if (last && inner.value - used >= 24) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.width(12.dp).height(72.dp).background(wood.edge, RoundedCornerShape(topStart = 2.dp, topEnd = 10.dp)))
            }
        }
        Plank(wood)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Spine(book: Book, colour: Int, enabled: Boolean, onDetails: (Book) -> Unit) {
    val height = shelfHeight * spineHeightShare(book.id)
    val ink = inkOn(colour)
    Box(Modifier.width(spineThickness(book.units).dp).height(height + 10.dp)) {
        // Books you're reading have a bookmark sticking out of the top.
        if (readingState(book) == ReadingState.Reading)
            Box(Modifier.align(Alignment.TopCenter).width(6.dp).height(18.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height)
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp, bottomStart = 1.dp, bottomEnd = 1.dp))
            .background(Color(colour))
            .drawBehind {
                drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .28f), .28f to Color.White.copy(alpha = .14f),
                    .7f to Color.Black.copy(alpha = .04f), 1f to Color.Black.copy(alpha = .3f)))
                val band = 1.dp.toPx()
                drawRect(Color.White.copy(alpha = .3f), Offset(0f, 7.dp.toPx()), Size(size.width, band))
                drawRect(Color.White.copy(alpha = .3f), Offset(0f, size.height - 8.dp.toPx()), Size(size.width, band))
            }
            .combinedClickable(enabled = enabled, onClickLabel = "Book details", onClick = { onDetails(book) })
            .semantics { contentDescription = describe(book) }) {
            Box(Modifier.fillMaxSize().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                Text(book.title, Modifier.sideways(), color = ink, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A book's front: its cover image, or a cloth-style binding with the title when it has none. */
@Composable
internal fun BookFace(book: Book, colour: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        if (book.cover != null) CoverImage(File(book.directory, book.cover), Modifier.fillMaxSize(), book.title,
            ContentScale.Crop) { PlainCover(book, colour) }
        else PlainCover(book, colour)
    }
}

@Composable
private fun PlainCover(book: Book, colour: Int) {
    val ink = inkOn(colour)
    Box(Modifier.fillMaxSize().background(Color(colour)).padding(5.dp).border(1.dp, ink.copy(alpha = .3f))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp)) {
            Text(book.title, color = ink, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                lineHeight = 15.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            if (book.author.isNotBlank()) Text(book.author, color = ink.copy(alpha = .85f), fontSize = 9.sp, lineHeight = 11.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The sheet shown when you tap a spine or long-press a cover. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookSheet(book: Book, colour: Int, enabled: Boolean, onDismiss: () -> Unit, onOpen: () -> Unit, onRemove: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val shape = RoundedCornerShape(topStart = 1.dp, bottomStart = 1.dp, topEnd = 3.dp, bottomEnd = 3.dp)
            Box(Modifier.width(104.dp).aspectRatio(.68f).shadow(3.dp, shape).clip(shape)) { BookFace(book, colour) }
            Column(Modifier.weight(1f)) {
                Text(book.title, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                Text("${book.format}  ·  ${readingStatus(book)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = .7f))
                if (readingState(book) == ReadingState.Reading)
                    LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp))
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpen, enabled = enabled) {
                    Text(when (readingState(book)) {
                        ReadingState.NotStarted -> "Start reading"
                        ReadingState.Reading -> "Continue reading"
                        ReadingState.Finished -> "Open book"
                    })
                }
                TextButton(onClick = onRemove, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove from library")
                }
            }
        }
    }
}
