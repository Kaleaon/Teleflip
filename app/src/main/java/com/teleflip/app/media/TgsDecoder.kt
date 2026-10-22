package com.teleflip.app.media

import android.animation.ValueAnimator
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import okio.ByteString.Companion.toByteString
import java.util.zip.GZIPInputStream

/** Telegram's TGS stickers are gzip-compressed Lottie compositions. */
class TgsDecoder(private val result: SourceResult) : Decoder {
    override suspend fun decode(): DecodeResult {
        val composition =
            GZIPInputStream(result.source.source().inputStream()).use {
                LottieCompositionFactory.fromJsonInputStreamSync(it, null).value
            } ?: error("Unable to decode Telegram animated sticker")
        val drawable =
            LottieDrawable().apply {
                setComposition(composition)
                repeatCount = ValueAnimator.INFINITE
            }
        return DecodeResult(drawable, false)
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val signature = byteArrayOf(0x1f, 0x8b.toByte()).toByteString()
            return if (result.source.source().rangeEquals(0, signature)) TgsDecoder(result) else null
        }
    }
}
