package dev.otmgram.core.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

/**
 * Film looks for photos, from tungsten (github.com/ian-te/tungsten), in Rust (`film/`).
 * The glow around lights is worked out once from the full-size photo ([mask]); any copy of
 * it, a small preview or the photo itself, is then developed with it.
 */
object FilmLook {
    init {
        System.loadLibrary("otmgram_film")
    }

    /** tungsten's stocks, in its order. */
    enum class Stock { Cinestill800T, Cinestill50D, Portra400, Ektar100, Superia400, TriX400 }

    /**
     * The longest side a photo is developed at. Telegram keeps photos at most 2560 px, and its
     * glow is pooled from this size too, so a preview and the photo sent match.
     */
    const val MAX_SIDE = 2560

    /**
     * [source] decoded upright at most [maxSide] px, as a mutable ARGB_8888 bitmap to develop;
     * transparent parts white, as JPEG has none.
     */
    fun decode(source: ImageDecoder.Source, maxSide: Int): Bitmap {
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = minOf(1f, maxSide.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
        if (!decoded.hasAlpha() && decoded.config == Bitmap.Config.ARGB_8888) return decoded
        return createBitmap(decoded.width, decoded.height).applyCanvas {
            drawColor(Color.WHITE)
            drawBitmap(decoded, 0f, 0f, null)
        }.also { decoded.recycle() }
    }

    /**
     * The glow's source, from [photo] at full size (ARGB_8888); [close] it when done. One
     * develops with it at a time: it caches its glow without locking, so two at once crashed.
     * Closed, it develops nothing, so a preview still developing when it's closed is safe.
     */
    class Mask internal constructor(private val handle: Long) : AutoCloseable {
        private var closed = false

        @Synchronized
        internal fun develop(bitmap: Bitmap, stock: Stock): Boolean = closed || nativeDevelop(handle, bitmap, stock.ordinal)

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            nativeFreeMask(handle)
        }
    }

    fun mask(photo: Bitmap): Mask = Mask(nativeMask(photo).also { require(it != 0L) { "not an ARGB_8888 bitmap" } })

    /** Develops [bitmap] (mutable, ARGB_8888) in place with [stock], its glow from [mask]. */
    fun develop(mask: Mask, bitmap: Bitmap, stock: Stock) {
        check(mask.develop(bitmap, stock)) { "not an ARGB_8888 bitmap" }
    }

    @JvmStatic private external fun nativeMask(bitmap: Bitmap): Long

    @JvmStatic private external fun nativeFreeMask(mask: Long)

    @JvmStatic private external fun nativeDevelop(mask: Long, bitmap: Bitmap, stock: Int): Boolean
}
