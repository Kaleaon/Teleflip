package com.teleflip.app.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceRecorderPlayerTest {
    @Test
    fun testVoicePlayerInitialStateAndSafeStop() {
        val player = VoicePlayer(dummyContext())
        assertFalse("VoicePlayer should not be playing initially", player.isPlayingPath("/fake/path.m4a"))

        // Ensure safe stop on non-playing instance does not throw exceptions
        player.stop()
        assertFalse("VoicePlayer should remain idle after stop", player.isPlayingPath("/fake/path.m4a"))
    }

    @Test
    fun testVoiceRecorderInitialStateAndSafeStop() {
        val recorder = VoiceRecorder(dummyContext())
        assertFalse("VoiceRecorder should not be recording initially", recorder.isRecording())

        // Ensure stopRecording returns null when not active
        val recording = recorder.stopRecording()
        assertNull("stopRecording should return null when not recording", recording)

        // Ensure cancelRecording on idle recorder executes without errors
        recorder.cancelRecording()
        assertFalse("VoiceRecorder should remain idle after cancel", recorder.isRecording())
    }

    private fun dummyContext(): android.content.Context {
        return org.mockito.Mockito.mock(android.content.Context::class.java)
    }
}
