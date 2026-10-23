package dev.otmgram.core.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
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

    /** A fresh fix, or the last known one if none arrives in time; null if location is off. */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    suspend fun current(): Location? {
        val provider = provider() ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                LocationManagerCompat.getCurrentLocation(
                    manager, provider, signal, ContextCompat.getMainExecutor(context),
                ) { continuation.resume(it) }
                continuation.invokeOnCancellation { signal.cancel() }
            }
        } ?: manager.getLastKnownLocation(provider)
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 20_000L
    }
}
