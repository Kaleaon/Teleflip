package com.teleflip.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
