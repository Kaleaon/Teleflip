package com.teleflip.app.input

import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.MainActivity
import com.teleflip.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettingsKeypadRegistryTest {
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().visible().get()
    }

    @Test
    fun testViewSettingsLayoutContainsInputModeIndicator() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.setTheme(R.style.Theme_TeleFlip)
        val view = LayoutInflater.from(context).inflate(R.layout.view_settings, null, false)

        val tvMode = view.findViewById<TextView>(R.id.tv_settings_input_mode)
        assertNotNull("tv_settings_input_mode should exist in view_settings.xml", tvMode)
        val etId = view.findViewById<EditText>(R.id.et_api_id)
        assertNotNull("et_api_id should exist in view_settings.xml", etId)
        val etHash = view.findViewById<EditText>(R.id.et_api_hash)
        assertNotNull("et_api_hash should exist in view_settings.xml", etHash)
    }

    @Test
    fun testSettingsKeypadEnginesAndKeyEventRouting() {
        val showSettingsMethod = MainActivity::class.java.getDeclaredMethod("showSettingsScreen")
        showSettingsMethod.isAccessible = true
        showSettingsMethod.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()

        val etId = activity.findViewById<EditText>(R.id.et_api_id)
        val etHash = activity.findViewById<EditText>(R.id.et_api_hash)
        val tvMode = activity.findViewById<TextView>(R.id.tv_settings_input_mode)

        assertNotNull("et_api_id should be present on Settings screen", etId)
        assertNotNull("et_api_hash should be present on Settings screen", etHash)
        assertNotNull("tv_settings_input_mode should be present on Settings screen", tvMode)

        // Focus et_api_id (Numeric mode)
        etId.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("[#] 123", tvMode.text.toString())

        // Dispatch key '2' to et_api_id
        val down2 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        val up2 = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_2)
        val handledDown1 = activity.dispatchKeyEvent(down2)
        activity.dispatchKeyEvent(up2)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("Key event on et_api_id should be handled", handledDown1)

        // Focus et_api_hash (Lower mode)
        etHash.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("[#] abc", tvMode.text.toString())

        // Dispatch key '2' to et_api_hash (multi-tap 'a')
        val handledDown2 = activity.dispatchKeyEvent(down2)
        activity.dispatchKeyEvent(up2)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("Key event on et_api_hash should be handled", handledDown2)

        // Trigger commitActiveInputEngines via reflection
        val commitMethod = MainActivity::class.java.getDeclaredMethod("commitActiveInputEngines")
        commitMethod.isAccessible = true
        commitMethod.invoke(activity)

        assertEquals("a", etHash.text.toString())
    }

    @Test
    fun testModeSwitchingAndSymbolInsertionOnSettingsFields() {
        val showSettingsMethod = MainActivity::class.java.getDeclaredMethod("showSettingsScreen")
        showSettingsMethod.isAccessible = true
        showSettingsMethod.invoke(activity)
        shadowOf(Looper.getMainLooper()).idle()

        val etHash = activity.findViewById<EditText>(R.id.et_api_hash)
        val tvMode = activity.findViewById<TextView>(R.id.tv_settings_input_mode)
        etHash.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()

        // Toggle mode with POUND (#)
        val downPound = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POUND)
        val upPound = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POUND)
        activity.dispatchKeyEvent(downPound)
        activity.dispatchKeyEvent(upPound)
        shadowOf(Looper.getMainLooper()).idle()

        // Mode toggled from LOWER (abc) to UPPER (ABC)
        assertEquals("[#] ABC", tvMode.text.toString())
    }
}
