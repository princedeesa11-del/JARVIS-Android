package com.example.ai.memory

/**
 * Status results for all memory write, update, delete, and lookup operations.
 * Explicitly exposes status codes:
 * MEMORY_SAVED, MEMORY_UPDATED, MEMORY_DELETED, MEMORY_NOT_FOUND, MEMORY_FAILED.
 */
sealed class MemoryOperationResult(
    val statusCode: String,
    val message: String
) {
    data class Saved(
        val memoryId: Long,
        val key: String,
        val category: MemoryCategory,
        val detail: String = "Memory persisted into long-term storage."
    ) : MemoryOperationResult("MEMORY_SAVED", detail)

    data class Updated(
        val memoryId: Long,
        val key: String,
        val previousContent: String,
        val newContent: String,
        val detail: String = "Memory successfully updated with new information."
    ) : MemoryOperationResult("MEMORY_UPDATED", detail)

    data class Deleted(
        val target: String,
        val deletedCount: Int,
        val detail: String = "Memory records successfully purged."
    ) : MemoryOperationResult("MEMORY_DELETED", detail)

    data class NotFound(
        val query: String,
        val detail: String = "No matching memory records found to operate upon."
    ) : MemoryOperationResult("MEMORY_NOT_FOUND", detail)

    data class Failed(
        val error: String,
        val detail: String = "Memory operation failed: $error"
    ) : MemoryOperationResult("MEMORY_FAILED", detail)
}
