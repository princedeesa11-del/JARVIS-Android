package com.example.web.provider

import android.util.Log
import com.example.web.WebSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.regex.Pattern

class DuckDuckGoProvider(private val httpClient: OkHttpClient) : SearchProvider {
    override val providerName: String = "DuckDuckGo"
    private val tag = "DuckDuckGoProvider"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchService.SearchHit> = withContext(Dispatchers.IO) {
        val hits = mutableListOf<WebSearchService.SearchHit>()
        if (query.isBlank()) return@withContext hits

        // 1. DuckDuckGo Instant Answer API
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 JARVIS/2.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val abstractText = json.optString("AbstractText")
                    val abstractUrl = json.optString("AbstractURL")
                    val heading = json.optString("Heading")
                    if (abstractUrl.isNotBlank() && abstractText.isNotBlank()) {
                        hits.add(WebSearchService.SearchHit(heading.ifBlank { query }, abstractUrl, abstractText))
                    }

                    val related = json.optJSONArray("RelatedTopics")
                    if (related != null) {
                        for (i in 0 until related.length()) {
                            if (hits.size >= maxResults) break
                            val obj = related.optJSONObject(i) ?: continue
                            val text = obj.optString("Text")
                            val firstUrl = obj.optString("FirstURL")
                            if (firstUrl.isNotBlank() && text.isNotBlank()) {
                                hits.add(WebSearchService.SearchHit(text.take(80), firstUrl, text))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "DuckDuckGo API search error: ${e.message}")
        }

        // 2. DuckDuckGo HTML Fallback if hits < maxResults
        if (hits.size < maxResults) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://html.duckduckgo.com/html/?q=$encoded"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "text/html")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    // Regex parse result__a links: <a class="result__url" href="..."> or <a class="result__snippet" ...>
                    val linkPattern = Pattern.compile("<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE)
                    val matcher = linkPattern.matcher(html)
                    while (matcher.find() && hits.size < maxResults) {
                        val rawHref = matcher.group(1) ?: ""
                        val snippet = matcher.group(2)?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
                        if (rawHref.startsWith("http") && !rawHref.contains("duckduckgo.com")) {
                            hits.add(WebSearchService.SearchHit(query, rawHref, snippet))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "DuckDuckGo HTML fallback error: ${e.message}")
            }
        }

        hits
    }
}
