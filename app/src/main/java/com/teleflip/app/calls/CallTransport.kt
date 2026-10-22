package com.teleflip.app.calls

import org.drinkless.tdlib.TdApi

/** TDLib owns the authenticated key exchange; this interface owns encrypted media. */
interface CallTransport {
    fun protocol(): TdApi.CallProtocol

    fun start(
        callId: Int,
        isOutgoing: Boolean,
        ready: TdApi.CallStateReady,
        initiallyMuted: Boolean,
        onSignaling: (ByteArray) -> Unit,
        onState: (TransportState) -> Unit,
    )

    fun receiveSignaling(
        callId: Int,
        data: ByteArray,
    )

    fun setMuted(
        callId: Int,
        muted: Boolean,
    )

    fun stop(callId: Int)
}

enum class TransportState { CONNECTING, CONNECTED, FAILED, CLOSED }
