package com.samdvich.familyarchivegallery.data.update

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class GitHubUpdateRepository(
    private val context: Context,
    private val owner: String,
    private val repository: String,
    private val apkName: String
) {
    fun findUpdate(currentVersion: String): UpdateInfo? {
        return try {
            val release = JSONObject(
                requestText(
                    "https://api.github.com/repos/$owner/$repository/releases/latest",
                    githubApi = true,
                    stage = UpdateFailureStage.CHECK
                )
            )
            val versionName = release.getString("tag_name").removePrefix("v")
            if (!VersionOrder.isNewer(versionName, currentVersion)) return null

            val assets = release.getJSONArray("assets")
            var apkAsset: JSONObject? = null
            var checksumUrl: String? = null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                when (asset.getString("name")) {
                    apkName -> apkAsset = asset
                    "$apkName.sha256" -> checksumUrl = asset.getString("browser_download_url")
                }
            }
            val apk = apkAsset ?: throw UpdateException(
                UpdateFailureStage.CHECK,
                "Release $versionName does not contain $apkName"
            )
            val digest = apk.optString("digest")
                .takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")

            UpdateInfo(
                versionName = versionName,
                releaseNotes = release.optString("body"),
                downloadUrl = apk.getString("browser_download_url"),
                checksumUrl = checksumUrl,
                sha256 = digest
            )
        } catch (error: UpdateException) {
            throw error
        } catch (error: Exception) {
            throw UpdateException(UpdateFailureStage.CHECK, safeMessage(error), error)
        }
    }

    fun download(update: UpdateInfo, onProgress: (Int) -> Unit): File {
        val expectedChecksum = try {
            update.sha256
                ?: update.checksumUrl?.let {
                    requestText(it, stage = UpdateFailureStage.DOWNLOAD).trim().substringBefore(' ')
                }
                ?: throw UpdateException(UpdateFailureStage.CHECKSUM, "Release has no SHA-256 checksum")
        } catch (error: UpdateException) {
            throw error
        } catch (error: Exception) {
            throw UpdateException(UpdateFailureStage.CHECKSUM, safeMessage(error), error)
        }

        val updateDirectory = File(context.cacheDir, "updates").apply { mkdirs() }
        val temporaryFile = File(updateDirectory, "$apkName.part").apply { delete() }
        val destinationFile = File(updateDirectory, apkName).apply { delete() }
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = openConnection(update.downloadUrl)

        try {
            ensureSuccessful(connection, UpdateFailureStage.DOWNLOAD)
            val total = connection.contentLengthLong
            var copied = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }

            val actualChecksum = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                throw UpdateException(
                    UpdateFailureStage.CHECKSUM,
                    "Downloaded APK checksum does not match the GitHub Release"
                )
            }
            if (!temporaryFile.renameTo(destinationFile)) {
                throw UpdateException(UpdateFailureStage.DOWNLOAD, "Unable to prepare the downloaded APK")
            }
            onProgress(100)
            return destinationFile
        } catch (error: UpdateException) {
            temporaryFile.delete()
            destinationFile.delete()
            throw error
        } catch (error: Exception) {
            temporaryFile.delete()
            destinationFile.delete()
            throw UpdateException(UpdateFailureStage.DOWNLOAD, safeMessage(error), error)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestText(
        url: String,
        githubApi: Boolean = false,
        stage: UpdateFailureStage
    ): String {
        val connection = openConnection(url, githubApi)
        return try {
            ensureSuccessful(connection, stage)
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (requestFailure: Exception) {
            val details = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (requestFailure is UpdateException) throw requestFailure
            val code = runCatching { connection.responseCode }.getOrDefault(-1)
            val detail = if (code == 404) {
                "No published GitHub Release was found (HTTP 404)"
            } else {
                "GitHub request failed (HTTP $code): ${details.take(120)}"
            }
            throw UpdateException(stage, detail, requestFailure)
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureSuccessful(connection: HttpURLConnection, stage: UpdateFailureStage) {
        val code = runCatching { connection.responseCode }.getOrElse { error ->
            throw UpdateException(stage, safeMessage(error), error)
        }
        if (code !in 200..299) {
            throw UpdateException(stage, "HTTP $code")
        }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

    private fun openConnection(url: String, githubApi: Boolean = false): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "familyarchivegallery-android-tv")
            if (githubApi) {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
        }
}
