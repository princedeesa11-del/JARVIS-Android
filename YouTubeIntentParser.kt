package com.example.youtube.parser

import com.example.youtube.model.YouTubeIntent
import com.example.youtube.model.YouTubeParseResult
import java.util.Locale

/**
 * Natural language intent parser for YouTube automation supporting:
 * - English
 * - Hindi
 * - Hinglish
 * - Gujarati (both Gujarati script and Latin transliteration)
 */
object YouTubeIntentParser {

    /**
     * Parses raw user utterance into structured YouTube intent and query.
     * Returns null if utterance is not a YouTube-related directive.
     */
    fun parse(rawInput: String): YouTubeParseResult? {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. OPEN_YOUTUBE
        val openMatch = matchOpen(trimmed, lower)
        if (openMatch != null) return openMatch

        // 2. CONTROLS (Pause, Resume, Stop, Next, Previous, Volume, Mute)
        val controlMatch = matchControls(trimmed, lower)
        if (controlMatch != null) return controlMatch

        // 3. SEARCH_YOUTUBE
        val searchMatch = matchSearch(trimmed, lower)
        if (searchMatch != null) return searchMatch

        // 4. PLAY_YOUTUBE
        val playMatch = matchPlay(trimmed, lower)
        if (playMatch != null) return playMatch

        return null
    }

    private fun matchOpen(trimmed: String, lower: String): YouTubeParseResult? {
        // Gujarati script
        if (lower.contains("યુટ્યુબ ખોલો") || lower.contains("યુટ્યુબ ચાલુ કરો") ||
            lower.contains("યુટ્યુબ શરૂ કરો") || lower.contains("યુટ્યુબ ઓપન કરો") ||
            lower.contains("youtube ખોલો") || lower.contains("youtube ચાલુ કરો") ||
            (trimmed.contains("ખોલો") && (trimmed.contains("યુટ્યુબ") || lower.contains("youtube")))
        ) {
            return YouTubeParseResult(YouTubeIntent.OPEN_YOUTUBE, detectedLanguage = "gu")
        }

        // English & Hindi/Hinglish
        val openPatterns = listOf(
            "open youtube", "launch youtube", "start youtube", "go to youtube", "show youtube",
            "youtube kholo", "youtube open karo", "youtube chalu karo", "youtube start karo", "kholo youtube"
        )
        if (openPatterns.any { lower == it || lower.startsWith("$it ") }) {
            val lang = if (lower.contains("kholo") || lower.contains("chalu") || lower.contains("karo")) "hi" else "en"
            return YouTubeParseResult(YouTubeIntent.OPEN_YOUTUBE, detectedLanguage = lang)
        }

        return null
    }

    private fun matchControls(trimmed: String, lower: String): YouTubeParseResult? {
        // --- NEXT ---
        // Gujarati: આગળનું ગીત ચલાવો, આગળનો વિડીયો, આગળ નું ગીત
        if (trimmed.contains("આગળનું ગીત") || trimmed.contains("આગળનો વિડીયો") || trimmed.contains("આગળ નું") || trimmed.contains("આગળ નું ગીત")) {
            return YouTubeParseResult(YouTubeIntent.NEXT_YOUTUBE, detectedLanguage = "gu")
        }
        // Hindi & English
        val nextPatterns = listOf(
            "agla video chalao", "agla video", "agla gana chalao", "agla gana", "agli video", "next video chalao",
            "next video", "next song", "next track", "skip video", "play next video", "play next song",
            "youtube next", "next youtube", "skip this video"
        )
        if (nextPatterns.any { lower == it || lower.startsWith("$it ") }) {
            val lang = if (lower.contains("agla") || lower.contains("gana") || lower.contains("chalao")) "hi" else "en"
            return YouTubeParseResult(YouTubeIntent.NEXT_YOUTUBE, detectedLanguage = lang)
        }

        // --- PREVIOUS ---
        // Gujarati: પાછળનું ગીત, પાછળનો વિડીયો
        if (trimmed.contains("પાછળનું ગીત") || trimmed.contains("પાછળનો વિડીયો") || trimmed.contains("પાછળ નું")) {
            return YouTubeParseResult(YouTubeIntent.PREVIOUS_YOUTUBE, detectedLanguage = "gu")
        }
        val prevPatterns = listOf(
            "pichhla video chalao", "pichhla video", "pichhla gana", "pichhla gana chalao", "previous video chalao",
            "previous video", "previous song", "previous track", "go back video", "play previous",
            "youtube previous", "previous youtube"
        )
        if (prevPatterns.any { lower == it || lower.startsWith("$it ") }) {
            val lang = if (lower.contains("pichhla") || lower.contains("gana")) "hi" else "en"
            return YouTubeParseResult(YouTubeIntent.PREVIOUS_YOUTUBE, detectedLanguage = lang)
        }

        // --- PAUSE ---
        // Gujarati: થોભો, રોકો, યુટ્યુબ થોભો, વિડીયો રોકો
        if (trimmed == "થોભો" || trimmed == "રોકો" || trimmed.contains("યુટ્યુબ થોભો") || trimmed.contains("વિડીયો થોભો")) {
            return YouTubeParseResult(YouTubeIntent.PAUSE_YOUTUBE, detectedLanguage = "gu")
        }
        val pausePatterns = listOf(
            "pause youtube", "pause video", "pause the video", "pause playback", "pause", "pause karo",
            "youtube pause", "youtube pause karo", "video pause karo", "video roko", "youtube roko", "rok do"
        )
        if (pausePatterns.any { lower == it }) {
            val lang = if (lower.contains("roko") || lower.contains("karo")) "hi" else "en"
            return YouTubeParseResult(YouTubeIntent.PAUSE_YOUTUBE, detectedLanguage = lang)
        }

        // --- RESUME ---
        // Gujarati: ફરી ચાલુ કરો, યુટ્યુબ ફરી ચાલુ કરો
        if (trimmed.contains("ફરી ચાલુ કરો") || trimmed.contains("ચાલુ રાખો")) {
            return YouTubeParseResult(YouTubeIntent.RESUME_YOUTUBE, detectedLanguage = "gu")
        }
        val resumePatterns = listOf(
            "resume youtube", "resume video", "resume playback", "unpause youtube", "unpause video",
            "unpause", "resume", "youtube chalu karo", "video firse chalao", "firse chalao", "dobara chalao"
        )
        if (resumePatterns.any { lower == it }) {
            val lang = if (lower.contains("chalao") || lower.contains("firse")) "hi" else "en"
            return YouTubeParseResult(YouTubeIntent.RESUME_YOUTUBE, detectedLanguage = lang)
        }

        // --- STOP ---
        if (trimmed.contains("યુટ્યુબ બંધ કરો") || trimmed.contains("વિડીયો બંધ કરો")) {
            return YouTubeParseResult(YouTubeIntent.STOP_YOUTUBE, detectedLanguage = "gu")
        }
        val stopPatterns = listOf(
            "stop youtube", "stop video", "stop playback", "band karo video", "youtube band karo", "video band karo"
        )
        if (stopPatterns.any { lower == it }) {
            return YouTubeParseResult(YouTubeIntent.STOP_YOUTUBE, detectedLanguage = if (lower.contains("band")) "hi" else "en")
        }

        // --- MUTE ---
        if (trimmed.contains("અવાજ બંધ કરો") || trimmed.contains("અવાજ મ્યુટ કરો")) {
            return YouTubeParseResult(YouTubeIntent.MUTE_YOUTUBE, detectedLanguage = "gu")
        }
        val mutePatterns = listOf(
            "mute youtube", "mute video", "mute sound", "mute", "awaaz band karo", "sound band karo", "mute karo"
        )
        if (mutePatterns.any { lower == it }) {
            return YouTubeParseResult(YouTubeIntent.MUTE_YOUTUBE, detectedLanguage = if (lower.contains("awaaz")) "hi" else "en")
        }

        // --- UNMUTE ---
        if (trimmed.contains("અવાજ ચાલુ કરો") || trimmed.contains("અવાજ અનમ્યુટ કરો")) {
            return YouTubeParseResult(YouTubeIntent.UNMUTE_YOUTUBE, detectedLanguage = "gu")
        }
        val unmutePatterns = listOf(
            "unmute youtube", "unmute video", "unmute sound", "unmute", "awaaz chalu karo", "unmute karo"
        )
        if (unmutePatterns.any { lower == it }) {
            return YouTubeParseResult(YouTubeIntent.UNMUTE_YOUTUBE, detectedLanguage = if (lower.contains("awaaz")) "hi" else "en")
        }

        // --- VOLUME UP ---
        if (trimmed.contains("અવાજ વધારો") || trimmed.contains("વોલ્યુમ વધારો")) {
            return YouTubeParseResult(YouTubeIntent.VOLUME_UP, detectedLanguage = "gu")
        }
        val volUpPatterns = listOf(
            "volume up", "increase volume", "louder", "turn up volume", "raise volume",
            "awaaz badhao", "volume badhao", "awaaz tej karo"
        )
        if (volUpPatterns.any { lower == it }) {
            return YouTubeParseResult(YouTubeIntent.VOLUME_UP, detectedLanguage = if (lower.contains("awaaz")) "hi" else "en")
        }

        // --- VOLUME DOWN ---
        if (trimmed.contains("અવાજ ઘટાડો") || trimmed.contains("અવાજ ઓછો કરો") || trimmed.contains("વોલ્યુમ ઘટાડો")) {
            return YouTubeParseResult(YouTubeIntent.VOLUME_DOWN, detectedLanguage = "gu")
        }
        val volDownPatterns = listOf(
            "volume down", "decrease volume", "lower volume", "turn down volume", "softer",
            "awaaz kam karo", "volume kam karo", "awaaz dheemi karo"
        )
        if (volDownPatterns.any { lower == it }) {
            return YouTubeParseResult(YouTubeIntent.VOLUME_DOWN, detectedLanguage = if (lower.contains("awaaz")) "hi" else "en")
        }

        return null
    }

    private fun matchSearch(trimmed: String, lower: String): YouTubeParseResult? {
        // 1. Gujarati Script:
        // "YouTube પર Arijit Singh નું song શોધો"
        // "યુટ્યુબ પર Hanuman Chalisa શોધો"
        // "[query] શોધો"
        val guSearchRegexes = listOf(
            Regex("""(?:યુટ્યુબ|youtube)\s+પર\s+(.*?)\s+(?:નું\s+)?(?:song|ગીત|video|વિડીયો)?\s*શોધો""", RegexOption.IGNORE_CASE),
            Regex("""(?:યુટ્યુબ|youtube)\s+માં\s+(.*?)\s+(?:નું\s+)?(?:song|ગીત|video|વિડીયો)?\s*શોધો""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+શોધો$""", RegexOption.IGNORE_CASE)
        )
        for (regex in guSearchRegexes) {
            val match = regex.find(trimmed)
            if (match != null) {
                val q = cleanQuery(match.groupValues[1])
                if (q.isNotBlank()) {
                    return YouTubeParseResult(YouTubeIntent.SEARCH_YOUTUBE, query = q, detectedLanguage = "gu")
                }
            }
        }

        // 2. Hindi/Hinglish search:
        val hiSearchRegexes = listOf(
            Regex("""^youtube\s+p[ae]r?\s+(.*?)\s+search(?:\s+karo)?$""", RegexOption.IGNORE_CASE),
            Regex("""^youtube\s+p[ae]r?\s+(.*?)\s+dhundo$""", RegexOption.IGNORE_CASE),
            Regex("""^youtube\s+p[ae]r?\s+search\s+karo\s+(.*)$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+search\s+karo$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+dhundo$""", RegexOption.IGNORE_CASE)
        )
        for (regex in hiSearchRegexes) {
            val match = regex.find(trimmed)
            if (match != null) {
                val q = cleanQuery(match.groupValues[1])
                if (q.isNotBlank()) {
                    return YouTubeParseResult(YouTubeIntent.SEARCH_YOUTUBE, query = q, detectedLanguage = "hi")
                }
            }
        }

        // 3. English search:
        val enSearchRegexes = listOf(
            Regex("""^search\s+(.*?)\s+on\s+youtube$""", RegexOption.IGNORE_CASE),
            Regex("""^search\s+for\s+(.*?)\s+on\s+youtube$""", RegexOption.IGNORE_CASE),
            Regex("""^search\s+youtube\s+for\s+(.*)$""", RegexOption.IGNORE_CASE),
            Regex("""^youtube\s+search\s+(.*)$""", RegexOption.IGNORE_CASE),
            Regex("""^search\s+on\s+youtube\s+(.*)$""", RegexOption.IGNORE_CASE),
            Regex("""^search\s+for\s+(.*?)\s+youtube$""", RegexOption.IGNORE_CASE),
            Regex("""^search\s+(.*?)\s+in\s+youtube$""", RegexOption.IGNORE_CASE),
            Regex("""^search\s+(.*?)\s+youtube$""", RegexOption.IGNORE_CASE),
            Regex("""^search\s+(.*)$""", RegexOption.IGNORE_CASE)
        )
        for (regex in enSearchRegexes) {
            val match = regex.find(trimmed)
            if (match != null) {
                val rawGroup = match.groupValues[1].trim()
                val lowerGroup = rawGroup.lowercase(Locale.ROOT)
                // Ignore generic Google search prefixes handled elsewhere
                if (lowerGroup.startsWith("google") || lowerGroup.startsWith("web ") || lowerGroup.startsWith("the web")) {
                    continue
                }
                val q = cleanQuery(rawGroup)
                if (q.isNotBlank()) {
                    return YouTubeParseResult(YouTubeIntent.SEARCH_YOUTUBE, query = q, detectedLanguage = "en")
                }
            }
        }

        return null
    }

    private fun matchPlay(trimmed: String, lower: String): YouTubeParseResult? {
        // 1. Gujarati Script:
        // "YouTube પર Kesariya વગાડો"
        // "YouTube પર Arijit Singh નું song વગાડો"
        // "Kesariya વગાડો"
        // "આ ગીત ચલાવો" -> generic play
        // "આ ગીત વગાડો" -> generic play
        if (trimmed == "આ ગીત ચલાવો" || trimmed == "આ ગીત વગાડો" || trimmed == "ગીત વગાડો" || trimmed == "ગીત ચલાવો") {
            return YouTubeParseResult(YouTubeIntent.PLAY_YOUTUBE, query = "popular trending songs", detectedLanguage = "gu")
        }

        val guPlayRegexes = listOf(
            Regex("""(?:યુટ્યુબ|youtube)\s+પર\s+(.*?)\s+(?:નું\s+)?(?:song|ગીત|video|વિડીયો)?\s*(?:વગાડો|ચલાવો)""", RegexOption.IGNORE_CASE),
            Regex("""(?:યુટ્યુબ|youtube)\s+માં\s+(.*?)\s+(?:નું\s+)?(?:song|ગીત|video|વિડીયો)?\s*(?:વગાડો|ચલાવો)""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+(?:નું\s+)?(?:song|ગીત)?\s*વગાડો$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+(?:નું\s+)?(?:song|ગીત)?\s*ચલાવો$""", RegexOption.IGNORE_CASE)
        )
        for (regex in guPlayRegexes) {
            val match = regex.find(trimmed)
            if (match != null) {
                val q = cleanQuery(match.groupValues[1])
                if (q.isNotBlank()) {
                    return YouTubeParseResult(YouTubeIntent.PLAY_YOUTUBE, query = q, detectedLanguage = "gu")
                }
            }
        }

        // 2. Hindi/Hinglish Play:
        // "YouTube par Kesariya chalao"
        // "Arijit Singh ka song chalao"
        // "Kesariya baja do"
        // "Kesariya bajao"
        // "Kesariya chalao"
        // "chalao [query]"
        val hiPlayRegexes = listOf(
            Regex("""^youtube\s+p[ae]r?\s+(.*?)\s+chalao$""", RegexOption.IGNORE_CASE),
            Regex("""^youtube\s+p[ae]r?\s+(.*?)\s+baja\s+do$""", RegexOption.IGNORE_CASE),
            Regex("""^youtube\s+p[ae]r?\s+(.*?)\s+bajao$""", RegexOption.IGNORE_CASE),
            Regex("""^youtube\s+p[ae]r?\s+(.*?)\s+play(?:\s+karo)?$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+ka\s+song\s+chalao$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+ka\s+gana\s+chalao$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+ki\s+video\s+chalao$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+ke\s+gaane\s+chalao$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+gana\s+chalao$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+song\s+chalao$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+baja\s+do$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+bajao$""", RegexOption.IGNORE_CASE),
            Regex("""^(.*?)\s+chalao$""", RegexOption.IGNORE_CASE),
            Regex("""^chalao\s+(.*)$""", RegexOption.IGNORE_CASE)
        )
        for (regex in hiPlayRegexes) {
            val match = regex.find(trimmed)
            if (match != null) {
                val q = cleanQuery(match.groupValues[1])
                if (q.isNotBlank()) {
                    return YouTubeParseResult(YouTubeIntent.PLAY_YOUTUBE, query = q, detectedLanguage = "hi")
                }
            }
        }

        // 3. English Play:
        val enPlayRegexes = listOf(
            Regex("""^play\s+(?:the\s+)?first\s+video\s+of\s+(.*?)(?:\s+on\s+youtube)?$""", RegexOption.IGNORE_CASE),
            Regex("""^play\s+(?:the\s+)?first\s+video\s+for\s+(.*?)(?:\s+on\s+youtube)?$""", RegexOption.IGNORE_CASE),
            Regex("""^play\s+(.*?)\s+on\s+youtube$""", RegexOption.IGNORE_CASE),
            Regex("""^play\s+(.*?)\s+in\s+youtube$""", RegexOption.IGNORE_CASE),
            Regex("""^play\s+(.*?)\s+youtube$""", RegexOption.IGNORE_CASE),
            Regex("""^play\s+on\s+youtube\s+(.*)$""", RegexOption.IGNORE_CASE),
            Regex("""^play\s+song\s+(.*)$""", RegexOption.IGNORE_CASE),
            Regex("""^play\s+video\s+(.*)$""", RegexOption.IGNORE_CASE),
            Regex("""^play\s+(.*)$""", RegexOption.IGNORE_CASE)
        )
        for (regex in enPlayRegexes) {
            val match = regex.find(trimmed)
            if (match != null) {
                val rawGroup = match.groupValues[1].trim()
                val lowerGroup = rawGroup.lowercase(Locale.ROOT)
                // Avoid matching media general controls like "play music" or "play"
                if (lowerGroup == "music" || lowerGroup == "audio" || lowerGroup.isBlank()) {
                    continue
                }
                // Avoid matching spotify commands e.g. "play ... on spotify"
                if (lowerGroup.endsWith("on spotify") || lowerGroup.endsWith("in spotify")) {
                    continue
                }
                val q = cleanQuery(rawGroup)
                if (q.isNotBlank()) {
                    return YouTubeParseResult(YouTubeIntent.PLAY_YOUTUBE, query = q, detectedLanguage = "en")
                }
            }
        }

        return null
    }

    /**
     * Cleans natural language artifacts and filler words from query
     */
    fun cleanQuery(raw: String): String {
        var q = raw.trim()
        // Strip trailing punctuation
        q = q.replace(Regex("[.,!?;:]+$"), "").trim()

        // Strip prefixes
        val prefixes = listOf(
            "on youtube", "in youtube", "from youtube", "youtube par", "youtube pe", "youtube ma",
            "the song ", "song of ", "songs of ", "video of ", "track of ", "audio of "
        )
        for (p in prefixes) {
            if (q.lowercase(Locale.ROOT).startsWith(p)) {
                q = q.substring(p.length).trim()
            }
        }

        // Strip suffixes
        val suffixes = listOf(
            "on youtube", "in youtube", "on yt", "in yt", "youtube", "yt",
            "ka song", "ke gaane", "ka gana", "ki video", "nu song", "nu geet",
            "song", "songs", "gana", "gaane", "video", "videos"
        )
        for (s in suffixes) {
            val lower = q.lowercase(Locale.ROOT)
            if (lower.endsWith(" $s")) {
                q = q.substring(0, q.length - s.length - 1).trim()
            }
        }

        return q.trim()
    }
}
