# Validation and device checklist

Verified on 2026-10-18 from a clean build:

- `./gradlew clean testDebugUnitTest lintDebug assembleDebug`: successful.
- 10 automated tests passed; zero failures or errors.
- Android lint: zero errors, 0 warnings.
- APK signature verification: passed (Android debug signing certificate).
- APK permission inspection: no INTERNET or broad storage permissions.
- Android 12+ backup and device transfer: explicitly excluded for app data.
- APK SHA-256: `12ddd75998f32285b2f0bdbfeb5fb74c103c4e4e87546273ca58da3d1825e07a`

The tests run on the JVM with JUnit and Robolectric (Android API 28). They cover
text integrity and Unicode boundaries, EPUB path safety, import copy/duplicate handling,
EPUB reading order and anchor navigation, failed-import cleanup, Room persistence and
bookmark cascade deletion, and the offline permission contract.

No physical device or Android emulator UI session was available. The checks below
remain manual; compiling and JVM tests do not establish device performance or visual QA.

## Device acceptance checklist

A physical Android device or emulator is still needed for these interaction checks:

- [ ] Turn on airplane mode before launching; import local EPUB, PDF and TXT files.
- [ ] Open an EPUB with a cover, nested chapters and raster illustrations.
- [ ] Open a long PDF; scroll, jump to a page, and pinch/pan in Zoom.
- [ ] Scroll to the middle of a passage/page, return to the library and reopen it.
- [ ] Background and reopen the app, then force-stop and relaunch after saving.
- [ ] Add multiple bookmarks; jump to and delete them; reopen the app.
- [ ] Change font size, spacing and each theme, then relaunch.
- [ ] Rotate on a phone and tablet; check comfortable margins and library columns.
- [ ] Hide the toolbar, wait, and bring it back using the chevron or a page tap.
- [ ] Delete an original source file and confirm the imported book still reads offline.
- [ ] Reimport identical bytes and confirm the library retains one entry.
- [ ] Try an unsupported extension, damaged EPUB and protected PDF; check error messages.
- [ ] Import a large book while using the UI and check memory/performance on your device.

Fixed-layout EPUB/CSS fidelity, SVG/math, encrypted content, PDF outlines/search/selection,
and additional proprietary formats are outside this version's scope. See README.md.
