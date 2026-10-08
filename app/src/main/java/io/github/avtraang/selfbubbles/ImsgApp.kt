package io.github.avtraang.selfbubbles

import android.app.Application

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
    }
}
