# Family Archive Gallery

## Project Description and Requirements

### 1. Project Summary

Family Archive Gallery is an offline Android TV application for browsing private family photo archives stored in the TV's shared internal storage and on removable USB drives.

The application automatically discovers a predefined archive directory, treats each first-level subdirectory as a photo category, displays category cards with multi-image previews, provides a photo grid for each category, and supports full-screen photo viewing with D-pad navigation.

The application is intended for private use and will be distributed as a signed APK through GitHub Releases, USB drive, or ADB. Google Play distribution is outside the project scope.

### 2. Primary Goals

- Support Android TV 9 (API 28) and later.
- Run correctly at 1920 × 1080 on Xiaomi TV Stick hardware.
- Discover and read family archives from every available storage location, including the TV's shared internal storage and any number of removable USB drives.
- Avoid querying, inserting, or updating Android MediaStore.
- Prevent archive photos from appearing in the system media library.
- Require as little user interaction as the Android storage model allows.
- Provide a TV-native interface operated entirely with a D-pad remote.
- Select English or Russian UI text automatically from the Android TV system language.
- Remain responsive with large archives and high-resolution photographs.
- Work fully offline and keep all archive information on the device.
- Check GitHub Releases for updates automatically at startup without blocking offline archive browsing.

### 3. Non-Goals

The first release will not include:

- Photo editing, deletion, renaming, or moving.
- Writing any changes to original photo files.
- Cloud storage, synchronization, or user accounts. Network access is used only for optional GitHub Release updates.
- Google Play publication.
- Video or audio playback.
- Mobile phone or touch-first layouts.
- Face recognition, object recognition, or automatic tagging.
- Remote administration of the archive.
- Merging same-named categories from different archive roots; each discovered first-level directory remains a separate category.

### 4. Target Platform

| Item | Requirement |
| --- | --- |
| Device class | Android TV devices |
| Primary device | Xiaomi TV Stick, model `MITV-AESP0` |
| Secondary device | Xiaomi TV Stick, model `MITV-AYFR0` |
| Primary OS | Android TV 9 |
| Secondary OS | Android TV 11 |
| Minimum SDK | API 28 |
| Compile SDK | API 36 |
| Target SDK | API 36 |
| Screen resolution | Optimized for 1920 × 1080 |
| Input | D-pad remote control |
| Network | Optional; required only for GitHub Release updates |
| Distribution | Signed APK through GitHub Releases, outside Google Play |

The application must remain installable and functional on Android 9 and later, including Android TV and Google TV. Storage access is selected at runtime so the app stays compatible with current Android and a future Google Play release.

### 5. Archive Directory Contract

The archive can be placed at the root of the TV's shared internal storage or at the root of any USB drive. The recommended structure is:

```text
USB_ROOT/
└── FamilyArchive/
    ├── .nomedia
    ├── 01 Family/
    │   ├── photo001.jpg
    │   └── photo002.jpg
    ├── 02 Travel/
    │   ├── minsk.jpg
    │   └── italy.jpg
    └── 03 Holidays/
        └── new-year.jpg
```

Rules:

- `FamilyArchive` is the default archive root name.
- The archive root name must be defined in application configuration so it can be changed without rewriting the scanner.
- Each visible first-level directory inside `FamilyArchive` is a category.
- Photos may be stored directly in `FamilyArchive`, directly in a category, or in nested subdirectories beneath a first-level category.
- Category scanning is recursive.
- Supported photos stored directly in `FamilyArchive` appear in the virtual **Uncategorized** category.
- The virtual **All photos** category contains every supported photo in the archive, including root photos and files at any nesting depth.
- Nested directories never become separate categories; their photos remain in their first-level parent category.
- Other files stored directly in `FamilyArchive` are ignored, except for supported configuration files and `.nomedia`.
- Hidden directories and directories whose names begin with `.` are ignored.
- Empty categories are not shown.
- Unreadable files are skipped without stopping the scan.
- The application treats the archive as read-only.
- Every readable, mounted shared-storage root is searched automatically on every scan. This includes the TV's primary shared storage and all mounted removable volumes.
- Each matching `FamilyArchive` root is scanned. Categories from different roots are listed separately, even when their folder names are the same.
- A USB hub or multi-slot card reader can expose several mass-storage devices and empty logical units. An unreadable device, unsupported partition, or empty slot must be skipped without cancelling discovery on the other connected sources.

### 6. Media Library Exclusion

The archive root must contain an empty `.nomedia` file. Android uses this filename to instruct its media scanner to ignore the containing directory and its descendants.

The application must:

- Never call `MediaScannerConnection` for archive files.
- Never insert archive files into MediaStore.
- Never query MediaStore to discover archive photos.
- Check whether `.nomedia` exists in the archive root.
- Display a non-blocking warning when `.nomedia` is missing.
- Not create `.nomedia` automatically in the first release because the application is read-only.

The archive owner is responsible for placing `.nomedia` in `FamilyArchive` before attaching the USB drive.

### 7. Storage Access Strategy

Android storage behavior differs by OS version. The application must select an access strategy at runtime.

| Android version | API | Primary access mode |
| --- | ---: | --- |
| Android 9–10 | 28–29 | `READ_EXTERNAL_STORAGE` and direct `File` access |
| Android 11+ | 30+ | Persisted Storage Access Framework grant, if supplied by the firmware |
| Android 11+ fallback | 30+ | Read-only USB Host access for USB mass-storage devices when the firmware provides neither a folder picker nor usable all-files settings |
| Optional direct mode | 30+ | `MANAGE_EXTERNAL_STORAGE` plus direct `File` access when the user grants it |

#### 7.1 Android 9

- Request `READ_EXTERNAL_STORAGE` at runtime.
- Enumerate mounted storage volumes through `StorageManager`, including the non-removable primary shared-storage volume.
- Resolve the USB mount directory using the volume UUID and validated mount candidates.
- Search automatically for `FamilyArchive`.
- Use `java.io.File` for scanning and image loading.

Android 9 does not expose `StorageVolume.getDirectory()`. Mount resolution must therefore be isolated behind a storage adapter and verified on the target Xiaomi firmware.

#### 7.2 Android 11 and Later

- Prefer a persisted Storage Access Framework grant to the archive folder; it survives restarts.
- Use all-files access only when the device exposes and the user explicitly grants it.
- When Xiaomi firmware omits both system UI paths, enumerate USB mass-storage devices through Android USB Host, request per-device read-only permission, and scan `FamilyArchive` directly from the USB filesystem.
- USB Host access is process-local and is re-requested after an app restart; it is never used for the TV's internal storage.
- Keep the archive read-only and do not use MediaStore.

#### 7.3 Android 13 and Later

- Declare `MANAGE_EXTERNAL_STORAGE`.
- Check access with `Environment.isExternalStorageManager()`.
- Open the system all-files access settings when permission is missing.
- Prefer the general all-files settings screen and fall back to the app-specific screen when required by vendor firmware.
- Enumerate every mounted storage volume through `StorageManager`, including the TV's internal shared storage.
- Use `StorageVolume.getDirectory()` where available.
- Search for `FamilyArchive` automatically.
- Use a persisted Storage Access Framework grant as the normal user-selected source. USB Host remains the fallback for physical USB storage on vendor firmware without DocumentsUI.

### 8. Supported Image Formats

Required formats:

- JPEG: `.jpg`, `.jpeg`
- PNG: `.png`
- WebP: `.webp`
- BMP: `.bmp`

Conditional formats:

- HEIF/HEIC: `.heif`, `.heic`

HEIF/HEIC files may be shown when the platform decoder supports them. Unsupported files must display a placeholder or be skipped without crashing the application.

File extension checks are case-insensitive. The decoder result, rather than the extension alone, determines whether an image is viewable.

### 9. Functional Requirements

#### 9.1 Application Startup

On startup, the application must:

1. Determine the Android storage access mode.
2. Check or request the required permission.
3. Detect every mounted, readable storage location.
4. Locate every matching archive root.
5. Retain currently visible scan state when available.
6. Start an asynchronous rescan.
7. Display categories progressively as results become available.
8. Start one asynchronous GitHub Release update check for the current application process.

The UI must never block while scanning storage or checking for updates. A network error must not prevent offline archive browsing or delay D-pad focus feedback.

#### 9.2 Category Screen

Each category card must show:

- Category name.
- Total photo count.
- A collage containing up to four photo previews.
- A clear focused state suitable for TV viewing.

The first cards are virtual categories:

- **All photos** is always shown when at least one supported photo exists and opens a unified list for the whole archive.
- **Uncategorized** is shown only when supported photos are stored directly in `FamilyArchive`.
- The remaining cards represent only non-empty first-level directories.

Additional behavior:

- Categories are sorted using natural, case-insensitive filename ordering.
- Numeric prefixes such as `01`, `02`, and `10` must sort naturally.
- The initial layout should use three or four cards per row at 1080p.
- The previously focused category should be restored after returning from another screen.
- An explicit **Refresh photo list** action must be available away from the application-update action. It appears below empty-state guidance or in a separate footer below the category grid.

#### 9.3 Photo Grid Screen

The photo grid must:

- Display the category title and photo count.
- Show approximately five thumbnails per row at 1080p.
- Load thumbnails asynchronously.
- Use natural filename order by default.
- Preserve focus and scroll position after returning from the viewer.
- Open the focused photo when the user presses the D-pad center button.
- Return to the category screen when the user presses Back.

When the viewer is opened from **All photos**, Left and Right navigate through every photo in the archive. When it is opened from another category, navigation remains within that category.

Sorting by capture date may be added later, but filename ordering is the required default for the first release.

#### 9.4 Full-Screen Photo Viewer

The viewer must:

- Display the selected photo against a neutral dark background.
- Fit the photo inside the 1920 × 1080 viewport while preserving aspect ratio.
- Respect image orientation metadata.
- Move to the previous photo on D-pad Left.
- Move to the next photo on D-pad Right.
- Toggle metadata and controls on D-pad Center.
- Return to the grid on Back.
- Stop at the first and last photo rather than wrapping by default.
- Prefetch only the immediately previous and next photos.
- Recover gracefully if the current file disappears or the USB drive is removed.

#### 9.5 Refresh and Change Detection

A refresh must occur:

- On application startup.
- When the application returns to the foreground.
- When storage is mounted.
- When removable storage is unmounted or ejected.
- When the user explicitly selects Refresh.
- When a previously known volume is reconnected.

The application must listen for relevant media mount and removal broadcasts while it is active.

If incremental indexing is added later, the scanner must compare discovered files using stable metadata such as:

- Volume identifier.
- Relative path.
- Filename.
- File size.
- Last-modified timestamp.

No original photo content may be copied into internal application storage.

#### 9.6 Application Updates

- Check the latest public GitHub Release automatically once when the application process starts.
- Keep a manual **Check for updates** action as a fallback.
- Do not download or install an update without an explicit D-pad action from the user.
- Download the stable assets `familyarchivegallery.apk` and `familyarchivegallery.apk.sha256`.
- Verify the APK SHA-256 checksum before opening the Android package installer.
- Require the Android system confirmation screen for installation.
- Treat update-check failures as non-blocking; all archive features must remain usable offline.

### 10. Navigation Requirements

- Every interactive element must be reachable with a D-pad.
- No action may require touch input.
- Focus must always be visible.
- Focus movement must be predictable in all four directions.
- Scrolling must keep the focused element visible.
- Back must follow the screen hierarchy: Viewer → Grid → Categories → Exit.
- Returning to a previous screen must restore its prior focus and scroll position.
- Long operations must show a visible progress state without stealing focus unexpectedly.

### 11. Application States and Error Handling

The application must provide dedicated UI states for:

- Permission required.
- Permission denied.
- No readable storage location available.
- USB drive detected but not mounted.
- Archive root not found.
- Archive root found but empty.
- Archive root contains photos directly and no category folders.
- Archive root contains no first-level category folders.
- Archive is currently being scanned.
- `.nomedia` is missing.
- Unsupported or damaged image.
- USB drive removed during browsing.
- Saved SAF permission no longer valid.
- Unexpected read error.

Errors must be written in plain language and include a remote-accessible recovery action when recovery is possible.

All user-facing application text must come from Android string resources. English is the default locale and Russian is provided through `values-ru`; Android selects the language from the device configuration.

### 12. Performance Requirements

- Scanning and image decoding must run outside the main thread.
- Existing visible scan state should remain usable while a refresh runs.
- Category and grid thumbnails must be decoded near their display size, not at original resolution.
- Full-screen images must be decoded near the device viewport size.
- Memory usage must be bounded for low-memory TV sticks.
- Only the current, previous, and next full-screen images may be retained or prefetched.
- Thumbnail cache size must be configurable and bounded.
- The decoded bitmap memory cache must be bounded between 8 MiB and 32 MiB according to available runtime memory.
- Corrupt files must not terminate a scan or browsing session.
- Large directories must be processed incrementally.
- UI lists and grids must use stable item keys.
- Primary TV actions must use lightweight focus feedback without unnecessary focus-scale or ripple animation on low-power devices.
- Release builds must enable code and resource shrinking with R8.

Initial performance targets:

- The category screen should become usable within two seconds after startup under normal device conditions.
- Remote focus feedback should remain visually immediate during background scanning.
- The application should support archives of at least 20,000 photos without loading all full-resolution files into memory.

Performance targets must be validated on the physical Xiaomi TV Stick rather than only on an emulator.

### 13. Data and Cache Requirements

The application may store the following internally:

- Volume identifiers and labels.
- Persisted SAF tree URIs.
- Category metadata.
- Photo relative paths or document URIs.
- File sizes and modification times.
- Thumbnail cache entries.
- Last selected category and photo.
- Last scan status and time.

The application must not store:

- Copies of original archive photographs as permanent application data.
- Personal data outside the device.
- Analytics or usage telemetry.

Uninstalling the application may remove its preferences and bitmap cache but must never remove or modify files on the USB drive.

### 14. Technical Architecture

Technology baseline:

- Kotlin.
- Single-activity architecture.
- Jetpack Compose for TV.
- Lightweight Compose focusable controls, with TV Material used only where it remains responsive.
- Standard Compose lazy grids and lists.
- Coroutines and Flow.
- A sealed in-memory screen state managed by `ArchiveViewModel`.
- `SharedPreferences` for small persisted grants and configuration.
- Direct `ImageDecoder` loading with requested decode sizes and a bounded `LruCache`.
- A direct scanner for the current archive; a persistent database index may be added later for very large archives.

Suggested layers:

```text
Compose TV UI
      ↓
ArchiveViewModel
      ↓
Archive scanner, USB locator, and update repository
      ↓
Direct File API or SAF Document API
      ↓
Removable USB storage
```

Storage-specific code must be hidden behind a common abstraction so screens do not need to know whether an item is represented by a file path or a content URI.

Suggested source structure:

```text
com.samdvich.familyarchivegallery/
├── MainActivity.kt
├── data/
│   ├── scanner/
│   ├── storage/
│   └── update/
├── domain/
│   └── model/
└── ui/
    ├── components/
    └── theme/
```

### 15. Security and Privacy Requirements

- The application must request only storage permissions required by the active Android version.
- Internet access must be limited to HTTPS requests required for GitHub Release update checks and APK downloads.
- The archive must be treated as read-only.
- File paths, names, thumbnails, and metadata must stay on the device.
- Logs must not include sensitive full paths in release builds unless explicitly enabled for diagnostics.
- Release APKs must be signed with a private keystore controlled by the project owner.
- The signing key must be preserved so future APKs can update existing installations.

### 16. Packaging and Distribution

- Debug builds may be installed through Android Studio or ADB during development.
- Production builds must use a dedicated release keystore.
- The main activity must declare both `LAUNCHER` and `LEANBACK_LAUNCHER` so it remains visible in Android TV and can be opened by compatible system installers.
- The application version must use both `versionCode` and `versionName`.
- Every GitHub Release must publish these stable asset names for the embedded updater:

```text
familyarchivegallery.apk
familyarchivegallery.apk.sha256
```

- An upgrade must preserve settings and persisted SAF permissions when Android allows it.
- The project must not depend on Google Play Services.
- Every published APK must be signed with the same private release key.
- The application must verify the downloaded release checksum before opening the package installer.
- The repository used by the embedded updater must be public; credentials must never be embedded in the APK.

### 17. Acceptance Criteria for Version 1.0

Version 1.0 is complete when all of the following are true:

1. The application installs and launches on the Xiaomi TV Stick running Android 9.
2. The application also launches on at least one Android TV device or emulator running Android 11 or later.
3. The application appears in the Android TV launcher with a proper TV banner.
4. All screens can be operated with the standard D-pad remote.
5. Android 9 storage permission is requested and handled correctly.
6. Android 9–10 legacy USB access is requested and handled correctly.
7. Android 11+ can retain and reuse a SAF directory grant, use all-files access when it is available, or request USB Host access for a physical mass-storage drive.
8. A removable USB drive is detected without querying MediaStore.
9. `FamilyArchive` is found automatically in every available storage location whenever direct access is available.
10. Every non-empty first-level folder becomes a category, while nested folders do not.
11. Root-level photos appear in **Uncategorized** and all photos appear in **All photos**.
11. Category cards display a title, photo count, and up to four previews.
12. A category opens into a navigable photo grid.
13. Any supported photo can be opened full-screen.
14. D-pad Left and Right navigate between adjacent photos.
15. Back navigation restores the previously focused item.
16. Reconnecting an updated USB drive refreshes the archive contents.
17. Removing the USB drive during use does not crash the application.
18. Original photo files are never changed or deleted.
19. The application never inserts archive photos into MediaStore.
20. The application works without an internet connection.
21. The application checks for a newer GitHub Release automatically at startup without blocking browsing.
22. An update is downloaded only after user confirmation and its checksum is verified before installation.
23. The launcher uses a full-bleed 320 × 180 TV banner distinct from the square system icon.
24. Focus movement between primary actions remains responsive on both target Xiaomi devices.
25. Russian and English UI text follows the Android TV system language without mixed-language screens.
26. Empty-archive guidance explains whether category folders are missing or formats are unsupported; root-level photos must be shown instead of treated as an error.

### 18. Assumptions

- The default root directory is named `FamilyArchive`.
- The USB filesystem is readable by the Android TV device.
- FAT32 is the recommended USB filesystem for broad compatibility with both target Xiaomi devices.
- The USB drive is physically attached through a compatible powered OTG adapter or hub.
- `.nomedia` is added to the archive root before normal use.
- Categories are represented only by first-level directories.
- Natural filename sorting is the default.
- The first release is read-only and offline.
