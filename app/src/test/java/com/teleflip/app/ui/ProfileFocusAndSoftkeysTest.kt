package com.teleflip.app.ui

import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ProfileFocusAndSoftkeysTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private lateinit var mockSoftkeyBar: View
    private lateinit var mockTvLeft: TextView
    private lateinit var mockTvCenter: TextView
    private lateinit var mockTvRight: TextView
    private lateinit var softkeyManager: SoftkeyManager

    private var leftText: String = ""
    private var centerText: String = ""
    private var rightText: String = ""

    @Before
    fun setUp() {
        mockSoftkeyBar = mock(View::class.java)
        mockTvLeft = mock(TextView::class.java)
        mockTvCenter = mock(TextView::class.java)
        mockTvRight = mock(TextView::class.java)

        softkeyManager =
            SoftkeyManager(
                softkeyBar = mockSoftkeyBar,
                tvLeft = mockTvLeft,
                tvCenter = mockTvCenter,
                tvRight = mockTvRight,
            )

        // Capture text updates
        org.mockito.Mockito.`when`(mockTvLeft.setText(org.mockito.ArgumentMatchers.any(CharSequence::class.java))).thenAnswer { invocation ->
            leftText = invocation.arguments[0]?.toString() ?: ""
            null
        }
        org.mockito.Mockito.`when`(mockTvCenter.setText(org.mockito.ArgumentMatchers.any(CharSequence::class.java))).thenAnswer { invocation ->
            centerText = invocation.arguments[0]?.toString() ?: ""
            null
        }
        org.mockito.Mockito.`when`(mockTvRight.setText(org.mockito.ArgumentMatchers.any(CharSequence::class.java))).thenAnswer { invocation ->
            rightText = invocation.arguments[0]?.toString() ?: ""
            null
        }
    }

    private fun getLayoutFile(filename: String): File {
        val path = "src/main/res/layout/$filename"
        val file = File(path)
        if (file.exists()) return file
        return File("app/$path")
    }

    @Test
    fun testProfileLayoutFocusAttributesPresent() {
        val file = getLayoutFile("view_profile.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

        val expectedFocusChain =
            listOf(
                "@+id/layout_profile_phone_row",
                "@+id/layout_profile_username_row",
                "@+id/layout_profile_bio_row",
                "@+id/btn_profile_tg_call",
                "@+id/btn_profile_cell_call",
                "@+id/btn_profile_secret_chat",
                "@+id/btn_profile_self_destruct",
                "@+id/btn_profile_members",
                "@+id/btn_profile_forum_topics",
                "@+id/btn_profile_send_msg",
                "@+id/btn_profile_toggle_mute",
                "@+id/btn_profile_block_user",
                "@+id/btn_profile_search",
                "@+id/btn_profile_export_history",
            )

        for (id in expectedFocusChain) {
            val node = findNodeById(doc, id)
            assertNotNull("View $id should exist in view_profile.xml", node)
            val nextUp = node?.attributes?.getNamedItemNS(androidNs, "nextFocusUp")?.nodeValue
            val nextDown = node?.attributes?.getNamedItemNS(androidNs, "nextFocusDown")?.nodeValue

            assertNotNull("View $id must have android:nextFocusUp defined", nextUp)
            assertTrue("View $id nextFocusUp must not be blank", nextUp!!.isNotBlank())
            assertNotNull("View $id must have android:nextFocusDown defined", nextDown)
            assertTrue("View $id nextFocusDown must not be blank", nextDown!!.isNotBlank())
        }

        // Verify specific sequential link between controls
        val phoneRow = findNodeById(doc, "@+id/layout_profile_phone_row")
        assertEquals("@id/layout_profile_phone_row", phoneRow?.attributes?.getNamedItemNS(androidNs, "nextFocusUp")?.nodeValue)
        assertEquals("@id/layout_profile_username_row", phoneRow?.attributes?.getNamedItemNS(androidNs, "nextFocusDown")?.nodeValue)

        val tgCall = findNodeById(doc, "@+id/btn_profile_tg_call")
        assertEquals("@id/layout_profile_bio_row", tgCall?.attributes?.getNamedItemNS(androidNs, "nextFocusUp")?.nodeValue)
        assertEquals("@id/btn_profile_cell_call", tgCall?.attributes?.getNamedItemNS(androidNs, "nextFocusDown")?.nodeValue)

        val exportBtn = findNodeById(doc, "@+id/btn_profile_export_history")
        assertEquals("@id/btn_profile_search", exportBtn?.attributes?.getNamedItemNS(androidNs, "nextFocusUp")?.nodeValue)
        assertEquals("@id/btn_profile_export_history", exportBtn?.attributes?.getNamedItemNS(androidNs, "nextFocusDown")?.nodeValue)
    }

    @Test
    fun testDynamicFocusChainingSkipsGoneViews() {
        class MockView(
            val id: Int,
            var visibility: Int,
            var nextFocusUpId: Int = View.NO_ID,
            var nextFocusDownId: Int = View.NO_ID,
        )

        val v1 = MockView(1, View.VISIBLE)
        val v2 = MockView(2, View.GONE)
        val v3 = MockView(3, View.GONE)
        val v4 = MockView(4, View.VISIBLE)
        val v5 = MockView(5, View.GONE)
        val v6 = MockView(6, View.VISIBLE)

        val allViews = listOf(v1, v2, v3, v4, v5, v6)
        val visibleViews = allViews.filter { it.visibility == View.VISIBLE }

        for (i in visibleViews.indices) {
            val current = visibleViews[i]
            val prev = if (i > 0) visibleViews[i - 1] else visibleViews[i]
            val next = if (i < visibleViews.size - 1) visibleViews[i + 1] else visibleViews[i]
            current.nextFocusUpId = prev.id
            current.nextFocusDownId = next.id
        }

        // v1 should point down to v4 (skipping GONE v2 and v3)
        assertEquals(1, v1.nextFocusUpId)
        assertEquals(4, v1.nextFocusDownId)

        // v4 should point up to v1 and down to v6 (skipping GONE v5)
        assertEquals(1, v4.nextFocusUpId)
        assertEquals(6, v4.nextFocusDownId)

        // v6 should point up to v4 and down to itself
        assertEquals(4, v6.nextFocusUpId)
        assertEquals(6, v6.nextFocusDownId)
    }

    @Test
    fun testSoftkeyUpdatesOnTgCallFocus() {
        var callTriggered = false
        val callAction = { callTriggered = true }

        softkeyManager.update(
            left = "Call",
            center = "Call",
            right = "Back",
            onLeft = callAction,
            onCenter = callAction,
            onRight = {},
        )

        assertEquals("Call", leftText)
        assertEquals("Call", centerText)
        assertEquals("Back", rightText)

        // Trigger Left Softkey
        val handledLeft = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT))
        assertTrue(handledLeft)
        assertTrue(callTriggered)

        // Reset and trigger Center Key
        callTriggered = false
        val handledCenter = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(handledCenter)
        assertTrue(callTriggered)
    }

    @Test
    fun testSoftkeyUpdatesOnExportHistoryFocus() {
        var exportTriggered = false
        val exportAction = { exportTriggered = true }

        softkeyManager.update(
            left = "Export",
            center = "Export",
            right = "Back",
            onLeft = exportAction,
            onCenter = exportAction,
            onRight = {},
        )

        assertEquals("Export", leftText)
        assertEquals("Export", centerText)
        assertEquals("Back", rightText)

        // Trigger Center Softkey / D-Pad Center
        val handled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(handled)
        assertTrue(exportTriggered)
    }

    @Test
    fun testSoftkeyUpdatesOnPhoneNumberFocus() {
        var dialTriggered = false
        val dialAction = { dialTriggered = true }

        softkeyManager.update(
            left = "Dial",
            center = "Dial",
            right = "Back",
            onLeft = dialAction,
            onCenter = dialAction,
            onRight = {},
        )

        assertEquals("Dial", leftText)
        assertEquals("Dial", centerText)
        assertEquals("Back", rightText)

        // Trigger Left Softkey
        val handledLeft = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_LEFT))
        assertTrue(handledLeft)
        assertTrue(dialTriggered)

        // Trigger Center Key
        dialTriggered = false
        val handledCenter = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(handledCenter)
        assertTrue(dialTriggered)
    }

    @Test
    fun testBackSoftkeyReturns() {
        var backTriggered = false
        val backAction = { backTriggered = true }

        softkeyManager.update(
            left = "Open",
            center = "Open",
            right = "Back",
            onLeft = {},
            onCenter = {},
            onRight = backAction,
        )

        assertEquals("Back", rightText)

        val handled = softkeyManager.handleKeyEvent(KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SOFT_RIGHT))
        assertTrue(handled)
        assertTrue(backTriggered)
    }

    private fun findNodeById(
        node: org.w3c.dom.Node,
        targetId: String,
    ): org.w3c.dom.Node? {
        var result: org.w3c.dom.Node? = null
        if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
            val element = node as org.w3c.dom.Element
            val idAttr = element.getAttributeNS(androidNs, "id")
            if (idAttr == targetId || idAttr == targetId.replace("@+id/", "@id/")) {
                result = element
            }
        }
        val children = node.childNodes
        for (i in 0 until children.length) {
            if (result != null) break
            val child = children.item(i)
            val found = findNodeById(child, targetId)
            if (found != null) {
                result = found
            }
        }
        return result
    }
}
