package com.teleflip.app.ui

import android.app.Application
import android.app.Dialog
import android.view.KeyEvent
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.MainActivity
import com.teleflip.app.R
import com.teleflip.app.telegram.TelegramManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class DialogExtensionsTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
    }

    @Test
    fun setupDismissKeys_dismissesOnBackKey() {
        val dialog = Dialog(context)
        dialog.setupDismissKeys()
        dialog.show()

        assertTrue(dialog.isShowing)

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = dialog.dispatchKeyEvent(event)

        assertTrue(handled)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun setupDismissKeys_dismissesOnSoftRightKey() {
        val dialog = Dialog(context)
        dialog.setupDismissKeys()
        dialog.show()

        assertTrue(dialog.isShowing)

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val handled = dialog.dispatchKeyEvent(event)

        assertTrue(handled)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun setupDismissKeys_invokesCustomOnDismissOnBackKey() {
        var customDismissCalled = false
        val dialog = Dialog(context)
        dialog.setupDismissKeys { customDismissCalled = true }
        dialog.show()

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = dialog.dispatchKeyEvent(event)

        assertTrue(handled)
        assertTrue(customDismissCalled)
    }

    @Test
    fun setupDismissKeys_invokesCustomOnDismissOnSoftRightKey() {
        var customDismissCalled = false
        val dialog = Dialog(context)
        dialog.setupDismissKeys { customDismissCalled = true }
        dialog.show()

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val handled = dialog.dispatchKeyEvent(event)

        assertTrue(handled)
        assertTrue(customDismissCalled)
    }

    @Test
    fun setupDismissKeys_doesNotDismissOnOtherKeys() {
        val dialog = Dialog(context)
        dialog.setupDismissKeys()
        dialog.show()

        assertTrue(dialog.isShowing)

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        dialog.dispatchKeyEvent(event)

        assertTrue(dialog.isShowing)
    }

    @Test
    fun diagnosticsDialog_dismissesOnBackKey() {
        val dialog = DiagnosticsDialog(context)
        dialog.show()

        assertTrue(dialog.isShowing)

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = dialog.dispatchKeyEvent(event)

        assertTrue(handled)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun diagnosticsDialog_dismissesOnSoftRightKey() {
        val dialog = DiagnosticsDialog(context)
        dialog.show()

        assertTrue(dialog.isShowing)

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val handled = dialog.dispatchKeyEvent(event)

        assertTrue(handled)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun symbolsDialog_dismissesOnBackKey() {
        val dialog = SymbolsDialog(context) { }
        dialog.show()

        assertTrue(dialog.isShowing)

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val handled = dialog.dispatchKeyEvent(event)

        assertTrue(handled)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun symbolsDialog_dismissesOnSoftRightKey() {
        val dialog = SymbolsDialog(context) { }
        dialog.show()

        assertTrue(dialog.isShowing)

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val handled = dialog.dispatchKeyEvent(event)

        assertTrue(handled)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun optionsMenuDialog_dismissesOnBackKeyAndSoftRight() {
        val dialog = OptionsMenuDialog(context, "Test Menu", emptyList())
        dialog.show()

        assertTrue(dialog.isShowing)

        val backEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val backHandled = dialog.dispatchKeyEvent(backEvent)

        assertTrue(backHandled)
        assertFalse(dialog.isShowing)

        dialog.show()
        assertTrue(dialog.isShowing)

        val softRightEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val softRightHandled = dialog.dispatchKeyEvent(softRightEvent)

        assertTrue(softRightHandled)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun optionsMenuDialog_handlesNumberKeysCorrectly() {
        var actionExecuted = false
        val options =
            listOf(
                MenuOption(1, "Option 1") { actionExecuted = true },
                MenuOption(2, "Option 2") { },
            )
        val dialog = OptionsMenuDialog(context, "Test Menu", options)
        dialog.show()

        assertTrue(dialog.isShowing)

        val num1Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1)
        val handled = dialog.dispatchKeyEvent(num1Event)

        assertTrue(handled)
        assertTrue(actionExecuted)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun testFocusRestorationOnDialogDismissal() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            ShadowLooper.idleMainLooper()

            val mainContainer = activity.findViewById<FrameLayout>(R.id.main_container)
            val button =
                Button(activity).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
            mainContainer.addView(button)
            button.requestFocus()

            val currentFocus = activity.currentFocus ?: activity.window?.decorView?.findFocus()
            assertNotNull("A view should be focused before showing dialog", currentFocus)

            activity.onDialogShown()
            activity.onDialogDismissed()

            ShadowLooper.idleMainLooper()

            assertTrue("Trigger view should be focused after dialog dismiss", button.isFocused)
        }
        scenario.close()
    }

    @Test
    fun testNestedDialogFocusRestoration() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            ShadowLooper.idleMainLooper()

            val mainContainer = activity.findViewById<FrameLayout>(R.id.main_container)
            val button =
                Button(activity).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
            mainContainer.addView(button)
            button.requestFocus()

            // Open dialog 1
            activity.onDialogShown()

            // Open nested dialog 2
            activity.onDialogShown()

            // Dismiss dialog 2
            activity.onDialogDismissed()
            ShadowLooper.idleMainLooper()

            // Dismiss dialog 1
            activity.onDialogDismissed()
            ShadowLooper.idleMainLooper()

            assertTrue("Trigger view should be focused after all dialogs dismiss", button.isFocused)
        }
        scenario.close()
    }

    @Test
    fun testSearchDialogInheritsBaseDialogLifecycle() {
        assertTrue("SearchDialog must inherit BaseDialog", BaseDialog::class.java.isAssignableFrom(SearchDialog::class.java))

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val mockTm = mock(TelegramManager::class.java)
            val dialog =
                SearchDialog(
                    context = activity,
                    telegramManager = mockTm,
                    onChatSelected = {},
                    onMessageSelected = { _, _ -> },
                )

            dialog.show()
            ShadowLooper.idleMainLooper()

            dialog.dismiss()
            ShadowLooper.idleMainLooper()
        }
        scenario.close()
    }
}
