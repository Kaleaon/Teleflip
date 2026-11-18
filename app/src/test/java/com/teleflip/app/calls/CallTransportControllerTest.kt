package com.teleflip.app.calls

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTransportControllerTest {
    private class FakeTransport : CallTransport {
        var starts = 0
        var stops = 0
        var muted = false
        var initiallyMuted = false
        val packets = mutableListOf<ByteArray>()
        var signal: ((ByteArray) -> Unit)? = null
        var state: ((TransportState) -> Unit)? = null

        override fun protocol() = TdApi.CallProtocol(true, true, 92, 220, arrayOf("test-protocol"))

        override fun start(
            callId: Int,
            isOutgoing: Boolean,
            ready: TdApi.CallStateReady,
            initiallyMuted: Boolean,
            onSignaling: (ByteArray) -> Unit,
            onState: (TransportState) -> Unit,
        ) {
            starts++
            this.initiallyMuted = initiallyMuted
            signal = onSignaling
            state = onState
        }

        override fun receiveSignaling(
            callId: Int,
            data: ByteArray,
        ) {
            packets.add(data)
        }

        override fun setMuted(
            callId: Int,
            muted: Boolean,
        ) {
            this.muted = muted
        }

        override fun stop(callId: Int) {
            stops++
        }
    }

    private fun ready() =
        TdApi.CallStateReady().apply {
            encryptionKey = ByteArray(256) { it.toByte() }
            protocol = TdApi.CallProtocol(true, true, 92, 220, arrayOf("test-protocol"))
            servers = arrayOf(TdApi.CallServer())
        }

    @Test fun earlySignalingIsCopiedAndDrainedOnce() {
        val transport = FakeTransport()
        val controller = CallTransportController(transport, { _, _ -> }, { _, _ -> })
        controller.select(7)
        val packet = byteArrayOf(1, 2)
        controller.receiveSignaling(7, packet)
        packet[0] = 9
        controller.setMuted(7, true)
        controller.start(7, true, ready())
        controller.start(7, true, ready())
        assertEquals(1, transport.starts)
        assertTrue(transport.muted)
        assertTrue(transport.initiallyMuted)
        assertArrayEquals(byteArrayOf(1, 2), transport.packets.single())
    }

    @Test fun staleCallbacksCannotAffectAnotherCall() {
        val transport = FakeTransport()
        val states = mutableListOf<Int>()
        val signals = mutableListOf<Int>()
        val controller = CallTransportController(transport, { id, _ -> signals.add(id) }, { id, _ -> states.add(id) })
        controller.start(7, true, ready())
        val oldSignal = transport.signal!!
        val oldState = transport.state!!
        controller.stop(7)
        controller.start(8, false, ready())
        oldSignal(byteArrayOf(1))
        oldState(TransportState.CONNECTED)
        transport.state!!(TransportState.CONNECTED)
        assertTrue(signals.isEmpty())
        assertEquals(listOf(8), states)
        assertEquals(1, transport.stops)
    }

    @Test fun invalidKeysAndUnsupportedProtocolsNeverReachNativeEngine() {
        val transport = FakeTransport()
        val controller = CallTransportController(transport, { _, _ -> }, { _, _ -> })
        assertThrows(IllegalArgumentException::class.java) { controller.start(7, true, ready().apply { encryptionKey = ByteArray(255) }) }
        assertThrows(IllegalArgumentException::class.java) { controller.start(7, true, ready().apply { protocol.libraryVersions = arrayOf("libtgvoip") }) }
        assertEquals(0, transport.starts)
    }

    @Test fun signalingQueueIsBoundedAndIgnoresOtherCalls() {
        val transport = FakeTransport()
        val controller = CallTransportController(transport, { _, _ -> }, { _, _ -> })
        controller.select(7)
        controller.receiveSignaling(8, ByteArray(300000))
        assertThrows(IllegalArgumentException::class.java) { controller.receiveSignaling(7, ByteArray(300000)) }
        repeat(128) { controller.receiveSignaling(7, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { controller.receiveSignaling(7, byteArrayOf(1)) }
        controller.stop(7)
        controller.start(8, true, ready())
        assertTrue(transport.packets.isEmpty())
    }

    @Test fun concurrentCallCannotReplaceSelectedCall() {
        val controller = CallTransportController(FakeTransport(), { _, _ -> }, { _, _ -> })
        controller.select(7)
        assertThrows(IllegalStateException::class.java) { controller.select(8) }
    }
}
