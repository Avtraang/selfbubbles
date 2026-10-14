package io.github.avtraang.selfbubbles

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process entry point. Loads the stored relay overrides before any activity,
 * service or the push receiver can make a request, so every component in the
 * process reads the same [RelayConfigStore.current]. With nothing stored, that
 * is exactly the build's own values.
 */
class ImsgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RelayConfigStore.init(this)
        Features.init(this)
        // The texts not yet delivered (Outbox.kt), before a notification reply or a screen can add one.
        Outbox.init(this)
        Uploads.init(this)
        // What the relay offers, asked once per process start (Features.refreshFromRelay): a feature the relay
        // gained since the last Test connection shows up on the next launch, not after a visit to Settings.
        processScope.launch { Features.refreshFromRelay(alsoChanges = true) }
    }

    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
