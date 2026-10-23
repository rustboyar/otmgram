package dev.otmgram.core.location

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private val cache = LruCache<String, Bitmap>(CACHE_SIZE)

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
        val snapshotter = MapSnapshotter(context, options)
        val bitmap = suspendCancellableCoroutine { continuation ->
            snapshotter.start({ continuation.resume(it.bitmap) }, { continuation.resume(null) })
            continuation.invokeOnCancellation { snapshotter.cancel() }
        }
        bitmap?.let { cache.put(key, it) }
        return bitmap
    }

    private const val CACHE_SIZE = 30
    private const val ZOOM = 10.0
}
