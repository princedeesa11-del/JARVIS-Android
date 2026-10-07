package com.example.tools

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Real Android Media control helper using AudioManager, key events, and media transport.
 * Distinguishes between active playback, command dispatch, and permission/state limits.
 */
class MediaControlHelper(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun getPlaybackStatus(): Map<String, Any> {
        val am = audioManager ?: return mapOf(
            "isAudioActive" to false,
            "status" to "AUDIO_UNAVAILABLE",
            "message" to "AudioManager service not available"
        )

        val isMusicActive = am.isMusicActive
        val currentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = if (maxVolume > 0) (currentVolume * 100) / maxVolume else 0

        return mapOf(
            "isMusicActive" to isMusicActive,
            "currentVolumePercent" to volumePercent,
            "currentVolume" to currentVolume,
            "maxVolume" to maxVolume,
            "mode" to when (am.mode) {
                AudioManager.MODE_IN_CALL -> "IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "COMMUNICATION"
                AudioManager.MODE_RINGTONE -> "RINGTONE"
                else -> "NORMAL"
            },
            "status" to if (isMusicActive) "PLAYING" else "IDLE"
        )
    }

    fun dispatchMediaKey(keyCode: Int): Pair<Boolean, String> {
        val am = audioManager ?: return false to "AudioManager is unavailable."

        return try {
            val downTime = SystemClock.uptimeMillis()
            val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            val upTime = SystemClock.uptimeMillis()
            val upEvent = KeyEvent(upTime, upTime, KeyEvent.ACTION_UP, keyCode, 0)

            am.dispatchMediaKeyEvent(downEvent)
            am.dispatchMediaKeyEvent(upEvent)

            val keyName = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> "PLAY"
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "PAUSE"
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "PLAY_PAUSE_TOGGLE"
                KeyEvent.KEYCODE_MEDIA_NEXT -> "NEXT"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "PREVIOUS"
                KeyEvent.KEYCODE_MEDIA_STOP -> "STOP"
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "FAST_FORWARD"
                KeyEvent.KEYCODE_MEDIA_REWIND -> "REWIND"
                else -> "MEDIA_KEY_$keyCode"
            }
            true to "Dispatched $keyName transport command to active Android media receiver."
        } catch (e: Exception) {
            false to "Failed to dispatch media key: ${e.message}"
        }
    }

    fun adjustVolume(direction: String, streamType: String = "MEDIA"): Pair<Boolean, String> {
        val am = audioManager ?: return false to "AudioManager is unavailable."
        val stream = getStreamInt(streamType)

        val dir = if (direction.equals("UP", ignoreCase = true)) {
            AudioManager.ADJUST_RAISE
        } else {
            AudioManager.ADJUST_LOWER
        }

        return try {
            am.adjustStreamVolume(stream, dir, AudioManager.FLAG_SHOW_UI)
            val current = am.getStreamVolume(stream)
            val max = am.getStreamMaxVolume(stream)
            true to "$streamType volume adjusted $direction (Level: $current/$max)."
        } catch (e: Exception) {
            false to "Failed to adjust volume: ${e.message}"
        }
    }

    fun setVolumePercent(percent: Int, streamType: String = "MEDIA"): Pair<Boolean, String> {
        val am = audioManager ?: return false to "AudioManager is unavailable."
        val stream = getStreamInt(streamType)

        return try {
            val max = am.getStreamMaxVolume(stream)
            val targetLevel = ((percent.coerceIn(0, 100) / 100.0) * max).toInt().coerceIn(0, max)
            am.setStreamVolume(stream, targetLevel, AudioManager.FLAG_SHOW_UI)
            val actual = am.getStreamVolume(stream)
            val actualPct = if (max > 0) (actual * 100) / max else 0
            true to "$streamType volume set to $actualPct% ($actual/$max)."
        } catch (e: Exception) {
            false to "Failed to set volume: ${e.message}"
        }
    }

    fun setMute(mute: Boolean, streamType: String = "MEDIA"): Pair<Boolean, String> {
        val am = audioManager ?: return false to "AudioManager is unavailable."
        val stream = getStreamInt(streamType)

        return try {
            val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            am.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
            true to "$streamType volume ${if (mute) "muted" else "unmuted"}."
        } catch (e: Exception) {
            false to "Failed to toggle mute: ${e.message}"
        }
    }

    private fun getStreamInt(streamType: String): Int {
        return when (streamType.uppercase()) {
            "RING", "RINGTONE" -> AudioManager.STREAM_RING
            "ALARM" -> AudioManager.STREAM_ALARM
            "NOTIFICATION" -> AudioManager.STREAM_NOTIFICATION
            "VOICE_CALL", "CALL" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
    }
}
