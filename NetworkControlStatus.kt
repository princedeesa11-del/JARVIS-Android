package com.example.tools

/**
 * Standard explicit return statuses for Bluetooth and Wi-Fi operations,
 * conforming strictly to Android version and runtime permission restrictions.
 */
enum class NetworkControlStatus {
    SUCCESS,
    USER_ACTION_REQUIRED,
    PERMISSION_REQUIRED,
    BLOCKED_BY_ANDROID,
    FAILED
}
