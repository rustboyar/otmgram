package dev.otmgram.core.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * tungsten's looks run on a photo, at the sizes the app uses (logging how long they take),
 * and several at once with one mask, as the send preview does.
 */
@RunWith(AndroidJUnit4::class)
class FilmLookTest {

    /** A dusk street: a gradient with a few bright lamps, which glow. */
    private fun photo(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.rgb(30, 40, 70), Color.rgb(200, 140, 90), Shader.TileMode.CLAMP) })
        val lamp = Paint().apply { color = Color.WHITE }
        for (i in 1..5) canvas.drawCircle(width * i / 6f, height * 0.4f, width / 80f, lamp)
    }

    @Test
    fun aLookChangesThePhotoAndTakesThisLong() {
        val full = photo(4000, 3000) // 12 MP, a phone camera's photo.
        val maskStart = SystemClock.elapsedRealtime()
        FilmLook.mask(full).use { mask ->
            val maskMs = SystemClock.elapsedRealtime() - maskStart
            val times = listOf(1100 to 825, 2560 to 1920, 4000 to 3000).map { (w, h) ->
                val copy = Bitmap.createScaledBitmap(full, w, h, true)
                val before = copy.getPixel(w / 6, (h * 0.42f).toInt())
                val start = SystemClock.elapsedRealtime()
                FilmLook.develop(mask, copy, FilmLook.Stock.Cinestill800T)
                val ms = SystemClock.elapsedRealtime() - start
                assertNotEquals(before, copy.getPixel(w / 6, (h * 0.42f).toInt()))
                "${w}x$h: $ms ms"
            }
            Log.i("FilmLookTest", "mask (12 MP): $maskMs ms; 800T at ${times.joinToString()}")
        }
    }

    @Test
    fun severalDevelopAtOnceWithOneMask() {
        val photo = photo(800, 600)
        FilmLook.mask(photo).use { mask ->
            val threads = FilmLook.Stock.entries.map { stock ->
                Thread { FilmLook.develop(mask, Bitmap.createScaledBitmap(photo, 200, 150, true), stock) }.apply { start() }
            }
            threads.forEach(Thread::join)
        }
    }
}
