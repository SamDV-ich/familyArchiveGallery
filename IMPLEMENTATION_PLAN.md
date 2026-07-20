# Family Archive Gallery

## Detailed Project Implementation Plan

### 1. Delivery Strategy

Development should proceed in small, testable increments. Each phase must produce a build that can be run on an Android TV emulator or either physical Xiaomi TV Stick: `MITV-AESP0` on Android TV 9 and `MITV-AYFR0` on Android TV 11.

The primary development target is Android TV 9 / API 28. Compatibility checks for Android 10 and Android 11 or later must begin early because storage behavior differs significantly between these versions.

The project should avoid building the complete UI before storage access has been validated on the physical device. USB discovery on Android 9 vendor firmware is the highest technical risk and should be proven during the first implementation milestone.

### 2. Milestone Overview

| Milestone | Outcome |
| --- | --- |
| M0 | Android Studio project builds and launches as a TV application |
| M1 | Storage permissions and USB discovery work on supported Android versions |
| M2 | Archive scanning and in-memory catalog state work without MediaStore |
| M3 | Category browser is usable with the remote |
| M4 | Photo grid and thumbnail loading are complete |
| M5 | Full-screen viewer and D-pad navigation are complete |
| M6 | Automatic refresh, removal handling, and recovery states are complete |
| M7 | Performance, compatibility, and release packaging are complete |
| M8 | Automatic GitHub Release update checking and verified installation are complete |

### 3. Phase 0 — Project Bootstrap

#### 3.1 Create the Android Studio Project

Create a new project with the current stable Android Studio:

```text
Template: Empty Activity
Language: Kotlin
Build scripts: Kotlin DSL
Minimum SDK: API 28
```

Recommended identifiers:

```text
Project name: Family Archive Gallery
Namespace: com.samdvich.familyarchivegallery
Application ID: com.samdvich.familyarchivegallery
```

This is the permanent production application ID. Do not change it after the first release is installed.

#### 3.2 Configure Android Versions

Initial baseline:

```text
minSdk: 28
compileSdk: 36
targetSdk: 36
```

Use runtime storage routing instead of reducing the target SDK: direct legacy access on Android 9–10, persisted SAF where it is available, optional all-files access, and USB Host fallback for USB mass storage on Xiaomi firmware without DocumentsUI.

#### 3.3 Configure Compose for TV

Add:

- Compose BOM.
- Activity Compose.
- Compose UI.
- Compose Foundation.
- TV Material.
- Compose tooling for debug builds.

Initial known stable baseline:

```text
Compose BOM: 2026.06.00
Activity Compose: 1.13.0
TV Material: 1.1.0
```

Use standard Compose lazy lists and grids. Do not build new code with deprecated TV-specific lazy layout APIs.

Use lightweight focusable Compose actions for the main buttons. Avoid focus scaling, ripples, and other optional animations that cause visible input lag on low-power TV sticks.

#### 3.4 Configure the TV Manifest

Add:

- `android.software.leanback` as a required feature.
- `android.hardware.touchscreen` as not required.
- `LAUNCHER` and `LEANBACK_LAUNCHER` categories on the same main activity, so the app is visible on TV and can be opened by system installers.
- Landscape orientation.
- `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"`.
- `android.hardware.usb.host` as an optional feature for USB Host fallback.
- `MANAGE_EXTERNAL_STORAGE` for API 30 and later.
- A full-bleed 320 × 180 TV banner designed independently from the square launcher/settings icon.

Add `INTERNET` only for the GitHub Release updater and `REQUEST_INSTALL_PACKAGES` for handing a verified APK to the Android package installer.

#### 3.5 Establish Build Types

Use at least:

- `debug` for local development.
- `release` for signed distribution.

Give debug builds a separate application ID suffix so they can coexist with a release build:

```kotlin
applicationIdSuffix = ".debug"
```

#### 3.6 Create Initial Package Structure

```text
app/src/main/java/com/samdvich/familyarchivegallery/
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

#### 3.7 Phase 0 Validation

- Gradle sync succeeds.
- Debug APK builds.
- Application launches on a 1080p Android TV API 28 emulator.
- Application appears in the TV launcher.
- A D-pad-focusable test control can be activated.
- Application launches on an API 30+ TV emulator.

### 4. Phase 1 — Storage Access Foundation

This phase must be completed and tested on physical hardware before building the full catalog UI.

#### 4.1 Define the Storage Abstraction

Create an archive entry abstraction that supports both direct files and SAF documents.

Example conceptual API:

```kotlin
sealed interface ArchiveNode {
    val name: String
    val relativePath: String

    data class FileNode(
        val file: File,
        override val name: String,
        override val relativePath: String
    ) : ArchiveNode

    data class DocumentNode(
        val uri: Uri,
        override val name: String,
        override val relativePath: String
    ) : ArchiveNode
}
```

Do not expose `File` or `DocumentFile` directly to UI code.

Define a storage interface with operations such as:

- List mounted sources.
- Find every archive root.
- List child directories.
- List child files.
- Collect supported files in the archive root for the virtual **Uncategorized** category.
- Recursively collect supported files beneath each first-level directory without turning nested folders into categories.
- Build a virtual **All photos** category from every discovered photo.
- Open a read stream.
- Read size and modification time.
- Check whether a node still exists.

#### 4.2 Implement the Access Coordinator

Create `StorageAccessCoordinator`.

Runtime selection:

```text
API 28–32   → LegacyDirectFileAccess with READ_EXTERNAL_STORAGE
API 33+     → AllFilesDirectAccess, with SAF fallback
```

The coordinator owns permission state and exposes it as `StateFlow`.

Suggested states:

```text
Checking
PermissionRequired
PermissionDenied
Ready
NoStorage
ArchiveNotFound
Error
```

#### 4.3 Android 9 Implementation

Implement:

- Runtime `READ_EXTERNAL_STORAGE` request.
- `StorageManager.getStorageVolumes()` enumeration.
- Filtering by mounted state while retaining both removable and non-removable shared-storage volumes.
- Storage-root resolution from volume UUID, plus a primary shared-storage fallback.
- Candidate validation with `StorageManager.getStorageVolume(file)`.
- Automatic search for `FamilyArchive` in every readable storage root.

Run device diagnostics during development and record:

- `StorageVolume` descriptions.
- UUID values.
- Mount states.
- Resolved directories.
- Readability of archive files.

Debug diagnostics may contain paths, but release logging must redact them.

#### 4.4 Android 10–12 Implementation

Implement:

- Runtime `READ_EXTERNAL_STORAGE` (`Files and media`) request.
- Runtime storage routing for legacy Android 9–10 access, SAF, all-files access, and USB Host fallback.
- Direct traversal of every mounted storage root without MediaStore.
- Automatic discovery of every `FamilyArchive` root.

The first-run UI must clearly state that file access is read-only and must be granted once.

#### 4.5 Android 13+ Implementation

Implement:

- Manifest declaration for `MANAGE_EXTERNAL_STORAGE`.
- `Environment.isExternalStorageManager()` check.
- Settings intent for the application-specific all-files access screen.
- Prefer the general all-files settings screen, with an app-specific settings fallback for vendor compatibility.
- Mounted storage-volume enumeration, including primary shared storage.
- Direct mount path access through `StorageVolume.getDirectory()`.
- SAF fallback when the settings page or direct access is unavailable on vendor firmware.

#### 4.6 Phase 1 Tests

Unit tests:

- API version to storage strategy mapping.
- Permission state transitions.
- Archive root name validation.
- Volume filtering.

Device tests:

- Android 9 permission granted and denied.
- USB absent at startup.
- USB attached before startup.
- USB attached while application is open.
- Archive root present and missing.
- Root-level photos, nested photos, and the unified all-photos list.
- Android 10–12 legacy read permission grant and denial.
- Android 13+ all-files permission and SAF fallback.

#### 4.7 Phase 1 Exit Criteria

- The physical Xiaomi TV Stick can locate and read sample files from USB.
- No MediaStore API is used.
- Each Android version selects the correct access mode.
- Permission denial does not cause a crash or dead end.

### 5. Phase 2 — Archive Scanner and Catalog State

#### 5.1 Define Domain Models

Create models such as:

```kotlin
data class PhotoCategory(
    val id: String,
    val sourceId: String,
    val name: String,
    val relativePath: String,
    val photoCount: Int,
    val previewPhotoIds: List<String>
)
```

```kotlin
data class PhotoItem(
    val id: String,
    val categoryId: String,
    val sourceId: String,
    val name: String,
    val relativePath: String,
    val sourceReference: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?
)
```

`sourceReference` may contain an internal file path representation or URI string. It must not be shown directly to the user.

#### 5.2 Implement File Filtering

Accept case-insensitive extensions:

```text
.jpg
.jpeg
.png
.webp
.bmp
.heic
.heif
```

Ignore:

- Hidden files.
- Hidden directories.
- Temporary files.
- Unsupported extensions.
- Unreadable nodes.
- Files directly under `FamilyArchive`.

#### 5.3 Implement Natural Sorting

Create and unit-test a natural comparator so that:

```text
2.jpg
10.jpg
20.jpg
```

is sorted numerically rather than lexicographically.

Use the same comparator for category directories and photos.

#### 5.4 Implement Recursive Scanning

Scanner behavior:

1. Open `FamilyArchive`.
2. Verify `.nomedia` and report its state.
3. List visible first-level directories.
4. Scan each category recursively.
5. Emit progress after each category or bounded batch.
6. Skip individual read failures.
7. Return category and photo metadata.

Use coroutines on an IO dispatcher. Add cancellation checks during traversal so scanning stops promptly when the drive is removed or the screen is destroyed.

#### 5.5 Publish Scan State

Store the current scan result in `ArchiveViewModel` state and expose immutable category/photo models to Compose. Keep filesystem work on the IO dispatcher and publish complete category batches so the UI does not observe partially built collections.

A Room-backed persistent index is a future optimization, not a version 1 requirement. Add it only after profiling shows that direct rescans and the current in-memory state do not meet the hardware targets.

#### 5.6 Implement Incremental Updates

Compare each photo using:

```text
source ID + relative path + size + last-modified time
```

Actions:

- Add newly discovered photos to the next published scan result.
- Replace changed metadata.
- Remove missing photos from the next result.
- Preserve unchanged decoded bitmap cache entries.
- Invalidate cached bitmaps for changed photos.

Do not rely exclusively on directory modification timestamps because removable filesystem behavior varies.

#### 5.7 Add ViewModel APIs

The ViewModel exposes immutable `StateFlow` values for archive and update state plus explicit actions for refresh, permission recovery, navigation, update checking, downloading, and installation.

#### 5.8 Phase 2 Tests

- Empty archive.
- One category with one photo.
- Multiple categories.
- Nested category directories.
- Hidden directories.
- Unsupported extensions.
- Uppercase extensions.
- Corrupt files.
- Duplicate filenames in different nested directories.
- Natural sorting.
- Interrupted scan.
- Added, changed, and removed files.
- Missing `.nomedia` warning.

#### 5.9 Phase 2 Exit Criteria

- A sample archive is scanned correctly.
- Existing visible data remains stable while a rescan runs.
- Updating the USB contents produces the correct next scan result.
- Original files remain unchanged.

### 6. Phase 3 — Navigation and Category Browser

#### 6.1 Add Screen State Navigation

Define sealed screen states:

```text
Categories
Photos(categoryId)
Viewer(categoryId, photoIndex)
```

Pass stable identifiers through state rather than file paths or full serialized objects. Navigation Compose may be introduced later if the screen hierarchy becomes more complex.

#### 6.2 Implement the Application State Host

The root UI selects among:

- Permission screen.
- No-storage screen.
- Archive-not-found screen.
- Loading or scanning screen.
- Catalog navigation host.
- Recoverable error screen.

#### 6.3 Build Category Cards

Each card contains:

- A two-by-two preview collage.
- Category title.
- Photo count.
- TV-focused border, glow, or scale state.

Use deterministic preview selection. The first implementation may use the first four naturally sorted photos.

#### 6.4 Build the Category Grid

- Use standard `LazyVerticalGrid`.
- Begin with three or four columns at 1080p.
- Use stable item keys.
- Preserve focus and scroll state.
- Include a remote-accessible Refresh action.
- Show scan progress without replacing usable cached content.

#### 6.5 Phase 3 Tests

- Navigate every category with D-pad only.
- Open the first, middle, and last category.
- Return and verify focus restoration.
- Refresh while a card is focused.
- Replace category data while the grid is visible.
- Verify text clipping with long category names.

#### 6.6 Phase 3 Exit Criteria

- Category browsing is fully usable without touch.
- Focus never becomes invisible or lost.
- All loading and error states have a recovery path.

### 7. Phase 4 — Thumbnail Pipeline and Photo Grid

#### 7.1 Add the Image Loader

Implement an image loader that supports:

- `File` sources.
- `content://` URI sources.
- A bitmap `LruCache` bounded to 8–32 MiB according to available runtime memory.
- Requested decode size.
- Error placeholders.
- EXIF orientation.

Use `ImageDecoder` for both `File` and `content://` sources. Do not request original-size decoding for thumbnails and do not create a permanent disk copy of private USB photos.

#### 7.2 Implement Category Preview Loading

- Load no more than four preview images per category card.
- Cancel requests when cards leave composition.
- Use stable cache keys derived from source, path, size, and modification time.

#### 7.3 Build the Photo Grid

- Use standard `LazyVerticalGrid`.
- Begin with five columns at 1080p.
- Show category title and count.
- Apply a clear TV-focused state.
- Keep thumbnail aspect handling visually consistent.
- Open the focused photo with D-pad Center.
- Restore grid focus after leaving the viewer.

#### 7.4 Phase 4 Tests

- Portrait, landscape, and square photos.
- Very high-resolution images.
- Corrupt images.
- Missing files.
- Rapid D-pad scrolling.
- Large category with thousands of photos.
- File and SAF URI sources.

#### 7.5 Phase 4 Exit Criteria

- Grid scrolling remains responsive on the Xiaomi TV Stick.
- Full-resolution files are not decoded for grid cells.
- Thumbnail failures do not affect other items.
- Visible tiles use one bounded loader and display loading/error placeholders.

### 8. Phase 5 — Full-Screen Viewer

#### 8.0 Zoom and slideshow extension

- Add fit-to-screen zoom in 0.1× increments to 2.0×. At fit, horizontal keys change photos;
  while zoomed, directional keys pan. Center exposes fit, zoom, previous, and next actions;
  Back resets zoom before leaving.
- Re-decode only the current source at the requested quality; never retain full originals just
  for zooming.
- Add looping slideshows for each category and All photos, with persisted 3/5/10/15/30/60-second
  delay, pause/resume, manual navigation, safe damaged-file skipping, and USB-removal stop.

#### 8.1 Implement Viewer State

Viewer state includes:

- Current category.
- Ordered photo list or paged references.
- Current photo index.
- Previous and next references.
- Controls visibility.
- Loading and error state.

#### 8.2 Implement Remote Input

Required behavior:

```text
D-pad Left   → previous photo
D-pad Right  → next photo
D-pad Center → toggle controls and metadata
Back         → return to photo grid
```

At list boundaries, keep the current photo and optionally show subtle feedback. Do not wrap in version 1.0.

#### 8.3 Implement Image Loading

- Fit within screen bounds.
- Preserve aspect ratio.
- Decode near viewport size.
- Prefetch only adjacent photos.
- Cancel obsolete loads after rapid navigation.
- Display a placeholder while decoding.
- Handle unsupported HEIC/HEIF gracefully.

#### 8.4 Preserve Navigation State

When returning to the grid:

- Restore the viewed photo as the focused item.
- Restore the prior grid scroll position.

Persist the last viewed category and photo internally so an optional resume feature can be added later.

#### 8.5 Phase 5 Tests

- First and last photo boundaries.
- Rapid Left and Right presses.
- Large and small images.
- Portrait orientation.
- Corrupt current image.
- File removed between grid and viewer.
- USB removed while a photo is open.

#### 8.6 Phase 5 Exit Criteria

- Viewing and navigation work reliably with the Xiaomi remote.
- Memory usage remains stable during extended browsing.
- Returning to the grid restores focus correctly.

### 9. Phase 6 — Automatic Refresh and Device Events

#### 9.1 Observe Storage Events

Handle relevant events:

```text
ACTION_MEDIA_MOUNTED
ACTION_MEDIA_UNMOUNTED
ACTION_MEDIA_EJECT
ACTION_MEDIA_REMOVED
ACTION_MEDIA_BAD_REMOVAL
```

Use an active lifecycle-aware receiver where appropriate and perform a fresh storage check in `onResume` as a reliability fallback.

#### 9.2 Mount Behavior

When a drive is attached:

1. Refresh the volume list.
2. Resolve access.
3. Search for every archive root.
4. Show cached content for a recognized source.
5. Start an incremental scan.

#### 9.3 Removal Behavior

When the active drive is removed:

- Cancel active scans and image requests.
- Close streams and scanner work related to the source.
- Leave the viewer or grid safely.
- Show a no-storage state.
- Retain cached metadata for fast reconnection unless the user clears it.

#### 9.4 Manual Refresh

Provide a visible Refresh action on the category screen and recovery screens. Refresh must be safe to invoke repeatedly and must not start overlapping scans.

#### 9.5 Phase 6 Tests

- Attach and remove USB on every screen.
- Remove USB during a scan-state update.
- Remove USB during image decoding.
- Reinsert the same drive.
- Insert a different drive.
- Reinsert an updated drive.
- Trigger Refresh repeatedly.

#### 9.6 Phase 6 Exit Criteria

- Device events never crash the application.
- Reconnected content is refreshed automatically.
- A single scan runs at any given time.

#### 9.7 Implement Application Updates

- Query the public GitHub Releases `latest` endpoint once when the application process starts.
- Keep the startup check asynchronous and independent of storage discovery.
- Keep a manual **Check for updates** action.
- Require a user action before downloading an APK; do not perform silent installation.
- Download `familyarchivegallery.apk` and its `.sha256` asset.
- Verify SHA-256 before passing the APK to the system package installer.
- Handle offline, rate-limit, missing-release, and installer-permission states without blocking archive browsing.
- Represent check, download, checksum, and installer failures separately, with short sanitized diagnostics.
- Cancel/supersede stale update jobs on retry and clear completed jobs so a retry cannot be silently ignored.
- Test an in-place update signed by the production key and verify that application data is retained.

### 10. Phase 7 — Performance and Stability

#### 10.1 Profile the Physical Device

Measure on the Xiaomi TV Stick:

- Startup time.
- Time to a usable category screen.
- Full scan time for representative archives.
- Thumbnail decoding time.
- Memory usage in category, grid, and viewer screens.
- Garbage collection frequency during rapid navigation.
- UI frame stability during scanning.

#### 10.2 Optimize Scanning

- Publish scan results in bounded batches.
- Limit concurrent filesystem work.
- Emit bounded progress updates.
- Avoid reopening unchanged files.
- Stop work immediately after source removal.

#### 10.3 Optimize Image Loading

- Set explicit requested sizes.
- Bound the bitmap `LruCache` between 8 MiB and 32 MiB based on runtime memory.
- Do not create permanent cached copies of private archive photos.
- Disable unnecessary transformations.
- Avoid keeping full-resolution bitmaps in screen state.

#### 10.4 Optimize Remote Focus and Release Code

- Use lightweight focusable actions for primary buttons.
- Avoid unnecessary focus-scale and indication animations.
- Keep network update checks and storage work off the main thread.
- Enable R8 code and resource shrinking for release builds.
- Compare D-pad response on both physical Xiaomi devices.

#### 10.5 Run Long-Duration Tests

- Browse continuously for at least one hour.
- Navigate rapidly through several hundred photos.
- Reopen categories repeatedly.
- Disconnect and reconnect storage several times.
- Restart the application and device.

#### 10.6 Phase 7 Exit Criteria

- No out-of-memory crashes.
- No unbounded cache growth.
- No main-thread filesystem, image decode, or network warnings.
- Remote navigation remains responsive during background work.

### 11. Phase 8 — Compatibility and Quality Assurance

#### 11.1 Required Test Matrix

| Environment | Required coverage |
| --- | --- |
| `MITV-AESP0`, Android TV 9 / API 28 | Complete regression, USB permission, path resolution, and focus tests |
| `MITV-AYFR0`, Android TV 11 | Complete regression, USB Host permission, and missing-system-picker recovery tests |
| Android TV API 28 emulator | UI and permission-state tests |
| Android TV API 29 emulator/device | Legacy read-only permission and direct storage tests |
| Android TV API 30+ emulator/device | SAF, all-files permission, and USB Host fallback tests |
| 1080p display | Complete UI validation |

#### 11.2 Archive Test Sets

Maintain local test archives:

- Small: 3 categories, 20 photos.
- Medium: 20 categories, 2,000 photos.
- Large: at least 20,000 photos.
- Invalid: corrupt, unreadable, hidden, and unsupported files.
- Unicode: Cyrillic, accented Latin, spaces, and punctuation in names.
- Mixed orientation and resolution.

Do not commit private family photos to source control. Use generated or explicitly licensed test images.

#### 11.3 Accessibility and TV Usability

- Verify legibility from typical viewing distance.
- Verify minimum focus contrast.
- Verify that focused scaling does not clip cards.
- Verify no touch-only interaction exists.
- Verify Back behavior on every screen.
- Verify complete English and Russian UI by changing the Android TV system language.
- Verify that **Check for updates** and **Refresh photo list** are clearly distinguishable and are not placed beside each other.
- Verify each empty-archive diagnostic with matching USB folder structures.

#### 11.4 Phase 8 Exit Criteria

- All acceptance criteria in `PROJECT_REQUIREMENTS.md` pass.
- No critical or high-severity defects remain.
- Known lower-severity limitations are documented.

### 12. Phase 9 — Release Preparation

#### 12.1 Create the Release Identity

Finalize:

- Application ID.
- Application name `Family Archive Gallery`.
- Square launcher/settings icon.
- Full-bleed 320 × 180 TV banner without square-icon side padding.
- Version name and version code.

#### 12.2 Create and Protect the Signing Key

Create a dedicated release keystore. Store it and its credentials in a secure backup location outside the Git repository.

Never commit:

- Keystore files.
- Keystore passwords.
- Signing properties containing secrets.

The same key is required to install future versions over an existing installation.

#### 12.3 Configure Release Build

- Disable verbose diagnostics.
- Redact sensitive paths from logs.
- Verify `INTERNET` is used only by the GitHub Release updater.
- Verify debuggable is false.
- Enable R8 code and resource shrinking.
- Build a signed release APK.
- Test upgrading from the previous signed build.
- Configure GitHub Actions signing secrets as described in `RELEASING.md`.
- Publish `familyarchivegallery.apk` and its SHA-256 checksum from version tags.

#### 12.4 APK Naming

GitHub Releases must use the stable updater asset names:

```text
familyarchivegallery.apk
familyarchivegallery.apk.sha256
```

#### 12.5 Release Checklist

- Install on a clean Xiaomi TV Stick profile.
- Complete first-run permission flow.
- Browse a real USB archive.
- Restart the application.
- Confirm the update check runs automatically once and does not block offline browsing.
- Confirm the manual update check remains available.
- Restart the device.
- Reconnect the drive.
- Upgrade from the previous APK.
- Confirm original USB files are unchanged.

### 13. Testing Strategy

#### 13.1 Unit Tests

Prioritize tests for:

- Storage strategy selection.
- Permission state machine.
- Natural filename comparator.
- Extension filtering.
- Category creation rules.
- Incremental scan comparison.
- Viewer index movement.
- Error-to-UI-state mapping.

#### 13.2 Instrumented Tests

Use instrumented tests for:

- Persisted URI permission handling where practical.
- Screen-state navigation.
- Focus movement and restoration.
- Permission and recovery screens.

#### 13.3 Manual Hardware Tests

Physical tests are mandatory for:

- USB mounting and path resolution on Android 9.
- Persisted SAF access, optional all-files access, and USB Host access on `MITV-AYFR0` / Android TV 11.
- Real remote key behavior.
- Memory pressure.
- Large image decoding.
- USB removal during IO.
- A USB hub with a flash drive plus empty card-reader slots; errors from one device must not cancel scanning of the others.
- Release APK installation and upgrade.

### 14. Risk Register

#### Risk: Android 9 USB Mount Path Is Vendor-Specific

Mitigation:

- Isolate mount discovery behind `UsbStorageLocator`.
- Validate candidates through `StorageManager`.
- Test on the Xiaomi TV Stick in Phase 1.
- Keep SAF as a fallback.

#### Risk: Xiaomi Android TV 11 Omits Storage Permission Activities

Mitigation:

- Retain a modern target SDK.
- Use the platform USB Host permission for a detected mass-storage device.
- Keep SAF and all-files paths for compliant firmware and internal storage.

#### Risk: Vendor Firmware Hides the All-Files Settings Screen

Mitigation:

- Detect failure to launch or grant access.
- Use legacy read-only USB access on Android 10–12.
- Offer SAF directory selection on firmware that implements DocumentsUI.

#### Risk: Large Photos Cause Memory Pressure

Mitigation:

- Decode at requested display size.
- Bound caches.
- Prefetch only adjacent viewer images.
- Profile on physical hardware.

#### Image loading and cache extension

- Route previews, visible grid cells, current viewer frames, and zoom frames through a bounded
  coordinator. Use one USB Host decode and two non-USB decodes; cancel obsolete work.
- Add bounded RAM and derived-thumbnail disk cache. Archive-side cache is user-opt-in and only
  uses `FamilyArchive/.familyarchivegallery-cache/`; otherwise use app cache.
- Exclude all dot-prefixed paths, including AppleDouble `._*`, from File, SAF, and USB scans.

#### Risk: Remote Focus Feels Slow on Low-Power Hardware

Mitigation:

- Use lightweight action controls without unnecessary focus animation.
- Keep scanning, image decoding, and update checks off the main thread.
- Validate D-pad response on `MITV-AESP0`, not only on the newer device or emulator.

#### Risk: `.nomedia` Is Missing

Mitigation:

- Detect it during every root scan.
- Show a warning with instructions.
- Keep the application read-only.

#### Risk: USB Is Removed During a Scan

Mitigation:

- Use cancellable coroutine work.
- Catch individual IO failures.
- Cancel active work on removal events.
- Publish immutable scan state only after a category or scan batch is complete.

### 15. Recommended First Development Sequence

The first practical coding sequence should be:

1. Create and run the TV Compose project.
2. Add the TV manifest and banner.
3. Implement Android 9 permission handling.
4. Print mounted `StorageVolume` diagnostics in debug builds.
5. Prove that the Xiaomi TV Stick can read a test file from USB.
6. Find every `FamilyArchive` root automatically.
7. Implement the storage abstraction.
8. Add Android 10–12 legacy read-only storage support.
9. Add Android 13+ all-files and SAF fallback support.
10. Implement the scanner and unit tests.
11. Publish scanner results through ViewModel state.
12. Build the category grid.
13. Add bounded `ImageDecoder` thumbnail caching.
14. Build the photo grid.
15. Build the full-screen viewer.
16. Add automatic refresh and removal handling.
17. Add automatic GitHub Release checks and verified user-confirmed installation.
18. Profile focus response, enable R8, stabilize, and package the release APK.

### 16. Definition of Done

A feature is complete only when:

- Its behavior matches `PROJECT_REQUIREMENTS.md`.
- Unit tests cover non-UI logic.
- Relevant emulator tests pass.
- Hardware-dependent behavior has been tested on the Xiaomi TV Stick.
- D-pad focus behavior is verified manually.
- Errors provide a usable recovery path.
- No original archive file is modified.
- No MediaStore integration has been introduced.
- The feature does not add an unnecessary permission.
- Documentation is updated when behavior or assumptions change.

### USB lifecycle and removal safety implementation

- Register attach/detach broadcasts with the Activity lifecycle and debounce attach refreshes by 1.5 seconds.
- Keep a stable source identifier on every `PhotoItem` so a detached device can be removed without losing other sources.
- Close only the detached mass-storage session and clear only its photo handles; close all sessions for the explicit removal-preparation action.
- Cancel stale scans and image reads, and ignore errors from individual devices, partitions, and empty hub slots.
- Retry a failed USB Host enumeration once after closing app-owned sessions; expose a dedicated recovery action when all permitted devices remain unreadable.
- Distinguish an unreadable USB transport from an archive root that is genuinely absent.
- Add the localized **Prepare USB for removal** action and explain that it releases this app's handles but does not perform Android system unmounting.
- Validate attach, detach, reconnect, multiple-device, hub/card-reader, and removal-during-image-read scenarios on Android TV 9 and 11.
