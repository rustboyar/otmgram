package dev.otmgram.core.td

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.drinkless.tdlib.TdApi

/** The current network, as TDLib names it. */
internal fun Context.tdNetworkType(): TdApi.NetworkType {
    val manager = getSystemService(ConnectivityManager::class.java)
    return manager.getNetworkCapabilities(manager.activeNetwork).tdNetworkType()
}

/** A network with these capabilities, as TDLib names it; none for null. */
internal fun NetworkCapabilities?.tdNetworkType(): TdApi.NetworkType = when {
    this == null -> TdApi.NetworkTypeNone()
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TdApi.NetworkTypeWiFi()
    hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
        if (hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) TdApi.NetworkTypeMobile() else TdApi.NetworkTypeMobileRoaming()
    else -> TdApi.NetworkTypeOther()
}
