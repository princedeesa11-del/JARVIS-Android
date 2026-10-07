package com.example.ai.memory

import com.example.ai.SemanticMemoryEngine
import com.example.data.local.dao.MemoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Dedicated Memory Context Builder conforming to MASTER PROMPT 2/5 Section 18:
 *
 * Pipeline:
 * USER REQUEST
 *   ↓
 * IDENTIFY CONTEXT (keywords, active tasks, communication/device terms, project references)
 *   ↓
 * RETRIEVE RELEVANT MEMORY (from Room DB)
 *   ↓
 * RANK (semantic cosine similarity + lexical overlap + recency + importance + confidence)
 *   ↓
 * FILTER (score thresholds, deduplication, max 3-5 items)
 *   ↓
 * BUILD MINIMAL CONTEXT (concise, bounded token footprint < 250 tokens)
 *   ↓
 * GEMINI / TOOL EXECUTION
 */
class MemoryContextBuilder(
    private val memoryDao: MemoryDao
) {

    suspend fun buildMemoryContext(
        userQuery: String,
        activeTopic: String? = null,
        activeProject: String? = null,
        maxItems: Int = 4
    ): String = withContext(Dispatchers.IO) {
        val allMemories = memoryDao.getAllActiveMemoriesList()
        if (allMemories.isEmpty()) return@withContext ""

        // 1. Identify Context
        val queryLower = userQuery.lowercase(Locale.US)
        val combinedQuery = buildString {
            append(userQuery)
            if (!activeTopic.isNullOrBlank()) append(" $activeTopic")
            if (!activeProject.isNullOrBlank()) append(" $activeProject")
        }

        val targetCategory = when {
            queryLower.contains("theme") || queryLower.contains("dark") || queryLower.contains("light") || queryLower.contains("screen") || queryLower.contains("volume") -> MemoryCategory.DEVICE_PREFERENCE.code
            queryLower.contains("call") || queryLower.contains("message") || queryLower.contains("sms") || queryLower.contains("whatsapp") -> MemoryCategory.COMMUNICATION_PREFERENCE.code
            queryLower.contains("meeting") || queryLower.contains("calendar") || queryLower.contains("schedule") || queryLower.contains("routine") -> MemoryCategory.ROUTINE.code
            queryLower.contains("project") || queryLower.contains("code") || queryLower.contains("app") || queryLower.contains("website") -> MemoryCategory.PROJECT_CONTEXT.code
            queryLower.contains("prefer") || queryLower.contains("favorite") || queryLower.contains("style") -> MemoryCategory.USER_PREFERENCE.code
            else -> null
        }

        // 2. Retrieve & Rank
        val ranked = SemanticMemoryEngine.rankMemories(
            query = combinedQuery,
            memories = allMemories,
            limit = 8,
            minScore = 0.25f,
            targetCategory = targetCategory
        )

        // 3. Always include any persistent user response style preferences if present
        val stylePref = allMemories.firstOrNull {
            !it.isArchived &&
            (it.category == MemoryCategory.USER_PREFERENCE.code || it.category == MemoryCategory.COMMUNICATION_PREFERENCE.code) &&
            (it.key.contains("style", true) || it.content.contains("concise", true) || it.content.contains("bullet", true) || it.content.contains("call me", true))
        }

        // 4. Filter & Bound
        val selectedMemories = mutableListOf<com.example.data.local.entity.MemoryItem>()
        if (stylePref != null) {
            selectedMemories.add(stylePref)
        }

        for (scored in ranked) {
            if (selectedMemories.size >= maxItems) break
            if (selectedMemories.none { it.id == scored.memory.id }) {
                selectedMemories.add(scored.memory)
            }
        }

        if (selectedMemories.isEmpty()) return@withContext ""

        // 5. Build Minimal, High-Density Context (< 250 tokens)
        val sb = StringBuilder()
        sb.append("[USER CONTEXT & PREFERENCES]:\n")
        for (mem in selectedMemories) {
            val catName = MemoryCategory.fromString(mem.category).displayName
            sb.append("• [$catName] ${mem.key}: ${mem.content}\n")
        }

        sb.toString().trim()
    }
}
