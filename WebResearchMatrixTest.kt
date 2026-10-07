package com.example

import com.example.web.ResearchEngine
import com.example.web.ResearchStatus
import com.example.web.WebContentExtractor
import com.example.web.WebSearchService
import com.example.web.provider.SearchProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WebResearchMatrixTest {

    @Test
    fun `test research status enum values completeness`() {
        val statuses = ResearchStatus.values()
        assertTrue(statuses.contains(ResearchStatus.RESEARCH_SUCCESS))
        assertTrue(statuses.contains(ResearchStatus.RESEARCH_NO_RESULTS))
        assertTrue(statuses.contains(ResearchStatus.RESEARCH_SEARCH_UNAVAILABLE))
        assertTrue(statuses.contains(ResearchStatus.RESEARCH_EXTRACTION_FAILED))
        assertTrue(statuses.contains(ResearchStatus.RESEARCH_SYNTHESIS_FAILED))
    }

    @Test
    fun `test query planner generates distinct specialized queries`() {
        val engine = ResearchEngine()
        val queries = engine.planQueries("Android 16 updates")
        assertTrue(queries.isNotEmpty())
        assertTrue(queries.size <= 3)
        assertTrue(queries[0].contains("Android 16", ignoreCase = true))

        val comparisonQueries = engine.planQueries("Kotlin vs Java")
        assertTrue(comparisonQueries.any { it.contains("comparison") || it.contains("pros cons") })
    }

    @Test
    fun `test search hit deduplication and domain diversity`() {
        val searchService = WebSearchService()
        val rawHits = listOf(
            WebSearchService.SearchHit("Page 1", "https://en.wikipedia.org/wiki/Kotlin?utm_source=test", "Snippet 1"),
            WebSearchService.SearchHit("Page 2", "https://en.wikipedia.org/wiki/Kotlin", "Duplicate URL stripped"),
            WebSearchService.SearchHit("Page 3", "https://en.wikipedia.org/wiki/Android", "Wikipedia 2"),
            WebSearchService.SearchHit("Page 4", "https://en.wikipedia.org/wiki/Java", "Wikipedia 3 - exceeds 2 per domain"),
            WebSearchService.SearchHit("Page 5", "https://developer.android.com/kotlin", "Android Dev 1"),
            WebSearchService.SearchHit("Page 6", "https://kotlinlang.org/docs/home.html", "Kotlin Lang 1")
        )

        val ranked = searchService.deduplicateAndRank(rawHits, maxResults = 4)
        // Ensure URLs were stripped of utm params and deduplicated
        val urls = ranked.map { it.url }
        assertEquals(urls.distinct().size, urls.size)

        // Count per domain in first 3 results to ensure diversity
        val domains = ranked.map { java.net.URI(it.url).host?.removePrefix("www.") }
        val wikipediaCount = domains.count { it == "en.wikipedia.org" }
        assertTrue(wikipediaCount <= 2)
    }

    @Test
    fun `test web content extractor strips html and extracts summary`() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Test Article Title</title></head>
            <body>
                <header><p>Header to strip</p></header>
                <nav><a href="/menu">Menu</a></nav>
                <article>
                    <h1>Primary Heading</h1>
                    <p>This is the first substantive paragraph containing meaningful technical information about Android systems.</p>
                    <p>A second detailed paragraph describing the architectural benefits of clean room databases and coroutine dispatchers.</p>
                </article>
                <footer><p>&copy; 2026 Example Corp</p></footer>
            </body>
            </html>
        """.trimIndent()

        val extracted = WebContentExtractor.extract(sampleHtml, "https://example.com/article")
        assertEquals("Test Article Title", extracted.title)
        assertTrue(extracted.cleanText.contains("Primary Heading"))
        assertTrue(extracted.cleanText.contains("meaningful technical information"))
        assertFalse(extracted.cleanText.contains("<article>"))
        assertFalse(extracted.cleanText.contains("Header to strip"))
        assertFalse(extracted.cleanText.contains("Menu"))
        assertTrue(extracted.summary.isNotBlank())
    }

    @Test
    fun `test web content extractor handles empty and malformed html safely`() {
        val emptyExtracted = WebContentExtractor.extract("", "https://example.com")
        assertEquals("Untitled", emptyExtracted.title)
        assertEquals("", emptyExtracted.cleanText)
        assertTrue(emptyExtracted.outboundLinks.isEmpty())

        val malformed = "<div><p>Unclosed paragraph <b>bold"
        val malformedExtracted = WebContentExtractor.extract(malformed, "https://example.com")
        assertTrue(malformedExtracted.cleanText.contains("Unclosed paragraph"))
        assertTrue(malformedExtracted.cleanText.contains("bold"))
    }

    @Test
    fun `test custom search provider integration`() = runBlocking {
        val mockProvider = object : SearchProvider {
            override val providerName: String = "MockProvider"
            override suspend fun search(query: String, maxResults: Int): List<WebSearchService.SearchHit> {
                return listOf(
                    WebSearchService.SearchHit("Mock Title", "https://mock.example.com/item", "Mock Snippet")
                )
            }
        }

        val service = WebSearchService(providers = listOf(mockProvider))
        val hits = service.searchDirect("test query")
        assertEquals(1, hits.size)
        assertEquals("MockProvider", hits[0].provider)
        assertEquals("https://mock.example.com/item", hits[0].url)
    }
}
