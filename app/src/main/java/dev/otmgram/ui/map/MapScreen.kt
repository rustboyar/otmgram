package dev.otmgram.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otmgram.core.location.MAP_STYLE_URL
import dev.otmgram.ui.chat.liveStatus
import dev.otmgram.ui.chat.rememberNow
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/** Interactive map of a location message; follows live locations as they update. */
@Composable
fun MapScreen(onBack: () -> Unit, viewModel: MapViewModel = hiltViewModel()) {
    val location by viewModel.location.collectAsStateWithLifecycle()
    val mapView = rememberMapViewWithLifecycle()
    var loadedMap by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.setStyle(MAP_STYLE_URL) { style ->
                // The pin is a map layer, so it stays on the spot while the map moves.
                style.addSource(GeoJsonSource(PIN_SOURCE))
                style.addLayer(
                    CircleLayer(PIN_LAYER, PIN_SOURCE).withProperties(
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleColor("#E53935"),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                    ),
                )
                loadedMap = map to style
            }
        }
    }
    // Move the pin, and the camera with it, whenever the location changes.
    var centered by remember { mutableStateOf(false) }
    LaunchedEffect(loadedMap, location) {
        val (map, style) = loadedMap ?: return@LaunchedEffect
        val point = location ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(PIN_SOURCE)?.setGeoJson(Point.fromLngLat(point.longitude, point.latitude))
        val target = LatLng(point.latitude, point.longitude)
        if (centered) {
            map.animateCamera(CameraUpdateFactory.newLatLng(target))
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, ZOOM))
            centered = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize().testTag("map"))
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .safeDrawingPadding()
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .testTag("closeMap"),
        ) { Text("←") }
        location?.live?.let { live ->
            Text(
                liveStatus(live, rememberNow()),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("mapLiveStatus"),
            )
        }
    }
}

/** A MapView that follows the screen's lifecycle, as MapLibre requires. */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    return mapView
}

private const val PIN_SOURCE = "pin"
private const val PIN_LAYER = "pin"
private const val ZOOM = 15.0
