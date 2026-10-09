package io.github.avtraang.selfbubbles

// What the voice screen shows and says when a call to the relay does not come
// back with a line of the relay's own. Plain functions (VoiceCallsTest); the
// activity is VoiceActivity.kt.

import kotlinx.coroutines.CancellationException

/** The three calls of one voice conversation. */
enum class VoiceStep {
    /** The spoken sentence goes to the relay, which answers with a question or says why not. */
    PREPARE,
    /** The answer to the relay's question. A "yes" makes the relay send while this call waits. */
    ANSWER,
    /** Nothing was heard for an answer: the relay is told to drop the message. */
    CANCEL,
}

/** No call that could have sent anything got as far as the relay. */
const val VOICE_NOTHING_SENT = "Couldn't reach the relay. Nothing was sent."

/** Something answered with a refusal (a rejected token, Cloudflare Access, a missing route): no engine was asked. */
const val VOICE_REFUSED = "The relay refused the request. Nothing was sent."

/** The answer left the phone, or may have, and no reply came back: the Mac may be sending. */
const val VOICE_ANSWER_UNHEARD = "No reply from the relay. Check the chat before sending it again."

const val VOICE_CANCELLED = "Cancelled."

/** The line for a call that ended with [reply] (null: an answer that was not the relay's) or threw [error]. */
fun voiceLine(step: VoiceStep, reply: String?, error: Throwable?): String = when {
    reply != null -> reply
    // Nothing is sent without a yes, and no yes was given.
    step == VoiceStep.CANCEL -> VOICE_CANCELLED
    // A status that refuses the request before any engine runs ([unsentWhyFor]): said as what it is.
    error is SendFailedException && error.httpCode != null && unsentWhyFor(error).certain -> VOICE_REFUSED
    // A sentence that did not arrive sent nothing, whatever stopped it.
    step == VoiceStep.PREPARE -> VOICE_NOTHING_SENT
    // The same test a text send uses: only a failure before anything was written is certain.
    error != null && unsentWhyFor(error).certain -> VOICE_NOTHING_SENT
    else -> VOICE_ANSWER_UNHEARD
}

/**
 * What a voice call's answer is worth: the relay's line for a 2xx, and for any
 * other status a [SendFailedException] carrying that status and nothing of the
 * body, so [voiceLine] can tell a refusal (nothing was sent) from an answer
 * that never came (it may have been).
 */
fun voiceReply(code: Int, body: String?): String? {
    if (code !in 200..299) throw SendFailedException(sendFailureFor(code), "voice HTTP $code", code)
    return body?.trim()
}

/**
 * Runs one call of the conversation and returns the line to show and say.
 * Whatever [call] throws ends up as a line, never at the activity: thrown
 * there, with nothing to catch it, it ended the app's process while the Mac
 * went on delivering. Only a cancellation (the screen was left) passes
 * through. [onError] is for the log.
 */
suspend fun voiceCall(step: VoiceStep, onError: (Throwable) -> Unit = {}, call: suspend () -> String?): String {
    val reply = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { onError(e) }
        return voiceLine(step, null, e)
    }
    return voiceLine(step, reply, null)
}
