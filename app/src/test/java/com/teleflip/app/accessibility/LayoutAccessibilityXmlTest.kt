package com.teleflip.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LayoutAccessibilityXmlTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun getLayoutFile(filename: String): File {
        val path = "src/main/res/layout/$filename"
        val file = File(path)
        if (file.exists()) return file
        return File("app/$path")
    }

    @Test
    fun testChatListLiveRegion() {
        val file = getLayoutFile("view_chat_list.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val node = findNodeById(doc, "@+id/tv_connection_status")
        assertNotNull("tv_connection_status should exist in view_chat_list.xml", node)
        val liveRegion = node?.attributes?.getNamedItemNS(androidNs, "accessibilityLiveRegion")?.nodeValue
        assertEquals("polite", liveRegion)
    }

    @Test
    fun testChatConversationLiveRegions() {
        val file = getLayoutFile("view_chat_conversation.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

        val recordStatusNode = findNodeById(doc, "@+id/tv_record_status")
        assertNotNull("tv_record_status should exist", recordStatusNode)
        assertEquals("polite", recordStatusNode?.attributes?.getNamedItemNS(androidNs, "accessibilityLiveRegion")?.nodeValue)

        val inputModeNode = findNodeById(doc, "@+id/tv_input_mode")
        assertNotNull("tv_input_mode should exist", inputModeNode)
        assertEquals("polite", inputModeNode?.attributes?.getNamedItemNS(androidNs, "accessibilityLiveRegion")?.nodeValue)

        val recordTimerNode = findNodeById(doc, "@+id/tv_record_timer")
        assertNotNull("tv_record_timer should exist", recordTimerNode)
        val timerLiveRegion = recordTimerNode?.attributes?.getNamedItemNS(androidNs, "accessibilityLiveRegion")?.nodeValue
        assertNull("tv_record_timer must NOT have accessibilityLiveRegion to avoid 1-second speech ticks", timerLiveRegion)
    }

    @Test
    fun testLoginLiveRegion() {
        val file = getLayoutFile("view_login.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val node = findNodeById(doc, "@+id/tv_login_status")
        assertNotNull("tv_login_status should exist in view_login.xml", node)
        val liveRegion = node?.attributes?.getNamedItemNS(androidNs, "accessibilityLiveRegion")?.nodeValue
        assertEquals("polite", liveRegion)
    }

    @Test
    fun testMediaViewerLiveRegions() {
        val file = getLayoutFile("dialog_media_viewer.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

        val zoomBadgeNode = findNodeById(doc, "@+id/tv_viewer_zoom_badge")
        assertNotNull("tv_viewer_zoom_badge should exist in dialog_media_viewer.xml", zoomBadgeNode)
        val zoomLiveRegion = zoomBadgeNode?.attributes?.getNamedItemNS(androidNs, "accessibilityLiveRegion")?.nodeValue
        assertEquals("polite", zoomLiveRegion)

        val statusNode = findNodeById(doc, "@+id/tv_viewer_status")
        assertNotNull("tv_viewer_status should exist in dialog_media_viewer.xml", statusNode)
        val statusLiveRegion = statusNode?.attributes?.getNamedItemNS(androidNs, "accessibilityLiveRegion")?.nodeValue
        assertEquals("polite", statusLiveRegion)
    }

    @Test
    fun testPinnedBannerFocusableAndSelector() {
        val file = getLayoutFile("view_chat_conversation.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val bannerNode = findNodeById(doc, "@+id/layout_pinned_banner")
        assertNotNull("layout_pinned_banner should exist in view_chat_conversation.xml", bannerNode)
        assertEquals("true", bannerNode?.attributes?.getNamedItemNS(androidNs, "focusable")?.nodeValue)
        assertEquals("true", bannerNode?.attributes?.getNamedItemNS(androidNs, "clickable")?.nodeValue)
        assertEquals("@drawable/bg_item_selector", bannerNode?.attributes?.getNamedItemNS(androidNs, "background")?.nodeValue)
        assertEquals("@+id/rv_messages", bannerNode?.attributes?.getNamedItemNS(androidNs, "nextFocusDown")?.nodeValue)
    }

    @Test
    fun testMessageSubElementsFocusable() {
        val file = getLayoutFile("view_message_item.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

        val quoteNode = findNodeById(doc, "@+id/tv_reply_quote")
        assertNotNull("tv_reply_quote should exist in view_message_item.xml", quoteNode)
        assertEquals("true", quoteNode?.attributes?.getNamedItemNS(androidNs, "focusable")?.nodeValue)
        assertEquals("true", quoteNode?.attributes?.getNamedItemNS(androidNs, "clickable")?.nodeValue)

        val rxnNode = findNodeById(doc, "@+id/tv_reactions")
        assertNotNull("tv_reactions should exist in view_message_item.xml", rxnNode)
        assertEquals("true", rxnNode?.attributes?.getNamedItemNS(androidNs, "focusable")?.nodeValue)
        assertEquals("true", rxnNode?.attributes?.getNamedItemNS(androidNs, "clickable")?.nodeValue)
    }

    @Test
    fun testMediaViewerButtonsFocusable() {
        val file = getLayoutFile("dialog_media_viewer.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

        val buttonIds = listOf(
            "@+id/btn_viewer_zoom_in",
            "@+id/btn_viewer_zoom_out",
            "@+id/btn_viewer_reset",
            "@+id/btn_viewer_play_pause",
            "@+id/btn_viewer_rewind",
            "@+id/btn_viewer_fast_forward",
            "@+id/btn_viewer_speed",
            "@+id/btn_viewer_save",
        )

        for (btnId in buttonIds) {
            val node = findNodeById(doc, btnId)
            assertNotNull("$btnId should exist in dialog_media_viewer.xml", node)
            assertEquals("$btnId should be focusable=true", "true", node?.attributes?.getNamedItemNS(androidNs, "focusable")?.nodeValue)
        }
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
        if (result == null) {
            val children = node.childNodes
            for (i in 0 until children.length) {
                val found = findNodeById(children.item(i), targetId)
                if (found != null) {
                    result = found
                    break
                }
            }
        }
        return result
    }
}
