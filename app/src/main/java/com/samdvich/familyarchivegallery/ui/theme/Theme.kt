package com.samdvich.familyarchivegallery.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val ArchiveColors = darkColorScheme(
    primary = Color(0xFF80CBC4),
    onPrimary = Color(0xFF00201D),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFF2F5F8),
    surface = Color(0xFF151B24),
    onSurface = Color(0xFFF2F5F8),
    surfaceVariant = Color(0xFF263241),
    onSurfaceVariant = Color(0xFFC8D3DF),
    error = Color(0xFFFFB4AB)
)

@Composable
fun FamilyArchiveGalleryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ArchiveColors,
        content = content
    )
}
