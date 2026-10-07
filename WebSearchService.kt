package com.example.web

import android.net.Uri
import com.example.web.provider.DuckDuckGoProvider
import com.example.web.provider.SearchProvider
import com.example.web.provider.SearxngProvider
import com.example.web.provider.WikipediaProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Real Multi-Source Web Search Service conforming to Requirement 4:
 * Clean SearchProvider abstraction, multiple fallback providers (DuckDuckGo, Wikipedia, SearXNG),
 * cross-provider URL deduplication, and domain diversity ranking.
 */
class WebSearchService(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val providers: List<SearchProvider> = listOf(
        DuckDuckGoProvider(httpClient),
        WikipediaProvider(httpClient),
        SearxngProvider(httpClient)
    )
) {
    data class SearchHit(
        val title: String,
        val url: String,
        val snippet: String,
        val provider: String = "Web"
    )

    data class ResearchSource(
        val title: String,
        val url: String,
        val retrievedAt: Long,
        val extractedText: String,
        val summary: String
    )

    data class LegacyResearchResult(
        val query: String,
        val sources: List<ResearchSource>,
        val summary: String,
        val conflicts: String,
        val confidenceScore: Float,
        val keyFindings: List<String> = emptyList()
    )

    /**
     * Executes search across multiple providers with fallback, deduplication, and domain diversity.
     */
    suspend fun searchDirect(query: String, maxResults: Int = 8): List<SearchHit> = withContext(Dispatchers.IO) {
        val hits = mutableListOf<SearchHit>()
        if (query.isBlank()) return@withContext hits

        // Query providers in order with fallback
        for (provider in providers) {
            try {
                val results = provider.search(query, maxResults)
                for (hit in results) {
                    hits.add(hit.copy(provider = provider.providerName))
                }
            } catch (e: Exception) {
                // Next provider acts as fallback
            }
        }

        // Deduplicate and rank by domain diversity
        deduplicateAndRank(hits, maxResults)
    }

    /**
     * Canonicalizes and deduplicates URLs, stripping tracking parameters and balancing domain diversity.
     */
    fun deduplicateAndRank(rawHits: List<SearchHit>, maxResults: Int): List<SearchHit> {
        val seenUrls = mutableSetOf<String>()
        val domainCounts = mutableMapOf<String, Int>()
        val ranked = mutableListOf<SearchHit>()

        for (hit in rawHits) {
            val canonicalUrl = canonicalizeUrl(hit.url)
            if (canonicalUrl.isBlank() || seenUrls.contains(canonicalUrl)) continue
            seenUrls.add(canonicalUrl)

            val domain = extractDomain(canonicalUrl)
            val count = domainCounts.getOrDefault(domain, 0)
            // Limit each domain to max 2 entries to enforce diversity
            if (count < 2) {
                domainCounts[domain] = count + 1
                ranked.add(hit.copy(url = canonicalUrl))
            }
            if (ranked.size >= maxResults) break
        }

        // If not enough results after diversity filter, backfill remaining unique URLs
        if (ranked.size < maxResults) {
            for (hit in rawHits) {
                val canonicalUrl = canonicalizeUrl(hit.url)
                if (canonicalUrl.isNotBlank() && !ranked.any { it.url == canonicalUrl }) {
                    ranked.add(hit.copy(url = canonicalUrl))
                }
                if (ranked.size >= maxResults) break
            }
        }

        return ranked
    }

    private fun canonicalizeUrl(rawUrl: String): String {
        return try {
            val uri = Uri.parse(rawUrl.trim())
            if (uri.scheme == null || !uri.scheme!!.startsWith("http")) return ""
            // Strip tracking query params
            val trackingParams = setOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid")
            val cleanQuery = uri.queryParameterNames
                .filterNot { trackingParams.contains(it.lowercase()) }
                .joinToString("&") { key -> "$key=${uri.getQueryParameter(key)}" }

            val builder = uri.buildUpon().clearQuery()
            if (cleanQuery.isNotBlank()) {
                builder.encodedQuery(cleanQuery)
            }
            builder.fragment(null).build().toString()
        } catch (e: Exception) {
            rawUrl.trim()
        }
    }

    private fun extractDomain(url: String): String {
        return try {
            val uri = URI(url)
            uri.host?.removePrefix("www.") ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    suspend fun search(query: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext false to "Empty search query"

        try {
            val hits = searchDirect(query)
            if (hits.isEmpty()) {
                return@withContext false to "No search results found for '$query'."
            }
            val sb = StringBuilder()
            sb.append("Web Search results for '$query':\n\n")
            for ((idx, hit) in hits.withIndex()) {
                sb.append("${idx + 1}. ${hit.title} [via ${hit.provider}]\n")
                sb.append("   • URL: ${hit.url}\n")
                if (hit.snippet.isNotBlank()) {
                    sb.append("   • Snippet: ${hit.snippet}\n")
                }
                sb.append("\n")
            }
            true to sb.toString().trim()
        } catch (e: Exception) {
            false to "Web search error: ${e.localizedMessage ?: "Network failed"}"
        }
    }

    suspend fun extractPageText(url: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val fetcher = WebPageFetcher(httpClient)
        val res = fetcher.fetchUrl(url)
        if (!res.isSuccess) {
            return@withContext false to (res.errorMessage ?: "SOURCE_UNAVAILABLE")
        }
        val extracted = WebContentExtractor.extract(res.html, url)
        val text = buildString {
            append("Page Title: ${extracted.title}\n")
            append("Source: $url\n\n")
            append(extracted.cleanText.take(3000))
            if (extracted.cleanText.length > 3000) append("\n...[Truncated]")
        }
        true to text
    }

    suspend fun researchTopic(topic: String): LegacyResearchResult = withContext(Dispatchers.IO) {
        val engine = ResearchEngine(this@WebSearchService, WebPageFetcher(httpClient))
        val res = engine.researchTopic(topic)
        val legacySources = res.sources.map {
            ResearchSource(
                title = it.title,
                url = it.url,
                retrievedAt = it.retrievedAt,
                extractedText = it.content,
                summary = it.summary
            )
        }
        LegacyResearchResult(
            query = res.query,
            sources = legacySources,
            summary = res.summary,
            conflicts = if (res.conflicts.isNotEmpty()) res.conflicts.joinToString("\n") else "None detected",
            confidenceScore = if (legacySources.any { it.extractedText.isNotBlank() }) 0.95f else 0.4f,
            keyFindings = res.keyFindings
        )
    }

    suspend fun compareSources(urls: List<String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (urls.size < 2) return@withContext false to "Provide at least two URLs to compare."
        val engine = ResearchEngine(this@WebSearchService, WebPageFetcher(httpClient))
        val comparison = engine.compareSources(urls)
        val report = comparison["report"] as? String ?: "Comparison completed."
        true to report
    }
}
