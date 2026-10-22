package com.teleflip.app.telegram

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.calls.CallAudioSession
import com.teleflip.app.calls.CallTransport
import com.teleflip.app.calls.TransportState
import com.teleflip.app.model.CallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.isA
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TelegramCallTransportTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var manager: TelegramManager
    private lateinit var client: Client
    private lateinit var audio: CallAudioSession
    private val transport = TestTransport()

    private class TestTransport : CallTransport {
        var starts = 0
        var stops = 0
        var muted = false
        var connection: ((TransportState) -> Unit)? = null
        var outgoingSignal: ((ByteArray) -> Unit)? = null
        val received = mutableListOf<ByteArray>()

        override fun protocol() = TdApi.CallProtocol(true, true, 92, 220, arrayOf("supported"))

        override fun start(
            callId: Int,
            isOutgoing: Boolean,
            ready: TdApi.CallStateReady,
            initiallyMuted: Boolean,
            onSignaling: (ByteArray) -> Unit,
            onState: (TransportState) -> Unit,
        ) {
            starts++
            connection = onState
            outgoingSignal = onSignaling
        }

        override fun receiveSignaling(
            callId: Int,
            data: ByteArray,
        ) {
            received.add(data)
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

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        audio = mock(CallAudioSession::class.java)
        client = mock(Client::class.java)
        manager = TelegramManager(context, { transport }, { audio }, dispatcher)
        manager.setTdClientForTesting(client)
    }

    @After fun tearDown() {
        manager.activeCall.value?.let { manager.handleCallUpdate(call(TdApi.CallStateDiscarded())) }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun call(state: TdApi.CallState) =
        TdApi.Call().apply {
            id = 7
            userId = 10
            isOutgoing = true
            this.state = state
        }

    private fun ready() =
        TdApi.CallStateReady().apply {
            encryptionKey = ByteArray(256)
            protocol = transport.protocol()
            servers = arrayOf(TdApi.CallServer())
            emojis = arrayOf("🔑", "☎️", "⭐", "🌻")
        }

    @Test fun readyStartsAudioButOnlyConnectedStartsCallTimer() {
        manager.handleCallUpdate(call(ready()))
        dispatcher.scheduler.runCurrent()
        assertEquals(CallState.CONNECTING, manager.activeCall.value?.state)
        assertEquals(0, manager.activeCall.value?.durationSec)
        assertEquals(1, transport.starts)
        manager.handleCallUpdate(call(ready()))
        dispatcher.scheduler.runCurrent()
        assertEquals(1, transport.starts)
        transport.connection!!(TransportState.CONNECTED)
        dispatcher.scheduler.runCurrent()
        assertEquals(CallState.READY, manager.activeCall.value?.state)
        assertEquals(4, manager.activeCall.value?.verificationEmojis?.size)
        manager.updateCallAudioState(isMuted = true, isSpeakerOn = true)
        dispatcher.scheduler.runCurrent()
        assertTrue(transport.muted)
        verify(audio).speaker(true)
    }

    @Test fun signalingTravelsBothDirectionsAndHangupStopsTransport() {
        manager.onResult(TdApi.UpdateCall(call(TdApi.CallStatePending())))
        manager.onResult(TdApi.UpdateNewCallSignalingData(7, byteArrayOf(1, 2)))
        dispatcher.scheduler.runCurrent()
        assertTrue(transport.received.isEmpty())
        manager.handleCallUpdate(call(ready()))
        dispatcher.scheduler.runCurrent()
        assertArrayEquals(byteArrayOf(1, 2), transport.received.single())
        transport.outgoingSignal!!(byteArrayOf(3))
        verify(client).send(isA(TdApi.SendCallSignalingData::class.java), any())
        manager.handleCallUpdate(call(TdApi.CallStateDiscarded()))
        dispatcher.scheduler.runCurrent()
        assertEquals(1, transport.stops)
        verify(audio).stop()
        assertEquals(CallState.DISCARDED, manager.activeCall.value?.state)
    }

    @Test fun lateReadyUpdateCannotRestartHungUpCall() {
        manager.handleCallUpdate(call(ready()))
        dispatcher.scheduler.runCurrent()
        manager.handleCallUpdate(call(TdApi.CallStateDiscarded()))
        dispatcher.scheduler.runCurrent()
        manager.handleCallUpdate(call(ready()))
        dispatcher.scheduler.runCurrent()
        assertEquals(1, transport.starts)
        assertEquals(CallState.DISCARDED, manager.activeCall.value?.state)
    }

    @Test fun repeatedAcceptAndHangupKeysSendEachRequestOnce() {
        manager.handleCallUpdate(call(TdApi.CallStatePending()).apply { isOutgoing = false })
        manager.acceptCall(7)
        manager.acceptCall(7)
        verify(client).send(isA(TdApi.AcceptCall::class.java), any())
        manager.discardCall(7)
        manager.discardCall(7)
        verify(client).send(isA(TdApi.DiscardCall::class.java), any())
        dispatcher.scheduler.runCurrent()
        assertEquals(CallState.HANGING_UP, manager.activeCall.value?.state)
    }

    @Test fun missingMicrophonePermissionNeverCreatesCall() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        var success: Boolean? = null
        manager.initiateTelegramCall(10) { ok, _ -> success = ok }
        assertEquals(false, success)
        verifyNoInteractions(client)
        assertTrue(mockingDetails(audio).invocations.none { it.method.name == "start" })
    }
}
