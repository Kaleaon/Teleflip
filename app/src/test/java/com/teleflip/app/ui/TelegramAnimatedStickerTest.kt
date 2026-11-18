package com.teleflip.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.SourceResult
import com.airbnb.lottie.LottieDrawable
import com.teleflip.app.media.TgsDecoder
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class TelegramAnimatedStickerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun tgsStickerDecodesAsAnAnimatedDrawable() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val file = folder.newFile("animation.tgs")
            val json = """{"v":"5.5.7","fr":30,"ip":0,"op":30,"w":64,"h":64,"nm":"Sticker","ddd":0,"assets":[],"layers":[]}"""
            GZIPOutputStream(file.outputStream()).use { it.write(json.toByteArray()) }
            ImageSource(file.source().buffer(), context).use { source ->
                val result = TgsDecoder(SourceResult(source, "application/x-tgsticker", DataSource.DISK)).decode()
                val drawable = result.drawable
                assertTrue(drawable is LottieDrawable)
                assertEquals(64, drawable.intrinsicWidth)
                assertTrue((drawable as LottieDrawable).composition.duration > 0)
            }
        }
}
