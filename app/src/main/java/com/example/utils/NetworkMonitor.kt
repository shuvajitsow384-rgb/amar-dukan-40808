package com.example.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NetworkMonitor {
    private val _isOnlineFlow = MutableStateFlow(true)
    val isOnlineFlow: Flow<Boolean> = _isOnlineFlow.asStateFlow()

    var isOnline by mutableStateOf(true)
        private set

    var lastSyncTimestamp by mutableStateOf(System.currentTimeMillis())
        private set

    private var isInitialized = false
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (isInitialized) return
        isInitialized = true

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivityManager == null) {
            isOnline = true
            _isOnlineFlow.value = true
            return
        }

        try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            val initialConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            isOnline = initialConnected
            _isOnlineFlow.value = initialConnected

            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(
                networkRequest,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        isOnline = true
                        _isOnlineFlow.value = true
                        lastSyncTimestamp = System.currentTimeMillis()
                    }

                    override fun onLost(network: Network) {
                        val currentNetwork = connectivityManager.activeNetwork
                        val caps = connectivityManager.getNetworkCapabilities(currentNetwork)
                        val stillOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        isOnline = stillOnline
                        _isOnlineFlow.value = stillOnline
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        isOnline = hasInternet
                        _isOnlineFlow.value = hasInternet
                    }
                }
            )
        } catch (e: Exception) {
            android.util.Log.w("NetworkMonitor", "Network callback registration note: ${e.message}")
            isOnline = true
            _isOnlineFlow.value = true
        }
    }

    fun checkNow(): Boolean {
        val ctx = appContext ?: return isOnline
        val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return isOnline
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
            val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            isOnline = connected
            _isOnlineFlow.value = connected
            connected
        } catch (e: Exception) {
            isOnline
        }
    }

    fun markSynced() {
        lastSyncTimestamp = System.currentTimeMillis()
    }
}
