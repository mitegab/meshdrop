package com.meshdrop.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thumbnail for a picked or received file: a real preview for images and videos, a file-type
 * icon otherwise.
 *
 * Loaded off the main thread and only on Android 10+, where `ContentResolver.loadThumbnail`
 * does the work for us (no decoder, no glide dependency).
 */
@Composable
fun FileThumb(
    uri: Uri?,
    mimeType: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri, mimeType) {
        val target = uri
        if (target == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            bitmap = null
            return@LaunchedEffect
        }
        val isVisual = mimeType?.startsWith("image/") == true || mimeType?.startsWith("video/") == true
        if (!isVisual) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(target, Size(256, 256), null) }
                .getOrNull()
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = iconForMime(mimeType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.5f),
            )
        }
    }
}

/** One icon per file family, reused by the picker, the transfer panel and history. */
fun iconForMime(mimeType: String?): ImageVector = when {
    mimeType?.startsWith("image/") == true -> Icons.Default.Image
    mimeType?.startsWith("video/") == true -> Icons.Default.VideoFile
    mimeType?.startsWith("audio/") == true -> Icons.Default.AudioFile
    else -> Icons.Default.Description
}
