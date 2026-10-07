package com.example.web.provider

import android.util.Log
import com.example.web.WebSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Authoritative Wikipedia Search Provider.
 * Queries Wikimedia API OpenSearch endpoint returning real encyclopedia articles and citations.
 */
class WikipediaProvider(private val httpClient: OkHttpClient) : SearchProvider {
    override val providerName: String = "Wikipedia"
    private val tag = "WikipediaProvider"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchService.SearchHit> = withContext(Dispatchers.IO) {
        val hits = mutableListOf<WebSearchService.SearchHit>()
        if (query.isBlank()) return@withContext hits

        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://en.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=$maxResults&namespace=0&format=json"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "JARVIS-Android-Assistant/2.0 (contact: assistant@jarvis.local)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val array = JSONArray(body)
                // Format: [query, [titles], [descriptions], [urls]]
                if (array.length() >= 4) {
                    val titles = array.getJSONArray(1)
                    val descriptions = array.getJSONArray(2)
                    val urls = array.getJSONArray(3)

                    for (i in 0 until minOf(titles.length(), maxResults)) {
                        val title = titles.optString(i)
                        val desc = descriptions.optString(i)
                        val pageUrl = urls.optString(i)
                        if (pageUrl.isNotBlank()) {
                            hits.add(
                                WebSearchService.SearchHit(
                                    title = title.ifBlank { "Wikipedia - $query" },
                                    url = pageUrl,
                                    snippet = desc.ifBlank { "Wikipedia article on $title" }
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Wikipedia provider search error: ${e.message}")
        }

        hits
    }
}
