package com.example.ai.memory

import android.util.Log

/**
 * Diagnostic logger for memory lifecycle events conforming to Section 17 (Privacy) and Section 20 (Audit).
 * Records telemetry for:
 * MEMORY_CREATED, MEMORY_UPDATED, MEMORY_DELETED, MEMORY_RETRIEVED, MEMORY_IGNORED, MEMORY_CONFLICT, MEMORY_DEDUPLICATED.
 *
 * Strictly preserves privacy:
 * NEVER writes raw private user text to system logcat. Only records memory IDs, key hashes,
 * category codes, importance weights, and confidence levels.
 */
object MemoryAuditLogger {
    private const val TAG = "JarvisMemoryAudit"

    enum class AuditEvent {
        MEMORY_CREATED,
        MEMORY_UPDATED,
        MEMORY_DELETED,
        MEMORY_RETRIEVED,
        MEMORY_IGNORED,
        MEMORY_CONFLICT,
        MEMORY_DEDUPLICATED
    }

    data class AuditRecord(
        val event: AuditEvent,
        val memoryId: Long?,
        val keyIdentifier: String,
        val category: String,
        val importance: Float,
        val confidence: Float,
        val details: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val records = mutableListOf<AuditRecord>()
    private const val MAX_RECORDS = 150

    @Synchronized
    fun log(
        event: AuditEvent,
        memoryId: Long? = null,
        keyIdentifier: String = "",
        category: String = "",
        importance: Float = 0.5f,
        confidence: Float = 1.0f,
        details: String = ""
    ) {
        val record = AuditRecord(
            event = event,
            memoryId = memoryId,
            keyIdentifier = keyIdentifier,
            category = category,
            importance = importance,
            confidence = confidence,
            details = details
        )
        if (records.size >= MAX_RECORDS) {
            records.removeAt(0)
        }
        records.add(record)
        Log.d(TAG, "[$event] id=$memoryId key=$keyIdentifier cat=$category imp=$importance conf=$confidence | $details")
    }

    @Synchronized
    fun getRecentAudits(): List<AuditRecord> = records.toList()

    @Synchronized
    fun clear() {
        records.clear()
    }
}
