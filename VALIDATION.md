# Validation and device checklist

## Android 16 / API 36 upgrade (issue #10)

The app now compiles against and targets API 36, with minSdk 26. AGP is pinned
to 8.10.1, which supports API 36 with the existing Gradle 8.11.1 and JDK 17.
CI installs Android SDK Platform 36 and AGP's default Build Tools 35.0.0.
Kotlin/KSP, Compose, AndroidX and Room versions are unchanged.

Automated checks for this upgrade: pending CI.

Code review found the app already uses enableEdgeToEdge, safeDrawingPadding,
Compose BackHandler and Material 3 dialogs/sheets. It does not override the
legacy Activity.onBackPressed or intercept KEYCODE_BACK, opt out of edge-to-edge,
disable predictive back, or lock orientation/resizing. No navigation rewrite
was made; the checks below must confirm runtime behavior.

**Android 16 device validation is not completed.** Keep this upgrade in draft
until the entire device acceptance checklist below and these additional checks
have been run on Android 16:

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

The following results and APK hash refer to the original build, not the API 36 APK.
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
