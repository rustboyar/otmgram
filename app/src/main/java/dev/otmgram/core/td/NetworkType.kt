package dev.otmgram.core.td

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.drinkless.tdlib.TdApi

/** The current network, as TDLib names it. */
internal fun Context.tdNetworkType(): TdApi.NetworkType {
    val manager = getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return TdApi.NetworkTypeNone()
    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TdApi.NetworkTypeWiFi()
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) TdApi.NetworkTypeMobile() else TdApi.NetworkTypeMobileRoaming()
        else -> TdApi.NetworkTypeOther()
    }
}
