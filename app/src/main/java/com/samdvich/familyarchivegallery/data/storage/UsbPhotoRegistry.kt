package com.samdvich.familyarchivegallery.data.storage

import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps read-only handles for the current process. Android's scoped-storage APIs do
 * not expose a path for some TV USB mounts, while the USB Host API does.
 */
object UsbPhotoRegistry {
    private val files = ConcurrentHashMap<String, UsbFile>()
    private val sourceByPhoto = ConcurrentHashMap<String, String>()

    fun register(id: String, sourceId: String, file: UsbFile) {
        files[id] = file
        sourceByPhoto[id] = sourceId
    }

    fun open(id: String): InputStream = UsbFileInputStream(
        requireNotNull(files[id]) { "USB photo is no longer connected" }
    )

    fun clearSourcePrefix(prefix: String) {
        sourceByPhoto.entries
            .filter { it.value.startsWith(prefix) }
            .forEach { entry ->
                sourceByPhoto.remove(entry.key)
                files.remove(entry.key)
            }
    }

    fun clear() {
        sourceByPhoto.clear()
        files.clear()
    }
}
