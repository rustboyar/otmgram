package dev.otmgram

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.otmgram.core.td.TdClient
import org.maplibre.android.MapLibre
import javax.inject.Inject

@HiltAndroidApp
class OtmgramApp : Application() {

    @Inject
    lateinit var td: TdClient

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        td.start()
    }
}
