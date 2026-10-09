package io.github.avtraang.selfbubbles

// ChatVM: the single ViewModel behind App() (threads, open chat, sends, shares, paging,
// translation, relay reconfiguration). Moved verbatim out of MainActivity.kt (SelfBubbles
// split, step C2).

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// The SavedStateHandle comes from the activity's default factory (viewModel() in
// App()): it carries the launch seq this VM last handled across process death.
class ChatVM(app: Application, private val state: SavedStateHandle) : AndroidViewModel(app) {
    val threads = MutableStateFlow<List<Thread>>(emptyList())
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val media = MutableStateFlow<Media?>(null)
    var current by mutableStateOf<Thread?>(null); private set
    var showInfo by mutableStateOf(false); private set
    var showMap by mutableStateOf(false); private set
    // Both no-ops while the feature is off (Features.kt): a stale shortcut or state cannot open a disabled screen.
    fun openMap() { if (Features.familyMapUsable) showMap = true }
    fun closeMap() { showMap = false }
    var showFaceTime by mutableStateOf(false); private set
    fun openFaceTime() { if (Features.faceTime) showFaceTime = true }
    fun closeFaceTime() { showFaceTime = false }
    var showSettings by mutableStateOf(false); private set
    fun openSettings() { showSettings = true }
    fun closeSettings() { showSettings = false }
    var mediaError by mutableStateOf(false); private set

    /** A relay is configured (RelayConfigStore.current.isConfigured); App() shows the first-run relay screen until it is. */
    var isConfigured by mutableStateOf(RelayConfigStore.current.isConfigured); private set

    /**
     * How the last load of the list ended (ThreadsLoad.kt). The list screen shows
     * it only while it has no conversation to show: that the first load is taking
     * a while, "No conversations yet", or why the load failed with a way to try
     * again. A failed refresh never touches [threads], so a list that is on
     * screen stays.
     *
     * The first load is started by the init block, which is the last thing in
     * this class on purpose (see there).
     */
    var threadsLoad by mutableStateOf<ThreadsLoad>(ThreadsLoad.Loading); private set

    /** Loads are numbered in the order they start; this is the one started last. */
    private var threadsLoadSeq = 0

    /**
     * The load started last when the list last began waiting with nothing to go
     * by ([threadsLoadPending]). That load and every earlier one was started
     * under relay settings that have since been replaced, so however they end
     * says nothing about the relay in force (threadsLoadOnEndOf, ThreadsLoad.kt).
     */
    private var threadsLoadForgotten = 0

    /**
     * True once the list has waited [LOADING_NOTICE_DELAY_MILLIS] for its first
     * load, which is when it says "Loading conversations…" (threadListNotice,
     * ThreadsLoad.kt); it means nothing once that load has ended. Kept here and
     * not in the screen: a rotation, a dark-mode flip or a lock and unlock
     * composes the list anew, and a wait that started over with each would take
     * a row that is on screen away again.
     */
    var threadsLoadSlow by mutableStateOf(false); private set
    private var loadingNoticeWait: Job? = null

    /**
     * The list starts waiting for a load with nothing to go by: a fresh
     * ViewModel, or the relay settings just changed. [threadsLoad] is Loading and
     * the wait before the list says so starts over. One wait per such start, not
     * per request: refreshes overlap (the socket opening starts a second one a
     * moment after the first, and so does every message that arrives), and a wait
     * each of them restarted would never end on the slow link the row is for.
     */
    private fun threadsLoadPending() {
        threadsLoad = ThreadsLoad.Loading
        threadsLoadForgotten = threadsLoadSeq
        threadsLoadSlow = false
        loadingNoticeWait?.cancel()
        loadingNoticeWait = viewModelScope.launch {
            delay(LOADING_NOTICE_DELAY_MILLIS)
            // Nothing to say once the load has ended: an ordinary launch never gets here with a write.
            if (threadsLoad is ThreadsLoad.Loading) threadsLoadSlow = true
        }
    }

    /** The relay address the list held, the open chat and the send-path state were loaded from. */
    private var relayBase = RelayConfigStore.current.base

    /**
     * Settings saved or reset the relay config: the socket is dropped and
     * reconnected against the new snapshot (WsManager.reconnect: one socket at
     * a time, and the way an idle, unconfigured manager comes alive), then the
     * list is reloaded from the new relay. No process restart.
     *
     * A Save that keeps the relay's address (the token, the Cloudflare pair or
     * the map URL was edited) keeps the list on screen, as it always did. One
     * that moves to another address drops what belonged to the relay that was
     * left ([relayMoved]): its conversations would otherwise stay on screen over
     * a load from the new relay that failed, with no error panel, and a tap on
     * one would send to the new relay under the old one's chat identifier.
     *
     * The push token goes to the new relay as well: MainActivity registers it
     * when it is created, which on a first run is before any relay exists and
     * after a change is with the old one. Push.registerWithRelay is idempotent
     * (the relay keeps a set of tokens) and a no-op in a build without Firebase.
     */
    fun relayConfigChanged() {
        val cfg = RelayConfigStore.current
        isConfigured = cfg.isConfigured
        if (relayMoved(relayBase, cfg.base)) leaveRelay()
        relayBase = cfg.base
        wsm.reconnect()
        // What the last load said was about the old relay (or about no relay at all).
        threadsLoadPending()
        refreshThreads()
        if (isConfigured) Push.registerWithRelay(getApplication(), viewModelScope)
    }

    /**
     * Forgets everything that was the previous relay's: its list, its open chat,
     * its send path. The texts waiting in the outbox are not the relay's but the
     * owner's (often they failed because the address was wrong, which is what
     * this Save just fixed): they stay, each under its chat and in the list's
     * notice, until he sends them again or discards them.
     */
    private fun leaveRelay() {
        threads.value = emptyList()
        if (current != null) {
            current = null
            messages.value = emptyList()
            resetPaging()
            Notifs.openChat = null
        }
        showInfo = false; media.value = null; mediaError = false
        cancelReply()
        endEdit()
        sendPath = SendPathState()
        fallbackMode = false
    }

    // The socket's callbacks arrive on OkHttp's thread, and everything they touch (the
    // held messages, paging, the translations) is the main thread's: an append made from
    // there could overwrite a page the main thread had just put in place. Each hops to the
    // main thread first; viewModelScope is bound to it and runs them in the order they came.
    private val wsm = WsManager(
        onOpen = { viewModelScope.launch { socketOpened() } },
        onMsg = { msg -> viewModelScope.launch { socketMessage(msg) } },
        onUpdate = { msg -> viewModelScope.launch { socketUpdate(msg) } },
    )

    private fun socketOpened() {
        refreshThreads()
        val c = current ?: return
        // The reconnect reloads the latest page only, so paging starts over from
        // it: the reset drops any older page still in flight, which would otherwise
        // be merged into the fresh list and leave a gap below it.
        resetPaging()
        val req = PageRequest(c.chat_guid, pagingGeneration)
        viewModelScope.launch {
            runCatching { Api.messages(c.chat_guid) }.onSuccess {
                if (pageStillApplies(req, current?.chat_guid, pagingGeneration)) {
                    messages.value = it
                    hasOlder = pageHasOlder(it)
                }
            }
        }
    }

    private fun socketMessage(msg: Msg) {
        // No guid in the log: a chat identifier is a phone number or an e-mail address.
        android.util.Log.d("ImsgWS", "dispatch forOpenChat=${msg.chat_guid == current?.chat_guid}")
        // Already held (holdsMessage, HistoryPaging.kt): a page fetched meanwhile carried it too.
        if (msg.chat_guid == current?.chat_guid && !holdsMessage(messages.value, msg)) {
            messages.value = messages.value + msg
            markRead(msg.chat_guid, msg.rowid)
            if (current?.auto_translate == true && !msg.is_from_me) translate(msg)
        }
        refreshThreads()
    }

    private fun socketUpdate(msg: Msg) {
        android.util.Log.d("ImsgWS", "update rowid=${msg.rowid}")
        if (msg.chat_guid == current?.chat_guid) {
            messages.value = messages.value.map {
                val match = if (msg.rowid != 0L) it.rowid == msg.rowid else it.guid == msg.guid
                if (match) msg else it
            }
        }
        refreshThreads()
    }

    fun refreshThreads() = viewModelScope.launch {
        val load = threadsLoadStarted()
        val from = RelayConfigStore.current.base
        val result = runCatching { Api.threads() }.onSuccess { showThreads(from, it) }
        threadsLoadEnded(load, result.exceptionOrNull())
        threadsLoaded.value = true
    }

    /**
     * A list that was loaded from [loadedFrom]. It is shown only when that is
     * still the relay in force ([threadsLoadApplies]): a load that was in flight
     * to the previous address when Settings saved another one would otherwise
     * put the old relay's conversations back into the list just emptied for the
     * new one ([leaveRelay]).
     */
    private fun showThreads(loadedFrom: String, list: List<Thread>) {
        if (!threadsLoadApplies(loadedFrom, RelayConfigStore.current.base)) return
        threads.value = list; syncCurrentLabels(list); publishShortcuts(list)
    }

    private fun threadsLoadStarted(): Int {
        threadsLoad = threadsLoadOnStart(threadsLoad)
        return ++threadsLoadSeq
    }

    /**
     * Refreshes overlap, and an older one may end after a newer one: the load
     * started last has the last word. An earlier one that fails while the list
     * still waits for its first verdict is shown meanwhile, as a failure being
     * retried (threadsLoadOnEndOf, ThreadsLoad.kt, has the whole rule).
     */
    private fun threadsLoadEnded(load: Int, error: Throwable?) {
        threadsLoad = threadsLoadOnEndOf(load, latest = threadsLoadSeq, forgotten = threadsLoadForgotten, threadsLoad, error)
    }

    /** True once a load of the list has finished, well or not; a direct-share target waits for this, not for a clock. */
    private val threadsLoaded = MutableStateFlow(false)

    /** The launcher and share-sheet shortcuts follow the list (ConversationShortcuts.kt; debounced there). */
    private fun publishShortcuts(list: List<Thread>, force: Boolean = false) =
        ConversationShortcuts.publish(getApplication(), list, force)

    /** The Settings switch came back on: publish from the list held now, without waiting for a refresh. */
    fun republishShortcuts() = publishShortcuts(threads.value, force = true)

    /** Threads opened from a notification, a search hit, compose or createChat
     *  start as bare stubs, and a list-opened thread goes stale: pull the
     *  relay's per-thread send labels into [current] from the fresh list.
     *  Only these fields are merged, so local state (auto_translate, the
     *  chat_guid key the draft hangs off) is untouched. */
    private fun syncCurrentLabels(list: List<Thread>) {
        val c = current ?: return
        val f = list.firstOrNull { it.chat_guid == c.chat_guid } ?: return
        current = c.withLabelsFrom(f)
    }

    var refreshing by mutableStateOf(false); private set

    /** Pull-to-refresh: re-pull contacts from BlueBubbles, then reload threads. */
    fun pullRefresh() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            runCatching { Api.refreshContacts() }
            val load = threadsLoadStarted()
            val from = RelayConfigStore.current.base
            val result = runCatching { Api.threads() }.onSuccess { showThreads(from, it) }
            threadsLoadEnded(load, result.exceptionOrNull())
            threadsLoaded.value = true
            refreshing = false
        }
    }

    /**
     * The intent MainActivity was created with, or received later (onNewIntent):
     * a notification tap's chat, a share id from ShareActivity, or neither. Each
     * is handled once, by its [LaunchRequest.seq]: App() leaves and re-enters
     * composition on every lock/unlock cycle, so its LaunchedEffect fires each
     * time, and a rotation recreates the activity with the same request; without
     * this guard an unlock on the thread list would land in that chat again. A
     * request's seq survives the rotation in the saved state, so only a genuinely
     * new intent has a higher one.
     *
     * The handled seq is saved with the VM: after process death the recreated
     * activity still carries its last request (with the saved seq, see
     * launchSeqOnCreate), and a fresh VM starting from zero would act on it again,
     * reopening that chat or reporting a share already sent as "Nothing to share".
     * A VM that never existed (the app was still locked when the process died)
     * saved nothing, so that request is handled after the unlock as it should be.
     */
    private var handledLaunchSeq: Long
        get() = state[STATE_HANDLED_LAUNCH_SEQ] ?: 0L
        set(v) { state[STATE_HANDLED_LAUNCH_SEQ] = v }
    fun openFromLaunch(req: LaunchRequest) {
        if (!launchRequestIsNew(req.seq, handledLaunchSeq)) return
        handledLaunchSeq = req.seq
        val guid = req.chatGuid
        if (guid != null) {
            // The tapped chat takes the screen. Before singleTop a tap recreated the
            // activity with a fresh VM, so nothing could sit over the chat; now whatever
            // is up (Settings, compose, the map, FaceTime, this chat's info) would hide
            // it, with its pushes suppressed meanwhile as if it were being read.
            composing = false; showSettings = false; showMap = false; showFaceTime = false
            if (showInfo) { showInfo = false; media.value = null; mediaError = false }
            if (current?.chat_guid != guid) {
                open(Thread(chat_guid = guid, chat_name = req.chatName, last_rowid = 0))
            }
        }
        if (req.shareId != null) receiveShare(req.shareId)
    }

    // ---- share sheet (ShareActivity.kt, ShareSupport.kt, ShareScreens.kt) ----

    /** A share waiting for a thread: App() shows the chat picker while this is set. */
    var pendingShare by mutableStateOf<PendingShare?>(null); private set
    /** A share aimed at one chat by a direct-share target, waiting for the list to load: App() shows a wait, not the picker. */
    var shareResolving by mutableStateOf<PendingShare?>(null); private set
    /** Files of a share whose thread was chosen, waiting for Send in the confirmation sheet. */
    var shareConfirm by mutableStateOf<PendingShare?>(null); private set
    /** The chat the confirmation sheet was opened for; Send goes there even if
     *  a notification tap changed [current] under the dialog. */
    private var shareConfirmTarget: Thread? = null
    /** The name of the chat Send will go to, for the sheet's title. Set before [shareConfirm], so the
     *  composition that shows the sheet reads the right one. */
    val shareConfirmTitle: String? get() = shareConfirmTarget?.title
    /** Text to append to the composer draft of its chat; the Conversation screen consumes it. */
    var draftAppend by mutableStateOf<DraftAppend?>(null); private set
    private var draftAppendSeq = 0

    /** Reads the share ShareActivity recorded under [id]; a stale or unknown id says so. */
    private fun receiveShare(id: String) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val share = withContext(Dispatchers.IO) { ShareStore.load(app, id) }
            if (share == null) {
                Toast.makeText(app, "Nothing to share", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // A share that arrives over another still waiting replaces it.
            discardShare(pendingShare); discardShare(shareConfirm); discardShare(shareResolving)
            shareConfirm = null; pendingShare = null; shareResolving = null
            // A direct-share target (a conversation shortcut) already named the chat:
            // straight to it, with the files still confirmed on the sheet. On a cold
            // start the list is not there yet (the VM, and its first load, exist only
            // after the unlock), so the share waits behind a plain wait screen until a
            // load has finished, never behind the picker the owner just bypassed. Only
            // a guid the loaded list does not know (left the list, stale shortcut), or
            // a relay that cannot be reached at all, shows the picker as usual.
            val targetGuid = share.targetGuid
            if (targetGuid == null) { pendingShare = share; return@launch }
            shareResolving = share
            withTimeoutOrNull(SHARE_TARGET_LIST_WAIT_MILLIS) { threadsLoaded.first { it } }
            if (shareResolving !== share) return@launch      // cancelled, or another share replaced it
            shareResolving = null
            val target = resolveShareTarget(threads.value, targetGuid)
            // Both state writes land in one snapshot, so the picker never composes in between.
            pendingShare = share
            if (target != null) chooseShareTarget(target)
        }
    }

    private fun discardShare(share: PendingShare?) {
        if (share == null) return
        viewModelScope.launch { withContext(Dispatchers.IO) { ShareStore.delete(share) } }
    }

    /** The picker's thread: open it, put the text in its draft, and ask before sending files. */
    fun chooseShareTarget(t: Thread) {
        val share = pendingShare ?: return
        pendingShare = null
        // Whatever screen the share arrived over gives way to the chosen chat.
        composing = false; showSettings = false; showMap = false; showFaceTime = false
        if (current?.chat_guid != t.chat_guid) open(t)
        share.text?.let { draftAppend = DraftAppend(t.chat_guid, it, ++draftAppendSeq) }
        if (share.hasFiles) { shareConfirmTarget = t; shareConfirm = share } else discardShare(share)
    }

    /** System back on the picker or the wait screen, or Cancel on the sheet: the copied files go. */
    fun cancelShare() {
        discardShare(pendingShare); discardShare(shareConfirm); discardShare(shareResolving)
        pendingShare = null; shareConfirm = null; shareResolving = null
    }

    /** Send on the sheet: the copied files ride the normal attachment path, in order, then their folder goes. */
    fun sendShare(ctx: Context) {
        val share = shareConfirm ?: return
        shareConfirm = null
        val t = shareConfirmTarget ?: current ?: run { discardShare(share); return }
        shareConfirmTarget = null
        val app = ctx.applicationContext
        val uris = share.items.mapNotNull { item ->
            runCatching {
                androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", item.file)
            }.getOrNull()
        }
        sendTick++
        Sfx.playSend(ctx)
        uploadAttachments(app, t, uris) { withContext(Dispatchers.IO) { ShareStore.delete(share) } }
    }

    /** The Conversation screen took [d] into its draft. */
    fun consumeDraftAppend(d: DraftAppend) { if (draftAppend == d) draftAppend = null }

    fun open(t: Thread) {
        if (current?.chat_guid != t.chat_guid) {
            // Another chat takes the screen (a notification tap over an open chat): what
            // belonged to the one before goes with it. Its messages would otherwise stay
            // under the new chat's name until, and unless, the new first page lands, and a
            // reply started there would be sent into this chat (replyTargetGuid).
            messages.value = emptyList()
            cancelReply()
            endEdit()          // edit mode belongs to the chat it was started in, like the reply
        }
        current = threads.value.firstOrNull { it.chat_guid == t.chat_guid }
            ?.let { t.withLabelsFrom(it) } ?: t
        showInfo = false
        resetPaging()
        val req = PageRequest(t.chat_guid, pagingGeneration)
        // Opening a chat dismisses its notifications (a new message, a text that was not
        // sent: its row is on this screen) and stops pushes for it while it is on screen.
        Notifs.openChat = t.chat_guid
        Notifs.clear(getApplication(), t.chat_guid)
        Notifs.clearUnsent(getApplication(), t.chat_guid)
        ConversationShortcuts.pushUsage(getApplication(), t.chat_guid)
        viewModelScope.launch {
            runCatching { Api.messages(t.chat_guid) }.onSuccess {
                // A first page that lands after another chat was opened (or this one was
                // left) is dropped whole, so it can neither overwrite that chat's list nor
                // feed its smallest rowid to the next loadOlder().
                if (!pageStillApplies(req, current?.chat_guid, pagingGeneration)) return@onSuccess
                messages.value = it
                hasOlder = pageHasOlder(it)
                markRead(t.chat_guid, it.maxOfOrNull { m -> m.rowid } ?: t.last_rowid)
                autoTranslateThread()
            }
        }
        // A text here whose send ended without an answer a moment ago (the app was closed over it): its
        // row says "check the chat" while the Mac may still be sending, so the chat is read again for a while.
        recheckOnOpenSince(Outbox.texts.value, t.chat_guid, System.currentTimeMillis())?.let { recheckChat(t.chat_guid, it) }
    }

    // ---- older history (HistoryPaging.kt) ----

    /** More history may exist before the oldest message held: a full first page in an iMessage chat. */
    var hasOlder by mutableStateOf(false); private set
    /** A page of older messages is in flight; the list shows a spinner at its oldest end. */
    var loadingOlder by mutableStateOf(false); private set
    /** Bumped whenever the held list is about to be replaced (open, back, reconnect
     *  reload), so a page that lands late is dropped (see [pageStillApplies]). */
    private var pagingGeneration = 0

    private fun resetPaging() {
        pagingGeneration++
        hasOlder = false
        loadingOlder = false
    }

    /**
     * Fetches the page before the oldest message held and prepends it. One
     * request at a time; nothing happens once the relay reached the start of
     * the chat ([hasOlder] false), and a result for a chat that is no longer
     * the open one is discarded.
     */
    fun loadOlder() {
        if (!hasOlder || loadingOlder) return
        val guid = current?.chat_guid ?: return
        val before = nextBefore(messages.value)
        if (before == null) { hasOlder = false; return }
        val req = PageRequest(guid, pagingGeneration)
        loadingOlder = true
        viewModelScope.launch {
            val result = runCatching { Api.messages(guid, HISTORY_PAGE_SIZE, before) }
            // open()/back()/the reconnect reload already reset the flags; this result is stale.
            if (!pageStillApplies(req, current?.chat_guid, pagingGeneration)) return@launch
            result.onSuccess { page ->
                messages.value = mergeOlderPage(messages.value, page)
                hasOlder = pageHasOlder(page)
            }
            // On failure hasOlder stays true: the next scroll near the end tries again.
            loadingOlder = false
        }
    }

    /** Tells the relay everything up to [rowid] in this chat has been seen. */
    private fun markRead(guid: String, rowid: Long) {
        viewModelScope.launch {
            runCatching { Api.read(guid, rowid) }
            refreshThreads()
        }
    }

    fun back() {
        if (composing) { composing = false; return }
        if (showInfo) { showInfo = false; media.value = null; mediaError = false; return }
        current = null; messages.value = emptyList()
        cancelReply()      // a reply belongs to the chat it was started in
        endEdit()          // and so does edit mode
        resetPaging()
        Notifs.openChat = null
    }

    var composing by mutableStateOf(false); private set
    var creatingChat by mutableStateOf(false); private set

    fun openCompose() { composing = true }

    /** Compose matched an existing thread: land in it and send there directly. */
    fun openExistingAndSend(t: Thread, text: String) {
        composing = false
        open(t)
        send(text)
    }

    fun openExistingAndSendAttachments(t: Thread, ctx: Context, uris: List<Uri>) {
        composing = false
        open(t)
        sendAttachments(ctx, uris)
    }

    var showArchived by mutableStateOf(false); private set

    fun toggleArchiveView() { showArchived = !showArchived }

    fun markUnread(t: Thread) {
        viewModelScope.launch {
            runCatching { Api.markUnread(t.chat_guid) }
            refreshThreads()
        }
    }

    fun archive(t: Thread, archived: Boolean) {
        viewModelScope.launch {
            runCatching { Api.archive(t.chat_guid, archived) }
            refreshThreads()
        }
    }

    /** Swap a pinned chat with its left/right neighbor and persist the order. */
    fun movePin(t: Thread, delta: Int) {
        val order = threads.value.filter { it.pinned }.sortedBy { it.pin_index }
            .map { it.chat_guid }.toMutableList()
        val i = order.indexOf(t.chat_guid)
        val j = i + delta
        if (i < 0 || j < 0 || j >= order.size) return
        order[i] = order[j].also { order[j] = order[i] }
        viewModelScope.launch {
            runCatching { Api.pinOrder(order) }
            refreshThreads()
        }
    }

    /**
     * The first text of a new conversation has no stored entry, so its send id
     * lives here, with the compose screen: a second tap on the same message to
     * the same people is the same send, which the relay does not carry out
     * twice (SendIds.kt). Gone with this ViewModel: if the app is closed before
     * the send has ended and the message is typed again, that is a new send.
     */
    private var newChatSend: NewChatSend? = null

    /** What the compose screen asks about before a start that may deliver the first message a second time. */
    data class NewChatAsk(val addresses: List<String>, val title: String, val text: String)
    var newChatAsk by mutableStateOf<NewChatAsk?>(null); private set

    fun createChat(ctx: Context, addresses: List<String>, title: String, text: String) =
        startNewChat(ctx, addresses, title, text, anyway = false)

    /** "Send anyway" on the compose screen's warning: the same message as a new send, under a new id. */
    fun createChatAnyway(ctx: Context) {
        val ask = newChatAsk ?: return
        newChatAsk = null
        newChatSend = null
        startNewChat(ctx, ask.addresses, ask.title, ask.text, anyway = true)
    }

    fun dismissNewChatAsk() { newChatAsk = null }

    private fun startNewChat(ctx: Context, addresses: List<String>, title: String, text: String, anyway: Boolean) {
        if (creatingChat) return
        val app = ctx.applicationContext
        val send = newChatSendFor(newChatSend, addresses, text, ::newSendId).also { newChatSend = it }
        viewModelScope.launch {
            creatingChat = true
            if (!anyway && send.doubtful) {
                // A try of this very send ended without a certain answer. Under its id a relay that
                // keeps ids cannot start the conversation twice; any other relay could, so ask first.
                val keepsIds = runCatching { Api.relayKeepsSendIds() }.getOrNull()
                currentCoroutineContext().ensureActive()
                if (newChatPlan(send, keepsIds) == NewChatPlan.ASK_FIRST) {
                    creatingChat = false
                    newChatAsk = NewChatAsk(addresses, title, text)
                    return@launch
                }
            }
            Sfx.playSend(ctx)
            val made = runCatching { Api.startChat(addresses, text, send.id) }
                // Class names only: a decoding error would quote the reply, chat identifier included.
                .onFailure { android.util.Log.e("Imsg", sendFailureLogLine("create chat", it)) }
            currentCoroutineContext().ensureActive()    // the screen is gone: nothing left to say
            val reply = made.getOrNull()
            creatingChat = false
            if (reply == null) {
                val error = made.exceptionOrNull()
                newChatSend = newChatAfter(send, error)
                if (newChatMustAsk(error)) {
                    // The relay knows this send and cannot say what became of it: only "Send anyway" is left.
                    newChatAsk = NewChatAsk(addresses, title, text)
                } else {
                    // The first text travels like any send: unless the refusal is certain it may have
                    // been delivered, and the typed text is still in the field for a second tap.
                    Toast.makeText(app, createChatFailureMessage(error), Toast.LENGTH_LONG).show()
                }
            } else {
                newChatSend = null
                composing = false
                val guid = reply.chatGuid
                if (guid != null) open(Thread(chat_guid = guid, chat_name = title, last_rowid = 0))
                else Toast.makeText(app, NEW_CHAT_ALREADY_SENT, Toast.LENGTH_LONG).show()
                refreshThreads()
            }
        }
    }

    fun openInfo() {
        val t = current ?: return
        showInfo = true
        mediaError = false
        media.value = null
        viewModelScope.launch {
            runCatching { Api.media(t.chat_guid) }
                .onSuccess { media.value = it }
                .onFailure { mediaError = true }
        }
    }

    /** True while sends are going out via the AppleScript fallback. */
    var fallbackMode by mutableStateOf(false); private set

    /** What this session has seen of the relay's send path (SendRecovery.kt). */
    private var sendPath = SendPathState()

    /**
     * The two toasts about the send path, decided by [sendPathAfter]: "down" only
     * on a change from BlueBubbles to AppleScript (never on a relay whose only
     * engine is AppleScript), "restored" only after a "down".
     *
     * That this relay delivers through BlueBubbles is remembered across restarts
     * (AppPrefs, by [relayMark]; written the first time a send goes that way), so
     * opening the app while BlueBubbles is already down still says so on the
     * first send, with the "Fallback mode" line, as it did before the state
     * machine. A relay that never delivered through BlueBubbles has no mark.
     */
    private fun noteSendPath(via: String?) {
        AppPrefs.init(getApplication())
        val base = RelayConfigStore.current.base
        val remembered = blueBubblesKnownFor(AppPrefs.blueBubblesRelay(), base)
        val step = sendPathAfter(sendPathSeeded(sendPath, remembered), via)
        if (via == SEND_VIA_BLUEBUBBLES && !remembered && base.isNotEmpty()) AppPrefs.putBlueBubblesRelay(relayMark(base))
        sendPath = step.state
        fallbackMode = step.state.fallback
        when (step.notice) {
            SendPathNotice.FALLBACK -> Toast.makeText(getApplication(),
                "BlueBubbles down — sent via AppleScript fallback", Toast.LENGTH_LONG).show()
            SendPathNotice.RESTORED -> Toast.makeText(getApplication(),
                "BlueBubbles path restored", Toast.LENGTH_SHORT).show()
            SendPathNotice.NONE -> {}
        }
    }

    var replyingTo by mutableStateOf<Msg?>(null); private set
    /** The chat [replyingTo] was started in; a send anywhere else does not carry it ([replyTargetGuid]). */
    private var replyingChat: String? = null

    fun startReply(m: Msg) {
        // While the composer edits a message it has no reply banner: the reply waits with the stashed
        // draft and shows when edit mode ends (editReplyChosen, EditUnsend.kt).
        val edit = editModeFor(editing, current?.chat_guid)
        if (edit != null) { editing = editReplyChosen(edit, m); return }
        replyingTo = m; replyingChat = current?.chat_guid
    }
    fun cancelReply() { replyingTo = null; replyingChat = null }

    /** guid -> translated text; "…" while in flight. */
    val translations = mutableStateMapOf<String, String>()

    private suspend fun requestTranslation(text: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = org.json.JSONObject().put("text", text).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$BASE/translate").post(body).build()
            http.newCall(req).execute().use { r ->
                // null = request failed (retryable); "" = relay says the text
                // is already English (cache the skip, show nothing).
                if (!r.isSuccessful) null
                else org.json.JSONObject(r.body!!.string()).optString("translation")
            }
        }.getOrNull()
    }

    fun translate(m: Msg) {
        // The Translation switch (TranslationGate, Features.kt) gates every path to /translate here.
        if (!Features.translation) return
        val text = m.text ?: return
        if (translations[m.guid]?.let { it != "…" } == true) return
        translations[m.guid] = "…"
        viewModelScope.launch {
            val out = requestTranslation(text)
            if (out != null) translations[m.guid] = out   // "" = cached skip
            else translations.remove(m.guid)              // error — retry allowed
        }
    }

    private var autoJob: Job? = null

    /** Translate-on-open for flagged threads: incoming messages only, newest 30,
     *  strictly sequential so Ollama serves one request at a time and its idle
     *  timer can start the moment we're done. */
    private fun autoTranslateThread() {
        if (!Features.translation) return   // a thread flagged on the relay stays quiet while the feature is off
        val t = current ?: return
        if (!t.auto_translate) return
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            val targets = messages.value
                .filter {
                    !it.is_from_me && translations[it.guid] == null &&
                        // must contain an actual letter — attachment-only
                        // messages carry an invisible U+FFFC placeholder that
                        // isn't worth a network call (or a hallucinated "D")
                        it.text?.any { ch -> ch.isLetter() } == true
                }
                .takeLast(30)
            for (m in targets) {
                if (current?.chat_guid != t.chat_guid) break   // left the thread
                translations[m.guid] = "…"
                val out = requestTranslation(m.text!!)
                // "" = already English: cached so it's never re-queried; null =
                // error: removed so a later open can retry.
                if (out != null) translations[m.guid] = out else translations.remove(m.guid)
            }
        }
    }

    fun setAutoTranslate(t: Thread, on: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val body = org.json.JSONObject()
                        .put("chat_guid", t.chat_guid).put("enabled", on).toString()
                        .toRequestBody("application/json".toMediaType())
                    http.newCall(Request.Builder().url("$BASE/auto_translate").post(body).build())
                        .execute().close()
                }
            }
            refreshThreads()
            if (current?.chat_guid == t.chat_guid) {
                current = current?.copy(auto_translate = on)
                if (on) autoTranslateThread()
            }
        }
    }

    /** Bumps on every outgoing send — the conversation scrolls to bottom on it. */
    var sendTick by mutableIntStateOf(0); private set

    /** Bumps when refetched messages have actually been swapped in, so the
     *  scroll happens after the data lands — timers race the network. */
    var scrollTick by mutableIntStateOf(0); private set

    /**
     * Sends [text] into the open chat. The text goes to the outbox (Outbox.kt),
     * which keeps it on the phone until the relay answers "delivered" and sends
     * it outside this ViewModel's lifetime, so leaving the screen (or the
     * activity finishing) neither cancels the send nor loses its outcome. A
     * text that fails, or whose send ends without an answer, stays in the
     * outbox and shows above its chat's composer with "Send again" and
     * "Discard"; it is not put back into the composer, which is left as the
     * owner has it. How the send ended comes back through [textSendEnded].
     */
    fun send(text: String) {
        val t = current ?: return
        val target = replyTargetGuid(replyingTo, replyingChat, t.chat_guid)
        cancelReply()
        sendTick++
        // The list's name for the chat when it has one: a chat opened from a notification is a bare stub.
        val listed = threads.value.firstOrNull { it.chat_guid == t.chat_guid } ?: t
        Outbox.send(getApplication(), t.chat_guid, unsentChatTitle(listed.title, t.chat_guid), text, target)
    }

    /** "Send again" on an unsent text's row: the same text, replying to the same message, once more. */
    fun sendAgain(entry: UnsentText) {
        sendTick++
        Outbox.sendAgain(getApplication(), entry.id)
    }

    /** "Send anyway" on an unsent text's row, after its warning was confirmed: a new message under a new id. */
    fun sendAnyway(entry: UnsentText) {
        sendTick++
        Outbox.sendAnyway(getApplication(), entry.id)
    }

    /** "Discard" on an unsent text's row. */
    fun discardUnsent(entry: UnsentText) = Outbox.discard(getApplication(), entry.id)

    /**
     * One text send ended (from the composer, a row's "Send again", or a reply
     * typed in a notification): the send-path toasts follow its path, and the
     * open chat is brought up to date where the socket will not do it.
     */
    private fun textSendEnded(r: TextSendResult) {
        noteSendPath(r.via)
        val t = current?.takeIf { it.chat_guid == r.chatGuid } ?: return
        if (r.via != null) {
            // gmessages: Beeper's WS doesn't echo our own sends (verified —
            // ImsgWS stays silent after sending), so quietly re-fetch this
            // thread. Stable list keys make the refresh invisible: unchanged
            // messages don't re-render, the new bubble just appears at the
            // bottom with its real server timestamp. iMessage needs none of
            // this — the chat.db poll always echoes sends.
            if (t.network == "gmessages") viewModelScope.launch {
                delay(400)   // give Beeper a beat to register the send
                var got = runCatching { Api.messages(t.chat_guid) }.getOrNull()
                if (got != null && got.none { it.is_from_me && it.text == r.text }) {
                    delay(900)   // slow bridge — one more try
                    got = runCatching { Api.messages(t.chat_guid) }.getOrNull() ?: got
                }
                if (got != null && current?.chat_guid == t.chat_guid) {
                    messages.value = got
                    scrollTick++
                }
            }
        } else if (r.why?.certain == false) {
            recheckChat(t.chat_guid, r.sentAtMillis)
        }
    }

    /**
     * A send into the open chat ended without an answer, and its row says
     * "check the chat": re-read the chat's latest page a few times while the
     * Mac may still be sending ([recheckDelaysMillis]) and add what is new
     * ([mergeLatestPage]), so the chat on screen shows a message that did go
     * out. Needed where nothing echoes my own sends (Google Messages) and
     * whenever the socket was down at that moment. It only adds bubbles: the
     * unsent text stays until the owner deals with it.
     */
    private fun recheckChat(guid: String, sentAtMillis: Long) = viewModelScope.launch {
        for (wait in recheckDelaysMillis(System.currentTimeMillis() - sentAtMillis)) {
            delay(wait)
            if (current?.chat_guid != guid) return@launch
            val page = runCatching { Api.messages(guid) }.getOrNull() ?: continue
            if (current?.chat_guid != guid) return@launch
            if (messages.value.isEmpty()) {
                messages.value = page
                hasOlder = pageHasOlder(page)
            } else {
                messages.value = mergeLatestPage(messages.value, page)
            }
        }
    }

    /** Count of attachment uploads in flight — drives the composer spinner. */
    var sending by mutableIntStateOf(0); private set

    fun sendAttachments(ctx: Context, uris: List<Uri>) {
        sendTick++
        Sfx.playSend(ctx)
        val t = current ?: return
        val app = ctx.applicationContext
        uploadAttachments(app, t, uris)
    }

    /**
     * Uploads [uris] to [t] one after another (the picker, the keyboard and the share sheet all end
     * here). Uploads.kt does it, outside this ViewModel's lifetime: leaving the chat does not end an
     * upload, and one that fails is reported whoever is or is not looking. How each ended comes back
     * through [uploadEnded]; [afterAll] runs when the last one has.
     */
    private fun uploadAttachments(app: Context, t: Thread, uris: List<Uri>, afterAll: suspend () -> Unit = {}) {
        // The list's name for the chat when it has one: a chat opened from a notification is a bare stub.
        val listed = threads.value.firstOrNull { it.chat_guid == t.chat_guid } ?: t
        Uploads.send(app, t.chat_guid, unsentChatTitle(listed.title, t.chat_guid), uris, afterAll)
    }

    private fun uploadEnded(result: UploadResult) {
        if (result.via != null && current?.chat_guid == result.chatGuid) noteSendPath(result.via)
    }

    /** Keyboard GIF/sticker (InputConnection.commitContent). The IME hands us a
     *  content:// URI under a temporary grant, so the caller opens the stream
     *  synchronously while that grant is certainly alive; here we stage a copy
     *  under our own FileProvider so the upload sees a stable name + mime (IME
     *  providers don't reliably answer getType/DISPLAY_NAME), then ride the
     *  normal attachment path (spinner, toast, send sound, scroll-to-bottom). */
    fun sendKeyboardContent(ctx: Context, input: java.io.InputStream, mime: String) {
        val t = current ?: run { input.close(); return }
        val app = ctx.applicationContext
        viewModelScope.launch {
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    val ext = android.webkit.MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(mime) ?: "gif"
                    val dir = java.io.File(app.cacheDir, "shared").apply { mkdirs() }
                    // Keep the staging area from growing without bound.
                    dir.listFiles()?.forEach {
                        if (it.name.startsWith("keyboard-") &&
                            System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete()
                    }
                    val f = java.io.File(dir, "keyboard-${System.currentTimeMillis()}.$ext")
                    input.use { src -> f.outputStream().use { out -> src.copyTo(out) } }
                    androidx.core.content.FileProvider.getUriForFile(
                        app, "${app.packageName}.fileprovider", f,
                    )
                }.onFailure { android.util.Log.e("Imsg", "keyboard content stage failed (${failureLabel(it)})") }
                    .getOrNull()
            }
            if (staged == null) {
                Toast.makeText(app, "Couldn't send attachment", Toast.LENGTH_SHORT).show()
            } else if (current?.chat_guid == t.chat_guid) {
                sendAttachments(app, listOf(staged))
            }
        }
    }

    fun react(m: Msg, reaction: String) {
        val t = current ?: return
        // Caught: a tapback whose request fails (no network, a relay that takes too long to answer) says
        // nothing, like one the relay refuses; it shows on its message only once the Mac has made it.
        // Uncaught, the exception ended the whole process, and with it every send in flight and the draft
        // in the composer.
        viewModelScope.launch {
            runCatching { Api.react(t.chat_guid, m.guid, reaction) }
                // Class names only, like every failed send's line.
                .onFailure { android.util.Log.e("Imsg", sendFailureLogLine("reaction", it)) }
        }
    }

    // ---- Edit and Undo Send (EditUnsend.kt has the rules; Api.edit and Api.unsend the requests) ----

    /**
     * The composer's edit mode, held here like the reply target so a rotation or
     * a dark-mode flip keeps it. It belongs to one chat and ends when that chat
     * is left ([endEdit]); the Conversation screen only renders it.
     */
    var editing by mutableStateOf<EditMode?>(null); private set
    private var editSeq = 0

    /**
     * A text the composer's field is to show, cursor at the end: the message's
     * text on entering edit mode, the stashed draft on leaving it. The field is
     * the Conversation screen's own state, so the screen takes it from here and
     * says so ([consumeComposerText]), as it does a shared text ([draftAppend]).
     */
    var composerText by mutableStateOf<ComposerText?>(null); private set
    private var composerTextSeq = 0

    /**
     * The messages (by guid) whose Undo Send is on its way, or was confirmed and
     * has not emptied the bubble yet ([changeHeldUntilShown]); their bubbles say
     * "Unsending…" meanwhile ([showsUnsending]) and the panel offers them nothing.
     */
    var unsending by mutableStateOf<Set<String>>(emptySet()); private set

    /**
     * The messages (by guid) whose edit is on its way, or was confirmed and does
     * not show yet. Held apart from [editing], which ends with its chat: the
     * request does not, and the panel must not offer the same message a second
     * edit, or an Undo Send, while the first is still out ([panelChangesFor]).
     */
    var savingEdits by mutableStateOf<Set<String>>(emptySet()); private set

    /** The Conversation screen put [c] into its field. */
    fun consumeComposerText(c: ComposerText) { if (composerText == c) composerText = null }

    /** Edit mode goes with its chat. A request already on its way still ends by itself, and is told if it fails. */
    private fun endEdit() { editing = null; composerText = null }

    /** What one edit-mode event decided ([EditStep]), carried out. */
    private fun applyEdit(step: EditStep) {
        val chat = (step.mode ?: editing)?.chatGuid
        editing = step.mode
        if (step.field != null && chat != null) composerText = ComposerText(chat, step.field, ++composerTextSeq)
        if (step.setsReply) { replyingTo = step.reply; replyingChat = step.reply?.let { chat } }
        step.toast?.let { Toast.makeText(getApplication(), it, Toast.LENGTH_LONG).show() }
        step.request?.let { sendEdit(it) }
    }

    /** "Edit" in the long-press panel: the composer, which holds [draft], edits [m] from now on. */
    fun startEdit(m: Msg, draft: String) {
        val t = current ?: return
        // The message as held now: the panel's copy is from the moment of the long press.
        val now = messages.value.firstOrNull { it.guid == m.guid } ?: m
        val reply = replyingTo?.takeIf { replyingChat == t.chat_guid }
        applyEdit(editEnter(editModeFor(editing, t.chat_guid), ++editSeq, now, t.chat_guid, draft, reply))
    }

    /** The composer's field changed while in edit mode. */
    fun editTextChanged(text: String) { editing = editTyped(editing, text) }

    /** Text shared into this chat while in edit mode: it joins the stashed draft and shows when edit mode ends. */
    fun stashDraftAppend(text: String) { editing = editing?.let { editDraftAppended(it, text) } }

    /** The close button on the "Editing message" banner. */
    fun cancelEdit() = applyEdit(editCancel(editing))

    /** The send button in edit mode; [text] is what the field holds at the tap. */
    fun submitEdit(text: String) {
        editing = editTyped(editing, text)
        applyEdit(editSubmit(editing))
    }

    private fun sendEdit(req: EditRequest) {
        val before = messages.value.firstOrNull { it.guid == req.guid }
        val kind = chatKindOf(before, current?.takeIf { it.chat_guid == req.chatGuid })
        savingEdits = savingEdits + req.guid
        viewModelScope.launch {
            val outcome = Api.edit(req.chatGuid, req.guid, req.text)
            val recheck = changeEnded(ChangeAction.EDIT, req.chatGuid, before, kind, outcome)
            applyEdit(editOutcome(editing, req, outcome, toastChatFor(req.chatGuid)))
            // A confirmed edit stays marked until the bubble shows it, or the chat was read again.
            if (changeHeldUntilShown(outcome)) recheck?.join()
            savingEdits = savingEdits - req.guid
        }
    }

    /**
     * For a toast about [chatGuid]: null while that chat is the one on screen,
     * otherwise its name (blank when it has none), so the toast names the chat
     * it is about ([changeToast]). The answer to an edit or an unsend may come
     * after its chat was left.
     */
    private fun toastChatFor(chatGuid: String): String? {
        if (current?.chat_guid == chatGuid) return null
        val listed = threads.value.firstOrNull { it.chat_guid == chatGuid } ?: return ""
        return unsentChatTitle(listed.title, chatGuid)
    }

    /**
     * "Undo Send" in the long-press panel: one request for [m], and a quiet
     * "Unsending…" under its bubble while it runs. A second tap for a message
     * already on its way is ignored. When the relay confirms, its own update
     * frame empties the bubble, and the message stays marked until it has (or
     * until the chat was read again), so "Unsending…" does not go while the
     * bubble is still there; a refusal, or an end without an answer, is a toast.
     */
    fun unsend(m: Msg) {
        val t = current ?: return
        unsending = unsendStarted(unsending, m.guid) ?: return
        val kind = chatKindOf(m, t)
        viewModelScope.launch {
            val outcome = Api.unsend(t.chat_guid, m.guid)
            val recheck = changeEnded(ChangeAction.UNSEND, t.chat_guid, m, kind, outcome)
            changeToast(ChangeAction.UNSEND, outcome, toastChatFor(t.chat_guid))?.let {
                Toast.makeText(getApplication(), it, Toast.LENGTH_LONG).show()
            }
            if (changeHeldUntilShown(outcome)) recheck?.join()
            unsending = unsendEnded(unsending, m.guid)
        }
    }

    /**
     * An edit or an unsend ended: a relay that cannot do it is remembered for
     * the session ([ChangeSupport]), a change that was not confirmed is logged
     * (names only: no text, no guid, no chat), and the open chat is looked at
     * again where the relay's update frame may not arrive ([recheckChange],
     * whose job is returned; null when the message was not held).
     */
    private fun changeEnded(action: ChangeAction, chatGuid: String, before: Msg?, kind: ChatKind, outcome: ChangeOutcome): Job? {
        ChangeSupport.note(action, kind, outcome)
        if (outcome !is ChangeOutcome.Done) android.util.Log.e("Imsg", changeLogLine(action, outcome))
        return before?.let { recheckChange(action, chatGuid, it, outcome) }
    }

    /**
     * After a change the relay confirmed, one that ended without an answer, or
     * one the relay said the Mac did not apply: if the message on screen is
     * still as it was [before] when the wait is over
     * ([changeRecheckDelaysMillis], [changeStillPending]: the socket was down,
     * or the frame was missed), the chat's latest page is read once more and
     * that one message taken from it ([withMessageFrom]). Nothing else in the
     * held list is touched. The job ends as soon as the change shows (the
     * relay's update frame, normally) or the chat is left, which is what the
     * in-flight marks wait for ([changeHeldUntilShown]).
     */
    private fun recheckChange(action: ChangeAction, chatGuid: String, before: Msg, outcome: ChangeOutcome) = viewModelScope.launch {
        for (wait in changeRecheckDelaysMillis(outcome)) {
            withTimeoutOrNull(wait) { messages.first { !changeStillPending(action, before, it) } }
            if (current?.chat_guid != chatGuid || !changeStillPending(action, before, messages.value)) return@launch
            val page = runCatching { Api.messages(chatGuid) }.getOrNull() ?: continue
            if (current?.chat_guid != chatGuid) return@launch
            messages.value = withMessageFrom(messages.value, page, before.guid)
        }
    }

    fun togglePin(t: Thread) = viewModelScope.launch {
        runCatching { Api.pin(t.chat_guid, !t.pinned) }
        refreshThreads()
    }

    override fun onCleared() { wsm.stop() }

    /**
     * The last thing in the class, and it has to stay that: Kotlin initialises a
     * class from top to bottom, and this block starts coroutines that run inside
     * the constructor. viewModelScope runs a coroutine started on the main
     * thread in place up to its first suspension, and nothing promises that a
     * load suspends at all: one that ends before it does (it failed at once, or
     * its answer was already there) takes refreshThreads() to its last line
     * right here. A property declared below this block would still be null
     * then, whatever its type says, and the app would die in its constructor.
     * ChatVMConstructionTest pins the order.
     */
    init {
        threadsLoadPending()
        viewModelScope.launch {
            refreshThreads()
            wsm.start()
        }
        // How each text send ended (Outbox.kt sends them, outside this ViewModel's lifetime).
        viewModelScope.launch { Outbox.results.collect { textSendEnded(it) } }
        // The same for attachments (Uploads.kt), and how many are in flight for the composer's spinner.
        viewModelScope.launch { Uploads.results.collect { uploadEnded(it) } }
        viewModelScope.launch { Uploads.inFlight.collect { sending = it } }
    }
}

/** ChatVM's SavedStateHandle key: the seq it last acted on. */
private const val STATE_HANDLED_LAUNCH_SEQ = "handled_launch_seq"
