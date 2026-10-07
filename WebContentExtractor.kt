package com.example.web

import java.net.URI
import java.util.regex.Pattern

/**
 * Real HTML Content Extractor.
 * Extracts title, readable article text, meta tags, and outbound hyperlinks
 * without external heavy web engines.
 */
object WebContentExtractor {

    data class ExtractedContent(
        val title: String,
        val cleanText: String,
        val summary: String,
        val outboundLinks: List<String>
    )

    fun extract(html: String, baseUrl: String): ExtractedContent {
        if (html.isBlank()) {
            return ExtractedContent(
                title = "Untitled",
                cleanText = "",
                summary = "",
                outboundLinks = emptyList()
            )
        }

        val title = extractTitle(html)
        val links = extractLinks(html, baseUrl)
        val cleanText = stripHtml(html)

        // Build brief summary from first 3 meaningful paragraphs/sentences
        val sentences = cleanText.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length > 25 && !it.contains("cookie", ignoreCase = true) }
            .take(3)

        val summary = if (sentences.isNotEmpty()) {
            sentences.joinToString(" ")
        } else {
            cleanText.take(250).trim()
        }

        return ExtractedContent(
            title = title,
            cleanText = cleanText,
            summary = summary,
            outboundLinks = links
        )
    }

    private fun extractTitle(html: String): String {
        val titleMatcher = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
        if (titleMatcher.find()) {
            val rawTitle = titleMatcher.group(1)?.trim() ?: ""
            return unescapeHtml(rawTitle).take(120)
        }
        val h1Matcher = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
        if (h1Matcher.find()) {
            val rawH1 = h1Matcher.group(1)?.trim() ?: ""
            return stripHtml(rawH1).take(120)
        }
        return "Web Page"
    }

    private fun stripHtml(rawHtml: String): String {
        var text = rawHtml
        // Remove script, style, header, footer, nav tags and contents
        text = text.replace(Regex("(?is)<script.*?</script>"), " ")
        text = text.replace(Regex("(?is)<style.*?</style>"), " ")
        text = text.replace(Regex("(?is)<header.*?</header>"), " ")
        text = text.replace(Regex("(?is)<nav.*?</nav>"), " ")
        text = text.replace(Regex("(?is)<footer.*?</footer>"), " ")
        text = text.replace(Regex("(?is)<!--.*?-->"), " ")

        // Replace block tags with newline
        text = text.replace(Regex("(?i)</?(p|div|h[1-6]|li|br|tr)[^>]*>"), "\n")
        // Strip remaining HTML tags
        text = text.replace(Regex("<[^>]+>"), "")
        // Unescape entities
        text = unescapeHtml(text)

        // Collapse whitespace
        val lines = text.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return lines.joinToString("\n").take(8000) // Cap to 8000 chars for safe memory bounds
    }

    private fun unescapeHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&#8217;", "'")
            .replace("&#8216;", "'")
            .replace("&#8220;", "\"")
            .replace("&#8221;", "\"")
    }

    fun extractLinks(html: String, baseUrl: String): List<String> {
        val links = mutableListOf<String>()
        val linkMatcher = Pattern.compile("<a\\s+(?:[^>]*?\\s+)?href=([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE).matcher(html)
        while (linkMatcher.find() && links.size < 20) {
            val href = linkMatcher.group(2)?.trim() ?: continue
            val resolved = resolveUrl(baseUrl, href)
            if (resolved != null && (resolved.startsWith("http://") || resolved.startsWith("https://"))) {
                if (!links.contains(resolved)) {
                    links.add(resolved)
                }
            }
        }
        return links
    }

    fun resolveUrl(baseUrl: String, href: String): String? {
        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(href).toString()
        } catch (e: Exception) {
            if (href.startsWith("http://") || href.startsWith("https://")) href else null
        }
    }
}
