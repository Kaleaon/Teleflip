package com.teleflip.app.calls

import android.content.Context
import io.github.pytgcalls.ConnectionKind
import io.github.pytgcalls.ConnectionState
import io.github.pytgcalls.NTgCalls
import io.github.pytgcalls.media.AudioDescription
import io.github.pytgcalls.media.MediaDescription
import io.github.pytgcalls.media.MediaSource
import io.github.pytgcalls.media.StreamMode
import io.github.pytgcalls.p2p.RTCServer
import org.drinkless.tdlib.TdApi
import org.webrtc.ContextUtils

class NtgCallTransport(context: Context) : CallTransport {
    private val engine: NTgCalls

    @Volatile private var callId: Int? = null

    @Volatile private var signaling: ((ByteArray) -> Unit)? = null

    @Volatile private var state: ((TransportState) -> Unit)? = null

    init {
        ContextUtils.initialize(context.applicationContext)
        engine = NTgCalls()
        engine.onSignalingData { id, data ->
            if (id == callId?.toLong()) signaling?.invoke(data)
        }
        engine.onConnectionChange { id, info ->
            if (id == callId?.toLong() && info.kind == ConnectionKind.NORMAL) {
                state?.invoke(
                    when (info.state) {
                        ConnectionState.CONNECTED -> TransportState.CONNECTED
                        ConnectionState.CONNECTING -> TransportState.CONNECTING
                        ConnectionState.CLOSED -> TransportState.CLOSED
                        else -> TransportState.FAILED
                    },
                )
            }
        }
    }

    override fun protocol(): TdApi.CallProtocol {
        val protocol = NTgCalls.getProtocol()
        check(protocol.library_versions.isNotEmpty()) { "Call engine has no supported protocol versions" }
        return TdApi.CallProtocol(protocol.udp_p2p, protocol.udp_reflector, protocol.min_layer, protocol.max_layer, protocol.library_versions.toTypedArray())
    }

    override fun start(
        callId: Int,
        isOutgoing: Boolean,
        ready: TdApi.CallStateReady,
        initiallyMuted: Boolean,
        onSignaling: (ByteArray) -> Unit,
        onState: (TransportState) -> Unit,
    ) {
        require(ready.encryptionKey.size == 256) { "Invalid TDLib call encryption key" }
        check(this.callId == null) { "Another call is using audio" }
        this.callId = callId
        signaling = onSignaling
        state = onState
        try {
            engine.createP2pCall(callId.toLong())
            engine.skipExchange(callId.toLong(), ready.encryptionKey, isOutgoing)
            val devices = NTgCalls.getMediaDevices()
            val microphone = devices.microphone.firstOrNull() ?: error("No microphone is available")
            val speaker = devices.speaker.firstOrNull() ?: error("No audio output is available")
            val capture = AudioDescription(MediaSource.DEVICE, 48000, 1, microphone.metadata, false)
            val playback = AudioDescription(MediaSource.DEVICE, 48000, 1, speaker.metadata, false)
            engine.setStreamSources(callId.toLong(), StreamMode.CAPTURE, MediaDescription(capture, null, null, null))
            if (initiallyMuted) engine.mute(callId.toLong())
            engine.connectP2p(
                callId.toLong(),
                ready.servers.map { rtcServer(it) },
                ready.protocol.libraryVersions.toList(),
                ready.allowP2p,
                ready.customParameters?.takeIf { it.isNotBlank() } ?: "{}",
            )
            // Incoming remote microphone frames occupy the microphone slot in PLAYBACK.
            engine.setStreamSources(callId.toLong(), StreamMode.PLAYBACK, MediaDescription(playback, null, null, null))
        } catch (error: Throwable) {
            runCatching { stop(callId) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    override fun receiveSignaling(
        callId: Int,
        data: ByteArray,
    ) {
        if (this.callId == callId) engine.sendSignalingData(callId.toLong(), data)
    }

    override fun setMuted(
        callId: Int,
        muted: Boolean,
    ) {
        if (this.callId == callId) {
            if (muted) engine.mute(callId.toLong()) else engine.unmute(callId.toLong())
        }
    }

    override fun stop(callId: Int) {
        if (this.callId != callId) return
        this.callId = null
        signaling = null
        state = null
        engine.stop(callId.toLong())
    }

    companion object {
        internal fun rtcServer(server: TdApi.CallServer): RTCServer =
            when (val type = server.type) {
                is TdApi.CallServerTypeWebrtc -> RTCServer(server.id, server.ipAddress, server.ipv6Address, server.port, type.username, type.password, type.supportsTurn, type.supportsStun, false, null)
                is TdApi.CallServerTypeTelegramReflector -> RTCServer(server.id, server.ipAddress, server.ipv6Address, server.port, null, null, true, false, type.isTcp, type.peerTag)
                else -> error("Unsupported Telegram call server")
            }
    }
}
