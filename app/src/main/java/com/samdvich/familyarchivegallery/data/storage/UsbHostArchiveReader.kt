package com.samdvich.familyarchivegallery.data.storage

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.UsbMassStorageDevice.Companion.getMassStorageDevices
import me.jahnen.libaums.core.fs.UsbFile
import java.util.concurrent.ConcurrentHashMap

data class UsbHostScanResult(
    val roots: List<Pair<String, UsbFile>>,
    val readableDeviceCount: Int,
    val unreadableDeviceCount: Int
)

/** Read-only access to USB mass-storage devices when Android does not mount them for us. */
class UsbHostArchiveReader(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val openDevices = ConcurrentHashMap<Int, UsbMassStorageDevice>()

    fun pendingPermissionDevice(): UsbDevice? = massStorageDevices()
        .firstOrNull { !usbManager.hasPermission(it.usbDevice) }
        ?.usbDevice

    fun hasAccessibleDevice(): Boolean = massStorageDevices()
        .any { usbManager.hasPermission(it.usbDevice) }

    fun scanRoots(archiveName: String): UsbHostScanResult {
        close()
        val readableDevices = mutableMapOf<Int, UsbMassStorageDevice>()
        var readableDeviceCount = 0
        var unreadableDeviceCount = 0
        val roots = buildList {
            massStorageDevices()
                .filter { usbManager.hasPermission(it.usbDevice) }
                .forEach { device ->
                    // A hub or multi-slot card reader commonly exposes empty slots as
                    // separate mass-storage devices/LUNs. One unreadable slot must not
                    // prevent a real flash drive on the same hub from being scanned.
                    val deviceRoots = try {
                        device.init()
                        openDevices[device.usbDevice.deviceId] = device
                        val rootsForDevice = device.partitions.mapIndexedNotNull { index, partition ->
                            partition.fileSystem.rootDirectory.search(archiveName)
                                ?.takeIf { it.isDirectory }
                                ?.let { root ->
                                    "usb-host:${device.usbDevice.deviceId}:$index" to root
                                }
                        }
                        readableDeviceCount++
                        rootsForDevice
                    } catch (_: Exception) {
                        unreadableDeviceCount++
                        openDevices.remove(device.usbDevice.deviceId)
                        runCatching { device.close() }
                        emptyList()
                    }
                    if (deviceRoots.isNotEmpty()) {
                        readableDevices[device.usbDevice.deviceId] = device
                        addAll(deviceRoots)
                    } else {
                        openDevices.remove(device.usbDevice.deviceId)
                        runCatching { device.close() }
                    }
                }
        }
        openDevices.putAll(readableDevices)
        return UsbHostScanResult(roots, readableDeviceCount, unreadableDeviceCount)
    }

    fun closeDevice(deviceId: Int) {
        openDevices.remove(deviceId)?.let { runCatching { it.close() } }
        UsbPhotoRegistry.clearSourcePrefix("usb-host:$deviceId:")
    }

    fun close() {
        openDevices.values.forEach { runCatching { it.close() } }
        openDevices.clear()
        UsbPhotoRegistry.clear()
    }

    private fun massStorageDevices(): List<UsbMassStorageDevice> =
        UsbMassStorageDevice.getMassStorageDevices(context).toList()
}
