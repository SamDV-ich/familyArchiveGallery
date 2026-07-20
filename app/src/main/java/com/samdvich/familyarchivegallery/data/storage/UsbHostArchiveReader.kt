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
        val readableDevices = mutableListOf<UsbMassStorageDevice>()
        val roots = buildList {
            massStorageDevices()
                .filter { usbManager.hasPermission(it.usbDevice) }
                .forEach { device ->
                    // A hub or multi-slot card reader commonly exposes empty slots as
                    // separate mass-storage devices/LUNs. One unreadable slot must not
                    // prevent a real flash drive on the same hub from being scanned.
                    val deviceRoots = runCatching {
                        device.init()
                        device.partitions.mapIndexedNotNull { index, partition ->
                            partition.fileSystem.rootDirectory.search(archiveName)
                                ?.takeIf { it.isDirectory }
                                ?.let { root ->
                                    "usb-host:${device.usbDevice.deviceId}:$index" to root
                                }
                        }
                    }.getOrElse {
                        runCatching { device.close() }
                        emptyList()
                    }
                    if (deviceRoots.isNotEmpty()) {
                        readableDevices += device
                        addAll(deviceRoots)
                    } else {
                        runCatching { device.close() }
                    }
                }
        }
        openDevices = readableDevices
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
