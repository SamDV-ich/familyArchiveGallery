package com.samdvich.familyarchivegallery.ui.components

import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.samdvich.familyarchivegallery.domain.model.PhotoItem
import com.samdvich.familyarchivegallery.domain.model.PhotoSourceType
import com.samdvich.familyarchivegallery.data.storage.UsbPhotoRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

@Composable
fun ArchiveImage(
    photo: PhotoItem,
    maxDimension: Int,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cacheKey = "${photo.source}|${photo.size}|${photo.lastModified}|$maxDimension"
    val cached = remember(cacheKey) { ArchiveBitmapCache.get(cacheKey) }
    val bitmap by produceState<Bitmap?>(initialValue = cached, cacheKey) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            ArchiveBitmapCache.get(cacheKey) ?: runCatching {
                decodeBitmap(context, photo, maxDimension)
            }.getOrNull()?.also { ArchiveBitmapCache.put(cacheKey, it) }
        }
    }

    if (bitmap == null) {
        Box(modifier = modifier.background(Color(0xFF263241)))
    } else {
        Image(
            bitmap = requireNotNull(bitmap).asImageBitmap(),
            contentDescription = photo.name,
            contentScale = contentScale,
            modifier = modifier
        )
    }
}

private fun decodeBitmap(context: android.content.Context, photo: PhotoItem, maxDimension: Int): Bitmap {
    if (photo.sourceType == PhotoSourceType.USB_FILE) {
        return decodeUsbBitmap(photo.source, maxDimension)
    }
    val source = when (photo.sourceType) {
        PhotoSourceType.FILE -> ImageDecoder.createSource(File(photo.source))
        PhotoSourceType.CONTENT_URI -> ImageDecoder.createSource(context.contentResolver, Uri.parse(photo.source))
        PhotoSourceType.USB_FILE -> error("Handled before creating an ImageDecoder source")
    }
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        val largestSide = max(width, height)
        if (largestSide > maxDimension) {
            val scale = maxDimension.toFloat() / largestSide
            decoder.setTargetSize(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1)
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
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return requireNotNull(UsbPhotoRegistry.open(source).use {
        BitmapFactory.decodeStream(it, null, options)
    }) { "Unable to decode USB image" }
}

private object ArchiveBitmapCache {
    private const val KILOBYTE = 1024
    private val maxSizeKb = (Runtime.getRuntime().maxMemory() / 16L / KILOBYTE)
        .coerceIn(8L * KILOBYTE, 32L * KILOBYTE)
        .toInt()
    private val cache = object : LruCache<String, Bitmap>(maxSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / KILOBYTE).coerceAtLeast(1)
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}
