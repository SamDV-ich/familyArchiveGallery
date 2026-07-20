package com.samdvich.familyarchivegallery.data.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.samdvich.familyarchivegallery.domain.model.ArchiveScanResult
import com.samdvich.familyarchivegallery.domain.model.NaturalOrder
import com.samdvich.familyarchivegallery.domain.model.PhotoCategory
import com.samdvich.familyarchivegallery.domain.model.PhotoItem
import com.samdvich.familyarchivegallery.domain.model.PhotoSourceType
import com.samdvich.familyarchivegallery.data.storage.UsbPhotoRegistry
import me.jahnen.libaums.core.fs.UsbFile
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CancellationException

class ArchiveScanner(private val context: Context) {
    fun scanFileRoot(root: File): ArchiveScanResult {
        val sourceId = root.absolutePath
        val rootChildren = root.listFiles().orEmpty()
        val categoryDirectories = rootChildren
            .asSequence()
            .filter { it.isDirectory && !it.isHidden && !it.name.startsWith('.') }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .toList()
        val categories = categoryDirectories
            .asSequence()
            .mapNotNull { category ->
                checkForCancellation()
                runCatching { scanFileCategory(sourceId, root, category) }.getOrNull()
            }
            .toList()
        val rootPhotos = rootChildren
            .asSequence()
            .filter { it.isFile && it.canRead() && isVisibleSupportedImage(it.name) }
            .map { file -> scanFilePhoto(sourceId, root, file, ROOT_PHOTOS_CATEGORY_ID) }
            .sortedWith(compareBy(NaturalOrder) { it.relativePath })
            .toList()

        return ArchiveScanResult(
            sourceId = sourceId,
            categories = categories,
            rootPhotos = rootPhotos,
            hasNoMediaMarker = File(root, ".nomedia").isFile,
            firstLevelDirectoryCount = categoryDirectories.size,
            rootSupportedPhotoCount = rootPhotos.size
        )
    }

    fun scanFileRoots(roots: List<File>): ArchiveScanResult {
        val results = roots.map(::scanFileRoot)
        return ArchiveScanResult(
            sourceId = "multiple-file-roots",
            categories = results
                .flatMap(ArchiveScanResult::categories)
                .sortedWith(compareBy(NaturalOrder) { it.name }),
            rootPhotos = results
                .flatMap(ArchiveScanResult::rootPhotos)
                .sortedWith(compareBy(NaturalOrder) { it.relativePath }),
            // Show a warning if any discovered archive could still enter Android's media library.
            hasNoMediaMarker = results.all(ArchiveScanResult::hasNoMediaMarker),
            firstLevelDirectoryCount = results.sumOf(ArchiveScanResult::firstLevelDirectoryCount),
            rootSupportedPhotoCount = results.sumOf(ArchiveScanResult::rootSupportedPhotoCount)
        )
    }

    fun scanUsbRoots(roots: List<Pair<String, UsbFile>>): ArchiveScanResult {
        val results = roots.mapNotNull { (sourceId, root) ->
            checkForCancellation()
            runCatching { scanUsbRoot(sourceId, root) }.getOrNull()
        }
        return ArchiveScanResult(
            sourceId = "multiple-usb-host-roots",
            categories = results.flatMap(ArchiveScanResult::categories)
                .sortedWith(compareBy(NaturalOrder) { it.name }),
            rootPhotos = results.flatMap(ArchiveScanResult::rootPhotos)
                .sortedWith(compareBy(NaturalOrder) { it.relativePath }),
            // A .nomedia marker is only relevant to Android's media scanner; Host API
            // sources are never sent to that scanner.
            hasNoMediaMarker = true,
            firstLevelDirectoryCount = results.sumOf(ArchiveScanResult::firstLevelDirectoryCount),
            rootSupportedPhotoCount = results.sumOf(ArchiveScanResult::rootSupportedPhotoCount)
        )
    }

    fun scanDocumentTree(selectedUri: Uri, archiveName: String): ArchiveScanResult {
        val selected = requireNotNull(DocumentFile.fromTreeUri(context, selectedUri)) {
            "The selected directory is no longer available"
        }
        val root = if (selected.name == archiveName) {
            selected
        } else {
            selected.findFile(archiveName)?.takeIf(DocumentFile::isDirectory)
                ?: error("$archiveName was not found in the selected directory")
        }
        val sourceId = selectedUri.toString()
        val rootChildren = runCatching { root.listFiles().toList() }.getOrDefault(emptyList())
        val categoryDirectories = rootChildren
            .asSequence()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .sortedWith(compareBy(NaturalOrder) { it.name.orEmpty() })
            .toList()
        val categories = categoryDirectories
            .asSequence()
            .mapNotNull { category -> scanDocumentCategory(sourceId, category) }
            .toList()
        val rootPhotos = rootChildren
            .asSequence()
            .filter { it.isFile && isVisibleSupportedImage(it.name.orEmpty()) }
            .map { file -> scanDocumentPhoto(sourceId, file, ROOT_PHOTOS_CATEGORY_ID) }
            .sortedWith(compareBy(NaturalOrder) { it.relativePath })
            .toList()

        return ArchiveScanResult(
            sourceId = sourceId,
            categories = categories,
            rootPhotos = rootPhotos,
            hasNoMediaMarker = root.findFile(".nomedia")?.isFile == true,
            firstLevelDirectoryCount = categoryDirectories.size,
            rootSupportedPhotoCount = rootPhotos.size
        )
    }

    private fun scanUsbRoot(sourceId: String, root: UsbFile): ArchiveScanResult {
        val rootChildren = root.listFiles()
        val categoryDirectories = rootChildren.asSequence()
            .filter { it.isDirectory && !it.name.startsWith('.') }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .toList()
        val categories = categoryDirectories.mapNotNull { category ->
            checkForCancellation()
            runCatching { scanUsbCategory(sourceId, root, category) }.getOrNull()
        }
        val rootPhotos = rootChildren.asSequence()
            .filter { !it.isDirectory && isVisibleSupportedImage(it.name) }
            .map { file -> scanUsbPhoto(sourceId, root, file, ROOT_PHOTOS_CATEGORY_ID) }
            .sortedWith(compareBy(NaturalOrder) { it.relativePath })
            .toList()
        return ArchiveScanResult(
            sourceId = sourceId,
            categories = categories,
            rootPhotos = rootPhotos,
            hasNoMediaMarker = true,
            firstLevelDirectoryCount = categoryDirectories.size,
            rootSupportedPhotoCount = rootPhotos.size
        )
    }

    private fun scanFileCategory(sourceId: String, root: File, category: File): PhotoCategory? {
        val categoryPath = category.relativeTo(root).invariantSeparatorsPath
        val categoryId = stableId(sourceId, categoryPath)
        val photos = buildList {
            val pending = ArrayDeque<File>()
            pending.add(category)
            while (pending.isNotEmpty()) {
                checkForCancellation()
                val directory = pending.removeFirst()
                directory.listFiles().orEmpty().forEach { child ->
                    when {
                        child.isDirectory && !child.isHidden && !child.name.startsWith('.') -> pending.add(child)
                        child.isFile && child.canRead() && isVisibleSupportedImage(child.name) ->
                            add(scanFilePhoto(sourceId, root, child, categoryId))
                    }
                }
            }
        }.sortedWith(compareBy(NaturalOrder) { it.relativePath })

        if (photos.isEmpty()) return null
        return PhotoCategory(categoryId, category.name, categoryPath, photos)
    }

    private fun scanDocumentCategory(sourceId: String, category: DocumentFile): PhotoCategory? {
        val categoryName = category.name.orEmpty()
        val categoryId = stableId(sourceId, category.uri.toString())
        val photos = buildList {
            val pending = ArrayDeque<Pair<DocumentFile, String>>()
            pending.add(category to categoryName)
            while (pending.isNotEmpty()) {
                checkForCancellation()
                val (directory, path) = pending.removeFirst()
                directory.listFiles().forEach { child ->
                    val name = child.name.orEmpty()
                    when {
                        child.isDirectory && !name.startsWith('.') -> pending.add(child to "$path/$name")
                        child.isFile && isVisibleSupportedImage(name) ->
                            add(scanDocumentPhoto(sourceId, child, categoryId, "$path/$name"))
                    }
                }
            }
        }.sortedWith(compareBy(NaturalOrder) { it.relativePath })

        if (photos.isEmpty()) return null
        return PhotoCategory(categoryId, categoryName, categoryName, photos)
    }

    private fun scanUsbCategory(sourceId: String, root: UsbFile, category: UsbFile): PhotoCategory? {
        val categoryPath = category.absolutePath.removePrefix("/")
        val categoryId = stableId(sourceId, categoryPath)
        val photos = buildList {
            val pending = ArrayDeque<UsbFile>()
            pending.add(category)
            while (pending.isNotEmpty()) {
                checkForCancellation()
                pending.removeFirst().listFiles().forEach { child ->
                    when {
                        child.isDirectory && !child.name.startsWith('.') -> pending.add(child)
                        !child.isDirectory && isVisibleSupportedImage(child.name) ->
                            add(scanUsbPhoto(sourceId, root, child, categoryId))
                    }
                }
            }
        }.sortedWith(compareBy(NaturalOrder) { it.relativePath })
        return photos.takeIf(List<PhotoItem>::isNotEmpty)?.let {
            PhotoCategory(categoryId, category.name, categoryPath, it)
        }
    }

    private fun scanFilePhoto(
        sourceId: String,
        root: File,
        file: File,
        categoryId: String
    ): PhotoItem {
        val relativePath = file.relativeTo(root).invariantSeparatorsPath
        return PhotoItem(
            id = stableId(sourceId, relativePath),
            categoryId = categoryId,
            sourceId = sourceId,
            name = file.name,
            relativePath = relativePath,
            source = file.absolutePath,
            sourceType = PhotoSourceType.FILE,
            size = file.length(),
            lastModified = file.lastModified()
        )
    }

    private fun scanDocumentPhoto(
        sourceId: String,
        file: DocumentFile,
        categoryId: String,
        relativePath: String = file.name.orEmpty()
    ): PhotoItem = PhotoItem(
        id = stableId(sourceId, file.uri.toString()),
        categoryId = categoryId,
        sourceId = sourceId,
        name = file.name.orEmpty(),
        relativePath = relativePath,
        source = file.uri.toString(),
        sourceType = PhotoSourceType.CONTENT_URI,
        size = file.length(),
        lastModified = file.lastModified()
    )

    private fun scanUsbPhoto(
        sourceId: String,
        root: UsbFile,
        file: UsbFile,
        categoryId: String
    ): PhotoItem {
        val relativePath = file.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
        val id = stableId(sourceId, relativePath)
        UsbPhotoRegistry.register(id, sourceId, file)
        return PhotoItem(
            id = id,
            categoryId = categoryId,
            sourceId = sourceId,
            name = file.name,
            relativePath = relativePath,
            source = id,
            sourceType = PhotoSourceType.USB_FILE,
            size = file.length,
            lastModified = file.lastModified()
        )
    }

    companion object {
        private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")
        const val ROOT_PHOTOS_CATEGORY_ID = "root-photos"

        fun isSupportedImage(name: String): Boolean = name
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase() in supportedExtensions

        /**
         * macOS writes AppleDouble sidecar files such as `._IMG_0001.JPG` to FAT and
         * exFAT volumes. They are metadata, not photographs. Treat every dot-prefixed
         * name as hidden so `.DS_Store`, `.nomedia`, cache files, and their descendants
         * cannot become gallery items either.
         */
        fun isVisibleSupportedImage(name: String): Boolean =
            !name.startsWith('.') && isSupportedImage(name)

        private fun checkForCancellation() {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
        }

        private fun stableId(sourceId: String, path: String): String = UUID.nameUUIDFromBytes(
            "$sourceId|$path".toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }
}
