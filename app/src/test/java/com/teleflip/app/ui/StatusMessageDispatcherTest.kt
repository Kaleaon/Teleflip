package com.teleflip.app.ui

import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@RunWith(AndroidJUnit4::class)
class StatusMessageDispatcherTest {
    private lateinit var activity: AppCompatActivity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().start().resume().get()
        activity.setTheme(R.style.Theme_TeleFlip)
    }

    @Test
    fun testShowStatusWithActivityDoesNotCrash() {
        StatusMessageDispatcher.showStatus(activity, "Test message", isError = false)
        StatusMessageDispatcher.showStatus(activity, "Error message", isError = true)
    }

    @Test
    fun testShowStatusWithContextDoesNotCrash() {
        StatusMessageDispatcher.showStatus(activity.applicationContext, "Test context message", isError = false)
    }

    @Test
    fun testShowStatusWithView() {
        val view = Button(activity)
        activity.setContentView(view)

        StatusMessageDispatcher.showStatus(view, "View status message", isError = true)
    }
}
