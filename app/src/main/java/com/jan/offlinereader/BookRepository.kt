package com.jan.offlinereader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.createBitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.*
import java.net.URI
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Only sniff formats with a reliable signature; unknown data must not become TXT. */
internal fun detectBookExtension(file: File): String? {
    val header = ByteArray(5)
    file.inputStream().use { input ->
        var count = 0
        while (count < header.size) {
            val read = input.read(header, count, header.size - count)
            if (read < 0) break
            count += read
        }
    }
    if (header.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) return "pdf"
    if (!header.take(4).toByteArray().contentEquals(byteArrayOf(80, 75, 3, 4))) return null
    return try {
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            val first = if (entries.hasMoreElements()) entries.nextElement() else null
            val hasMimeType = first?.name == "mimetype" && zip.getInputStream(first).use { input ->
                // At most 21 bytes, even for a huge or malicious mimetype entry.
                "application/epub+zip".all { input.read() == it.code } && input.read() == -1
            }
            if (hasMimeType || zip.getEntry("META-INF/container.xml")?.isDirectory == false) "epub" else null
        }
    } catch (_: IOException) { null }
}

class BookRepository(private val context: Context, private val dao: ReaderDao) {
    private val root = File(context.filesDir, "books").apply { mkdirs() }

    data class ImportResult(val book: Book, val alreadyPresent: Boolean)

    suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?.takeIf { it.isNotBlank() } ?: "Selected file"
    }

    suspend fun import(uri: Uri, sourceName: String? = null): ImportResult = withContext(Dispatchers.IO) {
        val name = sourceName ?: displayName(uri)
        val namedExtension = name.substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("epub", "pdf", "txt", "md", "html", "htm", "fb2") }
        val reportedExtension = namedExtension
            ?: when (context.contentResolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase()) {
                "application/epub+zip" -> "epub"
                "application/pdf" -> "pdf"
                "text/plain" -> "txt"
                "text/markdown" -> "md"
                "text/html" -> "html"
                "application/x-fictionbook+xml" -> "fb2"
                else -> null
            }
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        try {
            val copied = File(dir, "incoming")
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { input ->
                copied.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 1024L * 1024 * 1024) { "This file exceeds the 1 GB import limit." }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                }
            } ?: error("The selected file is no longer available. Please select it again.")
            val extension = reportedExtension ?: detectBookExtension(copied)
            require(extension != null) {
                "Choose an EPUB, PDF, TXT, Markdown, HTML, or FB2 file. MOBI, AZW and DRM-protected books aren't supported."
            }
            val file = File(dir, "original.$extension")
            check(copied.renameTo(file)) { "The imported file could not be prepared." }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            dao.byHash(hash)?.let { dir.deleteRecursively(); return@withContext ImportResult(it, alreadyPresent = true) }
            var title = if (namedExtension != null) name.substringBeforeLast('.') else name
            var author = ""
            var cover: String? = null
            val units: Int
            if (extension == "pdf") {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        units = renderer.pageCount
                        require(units > 0) { "This PDF has no pages." }
                        val ratios = JSONArray()
                        repeat(units) { index -> renderer.openPage(index).use { page ->
                            ratios.put(page.height.toDouble() / page.width)
                        } }
                        File(dir, "pages.json").writeText(ratios.toString())
                        renderer.openPage(0).use { page ->
                            val width = 360
                            val height = (width * page.height.toFloat() / page.width).toInt().coerceIn(1, 2048)
                            val bitmap = createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(android.graphics.Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                File(dir, "cover.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                                cover = "cover.png"
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            } else {
                IndexWriter(dir).use { writer ->
                    when (extension) {
                        "epub" -> {
                            val metadata = importEpub(file, dir, writer)
                            if (metadata.first.isNotBlank()) title = metadata.first
                            author = metadata.second; cover = metadata.third
                        }
                        "html", "htm", "fb2" -> {
                            require(file.length() <= 16 * 1024 * 1024) { "HTML and FB2 files must be under 16 MB." }
                            val doc = file.inputStream().use { Jsoup.parse(it, null, "", if (extension == "fb2") Parser.xmlParser() else Parser.htmlParser()) }
                            if (extension == "fb2") {
                                title = doc.selectFirst("book-title")?.text()?.ifBlank { title } ?: title
                                author = doc.selectFirst("author")?.text().orEmpty()
                            } else title = doc.title().ifBlank { title }
                            writer.mark(title)
                            writer.html(if (extension == "fb2") doc.selectFirst("body") ?: doc else doc.body(), "")
                        }
                        else -> {
                            writer.mark(title)
                            // Fixed-size reads avoid allocating an entire huge line or book.
                            file.bufferedReader(textCharset(file)).use { input ->
                                val buffer = CharArray(8192)
                                var carry = ""
                                var first = true
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) { writer.text(carry); break }
                                    var text = carry + String(buffer, 0, count)
                                    if (first) { text = text.removePrefix("\uFEFF"); first = false }
                                    carry = if (text.isNotEmpty() && Character.isHighSurrogate(text.last())) text.takeLast(1) else ""
                                    if (carry.isNotEmpty()) text = text.dropLast(1)
                                    writer.text(text)
                                }
                            }
                        }
                    }
                    units = writer.blocks.size
                    require(units > 0) { "No readable content was found. Image-only or protected EPUBs may not be supported." }
                    writer.finish()
                }
            }
            val book = Book(id, title, author, extension.uppercase(), dir.absolutePath, hash, cover, units)
            dao.insert(book)
            ImportResult(book, alreadyPresent = false)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun textCharset(file: File): Charset {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            // Scan in bounded chunks, including the end of the file, before indexing.
            InputStreamReader(file.inputStream(), decoder).use { reader ->
                val buffer = CharArray(8192)
                while (reader.read(buffer) != -1) { /* Validate without retaining the book. */ }
            }
            Charsets.UTF_8
        } catch (_: CharacterCodingException) {
            Charset.forName("windows-1252")
        }
    }

    private fun importEpub(file: File, dir: File, writer: IndexWriter): Triple<String, String, String?> = ZipFile(file).use { zip ->
        var expanded = 0L
        fun bytes(path: String, limit: Int = 8 * 1024 * 1024): ByteArray {
            val entry = zip.getEntry(path) ?: error("The EPUB is missing a required file: $path")
            val data = zip.getInputStream(entry).use { it.readBytesLimited(limit) }
            expanded += data.size
            require(expanded <= 100L * 1024 * 1024) { "This EPUB expands beyond the 100 MB safety limit." }
            return data
        }
        fun xml(path: String) = Jsoup.parse(String(bytes(path), Charsets.UTF_8), "", Parser.xmlParser())
        zip.getEntry("META-INF/encryption.xml")?.let {
            val encryption = xml("META-INF/encryption.xml")
            val fontObfuscation = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
            require(encryption.select("EncryptionMethod").all { method -> method.attr("Algorithm") in fontObfuscation }) {
                "This EPUB uses encryption or DRM. Please import an unprotected copy."
            }
        }
        val container = xml("META-INF/container.xml")
        val opfPath = container.selectFirst("rootfile")?.attr("full-path") ?: error("This isn't a valid EPUB container.")
        val opf = xml(opfPath)
        val metadata = opf.getElementsByTag("metadata").firstOrNull()
        fun meta(name: String) = metadata?.allElements?.firstOrNull { it.tagName().substringAfter(':') == name }?.text().orEmpty()
        val items = opf.select("manifest > item").associateBy { it.attr("id") }
        var cover: String? = null
        val coverId = opf.select("meta[name=cover]").firstOrNull()?.attr("content")
        val coverItem = items.values.firstOrNull { "cover-image" in it.attr("properties").split(' ') } ?: items[coverId]
        val imageCache = mutableMapOf<String, Block?>()
        fun image(path: String): Block? {
            // containsKey also recognizes a cached failure (null).
            if (imageCache.containsKey(path)) return imageCache[path]
            val saved = runCatching {
                val data = bytes(path, 12 * 1024 * 1024)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val filename = "image-${MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).take(12).joinToString("") { "%02x".format(it) }}"
                File(dir, filename).writeBytes(data)
                Block(image = filename, imageWidth = bounds.outWidth, imageHeight = bounds.outHeight)
            }.getOrNull()
            imageCache[path] = saved
            return saved
        }
        coverItem?.let { cover = image(resolve(opfPath, it.attr("href")))?.image }
        val fallback = mutableListOf<ContentsEntry>()
        opf.select("spine > itemref").filter { it.attr("linear") != "no" }.forEachIndexed { index, ref ->
            val item = items[ref.attr("idref")] ?: error("The EPUB has a broken reading order.")
            val path = resolve(opfPath, item.attr("href"))
            val doc = Jsoup.parse(String(bytes(path), Charsets.UTF_8))
            val label = doc.selectFirst("h1, h2, title")?.text()?.ifBlank { null } ?: "Section ${index + 1}"
            fallback += ContentsEntry(label, writer.blocks.size)
            writer.anchors[path] = writer.blocks.size
            writer.html(doc.body(), path) { href -> image(resolve(path, href)) }
        }
        val toc = mutableListOf<ContentsEntry>()
        val nav = items.values.firstOrNull { "nav" in it.attr("properties").split(' ') }
        if (nav != null) {
            val path = resolve(opfPath, nav.attr("href"))
            val doc = xml(path)
            val navElement = doc.select("nav").firstOrNull { it.attr("epub:type").split(' ').contains("toc") } ?: doc.selectFirst("nav")
            navElement?.select("a[href]")?.forEach { a ->
                writer.anchorPosition(path, a.attr("href"))?.let { toc += ContentsEntry(a.text(), it) }
            }
        } else {
            items.values.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }?.let { item ->
                val path = resolve(opfPath, item.attr("href"))
                xml(path).select("navPoint").forEach { point ->
                    val src = point.selectFirst("content")?.attr("src").orEmpty()
                    writer.anchorPosition(path, src)?.let { toc += ContentsEntry(point.selectFirst("navLabel")?.text().orEmpty(), it) }
                }
            }
        }
        writer.contents += (toc.ifEmpty { fallback }).filter { it.position < writer.blocks.size }
        Triple(meta("title"), meta("creator"), cover)
    }

    suspend fun document(book: Book): TextDocument = withContext(Dispatchers.IO) {
        val json = JSONObject(File(book.directory, "index.json").readText())
        val blocks = json.getJSONArray("blocks")
        val toc = json.getJSONArray("contents")
        TextDocument((0 until blocks.length()).map { i -> blocks.getJSONObject(i).let {
            Block(it.getLong("start"), it.getInt("length"), it.optBoolean("heading"), it.optString("image").ifBlank { null },
                it.optInt("imageWidth"), it.optInt("imageHeight"))
        } }, (0 until toc.length()).map { i -> toc.getJSONObject(i).let { ContentsEntry(it.getString("title"), it.getInt("position")) } })
    }
    suspend fun text(book: Book, block: Block): String = withContext(Dispatchers.IO) {
        RandomAccessFile(File(book.directory, "text.bin"), "r").use { input ->
            input.seek(block.start)
            val bytes = ByteArray(block.length); input.readFully(bytes); String(bytes, Charsets.UTF_8)
        }
    }
    suspend fun delete(book: Book) = withContext(Dispatchers.IO) {
        dao.deleteBook(book.id); File(book.directory).deleteRecursively(); Unit
    }
}

internal fun resolve(base: String, href: String): String {
    val target = href.substringBefore('#')
    if (target.isEmpty()) return base
    val decoded = URI(null, null, "/" + base, null).resolve(target.replace(" ", "%20")).normalize()
    require(decoded.scheme == null && decoded.host == null) { "External book content isn't supported." }
    return decoded.path.removePrefix("/")
}
private fun InputStream.readBytesLimited(max: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val n = read(buffer); if (n < 0) break
        require(result.size() + n <= max) { "A book section is too large to open safely." }
        result.write(buffer, 0, n)
    }
    return result.toByteArray()
}
private class IndexWriter(dir: File) : Closeable {
    private val output = File(dir, "text.bin").outputStream().buffered()
    private val index = File(dir, "index.json")
    private var offset = 0L
    val blocks = mutableListOf<Block>()
    val contents = mutableListOf<ContentsEntry>()
    val anchors = mutableMapOf<String, Int>()
    fun mark(title: String) { contents += ContentsEntry(title, blocks.size) }
    fun text(value: String, heading: Boolean = false) {
        if (value.isBlank()) return
        TextChunks.split(value).forEach { chunk ->
            val bytes = chunk.toByteArray(Charsets.UTF_8)
            require(offset + bytes.size <= 100L * 1024 * 1024 && blocks.size < 150_000) { "This book exceeds the 100 MB readable-text limit." }
            output.write(bytes); blocks += Block(offset, bytes.size, heading); offset += bytes.size
        }
    }
    fun html(root: Element, path: String, image: (String) -> Block? = { null }) {
        root.select("script, style, noscript").remove()
        val paragraph = StringBuilder()
        var heading = false
        fun flush() { text(paragraph.toString().trim(), heading); paragraph.clear() }
        fun walk(element: Element) {
            val tag = element.normalName().substringAfter(':')
            val block = tag in setOf("p", "div", "section", "article", "blockquote", "li", "pre", "h1", "h2", "h3", "h4", "title", "subtitle", "tr")
            if (block) flush()
            if (element.id().isNotBlank()) { flush(); anchors["$path#${element.id()}"] = blocks.size }
            val wasHeading = heading
            if (tag.matches(Regex("h[1-6]")) || tag == "title" || tag == "subtitle") heading = true
            if (tag == "img") {
                flush()
                image(element.attr("src"))?.let { blocks += it }
                    ?: text(element.attr("alt"))
            } else if (tag == "br") paragraph.append('\n')
            else element.childNodes().forEach { node ->
                when (node) { is TextNode -> paragraph.append(node.text()); is Element -> walk(node) }
            }
            if (block) flush()
            heading = wasHeading
        }
        walk(root); flush()
    }
    fun anchorPosition(base: String, href: String): Int? {
        val path = resolve(base, href)
        val fragment = href.substringAfter('#', "")
        val decodedFragment = runCatching { java.net.URLDecoder.decode(fragment.replace("+", "%2B"), "UTF-8") }.getOrDefault(fragment)
        return anchors[if (fragment.isBlank()) path else "$path#$decodedFragment"] ?: anchors[path]
    }
    fun finish() {
        output.flush()
        val json = JSONObject()
        json.put("blocks", JSONArray().apply { blocks.forEach { b ->
            put(JSONObject().put("start", b.start).put("length", b.length).put("heading", b.heading)
                .put("image", b.image ?: "").apply {
                    if (b.image != null) { put("imageWidth", b.imageWidth); put("imageHeight", b.imageHeight) }
                })
        } })
        json.put("contents", JSONArray().apply { contents.forEach { c -> put(JSONObject().put("title", c.title).put("position", c.position)) } })
        index.writeText(json.toString())
    }
    override fun close() = output.close()
}
