package com.securevox.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.securevox.app.whisper.NetworkState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Reads the current network. Uses ACCESS_NETWORK_STATE (a normal permission, no prompt). */
object Connectivity {

    fun state(context: Context): NetworkState {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return NetworkState.UNKNOWN
            val network = cm.activeNetwork ?: return NetworkState.OFFLINE
            val caps = cm.getNetworkCapabilities(network) ?: return NetworkState.OFFLINE
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkState.OFFLINE
            // VALIDATED is false behind a captive portal and while a new link is still being checked; treat
            // as unknown rather than offline so a slow-to-validate network is not refused.
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return NetworkState.UNKNOWN
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) NetworkState.UNMETERED
            else NetworkState.METERED
        } catch (e: SecurityException) {
            NetworkState.UNKNOWN
        } catch (e: RuntimeException) {
            NetworkState.UNKNOWN
        }
    }

    /** Emits now and again whenever connectivity or its metered-ness changes. */
    fun observe(context: Context): Flow<NetworkState> = callbackFlow {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        trySend(state(app))
        if (cm == null) {
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(state(app)) }
            override fun onLost(network: Network) { trySend(state(app)) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(state(app)) }
        }
        val registered = try {
            cm.registerDefaultNetworkCallback(callback)
            true
        } catch (e: RuntimeException) {
            false
        }
        awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
