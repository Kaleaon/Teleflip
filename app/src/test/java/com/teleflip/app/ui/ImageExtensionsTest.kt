package com.teleflip.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ImageExtensionsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var imageView: ImageView
    private lateinit var textView: TextView
    private lateinit var sampleImagePath: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        imageView = ImageView(context)
        textView = TextView(context)

        // Create a temporary valid PNG image file
        val imageFile = tempFolder.newFile("test_avatar.png")
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        sampleImagePath = imageFile.absolutePath
    }

    @Test
    fun loadCircularAvatarOrFallback_withValidPath_showsImageViewAndHidesTextView() {
        imageView.loadCircularAvatarOrFallback(sampleImagePath, "AB", textView)

        assertEquals(View.VISIBLE, imageView.visibility)
        assertEquals(View.GONE, textView.visibility)
        assertNotNull(imageView.drawable)
    }

    @Test
    fun loadCircularAvatarOrFallback_withNonExistentPath_hidesImageViewAndShowsTextView() {
        val invalidPath = File(tempFolder.root, "non_existent.png").absolutePath

        imageView.loadCircularAvatarOrFallback(invalidPath, "XY", textView)

        assertEquals(View.GONE, imageView.visibility)
        assertEquals(View.VISIBLE, textView.visibility)
        assertEquals("XY", textView.text.toString())
    }

    @Test
    fun loadCircularAvatarOrFallback_withNullPath_hidesImageViewAndShowsTextView() {
        imageView.loadCircularAvatarOrFallback(null, "CD", textView)

        assertEquals(View.GONE, imageView.visibility)
        assertEquals(View.VISIBLE, textView.visibility)
        assertEquals("CD", textView.text.toString())
    }

    @Test
    fun loadCircularAvatarOrFallback_withNullFallbackLetters_handlesNullSafely() {
        imageView.loadCircularAvatarOrFallback(null, null, textView)

        assertEquals(View.GONE, imageView.visibility)
        assertEquals(View.VISIBLE, textView.visibility)
        assertEquals("", textView.text.toString())
    }

    @Test
    @Suppress("DEPRECATION")
    fun loadCircularAvatarOrFallback_withInvalidImageContent_fallsBackSafely() {
        val corruptedFile = tempFolder.newFile("corrupted.png")
        corruptedFile.writeText("Not an image content")
        org.robolectric.shadows.ShadowBitmapFactory.provideWidthAndHeightHints(corruptedFile.absolutePath, -1, -1)

        imageView.loadCircularAvatarOrFallback(corruptedFile.absolutePath, "EF", textView)

        assertEquals(View.GONE, imageView.visibility)
        assertEquals(View.VISIBLE, textView.visibility)
        assertEquals("EF", textView.text.toString())
    }

    @Test
    fun loadBitmapFromFile_withValidPath_showsImageView() {
        imageView.loadBitmapFromFile(sampleImagePath)

        assertEquals(View.VISIBLE, imageView.visibility)
        assertNotNull(imageView.drawable)
    }

    @Test
    fun loadBitmapFromFile_withNonExistentPath_hidesImageView() {
        val invalidPath = File(tempFolder.root, "non_existent.png").absolutePath

        imageView.loadBitmapFromFile(invalidPath)

        assertEquals(View.GONE, imageView.visibility)
    }

    @Test
    fun loadBitmapFromFile_withNullPath_hidesImageView() {
        imageView.loadBitmapFromFile(null)

        assertEquals(View.GONE, imageView.visibility)
    }

    @Test
    @Suppress("DEPRECATION")
    fun loadBitmapFromFile_withCorruptedFile_hidesImageView() {
        val corruptedFile = tempFolder.newFile("corrupted_photo.jpg")
        corruptedFile.writeText("This is invalid image data")
        org.robolectric.shadows.ShadowBitmapFactory.provideWidthAndHeightHints(corruptedFile.absolutePath, -1, -1)

        imageView.loadBitmapFromFile(corruptedFile.absolutePath)

        assertEquals(View.GONE, imageView.visibility)
    }
}
