package com.example.touchguard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Audio Alarm & Vocal Warning Synthesizer for Touch Guard.
 * Plays customizable audible warnings ("Warning: JARVIS Touch Guard activated. Unauthorized access detected.")
 * and emergency security alarm/sirens.
 */
class TouchGuardAlarmPlayer(private val context: Context) : TextToSpeech.OnInitListener {

    private val tag = "TouchGuardAlarm"
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeech: String? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to init TextToSpeech: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsReady = true
            pendingSpeech?.let { speech ->
                speakWarning(speech)
                pendingSpeech = null
            }
        }
    }

    fun speakWarning(warningText: String) {
        if (isTtsReady && tts != null) {
            try {
                tts?.speak(warningText, TextToSpeech.QUEUE_FLUSH, null, "touch_guard_warning")
            } catch (e: Exception) {
                Log.e(tag, "TTS speak failed: ${e.message}")
            }
        } else {
            pendingSpeech = warningText
        }
    }

    fun playSiren(loop: Boolean = true) {
        stopSiren()
        try {
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alertUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = loop
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error playing siren: ${e.message}", e)
        }
    }

    fun triggerVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 500, 200, 800), -1)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 300, 200, 500, 200, 800), -1)
            }
        } catch (e: Exception) {
            Log.e(tag, "Vibration failed: ${e.message}")
        }
    }

    fun stopSiren() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun stopAll() {
        stopSiren()
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun release() {
        stopAll()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
    }
}
