package io.github.avtraang.selfbubbles

// Reading the open chat's latest page, and what the screen holds while that read is
// starting, failing or being tried again. Plain functions (ChatLoadingTest); ChatVM
// applies them, the strip that shows a failure is in UnsentStrip.kt.
//
// The rules: a read that fails is said, with a Retry. No read takes anything off the
// screen before it has succeeded. The app stores no messages and keeps none of a chat
// that is not open, so a chat opened while offline shows the failure and nothing else.

/** What is known about the latest read of the open chat. */
enum class ChatLoad {
    /** The last read succeeded, or no chat is open. */
    IDLE,
    /** A read is in flight: the first page, a reload after a reconnect, or Retry. */
    LOADING,
    /** The last read failed. What was on the screen is still there. */
    FAILED,
}

const val CHAT_LOAD_FAILED_MESSAGE = "Couldn't load this conversation. Check your connection and retry."
const val CHAT_LOAD_RETRY_LABEL = "Retry"

/** The open chat's messages as held, with the state of its latest read. */
data class ChatPage<T>(val messages: List<T>, val load: ChatLoad = ChatLoad.IDLE)

/**
 * A chat takes the screen and its latest page is about to be read. The chat
 * that is already open keeps what it shows. Another chat's messages go: they
 * would stand under this chat's name until, and unless, its own page lands,
 * and a reply started there would be sent into this chat.
 */
fun <T> chatOpening(held: ChatPage<T>, sameChat: Boolean): ChatPage<T> =
    ChatPage(if (sameChat) held.messages else emptyList(), ChatLoad.LOADING)

/** A read of the open chat starts. Nothing that is shown is taken away. */
fun <T> chatLoadStarted(held: ChatPage<T>): ChatPage<T> = held.copy(load = ChatLoad.LOADING)

/**
 * The read ended: with [page], which is what the chat holds now (an empty
 * page is a conversation with no messages yet, not a failure); or without one
 * (null), and then what is held stays and the failure is shown.
 */
fun <T> chatLoadEnded(held: ChatPage<T>, page: List<T>?): ChatPage<T> =
    if (page != null) ChatPage(page, ChatLoad.IDLE) else held.copy(load = ChatLoad.FAILED)

/** The chat is left. Nothing of it is kept: there is no local copy to show the next time it is opened. */
fun <T> chatLeft(): ChatPage<T> = ChatPage(emptyList(), ChatLoad.IDLE)

/** Whether the "couldn't load" strip shows. */
fun chatLoadFailureShows(load: ChatLoad): Boolean = load == ChatLoad.FAILED
