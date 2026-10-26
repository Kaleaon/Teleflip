package com.teleflip.app.calls

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CallTonePlayerTest {
    @Test
    fun startAndStopDoesNotThrow() {
        val player = AndroidCallTonePlayer()
        assertNotNull(player)
        // Should handle gracefully even if ToneGenerator is not fully supported in Robolectric JVM environment
        player.startDialTone()
        player.startDialTone() // Idempotent
        player.stop()
        player.stop() // Idempotent
    }
}
