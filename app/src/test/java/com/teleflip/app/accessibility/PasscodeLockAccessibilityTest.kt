package com.teleflip.app.accessibility

import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.MainActivity
import com.teleflip.app.R
import com.teleflip.app.security.PasscodeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PasscodeLockAccessibilityTest {
    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PasscodeManager.setPasscode(context, "1234")
        PasscodeManager.lock()
    }

    @Test
    fun testPasscodeDotsContentDescriptionAndLiveRegion() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val tvDots = activity.findViewById<TextView>(R.id.tv_passcode_dots)
            val tvError = activity.findViewById<TextView>(R.id.tv_passcode_error)

            assertNotNull(tvDots)
            assertNotNull(tvError)

            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, tvDots.accessibilityLiveRegion)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE, tvError.accessibilityLiveRegion)

            assertEquals("0 of 4 digits entered", tvDots.contentDescription)

            // Key in '1'
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1))
            assertEquals("1 of 4 digits entered", tvDots.contentDescription)

            // Key in '2'
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2))
            assertEquals("2 of 4 digits entered", tvDots.contentDescription)

            // Key in DEL
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            assertEquals("1 of 4 digits entered", tvDots.contentDescription)

            // Clear again
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            assertEquals("0 of 4 digits entered", tvDots.contentDescription)
        }
        scenario.close()
    }

    @Test
    fun testIncorrectPasscodeErrorAnnouncementAndReset() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val tvDots = activity.findViewById<TextView>(R.id.tv_passcode_dots)
            val tvError = activity.findViewById<TextView>(R.id.tv_passcode_error)

            // Enter wrong PIN "9999"
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_9))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_9))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_9))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_9))

            // Unlocking fails: tvError should display "Incorrect PIN. Try again."
            assertEquals("Incorrect PIN. Try again.", tvError.text.toString())

            // Passcode entry is cleared, so tvDots content description resets to "0 of 4 digits entered"
            assertEquals("0 of 4 digits entered", tvDots.contentDescription)
        }
        scenario.close()
    }

    @Test
    fun testIncompletePasscodeCenterKeyConsumesEventAndDoesNotTriggerLogout() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val tvDots = activity.findViewById<TextView>(R.id.tv_passcode_dots)
            assertNotNull("Passcode lock screen dots view must exist", tvDots)

            // D-Pad center key with 0 digits entered
            val centerEventZero = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            val consumedZero = activity.dispatchKeyEvent(centerEventZero)
            assertEquals(true, consumedZero)
            assertEquals("0 of 4 digits entered", tvDots.contentDescription)

            // Key in 2 digits ('1', '2')
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2))
            assertEquals("2 of 4 digits entered", tvDots.contentDescription)

            // D-Pad center key with 2 digits entered (incomplete)
            val centerEventIncomplete = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            val consumedIncomplete = activity.dispatchKeyEvent(centerEventIncomplete)
            assertEquals(true, consumedIncomplete)

            // Passcode screen should remain active and digits preserved
            val tvDotsAfter = activity.findViewById<TextView>(R.id.tv_passcode_dots)
            assertNotNull("Passcode lock screen must remain active", tvDotsAfter)
            assertEquals("2 of 4 digits entered", tvDotsAfter.contentDescription)

            // Also test KEYCODE_ENTER as center key
            val enterEventIncomplete = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
            val consumedEnter = activity.dispatchKeyEvent(enterEventIncomplete)
            assertEquals(true, consumedEnter)
            assertEquals("2 of 4 digits entered", tvDotsAfter.contentDescription)
        }
        scenario.close()
    }
}
