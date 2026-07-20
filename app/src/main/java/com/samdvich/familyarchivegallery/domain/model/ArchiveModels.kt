package com.samdvich.familyarchivegallery.domain.model

enum class PhotoSourceType {
    FILE,
    CONTENT_URI,
    USB_FILE
}

data class PhotoItem(
    val id: String,
    val categoryId: String,
    val sourceId: String,
    val name: String,
    val relativePath: String,
    val source: String,
    val sourceType: PhotoSourceType,
    val size: Long,
    val lastModified: Long
)

data class PhotoCategory(
    val id: String,
    val name: String,
    val relativePath: String,
    val photos: List<PhotoItem>
) {
    val previewPhotos: List<PhotoItem>
        get() = photos.take(4)
}

data class ArchiveScanResult(
    val sourceId: String,
    val categories: List<PhotoCategory>,
    val rootPhotos: List<PhotoItem>,
    val hasNoMediaMarker: Boolean,
    val firstLevelDirectoryCount: Int,
    val rootSupportedPhotoCount: Int
)
