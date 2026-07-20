package com.samdvich.familyarchivegallery.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.samdvich.familyarchivegallery.AccessRequest
import com.samdvich.familyarchivegallery.ArchiveScreen
import com.samdvich.familyarchivegallery.ArchiveStatus
import com.samdvich.familyarchivegallery.ArchiveUiState
import com.samdvich.familyarchivegallery.BuildConfig
import com.samdvich.familyarchivegallery.R
import com.samdvich.familyarchivegallery.domain.model.PhotoCategory
import com.samdvich.familyarchivegallery.domain.model.PhotoItem
import com.samdvich.familyarchivegallery.domain.model.PhotoSourceType
import com.samdvich.familyarchivegallery.data.update.UpdateStatus
import com.samdvich.familyarchivegallery.data.update.UpdateUiState
import com.samdvich.familyarchivegallery.ui.components.ArchiveImage

@Composable
fun FamilyArchiveApp(
    uiState: ArchiveUiState,
    screen: ArchiveScreen,
    updateState: UpdateUiState,
    onGrantAccess: (AccessRequest) -> Unit,
    onRefresh: () -> Unit,
    onPrepareUsbRemoval: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenPhoto: (String, Int) -> Unit,
    onMoveViewer: (Int) -> Unit,
    onUpdateAction: () -> Unit,
    onBack: () -> Unit
) {
    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (uiState.status) {
                ArchiveStatus.CHECKING -> StatusScreen(
                    title = stringResource(R.string.search_archive_title),
                    message = stringResource(R.string.search_archive_message)
                )
                ArchiveStatus.ACCESS_REQUIRED -> StatusScreen(
                    title = stringResource(R.string.access_required_title),
                    message = uiState.message ?: accessMessage(uiState.accessRequest),
                    actionLabel = accessActionLabel(uiState.accessRequest),
                    onAction = { uiState.accessRequest?.let(onGrantAccess) }
                )
                ArchiveStatus.NO_STORAGE -> StatusScreen(
                    title = stringResource(R.string.no_usb_title),
                    message = uiState.message,
                    actionLabel = stringResource(R.string.retry_search),
                    onAction = onRefresh
                )
                ArchiveStatus.ARCHIVE_NOT_FOUND -> StatusScreen(
                    title = stringResource(R.string.archive_not_found_title),
                    message = uiState.message,
                    actionLabel = stringResource(R.string.retry_search),
                    onAction = onRefresh
                )
                ArchiveStatus.ERROR -> StatusScreen(
                    title = stringResource(R.string.archive_open_error_title),
                    message = uiState.message,
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRefresh
                )
                ArchiveStatus.READY -> {
                    BackHandler(enabled = screen !is ArchiveScreen.Categories) { onBack() }
                    when (screen) {
                        ArchiveScreen.Categories -> CategoriesScreen(
                            state = uiState,
                            updateState = updateState,
                            onRefresh = onRefresh,
                            onPrepareUsbRemoval = onPrepareUsbRemoval,
                            onUpdateAction = onUpdateAction,
                            onOpenCategory = onOpenCategory
                        )
                        is ArchiveScreen.Photos -> uiState.categories
                            .firstOrNull { it.id == screen.categoryId }
                            ?.let { category ->
                                PhotosScreen(
                                    category = category,
                                    onOpenPhoto = { onOpenPhoto(category.id, it) }
                                )
                            }
                        is ArchiveScreen.Viewer -> uiState.categories
                            .firstOrNull { it.id == screen.categoryId }
                            ?.let { category ->
                                PhotoViewerScreen(
                                    category = category,
                                    index = screen.photoIndex,
                                    onMove = onMoveViewer,
                                    onBack = onBack
                                )
                            }
                    }
                }
            }

            if (uiState.status != ArchiveStatus.READY) {
                ActionButton(
                    label = updateButtonLabel(updateState),
                    onClick = onUpdateAction,
                    enabled = updateState.status != UpdateStatus.CHECKING &&
                        updateState.status != UpdateStatus.DOWNLOADING,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(36.dp)
                )
            }

            Text(
                text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            )
        }
    }
}

@Composable
private fun StatusScreen(
    title: String,
    message: String?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 96.dp, vertical = 64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 34.sp
        )
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 20.sp
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(30.dp))
            ActionButton(label = actionLabel, onClick = onAction)
        }
    }
}

@Composable
private fun CategoriesScreen(
    state: ArchiveUiState,
    updateState: UpdateUiState,
    onRefresh: () -> Unit,
    onPrepareUsbRemoval: () -> Unit,
    onUpdateAction: () -> Unit,
    onOpenCategory: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 58.dp, end = 58.dp, top = 38.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.family_archive_title),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 34.sp
                )
                Text(
                    text = if (state.isScanning) {
                        stringResource(R.string.scanning_archive)
                    } else {
                        pluralStringResource(
                            R.plurals.category_count,
                            state.categories.size,
                            state.categories.size
                        )
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 17.sp
                )
            }
            ActionButton(
                label = updateButtonLabel(updateState),
                onClick = onUpdateAction,
                enabled = updateState.status != UpdateStatus.CHECKING &&
                    updateState.status != UpdateStatus.DOWNLOADING
            )
        }

        updateState.message?.let { message ->
            Text(
                text = message,
                color = if (updateState.status == UpdateStatus.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 58.dp, vertical = 4.dp),
                fontSize = 16.sp
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 58.dp, vertical = 4.dp),
                fontSize = 16.sp
            )
        }

        if (!state.hasNoMediaMarker) {
            Text(
                text = stringResource(R.string.nomedia_warning),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 58.dp, vertical = 4.dp),
                fontSize = 16.sp
            )
        }

        if (state.categories.isEmpty()) {
            StatusScreen(
                title = stringResource(R.string.archive_empty_title),
                message = state.message ?: stringResource(R.string.archive_empty_message),
                actionLabel = stringResource(R.string.refresh_photo_list),
                onAction = onRefresh
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(start = 52.dp, end = 52.dp, top = 14.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(state.categories, key = { it.id }) { category ->
                    CategoryCard(category = category, onClick = { onOpenCategory(category.id) })
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 58.dp, end = 58.dp, top = 8.dp, bottom = 28.dp)
            ) {
                ActionButton(
                    label = stringResource(R.string.refresh_photo_list),
                    onClick = onRefresh
                )
                if (state.categories.any { category ->
                        category.photos.any { it.sourceType == PhotoSourceType.USB_FILE }
                    }) {
                    Spacer(Modifier.width(14.dp))
                    ActionButton(
                        label = stringResource(R.string.prepare_usb_removal),
                        onClick = onPrepareUsbRemoval
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(category: PhotoCategory, onClick: () -> Unit) {
    FocusableTile(onClick = onClick) {
        Column {
            PreviewCollage(
                photos = category.previewPhotos,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            )
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                Text(
                    text = category.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 20.sp
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.photo_count,
                        category.photos.size,
                        category.photos.size
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
private fun PreviewCollage(photos: List<PhotoItem>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        repeat(2) { row ->
            Row(modifier = Modifier.weight(1f)) {
                repeat(2) { column ->
                    val photo = photos.getOrNull(row * 2 + column)
                    if (photo == null) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(Color(0xFF263241))
                        )
                    } else {
                        ArchiveImage(
                            photo = photo,
                            maxDimension = 480,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotosScreen(category: PhotoCategory, onOpenPhoto: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 58.dp, top = 38.dp, bottom = 16.dp)) {
            Text(
                text = category.name,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 32.sp
            )
            Text(
                pluralStringResource(
                    R.plurals.photo_count,
                    category.photos.size,
                    category.photos.size
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 17.sp
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(start = 52.dp, end = 52.dp, bottom = 42.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(category.photos, key = { _, photo -> photo.id }) { index, photo ->
                FocusableTile(onClick = { onOpenPhoto(index) }) {
                    ArchiveImage(
                        photo = photo,
                        maxDimension = 640,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(9.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoViewerScreen(
    category: PhotoCategory,
    index: Int,
    onMove: (Int) -> Unit,
    onBack: () -> Unit
) {
    val photo = category.photos[index]
    val focusRequester = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onMove(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onMove(1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        controlsVisible = !controlsVisible
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        ArchiveImage(
            photo = photo,
            maxDimension = 1920,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xB3000000))
                    .padding(horizontal = 42.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = photo.name,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    fontSize = 19.sp
                )
                Spacer(Modifier.width(24.dp))
                Text(
                    text = "${index + 1} / ${category.photos.size}",
                    color = Color.White,
                    fontSize = 18.sp
                )
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun FocusableTile(onClick: () -> Unit, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = if (focused) 1.045f else 1f
                scaleY = if (focused) 1.045f else 1f
            }
            .border(
                border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent),
                shape = shape
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        content()
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    val background = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (focused && enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                // Keep a disabled asynchronous action focused on TV. Otherwise the
                // focus engine jumps to a different button while it is running.
                enabled = true,
                interactionSource = interactionSource,
                indication = null,
                onClick = { if (enabled) onClick() }
            )
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(text = label, color = foreground, fontSize = 16.sp)
    }
}

@Composable
private fun accessMessage(request: AccessRequest?): String = when (request) {
    AccessRequest.LEGACY_READ -> stringResource(R.string.access_legacy_message)
    AccessRequest.DOCUMENT_TREE -> stringResource(R.string.access_document_tree_message)
    AccessRequest.ALL_FILES -> stringResource(R.string.access_all_files_message)
    AccessRequest.USB_DEVICE -> stringResource(R.string.access_usb_host_message)
    null -> stringResource(R.string.access_default_message)
}

@Composable
private fun accessActionLabel(request: AccessRequest?): String = when (request) {
    AccessRequest.DOCUMENT_TREE -> stringResource(R.string.select_folder)
    AccessRequest.ALL_FILES -> stringResource(R.string.open_file_access_settings)
    AccessRequest.USB_DEVICE -> stringResource(R.string.grant_usb_access)
    AccessRequest.LEGACY_READ, null -> stringResource(R.string.grant_access)
}

@Composable
private fun updateButtonLabel(state: UpdateUiState): String = when (state.status) {
    UpdateStatus.IDLE, UpdateStatus.UP_TO_DATE -> stringResource(R.string.check_updates)
    UpdateStatus.CHECKING -> stringResource(R.string.checking_updates)
    UpdateStatus.AVAILABLE -> stringResource(
        R.string.update_to_version,
        state.info?.versionName.orEmpty()
    )
    UpdateStatus.DOWNLOADING -> stringResource(R.string.downloading_update, state.progress)
    UpdateStatus.INSTALLING -> stringResource(R.string.opening_installer)
    UpdateStatus.ERROR -> stringResource(R.string.retry_update)
}
