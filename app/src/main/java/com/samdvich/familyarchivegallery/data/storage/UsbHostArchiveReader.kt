package com.samdvich.familyarchivegallery.data.storage

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.UsbMassStorageDevice.Companion.getMassStorageDevices
import me.jahnen.libaums.core.fs.UsbFile

/** Read-only access to USB mass-storage devices when Android does not mount them for us. */
class UsbHostArchiveReader(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private var openDevices: List<UsbMassStorageDevice> = emptyList()

    fun pendingPermissionDevice(): UsbDevice? = massStorageDevices()
        .firstOrNull { !usbManager.hasPermission(it.usbDevice) }
        ?.usbDevice

    fun hasAccessibleDevice(): Boolean = massStorageDevices()
        .any { usbManager.hasPermission(it.usbDevice) }

    fun scanRoots(archiveName: String): List<Pair<String, UsbFile>> {
        close()
        val roots = buildList {
            massStorageDevices()
                .filter { usbManager.hasPermission(it.usbDevice) }
                .forEach { device ->
                    device.init()
                    openDevices = openDevices + device
                    device.partitions.forEachIndexed { index, partition ->
                        partition.fileSystem.rootDirectory.search(archiveName)?.takeIf { it.isDirectory }?.let { root ->
                            add("usb-host:${device.usbDevice.deviceId}:$index" to root)
                        }
                    }
                }
        }
        return roots
    }

    fun close() {
        openDevices.forEach { runCatching { it.close() } }
        openDevices = emptyList()
        UsbPhotoRegistry.clear()
    }

    private fun massStorageDevices(): List<UsbMassStorageDevice> =
        UsbMassStorageDevice.getMassStorageDevices(context).toList()
}
