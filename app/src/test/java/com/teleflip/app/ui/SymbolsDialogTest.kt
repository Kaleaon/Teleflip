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
    fun testDirectNumericShortcutSelection() {
        var selectedSymbol: String? = null
        val dialog = SymbolsDialog(context) { symbol ->
            selectedSymbol = symbol
        }
        dialog.show()
        ShadowLooper.idleMainLooper()

        // Key 1 -> Position 0 on Page 1 -> "?"
        val eventNum1 = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1)
        val handled = dialog.dispatchKeyEvent(eventNum1)

        assertTrue("Key 1 should be handled", handled)
        assertEquals("?", selectedSymbol)
        assertFalse("Dialog should be dismissed after selection", dialog.isShowing)
    }

    @Test
    fun testNumericShortcutOnSecondPage() {
        var selectedSymbol: String? = null
        val dialog = SymbolsDialog(context) { symbol ->
            selectedSymbol = symbol
        }
        dialog.show()
        ShadowLooper.idleMainLooper()

        // Advance to Page 2 via POUND key
        val poundEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POUND)
        dialog.dispatchKeyEvent(poundEvent)
        assertEquals(1, dialog.currentPage)

        // Key 3 on Page 2 -> Position 2 on Page 2 -> "-"
        val num3Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_3)
        val handled = dialog.dispatchKeyEvent(num3Event)

        assertTrue(handled)
        assertEquals("-", selectedSymbol)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun testSoftkeyBarSetupAndPageTitle() {
        val dialog = SymbolsDialog(context) {}
        dialog.show()
        ShadowLooper.idleMainLooper()

        val tvTitle = dialog.findViewById<TextView>(R.id.tv_symbols_title)
        val tvLsk = dialog.findViewById<TextView>(R.id.tv_softkey_left)
        val tvCenter = dialog.findViewById<TextView>(R.id.tv_softkey_center)
        val tvRsk = dialog.findViewById<TextView>(R.id.tv_softkey_right)

        assertNotNull(tvTitle)
        assertNotNull(tvLsk)
        assertNotNull(tvCenter)
        assertNotNull(tvRsk)

        assertEquals("Symbols (1/4)", tvTitle?.text?.toString())
        assertEquals("Next", tvLsk?.text?.toString())
        assertEquals("Select", tvCenter?.text?.toString())
        assertEquals("Cancel", tvRsk?.text?.toString())
    }

    @Test
    fun testPageNavigationAndWrapping() {
        val dialog = SymbolsDialog(context) {}
        dialog.show()
        ShadowLooper.idleMainLooper()

        assertEquals(0, dialog.currentPage)

        // POUND key -> Next page (Page 2)
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POUND))
        assertEquals(1, dialog.currentPage)

        // Left Soft Key -> Next page (Page 3)
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT))
        assertEquals(2, dialog.currentPage)

        // Next page (Page 4)
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POUND))
        assertEquals(3, dialog.currentPage)

        // Next page wraps around to Page 1 (index 0)
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POUND))
        assertEquals(0, dialog.currentPage)

        // STAR key -> Previous page wraps to Page 4 (index 3)
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STAR))
        assertEquals(3, dialog.currentPage)
    }

    @Test
    fun testSymbolCellBadgesAndContentDescriptions() {
        val dialog = SymbolsDialog(context) {}
        dialog.show()
        ShadowLooper.idleMainLooper()

        val rv = dialog.findViewById<RecyclerView>(R.id.rv_symbols_grid)
        assertNotNull(rv)
        assertEquals(8, rv?.adapter?.itemCount)

        val holder = rv?.adapter?.createViewHolder(rv, 0) as SymbolsDialog.SymbolAdapter.SymbolViewHolder
        rv.adapter?.bindViewHolder(holder, 0)

        assertEquals("?", holder.tvSymbol.text.toString())
        assertEquals("1", holder.tvShortcutBadge.text.toString())
        assertEquals("1. ?", holder.itemView.contentDescription)
    }

    @Test
    fun testDismissOnRightSoftKeyAndBack() {
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
}
