package com.samdvich.familyarchivegallery.data.update

data class UpdateInfo(
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val checksumUrl: String?,
    val sha256: String?
)

enum class UpdateFailureStage {
    CHECK,
    DOWNLOAD,
    CHECKSUM
}

class UpdateException(
    val stage: UpdateFailureStage,
    val diagnostic: String,
    cause: Throwable? = null
) : Exception(diagnostic, cause)

enum class UpdateStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    INSTALLING,
    ERROR
}

data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val info: UpdateInfo? = null,
    val progress: Int = 0,
    val downloadedFile: String? = null,
    val message: String? = null
)
