package com.teleflip.app.ui

import android.view.KeyEvent
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class SoftkeyManagerTest {
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
    fun testUpdateSetsTextAndContentDescription() {
        softkeyManager.update(
            left = "Options",
            center = "Open",
            right = "Back",
        )

        verify(tvLeft).text = "Options"
        verify(tvCenter).text = "Open"
        verify(tvRight).text = "Back"

        val leftDescCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(tvLeft).contentDescription = leftDescCaptor.capture()
        assertEquals("Options softkey", leftDescCaptor.value.toString())

        val centerDescCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(tvCenter).contentDescription = centerDescCaptor.capture()
        assertEquals("Open softkey", centerDescCaptor.value.toString())

        val rightDescCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(tvRight).contentDescription = rightDescCaptor.capture()
        assertEquals("Back softkey", rightDescCaptor.value.toString())
    }

    @Test
    fun testUpdateEmptyLabelsSetsEmptyContentDescription() {
        softkeyManager.update(
            left = "",
            center = "",
            right = "",
        )

        val leftDescCaptor = ArgumentCaptor.forClass(CharSequence::class.java)
        verify(tvLeft).contentDescription = leftDescCaptor.capture()
        assertEquals("", leftDescCaptor.value.toString())
    }

    @Test
    fun testHandleKeyEventSoftLeft() {
        var leftClicked = false
        softkeyManager.update(
            left = "Options",
            onLeft = { leftClicked = true },
        )

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT)
        val handled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_SOFT_LEFT, event)

        assertTrue(handled)
        assertTrue(leftClicked)
    }

    @Test
    fun testHandleKeyEventSoftRight() {
        var rightClicked = false
        softkeyManager.update(
            right = "Back",
            onRight = { rightClicked = true },
        )

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val handled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_SOFT_RIGHT, event)

        assertTrue(handled)
        assertTrue(rightClicked)
    }

    @Test
    fun testHandleKeyEventCenter() {
        var centerClicked = false
        softkeyManager.update(
            center = "Open",
            onCenter = { centerClicked = true },
        )

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        val handled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER, event)

        assertTrue(handled)
        assertTrue(centerClicked)
    }

    @Test
    fun testCenterLongClickInvokesCallback() {
        val captor = ArgumentCaptor.forClass(android.view.View.OnLongClickListener::class.java)
        verify(tvCenter).setOnLongClickListener(captor.capture())
        val listener = captor.value

        var longClicked = false
        softkeyManager.update(
            center = "Send",
            onCenterLongClick = { longClicked = true },
        )

        val consumed = listener.onLongClick(tvCenter)
        assertTrue(consumed)
        assertTrue(longClicked)
    }

    @Test
    fun testCenterLongClickWithoutCallbackReturnsFalse() {
        val captor = ArgumentCaptor.forClass(android.view.View.OnLongClickListener::class.java)
        verify(tvCenter).setOnLongClickListener(captor.capture())
        val listener = captor.value

        softkeyManager.update(
            center = "Send",
            onCenterLongClick = null,
        )

        val consumed = listener.onLongClick(tvCenter)
        assertFalse(consumed)
    }

    @Test
    @Suppress("DEPRECATION")
    fun testHandleBack() {
        val handled = softkeyManager.handleBack()
        assertFalse(handled)
    }
}
