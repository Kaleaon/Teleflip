package com.teleflip.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.teleflip.app.R
import com.teleflip.app.model.MediaType
import com.teleflip.app.model.MessageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaViewerDialogTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var samplePhotoFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        samplePhotoFile = tempFolder.newFile("sample_photo.jpg")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        FileOutputStream(samplePhotoFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
    }

    @Test
    fun setupPhoto_withValidFile_loadsBitmapAsynchronously() {
        val message = MessageModel(
            id = 1,
            chatId = 100,
            senderName = "Test Sender",
            text = "Photo Test",
            time = "10:00 AM",
            isOutbound = false,
            mediaType = MediaType.PHOTO,
            mediaFilePath = samplePhotoFile.absolutePath,
            mediaFileId = 1234
        )

        val dialog = MediaViewerDialog(
            context = context,
            message = message,
            onSaveMedia = {},
            onRequestDownload = {}
        )

        dialog.show()

        val ivPhoto = dialog.findViewById<ImageView>(R.id.iv_viewer_photo)
        val tvStatus = dialog.findViewById<TextView>(R.id.tv_viewer_status)

        // Execute background coroutine tasks and main looper tasks
        val startTime = System.currentTimeMillis()
        while (ivPhoto.visibility != View.VISIBLE && System.currentTimeMillis() - startTime < 3000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertEquals(View.VISIBLE, ivPhoto.visibility)
        assertEquals(View.GONE, tvStatus.visibility)
        assertNotNull(ivPhoto.drawable)

        dialog.dismiss()
    }

    @Test
    fun setupPhoto_withMissingFile_showsDownloadingStatus() {
        val missingPath = File(tempFolder.root, "missing_photo.jpg").absolutePath
        val message = MessageModel(
            id = 2,
            chatId = 100,
            senderName = "Test Sender",
            text = "Missing Photo",
            time = "10:00 AM",
            isOutbound = false,
            mediaType = MediaType.PHOTO,
            mediaFilePath = missingPath,
            mediaFileId = 5678
        )

        var downloadFileId = 0
        val dialog = MediaViewerDialog(
            context = context,
            message = message,
            onSaveMedia = {},
            onRequestDownload = { downloadFileId = it }
        )

        dialog.show()

        val ivPhoto = dialog.findViewById<ImageView>(R.id.iv_viewer_photo)
        val tvStatus = dialog.findViewById<TextView>(R.id.tv_viewer_status)

        assertEquals(View.GONE, ivPhoto.visibility)
        assertEquals(View.VISIBLE, tvStatus.visibility)
        assertEquals(5678, downloadFileId)

        dialog.dismiss()
    }

    @Test
    fun dialogStop_cancelsBackgroundDecodingJobsSafely() {
        val message = MessageModel(
            id = 3,
            chatId = 100,
            senderName = "Test Sender",
            text = "Photo Cancel Test",
            time = "10:00 AM",
            isOutbound = false,
            mediaType = MediaType.PHOTO,
            mediaFilePath = samplePhotoFile.absolutePath,
            mediaFileId = 999
        )

        val dialog = MediaViewerDialog(
            context = context,
            message = message,
            onSaveMedia = {},
            onRequestDownload = {}
        )

        dialog.show()
        // Immediately dismiss dialog before background coroutines finish
        dialog.dismiss()

        shadowOf(android.os.Looper.getMainLooper()).idle()
        // Should close cleanly without crashing or updating dismissed views
    }

    @Test
    fun generateScreenshot_savesDialogViewToTmp() {
        val message = MessageModel(
            id = 4,
            chatId = 100,
            senderName = "Test Sender",
            text = "Photo Viewer",
            time = "10:00 AM",
            isOutbound = false,
            mediaType = MediaType.PHOTO,
            mediaFilePath = samplePhotoFile.absolutePath,
            mediaFileId = 1111
        )

        val dialog = MediaViewerDialog(
            context = context,
            message = message,
            onSaveMedia = {},
            onRequestDownload = {}
        )

        dialog.show()
        val ivPhoto = dialog.findViewById<ImageView>(R.id.iv_viewer_photo)
        val startTime = System.currentTimeMillis()
        while (ivPhoto.visibility != View.VISIBLE && System.currentTimeMillis() - startTime < 3000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        val decorView = dialog.window?.decorView
        if (decorView != null) {
            decorView.measure(
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
            )
            decorView.layout(0, 0, 480, 800)
            val bitmap = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            decorView.draw(canvas)

            val file = File("/tmp/media_viewer_dialog.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        dialog.dismiss()
    }
}
