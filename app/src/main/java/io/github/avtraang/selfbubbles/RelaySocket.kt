package io.github.avtraang.selfbubbles

// Whether the app's live connection to the relay is up, as far as the app knows.
// Plain Kotlin (RelaySocketTest); WsManager (Imsg.kt) reports to it, and the push
// handler asks it before it drops a notification as redundant (AppLock.kt).

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A push for the chat that is open on screen is dropped because the same
 * message arrives over the socket and is drawn at once. That is only true
 * while there is a socket: after the relay restarted, or closed a connection
 * that had stopped taking data, the app has none for a few seconds, and a push
 * dropped in that time showed nowhere (audit R3-F4).
 *
 * Live means: at least one socket of this process is open. Sockets are told
 * apart by identity, not by a number: every screen has a connection manager
 * of its own ([SocketOwner]), each counted its attempts from one, and when the
 * screen was recreated the old manager's "mine is gone" took the new
 * manager's open socket for its own (review of 2026-10-08).
 */
class SocketLiveness {
    private val open: MutableSet<Any> = Collections.newSetFromMap(ConcurrentHashMap())

    val live: Boolean get() = open.isNotEmpty()

    internal fun opened(socket: Any) {
        open.add(socket)
    }

    internal fun ended(socket: Any) {
        open.remove(socket)
    }

    /** The handle one connection manager reports through. */
    fun owner(): SocketOwner = SocketOwner(this)
}

/**
 * One connection manager's side. It can only ever speak of its own sockets:
 * nothing it does takes another manager's open socket down.
 */
class SocketOwner internal constructor(private val liveness: SocketLiveness) {
    private var current: Any? = null

    /** A new attempt starts: whatever socket this owner had is being dropped. Returns the attempt's handle. */
    @Synchronized
    fun attempt(): Any {
        current?.let(liveness::ended)
        return Any().also { current = it }
    }

    /** The socket of [attempt] is open. One that was replaced before it opened does not count. */
    @Synchronized
    fun opened(attempt: Any) {
        if (attempt === current) liveness.opened(attempt)
    }

    /** The socket of [attempt] is closing, closed or failed. */
    fun ended(attempt: Any) = liveness.ended(attempt)

    /** The manager stops: its socket is being dropped. */
    @Synchronized
    fun stop() {
        current?.let(liveness::ended)
        current = null
    }
}

/** The live connections of this process. */
val relaySocket = SocketLiveness()
