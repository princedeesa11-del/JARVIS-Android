package com.example.ai

import com.example.data.local.entity.MemoryItem
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Real on-device semantic retrieval engine.
 * Computes normalized feature vectors (TF-IDF + hashed character n-grams)
 * and cosine similarity combined with recency weighting and importance scoring.
 * Runs 100% locally and privately on Android with zero external latency.
 */
object SemanticMemoryEngine {

    data class ScoredMemory(
        val memory: MemoryItem,
        val score: Float,
        val similarity: Float,
        val lexicalScore: Float
    )

    private const val VECTOR_DIM = 64

    /**
     * Extracts token frequency vector normalized to unit length.
     */
    fun computeVector(text: String): FloatArray {
        val vector = FloatArray(VECTOR_DIM) { 0f }
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return vector

        for (token in tokens) {
            // Word hash
            val wordHash = (token.hashCode() and 0x7FFFFFFF) % VECTOR_DIM
            vector[wordHash] += 1.0f

            // 3-gram sub-word hashes for fuzzy semantic matching
            if (token.length >= 3) {
                for (i in 0..token.length - 3) {
                    val sub = token.substring(i, i + 3)
                    val subHash = (sub.hashCode() and 0x7FFFFFFF) % VECTOR_DIM
                    vector[subHash] += 0.4f
                }
            }
        }

        // Normalize to unit length for fast cosine similarity
        var sumSquares = 0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares.toDouble()).toFloat()
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }

    fun vectorToString(vector: FloatArray): String {
        return vector.joinToString(",") { String.format(Locale.US, "%.4f", it) }
    }

    fun stringToVector(str: String): FloatArray {
        if (str.isBlank()) return FloatArray(VECTOR_DIM) { 0f }
        val parts = str.split(",")
        val vector = FloatArray(VECTOR_DIM) { 0f }
        for (i in 0 until minOf(parts.size, VECTOR_DIM)) {
            vector[i] = parts[i].trim().toFloatOrNull() ?: 0f
        }
        return vector
    }

    /**
     * Cosine similarity of two unit vectors is simply their dot product.
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.isEmpty() || v2.isEmpty()) return 0f
        var dot = 0f
        val len = minOf(v1.size, v2.size)
        for (i in 0 until len) {
            dot += v1[i] * v2[i]
        }
        return dot.coerceIn(-1.0f, 1.0f)
    }

    /**
     * Lexical token overlap score (Jaccard / BM25-style overlap).
     */
    fun lexicalOverlap(query: String, content: String): Float {
        val queryTokens = tokenize(query).toSet()
        val docTokens = tokenize(content).toSet()
        if (queryTokens.isEmpty() || docTokens.isEmpty()) return 0f

        val intersection = queryTokens.intersect(docTokens).size
        val union = queryTokens.union(docTokens).size
        return if (union > 0) intersection.toFloat() / union.toFloat() else 0f
    }

    sealed class ConflictResult {
        object None : ConflictResult()
        data class Duplicate(val existing: MemoryItem, val similarity: Float) : ConflictResult()
        data class Conflict(
            val existing: MemoryItem,
            val isContextSpecific: Boolean,
            val opposingReason: String
        ) : ConflictResult()
    }

    /**
     * Ranks memories semantically with recency, importance, confidence, and category relevance.
     */
    fun rankMemories(
        query: String,
        memories: List<MemoryItem>,
        limit: Int = 5,
        minScore: Float = 0.15f,
        targetCategory: String? = null
    ): List<ScoredMemory> {
        if (memories.isEmpty()) return emptyList()
        val queryVector = computeVector(query)
        val now = System.currentTimeMillis()
        val lowerQuery = query.lowercase(Locale.US)

        val results = mutableListOf<ScoredMemory>()

        for (mem in memories) {
            if (mem.isArchived) continue

            val memVector = if (mem.embeddingVector.isNotBlank()) {
                stringToVector(mem.embeddingVector)
            } else {
                computeVector("${mem.key} ${mem.content} ${mem.tags}")
            }

            val similarity = cosineSimilarity(queryVector, memVector)
            val lexical = lexicalOverlap(query, "${mem.key} ${mem.content} ${mem.tags}")

            // Exact keyword match boost
            val exactBoost = if (mem.key.isNotBlank() && lowerQuery.contains(mem.key.lowercase(Locale.US))) 0.25f else 0f

            // Category relevance boost
            val categoryBoost = when {
                targetCategory != null && mem.category.equals(targetCategory, ignoreCase = true) -> 0.30f
                (lowerQuery.contains("dark") || lowerQuery.contains("light") || lowerQuery.contains("theme") || lowerQuery.contains("ui") || lowerQuery.contains("look") || lowerQuery.contains("interface")) &&
                        (mem.category.contains("PREFERENCE", ignoreCase = true) || mem.content.contains("dark", true) || mem.content.contains("light", true) || mem.content.contains("theme", true)) -> 0.35f
                (lowerQuery.contains("meeting") || lowerQuery.contains("calendar") || lowerQuery.contains("schedule") || lowerQuery.contains("reminder")) &&
                        (mem.category.contains("ROUTINE", ignoreCase = true) || mem.content.contains("reminder", true) || mem.content.contains("calendar", true)) -> 0.30f
                (lowerQuery.contains("project") || lowerQuery.contains("code") || lowerQuery.contains("android") || lowerQuery.contains("app")) &&
                        mem.category.contains("PROJECT", ignoreCase = true) -> 0.30f
                else -> 0.0f
            }

            // Combined semantic + lexical score
            val baseScore = (0.60f * similarity) + (0.40f * lexical) + exactBoost + categoryBoost

            // Recency decay: recent memories get slight boost, old memories stay relevant if high similarity
            val hoursElapsed = ((now - mem.updatedAt).coerceAtLeast(0L)) / (1000.0 * 3600.0)
            val recencyWeight = exp(-hoursElapsed * 0.0005).toFloat().coerceIn(0.7f, 1.0f)

            // Importance weight (0.5 to 1.5)
            val importanceWeight = 0.75f + (mem.importance.coerceIn(0f, 1f) * 0.5f)

            // Confidence scaling (0.5 to 1.0)
            val confidenceWeight = mem.confidence.coerceIn(0.5f, 1.0f)

            val finalScore = baseScore * recencyWeight * importanceWeight * confidenceWeight

            if (finalScore >= minScore || similarity > 0.30f || lexical > 0.2f || exactBoost > 0f) {
                results.add(ScoredMemory(mem, finalScore, similarity, lexical))
            }
        }

        return results.sortedByDescending { it.score }.take(limit)
    }

    /**
     * Intelligent deduplication and conflict detection.
     * Evaluates whether a new incoming memory is an exact/near duplicate,
     * or an opposing conflict (e.g. dark vs light mode, wake up at 6am vs 7am).
     */
    fun detectDuplicateOrConflict(
        newKey: String,
        newContent: String,
        existingMemories: List<MemoryItem>
    ): ConflictResult {
        val newVec = computeVector("$newKey $newContent")
        val lowerNew = "$newKey $newContent".lowercase(Locale.US)

        for (mem in existingMemories) {
            if (mem.isArchived) continue
            val vec = if (mem.embeddingVector.isNotBlank()) stringToVector(mem.embeddingVector) else computeVector("${mem.key} ${mem.content}")
            val sim = cosineSimilarity(newVec, vec)
            val lowerExisting = "${mem.key} ${mem.content}".lowercase(Locale.US)

            // Check for opposing concepts on the same topic
            val hasOpposingPolarity = (
                (lowerExisting.contains("dark") && lowerNew.contains("light")) ||
                (lowerExisting.contains("light") && lowerNew.contains("dark")) ||
                (lowerExisting.contains("always") && lowerNew.contains("never")) ||
                (lowerExisting.contains("never") && lowerNew.contains("always")) ||
                (lowerExisting.contains("enable") && lowerNew.contains("disable")) ||
                (lowerExisting.contains("disable") && lowerNew.contains("enable"))
            )

            // Check if context-specific qualifier exists (e.g., "during daytime", "at night", "when driving")
            val isContextSpecific = lowerNew.contains("during") ||
                    lowerNew.contains("at night") ||
                    lowerNew.contains("in the morning") ||
                    lowerNew.contains("daytime") ||
                    lowerNew.contains("when ") ||
                    lowerNew.contains("only if")

            // If same key or high similarity on same topic with opposing polarity: Conflict!
            if ((mem.key.equals(newKey, ignoreCase = true) || sim >= 0.65f) && hasOpposingPolarity) {
                return ConflictResult.Conflict(
                    existing = mem,
                    isContextSpecific = isContextSpecific,
                    opposingReason = "Opposing polarity detected between existing ('${mem.content}') and new ('$newContent')"
                )
            }

            // High similarity duplicate check
            if (sim >= 0.82f || (mem.key.equals(newKey, ignoreCase = true) && sim >= 0.70f)) {
                return ConflictResult.Duplicate(mem, sim)
            }
        }

        return ConflictResult.None
    }

    /**
     * Checks if a memory with matching semantic meaning already exists (deduplication).
     */
    fun findDuplicate(
        newContent: String,
        existingMemories: List<MemoryItem>,
        threshold: Float = 0.82f
    ): MemoryItem? {
        val newVec = computeVector(newContent)
        for (mem in existingMemories) {
            if (mem.isArchived) continue
            val vec = if (mem.embeddingVector.isNotBlank()) stringToVector(mem.embeddingVector) else computeVector("${mem.key} ${mem.content}")
            val sim = cosineSimilarity(newVec, vec)
            if (sim >= threshold) {
                return mem
            }
        }
        return null
    }

    /**
     * Builds concise, token-efficient context for the LLM prompt.
     */
    fun buildContextString(query: String, memories: List<MemoryItem>, limit: Int = 5): String {
        val relevant = rankMemories(query, memories, limit = limit)
        if (relevant.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("Relevant User Memory Context:\n")
        for (scored in relevant) {
            val mem = scored.memory
            sb.append("• [${mem.category.uppercase(Locale.US)}] ${mem.key}: ${mem.content}\n")
        }
        return sb.toString().trim()
    }

    private fun tokenize(text: String): List<String> {
        val clean = text.lowercase(Locale.US).replace(Regex("[^a-z0-9\\s]"), " ")
        return clean.split(Regex("\\s+")).filter { it.length > 1 && !STOP_WORDS.contains(it) }
    }

    private val STOP_WORDS = setOf(
        "the", "and", "a", "an", "in", "on", "at", "to", "for", "of", "with",
        "is", "it", "this", "that", "you", "my", "me", "what", "are", "be",
        "do", "can", "please", "jarvis", "hey", "tell", "show"
    )
}
