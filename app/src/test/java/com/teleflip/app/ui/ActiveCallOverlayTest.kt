package com.teleflip.app.ui

import android.view.KeyEvent
import android.widget.TextView
import com.teleflip.app.model.ActiveCall
import com.teleflip.app.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class ActiveCallOverlayTest {
    private lateinit var tvLeft: TextView
    private lateinit var tvCenter: TextView
    private lateinit var tvRight: TextView
    private lateinit var softkeyManager: SoftkeyManager

    @Before
    fun setUp() {
        tvLeft = mock(TextView::class.java)
        tvCenter = mock(TextView::class.java)
        tvRight = mock(TextView::class.java)
        softkeyManager = SoftkeyManager(tvLeft, tvCenter, tvRight)
    }

    @Test
    fun testActiveCallModelCreationAndMuteToggle() {
        val call =
            ActiveCall(
                callId = 1001,
                userId = 42L,
                userName = "Alice Smith",
                isOutgoing = true,
                state = CallState.READY,
                durationSec = 45,
                isMuted = false,
                isSpeakerOn = false,
            )

        assertEquals(1001, call.callId)
        assertEquals(42L, call.userId)
        assertEquals("Alice Smith", call.userName)
        assertTrue(call.isOutgoing)
        assertEquals(CallState.READY, call.state)
        assertEquals(45, call.durationSec)
        assertFalse(call.isMuted)

        val mutedCall = call.copy(isMuted = true)
        assertTrue(mutedCall.isMuted)
    }

    @Test
    fun testInCallSoftkeysActiveCall() {
        var minimizeClicked = false
        var muteClicked = false
        var endCallClicked = false

        softkeyManager.update(
            left = "Minimize",
            center = "Mute",
            right = "End Call",
            onLeft = { minimizeClicked = true },
            onCenter = { muteClicked = true },
            onRight = { endCallClicked = true },
        )

        verify(tvLeft).text = "Minimize"
        verify(tvCenter).text = "Mute"
        verify(tvRight).text = "End Call"

        // Test LSK click (Minimize)
        val lskEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT)
        val lskHandled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_SOFT_LEFT, lskEvent)
        assertTrue(lskHandled)
        assertTrue(minimizeClicked)

        // Test Center click (Mute)
        val centerEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        val centerHandled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER, centerEvent)
        assertTrue(centerHandled)
        assertTrue(muteClicked)

        // Test RSK click (End Call)
        val rskEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val rskHandled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_SOFT_RIGHT, rskEvent)
        assertTrue(rskHandled)
        assertTrue(endCallClicked)
    }

    @Test
    fun testMinimizedOverlaySoftkeys() {
        var returnToCallClicked = false
        var unmuteClicked = false
        var endCallClicked = false

        softkeyManager.update(
            left = "Return to Call",
            center = "Unmute",
            right = "End Call",
            onLeft = { returnToCallClicked = true },
            onCenter = { unmuteClicked = true },
            onRight = { endCallClicked = true },
        )

        verify(tvLeft).text = "Return to Call"
        verify(tvCenter).text = "Unmute"
        verify(tvRight).text = "End Call"

        val lskDescCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(tvLeft).contentDescription = lskDescCaptor.capture()
        assertEquals("Return to Call softkey", lskDescCaptor.value.toString())

        // Test Return to Call action
        val lskEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT)
        val lskHandled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_SOFT_LEFT, lskEvent)
        assertTrue(lskHandled)
        assertTrue(returnToCallClicked)

        // Test Unmute action
        val centerEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        val centerHandled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER, centerEvent)
        assertTrue(centerHandled)
        assertTrue(unmuteClicked)

        // Test End Call action
        val rskEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val rskHandled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_SOFT_RIGHT, rskEvent)
        assertTrue(rskHandled)
        assertTrue(endCallClicked)
    }

    @Test
    fun testHardwareCallKeyTriggersCenterOrReturnToCallAction() {
        var returnToCallTriggered = false
        softkeyManager.update(
            left = "Return to Call",
            center = "Mute",
            right = "End Call",
            onCenter = { returnToCallTriggered = true },
        )

        val callKeyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CALL)
        val handled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_CALL, callKeyEvent)
        assertTrue("KEYCODE_CALL should be handled by SoftkeyManager", handled)
        assertTrue("KEYCODE_CALL should trigger Center/Call action", returnToCallTriggered)
    }
}
