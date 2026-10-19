# Validation and device checklist

## Current app on main — 2026-10-19

With PR #22 (issue #10) applied, the app compiles against and targets Android 16
(API 36), with minSdk 26, JDK 17, AGP 8.10.1, Gradle 8.11.1, SDK Platform 36 and
Build Tools 35.0.0. Before this change, main targeted API 35. The upgrade stays in
draft until the Android 16 device checklist below has been run.

**PR #22 head and CI (fill in after push):** commit `<NEW_HEAD_SHA>`,
[CI run](<CI_RUN_URL>) — `testDebugUnitTest lintDebug assembleDebug` result: `<PENDING>`.
The earlier green API 36 build, [run 39118693849](https://github.com/qualified-opinion/offline-ebook-reader/actions/runs/39118693849),
is for old commit `dcecc2226997d48ec798f654d08c57ce5196adc9`, which predates the merge
of current main and so does not validate the merged code.

Latest verified green main build (API 35 target, before PR #22): commit `dc37d3c2e731e9b7b5a752ba0493adefd4657dc6`,
[Build Android APK run 39201432151](https://github.com/qualified-opinion/offline-ebook-reader/actions/runs/39201432151).
`./gradlew testDebugUnitTest lintDebug assembleDebug` succeeded. This establishes
successful JVM tests, lint and debug APK compilation, not phone/UI acceptance or
zero lint warnings. The original APK hash below is not the current APK hash. The API 36 APK has not been built by CI from the merged head yet.

**Test-count evidence:** the workflow uploads only the debug APK, not JUnit XML or
HTML test reports; successful job logs do not state the executed-test count.
A report-derived count could not be retrieved and is not claimed here. Current
main source defines 84 `@Test` methods; that is a source inventory, not an execution
count (Robolectric can run a method on multiple SDKs). To record an exact count
from a local build, sum the `tests`, `failures`, `errors` and `skipped` attributes
in `app/build/test-results/testDebugUnitTest/TEST-*.xml`. Do not reuse the historical
10-test total for a current build.

Current coverage includes import/format recognition, EPUB navigation and repeated
pictures, text encoding, export/restore and changed-layout handling, newer-progress
and bookmark merging, PDF zoom geometry, cover caching, status-bar themes, text
preloading, chapter bookmark labels, the save worker and reader reopen persistence.
Most Robolectric tests use API 28; status-bar tests also use API 30. Every Robolectric
test pins its SDK with an explicit `@Config`, so none run on SDK 36 by default. These
are not Android 16 UI tests.

### Android 16 / API 36 device validation (PR #22, issue #10)

The code review found the app already uses enableEdgeToEdge, safeDrawingPadding,
Compose BackHandler and Material 3 dialogs/sheets. It does not override the legacy
Activity.onBackPressed or intercept KEYCODE_BACK, opt out of edge-to-edge, disable
predictive back, or lock orientation/resizing. No navigation rewrite was made; the
checks below must confirm runtime behavior. JVM/static/build checks do not establish
Android 16 UI behavior.

**Android 16 device validation is not completed.** Keep this upgrade in draft until
the entire device acceptance checklist below and these additional checks have been
run on Android 16:

- [ ] Record the device/emulator model, Android version and tested commit.
- [ ] Complete and cancel a back swipe in the reader; only a completed swipe returns to the library.
- [ ] Open Appearance, Contents and Bookmarks; Back closes the sheet before leaving the reader.
- [ ] Open PDF Zoom; Back closes Zoom before leaving the reader.
- [ ] Check back-to-home from the library and reopen/resume after backgrounding.
- [ ] Check status/navigation bars and cutouts with both gesture and three-button navigation.
- [ ] Show the search keyboard; controls remain reachable above system insets.
- [ ] Check full-screen reading, rotation, tablet resizing and split-screen layouts.

References: [AGP 8.10 compatibility](https://developer.android.com/build/releases/agp-8-10-0-release-notes),
[Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16),
[predictive back APIs](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture).

## Original API 35 baseline (historical)

These results and this APK hash refer only to the original build.

Verified on 2026-10-18 from a clean build:

- `./gradlew clean testDebugUnitTest lintDebug assembleDebug`: successful.
- 10 automated tests passed; zero failures or errors.
- Android lint: zero errors, 0 warnings.
- APK signature verification: passed (Android debug signing certificate).
- APK permission inspection: no INTERNET or broad storage permissions.
- Android 12+ backup and device transfer: explicitly excluded for app data.
- APK SHA-256: `12ddd75998f32285b2f0bdbfeb5fb74c103c4e4e87546273ca58da3d1825e07a`

The original tests ran on the JVM with JUnit and Robolectric (Android API 28). They cover
text integrity and Unicode boundaries, EPUB path safety, import copy/duplicate handling,
EPUB reading order and anchor navigation, failed-import cleanup, Room persistence and
bookmark cascade deletion, and the offline permission contract.

No physical device or Android emulator UI session was available. The checks below
remain manual; compiling and JVM tests do not establish device performance or visual QA.

## Device acceptance checklist

All items below remain unverified here; record the phone/tablet model, Android
version, tested commit and results when performing them. Export before any
uninstall/fresh-install check; uninstalling deletes the private library.

- [ ] Turn on airplane mode before launching; import local EPUB, PDF and TXT files.
- [ ] Open an EPUB with a cover, nested chapters and raster illustrations.
- [ ] Import an EPUB with no filename ending and a missing/generic provider type; open it normally. Repeat with a PDF named `scan` without `.pdf`.
- [ ] Import `Dr. Who.txt`; its title remains `Dr. Who`. Check an extensionless EPUB uses metadata, or its complete filename when metadata is absent.
- [ ] Open a long PDF; scroll, jump to a page, and pinch/pan in Zoom. Pause at 3×–5× and check that small text sharpens and stays aligned while panning.
- [ ] Scroll to the middle of a passage/page, return to the library and reopen it; check the passage and scroll offset.
- [ ] Background and reopen, then wait for the save before force-stopping and relaunching. An immediate process kill can lose the latest queued save.
- [ ] Add bookmarks in different EPUB chapters; new labels show chapter names and progress. PDF labels still show page numbers; existing labels remain unchanged. Jump to/delete bookmarks and reopen.
- [ ] Change font size, spacing and each theme, then relaunch.
- [ ] With the phone in light mode, select Dark paper and return to the library; clock/battery icons remain readable. With the phone in dark mode, test Light and Sepia paper.
- [ ] Rotate on a phone and tablet; check comfortable margins and library columns. Reflow may shift the visual line while retaining the saved passage.
- [ ] Hide the toolbar, wait, and bring it back using the chevron or a page tap.
- [ ] Delete an original source file and confirm the imported book still reads offline.
- [ ] Reimport identical bytes and confirm the library retains one entry.
- [ ] Export a library containing several formats, progress, bookmarks and preferences; restore the ZIP and confirm all are present.
- [ ] Copy the export outside the app before uninstalling. On a fresh installation, restore it and check books, progress, bookmarks and preferences.
- [ ] Export at about 10% with a bookmark, read to about 50% and add another, then restore the older ZIP: the newer phone spot and both bookmarks remain.
- [ ] Restore the same ZIP twice: no duplicate bookmarks appear. Unrelated books/bookmarks remain. On a phone with an older saved spot, a newer backup supplies its newer spot.
- [ ] After an app update that changes passage splitting, restore an older export: it remains restorable, with approximate mapped spots. The normal same-layout case should retain exact stored indices/offsets.
- [ ] Try an unsupported/unknown binary file, unrelated ZIP, damaged EPUB, protected PDF and invalid library archive; check useful errors and preservation of the existing library.
- [ ] Import a large book while using the UI; check memory/performance on the device.

## Pending picture work (not yet on main)

PR #36 / issue #28 adds reader-sized decoding and a book-scoped illustration cache.
After it merges, scroll down past pictures and back up; check prompt redisplay,
clarity after rotation/resizing, and normal display after switching books.
Issue #29 follows #28 and reserves picture heights before loading. After it merges,
re-import an illustrated EPUB, jump to the middle and scroll slowly upward past
pictures; text should not jump. Older imports without picture dimensions retain
the fallback behavior. These pending items are not claimed as current features.

Fixed-layout EPUB/CSS fidelity, SVG/math, encrypted content, PDF outlines/search/selection,
and additional proprietary formats are outside this version's scope. See README.md.
