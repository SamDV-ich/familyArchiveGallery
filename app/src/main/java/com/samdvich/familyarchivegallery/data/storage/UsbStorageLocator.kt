package com.samdvich.familyarchivegallery.data.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

class UsbStorageLocator(context: Context) {
    private val storageManager = context.getSystemService(StorageManager::class.java)

    /**
     * Returns every readable, mounted shared-storage root available to the application.
     *
     * Android reports the TV's shared internal storage as a non-removable volume, so it
     * must not be filtered out: an archive can live there as well as on any number of
     * USB drives. The explicit external-storage fallback is needed on Android 9 vendor
     * builds where the primary volume cannot be resolved from StorageVolume metadata.
     */
    fun mountedStorageRoots(): List<File> = (
        storageManager.storageVolumes
            .asSequence()
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull(::resolveRoot)
            .toList() + listOf(Environment.getExternalStorageDirectory())
        )
        .asSequence()
        .filter { it.exists() && it.isDirectory && it.canRead() }
        .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
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
