package com.samdvich.familyarchivegallery.data.scanner

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.samdvich.familyarchivegallery.domain.model.ArchiveScanResult
import com.samdvich.familyarchivegallery.domain.model.NaturalOrder
import com.samdvich.familyarchivegallery.domain.model.PhotoCategory
import com.samdvich.familyarchivegallery.domain.model.PhotoItem
import com.samdvich.familyarchivegallery.domain.model.PhotoSourceType
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID

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
            .mapNotNull { category -> scanFileCategory(sourceId, root, category) }
            .toList()
        val rootPhotos = rootChildren
            .asSequence()
            .filter { it.isFile && it.canRead() && isSupportedImage(it.name) }
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
        val rootChildren = root.listFiles()
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
            .filter { it.isFile && isSupportedImage(it.name.orEmpty()) }
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

    private fun scanFileCategory(sourceId: String, root: File, category: File): PhotoCategory? {
        val categoryPath = category.relativeTo(root).invariantSeparatorsPath
        val categoryId = stableId(sourceId, categoryPath)
        val photos = buildList {
            val pending = ArrayDeque<File>()
            pending.add(category)
            while (pending.isNotEmpty()) {
                val directory = pending.removeFirst()
                directory.listFiles().orEmpty().forEach { child ->
                    when {
                        child.isDirectory && !child.isHidden && !child.name.startsWith('.') -> pending.add(child)
                        child.isFile && child.canRead() && isSupportedImage(child.name) ->
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
                val (directory, path) = pending.removeFirst()
                directory.listFiles().forEach { child ->
                    val name = child.name.orEmpty()
                    when {
                        child.isDirectory && !name.startsWith('.') -> pending.add(child to "$path/$name")
                        child.isFile && isSupportedImage(name) ->
                            add(scanDocumentPhoto(sourceId, child, categoryId, "$path/$name"))
                    }
                }
            }
        }.sortedWith(compareBy(NaturalOrder) { it.relativePath })

        if (photos.isEmpty()) return null
        return PhotoCategory(categoryId, categoryName, categoryName, photos)
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
        name = file.name.orEmpty(),
        relativePath = relativePath,
        source = file.uri.toString(),
        sourceType = PhotoSourceType.CONTENT_URI,
        size = file.length(),
        lastModified = file.lastModified()
    )

    companion object {
        private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")
        const val ROOT_PHOTOS_CATEGORY_ID = "root-photos"

        fun isSupportedImage(name: String): Boolean = name
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase() in supportedExtensions

        private fun stableId(sourceId: String, path: String): String = UUID.nameUUIDFromBytes(
            "$sourceId|$path".toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }
}
