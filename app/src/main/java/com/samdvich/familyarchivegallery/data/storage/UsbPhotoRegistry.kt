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

    fun register(id: String, file: UsbFile) {
        files[id] = file
    }

    fun open(id: String): InputStream = UsbFileInputStream(
        requireNotNull(files[id]) { "USB photo is no longer connected" }
    )

    fun clear() = files.clear()
}
