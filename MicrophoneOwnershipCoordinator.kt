package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clear microphone modes adhering to Section 9:
 * WAKE_WORD_MODE, COMMAND_MODE, SPEAKING, STOPPED.
 */
enum class MicrophoneState {
    WAKE_WORD_MODE,
    COMMAND_MODE,
    SPEAKING,
    STOPPED
}

/**
 * Microphone & Audio Focus Ownership Coordinator for JARVIS.
 * Strictly guarantees that only one audio consumer (WakeWord sentinel, SpeechRecognizer,
 * Gemini Live, or TextToSpeech) owns the microphone or audio playback at any instant.
 *
 * Prevents OS-level microphone contention (error -38, audio record busy), manages Android
 * AudioFocus transitions, and supports immediate barge-in interruption.
 */
enum class AudioClient {
    NONE,
    WAKE_WORD_SENTINEL,
    CONTINUOUS_MIC,
    SPEECH_RECOGNITION,
    GEMINI_LIVE,
    TTS_PLAYBACK
}

class MicrophoneOwnershipCoordinator private constructor(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _currentOwner = MutableStateFlow(AudioClient.NONE)
    val currentOwner: StateFlow<AudioClient> = _currentOwner.asStateFlow()

    private val _microphoneState = MutableStateFlow(MicrophoneState.STOPPED)
    val microphoneState: StateFlow<MicrophoneState> = _microphoneState.asStateFlow()

    private val _isMicrophoneBusy = MutableStateFlow(false)
    val isMicrophoneBusy: StateFlow<Boolean> = _isMicrophoneBusy.asStateFlow()

    var onBargeInDetected: (() -> Unit)? = null
    var onAudioFocusLost: (() -> Unit)? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.w(TAG, "Audio focus lost ($focusChange). Yielding audio resources.")
                onAudioFocusLost?.invoke()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio focus regained.")
            }
        }
    }

    fun setMicrophoneState(state: MicrophoneState) {
        _microphoneState.value = state
        Log.d(TAG, "MicrophoneState set to: $state")
    }

    /**
     * Attempts to acquire microphone exclusive ownership for a specific client.
     * Returns true if granted, false if rejected.
     */
    @Synchronized
    fun acquireMicrophone(client: AudioClient): Boolean {
        if (client == AudioClient.NONE || client == AudioClient.TTS_PLAYBACK) {
            return false
        }

        val active = _currentOwner.value
        Log.i(TAG, "Client '$client' requesting microphone. Current owner: '$active'")

        // If client is already owner, permit
        if (active == client) {
            _isMicrophoneBusy.value = true
            _microphoneState.value = if (client == AudioClient.SPEECH_RECOGNITION || client == AudioClient.GEMINI_LIVE) {
                MicrophoneState.COMMAND_MODE
            } else {
                MicrophoneState.WAKE_WORD_MODE
            }
            return true
        }

        // Higher priority speech/live barge-in interrupts TTS
        if (active == AudioClient.TTS_PLAYBACK) {
            onBargeInDetected?.invoke()
        }

        // Request audio focus from Android system
        requestSystemAudioFocus()

        _currentOwner.value = client
        _isMicrophoneBusy.value = true
        _microphoneState.value = if (client == AudioClient.SPEECH_RECOGNITION || client == AudioClient.GEMINI_LIVE) {
            MicrophoneState.COMMAND_MODE
        } else {
            MicrophoneState.WAKE_WORD_MODE
        }
        Log.i("WAKE", "[WAKE] mic_acquired: $client, state=${_microphoneState.value}")
        return true
    }

    /**
     * Acquires audio output ownership for Text-To-Speech playback.
     */
    @Synchronized
    fun acquireAudioPlayback(): Boolean {
        requestSystemAudioFocus()
        _currentOwner.value = AudioClient.TTS_PLAYBACK
        _isMicrophoneBusy.value = false
        _microphoneState.value = MicrophoneState.SPEAKING
        return true
    }

    /**
     * Releases ownership for the given client.
     */
    @Synchronized
    fun release(client: AudioClient) {
        if (_currentOwner.value == client) {
            Log.i(TAG, "Client '$client' released audio ownership.")
            _currentOwner.value = AudioClient.NONE
            _isMicrophoneBusy.value = false
            _microphoneState.value = MicrophoneState.STOPPED
            abandonSystemAudioFocus()
        }
    }

    /**
     * Forces immediate release of all audio owners (e.g. on emergency STOP or user interruption).
     */
    @Synchronized
    fun forceReset() {
        Log.i(TAG, "Force resetting microphone & audio ownership.")
        _currentOwner.value = AudioClient.NONE
        _isMicrophoneBusy.value = false
        _microphoneState.value = MicrophoneState.STOPPED
        abandonSystemAudioFocus()
    }

    private fun requestSystemAudioFocus(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()

            audioFocusRequest = request
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonSystemAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusChangeListener)
        }
    }

    companion object {
        private const val TAG = "MicCoordinator"

        @Volatile
        private var instance: MicrophoneOwnershipCoordinator? = null

        fun getInstance(context: Context): MicrophoneOwnershipCoordinator {
            return instance ?: synchronized(this) {
                instance ?: MicrophoneOwnershipCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }
}
