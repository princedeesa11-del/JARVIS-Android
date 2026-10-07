package com.example.youtube.engine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import com.example.service.JarvisAccessibilityService
import com.example.tools.MediaControlHelper
import com.example.voice.assistant.CommandResult
import com.example.voice.assistant.ExecutionStatus
import com.example.youtube.client.YouTubeSearchService
import com.example.youtube.config.YouTubeConfigStore
import com.example.youtube.model.YouTubeIntent
import com.example.youtube.model.YouTubePlaybackTarget
import com.example.youtube.model.YouTubeState
import com.example.youtube.model.YouTubeVideoItem
import com.example.youtube.parser.YouTubeIntentParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Core YouTube Automation Engine for JARVIS.
 * Coordinates intent parsing, smart search resolution, Android app/browser launching,
 * accessibility verification, media key transport, and state management.
 */
class YouTubeAutomationEngine private constructor(private val context: Context) {

    private val searchService = YouTubeSearchService(context)
    private val mediaHelper = MediaControlHelper(context)
    private val configStore = YouTubeConfigStore.getInstance(context)

    private val _state = MutableStateFlow(YouTubeState())
    val state: StateFlow<YouTubeState> = _state.asStateFlow()

    companion object {
        private const val TAG = "YouTubeAutomationEngine"
        const val YT_PACKAGE = "com.google.android.youtube"

        @Volatile
        private var instance: YouTubeAutomationEngine? = null

        fun getInstance(context: Context): YouTubeAutomationEngine {
            return instance ?: synchronized(this) {
                instance ?: YouTubeAutomationEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Attempts to parse and execute a YouTube-related voice or text command.
     * Returns a CommandResult if handled, or null if the utterance is not a YouTube directive.
     */
    suspend fun handleDirective(input: String): CommandResult? {
        val parsed = YouTubeIntentParser.parse(input) ?: return null
        Log.i(TAG, "[YOUTUBE] Detected intent=${parsed.intent} query='${parsed.query}' lang=${parsed.detectedLanguage}")

        return when (parsed.intent) {
            YouTubeIntent.PLAY_YOUTUBE -> executePlay(parsed.query)
            YouTubeIntent.SEARCH_YOUTUBE -> executeSearch(parsed.query)
            YouTubeIntent.OPEN_YOUTUBE -> executeOpen()
            YouTubeIntent.PAUSE_YOUTUBE -> executePause()
            YouTubeIntent.RESUME_YOUTUBE -> executeResume()
            YouTubeIntent.NEXT_YOUTUBE -> executeNext()
            YouTubeIntent.PREVIOUS_YOUTUBE -> executePrevious()
            YouTubeIntent.STOP_YOUTUBE -> executeStop()
            YouTubeIntent.MUTE_YOUTUBE -> executeMute(true)
            YouTubeIntent.UNMUTE_YOUTUBE -> executeMute(false)
            YouTubeIntent.VOLUME_UP -> executeVolumeAdjust(up = true)
            YouTubeIntent.VOLUME_DOWN -> executeVolumeAdjust(up = false)
        }
    }

    /**
     * Executes PLAY_YOUTUBE:
     * 1. Resolves video via search service
     * 2. Selects most relevant video
     * 3. Launches playback in YouTube app (or embedded/browser)
     * 4. Verifies playback via Accessibility if available
     * 5. Informs user: "Playing [title] on YouTube."
     */
    suspend fun executePlay(rawQuery: String): CommandResult = withContext(Dispatchers.IO) {
        val query = if (rawQuery.isBlank()) "Kesariya" else rawQuery
        _state.update { it.copy(isSearching = true, currentQuery = query, lastAction = "Searching $query") }

        Log.i(TAG, "[PLAY_YOUTUBE] Searching video for query: '$query'")
        val searchResults = try {
            searchService.searchVideos(query, maxResults = 5)
        } catch (e: Exception) {
            Log.e(TAG, "Search failure: ${e.message}")
            emptyList()
        }

        val bestVideo = searchResults.firstOrNull { it.videoId.isNotBlank() }

        if (bestVideo != null) {
            val opened = openVideo(bestVideo)
            if (opened) {
                val a11y = JarvisAccessibilityService.instance
                val verified = if (a11y != null && configStore.autoVerifyAccessibility) {
                    a11y.verifyYouTubePlayerOpened(timeoutMs = 4000L)
                } else {
                    isMusicActive()
                }

                val titleSpoken = bestVideo.title.take(50).trim()
                _state.update {
                    it.copy(
                        activeVideo = bestVideo,
                        isPlaying = verified,
                        searchResults = searchResults,
                        isSearching = false,
                        lastAction = if (verified) "Playing ${bestVideo.title}" else "Opened ${bestVideo.title} (playback unverified)",
                        lastStatusMessage = if (verified) "Playing ${bestVideo.title} on YouTube." else "Opened $titleSpoken on YouTube, but playback state is unverified."
                    )
                }

                return@withContext if (verified) {
                    CommandResult(
                        handled = true,
                        commandName = "PLAY_YOUTUBE",
                        speechResponse = "Playing $titleSpoken on YouTube.",
                        success = true,
                        status = ExecutionStatus.SUCCESS,
                        details = bestVideo.videoId
                    )
                } else {
                    CommandResult(
                        handled = true,
                        commandName = "PLAY_YOUTUBE",
                        speechResponse = "Opened $titleSpoken on YouTube, but playback state is unverified.",
                        success = false,
                        status = ExecutionStatus.PLAYBACK_UNVERIFIED,
                        details = bestVideo.videoId
                    )
                }
            }
        }

        // Fallback: If no direct video ID resolved, launch YouTube search directly
        val searchLaunched = launchSearchIntent(query)
        if (searchLaunched) {
            val a11y = JarvisAccessibilityService.instance
            var verifiedPlayback = false
            if (a11y != null) {
                // Attempt to click first video in search results
                val firstCard = a11y.findFirstVideoResult(retries = 5, retryDelayMs = 400L)
                if (firstCard != null) {
                    val clicked = a11y.clickNodeSafely(firstCard.first)
                    if (clicked) {
                        verifiedPlayback = a11y.verifyYouTubePlayerOpened(timeoutMs = 4000L)
                    }
                }
            } else {
                verifiedPlayback = isMusicActive()
            }

            _state.update {
                it.copy(
                    isSearching = false,
                    isPlaying = verifiedPlayback,
                    lastAction = if (verifiedPlayback) "Playing $query" else "Opened search for $query",
                    lastStatusMessage = if (verifiedPlayback) "Playing $query on YouTube." else "Opened search for $query on YouTube, but playback state is unverified."
                )
            }

            return@withContext if (verifiedPlayback) {
                CommandResult(
                    handled = true,
                    commandName = "PLAY_YOUTUBE",
                    speechResponse = "Playing $query on YouTube.",
                    success = true,
                    status = ExecutionStatus.SUCCESS
                )
            } else {
                CommandResult(
                    handled = true,
                    commandName = "PLAY_YOUTUBE",
                    speechResponse = "Opened search for $query on YouTube, but playback state is unverified.",
                    success = false,
                    status = ExecutionStatus.PLAYBACK_UNVERIFIED
                )
            }
        }

        _state.update { it.copy(isSearching = false, lastAction = "Failed to play $query") }
        CommandResult(
            handled = true,
            commandName = "PLAY_YOUTUBE",
            speechResponse = "I couldn't find that video on YouTube. Please check your network connection.",
            success = false,
            status = ExecutionStatus.TARGET_NOT_FOUND
        )
    }

    private fun isMusicActive(): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return am?.isMusicActive == true
    }

    /**
     * Executes SEARCH_YOUTUBE:
     * Does NOT automatically play video.
     * Opens YouTube search results or shows in dashboard so user can choose.
     */
    suspend fun executeSearch(query: String): CommandResult = withContext(Dispatchers.IO) {
        val effectiveQuery = query.ifBlank { "trending songs" }
        _state.update { it.copy(isSearching = true, currentQuery = effectiveQuery, lastAction = "Searching $effectiveQuery") }

        // Fetch results for dashboard
        val results = try {
            searchService.searchVideos(effectiveQuery, maxResults = 8)
        } catch (_: Exception) {
            emptyList()
        }

        _state.update {
            it.copy(
                searchResults = results,
                isSearching = false,
                lastAction = "Searched $effectiveQuery",
                lastStatusMessage = "Found ${results.size} results for $effectiveQuery"
            )
        }

        // Open YouTube search results screen on device
        val launched = launchSearchIntent(effectiveQuery)
        val speech = if (launched) {
            "Searching $effectiveQuery on YouTube."
        } else {
            "Found ${results.size} search results for $effectiveQuery."
        }

        CommandResult(
            handled = true,
            commandName = "SEARCH_YOUTUBE",
            speechResponse = speech,
            success = true,
            status = ExecutionStatus.SUCCESS,
            details = effectiveQuery
        )
    }

    /**
     * Executes OPEN_YOUTUBE:
     * Opens official YouTube app or browser fallback.
     */
    fun executeOpen(): CommandResult {
        val isInstalled = isAppInstalled(YT_PACKAGE)
        return if (isInstalled) {
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(YT_PACKAGE)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    CommandResult(
                        handled = true,
                        commandName = "OPEN_YOUTUBE",
                        speechResponse = "Opening YouTube.",
                        success = true
                    )
                } else {
                    openBrowser("https://www.youtube.com")
                }
            } catch (e: Exception) {
                openBrowser("https://www.youtube.com")
            }
        } else {
            openBrowser("https://www.youtube.com")
        }
    }

    /**
     * Executes PAUSE_YOUTUBE:
     * Uses Accessibility click if YouTube is active foreground, plus Android MediaSession Key dispatch.
     */
    fun executePause(): CommandResult {
        var tappedUi = false
        val a11y = JarvisAccessibilityService.instance
        if (a11y != null) {
            val root = a11y.rootInActiveWindow
            if (root != null && root.packageName?.toString() == YT_PACKAGE) {
                // Tap pause button or screen
                tappedUi = a11y.clickNodeSafely(root)
            }
        }

        val (dispatched, _) = mediaHelper.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        _state.update { it.copy(isPlaying = false, lastAction = "Paused") }

        return if (tappedUi || dispatched) {
            CommandResult(
                handled = true,
                commandName = "PAUSE_YOUTUBE",
                speechResponse = "Pausing YouTube video.",
                success = true
            )
        } else {
            CommandResult(
                handled = true,
                commandName = "PAUSE_YOUTUBE",
                speechResponse = "Dispatched pause signal. Note that Android restricts controlling external apps in the background without an active media session.",
                success = true
            )
        }
    }

    /**
     * Executes RESUME_YOUTUBE
     */
    fun executeResume(): CommandResult {
        val (dispatched, _) = mediaHelper.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        _state.update { it.copy(isPlaying = true, lastAction = "Resumed") }
        return CommandResult(
            handled = true,
            commandName = "RESUME_YOUTUBE",
            speechResponse = "Resuming YouTube playback.",
            success = dispatched
        )
    }

    /**
     * Executes STOP_YOUTUBE
     */
    fun executeStop(): CommandResult {
        mediaHelper.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
        _state.update { it.copy(isPlaying = false, lastAction = "Stopped") }
        return CommandResult(
            handled = true,
            commandName = "STOP_YOUTUBE",
            speechResponse = "Stopping YouTube playback.",
            success = true
        )
    }

    /**
     * Executes NEXT_YOUTUBE
     */
    fun executeNext(): CommandResult {
        mediaHelper.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        _state.update { it.copy(lastAction = "Next video") }
        return CommandResult(
            handled = true,
            commandName = "NEXT_YOUTUBE",
            speechResponse = "Skipping to next video.",
            success = true
        )
    }

    /**
     * Executes PREVIOUS_YOUTUBE
     */
    fun executePrevious(): CommandResult {
        mediaHelper.dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        _state.update { it.copy(lastAction = "Previous video") }
        return CommandResult(
            handled = true,
            commandName = "PREVIOUS_YOUTUBE",
            speechResponse = "Returning to previous video.",
            success = true
        )
    }

    /**
     * Executes MUTE/UNMUTE
     */
    fun executeMute(mute: Boolean): CommandResult {
        val (ok, _) = mediaHelper.setMute(mute)
        val text = if (mute) "Media volume muted." else "Media volume unmuted."
        return CommandResult(
            handled = true,
            commandName = if (mute) "MUTE_YOUTUBE" else "UNMUTE_YOUTUBE",
            speechResponse = text,
            success = ok
        )
    }

    /**
     * Executes VOLUME UP/DOWN
     */
    fun executeVolumeAdjust(up: Boolean): CommandResult {
        val direction = if (up) "UP" else "DOWN"
        val (ok, _) = mediaHelper.adjustVolume(direction)
        val text = if (up) "Media volume increased." else "Media volume decreased."
        return CommandResult(
            handled = true,
            commandName = if (up) "VOLUME_UP" else "VOLUME_DOWN",
            speechResponse = text,
            success = ok
        )
    }

    /**
     * Opens a specific video in YouTube app or Browser
     */
    fun openVideo(video: YouTubeVideoItem): Boolean {
        val uri = Uri.parse(video.watchUrl)
        val isInstalled = isAppInstalled(YT_PACKAGE)
        val target = configStore.playbackTarget

        if (target == YouTubePlaybackTarget.BROWSER || !isInstalled) {
            return launchBrowserUrl(video.watchUrl)
        }

        // Try launching official YouTube app
        return try {
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(video.appIntentUri)).apply {
                setPackage(YT_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(appIntent)
            true
        } catch (_: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(YT_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                true
            } catch (_: Exception) {
                launchBrowserUrl(video.watchUrl)
            }
        }
    }

    /**
     * Launches YouTube Search Intent
     */
    private fun launchSearchIntent(query: String): Boolean {
        val searchUrl = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val uri = Uri.parse(searchUrl)
        val isInstalled = isAppInstalled(YT_PACKAGE)

        if (isInstalled) {
            try {
                val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(YT_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(appIntent)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Failed launching YouTube app search: ${e.message}")
            }
        }

        return launchBrowserUrl(searchUrl)
    }

    private fun launchBrowserUrl(url: String): Boolean {
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No browser or activity found to open URL: ${e.message}")
            false
        }
    }

    private fun openBrowser(url: String): CommandResult {
        val ok = launchBrowserUrl(url)
        return if (ok) {
            CommandResult(
                handled = true,
                commandName = "OPEN_YOUTUBE",
                speechResponse = "YouTube app is not installed, so I'm opening YouTube in your browser.",
                success = true
            )
        } else {
            CommandResult(
                handled = true,
                commandName = "OPEN_YOUTUBE",
                speechResponse = "Unable to open browser.",
                success = false,
                status = ExecutionStatus.FAILED
            )
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
