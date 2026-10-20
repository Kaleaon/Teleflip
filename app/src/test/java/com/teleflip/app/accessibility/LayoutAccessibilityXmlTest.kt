package com.teleflip.app.accessibility

import android.content.Context
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import com.teleflip.app.ui.SectionHeaderView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class LayoutAccessibilityXmlTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun getLayoutFile(filename: String): File {
        val path = "src/main/res/layout/$filename"
        val file = File(path)
        if (file.exists()) return file
        return File("app/$path")
    }

    @Test
    fun testSectionHeaderViewHeadingSemantics() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val headerView = SectionHeaderView(context)
        assertTrue(
            "SectionHeaderView must be marked as an accessibility heading",
            ViewCompat.isAccessibilityHeading(headerView)
        )
    }

    @Test
    fun testSectionHeaderViewsInLayoutXmlFiles() {
        val layoutFiles = listOf(
            "view_settings.xml",
            "view_profile.xml",
            "dialog_diagnostics.xml",
            "dialog_create_poll.xml",
            "view_login.xml",
            "view_passcode_lock.xml",
            "dialog_new_chat.xml",
            "dialog_search.xml",
            "dialog_options_menu.xml"
        )

        for (filename in layoutFiles) {
            val file = getLayoutFile(filename)
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
            val sectionHeaders = findNodesByTagName(doc, "com.teleflip.app.ui.SectionHeaderView")
            assertTrue(
                "Layout $filename should contain at least one <com.teleflip.app.ui.SectionHeaderView>",
                sectionHeaders.isNotEmpty()
            )
        }
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

    private fun findNodesByTagName(
        node: org.w3c.dom.Node,
        tagName: String,
        result: MutableList<org.w3c.dom.Node> = mutableListOf()
    ): List<org.w3c.dom.Node> {
        if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
            if (node.nodeName == tagName) {
                result.add(node)
            }
        }
        val children = node.childNodes
        for (i in 0 until children.length) {
            findNodesByTagName(children.item(i), tagName, result)
        }
        return result
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

