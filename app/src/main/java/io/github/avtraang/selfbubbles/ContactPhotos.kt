package io.github.avtraang.selfbubbles

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Contact photos come from the *phone's* address book, not the relay — the
 * Mac's iCloud contacts carry no images (BlueBubbles reports 0 of 191 with an
 * avatar), while the phone's Google contacts do. Same source Beeper uses.
 *
 * Addresses are matched on their last 10 digits, which sidesteps every
 * formatting war (+1 818…, (818) …, 818-…) without needing libphonenumber.
 */
object ContactPhotos {

    private var byKey: Map<String, Uri> = emptyMap()
    private var byName: Map<String, Uri> = emptyMap()
    private var loaded = false

    /** Bumped whenever the photo map changes. Avatars key their lookup on this
     *  so they repaint the moment contacts load — e.g. right after the
     *  permission is granted, without needing an app restart. */
    var version by mutableIntStateOf(0)
        private set

    private fun key(addr: String?): String? {
        if (addr.isNullOrBlank()) return null
        if (addr.contains("@")) return addr.trim().lowercase()
        val digits = addr.filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else null
    }

    /** Reads the phone's contacts once. Safe to call without permission — it
     *  simply yields no photos, and avatars fall back to initials. */
    @SuppressLint("Range")
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val map = HashMap<String, Uri>()
        val names = HashMap<String, Uri>()

        fun scan(uri: Uri, addrCol: String) {
            runCatching {
                ctx.contentResolver.query(
                    uri,
                    arrayOf(addrCol, ContactsContract.Data.PHOTO_THUMBNAIL_URI,
                            ContactsContract.Data.PHOTO_URI,
                            ContactsContract.Data.DISPLAY_NAME_PRIMARY),
                    null, null, null,
                )?.use { c ->
                    val ai = c.getColumnIndex(addrCol)
                    val ti = c.getColumnIndex(ContactsContract.Data.PHOTO_THUMBNAIL_URI)
                    val pi = c.getColumnIndex(ContactsContract.Data.PHOTO_URI)
                    val ni = c.getColumnIndex(ContactsContract.Data.DISPLAY_NAME_PRIMARY)
                    while (c.moveToNext()) {
                        val photo = c.getString(ti) ?: c.getString(pi) ?: continue
                        val uriPhoto = Uri.parse(photo)
                        key(c.getString(ai))?.let { map.putIfAbsent(it, uriPhoto) }
                        // Name index: rescues handles the phone doesn't know —
                        // e.g. someone whose iMessage rides their iCloud email
                        // while the phone contact only lists their number.
                        c.getString(ni)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                            ?.let { names.putIfAbsent(it, uriPhoto) }
                    }
                }
            }
        }

        scan(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
             ContactsContract.CommonDataKinds.Phone.NUMBER)
        scan(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
             ContactsContract.CommonDataKinds.Email.ADDRESS)

        byKey = map
        byName = names
        version++
        android.util.Log.d("Imsg", "contact photos loaded: ${map.size} by address, ${names.size} by name")
    }

    /** Re-read after the permission is granted. */
    fun reload(ctx: Context) {
        loaded = false
        load(ctx)
    }

    fun photo(addr: String?): Uri? = key(addr)?.let { byKey[it] }

    fun photoByName(name: String?): Uri? =
        name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { byName[it] }

    /** Photos for a chat's participants, in order, skipping those without one. */
    fun photos(handles: List<String>?, limit: Int = 4): List<Uri> =
        handles.orEmpty().mapNotNull { photo(it) }.distinct().take(limit)

    /** Address match first (exact), then the contact's name as a fallback. */
    fun photosFor(handles: List<String>?, name: String?, limit: Int = 4): List<Uri> {
        val byAddr = photos(handles, limit)
        if (byAddr.isNotEmpty()) return byAddr
        return listOfNotNull(photoByName(name))
    }

    fun isEmpty() = byKey.isEmpty()
}
