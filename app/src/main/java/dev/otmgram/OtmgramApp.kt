package dev.otmgram

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import dev.otmgram.core.td.TdClient
import org.maplibre.android.MapLibre
import javax.inject.Inject

@HiltAndroidApp
class OtmgramApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var td: TdClient

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        td.start()
    }

    /** Images for Coil, including frames of local videos (e.g. picked for sending). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
