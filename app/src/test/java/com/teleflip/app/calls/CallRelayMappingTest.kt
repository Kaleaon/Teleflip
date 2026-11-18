package com.teleflip.app.calls

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRelayMappingTest {
    @Test fun webRtcCredentialsAndStunTurnFlagsArePreservedWithoutReflectorTag() {
        val server = TdApi.CallServer(5L, "1.2.3.4", "::1", 443, TdApi.CallServerTypeWebrtc("user", "secret", true, false))
        val result = NtgCallTransport.rtcServer(server)
        assertEquals("user", result.username)
        assertEquals("secret", result.password)
        assertEquals(443, result.port)
        assertTrue(result.turn)
        assertFalse(result.stun)
        assertNull(result.peer_tag)
    }

    @Test fun telegramReflectorRetainsTcpAndPeerTag() {
        val tag = byteArrayOf(1, 2, 3)
        val server = TdApi.CallServer(6L, "1.2.3.4", "", 443, TdApi.CallServerTypeTelegramReflector(tag, true))
        val result = NtgCallTransport.rtcServer(server)
        assertTrue(result.tcp)
        assertTrue(result.turn)
        assertFalse(result.stun)
        assertArrayEquals(tag, result.peer_tag)
    }
}
