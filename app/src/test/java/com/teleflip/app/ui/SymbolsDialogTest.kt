package com.teleflip.app.ui

import android.app.Application
import android.view.KeyEvent
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SymbolsDialogTest {
    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_TeleFlip)
    }

    @Test
    fun testSymbolsDialog_initialState() {
        val dialog = SymbolsDialog(context) { }
        dialog.show()

        assertTrue(dialog.isShowing)
        val tvHeader = dialog.findViewById<TextView>(R.id.tv_symbols_header)
        assertEquals("Page 1/4 (1-9)", tvHeader.text.toString())

        val rvGrid = dialog.findViewById<RecyclerView>(R.id.rv_symbols_grid)
        assertEquals(9, rvGrid.adapter?.itemCount)

        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)

        assertEquals("Page", tvLeft.text.toString())
        assertEquals("Select", tvCenter.text.toString())
        assertEquals("Back", tvRight.text.toString())
    }

    @Test
    fun testSymbolsDialog_numericKeySelectionPage1() {
        var selectedSymbol: String? = null
        val dialog =
            SymbolsDialog(context) { symbol ->
                selectedSymbol = symbol
            }
        dialog.show()

        // Key 1 corresponds to index 0 on Page 1: "?"
        val event1 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1)
        val handled1 = dialog.onKeyDown(KeyEvent.KEYCODE_1, event1)

        assertTrue(handled1)
        assertFalse(dialog.isShowing)
        assertEquals("?", selectedSymbol)
    }

    @Test
    fun testSymbolsDialog_numericKeySelection9Page1() {
        var selectedSymbol: String? = null
        val dialog =
            SymbolsDialog(context) { symbol ->
                selectedSymbol = symbol
            }
        dialog.show()

        // Key 9 corresponds to index 8 on Page 1: "("
        val event9 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_9)
        val handled9 = dialog.onKeyDown(KeyEvent.KEYCODE_9, event9)

        assertTrue(handled9)
        assertFalse(dialog.isShowing)
        assertEquals("(", selectedSymbol)
    }

    @Test
    fun testSymbolsDialog_poundAndStarPageNavigation() {
        val dialog = SymbolsDialog(context) { }
        dialog.show()

        val tvHeader = dialog.findViewById<TextView>(R.id.tv_symbols_header)
        val rvGrid = dialog.findViewById<RecyclerView>(R.id.rv_symbols_grid)

        assertEquals("Page 1/4 (1-9)", tvHeader.text.toString())

        // Press '#' to cycle to Page 2
        val poundEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POUND)
        assertTrue(dialog.onKeyDown(KeyEvent.KEYCODE_POUND, poundEvent))
        assertEquals("Page 2/4 (1-9)", tvHeader.text.toString())
        assertEquals(9, rvGrid.adapter?.itemCount)

        // Press '#' to cycle to Page 3
        assertTrue(dialog.onKeyDown(KeyEvent.KEYCODE_POUND, poundEvent))
        assertEquals("Page 3/4 (1-9)", tvHeader.text.toString())
        assertEquals(9, rvGrid.adapter?.itemCount)

        // Press '#' to cycle to Page 4 (5 items)
        assertTrue(dialog.onKeyDown(KeyEvent.KEYCODE_POUND, poundEvent))
        assertEquals("Page 4/4 (1-5)", tvHeader.text.toString())
        assertEquals(5, rvGrid.adapter?.itemCount)

        // Press '*' to cycle back to Page 3
        val starEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STAR)
        assertTrue(dialog.onKeyDown(KeyEvent.KEYCODE_STAR, starEvent))
        assertEquals("Page 3/4 (1-9)", tvHeader.text.toString())
    }

    @Test
    fun testSymbolsDialog_page4PartialPageKeyFiltering() {
        var selectedSymbol: String? = null
        val dialog =
            SymbolsDialog(context) { symbol ->
                selectedSymbol = symbol
            }
        dialog.show()

        // Navigate to Page 4
        val poundEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POUND)
        dialog.onKeyDown(KeyEvent.KEYCODE_POUND, poundEvent) // Page 2
        dialog.onKeyDown(KeyEvent.KEYCODE_POUND, poundEvent) // Page 3
        dialog.onKeyDown(KeyEvent.KEYCODE_POUND, poundEvent) // Page 4

        val tvHeader = dialog.findViewById<TextView>(R.id.tv_symbols_header)
        assertEquals("Page 4/4 (1-5)", tvHeader.text.toString())

        // Key 6 is invalid on Page 4 (only 5 items)
        val event6 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_6)
        val handled6 = dialog.onKeyDown(KeyEvent.KEYCODE_6, event6)

        assertTrue(handled6) // Handled/consumed safely
        assertTrue(dialog.isShowing) // Dialog remains open
        assertNull(selectedSymbol)

        // Key 1 is valid on Page 4 (index 0 on page 4 is "❤️")
        val event1 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1)
        val handled1 = dialog.onKeyDown(KeyEvent.KEYCODE_1, event1)

        assertTrue(handled1)
        assertFalse(dialog.isShowing)
        assertEquals("❤️", selectedSymbol)
    }

    @Test
    fun testSymbolsDialog_softkeyPageAndBackActions() {
        val dialog = SymbolsDialog(context) { }
        dialog.show()

        val tvLeft = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvRight = dialog.findViewById<TextView>(R.id.tv_softkey_right)
        val tvHeader = dialog.findViewById<TextView>(R.id.tv_symbols_header)

        // Click LSK ("Page")
        tvLeft.performClick()
        assertEquals("Page 2/4 (1-9)", tvHeader.text.toString())

        // Click RSK ("Back")
        tvRight.performClick()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun testSymbolsDialog_softkeySelectAction() {
        var selectedSymbol: String? = null
        val dialog =
            SymbolsDialog(context) { symbol ->
                selectedSymbol = symbol
            }
        dialog.show()

        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        tvCenter.performClick()

        assertFalse(dialog.isShowing)
        assertEquals("?", selectedSymbol)
    }
}
