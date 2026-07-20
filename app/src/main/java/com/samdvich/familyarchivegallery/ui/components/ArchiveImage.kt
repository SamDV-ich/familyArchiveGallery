package com.samdvich.familyarchivegallery.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import com.samdvich.familyarchivegallery.data.storage.UsbPhotoRegistry
import com.samdvich.familyarchivegallery.domain.model.PhotoItem
import com.samdvich.familyarchivegallery.domain.model.PhotoSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** The caller declares why it needs an image, so USB work stays bounded and visible work wins. */
enum class ImagePurpose {
    CATEGORY_PREVIEW,
    GRID_VISIBLE,
    VIEWER_NEIGHBOR,
    VIEWER_CURRENT,
    ZOOM_REGION
}

sealed interface ImageLoadState {
    data object Loading : ImageLoadState
    data class Ready(val bitmap: Bitmap) : ImageLoadState
    data object Error : ImageLoadState
}

@Composable
fun ArchiveImage(
    photo: PhotoItem,
    maxDimension: Int,
    contentScale: ContentScale,
    purpose: ImagePurpose,
    useArchiveDiskCache: Boolean = false,
    onLoadState: ((ImageLoadState) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cacheKey = remember(photo.id, photo.size, photo.lastModified, maxDimension) {
        "${photo.id}|${photo.size}|${photo.lastModified}|$maxDimension"
    }
    val cached = remember(cacheKey) { ArchiveBitmapCache.get(cacheKey) }
    val state by produceState<ImageLoadState>(
        initialValue = cached?.let(ImageLoadState::Ready) ?: ImageLoadState.Loading,
        cacheKey,
        purpose,
        useArchiveDiskCache
    ) {
        if (cached != null) return@produceState
        value = ImageLoadState.Loading
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageLoadCoordinator.load(
                    context = context,
                    photo = photo,
                    cacheKey = cacheKey,
                    maxDimension = maxDimension,
                    purpose = purpose,
                    useArchiveDiskCache = useArchiveDiskCache
                )
            }.fold(
                onSuccess = ImageLoadState::Ready,
                onFailure = { ImageLoadState.Error }
            )
        }
    }

    Box(modifier = modifier.background(Color(0xFF263241)), contentAlignment = Alignment.Center) {
        when (val current = state) {
            is ImageLoadState.Ready -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = photo.name,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
            ImageLoadState.Loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
            ImageLoadState.Error -> ArchiveImageErrorPlaceholder()
        }
    }
    LaunchedEffect(state) { onLoadState?.invoke(state) }
}

@Composable
private fun ArchiveImageErrorPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF3D2630))
    )
}

/**
 * A small coordinator rather than one decoder per composable. USB Host is intentionally
 * serial: concurrent reads through a hub are a common reason for stalled thumbnails.
 */
private object ImageLoadCoordinator {
    private val usbReads = priorityExecutor(workerCount = 1, threadName = "archive-usb-image")
    private val normalReads = priorityExecutor(workerCount = 2, threadName = "archive-image")

    suspend fun load(
        context: Context,
        photo: PhotoItem,
        cacheKey: String,
        maxDimension: Int,
        purpose: ImagePurpose,
        useArchiveDiskCache: Boolean
    ): Bitmap {
        ArchiveBitmapCache.get(cacheKey)?.let { return it }
        val allowDiskThumbnail = purpose == ImagePurpose.CATEGORY_PREVIEW || purpose == ImagePurpose.GRID_VISIBLE
        if (allowDiskThumbnail) {
            ArchiveDiskCache.read(context, photo, cacheKey, useArchiveDiskCache)?.let {
                ArchiveBitmapCache.put(cacheKey, it)
                return it
            }
        }
        val executor = if (photo.sourceType == PhotoSourceType.USB_FILE) usbReads else normalReads
        return execute(executor, purpose) {
            // Check again after waiting so composed grid cells never decode the same bitmap twice.
            ArchiveBitmapCache.get(cacheKey) ?: decodeBitmap(context, photo, maxDimension).also { bitmap ->
                ArchiveBitmapCache.put(cacheKey, bitmap)
                if (allowDiskThumbnail) {
                    ArchiveDiskCache.write(context, photo, cacheKey, bitmap, useArchiveDiskCache)
                }
            }
        }
    }

    private fun priorityExecutor(workerCount: Int, threadName: String): ThreadPoolExecutor =
        ThreadPoolExecutor(
            workerCount,
            workerCount,
            30L,
            TimeUnit.SECONDS,
            PriorityBlockingQueue(),
            { runnable -> Thread(runnable, threadName).apply { isDaemon = true } }
        )

    private suspend fun <T> execute(
        executor: ThreadPoolExecutor,
        purpose: ImagePurpose,
        block: () -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val task = PrioritizedTask(purpose, block, continuation)
        continuation.invokeOnCancellation { task.cancel(true) }
        executor.execute(task)
    }

    private class PrioritizedTask<T>(
        purpose: ImagePurpose,
        block: () -> T,
        private val continuation: kotlinx.coroutines.CancellableContinuation<T>
    ) : FutureTask<T>(Callable { block() }), Comparable<PrioritizedTask<*>> {
        private val priority = when (purpose) {
            ImagePurpose.ZOOM_REGION, ImagePurpose.VIEWER_CURRENT -> 0
            ImagePurpose.VIEWER_NEIGHBOR -> 1
            ImagePurpose.GRID_VISIBLE -> 2
            ImagePurpose.CATEGORY_PREVIEW -> 3
        }
        private val order = nextOrder.getAndIncrement()

        override fun compareTo(other: PrioritizedTask<*>): Int =
            compareValuesBy(this, other, PrioritizedTask<*>::priority, PrioritizedTask<*>::order)

        override fun done() {
            if (isCancelled || continuation.isCancelled) return
            try {
                continuation.resume(get())
            } catch (error: Throwable) {
                continuation.resumeWithException(error.cause ?: error)
            }
        }
    }

    private val nextOrder = AtomicLong()
}

private fun decodeBitmap(context: Context, photo: PhotoItem, maxDimension: Int): Bitmap {
    if (photo.sourceType == PhotoSourceType.USB_FILE) return decodeUsbBitmap(photo.source, maxDimension)
    val source = when (photo.sourceType) {
        PhotoSourceType.FILE -> ImageDecoder.createSource(File(photo.source))
        PhotoSourceType.CONTENT_URI -> ImageDecoder.createSource(context.contentResolver, Uri.parse(photo.source))
        PhotoSourceType.USB_FILE -> error("Handled before creating an ImageDecoder source")
    }
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val largestSide = max(info.size.width, info.size.height)
        if (largestSide > maxDimension) {
            val scale = maxDimension.toFloat() / largestSide
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1)
            )
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

private fun decodeUsbBitmap(source: String, maxDimension: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    UsbPhotoRegistry.open(source).use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported USB image" }
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return requireNotNull(UsbPhotoRegistry.open(source).use {
        BitmapFactory.decodeStream(it, null, options)
    }) { "Unable to decode USB image" }
}

/** Bounded thumbnail cache. Full-screen frames and zoom frames are deliberately never written. */
private object ArchiveDiskCache {
    private const val INTERNAL_LIMIT = 64L * 1024L * 1024L
    private const val ARCHIVE_LIMIT = 256L * 1024L * 1024L

    fun read(context: Context, photo: PhotoItem, key: String, useArchive: Boolean): Bitmap? {
        val file = cacheFile(context, photo, key, useArchive) ?: return null
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun write(context: Context, photo: PhotoItem, key: String, bitmap: Bitmap, useArchive: Boolean) {
        val file = cacheFile(context, photo, key, useArchive) ?: return
        val directory = file.parentFile ?: return
        if (!directory.exists() && !directory.mkdirs()) return
        File(directory, ".nomedia").takeIf { !it.exists() }?.runCatching { createNewFile() }
        val temporary = File(directory, "${file.name}.tmp")
        runCatching {
            temporary.outputStream().use { output ->
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 85, output)
            }
            if (!temporary.renameTo(file)) {
                file.delete()
                temporary.renameTo(file)
            }
            trim(directory, if (directory.name == ".familyarchivegallery-cache") ARCHIVE_LIMIT else INTERNAL_LIMIT)
        }.onFailure { temporary.delete() }
    }

    private fun cacheFile(context: Context, photo: PhotoItem, key: String, useArchive: Boolean): File? {
        val directory = if (useArchive && photo.sourceType == PhotoSourceType.FILE) {
            File(photo.sourceId, ".familyarchivegallery-cache").takeIf { cacheDirectory ->
                cacheDirectory.parentFile?.canWrite() == true
            }
        } else {
            File(context.cacheDir, "familyarchivegallery-thumbnails")
        } ?: File(context.cacheDir, "familyarchivegallery-thumbnails")
        return File(directory, sha256(key) + ".webp")
    }

    private fun trim(directory: File, limit: Long) {
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".webp") }
            ?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf(File::length)
        files.forEach { file ->
            if (total <= limit) return
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private object ArchiveBitmapCache {
    private const val KILOBYTE = 1024
    private val maxSizeKb = (Runtime.getRuntime().maxMemory() / 16L / KILOBYTE)
        .coerceIn(8L * KILOBYTE, 32L * KILOBYTE).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / KILOBYTE).coerceAtLeast(1)
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) = cache.put(key, bitmap)
}
