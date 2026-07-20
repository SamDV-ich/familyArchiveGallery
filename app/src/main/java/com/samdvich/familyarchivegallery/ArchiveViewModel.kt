package com.samdvich.familyarchivegallery

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.samdvich.familyarchivegallery.data.scanner.ArchiveScanner
import com.samdvich.familyarchivegallery.data.storage.UsbStorageLocator
import com.samdvich.familyarchivegallery.data.update.GitHubUpdateRepository
import com.samdvich.familyarchivegallery.data.update.UpdateStatus
import com.samdvich.familyarchivegallery.data.update.UpdateUiState
import com.samdvich.familyarchivegallery.domain.model.PhotoCategory
import com.samdvich.familyarchivegallery.domain.model.PhotoItem
import com.samdvich.familyarchivegallery.domain.model.NaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    ALL_FILES
}

data class ArchiveUiState(
    val status: ArchiveStatus = ArchiveStatus.CHECKING,
    val categories: List<PhotoCategory> = emptyList(),
    val isScanning: Boolean = false,
    val accessRequest: AccessRequest? = null,
    val message: String? = null,
    val hasNoMediaMarker: Boolean = true
)

sealed interface ArchiveScreen {
    data object Categories : ArchiveScreen
    data class Photos(val categoryId: String) : ArchiveScreen
    data class Viewer(val categoryId: String, val photoIndex: Int) : ArchiveScreen
}

class ArchiveViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = ArchiveScanner(application)
    private val storageLocator = UsbStorageLocator(application)
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

    fun checkForUpdates() {
        if (updateJob?.isActive == true) return
        _updateState.value = UpdateUiState(status = UpdateStatus.CHECKING)
        updateJob = viewModelScope.launch {
            _updateState.value = runCatching {
                withContext(Dispatchers.IO) { updateRepository.findUpdate(BuildConfig.VERSION_NAME) }
            }.fold(
                onSuccess = { update ->
                    if (update == null) {
                        UpdateUiState(
                            status = UpdateStatus.UP_TO_DATE,
                            message = text(R.string.latest_version_installed)
                        )
                    } else {
                        UpdateUiState(status = UpdateStatus.AVAILABLE, info = update)
                    }
                },
                onFailure = {
                    UpdateUiState(
                        status = UpdateStatus.ERROR,
                        message = text(R.string.update_check_failed)
                    )
                }
            )
        }
    }

    fun downloadUpdate(onReadyToInstall: (File) -> Unit) {
        val update = _updateState.value.info ?: return
        if (updateJob?.isActive == true) return
        _updateState.value = _updateState.value.copy(status = UpdateStatus.DOWNLOADING, progress = 0, message = null)
        updateJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    updateRepository.download(update) { progress ->
                        _updateState.value = _updateState.value.copy(progress = progress)
                    }
                }
            }.onSuccess { file ->
                _updateState.value = _updateState.value.copy(
                    status = UpdateStatus.READY_TO_INSTALL,
                    progress = 100,
                    downloadedFile = file.absolutePath
                )
                onReadyToInstall(file)
            }.onFailure {
                _updateState.value = _updateState.value.copy(
                    status = UpdateStatus.ERROR,
                    message = text(R.string.update_download_failed)
                )
            }
        }
    }

    fun reportUpdateError(message: String) {
        _updateState.value = _updateState.value.copy(status = UpdateStatus.ERROR, message = message)
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

    fun openCategory(categoryId: String) {
        if (_uiState.value.categories.any { it.id == categoryId }) {
            _screen.value = ArchiveScreen.Photos(categoryId)
        }
    }

    fun openPhoto(categoryId: String, index: Int) {
        val category = category(categoryId) ?: return
        if (index in category.photos.indices) {
            _screen.value = ArchiveScreen.Viewer(categoryId, index)
        }
    }

    fun moveViewer(delta: Int) {
        val current = _screen.value as? ArchiveScreen.Viewer ?: return
        val category = category(current.categoryId) ?: return
        val target = (current.photoIndex + delta).coerceIn(category.photos.indices)
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
        ArchiveScreen.Categories -> false
    }

    private fun category(id: String): PhotoCategory? = _uiState.value.categories.firstOrNull { it.id == id }

    private fun startScan(block: suspend () -> ScanOutcome) {
        scanJob?.cancel()
        val previous = _uiState.value
        _uiState.value = previous.copy(
            status = if (previous.categories.isEmpty()) ArchiveStatus.CHECKING else ArchiveStatus.READY,
            isScanning = true,
            accessRequest = null,
            message = null
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
        data class Success(val result: com.samdvich.familyarchivegallery.domain.model.ArchiveScanResult) : ScanOutcome
        data class Failure(val message: String) : ScanOutcome
    }

    private fun text(@StringRes resourceId: Int): String = getApplication<Application>().getString(resourceId)

    private companion object {
        const val ALL_PHOTOS_CATEGORY_ID = "all-photos"
    }
}
