package com.example.familyarchivegallery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.familyarchivegallery.data.scanner.ArchiveScanner
import com.example.familyarchivegallery.data.storage.UsbStorageLocator
import com.example.familyarchivegallery.data.update.GitHubUpdateRepository
import com.example.familyarchivegallery.data.update.UpdateStatus
import com.example.familyarchivegallery.data.update.UpdateUiState
import com.example.familyarchivegallery.domain.model.PhotoCategory
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
                        UpdateUiState(status = UpdateStatus.UP_TO_DATE, message = "Установлена последняя версия")
                    } else {
                        UpdateUiState(status = UpdateStatus.AVAILABLE, info = update)
                    }
                },
                onFailure = { error ->
                    UpdateUiState(status = UpdateStatus.ERROR, message = error.message ?: "Не удалось проверить обновления")
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
            }.onFailure { error ->
                _updateState.value = _updateState.value.copy(
                    status = UpdateStatus.ERROR,
                    message = error.message ?: "Не удалось загрузить обновление"
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
            val storageRoots = storageLocator.mountedRemovableRoots()
            if (storageRoots.isEmpty()) return@startScan ScanOutcome.NoStorage
            val archiveRoot = storageRoots
                .asSequence()
                .map { File(it, archiveName) }
                .firstOrNull { it.isDirectory && it.canRead() }
                ?: return@startScan ScanOutcome.ArchiveNotFound
            ScanOutcome.Success(scanner.scanFileRoot(archiveRoot))
        }
    }

    fun scanDocumentTree(uri: Uri, archiveName: String) {
        startScan {
            runCatching { scanner.scanDocumentTree(uri, archiveName) }
                .fold(
                    onSuccess = { ScanOutcome.Success(it) },
                    onFailure = { ScanOutcome.Failure(it.message ?: "The selected archive cannot be read") }
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
                ScanOutcome.Failure(error.message ?: "Unexpected storage error")
            }
            applyOutcome(outcome)
        }
    }

    private fun applyOutcome(outcome: ScanOutcome) {
        _uiState.value = when (outcome) {
            ScanOutcome.NoStorage -> ArchiveUiState(
                status = ArchiveStatus.NO_STORAGE,
                message = "Connect a USB drive containing the FamilyArchive folder"
            )
            ScanOutcome.ArchiveNotFound -> ArchiveUiState(
                status = ArchiveStatus.ARCHIVE_NOT_FOUND,
                message = "The FamilyArchive folder was not found on the connected USB drive"
            )
            is ScanOutcome.Failure -> ArchiveUiState(
                status = ArchiveStatus.ERROR,
                message = outcome.message
            )
            is ScanOutcome.Success -> ArchiveUiState(
                status = ArchiveStatus.READY,
                categories = outcome.result.categories,
                isScanning = false,
                hasNoMediaMarker = outcome.result.hasNoMediaMarker,
                message = if (outcome.result.categories.isEmpty()) {
                    "The archive does not contain any supported photos"
                } else null
            )
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

    private sealed interface ScanOutcome {
        data object NoStorage : ScanOutcome
        data object ArchiveNotFound : ScanOutcome
        data class Success(val result: com.example.familyarchivegallery.domain.model.ArchiveScanResult) : ScanOutcome
        data class Failure(val message: String) : ScanOutcome
    }
}
