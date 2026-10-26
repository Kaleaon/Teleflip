package com.teleflip.app.telegram

import android.content.Context
import android.content.SharedPreferences
import com.teleflip.app.model.SessionModel
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class TelegramSessionTest {
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockClient: Client

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)
        mockClient = mock(Client::class.java)

        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor)
    }

    @Test
    fun testTerminateSessionWhenDisconnected() {
        val manager = TelegramManager(mockContext)
        var resultOk: Boolean? = null
        var resultError: String? = null

        manager.terminateSession(12345L) { ok, err ->
            resultOk = ok
            resultError = err
        }

        assertEquals(false, resultOk)
        assertEquals("Not connected", resultError)
    }

    @Test
    fun testTerminateSessionSuccess() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        var resultOk: Boolean? = null
        var resultError: String? = "initial"

        manager.terminateSession(12345L) { ok, err ->
            resultOk = ok
            resultError = err
        }

        val functionCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        val handlerCaptor = ArgumentCaptor.forClass(Client.ResultHandler::class.java)
        verify(mockClient, timeout(1000)).send(functionCaptor.capture(), handlerCaptor.capture())

        val query = functionCaptor.value
        assertTrue("Query should be instance of TerminateSession", query is TdApi.TerminateSession)
        val termQuery = query as TdApi.TerminateSession
        assertEquals(12345L, termQuery.sessionId)

        handlerCaptor.value.onResult(TdApi.Ok())
        ShadowLooper.idleMainLooper()

        assertEquals(true, resultOk)
        assertNull(resultError)
    }

    @Test
    fun testTerminateSessionError() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        var resultOk: Boolean? = null
        var resultError: String? = null

        manager.terminateSession(67890L) { ok, err ->
            resultOk = ok
            resultError = err
        }

        val functionCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        val handlerCaptor = ArgumentCaptor.forClass(Client.ResultHandler::class.java)
        verify(mockClient, timeout(1000)).send(functionCaptor.capture(), handlerCaptor.capture())

        val termQuery = functionCaptor.value as TdApi.TerminateSession
        assertEquals(67890L, termQuery.sessionId)

        handlerCaptor.value.onResult(TdApi.Error(400, "SESSION_INVALID"))
        ShadowLooper.idleMainLooper()

        assertEquals(false, resultOk)
        assertEquals("SESSION_INVALID", resultError)
    }

    @Test
    fun testTerminateSessionUnexpectedObject() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        var resultOk: Boolean? = null
        var resultError: String? = null

        manager.terminateSession(11111L) { ok, err ->
            resultOk = ok
            resultError = err
        }

        val functionCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        val handlerCaptor = ArgumentCaptor.forClass(Client.ResultHandler::class.java)
        verify(mockClient, timeout(1000)).send(functionCaptor.capture(), handlerCaptor.capture())

        handlerCaptor.value.onResult(TdApi.UpdateOption())
        ShadowLooper.idleMainLooper()

        assertEquals(false, resultOk)
        assertEquals("Failed to terminate session", resultError)
    }

    @Test
    fun testLoadActiveSessions() {
        val manager = TelegramManager(mockContext)
        manager.setTdClientForTesting(mockClient)

        var loadedSessions: List<SessionModel>? = null
        manager.loadActiveSessions { sessions ->
            loadedSessions = sessions
        }

        val functionCaptor = ArgumentCaptor.forClass(TdApi.Function::class.java)
        val handlerCaptor = ArgumentCaptor.forClass(Client.ResultHandler::class.java)
        verify(mockClient, timeout(1000)).send(functionCaptor.capture(), handlerCaptor.capture())

        assertTrue(functionCaptor.value is TdApi.GetActiveSessions)

        val session1 = TdApi.Session().apply {
            id = 101L
            isCurrent = true
            deviceModel = "Linux"
            platform = "Android"
            systemVersion = "14.0"
            applicationName = "Teleflip"
            applicationVersion = "1.0"
            lastActiveDate = 1700000
            ipAddress = "127.0.0.1"
            location = "US"
        }
        val session2 = TdApi.Session().apply {
            id = 102L
            isCurrent = false
            deviceModel = "Windows"
            platform = "Desktop"
            systemVersion = "11.0"
            applicationName = "Teleflip Desktop"
            applicationVersion = "1.0"
            lastActiveDate = 1690000
            ipAddress = "192.168.1.1"
            location = "US"
        }
        val sessionsResponse = TdApi.Sessions().apply {
            sessions = arrayOf(session1, session2)
        }

        handlerCaptor.value.onResult(sessionsResponse)
        ShadowLooper.idleMainLooper()

        assertEquals(2, loadedSessions?.size)
        assertEquals(101L, loadedSessions!![0].id)
        assertTrue(loadedSessions!![0].isCurrent)
        assertEquals(102L, loadedSessions!![1].id)
        assertFalse(loadedSessions!![1].isCurrent)
    }
}
