package com.teleflip.app.ui

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftkeyUtilsTest {
    @Test
    fun testIsSoftLeftKey_standardKeycodes() {
        assertTrue(SoftkeyUtils.isSoftLeftKey(KeyEvent.KEYCODE_SOFT_LEFT))
        assertTrue(SoftkeyUtils.isSoftLeftKey(KeyEvent.KEYCODE_MENU))
    }

    @Test
    fun testIsSoftLeftKey_vendorKeycodes() {
        for (code in listOf(132, 60, 228)) {
            assertTrue("Expected vendor keycode $code to be recognized as Soft Left", SoftkeyUtils.isSoftLeftKey(code))
        }
    }

    @Test
    fun testIsSoftLeftKey_nonSoftLeftKeycodes() {
        assertFalse(SoftkeyUtils.isSoftLeftKey(KeyEvent.KEYCODE_SOFT_RIGHT))
        assertFalse(SoftkeyUtils.isSoftLeftKey(KeyEvent.KEYCODE_BACK))
        assertFalse(SoftkeyUtils.isSoftLeftKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(SoftkeyUtils.isSoftLeftKey(KeyEvent.KEYCODE_A))
        assertFalse(SoftkeyUtils.isSoftLeftKey(999))
    }

    @Test
    fun testIsSoftRightKey_standardKeycode() {
        assertTrue(SoftkeyUtils.isSoftRightKey(KeyEvent.KEYCODE_SOFT_RIGHT))
    }

    @Test
    fun testIsSoftRightKey_vendorKeycodes() {
        for (code in listOf(229, 230, 61, 158)) {
            assertTrue("Expected vendor keycode $code to be recognized as Soft Right", SoftkeyUtils.isSoftRightKey(code))
        }
    }

    @Test
    fun testIsSoftRightKey_nonSoftRightKeycodes() {
        assertFalse(SoftkeyUtils.isSoftRightKey(KeyEvent.KEYCODE_SOFT_LEFT))
        assertFalse(SoftkeyUtils.isSoftRightKey(KeyEvent.KEYCODE_MENU))
        assertFalse(SoftkeyUtils.isSoftRightKey(KeyEvent.KEYCODE_BACK))
        assertFalse(SoftkeyUtils.isSoftRightKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(SoftkeyUtils.isSoftRightKey(KeyEvent.KEYCODE_A))
        assertFalse(SoftkeyUtils.isSoftRightKey(999))
    }

    @Test
    fun testIsBackOrSoftRightKey_backAndSoftRight() {
        assertTrue(SoftkeyUtils.isBackOrSoftRightKey(KeyEvent.KEYCODE_BACK))
        assertTrue(SoftkeyUtils.isBackOrSoftRightKey(KeyEvent.KEYCODE_SOFT_RIGHT))
    }

    @Test
    fun testIsBackOrSoftRightKey_vendorKeycodes() {
        for (code in listOf(229, 230, 61, 158)) {
            assertTrue("Expected vendor keycode $code to be recognized as Back or Soft Right", SoftkeyUtils.isBackOrSoftRightKey(code))
        }
    }

    @Test
    fun testIsBackOrSoftRightKey_unrelatedKeycodes() {
        assertFalse(SoftkeyUtils.isBackOrSoftRightKey(KeyEvent.KEYCODE_SOFT_LEFT))
        assertFalse(SoftkeyUtils.isBackOrSoftRightKey(KeyEvent.KEYCODE_MENU))
        assertFalse(SoftkeyUtils.isBackOrSoftRightKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(SoftkeyUtils.isBackOrSoftRightKey(KeyEvent.KEYCODE_A))
        assertFalse(SoftkeyUtils.isBackOrSoftRightKey(999))
    }

    @Test
    fun testIsCenterKey_centerAndEnter() {
        assertTrue(SoftkeyUtils.isCenterKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(SoftkeyUtils.isCenterKey(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun testIsCenterKey_unrelatedKeycodes() {
        assertFalse(SoftkeyUtils.isCenterKey(KeyEvent.KEYCODE_SOFT_LEFT))
        assertFalse(SoftkeyUtils.isCenterKey(KeyEvent.KEYCODE_SOFT_RIGHT))
        assertFalse(SoftkeyUtils.isCenterKey(KeyEvent.KEYCODE_BACK))
        assertFalse(SoftkeyUtils.isCenterKey(KeyEvent.KEYCODE_A))
        assertFalse(SoftkeyUtils.isCenterKey(999))
    }
}
