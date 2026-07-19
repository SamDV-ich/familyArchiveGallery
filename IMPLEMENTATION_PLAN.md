# Family Archive TV

## Detailed Project Implementation Plan

### 1. Delivery Strategy

Development should proceed in small, testable increments. Each phase must produce a build that can be run on an Android TV emulator or the physical Xiaomi TV Stick.

The primary development target is Android TV 9 / API 28. Compatibility checks for Android 10 and Android 11 or later must begin early because storage behavior differs significantly between these versions.

The project should avoid building the complete UI before storage access has been validated on the physical device. USB discovery on Android 9 vendor firmware is the highest technical risk and should be proven during the first implementation milestone.

### 2. Milestone Overview

| Milestone | Outcome |
| --- | --- |
| M0 | Android Studio project builds and launches as a TV application |
| M1 | Storage permissions and USB discovery work on supported Android versions |
| M2 | Archive scanning and internal indexing work without MediaStore |
| M3 | Category browser is usable with the remote |
| M4 | Photo grid and thumbnail loading are complete |
| M5 | Full-screen viewer and D-pad navigation are complete |
| M6 | Automatic refresh, removal handling, and recovery states are complete |
| M7 | Performance, compatibility, and release packaging are complete |

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
Project name: Family Archive TV
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

If the current stable Android Studio generates a newer stable compile and target SDK, keep the generated values. Do not lower `targetSdk` to 28 to bypass modern storage restrictions.

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

#### 3.4 Configure the TV Manifest

Add:

- `android.software.leanback` as a required feature.
- `android.hardware.touchscreen` as not required.
- `LEANBACK_LAUNCHER` activity category.
- Landscape orientation.
- `READ_EXTERNAL_STORAGE` with `maxSdkVersion="28"`.
- `MANAGE_EXTERNAL_STORAGE` for API 30 and later.
- A 320 × 180 TV banner.

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
app/src/main/java/com/example/familyarchive/
├── MainActivity.kt
├── data/
│   ├── database/
│   ├── repository/
│   ├── scanner/
│   └── storage/
├── domain/
│   ├── model/
│   └── usecase/
└── ui/
    ├── categories/
    ├── photos/
    ├── viewer/
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
- Find the archive root.
- List child directories.
- List child files.
- Open a read stream.
- Read size and modification time.
- Check whether a node still exists.

#### 4.2 Implement the Access Coordinator

Create `StorageAccessCoordinator`.

Runtime selection:

```text
API 28      → LegacyDirectFileAccess
API 29      → SafTreeAccess
API 30+     → AllFilesDirectAccess, with SAF fallback
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
- Filtering by mounted state and removable status.
- USB mount candidate resolution from volume UUID.
- Candidate validation with `StorageManager.getStorageVolume(file)`.
- Automatic search for `FamilyArchive`.

Run device diagnostics during development and record:

- `StorageVolume` descriptions.
- UUID values.
- Mount states.
- Resolved directories.
- Readability of archive files.

Debug diagnostics may contain paths, but release logging must redact them.

#### 4.4 Android 10 Implementation

Implement:

- `ACTION_OPEN_DOCUMENT_TREE` launcher.
- User selection of `FamilyArchive`.
- `takePersistableUriPermission()`.
- URI validation on future launches.
- `DocumentFile` or `DocumentsContract` traversal.
- Recovery when the grant is revoked.

The first-run UI must clearly state that Android 10 requires the archive directory to be selected once.

#### 4.5 Android 11+ Implementation

Implement:

- Manifest declaration for `MANAGE_EXTERNAL_STORAGE`.
- `Environment.isExternalStorageManager()` check.
- Settings intent for the application-specific all-files access screen.
- Mounted removable volume enumeration.
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
- Android 10 persisted SAF grant.
- Android 11+ all-files permission grant and denial.

#### 4.7 Phase 1 Exit Criteria

- The physical Xiaomi TV Stick can locate and read sample files from USB.
- No MediaStore API is used.
- Each Android version selects the correct access mode.
- Permission denial does not cause a crash or dead end.

### 5. Phase 2 — Archive Scanner and Index

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

#### 5.5 Add Room Database

Create entities for:

- Storage source.
- Category.
- Photo.
- Scan session.

Recommended indices:

- Unique source identifier.
- Category source and relative path.
- Photo source and relative path.
- Photo category identifier.

Use database transactions when replacing scan results so the UI never sees a partially inconsistent index.

#### 5.6 Implement Incremental Updates

Compare each photo using:

```text
source ID + relative path + size + last-modified time
```

Actions:

- Insert newly discovered photos.
- Update changed metadata.
- Remove index records for missing photos.
- Preserve unchanged thumbnail cache entries.
- Invalidate thumbnails for changed photos.

Do not rely exclusively on directory modification timestamps because removable filesystem behavior varies.

#### 5.7 Add Repository APIs

The repository should expose flows such as:

```kotlin
fun observeCategories(): Flow<List<PhotoCategory>>

fun observePhotos(categoryId: String): Flow<List<PhotoItem>>

suspend fun refresh(): ScanResult
```

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

- A sample archive is indexed correctly.
- Cached data is available before a rescan completes.
- Updating the USB contents produces correct database changes.
- Original files remain unchanged.

### 6. Phase 3 — Navigation and Category Browser

#### 6.1 Add Navigation Compose

Define routes:

```text
categories
category/{categoryId}
viewer/{categoryId}/{photoId}
```

Pass stable identifiers through navigation rather than file paths or full serialized objects.

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

Configure an image loader that supports:

- `File` sources.
- `content://` URI sources.
- Memory cache.
- Bounded disk cache.
- Requested decode size.
- Error placeholders.
- EXIF orientation.

Do not request original-size decoding for thumbnails.

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

### 8. Phase 5 — Full-Screen Viewer

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
3. Search for the archive root.
4. Show cached content for a recognized source.
5. Start an incremental scan.

#### 9.3 Removal Behavior

When the active drive is removed:

- Cancel active scans and image requests.
- Close streams and database work related to the source.
- Leave the viewer or grid safely.
- Show a no-storage state.
- Retain cached metadata for fast reconnection unless the user clears it.

#### 9.4 Manual Refresh

Provide a visible Refresh action on the category screen and recovery screens. Refresh must be safe to invoke repeatedly and must not start overlapping scans.

#### 9.5 Phase 6 Tests

- Attach and remove USB on every screen.
- Remove USB during a database update.
- Remove USB during image decoding.
- Reinsert the same drive.
- Insert a different drive.
- Reinsert an updated drive.
- Trigger Refresh repeatedly.

#### 9.6 Phase 6 Exit Criteria

- Device events never crash the application.
- Reconnected content is refreshed automatically.
- A single scan runs at any given time.

### 10. Phase 7 — Performance and Stability

#### 10.1 Profile the Physical Device

Measure on the Xiaomi TV Stick:

- Startup time.
- Time to cached category screen.
- Full scan time for representative archives.
- Thumbnail decoding time.
- Memory usage in category, grid, and viewer screens.
- Garbage collection frequency during rapid navigation.
- UI frame stability during scanning.

#### 10.2 Optimize Scanning

- Batch database operations.
- Limit concurrent filesystem work.
- Emit bounded progress updates.
- Avoid reopening unchanged files.
- Stop work immediately after source removal.

#### 10.3 Optimize Image Loading

- Set explicit requested sizes.
- Tune memory cache for the TV Stick.
- Bound disk cache size.
- Disable unnecessary transformations.
- Avoid keeping full-resolution bitmaps in screen state.

#### 10.4 Run Long-Duration Tests

- Browse continuously for at least one hour.
- Navigate rapidly through several hundred photos.
- Reopen categories repeatedly.
- Disconnect and reconnect storage several times.
- Restart the application and device.

#### 10.5 Phase 7 Exit Criteria

- No out-of-memory crashes.
- No unbounded cache growth.
- No main-thread filesystem or database warnings.
- Remote navigation remains responsive during background work.

### 11. Phase 8 — Compatibility and Quality Assurance

#### 11.1 Required Test Matrix

| Environment | Required coverage |
| --- | --- |
| Xiaomi TV Stick, Android 9 | Complete regression test |
| Android TV API 28 emulator | UI and permission-state tests |
| Android TV API 29 emulator/device | SAF setup and persisted access |
| Android TV API 30+ emulator/device | All-files access and direct storage path |
| 1080p display | Complete UI validation |

#### 11.2 Archive Test Sets

Maintain local test archives:

- Small: 3 categories, 20 photos.
- Medium: 20 categories, 2,000 photos.
- Large: at least 20,000 indexed photos.
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

#### 11.4 Phase 8 Exit Criteria

- All acceptance criteria in `PROJECT_REQUIREMENTS.md` pass.
- No critical or high-severity defects remain.
- Known lower-severity limitations are documented.

### 12. Phase 9 — Release Preparation

#### 12.1 Create the Release Identity

Finalize:

- Application ID.
- Application name.
- Launcher icon.
- 320 × 180 TV banner.
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
- Verify no internet permission is present.
- Verify debuggable is false.
- Build a signed release APK.
- Test upgrading from the previous signed build.
- Configure GitHub Actions signing secrets as described in `RELEASING.md`.
- Publish `familyarchivegallery.apk` and its SHA-256 checksum from version tags.

#### 12.4 APK Naming

Use:

```text
family-archive-tv-<version>.apk
```

Example:

```text
family-archive-tv-1.0.0.apk
```

#### 12.5 Release Checklist

- Install on a clean Xiaomi TV Stick profile.
- Complete first-run permission flow.
- Browse a real USB archive.
- Restart the application.
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
- Incremental index comparison.
- Viewer index movement.
- Error-to-UI-state mapping.

#### 13.2 Instrumented Tests

Use instrumented tests for:

- Room database migrations and queries.
- Persisted URI permission handling where practical.
- Compose navigation.
- Focus movement and restoration.
- Permission and recovery screens.

#### 13.3 Manual Hardware Tests

Physical tests are mandatory for:

- USB mounting and path resolution on Android 9.
- Real remote key behavior.
- Memory pressure.
- Large image decoding.
- USB removal during IO.
- Release APK installation and upgrade.

### 14. Risk Register

#### Risk: Android 9 USB Mount Path Is Vendor-Specific

Mitigation:

- Isolate mount discovery behind `UsbStorageLocator`.
- Validate candidates through `StorageManager`.
- Test on the Xiaomi TV Stick in Phase 1.
- Keep SAF as a fallback.

#### Risk: Android 10 Cannot Provide Modern Automatic All-Files Access

Mitigation:

- Use one-time SAF directory selection.
- Persist the grant.
- Explain the exception clearly in first-run UI.

#### Risk: Vendor Firmware Hides the All-Files Settings Screen

Mitigation:

- Detect failure to launch or grant access.
- Offer SAF directory selection as fallback.

#### Risk: Large Photos Cause Memory Pressure

Mitigation:

- Decode at requested display size.
- Bound caches.
- Prefetch only adjacent viewer images.
- Profile on physical hardware.

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
- Commit database changes transactionally.

### 15. Recommended First Development Sequence

The first practical coding sequence should be:

1. Create and run the TV Compose project.
2. Add the TV manifest and banner.
3. Implement Android 9 permission handling.
4. Print mounted `StorageVolume` diagnostics in debug builds.
5. Prove that the Xiaomi TV Stick can read a test file from USB.
6. Find `FamilyArchive` automatically.
7. Implement the storage abstraction.
8. Add Android 10 SAF support.
9. Add Android 11+ all-files support.
10. Implement the scanner and unit tests.
11. Add Room indexing.
12. Build the category grid.
13. Add thumbnail loading.
14. Build the photo grid.
15. Build the full-screen viewer.
16. Add automatic refresh and removal handling.
17. Profile, stabilize, and package the release APK.

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
