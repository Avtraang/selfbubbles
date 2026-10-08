package io.github.avtraang.selfbubbles

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The share-sheet entry (manifest: ACTION_SEND / ACTION_SEND_MULTIPLE). Draws
 * nothing of its own: it validates the intent ([checkShareIntent]), copies every
 * stream into a private folder of its own under cacheDir/shared/share-in/<id>
 * while the sender's temporary read grant is still alive, records the text and
 * the copied files there (share.json), then hands MainActivity the share id
 * alone and finishes. MainActivity shows the chat picker, behind the app lock
 * when that is up.
 *
 * The copy itself lives in [ShareStagingVM], which outlives a configuration
 * change (this activity is recreated by a rotation or a dark-mode flip like any
 * other) and is cleared when the activity really goes, cancelling a copy still
 * running. The result is handed on only while the activity is started: Android
 * refuses an activity start from one that is stopped.
 *
 * Nothing shared is logged: no names, no text, no URIs.
 */
class ShareActivity : ComponentActivity() {
    private val staging: ShareStagingVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated instance finds the copy already running (or done) in the
        // retained model and only waits for it; a fresh one starts it.
        if (!staging.started) {
            val intent = intent
            val streams = streamsOf(intent)
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            val check = checkShareIntent(
                action = intent.action,
                mime = intent.type,
                hasText = text != null,
                uriSchemes = streams.map { it?.scheme },
                count = streams.size,
                uriAuthorities = streams.map { it?.authority },
                ownAuthority = "$packageName.fileprovider",
            )
            if (check !is ShareCheck.Accept) {
                nothingToShare()
                return
            }
            // A direct-share target (a conversation shortcut, ConversationShortcuts.kt)
            // names its chat by shortcut id; the id resolves through the map the app
            // kept when it published the shortcut, never through anything in the intent.
            AppPrefs.init(this)
            val targetGuid = AppPrefs.shortcutGuid(intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
            staging.start(applicationContext, streams.filterNotNull(), if (check.text) text else null, targetGuid)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val progress = launch {
                    // Counted from when the copy began, not from this (possibly recreated) instance.
                    delay(shareProgressDelay(SystemClock.elapsedRealtime() - staging.startedAt))
                    Toast.makeText(this@ShareActivity, "Copying…", Toast.LENGTH_SHORT).show()
                }
                val result = try {
                    staging.result.await()
                } finally {
                    progress.cancel()
                }
                deliver(result)
            }
        }
    }

    private fun deliver(result: ShareStore.Staged?) {
        if (result == null) { nothingToShare(); return }
        if (result.tooLarge > 0) {
            Toast.makeText(this, sendFailureMessage(SendFailure.TOO_LARGE), Toast.LENGTH_LONG).show()
        }
        val share = result.share
        if (share == null) { nothingToShare(); return }
        staging.markDelivered()
        startActivity(
            Intent(this, MainActivity::class.java)
                // NEW_TASK, as the notification tap does: lands in the app's own
                // task, where singleTop + CLEAR_TOP reuse the running instance
                // (onNewIntent) instead of stacking a second one in the sender's task.
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                .putExtra(EXTRA_SHARE_ID, share.id),
        )
        finish()
    }

    private fun nothingToShare() {
        Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
        finish()
    }

    /** The stream extras as delivered: one for SEND, the list for SEND_MULTIPLE; null entries stay (they fail validation). */
    private fun streamsOf(intent: Intent): List<Uri?> = when (intent.action) {
        Intent.ACTION_SEND ->
            if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                listOf(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            } else emptyList()
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.toList() ?: emptyList()
        else -> emptyList()
    }
}

/**
 * Runs one share's copy ([ShareStore.stage]) in a scope that survives the
 * activity's configuration changes and ends with it: when the activity finishes
 * (the owner backs out of the translucent screen, or the system removes it)
 * viewModelScope is cancelled, which stops the copy, closes the stream being
 * read and removes the half-made folder. A copy that finished but was never
 * handed on is removed too, instead of waiting a day for the prune.
 */
class ShareStagingVM : ViewModel() {
    var started = false; private set
    /** SystemClock.elapsedRealtime() when the copy began, for the progress toast's timing. */
    var startedAt = 0L; private set
    val result = CompletableDeferred<ShareStore.Staged?>()
    @Volatile private var staged: ShareStore.Staged? = null
    private var delivered = false

    fun start(app: Context, uris: List<Uri>, text: String?, targetGuid: String? = null) {
        if (started) return
        started = true
        startedAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { ShareStore.stage(app, uris, text, targetGuid) }
            staged = r
            result.complete(r)
        }
    }

    /** MainActivity has been given the share id; its folder is MainActivity's to delete now. */
    fun markDelivered() { delivered = true }

    override fun onCleared() {
        // viewModelScope is already closed here (a copy still running has cancelled
        // itself and cleaned up); only a finished, unhanded share is left to remove.
        val share = staged?.share
        if (!delivered && share != null) thread { ShareStore.delete(share) }
    }
}

/**
 * The share folders under cacheDir/shared/share-in: staging a share, reading it
 * back by id, deleting it, and pruning stale ones. Every function does file I/O
 * and belongs on Dispatchers.IO.
 */
object ShareStore {
    class Staged(val share: PendingShare?, val tooLarge: Int)

    private fun root(ctx: Context): File = File(File(ctx.cacheDir, "shared"), SHARE_IN_DIR)

    /** The folder for [id], or null for an id that is not one of ours (never a path from an intent). */
    fun folderFor(ctx: Context, id: String?): File? {
        if (!isValidShareId(id)) return null
        return File(root(ctx), id!!)
    }

    /**
     * Copies [uris] into a fresh share folder and writes its manifest. Items the
     * tunnel would refuse (or that cannot be read) are skipped and counted in
     * [Staged.tooLarge] / dropped. Null when nothing at all could be kept.
     *
     * Cancellation (the caller's scope ending) is honoured between streams and
     * within each copy, and removes the folder whole: a cancelled share leaves
     * nothing behind, and never a manifest.
     */
    suspend fun stage(ctx: Context, uris: List<Uri>, text: String?, targetGuid: String? = null): Staged? {
        pruneStale(ctx, System.currentTimeMillis())
        val id = shareIdFrom(ByteArray(8).also { SecureRandom().nextBytes(it) })
        val dir = File(root(ctx), id)
        if (!dir.mkdirs() && !dir.isDirectory) return null
        val resolver = ctx.contentResolver
        val mimeMap = MimeTypeMap.getSingleton()
        val entries = ArrayList<ShareManifestItem>()
        // The manifest's own name is taken from the start, so a stream called
        // share.json is copied as "share (2).json" and never overwritten by it.
        val taken = HashSet(shareReservedNames())
        var tooLarge = 0
        try {
            for ((i, uri) in uris.withIndex()) {
                currentCoroutineContext().ensureActive()
                var declaredSize: Long? = null
                var displayName: String? = null
                runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                        ?.use { c ->
                            if (c.moveToFirst()) {
                                val si = c.getColumnIndex(OpenableColumns.SIZE)
                                if (si >= 0 && !c.isNull(si)) declaredSize = c.getLong(si)
                                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (ni >= 0 && !c.isNull(ni)) displayName = c.getString(ni)
                            }
                        }
                }
                if (!shareItemFits(declaredSize)) { tooLarge++; continue }
                val mime = runCatching { resolver.getType(uri) }.getOrNull()
                    ?.takeIf { it.isNotBlank() && !it.contains('*') }
                    ?: extensionOf(displayName)?.let { mimeMap.getMimeTypeFromExtension(it.drop(1)) }
                    ?: "application/octet-stream"
                val extFromMime = mimeMap.getExtensionFromMimeType(mime)
                val name = uniqueFileName(shareFileName(displayName, extFromMime, i + 1), taken)
                val target = File(dir, name)
                val copied = copyStream(resolver, uri, target)
                if (copied < 0) {
                    target.delete()
                    if (copied == -2L) tooLarge++
                    continue
                }
                taken += name
                entries += ShareManifestItem(file = name, mime = mime, name = name, size = copied)
            }
            // A share backed out of while its last stream was being copied gets no
            // manifest: the cancellation below takes the folder with it.
            currentCoroutineContext().ensureActive()
            if (entries.isEmpty() && text == null) {
                dir.deleteRecursively()
                return Staged(null, tooLarge)
            }
            val manifest = ShareManifest(text = text, items = entries, target = targetGuid)
            File(dir, SHARE_MANIFEST_NAME).writeText(json.encodeToString(ShareManifest.serializer(), manifest))
            return Staged(pendingShareFrom(id, dir, manifest), tooLarge)
        } catch (e: CancellationException) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            return null
        }
    }

    /**
     * Copies [uri] into [target]: the byte count, -1 when it cannot be read, -2
     * when it runs past the cap. A cancellation closes the stream from another
     * coroutine, which Android turns into an error on a read() blocked inside the
     * provider, so a share backed out of (or a provider that never answers) does
     * not keep an IO thread, the activity, or its URI grant alive.
     */
    private suspend fun copyStream(resolver: ContentResolver, uri: Uri, target: File): Long = coroutineScope {
        val input = try { resolver.openInputStream(uri) } catch (e: Exception) { null }
            ?: return@coroutineScope -1L
        val closer = launch { try { awaitCancellation() } finally { runCatching { input.close() } } }
        try {
            var total = 0L
            val buf = ByteArray(64 * 1024)
            input.use { src ->
                target.outputStream().use { out ->
                    while (true) {
                        ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > SHARE_MAX_ITEM_BYTES) return@coroutineScope -2L
                        out.write(buf, 0, n)
                    }
                }
            }
            total
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A read broken by the closer above is the cancellation, not a bad file.
            ensureActive()
            -1L
        } finally {
            closer.cancel()
        }
    }

    /** The share recorded under [id], or null when there is none (or the id is not ours). */
    fun load(ctx: Context, id: String?): PendingShare? {
        val dir = folderFor(ctx, id) ?: return null
        val f = File(dir, SHARE_MANIFEST_NAME)
        if (!f.isFile) return null
        val m = runCatching { json.decodeFromString(ShareManifest.serializer(), f.readText()) }.getOrNull()
            ?: return null
        val share = pendingShareFrom(id!!, dir, m)
        return if (share.text == null && share.items.isEmpty()) null else share
    }

    fun delete(share: PendingShare) {
        runCatching { share.dir.deleteRecursively() }
    }

    /** Removes share folders last touched more than [SHARE_MAX_AGE_MILLIS] ago. */
    fun pruneStale(ctx: Context, now: Long) {
        root(ctx).listFiles()?.forEach { d ->
            if (!d.isDirectory) { d.delete(); return@forEach }
            val newest = (d.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L).coerceAtLeast(d.lastModified())
            if (shareFolderIsStale(newest, now)) runCatching { d.deleteRecursively() }
        }
    }
}
