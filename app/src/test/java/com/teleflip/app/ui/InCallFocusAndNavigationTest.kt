package com.teleflip.app.ui

import android.view.View
import android.widget.Button
import com.teleflip.app.MainActivity
import com.teleflip.app.R
import com.teleflip.app.model.ActiveCall
import com.teleflip.app.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class InCallFocusAndNavigationTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private lateinit var activity: MainActivity

    private fun getLayoutFile(filename: String): File {
        val path = "src/main/res/layout/$filename"
        val file = File(path)
        if (file.exists()) return file
        return File("app/$path")
    }

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    @Test
    fun testInCallLayoutXmlFocusAttributes() {
        val file = getLayoutFile("view_in_call.xml")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)

        val acceptNode = findNodeById(doc, "@+id/btn_call_accept")
        assertNotNull("btn_call_accept should exist", acceptNode)
        assertEquals("true", getAttr(acceptNode, "focusable"))
        assertEquals("true", getAttr(acceptNode, "focusableInTouchMode"))
        assertEquals("@+id/btn_call_end", getAttr(acceptNode, "nextFocusUp"))
        assertEquals("@+id/btn_call_mute", getAttr(acceptNode, "nextFocusDown"))
        assertEquals("@+id/btn_call_accept", getAttr(acceptNode, "nextFocusLeft"))
        assertEquals("@+id/btn_call_accept", getAttr(acceptNode, "nextFocusRight"))

        val muteNode = findNodeById(doc, "@+id/btn_call_mute")
        assertNotNull("btn_call_mute should exist", muteNode)
        assertEquals("true", getAttr(muteNode, "focusable"))
        assertEquals("true", getAttr(muteNode, "focusableInTouchMode"))
        assertEquals("@+id/btn_call_accept", getAttr(muteNode, "nextFocusUp"))
        assertEquals("@+id/btn_call_end", getAttr(muteNode, "nextFocusDown"))
        assertEquals("@+id/btn_call_speaker", getAttr(muteNode, "nextFocusLeft"))
        assertEquals("@+id/btn_call_speaker", getAttr(muteNode, "nextFocusRight"))

        val speakerNode = findNodeById(doc, "@+id/btn_call_speaker")
        assertNotNull("btn_call_speaker should exist", speakerNode)
        assertEquals("true", getAttr(speakerNode, "focusable"))
        assertEquals("true", getAttr(speakerNode, "focusableInTouchMode"))
        assertEquals("@+id/btn_call_accept", getAttr(speakerNode, "nextFocusUp"))
        assertEquals("@+id/btn_call_end", getAttr(speakerNode, "nextFocusDown"))
        assertEquals("@+id/btn_call_mute", getAttr(speakerNode, "nextFocusLeft"))
        assertEquals("@+id/btn_call_mute", getAttr(speakerNode, "nextFocusRight"))

        val endNode = findNodeById(doc, "@+id/btn_call_end")
        assertNotNull("btn_call_end should exist", endNode)
        assertEquals("true", getAttr(endNode, "focusable"))
        assertEquals("true", getAttr(endNode, "focusableInTouchMode"))
        assertEquals("@+id/btn_call_mute", getAttr(endNode, "nextFocusUp"))
        assertEquals("@+id/btn_call_accept", getAttr(endNode, "nextFocusDown"))
        assertEquals("@+id/btn_call_end", getAttr(endNode, "nextFocusLeft"))
        assertEquals("@+id/btn_call_end", getAttr(endNode, "nextFocusRight"))
    }

    @Test
    fun testIncomingPendingCallFocusesAcceptButton() {
        val incomingCall = ActiveCall(
            callId = 100,
            userId = 200L,
            userName = "Alice",
            isOutgoing = false,
            state = CallState.PENDING
        )

        val showInCallScreenMethod = MainActivity::class.java.getDeclaredMethod("showInCallScreen", ActiveCall::class.java)
        showInCallScreenMethod.isAccessible = true
        showInCallScreenMethod.invoke(activity, incomingCall)

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val btnAccept = activity.findViewById<Button>(R.id.btn_call_accept)
        assertNotNull("btn_call_accept should be in activity view hierarchy", btnAccept)
        assertEquals(View.VISIBLE, btnAccept.visibility)
        assertTrue("btn_call_accept should be focused for incoming pending calls", btnAccept.isFocused)
    }

    @Test
    fun testActiveCallFocusesMuteButton() {
        val activeCall = ActiveCall(
            callId = 101,
            userId = 201L,
            userName = "Bob",
            isOutgoing = true,
            state = CallState.READY
        )

        val showInCallScreenMethod = MainActivity::class.java.getDeclaredMethod("showInCallScreen", ActiveCall::class.java)
        showInCallScreenMethod.isAccessible = true
        showInCallScreenMethod.invoke(activity, activeCall)

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val btnAccept = activity.findViewById<Button>(R.id.btn_call_accept)
        val btnMute = activity.findViewById<Button>(R.id.btn_call_mute)
        assertNotNull("btn_call_mute should exist", btnMute)
        assertEquals(View.GONE, btnAccept.visibility)
        assertTrue("btn_call_mute should be focused for active call screens", btnMute.isFocused)
    }

    @Test
    fun testPendingToActiveCallTransitionTransfersFocusToMuteButton() {
        val pendingCall = ActiveCall(
            callId = 102,
            userId = 202L,
            userName = "Charlie",
            isOutgoing = false,
            state = CallState.PENDING
        )

        val showInCallScreenMethod = MainActivity::class.java.getDeclaredMethod("showInCallScreen", ActiveCall::class.java)
        showInCallScreenMethod.isAccessible = true
        showInCallScreenMethod.invoke(activity, pendingCall)

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val btnAccept = activity.findViewById<Button>(R.id.btn_call_accept)
        val btnMute = activity.findViewById<Button>(R.id.btn_call_mute)
        assertTrue("btn_call_accept should initially be focused", btnAccept.isFocused)

        val connectedCall = pendingCall.copy(state = CallState.READY)
        val updateInCallUIMethod = MainActivity::class.java.getDeclaredMethod("updateInCallUI", ActiveCall::class.java)
        updateInCallUIMethod.isAccessible = true
        updateInCallUIMethod.invoke(activity, connectedCall)

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(View.GONE, btnAccept.visibility)
        assertTrue("btn_call_mute should receive focus after accept button becomes GONE", btnMute.isFocused)
    }

    @Test
    fun testDpadCenterKeyTriggersClickOnFocusedButton() {
        val activeCall = ActiveCall(
            callId = 103,
            userId = 203L,
            userName = "Dave",
            isOutgoing = true,
            state = CallState.READY
        )

        val showInCallScreenMethod = MainActivity::class.java.getDeclaredMethod("showInCallScreen", ActiveCall::class.java)
        showInCallScreenMethod.isAccessible = true
        showInCallScreenMethod.invoke(activity, activeCall)

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val btnMute = activity.findViewById<Button>(R.id.btn_call_mute)
        assertTrue("btn_call_mute should be focused", btnMute.isFocused)

        var clicked = false
        btnMute.setOnClickListener { clicked = true }

        val centerKeyEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        val handled = activity.dispatchKeyEvent(centerKeyEvent)

        assertTrue("dispatchKeyEvent should consume DPAD_CENTER event for focused button", handled)
        assertTrue("Focused button click listener should be invoked on DPAD_CENTER press", clicked)
    }

    private fun getAttr(node: org.w3c.dom.Node?, attrName: String): String? {
        return node?.attributes?.getNamedItemNS(androidNs, attrName)?.nodeValue
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
