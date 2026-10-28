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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A photo about to be sent, ready to show in each film: a preview, a small square of it,
 * and its glow from it at the size it's sent at, so what's shown is what goes. Close it when
 * done.
 */
class FilmPreview internal constructor(private val photo: Bitmap, private val square: Bitmap, private val mask: FilmLook.Mask) : AutoCloseable {
    /** The preview in [film], or as it is for null. A new bitmap each time. */
    suspend fun developed(film: FilmLook.Stock?): Bitmap = develop(photo, film)

    /** The small square in [film], or as it is. */
    suspend fun thumbnail(film: FilmLook.Stock?): Bitmap = develop(square, film)

    private suspend fun develop(source: Bitmap, film: FilmLook.Stock?): Bitmap = withContext(Dispatchers.Default) {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        if (film != null) FilmLook.develop(mask, copy, film)
        copy
    }

    override fun close() {
        mask.close()
        photo.recycle()
        square.recycle()
    }
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
