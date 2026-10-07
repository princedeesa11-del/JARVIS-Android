package com.example.youtube.model

/**
 * Standard YouTube Automation Intents for JARVIS
 */
enum class YouTubeIntent {
    PLAY_YOUTUBE,
    SEARCH_YOUTUBE,
    OPEN_YOUTUBE,
    PAUSE_YOUTUBE,
    RESUME_YOUTUBE,
    NEXT_YOUTUBE,
    PREVIOUS_YOUTUBE,
    STOP_YOUTUBE,
    MUTE_YOUTUBE,
    UNMUTE_YOUTUBE,
    VOLUME_UP,
    VOLUME_DOWN
}

/**
 * Playback target mode preference
 */
enum class YouTubePlaybackTarget {
    AUTO,           // Prefers YouTube app if installed, else Embedded player / Browser
    YOUTUBE_APP,    // Force open in official YouTube Android app
    EMBEDDED,       // Play inside JARVIS embedded player
    BROWSER         // Open in web browser
}

/**
 * Represents a structured YouTube video item
 */
data class YouTubeVideoItem(
    val videoId: String,
    val title: String,
    val channelTitle: String = "",
    val thumbnailUrl: String = "",
    val description: String = "",
    val isOfficial: Boolean = false,
    val relevanceScore: Int = 0
) {
    val watchUrl: String
        get() = "https://www.youtube.com/watch?v=$videoId"

    val embedUrl: String
        get() = "https://www.youtube.com/embed/$videoId?autoplay=1&enablejsapi=1"

    val appIntentUri: String
        get() = "vnd.youtube:$videoId"
}

/**
 * State of YouTube automation in JARVIS
 */
data class YouTubeState(
    val activeVideo: YouTubeVideoItem? = null,
    val isPlaying: Boolean = false,
    val searchResults: List<YouTubeVideoItem> = emptyList(),
    val currentQuery: String = "",
    val playbackTarget: YouTubePlaybackTarget = YouTubePlaybackTarget.AUTO,
    val lastAction: String = "Idle",
    val lastStatusMessage: String = "",
    val isSearching: Boolean = false
)

/**
 * Parse result from natural language input
 */
data class YouTubeParseResult(
    val intent: YouTubeIntent,
    val query: String = "",
    val detectedLanguage: String = "en",
    val confidence: Float = 1.0f
)
