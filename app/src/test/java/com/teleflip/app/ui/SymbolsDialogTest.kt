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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

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
        ShadowLooper.idleMainLooper()

        assertTrue(dialog.isShowing)
        assertEquals(0, dialog.currentPage)
        assertEquals(4, dialog.totalPages)
        assertEquals(4, dialog.pageCount)

        val tvHeader = dialog.findViewById<TextView>(R.id.tv_symbols_header)
        assertNotNull(tvHeader)
        assertEquals("Page 1/4 (1-9)", tvHeader.text.toString())

        val rvGrid = dialog.findViewById<RecyclerView>(R.id.rv_symbols_grid)
        assertNotNull(rvGrid)
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
        ShadowLooper.idleMainLooper()

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
        ShadowLooper.idleMainLooper()

        // Key 9 corresponds to index 8 on Page 1: "("
        val event9 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_9)
        val handled9 = dialog.onKeyDown(KeyEvent.KEYCODE_9, event9)

        assertTrue(handled9)
        assertFalse(dialog.isShowing)
        assertEquals("(", selectedSymbol)
    }

    @Test
    fun testSymbolsDialog_numericKeySelectionPage2() {
        var selectedSymbol: String? = null
        val dialog =
            SymbolsDialog(context) { symbol ->
                selectedSymbol = symbol
            }
        dialog.show()
        ShadowLooper.idleMainLooper()

        // Advance to Page 2 via POUND key
        val poundEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POUND)
        dialog.dispatchKeyEvent(poundEvent)
        assertEquals(1, dialog.currentPage)

        // Key 2 on Page 2 corresponds to index 1 on Page 2: "-"
        val num2Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)
        val handled = dialog.dispatchKeyEvent(num2Event)

        assertTrue(handled)
        assertEquals("-", selectedSymbol)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun testSymbolsDialog_poundAndStarPageNavigation() {
        val dialog = SymbolsDialog(context) { }
        dialog.show()
        ShadowLooper.idleMainLooper()

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

        // Press '#' to wrap around to Page 1
        assertTrue(dialog.onKeyDown(KeyEvent.KEYCODE_POUND, poundEvent))
        assertEquals("Page 1/4 (1-9)", tvHeader.text.toString())
        assertEquals(9, rvGrid.adapter?.itemCount)

        // Press '*' to cycle back wrapping to Page 4
        val starEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STAR)
        assertTrue(dialog.onKeyDown(KeyEvent.KEYCODE_STAR, starEvent))
        assertEquals("Page 4/4 (1-5)", tvHeader.text.toString())
        assertEquals(5, rvGrid.adapter?.itemCount)

        // Press '*' to cycle back to Page 3
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
        ShadowLooper.idleMainLooper()

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
        ShadowLooper.idleMainLooper()

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
        ShadowLooper.idleMainLooper()

        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        tvCenter.performClick()

        assertFalse(dialog.isShowing)
        assertEquals("?", selectedSymbol)
    }

    @Test
    fun testSymbolsDialog_softkeyLeftKeyNavigation() {
        val dialog = SymbolsDialog(context) {}
        dialog.show()
        ShadowLooper.idleMainLooper()

        assertEquals(0, dialog.currentPage)

        // Left Soft Key -> Next page (Page 2)
        val lskEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT)
        val handled = dialog.dispatchKeyEvent(lskEvent)

        assertTrue(handled)
        assertEquals(1, dialog.currentPage)
    }

    @Test
    fun testSymbolsDialog_dismissOnRightSoftKeyAndBack() {
        var selectedSymbol: String? = null
        val dialog = SymbolsDialog(context) { selectedSymbol = it }
        dialog.show()
        ShadowLooper.idleMainLooper()

        val rskEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT)
        val handled = dialog.dispatchKeyEvent(rskEvent)

        assertTrue(handled)
        assertFalse(dialog.isShowing)
        assertNull(selectedSymbol)
    }

    @Test
    fun testSymbolsDialog_cellBadgesAndContentDescriptions() {
        val dialog = SymbolsDialog(context) {}
        dialog.show()
        ShadowLooper.idleMainLooper()

        val rv = dialog.findViewById<RecyclerView>(R.id.rv_symbols_grid)
        assertNotNull(rv)
        assertEquals(9, rv.adapter?.itemCount)

        val holder = rv.adapter?.createViewHolder(rv, 0) as SymbolsDialog.SymbolAdapter.SymbolViewHolder
        rv.adapter?.bindViewHolder(holder, 0)

        assertEquals("?", holder.tvSymbol.text.toString())
        assertEquals("1.", holder.tvShortcut?.text?.toString())
        assertEquals("1.", holder.tvShortcutBadge?.text?.toString())
        assertEquals("1. ?", holder.itemView.contentDescription)
    }
}
