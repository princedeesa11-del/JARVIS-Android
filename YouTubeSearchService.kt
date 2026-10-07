package com.example.youtube.client

import android.content.Context
import android.util.Log
import com.example.youtube.config.YouTubeConfigStore
import com.example.youtube.model.YouTubeVideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Real YouTube Search Service:
 * 1. Checks YouTube Data API v3 if an API key is configured.
 * 2. Seamlessly falls back to direct DuckDuckGo/SearXNG site:youtube.com/watch resolution.
 * 3. Applies smart ranking algorithm (exact title match, official artist/channel, relevance scoring).
 */
class YouTubeSearchService(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val configStore = YouTubeConfigStore.getInstance(context)

    companion object {
        private const val TAG = "YouTubeSearchService"
        private val VIDEO_ID_REGEX = Pattern.compile("^[a-zA-Z0-9_-]{11}$")
        private val YT_WATCH_REGEX = Pattern.compile("(?:youtube\\.com/watch\\?v=|youtu\\.be/)([a-zA-Z0-9_-]{11})")
    }

    /**
     * Searches YouTube for given query and returns ranked results.
     */
    suspend fun searchVideos(query: String, maxResults: Int = 8): List<YouTubeVideoItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // 1. Try Official YouTube Data API if key is present
        val apiKey = configStore.getApiKey()
        if (apiKey.isNotBlank()) {
            val apiResults = searchViaDataApi(trimmed, apiKey, maxResults)
            if (apiResults.isNotEmpty()) {
                return@withContext rankResults(apiResults, trimmed)
            }
        }

        // 2. Direct Smart Search via Web query fallback
        val webResults = searchViaWebResolution(trimmed, maxResults)
        if (webResults.isNotEmpty()) {
            return@withContext rankResults(webResults, trimmed)
        }

        // 3. Fallback: If scraping or web search didn't resolve video IDs, generate a direct query item
        listOf(
            YouTubeVideoItem(
                videoId = "",
                title = trimmed,
                channelTitle = "YouTube Search",
                thumbnailUrl = "",
                description = "Direct YouTube search result for $trimmed"
            )
        )
    }

    /**
     * Executes official YouTube Data API v3 search
     */
    private fun searchViaDataApi(query: String, apiKey: String, maxResults: Int): List<YouTubeVideoItem> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/youtube/v3/search" +
                    "?part=snippet" +
                    "&type=video" +
                    "&maxResults=$maxResults" +
                    "&q=$encodedQuery" +
                    "&key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "JARVIS-Android/2.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "YouTube Data API error HTTP ${response.code}: ${response.message}")
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val items = root.optJSONArray("items") ?: return emptyList()

            val results = mutableListOf<YouTubeVideoItem>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val idObj = item.optJSONObject("id")
                val videoId = idObj?.optString("videoId") ?: ""
                if (!VIDEO_ID_REGEX.matcher(videoId).matches()) continue

                val snippet = item.optJSONObject("snippet")
                val title = snippet?.optString("title") ?: query
                val channelTitle = snippet?.optString("channelTitle") ?: ""
                val desc = snippet?.optString("description") ?: ""
                val thumbnails = snippet?.optJSONObject("thumbnails")
                val highThumb = thumbnails?.optJSONObject("high")?.optString("url")
                    ?: thumbnails?.optJSONObject("medium")?.optString("url")
                    ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

                val isOfficial = channelTitle.contains("VEVO", ignoreCase = true) ||
                        channelTitle.contains("Official", ignoreCase = true) ||
                        channelTitle.contains("T-Series", ignoreCase = true) ||
                        channelTitle.contains("Sony Music", ignoreCase = true) ||
                        channelTitle.contains("Zee Music", ignoreCase = true) ||
                        channelTitle.contains("Topic", ignoreCase = true)

                results.add(
                    YouTubeVideoItem(
                        videoId = videoId,
                        title = unescapeHtml(title),
                        channelTitle = channelTitle,
                        thumbnailUrl = highThumb,
                        description = desc,
                        isOfficial = isOfficial
                    )
                )
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "searchViaDataApi exception: ${e.message}")
            emptyList()
        }
    }

    /**
     * Resolves real YouTube videos via DuckDuckGo / SearXNG search without requiring an API key.
     */
    private fun searchViaWebResolution(query: String, maxResults: Int): List<YouTubeVideoItem> {
        val results = mutableListOf<YouTubeVideoItem>()
        val seenVideoIds = mutableSetOf<String>()

        try {
            val fullQuery = "$query site:youtube.com/watch"
            val encodedQuery = URLEncoder.encode(fullQuery, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""

                // Match links and snippets from DDG HTML
                val linkPattern = Pattern.compile(
                    """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                    Pattern.CASE_INSENSITIVE or Pattern.DOTALL
                )
                val urlPattern = Pattern.compile("""uddg=([^&]+)""")

                val matcher = linkPattern.matcher(html)
                while (matcher.find() && results.size < maxResults) {
                    val rawHref = matcher.group(1) ?: ""
                    val snippet = matcher.group(2)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""

                    // Extract actual target URL from DDG redirect url
                    val decodedUrl = if (rawHref.contains("uddg=")) {
                        val m = urlPattern.matcher(rawHref)
                        if (m.find()) URLDecoder.decode(m.group(1) ?: "", "UTF-8") else rawHref
                    } else {
                        rawHref
                    }

                    val ytMatcher = YT_WATCH_REGEX.matcher(decodedUrl)
                    if (ytMatcher.find()) {
                        val videoId = ytMatcher.group(1) ?: continue
                        if (seenVideoIds.add(videoId)) {
                            val cleanTitle = cleanSnippetTitle(snippet, query)
                            val isOfficial = snippet.contains("Official", ignoreCase = true) ||
                                    snippet.contains("VEVO", ignoreCase = true) ||
                                    snippet.contains("T-Series", ignoreCase = true)

                            results.add(
                                YouTubeVideoItem(
                                    videoId = videoId,
                                    title = cleanTitle,
                                    channelTitle = if (isOfficial) "Official Source" else "YouTube Creator",
                                    thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                                    description = snippet,
                                    isOfficial = isOfficial
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "searchViaWebResolution error: ${e.message}")
        }

        // Direct YouTube search HTML probe if DDG returned nothing
        if (results.isEmpty()) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val ytUrl = "https://www.youtube.com/results?search_query=$encodedQuery"
                val request = Request.Builder()
                    .url(ytUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    // Extract "videoId":"[a-zA-Z0-9_-]{11}"
                    val idFinder = Pattern.compile(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
                    val idMatcher = idFinder.matcher(body)
                    while (idMatcher.find() && results.size < maxResults) {
                        val vId = idMatcher.group(1) ?: continue
                        if (seenVideoIds.add(vId)) {
                            results.add(
                                YouTubeVideoItem(
                                    videoId = vId,
                                    title = "$query - YouTube Video",
                                    channelTitle = "YouTube",
                                    thumbnailUrl = "https://img.youtube.com/vi/$vId/hqdefault.jpg",
                                    description = "YouTube search result for $query"
                                )
                            )
                        }
                    }
                }
            } catch (e2: Exception) {
                Log.w(TAG, "Direct YouTube probe error: ${e2.message}")
            }
        }

        return results
    }

    /**
     * Smart Ranking Algorithm:
     * 1. Exact match (+100)
     * 2. Overlap of query tokens (+15 per token)
     * 3. Official Source / Channel bonus (+40)
     * 4. Penalize covers/reactions unless explicitly asked (-30)
     */
    fun rankResults(rawList: List<YouTubeVideoItem>, query: String): List<YouTubeVideoItem> {
        val queryLower = query.lowercase(Locale.ROOT)
        val queryWords = queryLower.split(Regex("\\s+")).filter { it.length > 2 }

        return rawList.map { item ->
            var score = 0
            val titleLower = item.title.lowercase(Locale.ROOT)
            val channelLower = item.channelTitle.lowercase(Locale.ROOT)

            // Exact match
            if (titleLower.contains(queryLower)) {
                score += 100
            }

            // Word overlap
            for (word in queryWords) {
                if (titleLower.contains(word)) score += 20
                if (channelLower.contains(word)) score += 15
            }

            // Official channel or audio
            if (item.isOfficial || channelLower.contains("official") || titleLower.contains("official video") || titleLower.contains("official audio")) {
                score += 40
            }

            // Penalty for covers/reactions/parody if not queried
            val isReactionCover = titleLower.contains("reaction") || titleLower.contains("cover") || titleLower.contains("parody")
            val askedForReactionCover = queryLower.contains("reaction") || queryLower.contains("cover")
            if (isReactionCover && !askedForReactionCover) {
                score -= 35
            }

            item.copy(relevanceScore = score)
        }.sortedByDescending { it.relevanceScore }
    }

    private fun cleanSnippetTitle(snippet: String, fallback: String): String {
        val decoded = unescapeHtml(snippet)
        val firstLine = decoded.lines().firstOrNull { it.isNotBlank() } ?: fallback
        return firstLine.take(90).trim()
    }

    private fun unescapeHtml(text: String): String {
        return text.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }
}
