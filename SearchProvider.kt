package com.example.web.provider

import com.example.web.WebSearchService

/**
 * Clean abstraction for Web Search Providers conforming to Requirement 4:
 * Multi-source fallback search (SearXNG / DuckDuckGo / Wikipedia).
 */
interface SearchProvider {
    val providerName: String
    suspend fun search(query: String, maxResults: Int = 5): List<WebSearchService.SearchHit>
}
