package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voice.assistant.CommandResult
import com.example.voice.assistant.ExecutionStatus
import com.example.youtube.client.YouTubeSearchService
import com.example.youtube.config.YouTubeConfigStore
import com.example.youtube.engine.YouTubeAutomationEngine
import com.example.youtube.model.YouTubeIntent
import com.example.youtube.model.YouTubePlaybackTarget
import com.example.youtube.model.YouTubeVideoItem
import com.example.youtube.parser.YouTubeIntentParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeComprehensiveTestSuite {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ==========================================
    // 1. MULTI-LANGUAGE INTENT PARSING TESTS
    // ==========================================

    @Test
    fun testEnglishPlayCommands() {
        val res1 = YouTubeIntentParser.parse("Play Kesariya on YouTube")
        assertNotNull(res1)
        assertEquals(YouTubeIntent.PLAY_YOUTUBE, res1!!.intent)
        assertEquals("Kesariya", res1.query)

        val res2 = YouTubeIntentParser.parse("Play Believer")
        assertNotNull(res2)
        assertEquals(YouTubeIntent.PLAY_YOUTUBE, res2!!.intent)
        assertEquals("Believer", res2.query)

        val res3 = YouTubeIntentParser.parse("Play Arijit Singh songs")
        assertNotNull(res3)
        assertEquals(YouTubeIntent.PLAY_YOUTUBE, res3!!.intent)
        assertEquals("Arijit Singh", res3.query)

        val res4 = YouTubeIntentParser.parse("Play the first video of Kesariya on YouTube")
        assertNotNull(res4)
        assertEquals(YouTubeIntent.PLAY_YOUTUBE, res4!!.intent)
        assertEquals("Kesariya", res4.query)
    }

    @Test
    fun testEnglishSearchCommands() {
        val res1 = YouTubeIntentParser.parse("Search Arijit Singh on YouTube")
        assertNotNull(res1)
        assertEquals(YouTubeIntent.SEARCH_YOUTUBE, res1!!.intent)
        assertEquals("Arijit Singh", res1.query)

        val res2 = YouTubeIntentParser.parse("Search Hanuman Chalisa")
        assertNotNull(res2)
        assertEquals(YouTubeIntent.SEARCH_YOUTUBE, res2!!.intent)
        assertEquals("Hanuman Chalisa", res2.query)
    }

    @Test
    fun testEnglishControlCommands() {
        assertEquals(YouTubeIntent.OPEN_YOUTUBE, YouTubeIntentParser.parse("Open YouTube")?.intent)
        assertEquals(YouTubeIntent.PAUSE_YOUTUBE, YouTubeIntentParser.parse("Pause YouTube")?.intent)
        assertEquals(YouTubeIntent.PAUSE_YOUTUBE, YouTubeIntentParser.parse("pause")?.intent)
        assertEquals(YouTubeIntent.RESUME_YOUTUBE, YouTubeIntentParser.parse("Resume YouTube")?.intent)
        assertEquals(YouTubeIntent.NEXT_YOUTUBE, YouTubeIntentParser.parse("Next video")?.intent)
        assertEquals(YouTubeIntent.PREVIOUS_YOUTUBE, YouTubeIntentParser.parse("Previous video")?.intent)
        assertEquals(YouTubeIntent.STOP_YOUTUBE, YouTubeIntentParser.parse("Stop YouTube")?.intent)
        assertEquals(YouTubeIntent.VOLUME_UP, YouTubeIntentParser.parse("Volume up")?.intent)
        assertEquals(YouTubeIntent.VOLUME_DOWN, YouTubeIntentParser.parse("Volume down")?.intent)
        assertEquals(YouTubeIntent.MUTE_YOUTUBE, YouTubeIntentParser.parse("Mute")?.intent)
        assertEquals(YouTubeIntent.UNMUTE_YOUTUBE, YouTubeIntentParser.parse("Unmute")?.intent)
    }

    @Test
    fun testHindiHinglishCommands() {
        // Play
        val play1 = YouTubeIntentParser.parse("YouTube par Kesariya chalao")
        assertNotNull(play1)
        assertEquals(YouTubeIntent.PLAY_YOUTUBE, play1!!.intent)
        assertEquals("Kesariya", play1.query)

        val play2 = YouTubeIntentParser.parse("Arijit Singh ka song chalao")
        assertNotNull(play2)
        assertEquals(YouTubeIntent.PLAY_YOUTUBE, play2!!.intent)
        assertEquals("Arijit Singh", play2.query)

        val play3 = YouTubeIntentParser.parse("Kesariya baja do")
        assertNotNull(play3)
        assertEquals(YouTubeIntent.PLAY_YOUTUBE, play3!!.intent)
        assertEquals("Kesariya", play3.query)

        // Search
        val search1 = YouTubeIntentParser.parse("Believer search karo")
        assertNotNull(search1)
        assertEquals(YouTubeIntent.SEARCH_YOUTUBE, search1!!.intent)
        assertEquals("Believer", search1.query)

        // Open & Controls
        assertEquals(YouTubeIntent.OPEN_YOUTUBE, YouTubeIntentParser.parse("YouTube kholo")?.intent)
        assertEquals(YouTubeIntent.NEXT_YOUTUBE, YouTubeIntentParser.parse("Agla video chalao")?.intent)
        assertEquals(YouTubeIntent.PREVIOUS_YOUTUBE, YouTubeIntentParser.parse("Pichhla video chalao")?.intent)
        assertEquals(YouTubeIntent.VOLUME_UP, YouTubeIntentParser.parse("Awaaz badhao")?.intent)
        assertEquals(YouTubeIntent.VOLUME_DOWN, YouTubeIntentParser.parse("Awaaz kam karo")?.intent)
    }

    @Test
    fun testGujaratiCommands() {
        // Gujarati script play
        val guPlay1 = YouTubeIntentParser.parse("YouTube પર Kesariya વગાડો")
        assertNotNull(guPlay1)
        assertEquals(YouTubeIntent.PLAY_YOUTUBE, guPlay1!!.intent)
        assertEquals("Kesariya", guPlay1.query)

        val guPlay2 = YouTubeIntentParser.parse("YouTube પર Arijit Singh નું song વગાડો")
        assertNotNull(guPlay2)
        assertEquals(YouTubeIntent.PLAY_YOUTUBE, guPlay2!!.intent)
        assertEquals("Arijit Singh", guPlay2.query)

        val guPlay3 = YouTubeIntentParser.parse("Kesariya વગાડો")
        assertNotNull(guPlay3)
        assertEquals(YouTubeIntent.PLAY_YOUTUBE, guPlay3!!.intent)
        assertEquals("Kesariya", guPlay3.query)

        val guPlayGeneric = YouTubeIntentParser.parse("આ ગીત ચલાવો")
        assertNotNull(guPlayGeneric)
        assertEquals(YouTubeIntent.PLAY_YOUTUBE, guPlayGeneric!!.intent)

        // Gujarati script search
        val guSearch1 = YouTubeIntentParser.parse("યુટ્યુબ પર Hanuman Chalisa શોધો")
        assertNotNull(guSearch1)
        assertEquals(YouTubeIntent.SEARCH_YOUTUBE, guSearch1!!.intent)
        assertEquals("Hanuman Chalisa", guSearch1.query)

        val guSearch2 = YouTubeIntentParser.parse("YouTube પર Arijit Singh નું song શોધો")
        assertNotNull(guSearch2)
        assertEquals(YouTubeIntent.SEARCH_YOUTUBE, guSearch2!!.intent)
        assertEquals("Arijit Singh", guSearch2.query)

        // Gujarati script open & controls
        assertEquals(YouTubeIntent.OPEN_YOUTUBE, YouTubeIntentParser.parse("YouTube ખોલો")?.intent)
        assertEquals(YouTubeIntent.OPEN_YOUTUBE, YouTubeIntentParser.parse("યુટ્યુબ ખોલો")?.intent)
        assertEquals(YouTubeIntent.NEXT_YOUTUBE, YouTubeIntentParser.parse("આગળનું ગીત ચલાવો")?.intent)
        assertEquals(YouTubeIntent.PREVIOUS_YOUTUBE, YouTubeIntentParser.parse("પાછળનું ગીત")?.intent)
        assertEquals(YouTubeIntent.VOLUME_UP, YouTubeIntentParser.parse("અવાજ વધારો")?.intent)
        assertEquals(YouTubeIntent.VOLUME_DOWN, YouTubeIntentParser.parse("અવાજ ઘટાડો")?.intent)
        assertEquals(YouTubeIntent.MUTE_YOUTUBE, YouTubeIntentParser.parse("અવાજ બંધ કરો")?.intent)
        assertEquals(YouTubeIntent.UNMUTE_YOUTUBE, YouTubeIntentParser.parse("અવાજ ચાલુ કરો")?.intent)
        assertEquals(YouTubeIntent.PAUSE_YOUTUBE, YouTubeIntentParser.parse("થોભો")?.intent)
        assertEquals(YouTubeIntent.PAUSE_YOUTUBE, YouTubeIntentParser.parse("રોકો")?.intent)
    }

    // ==========================================
    // 2. SMART SEARCH & RANKING TESTS
    // ==========================================

    @Test
    fun testRankingAlgorithm() {
        val searchService = YouTubeSearchService(context)
        val rawItems = listOf(
            YouTubeVideoItem(
                videoId = "xyz789cover",
                title = "Kesariya (Guitar Cover) by Fan",
                channelTitle = "Acoustic Fan",
                isOfficial = false
            ),
            YouTubeVideoItem(
                videoId = "abc123official",
                title = "Kesariya - Brahmāstra | Ranbir & Alia | Pritam | Arijit Singh | Official Video",
                channelTitle = "Sony Music India",
                isOfficial = true
            ),
            YouTubeVideoItem(
                videoId = "mno456reaction",
                title = "American Reacts to Kesariya Music Video!",
                channelTitle = "Reaction Channel",
                isOfficial = false
            )
        )

        val ranked = searchService.rankResults(rawItems, "Kesariya")
        assertFalse(ranked.isEmpty())
        // Official source with exact match must rank first
        assertEquals("abc123official", ranked.first().videoId)
        assertTrue(ranked.first().relevanceScore > ranked[1].relevanceScore)
    }

    // ==========================================
    // 3. SECURE CONFIGURATION STORE TESTS
    // ==========================================

    @Test
    fun testSecureCredentialStorageAndMasking() {
        val store = YouTubeConfigStore.getInstance(context)

        // Empty state
        store.setApiKey("")
        assertEquals("", store.getApiKey())
        assertTrue(store.getMaskedApiKey().contains("Not configured"))

        // Set key
        val testKey = "AIzaSyTestApiKeyForYouTubeCloud123"
        store.setApiKey(testKey)
        assertEquals(testKey, store.getApiKey())

        // Masking check: Must never expose full key in UI
        val masked = store.getMaskedApiKey()
        assertTrue(masked.startsWith("AIza"))
        assertTrue(masked.endsWith("d123"))
        assertTrue(masked.contains("••••••••"))
        assertFalse(masked.contains("TestApiKeyForYouTubeCloud"))

        // Settings toggles
        store.playbackTarget = YouTubePlaybackTarget.EMBEDDED
        assertEquals(YouTubePlaybackTarget.EMBEDDED, store.playbackTarget)

        store.autoVerifyAccessibility = false
        assertFalse(store.autoVerifyAccessibility)
    }

    // ==========================================
    // 4. ENGINE EXECUTION & ERROR HANDLING TESTS
    // ==========================================

    @Test
    fun testEngineExecuteCommands() = runBlocking {
        val engine = YouTubeAutomationEngine.getInstance(context)

        // 1. Search command does NOT auto-play
        val searchResult = engine.executeSearch("Arijit Singh songs")
        assertTrue(searchResult.handled)
        assertEquals("SEARCH_YOUTUBE", searchResult.commandName)
        assertTrue(searchResult.speechResponse.contains("Searching Arijit Singh songs on YouTube"))

        // 2. Play command
        val playResult = engine.executePlay("Kesariya")
        assertTrue(playResult.handled)
        assertEquals("PLAY_YOUTUBE", playResult.commandName)
        assertTrue(playResult.speechResponse.contains("Playing") || playResult.speechResponse.contains("Kesariya"))

        // 3. Controls
        val pauseResult = engine.executePause()
        assertTrue(pauseResult.handled)
        assertEquals("PAUSE_YOUTUBE", pauseResult.commandName)

        val resumeResult = engine.executeResume()
        assertTrue(resumeResult.handled)
        assertEquals("RESUME_YOUTUBE", resumeResult.commandName)

        val nextResult = engine.executeNext()
        assertTrue(nextResult.handled)
        assertEquals("NEXT_YOUTUBE", nextResult.commandName)

        val prevResult = engine.executePrevious()
        assertTrue(prevResult.handled)
        assertEquals("PREVIOUS_YOUTUBE", prevResult.commandName)

        val volUpResult = engine.executeVolumeAdjust(up = true)
        assertTrue(volUpResult.handled)
        assertEquals("VOLUME_UP", volUpResult.commandName)

        val muteResult = engine.executeMute(true)
        assertTrue(muteResult.handled)
        assertEquals("MUTE_YOUTUBE", muteResult.commandName)
    }

    @Test
    fun testDirectIntentRouting() = runBlocking {
        val engine = YouTubeAutomationEngine.getInstance(context)

        val res1 = engine.handleDirective("YouTube પર Kesariya વગાડો")
        assertNotNull(res1)
        assertTrue(res1!!.handled)
        assertEquals("PLAY_YOUTUBE", res1.commandName)

        val res2 = engine.handleDirective("Search Hanuman Chalisa")
        assertNotNull(res2)
        assertTrue(res2!!.handled)
        assertEquals("SEARCH_YOUTUBE", res2.commandName)

        val res3 = engine.handleDirective("Open YouTube")
        assertNotNull(res3)
        assertTrue(res3!!.handled)
        assertEquals("OPEN_YOUTUBE", res3.commandName)

        val unhandled = engine.handleDirective("What is the weather today?")
        assertNull(unhandled)
    }

    @Test
    fun testVideoItemProperties() {
        val video = YouTubeVideoItem(
            videoId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            channelTitle = "Rick Astley",
            isOfficial = true
        )
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", video.watchUrl)
        assertEquals("vnd.youtube:dQw4w9WgXcQ", video.appIntentUri)
        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1&enablejsapi=1", video.embedUrl)
    }
}
