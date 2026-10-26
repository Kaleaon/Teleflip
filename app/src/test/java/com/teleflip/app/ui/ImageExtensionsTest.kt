package com.teleflip.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
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
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ImageExtensionsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var activity: Activity
    private lateinit var imageView: ImageView
    private lateinit var textView: TextView
    private lateinit var sampleImagePath: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val layout = LinearLayout(activity)
        imageView = ImageView(activity)
        textView = TextView(activity)
        layout.addView(imageView)
        layout.addView(textView)
        activity.setContentView(layout)

        val mainDispatcher = kotlinx.coroutines.Dispatchers.Main
        val imageLoader =
            coil.ImageLoader.Builder(activity)
                .components {
                    add(
                        coil.intercept.Interceptor { chain ->
                            val data = chain.request.data
                            val isCorrupted =
                                data is File && (
                                    data.name.contains("corrupted") || (
                                        data.exists() && data.length() < 100 &&
                                            runCatching { data.readText() }.getOrDefault("").let {
                                                it.contains("Not an image") || it.contains("invalid")
                                            }
                                    )
                                )
                            if (isCorrupted) {
                                throw java.io.IOException("Corrupted image file")
                            }
                            chain.proceed(chain.request)
                        },
                    )
                }
                .dispatcher(mainDispatcher)
                .interceptorDispatcher(mainDispatcher)
                .fetcherDispatcher(mainDispatcher)
                .decoderDispatcher(mainDispatcher)
                .build()
        coil.Coil.setImageLoader(imageLoader)

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
        ShadowLooper.idleMainLooper()

        assertEquals(View.VISIBLE, imageView.visibility)
        assertEquals(View.GONE, textView.visibility)
        assertNotNull(imageView.drawable)
    }

    @Test
    fun loadCircularAvatarOrFallback_withNonExistentPath_hidesImageViewAndShowsTextView() {
        val invalidPath = File(tempFolder.root, "non_existent.png").absolutePath

        imageView.loadCircularAvatarOrFallback(invalidPath, "XY", textView)
        ShadowLooper.idleMainLooper()

        assertEquals(View.GONE, imageView.visibility)
        assertEquals(View.VISIBLE, textView.visibility)
        assertEquals("XY", textView.text.toString())
    }

    @Test
    fun loadCircularAvatarOrFallback_withNullPath_hidesImageViewAndShowsTextView() {
        imageView.loadCircularAvatarOrFallback(null, "CD", textView)
        ShadowLooper.idleMainLooper()

        assertEquals(View.GONE, imageView.visibility)
        assertEquals(View.VISIBLE, textView.visibility)
        assertEquals("CD", textView.text.toString())
    }

    @Test
    fun loadCircularAvatarOrFallback_withNullFallbackLetters_handlesNullSafely() {
        imageView.loadCircularAvatarOrFallback(null, null, textView)
        ShadowLooper.idleMainLooper()

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
        ShadowLooper.idleMainLooper()

        assertEquals(View.GONE, imageView.visibility)
        assertEquals(View.VISIBLE, textView.visibility)
        assertEquals("EF", textView.text.toString())
    }

    @Test
    fun calculateInSampleSize_withLargeImage_calculatesPowerOfTwo() {
        val options =
            android.graphics.BitmapFactory.Options().apply {
                outWidth = 2000
                outHeight = 1000
            }
        val sampleSize = calculateInSampleSize(options, 500, 500)
        assertEquals(2, sampleSize)
    }

    @Test
    fun calculateInSampleSize_withSmallImage_returnsOne() {
        val options =
            android.graphics.BitmapFactory.Options().apply {
                outWidth = 400
                outHeight = 300
            }
        val sampleSize = calculateInSampleSize(options, 800, 600)
        assertEquals(1, sampleSize)
    }

    @Test
    fun calculateInSampleSize_withZeroOrNegativeTarget_returnsOne() {
        val options =
            android.graphics.BitmapFactory.Options().apply {
                outWidth = 1000
                outHeight = 1000
            }
        assertEquals(1, calculateInSampleSize(options, 0, 500))
        assertEquals(1, calculateInSampleSize(options, 500, -10))
    }

    @Test
    fun decodeSampledBitmapFromFile_withValidPath_returnsBitmap() {
        val bmp = decodeSampledBitmapFromFile(sampleImagePath, 100, 100)
        assertNotNull(bmp)
    }

    @Test
    fun decodeSampledBitmapFromFile_withNonExistentPath_returnsNull() {
        val invalidPath = File(tempFolder.root, "non_existent_image.png").absolutePath
        val bmp = decodeSampledBitmapFromFile(invalidPath, 100, 100)
        org.junit.Assert.assertNull(bmp)
    }
}
