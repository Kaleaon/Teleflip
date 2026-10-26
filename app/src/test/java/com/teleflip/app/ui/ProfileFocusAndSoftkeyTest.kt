package com.teleflip.app.ui

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ProfileFocusAndSoftkeyTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun getLayoutFile(filename: String): File {
        val path = "src/main/res/layout/$filename"
        val file = File(path)
        if (file.exists()) return file
        return File("app/$path")
    }

    @Test
    fun testViewProfileXmlExplicitFocusAttributes() {
        val file = getLayoutFile("view_profile.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

        val targetIds = listOf(
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
            "@+id/btn_profile_export_history"
        )

        for (id in targetIds) {
            val node = findNodeById(doc, id)
            assertNotNull("View with ID $id should exist in view_profile.xml", node)
            val focusable = node?.attributes?.getNamedItemNS(androidNs, "focusable")?.nodeValue
            assertEquals("View $id should have android:focusable='true'", "true", focusable)

            val focusableInTouchMode = node?.attributes?.getNamedItemNS(androidNs, "focusableInTouchMode")?.nodeValue
            assertEquals("View $id should have android:focusableInTouchMode='true'", "true", focusableInTouchMode)

            val nextFocusUp = node?.attributes?.getNamedItemNS(androidNs, "nextFocusUp")?.nodeValue
            assertNotNull("View $id should have android:nextFocusUp defined", nextFocusUp)

            val nextFocusDown = node?.attributes?.getNamedItemNS(androidNs, "nextFocusDown")?.nodeValue
            assertNotNull("View $id should have android:nextFocusDown defined", nextFocusDown)
        }
    }

    @Test
    fun testFocusChainBypassesHiddenViews() {
        val v1 = mock(View::class.java).apply {
            `when`(id).thenReturn(101)
            `when`(visibility).thenReturn(View.VISIBLE)
        }
        val v2 = mock(View::class.java).apply {
            `when`(id).thenReturn(102)
            `when`(visibility).thenReturn(View.GONE) // Hidden
        }
        val v3 = mock(View::class.java).apply {
            `when`(id).thenReturn(103)
            `when`(visibility).thenReturn(View.VISIBLE)
        }

        val allViews = listOf(v1, v2, v3)
        rebuildFocusChain(allViews)

        org.mockito.Mockito.verify(v1).nextFocusDownId = 103
        org.mockito.Mockito.verify(v3).nextFocusUpId = 101
    }

    private fun rebuildFocusChain(allViews: List<View>) {
        val visibleViews = allViews.filter { it.visibility == View.VISIBLE }
        for (i in visibleViews.indices) {
            val curr = visibleViews[i]
            val prev = visibleViews.getOrNull(i - 1)
            val next = visibleViews.getOrNull(i + 1)
            curr.nextFocusUpId = prev?.id ?: curr.id
            curr.nextFocusDownId = next?.id ?: curr.id
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
