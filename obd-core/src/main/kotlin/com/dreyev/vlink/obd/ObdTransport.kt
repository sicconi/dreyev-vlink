package com.dreyev.vlink.obd

import kotlinx.coroutines.channels.Channel

/**
 * A byte pipe to an ELM327-compatible adapter (VLink over Bluetooth Classic SPP or BLE,
 * or the built-in simulator). Implementations push received bytes with [deliver].
 */
abstract class ObdTransport {
    abstract val name: String
    abstract val kind: String

    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    abstract suspend fun open()
    abstract suspend fun write(bytes: ByteArray)
    abstract suspend fun close()

    /** Suspends until bytes arrive. Throws [TransportClosedException] once closed. */
    suspend fun read(): ByteArray =
        inbox.receiveCatching().getOrNull() ?: throw TransportClosedException()

    protected fun deliver(bytes: ByteArray) {
        if (bytes.isNotEmpty()) inbox.trySend(bytes)
    }

    protected fun markClosed() {
        inbox.close()
    }

    /** Drop anything still buffered (used before each command). */
    fun drain() {
        while (inbox.tryReceive().isSuccess) { /* discard */ }
    }
}

class TransportClosedException : Exception("Adapter connection closed")
