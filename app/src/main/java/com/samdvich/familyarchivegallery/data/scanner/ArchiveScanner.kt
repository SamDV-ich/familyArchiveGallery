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
        val sourceId = root.parentFile?.name ?: root.absolutePath
        val categories = root.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.isHidden && !it.name.startsWith('.') }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .mapNotNull { category -> scanFileCategory(sourceId, root, category) }
            .toList()

        return ArchiveScanResult(
            sourceId = sourceId,
            categories = categories,
            hasNoMediaMarker = File(root, ".nomedia").isFile
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
        val categories = root.listFiles()
            .asSequence()
            .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }
            .sortedWith(compareBy(NaturalOrder) { it.name.orEmpty() })
            .mapNotNull { category -> scanDocumentCategory(sourceId, category) }
            .toList()

        return ArchiveScanResult(
            sourceId = sourceId,
            categories = categories,
            hasNoMediaMarker = root.findFile(".nomedia")?.isFile == true
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
                        child.isFile && child.canRead() && isSupportedImage(child.name) -> add(
                            PhotoItem(
                                id = stableId(sourceId, child.relativeTo(root).invariantSeparatorsPath),
                                categoryId = categoryId,
                                name = child.name,
                                relativePath = child.relativeTo(root).invariantSeparatorsPath,
                                source = child.absolutePath,
                                sourceType = PhotoSourceType.FILE,
                                size = child.length(),
                                lastModified = child.lastModified()
                            )
                        )
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
                        child.isFile && isSupportedImage(name) -> add(
                            PhotoItem(
                                id = stableId(sourceId, child.uri.toString()),
                                categoryId = categoryId,
                                name = name,
                                relativePath = "$path/$name",
                                source = child.uri.toString(),
                                sourceType = PhotoSourceType.CONTENT_URI,
                                size = child.length(),
                                lastModified = child.lastModified()
                            )
                        )
                    }
                }
            }
        }.sortedWith(compareBy(NaturalOrder) { it.relativePath })

        if (photos.isEmpty()) return null
        return PhotoCategory(categoryId, categoryName, categoryName, photos)
    }

    companion object {
        private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")

        fun isSupportedImage(name: String): Boolean = name
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase() in supportedExtensions

        private fun stableId(sourceId: String, path: String): String = UUID.nameUUIDFromBytes(
            "$sourceId|$path".toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }
}
