package com.example.familyarchivegallery.ui.components

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.example.familyarchivegallery.domain.model.PhotoItem
import com.example.familyarchivegallery.domain.model.PhotoSourceType
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
    val image by produceState<ImageBitmap?>(initialValue = null, cacheKey) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val source = when (photo.sourceType) {
                    PhotoSourceType.FILE -> ImageDecoder.createSource(File(photo.source))
                    PhotoSourceType.CONTENT_URI -> ImageDecoder.createSource(
                        context.contentResolver,
                        Uri.parse(photo.source)
                    )
                }
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
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
                }.asImageBitmap()
            }.getOrNull()
        }
    }

    if (image == null) {
        Box(modifier = modifier.background(Color(0xFF263241)))
    } else {
        Image(
            bitmap = requireNotNull(image),
            contentDescription = photo.name,
            contentScale = contentScale,
            modifier = modifier
        )
    }
}
