package com.samdvich.familyarchivegallery.data.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

class UsbStorageLocator(context: Context) {
    private val storageManager = context.getSystemService(StorageManager::class.java)

    fun mountedRemovableRoots(): List<File> = storageManager.storageVolumes
        .asSequence()
        .filter(StorageVolume::isRemovable)
        .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
        .mapNotNull(::resolveRoot)
        .filter { it.exists() && it.isDirectory && it.canRead() }
        .distinctBy { it.absolutePath }
        .toList()

    private fun resolveRoot(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.directory
        }

        val uuidCandidate = volume.uuid?.let { File("/storage", it) }
        val storageCandidates = File("/storage").listFiles().orEmpty().asSequence()
            .filter { candidate ->
                runCatching { storageManager.getStorageVolume(candidate) == volume }.getOrDefault(false)
            }
            .toList()

        return (listOfNotNull(uuidCandidate) + storageCandidates)
            .firstOrNull { it.exists() && it.canRead() }
    }
}
