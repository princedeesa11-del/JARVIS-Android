package com.example.data.local.dao

import androidx.room.*
import com.example.data.local.entity.ConversationMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessages(sessionId: String = "default_session"): Flow<List<ConversationMessage>>

    @Query("SELECT * FROM conversation_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(sessionId: String = "default_session", limit: Int = 30): List<ConversationMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ConversationMessage): Long

    @Query("DELETE FROM conversation_messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String = "default_session")

    @Query("DELETE FROM conversation_messages")
    suspend fun clearAll()
}
