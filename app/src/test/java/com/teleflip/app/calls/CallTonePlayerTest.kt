package com.teleflip.app.calls

import androidx.test.core.app.ApplicationProvider
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val player = AndroidCallTonePlayer(context)
        assertNotNull(player)
        player.startDialTone()
        player.startDialTone() // Idempotent
        player.stop()
        player.startRingtone()
        player.startRingtone() // Idempotent
        player.stop()
        player.playBusyTone()
        player.stop()
        player.stop() // Idempotent
    }
}
