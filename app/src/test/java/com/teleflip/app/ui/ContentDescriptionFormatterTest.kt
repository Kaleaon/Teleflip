package com.teleflip.app.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ContentDescriptionFormatterTest {
    private val avatarFormatPattern = "%1\$s's avatar"
    private val avatarFallback = "Contact avatar"

    private val photoFormatPattern = "Photo: %1\$s"
    private val photoFallback = "Photo attachment"

    @Test
    fun testFormatAvatarDescription_withValidTitle() {
        val result =
            ContentDescriptionFormatter.formatAvatarDescription(
                title = "Alice Smith",
                formatPattern = avatarFormatPattern,
                fallbackString = avatarFallback,
            )
        assertEquals("Alice Smith's avatar", result)
    }

    @Test
    fun testFormatAvatarDescription_withEmptyOrBlankTitle() {
        val emptyResult =
            ContentDescriptionFormatter.formatAvatarDescription(
                title = "",
                formatPattern = avatarFormatPattern,
                fallbackString = avatarFallback,
            )
        assertEquals(avatarFallback, emptyResult)

        val whitespaceResult =
            ContentDescriptionFormatter.formatAvatarDescription(
                title = "   \n\t  ",
                formatPattern = avatarFormatPattern,
                fallbackString = avatarFallback,
            )
        assertEquals(avatarFallback, whitespaceResult)

        val nullResult =
            ContentDescriptionFormatter.formatAvatarDescription(
                title = null,
                formatPattern = avatarFormatPattern,
                fallbackString = avatarFallback,
            )
        assertEquals(avatarFallback, nullResult)
    }

    @Test
    fun testFormatPhotoDescription_withValidCaption() {
        val result =
            ContentDescriptionFormatter.formatPhotoDescription(
                caption = "Sunset at the beach",
                formatPattern = photoFormatPattern,
                fallbackString = photoFallback,
            )
        assertEquals("Photo: Sunset at the beach", result)
    }

    @Test
    fun testFormatPhotoDescription_withEmptyOrBlankCaption() {
        val emptyResult =
            ContentDescriptionFormatter.formatPhotoDescription(
                caption = "",
                formatPattern = photoFormatPattern,
                fallbackString = photoFallback,
            )
        assertEquals(photoFallback, emptyResult)

        val whitespaceResult =
            ContentDescriptionFormatter.formatPhotoDescription(
                caption = "   ",
                formatPattern = photoFormatPattern,
                fallbackString = photoFallback,
            )
        assertEquals(photoFallback, whitespaceResult)

        val nullResult =
            ContentDescriptionFormatter.formatPhotoDescription(
                caption = null,
                formatPattern = photoFormatPattern,
                fallbackString = photoFallback,
            )
        assertEquals(photoFallback, nullResult)
    }

    @Test
    fun testFormatAvatarDescription_invalidPattern_returnsFallbackSafely() {
        val result =
            ContentDescriptionFormatter.formatAvatarDescription(
                title = "Test User",
                formatPattern = "Invalid pattern %d %s %y",
                fallbackString = avatarFallback,
            )
        assertEquals(avatarFallback, result)
    }

    @Test
    fun testVoiceStateAndZoomFormatting() {
        val context = mock(Context::class.java)
        `when`(context.getString(com.teleflip.app.R.string.cd_voice_state_playing, "1.5x"))
            .thenReturn("Playing at 1.5x speed")
        `when`(context.getString(com.teleflip.app.R.string.cd_voice_state_paused, 12))
            .thenReturn("Paused at 12 seconds")
        `when`(context.getString(com.teleflip.app.R.string.cd_voice_message_format, "Playing", 12))
            .thenReturn("Voice message, Playing, 12 seconds")
        `when`(context.getString(com.teleflip.app.R.string.cd_zoom_scale_format, "1.5x"))
            .thenReturn("Zoom scale 1.5x")

        assertEquals("Playing at 1.5x speed", ContentDescriptionFormatter.getVoiceStateDescription(context, true, "1.5x", 12))
        assertEquals("Paused at 12 seconds", ContentDescriptionFormatter.getVoiceStateDescription(context, false, "1.5x", 12))
        assertEquals("Voice message, Playing, 12 seconds", ContentDescriptionFormatter.getVoiceMessageDescription(context, true, 12))
        assertEquals("Zoom scale 1.5x", ContentDescriptionFormatter.getZoomDescription(context, 1.5f))
    }
}
