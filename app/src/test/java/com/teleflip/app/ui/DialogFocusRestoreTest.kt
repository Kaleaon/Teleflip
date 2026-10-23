package com.teleflip.app.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.MainActivity
import com.teleflip.app.R
import com.teleflip.app.telegram.TelegramManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

private class TestDialog(context: Context) : BaseDialog(context)

@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DialogFocusRestoreTest {
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @Test
    fun testPreCaptureFocusPriorToDialogDisplayAndRestoreOnDismiss() {
        val testButton =
            Button(activity).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
            }
        val mainContainer = activity.findViewById<FrameLayout>(R.id.main_container)
        mainContainer.addView(testButton)
        testButton.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = TestDialog(activity)
        dialog.show()

        assertEquals("savedFocusView should pre-capture focus prior to dialog window transfer", testButton, activity.savedFocusView)
        assertEquals(1, activity.activeDialogCount)

        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("savedFocusView should be cleared after root dialog dismiss", activity.savedFocusView)
        assertEquals(0, activity.activeDialogCount)
        assertTrue("Trigger view should regain focus after dialog dismissal", testButton.hasFocus())
    }

    @Test
    fun testNestedDialogsPreserveOriginalSavedFocusView() {
        val testButton =
            Button(activity).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
            }
        val mainContainer = activity.findViewById<FrameLayout>(R.id.main_container)
        mainContainer.addView(testButton)
        testButton.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()

        val dialog1 = TestDialog(activity)
        dialog1.show()

        assertEquals(testButton, activity.savedFocusView)
        assertEquals(1, activity.activeDialogCount)

        val dialog2 = TestDialog(activity)
        dialog2.show()

        assertEquals("Nested dialog must preserve original saved focus view", testButton, activity.savedFocusView)
        assertEquals(2, activity.activeDialogCount)

        dialog2.dismiss()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Dismissing nested dialog should maintain original saved focus view", testButton, activity.savedFocusView)
        assertEquals(1, activity.activeDialogCount)

        dialog1.dismiss()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("Saved focus view should be cleared when all dialogs dismissed", activity.savedFocusView)
        assertEquals(0, activity.activeDialogCount)
        assertTrue("Original focus should be restored after all nested dialogs close", testButton.hasFocus())
    }

    @Test
    fun testDetachedTriggerViewFallsBackGracefully() {
        val testButton =
            Button(activity).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
            }
        val mainContainer = activity.findViewById<FrameLayout>(R.id.main_container)
        mainContainer.addView(testButton)
        testButton.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = TestDialog(activity)
        dialog.show()

        assertEquals(testButton, activity.savedFocusView)

        // Detach trigger view while dialog is open
        mainContainer.removeView(testButton)
        shadowOf(Looper.getMainLooper()).idle()

        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("savedFocusView should be cleared", activity.savedFocusView)
        assertEquals(0, activity.activeDialogCount)
        assertTrue("Fallback view should regain focus when trigger view is detached", mainContainer.hasFocus())
    }

    @Test
    fun testSearchDialogFocusRestorationLifecycle() {
        val testButton =
            Button(activity).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
            }
        val mainContainer = activity.findViewById<FrameLayout>(R.id.main_container)
        mainContainer.addView(testButton)
        testButton.requestFocus()
        shadowOf(Looper.getMainLooper()).idle()

        val tm = mock(TelegramManager::class.java)
        val searchDialog = SearchDialog(activity, tm, {}, { _, _ -> })
        searchDialog.show()

        assertEquals(testButton, activity.savedFocusView)

        searchDialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(activity.savedFocusView)
        assertTrue("SearchDialog dismissal should restore focus to trigger view", testButton.hasFocus())
    }
}
