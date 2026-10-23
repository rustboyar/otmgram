package dev.otmgram.ui.map

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.SoftButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import dev.otmgram.core.location.MapSnapshots
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
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) viewModel.locateMe()
    }
    var loadedMap by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.setStyle(MAP_STYLE_URL) { style ->
                // The pin is a map layer, so it stays on the spot while the map moves.
                style.addSource(GeoJsonSource(PIN_SOURCE))
                style.addLayer(
                    CircleLayer(PIN_LAYER, PIN_SOURCE).withProperties(
                        PropertyFactory.circleRadius(9f),
                        PropertyFactory.circleColor("#4A6B80"),
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
        IconBtn(
            OtmIcons.Back, "Back", onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(12.dp)
                .shadow(4.dp, CircleShape),
            background = Otm.Sheet,
            tag = "closeMap",
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Otm.Sheet, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val sender = viewModel.sender
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                sender?.let { ChatAvatar(it.id, it.name, it.photoFileId, it.minithumbnail) }
                Column(Modifier.weight(1f)) {
                    Text(sender?.name.orEmpty(), style = OtmType.Name, color = Otm.Ink)
                    val distance = location?.let { point ->
                        viewModel.myLocation?.takeIf { !viewModel.isMine }?.let { formatDistance(distanceMeters(it.latitude, it.longitude, point.latitude, point.longitude)) }
                    }
                    Text(
                        listOfNotNull(location?.live?.let { liveStatus(it, rememberNow()) } ?: "Location", distance).joinToString(" · "),
                        style = OtmType.Secondary,
                        color = if (location?.live != null) Otm.SlateDeep else Otm.InkSoft,
                        modifier = Modifier.testTag("mapLiveStatus"),
                    )
                }
            }
            location?.let { point ->
                SoftButton(
                    "Open in another app",
                    onClick = { openInMaps(context, point.latitude, point.longitude, sender?.name) },
                    modifier = Modifier.fillMaxWidth().testTag("openInMaps"),
                )
            }
        }
    }
}

/** Hands the point to whichever maps app is installed. */
private fun openInMaps(context: Context, latitude: Double, longitude: Double, label: String?) {
    val query = "$latitude,$longitude" + (label?.let { "(${Uri.encode(it)})" } ?: "")
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$query"))
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show()
    }
}

/** A MapView that follows the screen's lifecycle, as MapLibre requires. */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapSnapshots.ensureMapEngine(context)
        MapView(context).apply { onCreate(null) }
    }
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
