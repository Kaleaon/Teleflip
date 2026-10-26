package com.teleflip.app.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaseDialogTest {

    private class TestDialogWithSoftkeys(context: Context) : BaseDialog(context) {
        var leftClicked = false
        var rightClicked = false

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.dialog_symbols)
            setupSoftkeys(
                left = "Option",
                center = "Select",
                right = "Close",
                onLeft = { leftClicked = true },
                onRight = { rightClicked = true },
            )
        }

        public fun checkIsBackOrRightSoftKey(keyCode: Int): Boolean = isBackOrRightSoftKey(keyCode)
        public fun checkIsSoftLeftKey(keyCode: Int): Boolean = isSoftLeftKey(keyCode)
        public fun checkIsSoftRightKey(keyCode: Int): Boolean = isSoftRightKey(keyCode)
        public fun checkIsCenterKey(keyCode: Int): Boolean = isCenterKey(keyCode)
    }

    private class TestDialogWithoutSoftkeys(context: Context) : BaseDialog(context) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val tv = TextView(context)
            setContentView(tv)
            setupSoftkeys(left = "Left", center = "Center", right = "Right")
        }
    }

    @Test
    fun testIsBackOrRightSoftKeyDelegatesToSoftkeyUtils() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_TeleFlip)
        val dialog = TestDialogWithSoftkeys(context)

        assertTrue(dialog.checkIsBackOrRightSoftKey(KeyEvent.KEYCODE_BACK))
        assertTrue(dialog.checkIsBackOrRightSoftKey(KeyEvent.KEYCODE_SOFT_RIGHT))

        // Vendor keycodes defined in SoftkeyUtils
        for (code in listOf(229, 230, 61, 158)) {
            assertTrue("Keycode $code should return true for isBackOrRightSoftKey", dialog.checkIsBackOrRightSoftKey(code))
        }

        // Keycodes not in back/soft right set
        assertFalse(dialog.checkIsBackOrRightSoftKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(dialog.checkIsBackOrRightSoftKey(KeyEvent.KEYCODE_A))
    }

    @Test
    fun testSetupSoftkeysBindsViewsAndHandlers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_TeleFlip)
        val dialog = TestDialogWithSoftkeys(context)
        dialog.show()

        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)

        assertNotNull(tvLeft)
        assertNotNull(tvCenter)
        assertNotNull(tvRight)

        assertEquals("Option", tvLeft.text.toString())
        assertEquals("Select", tvCenter.text.toString())
        assertEquals("Close", tvRight.text.toString())

        tvLeft.performClick()
        assertTrue(dialog.leftClicked)

        tvRight.performClick()
        assertTrue(dialog.rightClicked)

        dialog.dismiss()
    }

    @Test
    fun testSafeHandlingWhenLayoutLacksSoftkeys() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(R.style.Theme_TeleFlip)
        val dialog = TestDialogWithoutSoftkeys(context)
        dialog.show()

        // Does not throw NullPointerException
        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        assertNull(tvLeft)

        dialog.dismiss()
    }
}
