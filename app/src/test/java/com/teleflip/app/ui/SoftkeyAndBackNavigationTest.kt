package com.teleflip.app.ui

import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class SoftkeyAndBackNavigationTest {
    private lateinit var mockSoftkeyBar: View
    private lateinit var mockTvLeft: TextView
    private lateinit var mockTvCenter: TextView
    private lateinit var mockTvRight: TextView
    private lateinit var softkeyManager: SoftkeyManager

    @Before
    fun setUp() {
        mockSoftkeyBar = mock(View::class.java)
        mockTvLeft = mock(TextView::class.java)
        mockTvCenter = mock(TextView::class.java)
        mockTvRight = mock(TextView::class.java)

        softkeyManager =
            SoftkeyManager(
                softkeyBar = mockSoftkeyBar,
                tvLeft = mockTvLeft,
                tvCenter = mockTvCenter,
                tvRight = mockTvRight,
            )
    }

    @Test
    fun testSoftkeyBarVisibilityToggle() {
        softkeyManager.setSoftkeyBarVisible(false)
        verify(mockSoftkeyBar).visibility = View.GONE

        softkeyManager.setSoftkeyBarVisible(true)
        verify(mockSoftkeyBar).visibility = View.VISIBLE
    }

    @Test
    fun testRightSoftkeyCodesTriggerRightAction() {
        var rightTriggered = false
        softkeyManager.update(
            right = "Exit",
            onRight = { rightTriggered = true },
        )

        val rskCodes =
            listOf(
                KeyEvent.KEYCODE_SOFT_RIGHT,
                229,
                230,
                61,
                158,
            )

        for (code in rskCodes) {
            val event = KeyEvent(KeyEvent.ACTION_DOWN, code)
            val handled = softkeyManager.handleKeyEvent(code, event)
            assertTrue("Keycode $code should be handled as Right Softkey", handled)
            assertTrue("Right action should be triggered for keycode $code", rightTriggered)
            rightTriggered = false
        }
    }

    @Test
    fun testBackKeyCodeDoesNotTriggerSoftkeyOrRightAction() {
        var rightTriggered = false
        softkeyManager.update(
            right = "Exit",
            onRight = { rightTriggered = true },
        )

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_BACK, event)
        assertFalse("KEYCODE_BACK should NOT be handled by SoftkeyManager", handled)
        assertFalse("KEYCODE_BACK should NOT trigger Right Softkey callback", rightTriggered)

        @Suppress("DEPRECATION")
        val backHandled = softkeyManager.handleBack()
        assertFalse("handleBack() should return false and NOT trigger softkey callback", backHandled)
        assertFalse("handleBack() should NOT trigger Right Softkey action", rightTriggered)
    }

    @Test
    fun testLeftSoftkeyCodesTriggerLeftAction() {
        var leftTriggered = false
        softkeyManager.update(
            left = "Options",
            onLeft = { leftTriggered = true },
        )

        val lskCodes =
            listOf(
                KeyEvent.KEYCODE_SOFT_LEFT,
                KeyEvent.KEYCODE_MENU,
                132,
                60,
                228,
            )

        for (code in lskCodes) {
            val event = KeyEvent(KeyEvent.ACTION_DOWN, code)
            val handled = softkeyManager.handleKeyEvent(code, event)
            assertTrue("Keycode $code should be handled as Left Softkey", handled)
            assertTrue("Left action should be triggered for keycode $code", leftTriggered)
            leftTriggered = false
        }
    }

    @Test
    fun testCenterSoftkeyCodesTriggerCenterAction() {
        var centerTriggered = false
        softkeyManager.update(
            center = "Open",
            onCenter = { centerTriggered = true },
        )

        val centerCodes =
            listOf(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_CALL,
            )

        for (code in centerCodes) {
            val event = KeyEvent(KeyEvent.ACTION_DOWN, code)
            val handled = softkeyManager.handleKeyEvent(code, event)
            assertTrue("Keycode $code should be handled as Center Softkey", handled)
            assertTrue("Center action should be triggered for keycode $code", centerTriggered)
            centerTriggered = false
        }
    }

    @Test
    fun testIsBackOrSoftRightKeyEvaluation() {
        val validCodes =
            listOf(
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_SOFT_RIGHT,
                229,
                230,
                61,
                158,
            )
        for (code in validCodes) {
            assertTrue(
                "Keycode $code should return true for isBackOrSoftRightKey",
                SoftkeyUtils.isBackOrSoftRightKey(code),
            )
        }

        val invalidCodes =
            listOf(
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_SOFT_LEFT,
                KeyEvent.KEYCODE_MENU,
                132,
                60,
                228,
                KeyEvent.KEYCODE_DPAD_DOWN,
            )
        for (code in invalidCodes) {
            assertFalse(
                "Keycode $code should return false for isBackOrSoftRightKey",
                SoftkeyUtils.isBackOrSoftRightKey(code),
            )
        }
    }
}
