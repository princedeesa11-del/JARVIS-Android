package com.example.web

/**
 * Explicit states for web research workflow conforming to Requirement 4:
 * RESEARCH_SUCCESS, RESEARCH_NO_RESULTS, RESEARCH_SEARCH_UNAVAILABLE,
 * RESEARCH_EXTRACTION_FAILED, RESEARCH_SYNTHESIS_FAILED.
 */
enum class ResearchStatus {
    RESEARCH_SUCCESS,
    RESEARCH_NO_RESULTS,
    RESEARCH_SEARCH_UNAVAILABLE,
    RESEARCH_EXTRACTION_FAILED,
    RESEARCH_SYNTHESIS_FAILED
}

/**
 * Result data class for an individual web page source analyzed during research.
 */
data class SourceResult(
    val title: String,
    val url: String,
    val domain: String,
    val retrievedAt: Long,
    val content: String,
    val summary: String,
    val relevance: Float,
    val errors: String? = null
)

/**
 * Multi-source comprehensive research synthesis result.
 */
data class ResearchResult(
    val query: String,
    val sources: List<SourceResult>,
    val summary: String,
    val keyFindings: List<String>,
    val conflicts: List<String>,
    val limitations: List<String>,
    val status: ResearchStatus = ResearchStatus.RESEARCH_SUCCESS,
    val timestamp: Long = System.currentTimeMillis()
)
