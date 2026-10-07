package com.example.web

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full Web Research Pipeline Engine.
 * Workflow:
 * USER QUERY -> QUERY PLANNER -> MULTIPLE SEARCH QUERIES -> SEARCH RESULTS
 * -> OPEN RELEVANT PAGES -> EXTRACT READABLE CONTENT -> FOLLOW RELEVANT LINKS
 * -> COMPARE SOURCES -> DETECT CONFLICTS -> SUMMARIZE -> RETURN SOURCES/CITATIONS
 */
class ResearchEngine(
    private val searchService: WebSearchService = WebSearchService(),
    private val fetcher: WebPageFetcher = WebPageFetcher()
) {
    private val tag = "ResearchEngine"

    /**
     * Query Planner: Breaks a complex user topic into multiple specialized search queries.
     */
    fun planQueries(userQuery: String): List<String> {
        val clean = userQuery.trim()
        val queries = mutableListOf<String>()
        queries.add(clean)

        val lower = clean.lowercase()
        if (lower.contains("android 16") || lower.contains("android")) {
            queries.add("$clean official release features changes")
            queries.add("$clean developer API changes")
            queries.add("$clean security and privacy updates")
        } else if (lower.contains("vs") || lower.contains("compare") || lower.contains("difference")) {
            queries.add("$clean comparison pros cons")
            queries.add("$clean benchmark specifications")
        } else {
            queries.add("$clean overview facts documentation")
            queries.add("$clean latest updates news")
        }

        return queries.distinct().take(3)
    }

    /**
     * Executes the comprehensive research pipeline on a given topic.
     */
    suspend fun researchTopic(topic: String): ResearchResult = withContext(Dispatchers.IO) {
        val plannedQueries = planQueries(topic)
        Log.i(tag, "Executing research with planned queries: $plannedQueries")

        // 1. Search across all planned queries
        val discoveredUrls = mutableListOf<String>()
        var searchFailed = false
        try {
            for (q in plannedQueries) {
                val hits = searchService.searchDirect(q)
                for (hit in hits) {
                    if (!discoveredUrls.contains(hit.url) && !hit.url.contains("duckduckgo.com")) {
                        discoveredUrls.add(hit.url)
                    }
                }
            }
        } catch (e: Exception) {
            searchFailed = true
        }

        if (discoveredUrls.isEmpty()) {
            val status = if (searchFailed) ResearchStatus.RESEARCH_SEARCH_UNAVAILABLE else ResearchStatus.RESEARCH_NO_RESULTS
            val msg = if (searchFailed) "Search providers were unavailable or unreachable." else "No search results found for topic '$topic'."
            return@withContext ResearchResult(
                query = topic,
                sources = emptyList(),
                summary = msg,
                keyFindings = emptyList(),
                conflicts = emptyList(),
                limitations = listOf(msg),
                status = status
            )
        }

        // 2. Open top relevant pages (up to 4)
        val candidateUrls = discoveredUrls.take(4)
        val analyzedSources = mutableListOf<SourceResult>()

        for (url in candidateUrls) {
            val fetchResult = fetcher.fetchUrl(url)
            if (fetchResult.isSuccess) {
                val extracted = WebContentExtractor.extract(fetchResult.html, url)
                analyzedSources.add(
                    SourceResult(
                        title = extracted.title,
                        url = url,
                        domain = fetchResult.domain,
                        retrievedAt = System.currentTimeMillis(),
                        content = extracted.cleanText.take(1500),
                        summary = extracted.summary,
                        relevance = computeRelevanceScore(topic, extracted.cleanText),
                        errors = null
                    )
                )
            } else {
                analyzedSources.add(
                    SourceResult(
                        title = "Source Unavailable",
                        url = url,
                        domain = fetcher.extractDomain(url),
                        retrievedAt = System.currentTimeMillis(),
                        content = "",
                        summary = "",
                        relevance = 0.0f,
                        errors = fetchResult.errorMessage ?: "SOURCE_UNAVAILABLE"
                    )
                )
            }
        }

        // 3. Compare sources, detect conflicts & synthesize findings
        val successfulSources = analyzedSources.filter { it.errors == null && it.content.isNotBlank() }

        val keyFindings = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val limitations = mutableListOf<String>()

        if (successfulSources.isEmpty()) {
            limitations.add("No readable online sources could be fetched directly (possible network limitation, authentication, or paywall).")
            return@withContext ResearchResult(
                query = topic,
                sources = analyzedSources,
                summary = "Research was unable to access live web page bodies. Candidate URLs: ${candidateUrls.joinToString()}",
                keyFindings = emptyList(),
                conflicts = emptyList(),
                limitations = limitations,
                status = ResearchStatus.RESEARCH_EXTRACTION_FAILED
            )
        }

        try {
            for (src in successfulSources) {
                if (src.summary.isNotBlank()) {
                    keyFindings.add("[${src.domain}] ${src.summary}")
                }
            }

            // Conflict detection across sources
            val yearMentions = mutableMapOf<String, MutableList<String>>()
            for (src in successfulSources) {
                val years = Regex("\\b(202[4-9]|2030)\\b").findAll(src.content).map { it.value }.toSet()
                for (y in years) {
                    yearMentions.getOrPut(y) { mutableListOf() }.add(src.domain)
                }
            }
            if (yearMentions.size > 1) {
                conflicts.add("Multiple timeline targets referenced across sources: " +
                    yearMentions.entries.joinToString("; ") { "${it.key} in [${it.value.joinToString()}]" })
            }

            val summary = buildString {
                append("Research synthesis for '$topic' based on ${successfulSources.size} verified web source(s):\n\n")
                successfulSources.forEachIndexed { idx, src ->
                    append("${idx + 1}. ${src.title} (${src.domain})\n")
                    append("   • Citation: ${src.url}\n")
                    if (src.summary.isNotBlank()) {
                        append("   • Key insight: ${src.summary}\n")
                    }
                }
            }

            ResearchResult(
                query = topic,
                sources = analyzedSources,
                summary = summary,
                keyFindings = keyFindings,
                conflicts = conflicts,
                limitations = limitations,
                status = ResearchStatus.RESEARCH_SUCCESS
            )
        } catch (e: Exception) {
            ResearchResult(
                query = topic,
                sources = analyzedSources,
                summary = "Failed to synthesize findings: ${e.message}",
                keyFindings = emptyList(),
                conflicts = emptyList(),
                limitations = listOf("Synthesis error: ${e.message}"),
                status = ResearchStatus.RESEARCH_SYNTHESIS_FAILED
            )
        }
    }

    /**
     * Compares multiple specific URLs directly.
     */
    suspend fun compareSources(urls: List<String>): Map<String, Any> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SourceResult>()
        for (url in urls.take(5)) {
            val fetchResult = fetcher.fetchUrl(url)
            if (fetchResult.isSuccess) {
                val extracted = WebContentExtractor.extract(fetchResult.html, url)
                results.add(
                    SourceResult(
                        title = extracted.title,
                        url = url,
                        domain = fetchResult.domain,
                        retrievedAt = System.currentTimeMillis(),
                        content = extracted.cleanText.take(1200),
                        summary = extracted.summary,
                        relevance = 1.0f,
                        errors = null
                    )
                )
            } else {
                results.add(
                    SourceResult(
                        title = "Unavailable",
                        url = url,
                        domain = fetcher.extractDomain(url),
                        retrievedAt = System.currentTimeMillis(),
                        content = "",
                        summary = "",
                        relevance = 0.0f,
                        errors = fetchResult.errorMessage ?: "SOURCE_UNAVAILABLE"
                    )
                )
            }
        }

        val comparisonReport = buildString {
            append("Source Comparison Report (${results.size} sources analyzed):\n")
            for (res in results) {
                if (res.errors != null) {
                    append("• [${res.domain}] Failed: ${res.errors}\n")
                } else {
                    append("• [${res.domain}] '${res.title}': ${res.summary}\n")
                }
            }
        }

        mapOf(
            "report" to comparisonReport,
            "sources" to results.map { mapOf("url" to it.url, "domain" to it.domain, "status" to if (it.errors == null) "SUCCESS" else "SOURCE_UNAVAILABLE") }
        )
    }

    /**
     * Directly extracts readable page text from a single URL.
     */
    suspend fun extractPageText(url: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val fetch = fetcher.fetchUrl(url)
        if (!fetch.isSuccess) {
            return@withContext false to (fetch.errorMessage ?: "SOURCE_UNAVAILABLE")
        }
        val extracted = WebContentExtractor.extract(fetch.html, url)
        true to "Title: ${extracted.title}\n\n${extracted.cleanText}"
    }

    /**
     * Follows an outbound link found on a web page.
     */
    suspend fun followLink(baseUrl: String, linkHrefOrIndex: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val fetch = fetcher.fetchUrl(baseUrl)
        if (!fetch.isSuccess) {
            return@withContext false to "Cannot fetch base page: ${fetch.errorMessage}"
        }
        val links = WebContentExtractor.extractLinks(fetch.html, baseUrl)
        if (links.isEmpty()) {
            return@withContext false to "No outbound hyperlinks found on $baseUrl"
        }

        val targetUrl: String = if (linkHrefOrIndex.toIntOrNull() != null) {
            val idx = (linkHrefOrIndex.toInt() - 1).coerceIn(0, links.size - 1)
            links[idx]
        } else {
            val match = links.find { it.contains(linkHrefOrIndex, ignoreCase = true) }
            match ?: links.first()
        }

        extractPageText(targetUrl)
    }

    private fun computeRelevanceScore(query: String, text: String): Float {
        val keywords = query.lowercase().split("\\s+".toRegex()).filter { it.length > 2 }
        if (keywords.isEmpty()) return 0.5f
        val lowerText = text.lowercase()
        val matches = keywords.count { lowerText.contains(it) }
        return (matches.toFloat() / keywords.size.toFloat()).coerceIn(0.0f, 1.0f)
    }
}
