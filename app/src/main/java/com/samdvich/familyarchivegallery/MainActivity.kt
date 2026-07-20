package com.samdvich.familyarchivegallery

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.samdvich.familyarchivegallery.ui.FamilyArchiveApp
import com.samdvich.familyarchivegallery.ui.theme.FamilyArchiveGalleryTheme
import com.samdvich.familyarchivegallery.data.update.UpdateStatus
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ArchiveViewModel
    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }
    private var receiverRegistered = false
    private var pendingUpdateFile: File? = null

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ensureStorageAccess() }

    private val documentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            viewModel.requireAccess(AccessRequest.DOCUMENT_TREE, getString(R.string.folder_not_selected))
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        preferences.edit()
            .putString(KEY_ARCHIVE_TREE_URI, uri.toString())
            .putBoolean(KEY_USE_SAF_FALLBACK, true)
            .apply()
        viewModel.scanDocumentTree(uri, ARCHIVE_DIRECTORY_NAME)
    }

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pending = pendingUpdateFile
        if (pending != null && canInstallPackages()) {
            launchPackageInstaller(pending)
        } else if (pending != null) {
            viewModel.reportUpdateError(getString(R.string.install_permission_denied))
        }
    }

    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            ensureStorageAccess()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[ArchiveViewModel::class.java]

        setContent {
            FamilyArchiveGalleryTheme {
                val state = viewModel.uiState.collectAsState().value
                val screen = viewModel.screen.collectAsState().value
                val updateState = viewModel.updateState.collectAsState().value
                FamilyArchiveApp(
                    uiState = state,
                    screen = screen,
                    updateState = updateState,
                    onGrantAccess = ::grantAccess,
                    onRefresh = ::ensureStorageAccess,
                    onOpenCategory = viewModel::openCategory,
                    onOpenPhoto = viewModel::openPhoto,
                    onMoveViewer = viewModel::moveViewer,
                    onUpdateAction = { handleUpdateAction(updateState) },
                    onBack = { if (!viewModel.back()) finish() }
                )
            }
        }
        ensureStorageAccess()
        viewModel.checkForUpdates()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(storageReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(storageReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        // The all-files setting does not return an ActivityResult. Re-check it whenever
        // the user returns from Settings so a granted permission starts a scan immediately.
        ensureStorageAccess()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(storageReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun ensureStorageAccess() {
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> {
                val granted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) viewModel.scanDirect(ARCHIVE_DIRECTORY_NAME)
                else viewModel.requireAccess(AccessRequest.LEGACY_READ)
            }
            Environment.isExternalStorageManager() -> viewModel.scanDirect(ARCHIVE_DIRECTORY_NAME)
            else -> {
                val savedUri = preferences.getString(KEY_ARCHIVE_TREE_URI, null)?.let(Uri::parse)
                if (preferences.getBoolean(KEY_USE_SAF_FALLBACK, false) && savedUri != null) {
                    viewModel.scanDocumentTree(savedUri, ARCHIVE_DIRECTORY_NAME)
                } else {
                    viewModel.requireAccess(AccessRequest.ALL_FILES)
                }
            }
        }
    }

    private fun grantAccess(request: AccessRequest) {
        when (request) {
            AccessRequest.LEGACY_READ -> legacyPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            AccessRequest.DOCUMENT_TREE -> documentTreeLauncher.launch(null)
            AccessRequest.ALL_FILES -> openAllFilesSettingsOrDocumentTree()
        }
    }

    private fun openAllFilesSettingsOrDocumentTree() {
        try {
            openAllFilesSettings()
        } catch (_: ActivityNotFoundException) {
            openDocumentTreeFallback()
        }
    }

    private fun openAllFilesSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun openDocumentTreeFallback() {
        try {
            documentTreeLauncher.launch(null)
        } catch (_: ActivityNotFoundException) {
            viewModel.requireAccess(
                AccessRequest.ALL_FILES,
                getString(R.string.system_folder_picker_unavailable)
            )
        }
    }

    private fun handleUpdateAction(state: com.samdvich.familyarchivegallery.data.update.UpdateUiState) {
        when (state.status) {
            UpdateStatus.AVAILABLE -> viewModel.downloadUpdate(::installDownloadedApk)
            UpdateStatus.READY_TO_INSTALL -> state.downloadedFile?.let { installDownloadedApk(File(it)) }
            UpdateStatus.DOWNLOADING, UpdateStatus.CHECKING -> Unit
            else -> viewModel.checkForUpdates()
        }
    }

    private fun installDownloadedApk(apkFile: File) {
        if (!apkFile.isFile) {
            viewModel.reportUpdateError(getString(R.string.downloaded_apk_unavailable))
            return
        }
        pendingUpdateFile = apkFile
        if (!canInstallPackages()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            unknownSourcesLauncher.launch(intent)
            return
        }
        launchPackageInstaller(apkFile)
    }

    private fun canInstallPackages(): Boolean = packageManager.canRequestPackageInstalls()

    private fun launchPackageInstaller(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(installIntent) }
            .onFailure { viewModel.reportUpdateError(getString(R.string.package_installer_unavailable)) }
    }

    companion object {
        private const val ARCHIVE_DIRECTORY_NAME = "FamilyArchive"
        private const val PREFERENCES_NAME = "archive_access"
        private const val KEY_ARCHIVE_TREE_URI = "archive_tree_uri"
        private const val KEY_USE_SAF_FALLBACK = "use_saf_fallback"
    }
}
