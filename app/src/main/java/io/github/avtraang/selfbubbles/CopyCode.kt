package io.github.avtraang.selfbubbles

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast

/**
 * Finds one-time verification codes in message text, Google Messages style.
 * Gated on keywords so ordinary numbers (addresses, prices) don't trigger it.
 */
object Otp {
    private val keyword = Regex(
        """(?i)\b(code|verification|verify|one[- ]?time|passcode|otp|2fa|pin|security)\b"""
    )
    private val number = Regex("""\b(\d{4,8})\b""")

    fun find(text: String?): String? {
        if (text.isNullOrBlank() || !keyword.containsMatchIn(text)) return null
        return number.findAll(text).map { it.groupValues[1] }.firstOrNull()
    }
}

/**
 * Invisible activity that copies a code and vanishes. Notification actions
 * can't touch the clipboard from the background (Android 10+), but a
 * momentarily-foreground translucent activity can — the standard workaround.
 */
class CopyCodeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent.getStringExtra("code")
        if (!code.isNullOrBlank()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("verification code", code))
            Toast.makeText(this, "Copied $code", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
