package com.teleflip.app.calls

import org.drinkless.tdlib.TdApi

/** All methods are called on the manager's single call dispatcher. */
class CallTransportController(
    private val transport: CallTransport,
    private val onSignaling: (Int, ByteArray) -> Unit,
    private val onState: (Int, TransportState) -> Unit,
) {
    @Volatile private var selectedId: Int? = null
    private var started = false

    @Volatile private var generation = 0L
    private var muted = false
    private var pendingBytes = 0
    private val pending = ArrayDeque<ByteArray>()

    fun protocol(): TdApi.CallProtocol = transport.protocol()

    fun select(callId: Int) {
        check(selectedId == null || selectedId == callId) { "Another call is already active" }
        selectedId = callId
    }

    fun start(
        callId: Int,
        outgoing: Boolean,
        ready: TdApi.CallStateReady,
    ) {
        select(callId)
        if (started) return
        require(ready.encryptionKey?.size == 256) { "Invalid TDLib call encryption key" }
        val supported = protocol().libraryVersions.toSet()
        require(ready.protocol.libraryVersions.any { it in supported }) { "No compatible call audio protocol" }
        require(!ready.servers.isNullOrEmpty()) { "No Telegram call relay is available" }
        val currentGeneration = ++generation
        started = true
        try {
            transport.start(
                callId,
                outgoing,
                ready,
                muted,
                { data -> if (generation == currentGeneration && selectedId == callId) onSignaling(callId, data) },
                { state -> if (generation == currentGeneration && selectedId == callId) onState(callId, state) },
            )
            transport.setMuted(callId, muted)
            while (pending.isNotEmpty()) transport.receiveSignaling(callId, pending.removeFirst())
            pendingBytes = 0
        } catch (error: Throwable) {
            runCatching { stop(callId) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    fun receiveSignaling(
        callId: Int,
        data: ByteArray,
    ) {
        if (selectedId != callId) return
        require(data.size <= MAX_SIGNAL_BYTES) { "Call signaling packet is too large" }
        if (started) {
            transport.receiveSignaling(callId, data)
        } else {
            require(pending.size < MAX_PENDING_PACKETS && pendingBytes + data.size <= MAX_PENDING_BYTES) { "Too much pending call signaling" }
            pending.addLast(data.copyOf())
            pendingBytes += data.size
        }
    }

    fun setMuted(
        callId: Int,
        muted: Boolean,
    ) {
        if (selectedId != callId) return
        this.muted = muted
        if (started) transport.setMuted(callId, muted)
    }

    fun stop(callId: Int) {
        if (selectedId != callId) return
        val wasStarted = started
        selectedId = null
        started = false
        muted = false
        generation++
        pending.clear()
        pendingBytes = 0
        if (wasStarted) transport.stop(callId)
    }

    companion object {
        private const val MAX_SIGNAL_BYTES = 256 * 1024
        private const val MAX_PENDING_BYTES = 2 * 1024 * 1024
        private const val MAX_PENDING_PACKETS = 128
    }
}
