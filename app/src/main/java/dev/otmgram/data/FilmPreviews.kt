package dev.otmgram.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.core.media.FilmLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A photo about to be sent, ready to show in each film: a preview, a small square of it,
 * and its glow from it at the size it's sent at, so what's shown is what goes. Close it when
 * done: that frees the glow (native memory). Its bitmaps are left to the garbage collector
 * rather than recycled, as the screen may still draw one for a frame.
 */
class FilmPreview internal constructor(private val photo: Bitmap, private val square: Bitmap, private val mask: FilmLook.Mask) : AutoCloseable {
    /** The preview in the last film asked for: shown again, after a hold, without developing it again. */
    @Volatile
    private var last: Pair<FilmLook.Stock, Bitmap>? = null

    /** The squares in each film, small (about 100 KB each), kept. */
    private val squares = ConcurrentHashMap<FilmLook.Stock, Bitmap>()

    /** The preview in [film]; as it is for null. Don't change it: it may be shared. */
    suspend fun developed(film: FilmLook.Stock?): Bitmap {
        if (film == null) return photo
        last?.let { (stock, bitmap) -> if (stock == film) return bitmap }
        return develop(photo, film).also { last = film to it }
    }

    /** The small square in [film], or as it is. Don't change it: it's shared. */
    suspend fun thumbnail(film: FilmLook.Stock?): Bitmap {
        if (film == null) return square
        return squares[film] ?: develop(square, film).also { squares[film] = it }
    }

    private suspend fun develop(source: Bitmap, film: FilmLook.Stock): Bitmap = withContext(Dispatchers.Default) {
        source.copy(Bitmap.Config.ARGB_8888, true).also { FilmLook.develop(mask, it, film) }
    }

    override fun close() = mask.close()
}

@Singleton
class FilmPreviews @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** True for photos a film can be applied to: still images, not GIFs. */
    fun canDevelop(uri: Uri): Boolean = context.contentResolver.getType(uri).orEmpty().let { it.startsWith("image/") && it != "image/gif" }

    /** [uri]'s photo ready for previews; null if it isn't a photo or can't be read. */
    suspend fun load(uri: Uri): FilmPreview? = withContext(Dispatchers.Default) {
        if (!canDevelop(uri)) return@withContext null
        try {
            val full = FilmLook.decode(ImageDecoder.createSource(context.contentResolver, uri), FilmLook.MAX_SIDE)
            val mask = FilmLook.mask(full)
            val scale = min(1f, PREVIEW_SIDE.toFloat() / max(full.width, full.height))
            val photo = full.scale((full.width * scale).roundToInt(), (full.height * scale).roundToInt())
            val side = min(photo.width, photo.height)
            val crop = Bitmap.createBitmap(photo, (photo.width - side) / 2, (photo.height - side) / 2, side, side)
            val square = crop.scale(THUMBNAIL_SIDE, THUMBNAIL_SIDE)
            if (crop !== photo && crop !== square) crop.recycle()
            if (photo !== full) full.recycle()
            FilmPreview(photo, square, mask)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        /** About the preview's size on screen. */
        const val PREVIEW_SIDE = 1100
        const val THUMBNAIL_SIDE = 160
    }
}
