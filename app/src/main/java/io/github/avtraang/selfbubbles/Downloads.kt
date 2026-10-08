package io.github.avtraang.selfbubbles

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.core.content.FileProvider
import okhttp3.Request
import java.io.File

/**
 * The one line shown wherever an attachment can no longer be fetched because the
 * relay's Mac no longer has the file (Messages in iCloud keeps older attachments
 * only in the cloud; the relay answers [ATTACHMENT_MISSING_STATUS]).
 */
const val ATTACHMENT_MISSING_MESSAGE = "Not on the Mac anymore"

/** The relay's answer for an attachment that is gone from the Mac's disk. */
const val ATTACHMENT_MISSING_STATUS = 404

/**
 * Saves an attachment straight into the camera roll (Pictures/Movies → "imsg"),
 * so it shows up in Google Photos like any other download.
 *
 * Uses MediaStore, which needs no storage permission on API 29+ — the OS owns
 * the file and hands us a scoped URI to write into.
 */
object Downloads {

    suspend fun save(ctx: Context, url: String, name: String?, mime: String): Boolean =
        withContext(Dispatchers.IO) {
            val isVideo = mime.startsWith("video/")
            val filename = (name?.takeIf { it.isNotBlank() }
                ?: "imsg-${System.currentTimeMillis()}")
                .replace('/', '_')

            val collection = if (isVideo) {
                if (Build.VERSION.SDK_INT >= 29)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                if (Build.VERSION.SDK_INT >= 29)
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                if (Build.VERSION.SDK_INT >= 29) {
                    val dir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/imsg")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = ctx.contentResolver
            val item = runCatching { resolver.insert(collection, values) }.getOrNull()
                ?: return@withContext false

            val ok = runCatching {
                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    resolver.openOutputStream(item)?.use { out ->
                        resp.body!!.byteStream().copyTo(out)
                    } ?: return@use false
                    true
                }
            }.getOrDefault(false)

            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                runCatching { resolver.update(item, values, null, null) }
            }
            if (!ok) runCatching { resolver.delete(item, null, null) }
            ok
        }

    /**
     * Opens an attachment in another app (video player, PDF viewer) by first
     * downloading it and handing over a content:// URI.
     *
     * Passing the relay URL directly doesn't work: the other app can't send our
     * auth header, and query-param tokens get stripped by some apps (Google
     * Photos). A FileProvider URI carries a temporary read grant instead, so the
     * viewer never touches the network at all.
     */
    suspend fun openExternally(ctx: Context, url: String, name: String?, mime: String): Boolean =
        openExternallyOutcome(ctx, url, name, mime) == OpenOutcome.OK

    /** How [openExternallyOutcome] ended: [MISSING] when the relay no longer has the file. */
    enum class OpenOutcome { OK, MISSING, FAILED }

    /** [openExternally], telling a file that is gone from the Mac apart from any other failure. */
    suspend fun openExternallyOutcome(ctx: Context, url: String, name: String?, mime: String): OpenOutcome {
        val fileName = (name?.takeIf { it.isNotBlank() }
            ?: "imsg-${System.currentTimeMillis()}").replace('/', '_')
        val file = when (val got = fetchToCache(ctx, url, fileName)) {
            is CacheFetch.Ok -> got.file
            CacheFetch.Missing -> return OpenOutcome.MISSING
            CacheFetch.TooLarge, CacheFetch.Failed -> return OpenOutcome.FAILED
        }

        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }.getOrNull() ?: return OpenOutcome.FAILED

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
        return if (started) OpenOutcome.OK else OpenOutcome.FAILED
    }

    /** Result of [fetchToCache]. */
    sealed interface CacheFetch {
        class Ok(val file: File) : CacheFetch
        data object TooLarge : CacheFetch
        /** The relay answered [ATTACHMENT_MISSING_STATUS]: the file is no longer on the Mac, so a retry cannot help. */
        data object Missing : CacheFetch
        data object Failed : CacheFetch
    }

    /**
     * Downloads [url] through the shared client into cacheDir/shared (the
     * directory the FileProvider exposes) as [fileName], streaming to disk. The
     * body goes to a temporary file first and is renamed when complete, so a
     * half-written file never sits under the final name. Stops with
     * [CacheFetch.TooLarge] as soon as the declared or the received size passes
     * [maxBytes]. [fileName] must already be a plain file name.
     *
     * [subDir] (a plain folder name) puts the file in its own folder, so files
     * with the same name from different attachments cannot replace each other.
     * With [reuse], a complete copy already at that path is returned instead of
     * downloading again.
     */
    suspend fun fetchToCache(
        ctx: Context,
        url: String,
        fileName: String,
        maxBytes: Long = Long.MAX_VALUE,
        subDir: String? = null,
        reuse: Boolean = false,
    ): CacheFetch = withContext(Dispatchers.IO) {
        var part: File? = null
        try {
            val shared = File(ctx.cacheDir, SHARED_DIR).apply { mkdirs() }
            // Keep the cache from growing without bound.
            prune(shared, System.currentTimeMillis() - 86_400_000)
            val dir = if (subDir == null) shared else File(shared, subDir).apply { mkdirs() }
            val target = File(dir, fileName)
            if (reuse && target.isFile && target.length() in 1..maxBytes) {
                target.setLastModified(System.currentTimeMillis())   // in use: keep it from being pruned
                return@withContext CacheFetch.Ok(target)
            }
            val tmp = File.createTempFile("dl-", ".part", dir)
            part = tmp
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { r ->
                if (r.code == ATTACHMENT_MISSING_STATUS) return@withContext CacheFetch.Missing
                if (!r.isSuccessful) return@withContext CacheFetch.Failed
                val body = r.body ?: return@withContext CacheFetch.Failed
                if (body.contentLength() > maxBytes) return@withContext CacheFetch.TooLarge
                var total = 0L
                val buf = ByteArray(64 * 1024)
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) return@withContext CacheFetch.TooLarge
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            ensureActive()   // a cancelled download must not replace the file under the final name
            if (!tmp.renameTo(target)) return@withContext CacheFetch.Failed
            CacheFetch.Ok(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CacheFetch.Failed
        } finally {
            part?.delete()   // gone already after a successful rename
        }
    }

    /** Deletes files last touched before [cutoff], and folders left empty since then. */
    private fun prune(dir: File, cutoff: Long) {
        dir.listFiles()?.forEach {
            if (it.isDirectory) prune(it, cutoff)
            // File.delete() refuses a folder that still has something in it.
            if (it.lastModified() < cutoff) it.delete()
        }
    }

    /**
     * Offers every app that can show a PDF, always through the system chooser:
     * a remembered default (an office suite, say) would otherwise swallow the
     * hand-off. [file] must be inside cacheDir/shared.
     */
    fun openPdfInAnotherApp(ctx: Context, file: File): Boolean = openInAnotherApp(ctx, file, PDF_MIME)

    /**
     * Offers every app that can show [mime], always through the system chooser,
     * with a read-only grant on the cached [file] (which must be inside
     * cacheDir/shared). Safe to call with an application context: the chooser
     * starts in its own task.
     */
    fun openInAnotherApp(ctx: Context, file: File, mime: String): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }.getOrNull() ?: return false
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, "Open with").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { ctx.startActivity(chooser); true }.getOrDefault(false)
    }

    /**
     * Copies an already-downloaded PDF into the device's Downloads folder.
     * MediaStore.Downloads exists from API 29; below that this returns false and
     * the viewer does not offer the button.
     */
    suspend fun savePdfToDownloads(ctx: Context, file: File, displayName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < 29) return@withContext false
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, PDF_MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val item = runCatching {
                resolver.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
                )
            }.getOrNull() ?: return@withContext false

            var ok = false
            try {
                ok = runCatching {
                    val out = resolver.openOutputStream(item) ?: return@runCatching false
                    out.use { o -> file.inputStream().use { it.copyTo(o) } }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(item, values, null, null) > 0
                }.getOrDefault(false)
            } finally {
                // also reached on cancellation: never leave a pending or partial row behind
                if (!ok) runCatching { resolver.delete(item, null, null) }
            }
            ok
        }

    fun toastDownloads(ctx: Context, ok: Boolean) {
        Toast.makeText(
            ctx,
            if (ok) "Saved to Downloads" else "Couldn't save",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private const val SHARED_DIR = "shared"

    fun toast(ctx: Context, ok: Boolean) {
        Toast.makeText(
            ctx,
            if (ok) "Saved to Photos" else "Couldn't save",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
