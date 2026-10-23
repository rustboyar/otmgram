package dev.otmgram.ui.map

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance between two points in meters (haversine). */
internal fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))
}

/** "340 m away", "2.1 km away", "23 km away". */
internal fun formatDistance(meters: Double): String = when {
    meters < 1_000 -> "${(meters / 10).roundToInt() * 10} m away"
    meters < 10_000 -> "%.1f km away".format(meters / 1_000)
    else -> "${(meters / 1_000).roundToInt()} km away"
}

private const val EARTH_RADIUS_M = 6_371_000.0
