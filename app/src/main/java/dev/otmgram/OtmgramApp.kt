package dev.otmgram

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import dev.otmgram.core.td.TdClient
import dev.otmgram.sync.CheckScheduler
import org.maplibre.android.MapLibre
import javax.inject.Inject

@HiltAndroidApp
class OtmgramApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject
    lateinit var td: TdClient

    @Inject
    lateinit var checkScheduler: CheckScheduler

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        td.start()
        checkScheduler.start()
    }

    /** Images for Coil, including frames of local videos (e.g. picked for sending). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
