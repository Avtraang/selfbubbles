package io.github.avtraang.selfbubbles

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume
import io.github.avtraang.selfbubbles.ui.theme.MessagesTheme
import io.github.avtraang.selfbubbles.ui.theme.Spacing

/**
 * "Hey Google, open Voice Text" -> this activity. It runs the whole voice
 * conversation itself: listen, ask the relay, speak the answer, listen for a
 * yes/no (or a contact name), send, speak the result, close.
 *
 * All the intelligence (contact matching, fuzzy names, chat lookup) lives on the
 * relay; this is just ears, mouth, and two HTTP calls. Registered as its own
 * launcher entry named "Voice Text" because launching an app by name is the one
 * capability every assistant reliably has.
 *
 * Deliberately NOT behind the app lock (AppLock.kt): a hands-free entry cannot
 * ask for a fingerprint, and it shows no history — only the answer to the one
 * question the owner just spoke. Only MainActivity is gated.
 *
 * The Voice assistant switch (Features.kt) turns the launcher entry off and on
 * through [VoiceComponent]; the manifest entry itself never changes.
 */
class VoiceActivity : ComponentActivity() {

    private var status by mutableStateOf("Starting\u2026")
    private var busy by mutableStateOf(true)

    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()

    // Uses the system speech dialog (RecognizerIntent), so no RECORD_AUDIO
    // permission is needed — the Google app owns the mic.
    private var onSpeech: ((String?) -> Unit)? = null
    private val recognizer =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res: ActivityResult ->
            val heard = res.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            onSpeech?.invoke(heard)
            onSpeech = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Off in Settings: say so and leave before the TTS engine or the mic is touched.
        if (!Features.voiceAssistant) {
            Toast.makeText(this, "Voice assistant is turned off in Settings", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        enableEdgeToEdge()

        tts = TextToSpeech(this) { code ->
            if (code == TextToSpeech.SUCCESS) tts?.language = Locale.US
            ttsReady.complete(code == TextToSpeech.SUCCESS)
        }

        setContent {
            MessagesTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                        ) {
                            if (busy) CircularProgressIndicator()
                            // No 20sp step in the scale: the Name style (17 Medium) is the nearest.
                            Text(status, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            try {
                runConversation()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The last line of defence: nothing thrown here may end the process (class name only).
                android.util.Log.e("Imsg", "voice conversation stopped (${failureLabel(e)})")
                runCatching { finish() }
            }
        }
    }

    private suspend fun runConversation() {
        ttsReady.await()

        status = "Listening\u2026"
        val spoken = listen("Say your message")
        if (spoken.isNullOrBlank()) {
            finishWith("Didn't catch that.")
            return
        }
        status = "\u201C$spoken\u201D"

        val reply = voiceCall(VoiceStep.PREPARE, ::logVoiceFailure) { Api.voicePrepare(spoken) }
        status = reply
        say(reply)

        // Only a question needs an answer; anything else ("I couldn't tell who
        // to send that to.") is terminal.
        if (!reply.trim().endsWith("?")) {
            finishWith(reply)
            return
        }

        busy = false
        val answer = listen("Yes or no")
        busy = true
        if (answer.isNullOrBlank()) {
            val cancelled = voiceCall(VoiceStep.CANCEL, ::logVoiceFailure) { Api.voiceConfirm("cancel") }
            finishWith(cancelled, speak = true)
            return
        }

        val result = voiceCall(VoiceStep.ANSWER, ::logVoiceFailure) { Api.voiceConfirm(answer) }
        finishWith(result, speak = true)
    }

    /** Class names and the status only: the throwable's text can name the relay's address. */
    private fun logVoiceFailure(e: Throwable) {
        android.util.Log.e("Imsg", sendFailureLogLine("voice call", e))
    }

    private suspend fun finishWith(message: String, speak: Boolean = false) {
        busy = false
        status = message
        if (speak) say(message)
        delay(1200)
        finish()
    }

    private suspend fun listen(prompt: String): String? =
        suspendCancellableCoroutine { cont ->
            onSpeech = { heard -> if (cont.isActive) cont.resume(heard) }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            runCatching { recognizer.launch(intent) }
                .onFailure { if (cont.isActive) cont.resume(null) }
        }

    /** Speaks and waits for it to finish, so the mic never hears the TTS. */
    private suspend fun say(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        val engine = tts
        if (engine == null) {
            cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (cont.isActive) cont.resume(Unit)
            }
            @Deprecated("required override", ReplaceWith(""))
            override fun onError(utteranceId: String?) {
                if (cont.isActive) cont.resume(Unit)
            }
        })
        val id = "imsg-${System.currentTimeMillis()}"
        val res = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (res != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}

/**
 * The launcher entry's enabled state follows the Voice assistant switch
 * (Features.kt), through PackageManager on the unchanged manifest component:
 * DEFAULT when on — not ENABLED, so the manifest stays authoritative and the
 * pinned icon and Assistant resolution keep working as declared — and DISABLED
 * when off. The decision ([transition]) is pure (VoiceComponentTest); [apply]
 * reads the current state first and writes only a real change, so the owner's
 * app never churns the component on every launch.
 */
object VoiceComponent {
    enum class State { DEFAULT, DISABLED }

    fun desired(featureOn: Boolean): State = if (featureOn) State.DEFAULT else State.DISABLED

    /**
     * The state to set, or null when [current] already is it. [current] is
     * null for any other PackageManager state (ENABLED, DISABLED_USER, …),
     * which always differs from what the switch wants.
     */
    fun transition(current: State?, featureOn: Boolean): State? = desired(featureOn).takeIf { it != current }

    /** Applies the switch to the component; MainActivity.onCreate and the Settings switch call this. */
    fun apply(ctx: Context) {
        val pm = ctx.packageManager
        val name = ComponentName(ctx, VoiceActivity::class.java)
        val current = when (pm.getComponentEnabledSetting(name)) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> State.DEFAULT
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> State.DISABLED
            else -> null
        }
        val next = transition(current, Features.voiceAssistant) ?: return
        val state = when (next) {
            State.DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            State.DISABLED -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching { pm.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP) }
    }
}
