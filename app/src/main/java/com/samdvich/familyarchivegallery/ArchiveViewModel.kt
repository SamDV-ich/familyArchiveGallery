package com.samdvich.familyarchivegallery

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.samdvich.familyarchivegallery.data.scanner.ArchiveScanner
import com.samdvich.familyarchivegallery.data.storage.UsbStorageLocator
import com.samdvich.familyarchivegallery.data.storage.UsbHostArchiveReader
import com.samdvich.familyarchivegallery.data.update.GitHubUpdateRepository
import com.samdvich.familyarchivegallery.data.update.UpdateStatus
import com.samdvich.familyarchivegallery.data.update.UpdateUiState
import com.samdvich.familyarchivegallery.data.update.UpdateException
import com.samdvich.familyarchivegallery.data.update.UpdateFailureStage
import com.samdvich.familyarchivegallery.domain.model.PhotoCategory
import com.samdvich.familyarchivegallery.domain.model.PhotoItem
import com.samdvich.familyarchivegallery.domain.model.NaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

data class GallerySettings(
    val slideshowDelaySeconds: Int = 10,
    val useArchiveDiskCache: Boolean = false
)

enum class ArchiveStatus {
    CHECKING,
    ACCESS_REQUIRED,
    NO_STORAGE,
    ARCHIVE_NOT_FOUND,
    READY,
    ERROR
}

enum class AccessRequest {
    LEGACY_READ,
    DOCUMENT_TREE,
    ALL_FILES,
    USB_DEVICE
}

data class ArchiveUiState(
    val status: ArchiveStatus = ArchiveStatus.CHECKING,
    val categories: List<PhotoCategory> = emptyList(),
    val isScanning: Boolean = false,
    val accessRequest: AccessRequest? = null,
    val message: String? = null,
    val hasNoMediaMarker: Boolean = true,
    val canRecoverUsbConnection: Boolean = false
)

sealed interface ArchiveScreen {
    data object Categories : ArchiveScreen
    data object Settings : ArchiveScreen
    data class Photos(val categoryId: String) : ArchiveScreen
    data class Viewer(val categoryId: String, val photoIndex: Int, val slideshow: Boolean = false) : ArchiveScreen
}

class ArchiveViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = ArchiveScanner(application)
    private val storageLocator = UsbStorageLocator(application)
    private val usbHostReader = UsbHostArchiveReader(application)
    private val updateRepository = GitHubUpdateRepository(
        context = application,
        owner = BuildConfig.GITHUB_OWNER,
        repository = BuildConfig.GITHUB_REPOSITORY,
        apkName = BuildConfig.RELEASE_APK_NAME
    )
    private var scanJob: Job? = null
    private var updateJob: Job? = null

    private val _uiState = MutableStateFlow(ArchiveUiState())
    val uiState: StateFlow<ArchiveUiState> = _uiState.asStateFlow()

    private val _screen = MutableStateFlow<ArchiveScreen>(ArchiveScreen.Categories)
    val screen: StateFlow<ArchiveScreen> = _screen.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val preferences = application.getSharedPreferences("gallery_settings", Application.MODE_PRIVATE)
    private val _settings = MutableStateFlow(
        GallerySettings(
            slideshowDelaySeconds = preferences.getInt(KEY_SLIDESHOW_DELAY, 10).coerceIn(3, 60),
            useArchiveDiskCache = preferences.getBoolean(KEY_ARCHIVE_DISK_CACHE, false)
        )
    )
    val settings: StateFlow<GallerySettings> = _settings.asStateFlow()

    fun checkForUpdates(force: Boolean = false) {
        if (updateJob?.isActive == true) {
            if (!force) return
            updateJob?.cancel()
        }
        _updateState.value = UpdateUiState(status = UpdateStatus.CHECKING)
        updateJob = viewModelScope.launch {
            try {
                val update = withContext(Dispatchers.IO) {
                    updateRepository.findUpdate(BuildConfig.VERSION_NAME)
                }
                _updateState.value = if (update == null) {
                    UpdateUiState(
                        status = UpdateStatus.UP_TO_DATE,
                        message = text(R.string.latest_version_installed)
                    )
                } else {
                    UpdateUiState(status = UpdateStatus.AVAILABLE, info = update)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _updateState.value = updateError(UpdateFailureStage.CHECK, error)
            } finally {
                if (updateJob === coroutineContext[Job]) updateJob = null
            }
        }
    }

    fun downloadUpdate(onReadyToInstall: (File) -> Unit) {
        val update = _updateState.value.info ?: return
        if (updateJob?.isActive == true) return
        _updateState.value = _updateState.value.copy(status = UpdateStatus.DOWNLOADING, progress = 0, message = null)
        updateJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    updateRepository.download(update) { progress ->
                        _updateState.value = _updateState.value.copy(progress = progress)
                    }
                }.also(onReadyToInstall)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _updateState.value = updateError(
                    (error as? UpdateException)?.stage ?: UpdateFailureStage.DOWNLOAD,
                    error
                )
            } finally {
                if (updateJob === coroutineContext[Job]) updateJob = null
            }
        }
    }

    fun reportUpdateError(message: String) {
        _updateState.value = _updateState.value.copy(status = UpdateStatus.ERROR, message = message)
    }

    fun markInstallerOpening() {
        _updateState.value = _updateState.value.copy(
            status = UpdateStatus.INSTALLING,
            progress = 100
        )
    }

    fun finishInstallAttempt() {
        if (_updateState.value.status == UpdateStatus.INSTALLING) {
            _updateState.value = UpdateUiState()
        }
    }

    fun requireAccess(request: AccessRequest, message: String? = null) {
        scanJob?.cancel()
        _uiState.value = _uiState.value.copy(
            status = ArchiveStatus.ACCESS_REQUIRED,
            isScanning = false,
            accessRequest = request,
            message = message
        )
    }

    fun scanDirect(archiveName: String) {
        startScan {
            val storageRoots = storageLocator.mountedStorageRoots()
            if (storageRoots.isEmpty()) return@startScan ScanOutcome.NoStorage
            val archiveRoots = storageRoots
                .asSequence()
                .map { File(it, archiveName) }
                .filter { it.isDirectory && it.canRead() }
                .toList()
            if (archiveRoots.isEmpty()) return@startScan ScanOutcome.ArchiveNotFound
            ScanOutcome.Success(scanner.scanFileRoots(archiveRoots))
        }
    }

    fun scanDocumentTree(uri: Uri, archiveName: String) {
        startScan {
            runCatching { scanner.scanDocumentTree(uri, archiveName) }
                .fold(
                    onSuccess = { ScanOutcome.Success(it) },
                    onFailure = { ScanOutcome.Failure(text(R.string.selected_archive_unreadable)) }
                )
        }
    }

    fun usbDeviceAwaitingPermission() = usbHostReader.pendingPermissionDevice()

    fun hasAccessibleUsbHostDevice() = usbHostReader.hasAccessibleDevice()

    fun scanUsbHost(archiveName: String) {
        startUsbHostScan(archiveName)
    }

    fun recoverUsbHost(archiveName: String) {
        when {
            hasAccessibleUsbHostDevice() -> {
                usbHostReader.close()
                startUsbHostScan(
                    archiveName = archiveName,
                    initialDelayMs = USB_RECOVERY_DELAY_MS
                )
            }
            usbDeviceAwaitingPermission() != null -> requireAccess(AccessRequest.USB_DEVICE)
            else -> {
                usbHostReader.close()
                _uiState.value = ArchiveUiState(
                    status = ArchiveStatus.NO_STORAGE,
                    message = text(R.string.connect_usb_message)
                )
            }
        }
    }

    private fun startUsbHostScan(archiveName: String, initialDelayMs: Long = 0) {
        startScan {
            if (initialDelayMs > 0) delay(initialDelayMs)
            scanUsbHostWithRetry(archiveName)
        }
    }

    private suspend fun scanUsbHostWithRetry(archiveName: String): ScanOutcome {
        repeat(MAX_USB_SCAN_ATTEMPTS) { attempt ->
            val scan = runCatching { usbHostReader.scanRoots(archiveName) }
                .getOrElse { return ScanOutcome.UsbUnreadable }
            if (scan.roots.isNotEmpty()) {
                return ScanOutcome.Success(scanner.scanUsbRoots(scan.roots))
            }
            if (scan.readableDeviceCount > 0) return ScanOutcome.ArchiveNotFound
            if (scan.unreadableDeviceCount == 0) return ScanOutcome.ArchiveNotFound

            usbHostReader.close()
            if (attempt < MAX_USB_SCAN_ATTEMPTS - 1) delay(USB_RETRY_DELAY_MS)
        }
        return ScanOutcome.UsbUnreadable
    }

    fun onUsbDeviceDetached(deviceId: Int) {
        scanJob?.cancel()
        usbHostReader.closeDevice(deviceId)
        val prefix = "usb-host:$deviceId:"
        val filtered = _uiState.value.categories
            .mapNotNull { category ->
                category.copy(photos = category.photos.filterNot { it.sourceId.startsWith(prefix) })
                    .takeIf { it.photos.isNotEmpty() }
            }
        val remaining = filtered.any { category ->
            category.photos.any { it.sourceType != com.samdvich.familyarchivegallery.domain.model.PhotoSourceType.USB_FILE }
        }
        _uiState.value = _uiState.value.copy(
            status = if (remaining) ArchiveStatus.READY else ArchiveStatus.ARCHIVE_NOT_FOUND,
            categories = filtered,
            isScanning = false,
            message = text(R.string.usb_disconnected_message)
        )
        if (_screen.value !is ArchiveScreen.Categories && categoryForScreen() == null) {
            _screen.value = ArchiveScreen.Categories
        }
    }

    fun prepareUsbForRemoval() {
        scanJob?.cancel()
        usbHostReader.close()
        val filtered = _uiState.value.categories
            .mapNotNull { category ->
                category.copy(photos = category.photos.filter {
                    it.sourceType != com.samdvich.familyarchivegallery.domain.model.PhotoSourceType.USB_FILE
                }).takeIf { it.photos.isNotEmpty() }
            }
        _uiState.value = _uiState.value.copy(
            status = if (filtered.isEmpty()) ArchiveStatus.ARCHIVE_NOT_FOUND else ArchiveStatus.READY,
            categories = filtered,
            isScanning = false,
            message = text(R.string.usb_ready_for_removal)
        )
        if (_screen.value !is ArchiveScreen.Categories && categoryForScreen() == null) {
            _screen.value = ArchiveScreen.Categories
        }
    }

    override fun onCleared() {
        usbHostReader.close()
        super.onCleared()
    }

    fun openCategory(categoryId: String) {
        if (_uiState.value.categories.any { it.id == categoryId }) {
            _screen.value = ArchiveScreen.Photos(categoryId)
        }
    }

    fun openSettings() {
        _screen.value = ArchiveScreen.Settings
    }

    fun setSlideshowDelay(seconds: Int) {
        val accepted = seconds.takeIf { it in SLIDESHOW_DELAYS } ?: return
        _settings.value = _settings.value.copy(slideshowDelaySeconds = accepted)
        preferences.edit().putInt(KEY_SLIDESHOW_DELAY, accepted).apply()
    }

    fun setArchiveDiskCacheEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(useArchiveDiskCache = enabled)
        preferences.edit().putBoolean(KEY_ARCHIVE_DISK_CACHE, enabled).apply()
    }

    fun openPhoto(categoryId: String, index: Int, slideshow: Boolean = false) {
        val category = category(categoryId) ?: return
        if (index in category.photos.indices) {
            _screen.value = ArchiveScreen.Viewer(categoryId, index, slideshow)
        }
    }

    fun moveViewer(delta: Int, wrap: Boolean = false) {
        val current = _screen.value as? ArchiveScreen.Viewer ?: return
        val category = category(current.categoryId) ?: return
        if (category.photos.isEmpty()) return
        val target = if (wrap) {
            ((current.photoIndex + delta) % category.photos.size + category.photos.size) % category.photos.size
        } else {
            (current.photoIndex + delta).coerceIn(category.photos.indices)
        }
        _screen.value = current.copy(photoIndex = target)
    }

    fun back(): Boolean = when (_screen.value) {
        is ArchiveScreen.Viewer -> {
            val current = _screen.value as ArchiveScreen.Viewer
            _screen.value = ArchiveScreen.Photos(current.categoryId)
            true
        }
        is ArchiveScreen.Photos -> {
            _screen.value = ArchiveScreen.Categories
            true
        }
        ArchiveScreen.Settings -> {
            _screen.value = ArchiveScreen.Categories
            true
        }
        ArchiveScreen.Categories -> false
    }

    private fun category(id: String): PhotoCategory? = _uiState.value.categories.firstOrNull { it.id == id }

    private fun categoryForScreen(): PhotoCategory? = when (val current = _screen.value) {
        is ArchiveScreen.Photos -> category(current.categoryId)
        is ArchiveScreen.Viewer -> category(current.categoryId)
        ArchiveScreen.Settings -> null
        ArchiveScreen.Categories -> null
    }

    private fun startScan(block: suspend () -> ScanOutcome) {
        scanJob?.cancel()
        val previous = _uiState.value
        _uiState.value = previous.copy(
            status = if (previous.categories.isEmpty()) ArchiveStatus.CHECKING else ArchiveStatus.READY,
            isScanning = true,
            accessRequest = null,
            message = null,
            canRecoverUsbConnection = false
        )
        scanJob = viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ScanOutcome.Failure(text(R.string.unexpected_storage_error))
            }
            applyOutcome(outcome)
        }
    }

    private fun applyOutcome(outcome: ScanOutcome) {
        _uiState.value = when (outcome) {
            ScanOutcome.NoStorage -> ArchiveUiState(
                status = ArchiveStatus.NO_STORAGE,
                message = text(R.string.connect_usb_message)
            )
            ScanOutcome.ArchiveNotFound -> ArchiveUiState(
                status = ArchiveStatus.ARCHIVE_NOT_FOUND,
                message = text(R.string.archive_folder_not_found_message)
            )
            is ScanOutcome.Failure -> ArchiveUiState(
                status = ArchiveStatus.ERROR,
                message = outcome.message
            )
            ScanOutcome.UsbUnreadable -> ArchiveUiState(
                status = ArchiveStatus.ERROR,
                message = text(R.string.usb_host_unreadable_recovery),
                canRecoverUsbConnection = true
            )
            is ScanOutcome.Success -> {
                val categories = displayCategories(outcome.result)
                ArchiveUiState(
                    status = ArchiveStatus.READY,
                    categories = categories,
                    isScanning = false,
                    hasNoMediaMarker = outcome.result.hasNoMediaMarker,
                    message = if (categories.isEmpty()) {
                        if (outcome.result.firstLevelDirectoryCount == 0) {
                            text(R.string.archive_no_category_folders)
                        } else {
                            text(R.string.archive_empty_message)
                        }
                    } else null
                )
            }
        }

        val currentScreen = _screen.value
        if (currentScreen !is ArchiveScreen.Categories && category(
                when (currentScreen) {
                    is ArchiveScreen.Photos -> currentScreen.categoryId
                    is ArchiveScreen.Viewer -> currentScreen.categoryId
                    ArchiveScreen.Settings -> ""
                    ArchiveScreen.Categories -> ""
                }
            ) == null
        ) {
            _screen.value = ArchiveScreen.Categories
        }
    }

    private fun displayCategories(
        result: com.samdvich.familyarchivegallery.domain.model.ArchiveScanResult
    ): List<PhotoCategory> {
        val rootPhotos = result.rootPhotos
        val regularCategories = result.categories
            .groupBy { it.name.lowercase() }
            .values
            .map { sameName ->
                val first = sameName.first()
                first.copy(
                    id = "category:${first.name.lowercase()}",
                    photos = sameName.flatMap(PhotoCategory::photos)
                        .sortedWith(compareBy(NaturalOrder) { it.relativePath })
                )
            }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        val allPhotos = (rootPhotos + regularCategories.flatMap(PhotoCategory::photos))
            .sortedWith(compareBy(NaturalOrder) { it.relativePath })
        if (allPhotos.isEmpty()) return emptyList()

        val allCategory = virtualCategory(
            id = ALL_PHOTOS_CATEGORY_ID,
            name = text(R.string.all_photos_category),
            photos = allPhotos
        )
        val uncategorizedCategory = rootPhotos.takeIf(List<PhotoItem>::isNotEmpty)?.let { photos ->
            virtualCategory(
                id = ArchiveScanner.ROOT_PHOTOS_CATEGORY_ID,
                name = text(R.string.uncategorized_category),
                photos = photos
            )
        }
        return buildList {
            add(allCategory)
            uncategorizedCategory?.let(::add)
            addAll(regularCategories)
        }
    }

    private fun virtualCategory(id: String, name: String, photos: List<PhotoItem>): PhotoCategory =
        PhotoCategory(
            id = id,
            name = name,
            relativePath = "",
            photos = photos
        )

    private sealed interface ScanOutcome {
        data object NoStorage : ScanOutcome
        data object ArchiveNotFound : ScanOutcome
        data object UsbUnreadable : ScanOutcome
        data class Success(val result: com.samdvich.familyarchivegallery.domain.model.ArchiveScanResult) : ScanOutcome
        data class Failure(val message: String) : ScanOutcome
    }

    private fun updateError(stage: UpdateFailureStage, error: Throwable): UpdateUiState {
        val reason = (error as? UpdateException)?.diagnostic
            ?: error.message
            ?: error::class.java.simpleName
        val safeReason = reason.replace(Regex("\\s+"), " ").trim().take(140)
        val messageRes = when (stage) {
            UpdateFailureStage.CHECK -> R.string.update_check_failed_with_reason
            UpdateFailureStage.DOWNLOAD -> R.string.update_download_failed_with_reason
            UpdateFailureStage.CHECKSUM -> R.string.update_checksum_failed_with_reason
        }
        return UpdateUiState(
            status = UpdateStatus.ERROR,
            message = text(messageRes, safeReason)
        )
    }

    private fun text(@StringRes resourceId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resourceId, *args)

    private companion object {
        const val ALL_PHOTOS_CATEGORY_ID = "all-photos"
        const val MAX_USB_SCAN_ATTEMPTS = 2
        const val USB_RETRY_DELAY_MS = 1_500L
        const val USB_RECOVERY_DELAY_MS = 1_500L
        const val KEY_SLIDESHOW_DELAY = "slideshow_delay"
        const val KEY_ARCHIVE_DISK_CACHE = "archive_disk_cache"
        val SLIDESHOW_DELAYS = setOf(3, 5, 10, 15, 30, 60)
    }
}
