package com.example.tools

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Real Android Bluetooth management helper using BluetoothManager and BluetoothAdapter.
 * Strictly respects runtime permissions (BLUETOOTH_CONNECT, BLUETOOTH_SCAN)
 * and returns explicit NetworkControlStatus.
 */
class BluetoothHelper(private val context: Context) {

    private val tag = "BluetoothHelper"
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getBluetoothStatus(): Map<String, Any> {
        val hasHardware = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        if (adapter == null || !hasHardware) {
            return mapOf(
                "status" to NetworkControlStatus.BLOCKED_BY_ANDROID.name,
                "hasHardware" to false,
                "isEnabled" to false,
                "state" to "NOT_SUPPORTED",
                "message" to "Bluetooth hardware is not available on this device."
            )
        }

        val isEnabled = adapter.isEnabled
        val connectPerm = hasConnectPermission()

        val deviceName = if (connectPerm) {
            try { adapter.name ?: "Android Device" } catch (e: SecurityException) { "Permission Required" }
        } else {
            "Permission Required"
        }

        return mapOf(
            "status" to NetworkControlStatus.SUCCESS.name,
            "hasHardware" to true,
            "isEnabled" to isEnabled,
            "state" to if (isEnabled) "ENABLED" else "DISABLED",
            "deviceName" to deviceName,
            "hasConnectPermission" to connectPerm,
            "hasScanPermission" to hasScanPermission(),
            "requiresSettingsForToggle" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        )
    }

    fun getPairedDevices(): List<Map<String, Any>> {
        if (adapter == null || !adapter.isEnabled) return emptyList()
        if (!hasConnectPermission()) return emptyList()

        return try {
            adapter.bondedDevices.map { device ->
                val typeStr = when (device.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
                    BluetoothDevice.DEVICE_TYPE_LE -> "BLE (Low Energy)"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual Mode"
                    else -> "Unknown"
                }
                mapOf(
                    "name" to (device.name ?: "Unnamed Device"),
                    "address" to device.address,
                    "type" to typeStr,
                    "bondState" to if (device.bondState == BluetoothDevice.BOND_BONDED) "BONDED" else "BONDING"
                )
            }
        } catch (e: SecurityException) {
            Log.w(tag, "SecurityException reading bonded devices: ${e.message}")
            emptyList()
        }
    }

    fun startDiscovery(): Pair<NetworkControlStatus, String> {
        val hasHardware = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        if (adapter == null || !hasHardware) {
            return NetworkControlStatus.BLOCKED_BY_ANDROID to "Bluetooth hardware not supported on this platform."
        }

        if (!adapter.isEnabled) {
            return NetworkControlStatus.USER_ACTION_REQUIRED to "Bluetooth is currently turned off. Please enable Bluetooth first."
        }

        if (!hasScanPermission()) {
            val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "BLUETOOTH_SCAN" else "ACCESS_FINE_LOCATION"
            return NetworkControlStatus.PERMISSION_REQUIRED to "Runtime permission $needed is required to perform Bluetooth discovery."
        }

        return try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            val started = adapter.startDiscovery()
            if (started) {
                NetworkControlStatus.SUCCESS to "Bluetooth device discovery started successfully."
            } else {
                NetworkControlStatus.FAILED to "Android Bluetooth stack could not initiate discovery."
            }
        } catch (e: SecurityException) {
            NetworkControlStatus.PERMISSION_REQUIRED to "Bluetooth scan permission denied by Android security manager."
        } catch (e: Exception) {
            NetworkControlStatus.FAILED to "Failed to start Bluetooth discovery: ${e.message}"
        }
    }

    fun openBluetoothSettings(): Pair<NetworkControlStatus, String> {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            NetworkControlStatus.USER_ACTION_REQUIRED to "Opened Android Bluetooth Settings. Please select your device or toggle state."
        } catch (e: Exception) {
            NetworkControlStatus.FAILED to "Unable to open Bluetooth Settings: ${e.message}"
        }
    }
}
