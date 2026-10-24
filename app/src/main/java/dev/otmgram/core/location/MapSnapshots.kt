package dev.otmgram.core.location

import org.maplibre.android.offline.OfflineManager
import dev.otmgram.core.log.AppLog
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume

/** Map style: OpenFreeMap vector tiles, free and without an API key. */
const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Static map images for location bubbles, which are much lighter than live maps.
 *
 * Previews are coarse on purpose: at [ZOOM] a tile covers about 25 km, so the tile server
 * learns only the rough area of a shared place. The pin is drawn on the phone, at the exact
 * spot; detailed tiles load only when the user opens the full map.
 */
object MapSnapshots {
    /** Bounded by memory, not count: a preview is about 1 MB at phone densities. */
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * Loads the map engine on first use, not at app start; most starts never show a map.
     * Must be called on the main thread before creating a map view or snapshotter.
     */
    fun ensureMapEngine(context: Context) {
        MapLibre.getInstance(context.applicationContext)
    }

    /** Forgets downloaded map tiles (which show where locations in chats were). Main thread. */
    fun clearTiles(context: Context) {
        ensureMapEngine(context)
        cache.evictAll()
        OfflineManager.getInstance(context.applicationContext).clearAmbientCache(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = Unit

            override fun onError(message: String) = AppLog.w("MapSnapshots", "Clearing map tiles failed: $message")
        })
    }

    /**
     * Map centered on the point; size in dp, rendered at [density]. Null if rendering
     * failed, e.g. offline. Must be called on the main thread.
     */
    suspend fun get(
        context: Context,
        latitude: Double,
        longitude: Double,
        widthDp: Int,
        heightDp: Int,
        density: Float,
    ): Bitmap? {
        val key = "%.5f,%.5f,%dx%d".format(latitude, longitude, widthDp, heightDp)
        cache.get(key)?.let { return it }
        val options = MapSnapshotter.Options(widthDp, heightDp)
            .withStyleBuilder(Style.Builder().fromUri(MAP_STYLE_URL))
            .withPixelRatio(density)
            .withCameraPosition(CameraPosition.Builder().target(LatLng(latitude, longitude)).zoom(ZOOM).build())
        ensureMapEngine(context)
        val snapshotter = MapSnapshotter(context, options)
        val bitmap = suspendCancellableCoroutine { continuation ->
            snapshotter.start({ continuation.resume(it.bitmap) }, { continuation.resume(null) })
            continuation.invokeOnCancellation { snapshotter.cancel() }
        }
        bitmap?.let { cache.put(key, it) }
        return bitmap
    }

    private const val CACHE_BYTES = 12 * 1024 * 1024
    private const val ZOOM = 10.0
}
