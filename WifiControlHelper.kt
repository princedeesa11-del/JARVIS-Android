package com.example.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Real Android Wi-Fi and Network helper.
 * Strictly respects Android 10+ restrictions: never fakes programmatic toggles.
 * Dispatches to system Internet Connectivity Panel or Wi-Fi settings when needed.
 * Returns explicit NetworkControlStatus.
 */
class WifiControlHelper(private val context: Context) {

    private val tag = "WifiControlHelper"
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun getWifiStatus(): Map<String, Any> {
        val isWifiEnabled = wifiManager?.isWifiEnabled ?: false

        var isConnected = false
        var isMetered = false
        var hasInternet = false

        connectivityManager?.let { cm ->
            val activeNet = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(activeNet)
            if (caps != null) {
                isConnected = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }

        val ssid = if (isConnected && wifiManager != null) {
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            val rawSsid = info?.ssid ?: "Unknown"
            if (rawSsid == "<unknown ssid>") "Connected (Location permission required for SSID on Android 10+)" else rawSsid
        } else {
            "Not connected"
        }

        return mapOf(
            "status" to NetworkControlStatus.SUCCESS.name,
            "isWifiEnabled" to isWifiEnabled,
            "isConnectedToWifi" to isConnected,
            "hasInternetAccess" to hasInternet,
            "ssid" to ssid,
            "isMetered" to isMetered,
            "directToggleRestricted" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        )
    }

    fun openWifiSettings(): Pair<NetworkControlStatus, String> {
        return try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
            NetworkControlStatus.USER_ACTION_REQUIRED to "Opened Android Internet Connectivity panel. User action required to switch networks or toggle Wi-Fi."
        } catch (e: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallback)
                NetworkControlStatus.USER_ACTION_REQUIRED to "Opened Wi-Fi Settings."
            } catch (fallbackError: Exception) {
                NetworkControlStatus.FAILED to "Could not launch Wi-Fi settings: ${fallbackError.message}"
            }
        }
    }

    /**
     * Scans for available Wi-Fi networks if ACCESS_FINE_LOCATION permission is granted.
     */
    fun scanAvailableNetworks(): Pair<NetworkControlStatus, List<Map<String, Any>>> {
        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLoc) {
            return NetworkControlStatus.PERMISSION_REQUIRED to emptyList()
        }

        val wm = wifiManager ?: return NetworkControlStatus.FAILED to emptyList()

        return try {
            @Suppress("DEPRECATION")
            val results = wm.scanResults
            val mapped = results.map { scan ->
                mapOf(
                    "ssid" to scan.SSID.ifBlank { "<Hidden Network>" },
                    "bssid" to scan.BSSID,
                    "levelDbm" to scan.level,
                    "frequencyMhz" to scan.frequency,
                    "capabilities" to scan.capabilities
                )
            }
            NetworkControlStatus.SUCCESS to mapped
        } catch (e: SecurityException) {
            NetworkControlStatus.PERMISSION_REQUIRED to emptyList()
        } catch (e: Exception) {
            Log.e(tag, "Failed to scan Wi-Fi: ${e.message}", e)
            NetworkControlStatus.FAILED to emptyList()
        }
    }
}
