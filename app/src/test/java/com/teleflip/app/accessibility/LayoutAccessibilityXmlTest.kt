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

    private fun findNodeById(
        node: org.w3c.dom.Node,
        targetId: String,
    ): org.w3c.dom.Node? {
        if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
            val element = node as org.w3c.dom.Element
            val idAttr = element.getAttributeNS(androidNs, "id")
            if (idAttr == targetId || idAttr == targetId.replace("@+id/", "@id/")) {
                return element
            }
        }
        val children = node.childNodes
        for (i in 0 until children.length) {
            val found = findNodeById(children.item(i), targetId)
            if (found != null) return found
        }
        return null
    }
}
