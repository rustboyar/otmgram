package dev.otmgram.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** The device's position from Android's LocationManager; no Google Play services needed. */
@Singleton
class LocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val manager: LocationManager = context.getSystemService(LocationManager::class.java)

    /** Best enabled provider: fused (Android 12+), GPS, then network. */
    fun provider(): String? {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return candidates.firstOrNull { it in manager.allProviders && manager.isProviderEnabled(it) }
    }

    /**
     * A fresh, accurate fix, or the last known one if none arrives in time; null if location
     * is off. High accuracy makes the fused provider use GPS, not just network location.
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun current(): Location? {
        val provider = provider() ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                val executor = ContextCompat.getMainExecutor(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val request = LocationRequest.Builder(0).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
                    manager.getCurrentLocation(provider, request, signal, executor) { continuation.resume(it) }
                } else {
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { continuation.resume(it) }
                }
                continuation.invokeOnCancellation { signal.cancel() }
            }
        } ?: manager.getLastKnownLocation(provider)
    }

    /** The last position any app got, without waiting for a new one; null if none or location is off. */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun lastKnown(): Location? = provider()?.let { manager.getLastKnownLocation(it) }

    /** Accurate position updates, at most every [intervalMs] and after moving [minDistanceMeters]. */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun requestUpdates(intervalMs: Long, minDistanceMeters: Float, listener: LocationListenerCompat): Boolean {
        val provider = provider() ?: return false
        val request = LocationRequestCompat.Builder(intervalMs)
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        LocationManagerCompat.requestLocationUpdates(manager, provider, request, ContextCompat.getMainExecutor(context), listener)
        return true
    }

    // Stopping needs no permission; it must work after the permission was taken back too.
    @SuppressLint("MissingPermission")
    fun removeUpdates(listener: LocationListenerCompat) {
        LocationManagerCompat.removeUpdates(manager, listener)
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 20_000L
    }
}
