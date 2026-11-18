package com.teleflip.app

import android.app.Application
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.model.ActiveCall
import com.teleflip.app.model.CallState
import com.teleflip.app.telegram.TelegramManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w240dp-h320dp-mdpi", application = Application::class)
class FlipPhoneCallTest {
    @Test fun compactCallControlsFitAndDpadReachesSpeakerAndHangup() {
        assertControlsFitAndNavigate(240, 288)
    }

    @Test
    @Config(sdk = [26, 27], qualifiers = "w320dp-h533dp-hdpi")
    fun schokClassicAndroid8ControlsFitAndNavigate() {
        assertControlsFitAndNavigate(480, 720)
    }

    private fun assertControlsFitAndNavigate(
        width: Int,
        height: Int,
    ) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.setTheme(R.style.Theme_TeleFlip)
        val root = LayoutInflater.from(context).inflate(R.layout.view_in_call, null) as ViewGroup
        root.findViewById<Button>(R.id.btn_call_accept).visibility = View.VISIBLE
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val mute = root.findViewById<Button>(R.id.btn_call_mute)
        val speaker = root.findViewById<Button>(R.id.btn_call_speaker)
        val end = root.findViewById<Button>(R.id.btn_call_end)
        assertSame(speaker, FocusFinder.getInstance().findNextFocus(root, mute, View.FOCUS_RIGHT))
        assertSame(end, FocusFinder.getInstance().findNextFocus(root, speaker, View.FOCUS_DOWN))
        val bounds = android.graphics.Rect()
        end.getDrawingRect(bounds)
        root.offsetDescendantRectToMyCoords(end, bounds)
        assertTrue("Hangup must fit above the softkey bar (bottom=${bounds.bottom}, height=$height)", bounds.bottom <= height)
    }

    private fun activity(): Pair<MainActivity, TelegramManager> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val manager = mock(TelegramManager::class.java)
        activity.telegramManager = manager
        activity.currentScreen = Screen.IN_CALL
        `when`(manager.activeCall).thenReturn(MutableStateFlow(ActiveCall(7, 10, "Alice", true, CallState.READY, 20)))
        `when`(manager.hasOngoingCall).thenReturn(true)
        return activity to manager
    }

    @Test fun callKeyDuringCallIsConsumedWithoutStartingAnotherCall() {
        val (activity, manager) = activity()
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CALL)))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CALL)))
        assertTrue(mockingDetails(manager).invocations.none { it.method.name == "initiateTelegramCall" })
    }

    @Test fun physicalEndAndHeadsetKeysHangUpWithoutTouchInput() {
        val (activity, manager) = activity()
        assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENDCALL)))
        verify(manager).endCurrentCall()
        val (headsetActivity, headsetManager) = activity()
        assertTrue(headsetActivity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)))
        verify(headsetManager).endCurrentCall()
    }
}
