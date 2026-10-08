package io.github.avtraang.selfbubbles

// The attachment side of the composer: FullscreenImage (the in-app pinch-to-zoom viewer),
// the recent-camera-roll AttachmentPanel with its RecentMedia row and loadRecentMedia query,
// and the ActionChip pill it shares with the per-message actions in Bubble.kt. Moved verbatim
// out of MainActivity.kt (SelfBubbles split, step C8b).

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import io.github.avtraang.selfbubbles.ui.theme.Spacing

private data class RecentMedia(val uri: Uri, val video: Boolean, val thumb: android.graphics.Bitmap)

/**
 * The runtime permissions the recent-photos grid asks for on a phone running
 * API [sdkInt]: the per-type media permissions from Android 13, and the one
 * storage permission before it (declared in the manifest with maxSdkVersion 32;
 * the newer names do not exist there, so a request for them was denied unseen).
 */
internal fun recentMediaPermissions(sdkInt: Int): List<String> =
    if (sdkInt >= android.os.Build.VERSION_CODES.TIRAMISU) {
        listOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/**
 * A grid thumbnail on Android 9, which has no ContentResolver.loadThumbnail (API 29):
 * its MediaStore hands out a mini thumbnail by row id. Null when it has none.
 */
@Suppress("DEPRECATION")
private fun legacyThumbnail(ctx: Context, id: Long, video: Boolean): android.graphics.Bitmap? =
    if (video) {
        MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
    } else {
        MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
    }

/** Full-screen image viewer: pinch to zoom, drag to pan, tap to dismiss.
 *  In-app rather than an external intent — Coil already sends the auth token,
 *  and other apps can't. */
@Composable
internal fun FullscreenImage(url: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else onClose() },
                        onDoubleTap = {
                            if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url, contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    translationX = offset.x, translationY = offset.y,
                ),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).padding(Spacing.sm),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = MessagesTheme.colors.onScrim)
            }
        }
    }
}


/** Newest 60 photos/videos from the device, with MediaStore thumbnails —
 *  works for video too, no extra image-loading dependencies. */
private fun loadRecentMedia(ctx: Context): List<RecentMedia> {
    val out = ArrayList<RecentMedia>()
    val proj = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
    )
    runCatching {
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"), proj,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)",
            arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            ),
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mtI = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (c.moveToNext() && out.size < 60) {
                val id = c.getLong(idI)
                val video = c.getInt(mtI) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val uri = if (video)
                    android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                else
                    android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                runCatching {
                    val bmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        ctx.contentResolver.loadThumbnail(uri, android.util.Size(320, 320), null)
                    } else {
                        legacyThumbnail(ctx, id, video) ?: error("no thumbnail")
                    }
                    out.add(RecentMedia(uri, video, bmp))
                }
            }
        }
    }
    return out
}

/** GM-style inline attachment panel: recent camera roll in a grid, tap to
 *  multi-select, Send pill; Gallery/Files chips open the full pickers. */
@Composable
internal fun AttachmentPanel(
    onGallery: () -> Unit,
    onGif: () -> Unit,
    onFiles: () -> Unit,
    onSend: (List<Uri>) -> Unit,
) {
    val ctx = LocalContext.current
    // Android 13+: READ_MEDIA_IMAGES and READ_MEDIA_VIDEO, as before. Android 12 and older: READ_EXTERNAL_STORAGE.
    val perms = remember { recentMediaPermissions(android.os.Build.VERSION.SDK_INT).toTypedArray() }
    fun granted() = perms.all {
        ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var hasPerm by remember { mutableStateOf(granted()) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPerm = granted() }
    var recent by remember { mutableStateOf<List<RecentMedia>>(emptyList()) }
    val selected = remember { mutableStateListOf<Uri>() }

    LaunchedEffect(hasPerm) {
        if (hasPerm && recent.isEmpty()) {
            recent = withContext(Dispatchers.IO) { loadRecentMedia(ctx) }
        }
    }

    Column(Modifier.fillMaxWidth().height(Dimens.attachPanelHeight)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            ActionChip("Gallery", onGallery)
            Spacer(Modifier.width(Spacing.sm))
            ActionChip("GIF", onGif)
            Spacer(Modifier.width(Spacing.sm))
            ActionChip("Files", onFiles)
            Spacer(Modifier.weight(1f))
            if (selected.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape,
                    modifier = Modifier.clip(CircleShape)
                        .clickable { onSend(selected.toList()); selected.clear() },
                ) {
                    Text(
                        "Send ${selected.size}", color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    )
                }
            }
        }
        when {
            !hasPerm -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // TextButton's default content color is colorScheme.primary.
                TextButton(onClick = { permLauncher.launch(perms) }) {
                    Text("Allow photo access")
                }
            }
            recent.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(Dimens.iconSmall), strokeWidth = Dimens.progressStroke)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.xxs),
            ) {
                items(recent, key = { it.uri }) { item ->
                    val isSel = selected.contains(item.uri)
                    val scheme = MaterialTheme.colorScheme
                    Box(
                        Modifier.aspectRatio(1f)
                            .clip(RoundedCornerShape(Dimens.thumbRadius))
                            .clickable {
                                if (isSel) selected.remove(item.uri) else selected.add(item.uri)
                            }
                            .then(
                                if (isSel) Modifier.border(Dimens.selectionBorder, scheme.primaryContainer, RoundedCornerShape(Dimens.thumbRadius))
                                else Modifier
                            ),
                    ) {
                        Image(
                            bitmap = item.thumb.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item.video) {
                            Box(
                                Modifier.align(Alignment.Center).size(Dimens.playOverlaySmall)
                                    .clip(CircleShape).background(scheme.scrim.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("\u25B6", color = MessagesTheme.colors.onScrim,
                                     style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (isSel) {
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(Spacing.xs).size(Dimens.iconSmall)
                                    .clip(CircleShape).background(scheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null,
                                     tint = scheme.onPrimaryContainer, modifier = Modifier.size(Dimens.iconTiny))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Small pill button used for per-message actions (copy code, translate) and the
 *  attachment panel (Gallery, GIF, Files). labelMedium (13/18) with Spacing.sm
 *  padding makes a 34dp pill: the spec puts picker labels at 13sp and keeps
 *  labelSmall for reaction counts and delivery status, and the chip is tapped,
 *  so it must not shrink below the 32dp it had before the theme. */
@Composable
internal fun ActionChip(label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer, shape = CircleShape,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
             modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm))
    }
}
