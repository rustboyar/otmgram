package dev.otmgram

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import dev.otmgram.core.location.LiveLocationSharing
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.td.TdClient
import dev.otmgram.sync.CheckScheduler
import dev.otmgram.sync.NotificationCenter
import javax.inject.Inject

@HiltAndroidApp
class OtmgramApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject
    lateinit var td: TdClient

    @Inject
    lateinit var checkScheduler: CheckScheduler

    @Inject
    lateinit var notifications: NotificationCenter

    @Inject
    lateinit var liveLocation: LiveLocationSharing

    @Inject
    lateinit var sessionCleanup: SessionCleanup

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i(
            "App",
            "Started ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}${if (BuildConfig.DEBUG) ", debug" else ""}) " +
                "on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        )
        notifications.start() // Before TDLib: it sends the active notifications once, at start.
        td.start()
        checkScheduler.start()
        liveLocation.endLeftovers()
        sessionCleanup.start()
    }

    /** Images for Coil, including frames of local videos (e.g. picked for sending). */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
