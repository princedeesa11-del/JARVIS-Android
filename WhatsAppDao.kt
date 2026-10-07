package com.example.whatsapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WhatsAppDao {

    // --- Messages ---
    @Query("SELECT * FROM whatsapp_messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<WhatsAppMessageEntity>>

    @Query("SELECT * FROM whatsapp_messages WHERE senderOrRecipientNumber = :number ORDER BY timestamp ASC")
    fun getConversation(number: String): Flow<List<WhatsAppMessageEntity>>

    @Query("SELECT * FROM whatsapp_messages WHERE whatsappMessageId = :messageId LIMIT 1")
    suspend fun getMessageByWhatsappId(messageId: String): WhatsAppMessageEntity?

    @Query("SELECT * FROM whatsapp_messages WHERE replyToMessageId = :messageId LIMIT 1")
    suspend fun getReplyForMessageId(messageId: String): WhatsAppMessageEntity?

    @Query("SELECT * FROM whatsapp_messages WHERE senderOrRecipientNumber = :number ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentConversationMessages(number: String, limit: Int = 10): List<WhatsAppMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: WhatsAppMessageEntity): Long

    @Update
    suspend fun updateMessage(message: WhatsAppMessageEntity)

    @Query("UPDATE whatsapp_messages SET status = :status WHERE whatsappMessageId = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("DELETE FROM whatsapp_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM whatsapp_messages")
    suspend fun clearAllMessages()

    // --- Contacts ---
    @Query("SELECT * FROM whatsapp_contacts ORDER BY lastActiveTimestamp DESC")
    fun getAllContacts(): Flow<List<WhatsAppContactEntity>>

    @Query("SELECT * FROM whatsapp_contacts WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getContactByPhone(phoneNumber: String): WhatsAppContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateContact(contact: WhatsAppContactEntity)

    @Query("UPDATE whatsapp_contacts SET lastMessageSnippet = :snippet, lastActiveTimestamp = :time WHERE phoneNumber = :phoneNumber")
    suspend fun updateContactActivity(phoneNumber: String, snippet: String, time: Long)

    @Query("DELETE FROM whatsapp_contacts WHERE id = :id")
    suspend fun deleteContact(id: Long)

    // --- Rules ---
    @Query("SELECT * FROM whatsapp_rules ORDER BY createdAt DESC")
    fun getAllRules(): Flow<List<WhatsAppRuleEntity>>

    @Query("SELECT * FROM whatsapp_rules WHERE enabled = 1")
    suspend fun getActiveRules(): List<WhatsAppRuleEntity>

    @Query("SELECT * FROM whatsapp_rules WHERE id = :id LIMIT 1")
    suspend fun getRuleById(id: Long): WhatsAppRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: WhatsAppRuleEntity): Long

    @Update
    suspend fun updateRule(rule: WhatsAppRuleEntity)

    @Query("DELETE FROM whatsapp_rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    // --- Scheduled Messages ---
    @Query("SELECT * FROM whatsapp_scheduled_messages ORDER BY scheduledTimeMillis ASC")
    fun getAllScheduledMessages(): Flow<List<WhatsAppScheduledMessageEntity>>

    @Query("SELECT * FROM whatsapp_scheduled_messages WHERE enabled = 1 AND deliveryStatus = 'PENDING' AND scheduledTimeMillis <= :nowMillis")
    suspend fun getPendingScheduledMessages(nowMillis: Long): List<WhatsAppScheduledMessageEntity>

    @Query("SELECT * FROM whatsapp_scheduled_messages WHERE id = :id LIMIT 1")
    suspend fun getScheduledMessageById(id: Long): WhatsAppScheduledMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScheduledMessage(item: WhatsAppScheduledMessageEntity): Long

    @Update
    suspend fun updateScheduledMessage(item: WhatsAppScheduledMessageEntity)

    @Query("DELETE FROM whatsapp_scheduled_messages WHERE id = :id")
    suspend fun deleteScheduledMessage(id: Long)

    // --- Audit Logs ---
    @Query("SELECT * FROM whatsapp_audit_logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentAuditLogs(): Flow<List<WhatsAppAuditLogEntity>>

    @Insert
    suspend fun insertAuditLog(log: WhatsAppAuditLogEntity)

    @Query("DELETE FROM whatsapp_audit_logs")
    suspend fun clearAuditLogs()
}
