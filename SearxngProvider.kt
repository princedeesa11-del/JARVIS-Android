package com.example.web.provider

import android.util.Log
import com.example.web.WebSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * SearXNG Metasearch Provider.
 * Queries public SearXNG instances with graceful timeout and fallback.
 */
class SearxngProvider(private val httpClient: OkHttpClient) : SearchProvider {
    override val providerName: String = "SearXNG"
    private val tag = "SearxngProvider"

    // Candidate public SearXNG endpoints
    private val instances = listOf(
        "https://searx.be",
        "https://search.ononoki.org",
        "https://baresearch.org"
    )

    override suspend fun search(query: String, maxResults: Int): List<WebSearchService.SearchHit> = withContext(Dispatchers.IO) {
        val hits = mutableListOf<WebSearchService.SearchHit>()
        if (query.isBlank()) return@withContext hits

        val encoded = URLEncoder.encode(query, "UTF-8")
        for (instance in instances) {
            try {
                val url = "$instance/search?q=$encoded&format=json&language=en"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "JARVIS-Research/2.0 (Mobile Assistant)")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.startsWith("{")) {
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            for (i in 0 until minOf(results.length(), maxResults)) {
                                val item = results.optJSONObject(i) ?: continue
                                val title = item.optString("title")
                                val hitUrl = item.optString("url")
                                val content = item.optString("content")
                                if (hitUrl.isNotBlank()) {
                                    hits.add(
                                        WebSearchService.SearchHit(
                                            title = title.ifBlank { query },
                                            url = hitUrl,
                                            snippet = content
                                        )
                                    )
                                }
                            }
                            if (hits.isNotEmpty()) break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "SearXNG instance $instance query error: ${e.message}")
            }
        }

        hits
    }
}
