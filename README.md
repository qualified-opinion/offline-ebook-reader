# Offline Reader

A native Kotlin + Jetpack Compose ebook reader for Android 8.0+ phones and tablets.
All library and reading operations run on-device. The application does not request
INTERNET, analytics, account, cloud backup, or broad storage permissions.

## Build and install

1. Clone or download this repository, then open its root folder in Android Studio.
2. Use JDK 17 and install Android SDK Platform 35. Allow the first Gradle sync to
   download dependencies. Building requires internet once; using the installed app does not.
3. Run on a connected Android device, or select **Build > Build APK(s)**.

Command line (Windows: use `gradlew.bat`):

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

A GitHub Actions workflow is included. Put the contents of this folder at a repository's
root, then push or run **Build Android APK**. Download the `Offline-Reader-debug-apk`
artifact from the workflow run. It is signed with the debug key for personal testing.
For long-term updates, generate and preserve your own release signing key in Android
Studio. A release build does not have a preconfigured signing key.

## Use

- Tap **Import books**, choose Files/Downloads, and select one or several local files.
  Files chosen from an unavailable cloud provider must first be downloaded in Files.
- Imports are copied into private storage. Moving the original does not break reading.
  Identical file bytes are recognized instead of creating duplicate books.
- Tap a book to open or resume. Scroll vertically in both EPUB and PDF.
- Tap the page or the bottom chevron to show/hide controls. Controls also hide after
  a short idle period. Use the contents icon, bookmark buttons, or appearance button.
- PDFs have page navigation and a separate pinch/pan zoom view. Tap **Done** to leave zoom.
- Use the three-dot button on a library card to remove the imported copy and its bookmarks.
  The original file is retained. Uninstalling the app deletes the private library.

## Supported content

| Format | Reading behavior |
| --- | --- |
| EPUB 2/3 without DRM | Reflowable text, basic headings, raster illustrations, cover, spine order, EPUB 3 navigation / EPUB 2 NCX, bookmarks |
| PDF without password/DRM | Android PdfRenderer, continuous page scrolling, page jump, zoom/pan, page + scroll-offset bookmarks |
| TXT | Streaming UTF-8 import |
| MD | Plain Markdown source, read as text |
| HTML / HTM | Text, headings; embedded scripts/styles removed |
| FB2 | Text, headings, title and author |

EPUB uses a calm, consistent reading style rather than reproducing publisher CSS,
embedded fonts, scripts, complex tables or fixed-page layouts. Local raster EPUB images
are supported; SVG, math layout and FB2 embedded images are not rendered. External image
and web resources never load. Image-based EPUBs using only unsupported vector graphics
may not import. MOBI, AZW/AZW3, CBZ and DRM removal are not included.
PDFs retain their page layout, so font and line spacing apply only to reflowable formats.
PDF dark/sepia appearance filters the whole page, including pictures. PDF outlines,
text search, selection and annotation editing are not included in this first version.

## Position and offline persistence

Room stores books, bookmarks and a singleton preference record. Each book has a private
directory containing its original file and prepared local reading data. Reflowable text
is indexed by UTF-8 byte range, then fetched only for visible passages. PDF bitmaps are
capped near two million pixels per page and render on IO behind a mutex.

Positions use a stable passage/page index and scroll offset. Scrolling queues frequent
writes (roughly every 150 ms during movement), with explicit capture on backgrounding
and reader disposal. Same-layout reopening restores the saved offset. Changing font size,
orientation, display scale or window size preserves the passage/page; the exact visual
line may shift because the layout changes. Transient PDF zoom/pan is not persisted.
Progress is an approximate fraction of text passages or PDF pages, rather than a publisher
page count. A very abrupt process kill can lose the most recent fraction of a second.

Import runs on Dispatchers.IO, uses SHA-256 duplicate detection, and commits the Room row
only after parsing succeeds. Failed imports clean up their staging directory. Limits:
1 GB source file, 100 MB normalized text / expanded EPUB processing, 8 MB per EPUB text
resource, 12 MB per raster image, 16 MB HTML/FB2, and 150,000 text units. These bounds
protect against malformed or unexpectedly huge content; they are shown as import errors.

## Code map

- `Data.kt`: Room entities, DAO, database and application dependencies.
- `BookRepository.kt`: private copies, import, EPUB spine/TOC parser, disk text index.
- `TextIndex.kt`: stable text chunks and document model.
- `ReaderViewModel.kt`: StateFlow state, import jobs, preferences and save queue.
- `MainActivity.kt`: Compose theme and adaptive library grid.
- `ReaderScreen.kt`: reading UI, contents/bookmarks/preferences, lifecycle saving.
- `PdfSource.kt`: serialized background PDF rendering and resource lifecycle.

The Gradle wrapper is included. Dependencies are pinned to a compatible toolchain rather
than automatically tracking the latest releases. Room schemas are exported under
`app/schemas`; add a Room migration before increasing the database version.

## Validation

See `VALIDATION.md` for actual build results and the device acceptance checklist.

## Repository notes

The native Android app is built from the Gradle project at the repository root.
The existing `index.html`, `app.js` and `style.css` files are an earlier web starter
and are separate from the Android app.
