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
    fun testFormLayoutsUseLabeledInputView() {
        val formLayoutsWithExpectedIds =
            mapOf(
                "view_login.xml" to listOf("@+id/liv_login_input"),
                "dialog_search.xml" to listOf("@+id/liv_search_input"),
                "dialog_create_poll.xml" to
                    listOf(
                        "@+id/liv_poll_question",
                        "@+id/liv_poll_opt1",
                        "@+id/liv_poll_opt2",
                        "@+id/liv_poll_opt3",
                    ),
                "dialog_new_chat.xml" to listOf("@+id/liv_new_chat_input"),
                "dialog_proxy_settings.xml" to
                    listOf(
                        "@+id/liv_proxy_server",
                        "@+id/liv_proxy_port",
                        "@+id/liv_proxy_username",
                        "@+id/liv_proxy_password",
                        "@+id/liv_proxy_secret",
                    ),
                "view_settings.xml" to
                    listOf(
                        "@+id/liv_api_id",
                        "@+id/liv_api_hash",
                    ),
            )

        for ((layoutName, expectedIds) in formLayoutsWithExpectedIds) {
            val file = getLayoutFile(layoutName)
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
            for (id in expectedIds) {
                val node = findNodeById(doc, id)
                assertNotNull("Expected node $id in $layoutName", node)
                assertEquals(
                    "Node $id in $layoutName must be LabeledInputView",
                    "com.teleflip.app.ui.LabeledInputView",
                    node?.nodeName,
                )
            }

            val directEditTexts = findNodesByTagName(doc, "EditText")
            assertEquals("Form layout $layoutName should not contain direct EditText elements", 0, directEditTexts.size)
        }
    }

    private fun findNodesByTagName(
        node: org.w3c.dom.Node,
        tagName: String,
        results: MutableList<org.w3c.dom.Element> = mutableListOf(),
    ): List<org.w3c.dom.Element> {
        if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
            val element = node as org.w3c.dom.Element
            if (element.tagName == tagName || element.nodeName == tagName) {
                results.add(element)
            }
        }
        val children = node.childNodes
        for (i in 0 until children.length) {
            findNodesByTagName(children.item(i), tagName, results)
        }
        return results
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
