package com.haraldmue.velin.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.SystemClock

/**
 * Wireless Android Auto takes the default Wi-Fi network. Existing LAN sockets then stall
 * instead of failing; cancel in-flight work when that default network is actually lost.
 *
 * Emulators and phones often fire a spurious [ConnectivityManager.NetworkCallback.onLost]
 * while the default network is still coming up. Ignore those so library loads are not killed.
 */
internal class NetworkLossCanceller(
    context: Context,
    private val onNetworkLost: () -> Unit,
) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var startedAtMs = 0L
    private var trackedNetwork: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trackedNetwork = network
        }

        override fun onLost(network: Network) {
            if (!shouldCancelForLostNetwork(
                    lostNetwork = network,
                    trackedNetwork = trackedNetwork,
                    elapsedSinceStartMs = SystemClock.elapsedRealtime() - startedAtMs,
                )
            ) {
                if (trackedNetwork == network) {
                    trackedNetwork = null
                }
                return
            }
            trackedNetwork = null
            onNetworkLost()
        }
    }

    fun start() {
        startedAtMs = SystemClock.elapsedRealtime()
        trackedNetwork = connectivity.activeNetwork
        connectivity.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        trackedNetwork = null
    }
}

internal fun shouldCancelForLostNetwork(
    lostNetwork: Any,
    trackedNetwork: Any?,
    elapsedSinceStartMs: Long,
    graceMs: Long = NetworkLossGraceMs,
): Boolean {
    if (trackedNetwork == null) return false
    if (lostNetwork != trackedNetwork) return false
    return elapsedSinceStartMs >= graceMs
}

internal const val NetworkLossGraceMs = 2_500L

internal fun isLikelyEmulator(
    fingerprint: String = Build.FINGERPRINT,
    model: String = Build.MODEL,
    hardware: String = Build.HARDWARE,
    product: String = Build.PRODUCT,
    manufacturer: String = Build.MANUFACTURER,
): Boolean {
    val fingerprintLower = fingerprint.lowercase()
    val modelLower = model.lowercase()
    val hardwareLower = hardware.lowercase()
    val productLower = product.lowercase()
    val manufacturerLower = manufacturer.lowercase()
    return fingerprintLower.startsWith("generic") ||
        fingerprintLower.contains("emulator") ||
        modelLower.contains("emulator") ||
        modelLower.contains("android sdk") ||
        hardwareLower.contains("goldfish") ||
        hardwareLower.contains("ranchu") ||
        productLower.contains("sdk_gphone") ||
        productLower.contains("emulator") ||
        manufacturerLower.contains("genymotion")
}
