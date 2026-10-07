package com.example.ai.memory

import com.example.ai.SemanticMemoryEngine
import com.example.data.local.dao.MemoryDao
import com.example.data.local.entity.MemoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Unified Memory Manager for J.A.R.V.I.S. conforming to MASTER PROMPT 2/5:
 * Coordinates storage, semantic deduplication, conflict resolution, correction,
 * explicit commands, and privacy-preserving audit logging.
 */
class MemoryManager(
    private val memoryDao: MemoryDao
) {

    suspend fun saveOrUpdateMemory(
        key: String,
        content: String,
        category: String? = null,
        importance: Float? = null,
        confidence: Float = 1.0f,
        source: String = "explicit_command",
        tags: String = ""
    ): MemoryOperationResult = withContext(Dispatchers.IO) {
        val trimmedKey = key.trim().ifBlank { "User Fact" }
        val trimmedContent = content.trim()
        if (trimmedContent.isBlank()) {
            return@withContext MemoryOperationResult.Failed("EMPTY_CONTENT", "Cannot save empty memory content.")
        }

        val memCategory = if (category != null && category.isNotBlank()) {
            MemoryCategory.fromString(category)
        } else {
            MemoryCategory.inferCategory(trimmedKey, trimmedContent)
        }

        val finalImportance = importance ?: memCategory.defaultImportance
        val existingMemories = memoryDao.getAllActiveMemoriesList()

        // 1. Run Semantic Deduplication & Conflict Detection
        val conflictCheck = SemanticMemoryEngine.detectDuplicateOrConflict(trimmedKey, trimmedContent, existingMemories)

        when (conflictCheck) {
            is SemanticMemoryEngine.ConflictResult.Duplicate -> {
                val existing = conflictCheck.existing
                val mergedTags = if (tags.isNotBlank()) {
                    if (existing.tags.isNotBlank()) "${existing.tags}, $tags" else tags
                } else existing.tags

                val updatedVec = SemanticMemoryEngine.vectorToString(
                    SemanticMemoryEngine.computeVector("${existing.key} $trimmedContent $mergedTags")
                )

                val updated = existing.copy(
                    content = trimmedContent,
                    category = memCategory.code,
                    importance = maxOf(existing.importance, finalImportance),
                    confidence = maxOf(existing.confidence, confidence),
                    tags = mergedTags,
                    embeddingVector = updatedVec,
                    updatedAt = System.currentTimeMillis()
                )

                memoryDao.updateMemory(updated)
                MemoryAuditLogger.log(
                    event = MemoryAuditLogger.AuditEvent.MEMORY_DEDUPLICATED,
                    memoryId = existing.id,
                    keyIdentifier = existing.key,
                    category = memCategory.code,
                    importance = updated.importance,
                    confidence = updated.confidence,
                    details = "Merged duplicate into memory #${existing.id} (sim: ${String.format(Locale.US, "%.2f", conflictCheck.similarity)})"
                )

                MemoryOperationResult.Updated(
                    memoryId = existing.id,
                    key = existing.key,
                    previousContent = existing.content,
                    newContent = trimmedContent,
                    detail = "Updated existing memory #${existing.id} ('${existing.key}') with latest information."
                )
            }

            is SemanticMemoryEngine.ConflictResult.Conflict -> {
                val existing = conflictCheck.existing
                if (conflictCheck.isContextSpecific) {
                    // Context-specific qualifier detected (e.g. "during daytime") -> create specific context preference
                    val specificKey = if (trimmedKey.contains("(")) trimmedKey else "$trimmedKey (Contextual)"
                    val vec = SemanticMemoryEngine.vectorToString(
                        SemanticMemoryEngine.computeVector("$specificKey $trimmedContent $tags")
                    )
                    val newId = memoryDao.insertMemory(
                        MemoryItem(
                            key = specificKey,
                            content = trimmedContent,
                            category = memCategory.code,
                            source = source,
                            importance = finalImportance,
                            confidence = confidence,
                            tags = tags,
                            embeddingVector = vec,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                    )

                    MemoryAuditLogger.log(
                        event = MemoryAuditLogger.AuditEvent.MEMORY_CONFLICT,
                        memoryId = newId,
                        keyIdentifier = specificKey,
                        category = memCategory.code,
                        importance = finalImportance,
                        confidence = confidence,
                        details = "Context-specific preference created alongside existing #${existing.id}"
                    )

                    MemoryOperationResult.Saved(
                        memoryId = newId,
                        key = specificKey,
                        category = memCategory,
                        detail = "Saved contextual preference alongside existing entry (#$newId)."
                    )
                } else {
                    // Unconditional correction -> Update existing memory with user's new preference!
                    val updatedVec = SemanticMemoryEngine.vectorToString(
                        SemanticMemoryEngine.computeVector("${existing.key} $trimmedContent $tags")
                    )
                    val updated = existing.copy(
                        content = trimmedContent,
                        category = memCategory.code,
                        importance = finalImportance,
                        confidence = confidence,
                        embeddingVector = updatedVec,
                        updatedAt = System.currentTimeMillis()
                    )

                    memoryDao.updateMemory(updated)
                    MemoryAuditLogger.log(
                        event = MemoryAuditLogger.AuditEvent.MEMORY_UPDATED,
                        memoryId = existing.id,
                        keyIdentifier = existing.key,
                        category = memCategory.code,
                        importance = finalImportance,
                        confidence = confidence,
                        details = "Resolved conflict: overwritten by user instruction"
                    )

                    MemoryOperationResult.Updated(
                        memoryId = existing.id,
                        key = existing.key,
                        previousContent = existing.content,
                        newContent = trimmedContent,
                        detail = "Preference updated: changed from '${existing.content}' to '$trimmedContent'."
                    )
                }
            }

            is SemanticMemoryEngine.ConflictResult.None -> {
                // New distinct memory record
                val vec = SemanticMemoryEngine.vectorToString(
                    SemanticMemoryEngine.computeVector("$trimmedKey $trimmedContent $tags")
                )
                val newId = memoryDao.insertMemory(
                    MemoryItem(
                        key = trimmedKey,
                        content = trimmedContent,
                        category = memCategory.code,
                        source = source,
                        importance = finalImportance,
                        confidence = confidence,
                        tags = tags,
                        embeddingVector = vec,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )

                MemoryAuditLogger.log(
                    event = MemoryAuditLogger.AuditEvent.MEMORY_CREATED,
                    memoryId = newId,
                    keyIdentifier = trimmedKey,
                    category = memCategory.code,
                    importance = finalImportance,
                    confidence = confidence,
                    details = "New memory entry saved"
                )

                MemoryOperationResult.Saved(
                    memoryId = newId,
                    key = trimmedKey,
                    category = memCategory,
                    detail = "Memory saved successfully with ID #$newId."
                )
            }
        }
    }

    suspend fun forgetMemory(queryOrId: String): MemoryOperationResult = withContext(Dispatchers.IO) {
        val trimmed = queryOrId.trim()
        if (trimmed.isBlank()) {
            return@withContext MemoryOperationResult.NotFound(trimmed, "No target provided to forget.")
        }

        // 1. Try numeric ID
        val numericId = trimmed.toLongOrNull()
        if (numericId != null) {
            val existing = memoryDao.getMemoryById(numericId)
            if (existing != null) {
                memoryDao.deleteById(numericId)
                // Verify deletion
                val check = memoryDao.getMemoryById(numericId)
                if (check == null) {
                    MemoryAuditLogger.log(
                        event = MemoryAuditLogger.AuditEvent.MEMORY_DELETED,
                        memoryId = numericId,
                        keyIdentifier = existing.key,
                        category = existing.category,
                        details = "Deleted by ID #$numericId"
                    )
                    return@withContext MemoryOperationResult.Deleted(
                        target = "#$numericId",
                        deletedCount = 1,
                        detail = "Memory #$numericId successfully removed from neural storage."
                    )
                }
            }
        }

        // 2. Try exact key match
        val byKey = memoryDao.getMemoryByKey(trimmed)
        if (byKey != null) {
            memoryDao.deleteByKey(trimmed)
            val verify = memoryDao.getMemoryByKey(trimmed)
            if (verify == null) {
                MemoryAuditLogger.log(
                    event = MemoryAuditLogger.AuditEvent.MEMORY_DELETED,
                    memoryId = byKey.id,
                    keyIdentifier = trimmed,
                    category = byKey.category,
                    details = "Deleted by key '$trimmed'"
                )
                return@withContext MemoryOperationResult.Deleted(
                    target = trimmed,
                    deletedCount = 1,
                    detail = "Memory '${byKey.key}' successfully removed from storage."
                )
            }
        }

        // 3. Try semantic match search to find what the user wants to forget
        val allMemories = memoryDao.getAllActiveMemoriesList()
        val ranked = SemanticMemoryEngine.rankMemories(trimmed, allMemories, limit = 3, minScore = 0.30f)
        if (ranked.isNotEmpty()) {
            var deletedCount = 0
            for (scored in ranked) {
                memoryDao.deleteById(scored.memory.id)
                deletedCount++
                MemoryAuditLogger.log(
                    event = MemoryAuditLogger.AuditEvent.MEMORY_DELETED,
                    memoryId = scored.memory.id,
                    keyIdentifier = scored.memory.key,
                    category = scored.memory.category,
                    details = "Deleted via semantic match score: ${String.format(Locale.US, "%.2f", scored.score)}"
                )
            }
            return@withContext MemoryOperationResult.Deleted(
                target = trimmed,
                deletedCount = deletedCount,
                detail = "Removed $deletedCount matching memories related to '$trimmed'."
            )
        }

        MemoryAuditLogger.log(
            event = MemoryAuditLogger.AuditEvent.MEMORY_IGNORED,
            keyIdentifier = trimmed,
            details = "Target memory not found for deletion"
        )
        MemoryOperationResult.NotFound(trimmed, "No memories matching '$trimmed' found to delete.")
    }

    suspend fun updateMemoryContent(
        idOrKey: String,
        newContent: String
    ): MemoryOperationResult = withContext(Dispatchers.IO) {
        val trimmed = idOrKey.trim()
        val allMemories = memoryDao.getAllActiveMemoriesList()
        val target = allMemories.find { it.id.toString() == trimmed || it.key.equals(trimmed, ignoreCase = true) }
            ?: SemanticMemoryEngine.rankMemories(trimmed, allMemories, limit = 1).firstOrNull()?.memory

        if (target != null) {
            val vec = SemanticMemoryEngine.vectorToString(
                SemanticMemoryEngine.computeVector("${target.key} $newContent ${target.tags}")
            )
            val updated = target.copy(
                content = newContent.trim(),
                embeddingVector = vec,
                updatedAt = System.currentTimeMillis()
            )
            memoryDao.updateMemory(updated)

            MemoryAuditLogger.log(
                event = MemoryAuditLogger.AuditEvent.MEMORY_UPDATED,
                memoryId = target.id,
                keyIdentifier = target.key,
                category = target.category,
                importance = target.importance,
                confidence = target.confidence,
                details = "Direct content update"
            )

            MemoryOperationResult.Updated(
                memoryId = target.id,
                key = target.key,
                previousContent = target.content,
                newContent = newContent.trim(),
                detail = "Memory #${target.id} ('${target.key}') updated successfully."
            )
        } else {
            MemoryOperationResult.NotFound(trimmed, "Memory '$trimmed' not found for update.")
        }
    }

    suspend fun searchMemories(
        query: String,
        limit: Int = 5,
        category: String? = null
    ): List<SemanticMemoryEngine.ScoredMemory> = withContext(Dispatchers.IO) {
        val allMemories = memoryDao.getAllActiveMemoriesList()
        val ranked = SemanticMemoryEngine.rankMemories(
            query = query,
            memories = allMemories,
            limit = limit,
            targetCategory = category
        )

        for (item in ranked) {
            MemoryAuditLogger.log(
                event = MemoryAuditLogger.AuditEvent.MEMORY_RETRIEVED,
                memoryId = item.memory.id,
                keyIdentifier = item.memory.key,
                category = item.memory.category,
                details = "Score: ${String.format(Locale.US, "%.2f", item.score)}"
            )
        }

        ranked
    }

    suspend fun getPreferencesSummary(): String = withContext(Dispatchers.IO) {
        val allMemories = memoryDao.getAllActiveMemoriesList()
        val relevant = allMemories.filter {
            it.category == MemoryCategory.USER_PREFERENCE.code ||
            it.category == MemoryCategory.DEVICE_PREFERENCE.code ||
            it.category == MemoryCategory.COMMUNICATION_PREFERENCE.code ||
            it.category == MemoryCategory.ROUTINE.code ||
            it.category == MemoryCategory.IMPORTANT_FACT.code
        }

        if (relevant.isEmpty()) {
            return@withContext "I have no saved user preferences or facts recorded in my neural memory yet, sir."
        }

        val sb = StringBuilder("Here are your saved preferences and facts, sir:\n")
        val grouped = relevant.groupBy { it.category }
        for ((cat, items) in grouped) {
            val catTitle = MemoryCategory.fromString(cat).displayName
            sb.append("\n[$catTitle]\n")
            for (item in items) {
                sb.append("• ${item.key}: ${item.content}\n")
            }
        }
        sb.toString().trim()
    }
}
