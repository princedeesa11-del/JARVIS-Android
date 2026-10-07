package com.example.whatsapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.whatsapp.model.WhatsAppMessageDirection
import com.example.whatsapp.model.WhatsAppMessageStatus
import com.example.whatsapp.model.WhatsAppRepeatInterval

/**
 * Entity representing an incoming or outgoing WhatsApp Cloud API message.
 */
@Entity(
    tableName = "whatsapp_messages",
    indices = [
        Index(value = ["whatsappMessageId"], unique = true),
        Index(value = ["senderOrRecipientNumber"]),
        Index(value = ["timestamp"])
    ]
)
data class WhatsAppMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val whatsappMessageId: String,
    val senderOrRecipientNumber: String,
    val contactName: String = "",
    val text: String,
    val messageType: String = "text",
    val direction: String = WhatsAppMessageDirection.INCOMING.name,
    val status: String = WhatsAppMessageStatus.RECEIVED.name,
    val isAiReply: Boolean = false,
    val replyToMessageId: String? = null,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * WhatsApp Contact entity
 */
@Entity(
    tableName = "whatsapp_contacts",
    indices = [
        Index(value = ["phoneNumber"], unique = true),
        Index(value = ["lastActiveTimestamp"])
    ]
)
data class WhatsAppContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val displayName: String,
    val lastMessageSnippet: String = "",
    val lastActiveTimestamp: Long = System.currentTimeMillis(),
    val isAutomationAllowed: Boolean = true,
    val optInVerified: Boolean = true,
    val notes: String = ""
)

/**
 * Automation keyword & context rules
 */
@Entity(tableName = "whatsapp_rules")
data class WhatsAppRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val ruleType: String, // KEYWORD, OUTSIDE_HOURS, INTENT_AI
    val matchKeyword: String = "",
    val predefinedReply: String = "",
    val workingHoursStart: String = "09:00",
    val workingHoursEnd: String = "18:00",
    val preventInfiniteLoops: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Scheduled WhatsApp message
 */
@Entity(tableName = "whatsapp_scheduled_messages")
data class WhatsAppScheduledMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipientNumber: String,
    val recipientName: String = "",
    val messageText: String,
    val scheduledTimeMillis: Long,
    val repeatInterval: String = WhatsAppRepeatInterval.ONCE.name,
    val enabled: Boolean = true,
    val deliveryStatus: String = WhatsAppMessageStatus.PENDING.name,
    val lastRunTimestamp: Long = 0L,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Audit log entry for security and compliance
 */
@Entity(tableName = "whatsapp_audit_logs")
data class WhatsAppAuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionType: String, // WEBHOOK_RECEIVED, AI_REPLY_SENT, USER_MESSAGE_SENT, RULE_TRIGGERED, ERROR
    val details: String,
    val targetNumber: String = "",
    val status: String = "SUCCESS",
    val timestamp: Long = System.currentTimeMillis()
)
