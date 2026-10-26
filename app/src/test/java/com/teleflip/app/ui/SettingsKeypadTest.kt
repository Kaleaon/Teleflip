package com.teleflip.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.MainActivity
import com.teleflip.app.R
import com.teleflip.app.Screen
import com.teleflip.app.security.PasscodeManager
import com.teleflip.app.telegram.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27], qualifiers = "w320dp-h533dp-hdpi", application = Application::class)
class SettingsKeypadTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PasscodeManager.disablePasscode(context)
    }

    private fun createDownEvent(keyCode: Int): KeyEvent {
        return KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0)
    }

    private fun createUpEvent(keyCode: Int): KeyEvent {
        return KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0)
    }

    private fun dispatchKey(
        activity: MainActivity,
        keyCode: Int,
    ) {
        activity.dispatchKeyEvent(createDownEvent(keyCode))
        activity.dispatchKeyEvent(createUpEvent(keyCode))
    }

    @Test
    fun testSettingsCredentialFieldsHandleKeypadMultiTapAndCommitOnFocusChange() {
        val intent = Intent(context, MainActivity::class.java)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        activity.telegramManager.setAuthStateForTesting(AuthState.Ready)

        // Press '0' from Chat List screen to open Settings screen
        dispatchKey(activity, KeyEvent.KEYCODE_0)
        assertEquals(Screen.SETTINGS, activity.currentScreen)

        val etApiId = activity.findViewById<EditText>(R.id.et_api_id)
        val etApiHash = activity.findViewById<EditText>(R.id.et_api_hash)
        assertNotNull(etApiId)
        assertNotNull(etApiHash)

        // Focus et_api_hash (text field) and test multi-tap typing
        etApiHash.requestFocus()
        assertTrue(etApiHash.hasFocus())
        etApiHash.setText("")

        // Press '2' twice in rapid succession -> 'a' -> 'b'
        dispatchKey(activity, KeyEvent.KEYCODE_2)
        dispatchKey(activity, KeyEvent.KEYCODE_2)
        assertEquals("b", etApiHash.text.toString())

        // Focus et_api_id; focus loss on et_api_hash commits 'b'
        etApiId.requestFocus()
        assertTrue(etApiId.hasFocus())
        etApiId.setText("")

        // Switch et_api_id to NUMERIC mode by pressing '#' 3 times (CAP_FIRST -> LOWER -> UPPER -> NUMERIC)
        dispatchKey(activity, KeyEvent.KEYCODE_POUND)
        dispatchKey(activity, KeyEvent.KEYCODE_POUND)
        dispatchKey(activity, KeyEvent.KEYCODE_POUND)

        // Type '2', '1', '7' -> '217'
        dispatchKey(activity, KeyEvent.KEYCODE_2)
        dispatchKey(activity, KeyEvent.KEYCODE_1)
        dispatchKey(activity, KeyEvent.KEYCODE_7)
        assertEquals("217", etApiId.text.toString())

        // Verify et_api_hash retained committed text 'b'
        assertEquals("b", etApiHash.text.toString())
    }

    @Test
    fun testSettingsKeypadModeToggleAndManualCommit() {
        val intent = Intent(context, MainActivity::class.java)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        activity.telegramManager.setAuthStateForTesting(AuthState.Ready)

        dispatchKey(activity, KeyEvent.KEYCODE_0)
        assertEquals(Screen.SETTINGS, activity.currentScreen)

        val etApiHash = activity.findViewById<EditText>(R.id.et_api_hash)
        assertNotNull(etApiHash)

        etApiHash.requestFocus()
        etApiHash.setText("")

        // Press '#' (POUND) to cycle mode: CAP_FIRST -> LOWER
        dispatchKey(activity, KeyEvent.KEYCODE_POUND)

        // Type '2' -> 'a' in LOWER mode
        dispatchKey(activity, KeyEvent.KEYCODE_2)
        assertEquals("a", etApiHash.text.toString())

        // Manual commit with DPAD_RIGHT
        dispatchKey(activity, KeyEvent.KEYCODE_DPAD_RIGHT)

        // Press '2' again immediately -> appends new 'a' rather than cycling to 'b'
        dispatchKey(activity, KeyEvent.KEYCODE_2)
        assertEquals("aa", etApiHash.text.toString())
    }

    @Test
    fun testSaveFlushesPendingMultiTapCharacters() {
        val intent = Intent(context, MainActivity::class.java)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller.get()
        activity.telegramManager.setAuthStateForTesting(AuthState.Ready)

        dispatchKey(activity, KeyEvent.KEYCODE_0)
        assertEquals(Screen.SETTINGS, activity.currentScreen)

        val etApiId = activity.findViewById<EditText>(R.id.et_api_id)
        val etApiHash = activity.findViewById<EditText>(R.id.et_api_hash)
        val btnSave = activity.findViewById<Button>(R.id.btn_save_config)

        etApiId.requestFocus()
        etApiId.setText("12345")

        etApiHash.requestFocus()
        etApiHash.setText("abcd")
        etApiHash.setSelection(4)

        // Type '2' (pending multi-tap 'E' or 'e' depending on mode)
        dispatchKey(activity, KeyEvent.KEYCODE_2)

        // Click save button while multi-tap timer is active
        btnSave.performClick()

        // Verify config was saved properly with flushed character
        assertEquals(12345, activity.telegramManager.apiId)
        assertTrue(activity.telegramManager.apiHash.startsWith("abcd"))
    }
}
