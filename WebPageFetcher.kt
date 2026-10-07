package com.example.web

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Real Web Page Fetcher supporting network requests, timeouts, retries,
 * rate-limit handling, and HTTP error distinction without fabricated content.
 */
class WebPageFetcher(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val tag = "WebPageFetcher"

    data class FetchResult(
        val isSuccess: Boolean,
        val html: String,
        val statusCode: Int,
        val url: String,
        val domain: String,
        val errorMessage: String? = null
    )

    suspend fun fetchUrl(targetUrl: String, maxRetries: Int = 2): FetchResult = withContext(Dispatchers.IO) {
        val domain = extractDomain(targetUrl)
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            return@withContext FetchResult(
                isSuccess = false,
                html = "",
                statusCode = 400,
                url = targetUrl,
                domain = domain,
                errorMessage = "Invalid URL protocol. Only http/https supported."
            )
        }

        var attempt = 0
        var lastException: Exception? = null
        var lastStatusCode = 0

        while (attempt <= maxRetries) {
            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 JARVIS/2.0")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    lastStatusCode = response.code
                    val body = response.body?.string() ?: ""

                    when (response.code) {
                        200 -> {
                            return@withContext FetchResult(
                                isSuccess = true,
                                html = body,
                                statusCode = 200,
                                url = targetUrl,
                                domain = domain
                            )
                        }
                        401, 403 -> {
                            return@withContext FetchResult(
                                isSuccess = false,
                                html = "",
                                statusCode = response.code,
                                url = targetUrl,
                                domain = domain,
                                errorMessage = "SOURCE_UNAVAILABLE (Authentication / Access Forbidden: HTTP ${response.code})"
                            )
                        }
                        429 -> {
                            Log.w(tag, "Rate limited on $domain. Retrying after delay...")
                            delay(1000L * (attempt + 1))
                        }
                        404 -> {
                            return@withContext FetchResult(
                                isSuccess = false,
                                html = "",
                                statusCode = 404,
                                url = targetUrl,
                                domain = domain,
                                errorMessage = "SOURCE_UNAVAILABLE (HTTP 404 Not Found)"
                            )
                        }
                        else -> {
                            Log.w(tag, "HTTP ${response.code} received for $targetUrl")
                        }
                    }
                }
            } catch (e: IOException) {
                lastException = e
                Log.w(tag, "Network attempt $attempt failed for $targetUrl: ${e.message}")
                delay(500L * (attempt + 1))
            } catch (e: Exception) {
                return@withContext FetchResult(
                    isSuccess = false,
                    html = "",
                    statusCode = 500,
                    url = targetUrl,
                    domain = domain,
                    errorMessage = "SOURCE_UNAVAILABLE (${e.message})"
                )
            }
            attempt++
        }

        FetchResult(
            isSuccess = false,
            html = "",
            statusCode = lastStatusCode,
            url = targetUrl,
            domain = domain,
            errorMessage = "SOURCE_UNAVAILABLE: Connection failed after $maxRetries retries (${lastException?.message ?: "HTTP $lastStatusCode"})"
        )
    }

    fun extractDomain(url: String): String {
        return try {
            val uri = URI(url)
            uri.host ?: "unknown_host"
        } catch (e: Exception) {
            "unknown_host"
        }
    }
}
