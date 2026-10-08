package io.github.avtraang.selfbubbles

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import okhttp3.Request
import io.github.avtraang.selfbubbles.ui.theme.DarkMessageColors
import io.github.avtraang.selfbubbles.ui.theme.Dimens
import io.github.avtraang.selfbubbles.ui.theme.LightMessageColors
import io.github.avtraang.selfbubbles.ui.theme.MessageColors
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.min

/*
 * Conversation shortcuts: the owner's main chats as launcher long-press
 * shortcuts, as direct targets at the top of the system share sheet
 * (res/xml/shortcuts.xml), and as conversations in notification settings.
 *
 * The first half of this file is plain Kotlin (ranking, the cap, labels, ids,
 * the id <-> guid map encoding, the bounded read) so it runs under JUnit;
 * [ConversationShortcuts] below is the Android side: icons, publishing, usage
 * reports.
 *
 * Nothing here logs a name, a number or a guid.
 */

/** Every shortcut id starts with this; the rest is the hex SHA-256 of the chat guid. */
const val SHORTCUT_ID_PREFIX = "chat-"

/** The shortcut category the share sheet and the notification conversation space look for. */
const val SHORTCUT_CATEGORY_CONVERSATION = "android.shortcut.conversation"

/** Label shown under the shortcut; longer names end in an ellipsis. */
const val SHORTCUT_SHORT_LABEL_MAX = 25

/**
 * Shortcut label when a name given to the label helpers is blank (never the guid, a
 * number or an address). A chat without a usable name gets no shortcut at all
 * ([selectShortcutThreads], [planNotificationShortcut]), so this is a last resort.
 */
const val SHORTCUT_LABEL_FALLBACK = "Chat"

/** A re-publish of an unchanged ranking waits at least this long (icons refresh, nothing else). */
const val SHORTCUT_REPUBLISH_MILLIS: Long = 30_000

/** Intent extra MainActivity reads beside chat_guid when a shortcut (or a notification) opens a chat. */
const val EXTRA_CHAT_NAME = "chat_name"

/** A relay group icon larger than this is not downloaded for a shortcut (the list shows it through Coil instead). */
const val SHORTCUT_ICON_MAX_BYTES = 2 * 1024 * 1024

/** Decoded relay group icons kept across re-publishes, by icon path and size. */
const val SHORTCUT_ICON_CACHE_MAX = 32

/** A name with this many digits is a number, not a name, whatever letters sit around it. */
const val SHORTCUT_NAME_MAX_DIGITS = 7

/** What a disabled home-screen pin says when tapped after the privacy switch went off. Names nothing. */
const val SHORTCUT_DISABLED_MESSAGE = "Turned off in the app's settings"

/** What a shortcut says when tapped after it could not be pointed at the app's current screen. Names nothing. */
const val SHORTCUT_OUTDATED_MESSAGE = "Out of date. Open the chat from the app."

private val SHORTCUT_ID = Regex("^${SHORTCUT_ID_PREFIX}[0-9a-f]{64}$")

/** The stable shortcut id for a chat: the prefix plus the hex SHA-256 of its guid. */
fun shortcutIdFor(chatGuid: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(chatGuid.toByteArray(Charsets.UTF_8))
    return SHORTCUT_ID_PREFIX + digest.joinToString("") { "%02x".format(it) }
}

/** True for an id [shortcutIdFor] could have made; anything else from an intent is ignored. */
fun isValidShortcutId(id: String?): Boolean = id != null && SHORTCUT_ID.matches(id)

/**
 * True when a chat's name is really its handle: a phone number, a short code or
 * an e-mail address. The relay names an un-contacted 1:1 chat after its handle,
 * and a handle must never become a label the launcher or the share sheet shows,
 * so such a name counts as absent. The test is the list's own (no word starts
 * with a letter, so [initialsOf] is empty), plus an '@' and a digit count that
 * catch an address or a number with a letter beside it.
 */
fun looksLikeHandle(name: String): Boolean =
    name.contains('@') || name.count { it.isDigit() } >= SHORTCUT_NAME_MAX_DIGITS || initialsOf(name).isEmpty()

/** The name a shortcut may show: [cleanTitle]d, and null when it is blank or [looksLikeHandle]. */
fun shortcutDisplayName(chatName: String?): String? =
    cleanTitle(chatName)?.trim()?.takeUnless { it.isEmpty() || looksLikeHandle(it) }

/** The short label: the name trimmed, "Chat" when blank, capped at [SHORTCUT_SHORT_LABEL_MAX] with an ellipsis. */
fun shortcutShortLabel(name: String?): String {
    val n = name?.trim().orEmpty().ifEmpty { SHORTCUT_LABEL_FALLBACK }
    if (n.length <= SHORTCUT_SHORT_LABEL_MAX) return n
    return n.take(SHORTCUT_SHORT_LABEL_MAX - 1).trimEnd() + "…"
}

/** The long label: the full name, "Chat" when blank. */
fun shortcutLongLabel(name: String?): String = name?.trim().orEmpty().ifEmpty { SHORTCUT_LABEL_FALLBACK }

/**
 * Which chats get a shortcut, in rank order: pinned threads first by pin_index,
 * then the rest by most recent message, never an archived one, at most [max].
 * A chat without a [shortcutDisplayName] (a 1:1 chat the relay named after its
 * handle, a group titled from numbers, no name at all) is left out, so the next
 * ranked named chat takes its slot: a shortcut that would say "Chat" tells the
 * launcher and the share sheet nothing. A [max] below 1 (the platform reports
 * none) yields nothing.
 */
fun selectShortcutThreads(threads: List<Thread>, max: Int): List<Thread> {
    if (max < 1) return emptyList()
    val live = threads
        .filter { !it.archived && it.chat_guid.isNotBlank() && shortcutDisplayName(it.chat_name) != null }
        .distinctBy { it.chat_guid }
    val pinned = live.filter { it.pinned }.sortedBy { it.pin_index }
    val rest = live.filter { !it.pinned }.sortedByDescending { it.last_date ?: Double.NEGATIVE_INFINITY }
    return (pinned + rest).take(max)
}

/** The id <-> guid map as a SharedPreferences string set: one "id\tguid" entry each. */
fun shortcutMapEncode(map: Map<String, String>): Set<String> =
    map.entries.map { "${it.key}\t${it.value}" }.toSet()

/** Back from [shortcutMapEncode]; malformed entries are dropped. */
fun shortcutMapDecode(set: Set<String>?): Map<String, String> {
    if (set == null) return emptyMap()
    val out = HashMap<String, String>()
    for (e in set) {
        val tab = e.indexOf('\t')
        if (tab <= 0 || tab == e.length - 1) continue
        val id = e.substring(0, tab)
        if (!isValidShortcutId(id)) continue
        out[id] = e.substring(tab + 1)
    }
    return out
}

/**
 * The map after a publish: the chats just published, plus every older entry the
 * system still holds ([heldIds]: cached by a notification, or pinned to the home
 * screen), so a share aimed at one of those still resolves. A chat the system
 * let go leaves the map. When the system could not be asked ([heldIds] null)
 * every old entry stays: a stale entry only sends a share to the picker.
 */
fun mergeShortcutMap(old: Map<String, String>, published: Map<String, String>, heldIds: Set<String>?): Map<String, String> =
    if (heldIds == null) old + published
    else old.filterKeys { it in heldIds } + published

/**
 * True when the shortcuts the system holds were published for another activity
 * class than [currentComponent] ([storedComponent] is what AppPrefs recorded at
 * the last publish; null when it recorded nothing yet). A shortcut's intent
 * names the activity class, so after the source package was renamed every
 * shortcut published before it points at a class that no longer exists.
 */
fun shortcutsNeedRetarget(storedComponent: String?, currentComponent: String): Boolean =
    storedComponent != currentComponent

/**
 * Whether a publish skips the debounce: asked for ([requested]: the Settings
 * switch came back on), or the first publish of this process while a retarget
 * is due ([retargetDue], from [shortcutsNeedRetarget]). Only the first
 * ([retargetForcedBefore]): should a retarget never complete, the publishes
 * after it are debounced like any other, and do not refetch every group icon
 * on each refresh of the list.
 */
fun shortcutPublishIsForced(requested: Boolean, retargetDue: Boolean, retargetForcedBefore: Boolean): Boolean =
    requested || (retargetDue && !retargetForcedBefore)

/**
 * A shortcut the system holds beyond the dynamic set (pinned to the home screen,
 * or cached by a notification), as the app reads it back: the activity class its
 * intent names ([className]) and the chat it opens ([guid]), each null when the
 * system did not hand it back, and whether it is enabled.
 */
data class HeldShortcut(val id: String, val className: String?, val guid: String?, val enabled: Boolean = true)

/** What a publish does beyond the dynamic set: ids to rebuild (with their chat guid) and ids to disable. */
data class ShortcutRetarget(val rebuild: Map<String, String> = emptyMap(), val disable: Set<String> = emptySet())

/**
 * The shortcuts beyond the dynamic set that still name another activity class
 * than [currentComponent], and what to do with each. Judged one by one, from
 * the shortcut's own intent: one that already names the current class is never
 * touched, whatever was or was not recorded (a pin that works stays as it is).
 * One whose class the system did not hand back counts as out of date only
 * while a retarget is due ([retargetDue]: nothing was recorded for the current
 * class yet).
 *
 * An out-of-date shortcut whose chat is known (its own [HeldShortcut.guid], or
 * the id [map]'s, in [knownGuids]: the chats the list holds under a usable
 * name) is rebuilt in place, id unchanged; one whose chat is not known cannot
 * be rebuilt and is disabled, so a tap says why and does not fail, and it is
 * rebuilt by a later publish whose list has the chat. A shortcut the publish
 * just made ([publishedIds]) and an id that is not a conversation shortcut are
 * never touched. Nothing is done when the system could not be asked ([held]
 * null), and nothing is disabled on the word of a list that knows no chat at
 * all (not loaded yet, or empty).
 */
fun planShortcutRetarget(
    retargetDue: Boolean,
    currentComponent: String,
    held: List<HeldShortcut>?,
    publishedIds: Set<String>,
    map: Map<String, String>,
    knownGuids: Set<String>,
): ShortcutRetarget {
    if (held == null || knownGuids.isEmpty()) return ShortcutRetarget()
    val rebuild = LinkedHashMap<String, String>()
    val disable = LinkedHashSet<String>()
    for (h in held) {
        if (h.id in publishedIds || !isValidShortcutId(h.id)) continue
        val outOfDate = if (h.className != null) h.className != currentComponent else retargetDue
        if (!outOfDate) continue
        // The guid a shortcut carries is trusted only when it is the one its id was made from.
        val guid = h.guid?.takeIf { shortcutIdFor(it) == h.id } ?: map[h.id]
        if (guid != null && guid in knownGuids) rebuild[h.id] = guid
        else if (h.enabled) disable.add(h.id)
    }
    return ShortcutRetarget(rebuild, disable)
}

/**
 * Whether a shortcut rebuilt in place is marked as kept off the launcher. A
 * cached conversation (not [pinned]) is, as when a notification made it. Not
 * up to Android 12L ([sdk] 32 and below): there the compat library leaves a
 * shortcut with that mark out of an update altogether, so it would never be
 * rebuilt, and an update does not put a cached shortcut on the launcher anyway.
 */
fun rebuiltShortcutIsOffLauncher(pinned: Boolean, sdk: Int): Boolean = !pinned && sdk > 32

/**
 * Whether a retarget that was due is now done, so the current class may be
 * recorded and no later publish treats it as due. Not when the system could not
 * list what it holds ([heldKnown] false), when the publish came with no thread
 * at all ([listEmpty]: nothing could be told about any chat), when the system
 * refused the dynamic set ([publishOk] false: rate-limited) or a rebuild
 * ([rebuildOk] false). The next publish then tries again.
 */
fun shortcutRetargetIsFinal(heldKnown: Boolean, listEmpty: Boolean, publishOk: Boolean, rebuildOk: Boolean): Boolean =
    heldKnown && !listEmpty && publishOk && rebuildOk

/**
 * Whether a notification may reuse the shortcut it finds for its chat: it is in
 * the id map ([inMap]) and names the current activity class, either because
 * everything was retargeted (the recorded class is the current one) or because
 * this process built that very shortcut ([builtThisProcess]). After the rename,
 * until then, a notification pushes its chat's shortcut again instead.
 */
fun notificationShortcutIsCurrent(
    inMap: Boolean, storedComponent: String?, currentComponent: String, builtThisProcess: Boolean = false,
): Boolean = inMap && (builtThisProcess || !shortcutsNeedRetarget(storedComponent, currentComponent))

/**
 * The rank of a shortcut pushed for a notification: the platform [max] (the
 * last place), so it never displaces a chat the list ranked, and it is the one
 * that goes when the set is full.
 */
fun notificationShortcutRank(max: Int): Int = max.coerceAtLeast(0)

/** The fill the list's avatar picks for the chat: the raw name's hash, as `Avatar` keys it. */
fun shortcutAvatarKey(chatName: String?): Long = (chatName ?: "").hashCode().toLong()

/** The initials the list's avatar draws for the chat: from the raw name, empty for a number. */
fun shortcutAvatarInitials(chatName: String?): String = initialsOf(chatName ?: "")

/**
 * Reads [input] to its end, or gives null once more than [limit] bytes arrive:
 * an image that would not fit is not decoded at all.
 */
fun readCapped(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (out.size() + n > limit) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** What one shortcut would be made of; equal values mean the ranking is unchanged. */
data class ShortcutPlan(val id: String, val guid: String, val shortLabel: String, val longLabel: String, val rank: Int)

/** The plans for [threads] (already selected and in rank order); names cleaned by [nameOf]. */
fun planShortcuts(threads: List<Thread>, nameOf: (Thread) -> String?): List<ShortcutPlan> =
    threads.mapIndexed { i, t ->
        val name = nameOf(t)
        ShortcutPlan(shortcutIdFor(t.chat_guid), t.chat_guid, shortcutShortLabel(name), shortcutLongLabel(name), i)
    }

/**
 * The plan for the shortcut a notification pushes for [chatGuid], at
 * [notificationShortcutRank] for the platform [max]; null when the chat has no
 * [shortcutDisplayName], as the list would not publish it either, and the
 * notification then shows without a conversation shortcut rather than under a
 * handle or "Chat".
 */
fun planNotificationShortcut(chatGuid: String, chatName: String?, max: Int): ShortcutPlan? {
    val name = shortcutDisplayName(chatName) ?: return null
    return ShortcutPlan(shortcutIdFor(chatGuid), chatGuid, shortcutShortLabel(name), shortcutLongLabel(name), notificationShortcutRank(max))
}

/**
 * Publishes the dynamic shortcuts and reports their use. Everything runs on one
 * background thread; a failure in ShortcutManager is swallowed (the shortcuts
 * are a convenience, and nothing about them is worth a crash or a log line).
 */
object ConversationShortcuts {
    private val worker = Executors.newSingleThreadExecutor { r -> java.lang.Thread(r, "shortcuts").apply { isDaemon = true } }
    private val lock = Any()
    private var lastPlans: List<ShortcutPlan>? = null
    private var lastPublishedAt = 0L
    /** A publish of this process already skipped the debounce for a retarget (shortcutPublishIsForced). */
    private var retargetForced = false
    /** Ids of shortcuts this process built, which therefore name the current activity class. */
    private val builtIds = HashSet<String>()

    /** Relay group icons already decoded, newest-used last; a re-publish does not download them again. */
    private val relayIcons = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > SHORTCUT_ICON_CACHE_MAX
    }

    private const val FLAGS_HELD = ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_PINNED
    private const val FLAGS_ALL = FLAGS_HELD or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC

    /**
     * Rebuilds the shortcut set from [threads]. Debounced: an unchanged ranking is
     * re-published at most every [SHORTCUT_REPUBLISH_MILLIS] (to pick up new
     * photos); a changed one goes out at once. [force] skips the debounce and
     * refetches the group icons.
     */
    fun publish(ctx: Context, threads: List<Thread>, force: Boolean = false) {
        val app = ctx.applicationContext
        AppPrefs.init(app)
        if (!AppPrefs.shortcutsEnabled) return
        val snapshot = threads.toList()
        worker.execute { runCatching { publishNow(app, snapshot, force) } }
    }

    private fun publishNow(app: Context, threads: List<Thread>, requested: Boolean) {
        if (!AppPrefs.shortcutsEnabled) return
        // Shortcuts published for another activity class (the source package was renamed)
        // open nothing: the first publish after that goes out whatever the debounce says,
        // and what it does not cover is rebuilt or disabled below.
        val component = MainActivity::class.java.name
        val retargetDue = shortcutsNeedRetarget(AppPrefs.shortcutComponent(), component)
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app).coerceAtLeast(0)
        val selected = selectShortcutThreads(threads, max)
        val plans = planShortcuts(selected) { shortcutDisplayName(it.chat_name) }
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val force = shortcutPublishIsForced(requested, retargetDue, retargetForced)
            if (!force && plans == lastPlans && now - lastPublishedAt < SHORTCUT_REPUBLISH_MILLIS) return
            // Set before anything below can throw: a retarget that keeps failing forces one publish per process.
            if (retargetDue) retargetForced = true
            lastPlans = plans
            lastPublishedAt = now
            if (force) relayIcons.clear()
        }
        val colors = messageColors(app)
        val size = iconSize(app)
        val byGuid = selected.associateBy { it.chat_guid }
        val infos = plans.map { p -> build(app, p, byGuid.getValue(p.guid), size, colors) }
        val published = plans.associate { it.id to it.guid }
        // What the system holds beyond the dynamic set: a notification's cached
        // conversation (with the owner's priority and bubble settings on it) and
        // the owner's home-screen pins. Those are left alone: replacing the
        // dynamic set drops a chat from the launcher and the share sheet, and
        // nothing more. A pin disabled while the switch was off comes back.
        val held = runCatching { ShortcutManagerCompat.getShortcuts(app, FLAGS_HELD) }.getOrNull()
        val publishOk = ShortcutManagerCompat.setDynamicShortcuts(app, infos)
        if (publishOk) synchronized(lock) { builtIds.addAll(published.keys) }
        val disabledPins = held?.filter { it.isPinned && !it.isEnabled }?.map { it.id }?.toSet().orEmpty()
        val revive = infos.filter { it.id in disabledPins }
        if (revive.isNotEmpty()) runCatching { ShortcutManagerCompat.enableShortcuts(app, revive) }
        val oldMap = AppPrefs.shortcutMap()
        val heldIds = held?.map { it.id }?.toSet()
        // The pins and the cached conversations the publish did not rebuild may still name the
        // old class. Each is judged by its own intent, which the system hands back to the app
        // that published it, so one that already names this class is left as it is. One that
        // does not keeps its id (and with it the owner's place on the home screen and the
        // conversation's notification settings); only what it is made of is replaced.
        val heldShortcuts = held?.map { s ->
            val intent = runCatching { s.intent }.getOrNull()
            HeldShortcut(
                s.id, intent?.component?.className,
                runCatching { intent?.getStringExtra(EXTRA_CHAT_GUID) }.getOrNull(), s.isEnabled,
            )
        }
        val anyOutOfDate = heldShortcuts.orEmpty().any {
            if (it.className != null) it.className != component else retargetDue
        }
        val known =
            if (!anyOutOfDate) emptyMap<String, Thread>()
            else threads.filter { it.chat_guid.isNotBlank() && shortcutDisplayName(it.chat_name) != null }
                .associateBy { it.chat_guid }
        val left = planShortcutRetarget(retargetDue, component, heldShortcuts, published.keys, oldMap, known.keys)
        var rebuildOk = true
        if (left.rebuild.isNotEmpty()) {
            val pinned = held.orEmpty().filter { it.isPinned }.map { it.id }.toSet()
            val rebuilt = left.rebuild.mapNotNull { (id, guid) ->
                val t = known[guid] ?: return@mapNotNull null
                val plan = planNotificationShortcut(guid, t.chat_name, max) ?: return@mapNotNull null
                // A cached conversation stays off the launcher, as when a notification made it.
                val offLauncher = rebuiltShortcutIsOffLauncher(id in pinned, Build.VERSION.SDK_INT)
                runCatching { build(app, plan, t, size, colors, fromNotification = offLauncher) }.getOrNull()
            }
            val updated = rebuilt.isNotEmpty() &&
                runCatching { ShortcutManagerCompat.updateShortcuts(app, rebuilt) }.getOrDefault(false)
            if (updated) {
                runCatching { ShortcutManagerCompat.enableShortcuts(app, rebuilt) }
                synchronized(lock) { builtIds.addAll(rebuilt.map { it.id }) }
            }
            rebuildOk = updated && rebuilt.size == left.rebuild.size
        }
        if (left.disable.isNotEmpty()) {
            runCatching { ShortcutManagerCompat.disableShortcuts(app, left.disable.toList(), SHORTCUT_OUTDATED_MESSAGE) }
        }
        // A disabled shortcut leaves the map, so the next notification for its chat pushes a
        // fresh one under the same id (ensureForNotification) rather than reusing a dead one.
        AppPrefs.replaceShortcutMap(mergeShortcutMap(oldMap, published, heldIds) - left.disable)
        // Recorded only once the retarget could do its work (shortcutRetargetIsFinal): with the
        // system's list unreadable, no thread to judge by, or a refused update, the next publish
        // tries again. What is still out of date after that is found again by its own intent.
        if (retargetDue && shortcutRetargetIsFinal(held != null, threads.isEmpty(), publishOk, rebuildOk)) {
            AppPrefs.putShortcutComponent(component)
        }
    }

    /** A chat was opened: tells the launcher and the share sheet, so the ranking follows use. */
    fun pushUsage(ctx: Context, chatGuid: String) {
        val app = ctx.applicationContext
        AppPrefs.init(app)
        if (!AppPrefs.shortcutsEnabled) return
        val id = shortcutIdFor(chatGuid)
        worker.execute { runCatching { ShortcutManagerCompat.reportShortcutUsed(app, id) } }
    }

    /**
     * A message notification is about to show for [chatGuid]: makes sure a
     * long-lived shortcut exists for it, so the notification lands in the
     * conversation space (and bubbles / priority settings work). Already on a
     * background thread (FCM), so this runs inline. Returns the shortcut id.
     *
     * The push cannot tell an archived chat from a live one, so a shortcut made
     * here is kept off the launcher (the system honours that from Android 13),
     * takes the last rank so it displaces nothing the list ranked, and the next
     * publish replaces the dynamic set whatever the debounce says, leaving only
     * the cached copy the notification holds. A chat without a usable name gets
     * none ([planNotificationShortcut]); the id still comes back, and the system
     * ignores a shortcut id it does not hold, as it does with the switch off.
     */
    fun ensureForNotification(ctx: Context, chatGuid: String, chatName: String?, isGroup: Boolean): String {
        val app = ctx.applicationContext
        AppPrefs.init(app)
        val id = shortcutIdFor(chatGuid)
        if (!AppPrefs.shortcutsEnabled) return id
        // Reused only when it was published for the activity class this build has; one from
        // before the package rename is pushed again here, with the current class.
        // (Or built by this very process, which is as good: a publish that could not finish the
        // retarget yet must not have its shortcut replaced by a notification's barer one.)
        val builtHere = synchronized(lock) { id in builtIds }
        if (notificationShortcutIsCurrent(
                AppPrefs.shortcutMap().containsKey(id), AppPrefs.shortcutComponent(), MainActivity::class.java.name, builtHere,
            )
        ) return id
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(app)
            val plan = planNotificationShortcut(chatGuid, chatName, max) ?: return id
            val t = Thread(chat_guid = chatGuid, chat_name = chatName, is_group = isGroup, last_rowid = 0)
            ShortcutManagerCompat.pushDynamicShortcut(app, build(app, plan, t, iconSize(app), messageColors(app), fromNotification = true))
            AppPrefs.putShortcutMapping(id, chatGuid)
            synchronized(lock) { lastPlans = null; builtIds.add(id) }
        }
        return id
    }

    /**
     * The privacy switch went off: every shortcut the system holds goes, the
     * launcher's, the cached ones and the long-lived state alike. A copy the
     * owner dragged to the home screen is the launcher's and only the owner can
     * remove it; it is disabled, so it opens nothing and says why.
     */
    fun removeAll(ctx: Context) {
        val app = ctx.applicationContext
        worker.execute {
            runCatching {
                val held = runCatching { ShortcutManagerCompat.getShortcuts(app, FLAGS_ALL) }.getOrNull().orEmpty()
                val ids = (held.map { it.id } + AppPrefs.shortcutMap().keys).distinct()
                ShortcutManagerCompat.removeAllDynamicShortcuts(app)
                if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(app, ids)
                val pinned = held.filter { it.isPinned }.map { it.id }
                if (pinned.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(app, pinned, SHORTCUT_DISABLED_MESSAGE)
            }
            AppPrefs.replaceShortcutMap(emptyMap())
            synchronized(lock) { lastPlans = null; lastPublishedAt = 0L; relayIcons.clear(); builtIds.clear() }
        }
    }

    // ---- building one shortcut ----

    private fun build(
        app: Context, p: ShortcutPlan, t: Thread, size: Int, colors: MessageColors, fromNotification: Boolean = false,
    ): ShortcutInfoCompat {
        // Same extras as the notification tap (PushService), so MainActivity routes it
        // the same way; the name is the relay's own, as that intent carries it (the
        // system never hands a shortcut's intent to the launcher).
        val intent = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_CHAT_GUID, p.guid)
        t.chat_name?.let { intent.putExtra(EXTRA_CHAT_NAME, it) }
        val b = ShortcutInfoCompat.Builder(app, p.id)
            .setShortLabel(p.shortLabel)
            .setLongLabel(p.longLabel)
            .setIntent(intent)
            .setLongLived(true)
            .setIsConversation()
            .setCategories(setOf(SHORTCUT_CATEGORY_CONVERSATION))
            .setLocusId(LocusIdCompat(p.id))
            .setRank(p.rank)
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon(app, t, size, colors)))
        if (fromNotification) b.setExcludedFromSurfaces(ShortcutInfoCompat.SURFACE_LAUNCHER)
        if (!t.is_group) {
            // The handle is the key only; the label is the chat's name, never the number.
            val persons = t.handles.filter { it.isNotBlank() }.distinct()
                .map { h -> Person.Builder().setName(p.longLabel).setKey(h).build() }
            if (persons.isNotEmpty()) b.setPersons(persons.toTypedArray())
        }
        return b.build()
    }

    /** The launcher's icon size in pixels: what ShortcutManager asks for, else the large launcher icon. */
    private fun iconSize(app: Context): Int {
        val sm = runCatching { app.getSystemService(ShortcutManager::class.java) }.getOrNull()
        val fromSm = sm?.let { min(it.iconMaxWidth, it.iconMaxHeight) } ?: 0
        if (fromSm > 0) return fromSm
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.launcherLargeIconSize.coerceAtLeast(1)
    }

    /** The avatar palette for the phone's current theme (the fills are the same in both). */
    private fun messageColors(app: Context): MessageColors {
        val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) DarkMessageColors else LightMessageColors
    }

    /**
     * The thread's avatar as the list draws it: group icon, contact photo, else
     * initials on a theme fill. Initials and fill come from the raw chat_name,
     * exactly as `Avatar` keys them, so the tile matches the row (a nameless
     * chat gets the silhouette, not a "C" for "Chat").
     */
    private fun icon(app: Context, t: Thread, size: Int, colors: MessageColors): Bitmap {
        ContactPhotos.load(app)
        t.icon_url?.let { path ->
            val url = BASE + path
            if (isRelayUrl(url)) relayBitmap(path, url, size)?.let { return squareCrop(it, size) }
        }
        ContactPhotos.photosFor(t.handles, t.chat_name).firstOrNull()?.let { uri ->
            contactBitmap(app, uri, size)?.let { return squareCrop(it, size) }
        }
        return initialsBitmap(shortcutAvatarInitials(t.chat_name), shortcutAvatarKey(t.chat_name), size, colors)
    }

    /**
     * A relay-served group icon, through the shared client (the only one that may
     * carry the token). Capped at [SHORTCUT_ICON_MAX_BYTES] and kept decoded, so a
     * busy group's every reorder does not fetch it again.
     */
    private fun relayBitmap(path: String, url: String, size: Int): Bitmap? {
        val key = "$path@$size"
        synchronized(lock) { relayIcons[key]?.let { return it } }
        val bmp = runCatching {
            val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val body = r.body
                if (!r.isSuccessful || body == null) return@runCatching null
                if (body.contentLength() > SHORTCUT_ICON_MAX_BYTES) return@runCatching null
                readCapped(body.byteStream(), SHORTCUT_ICON_MAX_BYTES) ?: return@runCatching null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, size) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull() ?: return null
        synchronized(lock) { relayIcons[key] = bmp }
        return bmp
    }

    /** A contact photo from the address book, decoded to a software bitmap near [size]. */
    private fun contactBitmap(app: Context, uri: Uri, size: Int): Bitmap? = runCatching {
        val src = ImageDecoder.createSource(app.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            if (w > 0 && h > 0) {
                val scale = size.toFloat() / min(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }
    }.getOrNull()

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        if (w <= 0 || h <= 0) return s
        while (min(w, h) / (s * 2) >= target) s *= 2
        return s
    }

    /** [src] scaled to fill a [size] square and cropped to its centre. */
    private fun squareCrop(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val side = min(src.width, src.height)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        c.drawBitmap(src, Rect(left, top, left + side, top + side), RectF(0f, 0f, size.toFloat(), size.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /**
     * The list's InitialsAvatar as a full-bleed adaptive bitmap: the whole square
     * takes the fill (the launcher masks it), the initials are sized as a share
     * of the masked circle, and a chat with no initials (a number, a short code)
     * gets a person silhouette, as the list shows a Person icon.
     */
    private fun initialsBitmap(initials: String, colorKey: Long, size: Int, colors: MessageColors): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(colors.avatarFills[colorKey.mod(colors.avatarFills.size)].toArgb())
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.onAvatar.toArgb() }
        val visible = size * Dimens.adaptiveIconMaskFraction
        val cx = size / 2f
        val cy = size / 2f
        if (initials.isNotEmpty()) {
            fg.textSize = visible * Dimens.avatarInitialsFraction
            fg.textAlign = Paint.Align.CENTER
            fg.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            val fm = fg.fontMetrics
            c.drawText(initials, cx, cy - (fm.ascent + fm.descent) / 2f, fg)
        } else {
            // Head and shoulders inside the masked circle.
            val headR = visible * Dimens.silhouetteHeadFraction
            c.drawCircle(cx, cy - headR * 0.9f, headR, fg)
            val bodyR = visible * Dimens.silhouetteBodyFraction
            val bodyTop = cy + headR * 0.55f
            c.drawArc(RectF(cx - bodyR, bodyTop, cx + bodyR, bodyTop + bodyR * 2f), 180f, 180f, true, fg)
        }
        return out
    }
}
