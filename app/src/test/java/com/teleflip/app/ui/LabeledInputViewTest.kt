package com.teleflip.app.ui

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LabeledInputViewTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
    }

    @Test
    fun testLabelForAutoLinking() {
        val view = LabeledInputView(context)
        assertNotEquals("Label view must have valid ID", View.NO_ID, view.labelView.id)
        assertNotEquals("EditText view must have valid ID", View.NO_ID, view.editText.id)
        assertEquals("Label view labelFor must point to editText id", view.editText.id, view.labelView.labelFor)
    }

    @Test
    fun testPropertiesGettersAndSetters() {
        val view = LabeledInputView(context)

        view.labelText = "Username:"
        assertEquals("Username:", view.labelText)
        assertEquals("Username:", view.labelView.text.toString())
        assertEquals(View.VISIBLE, view.labelView.visibility)

        view.labelText = ""
        assertEquals(View.GONE, view.labelView.visibility)

        view.text = "John Doe"
        assertEquals("John Doe", view.text)
        assertEquals("John Doe", view.editText.text.toString())

        view.errorText = "Invalid username"
        assertEquals("Invalid username", view.errorText)
        assertEquals(View.VISIBLE, view.errorView.visibility)

        view.errorText = null
        assertEquals(View.GONE, view.errorView.visibility)

        view.inputModeText = "[#] Abc"
        assertEquals("[#] Abc", view.inputModeText)
        assertEquals(View.VISIBLE, view.modeView.visibility)
    }

    @Test
    fun testFocusDelegation() {
        val view = LabeledInputView(context)
        view.requestFocus()
        assertTrue("Focus should be delegated to internal editText", view.editText.hasFocus())
    }
}
