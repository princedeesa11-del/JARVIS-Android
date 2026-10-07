package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.*
import com.example.data.local.entity.*
import com.example.whatsapp.data.*

@Database(
    entities = [
        MemoryItem::class,
        ConversationMessage::class,
        ReminderItem::class,
        ToolExecutionRecord::class,
        WebProjectItem::class,
        AutomationRule::class,
        GeofenceItem::class,
        AutomationExecutionRecord::class,
        WhatsAppMessageEntity::class,
        WhatsAppContactEntity::class,
        WhatsAppRuleEntity::class,
        WhatsAppScheduledMessageEntity::class,
        WhatsAppAuditLogEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun conversationDao(): ConversationDao
    abstract fun reminderDao(): ReminderDao
    abstract fun toolExecutionDao(): ToolExecutionDao
    abstract fun webProjectDao(): WebProjectDao
    abstract fun automationDao(): AutomationDao
    abstract fun geofenceDao(): GeofenceDao
    abstract fun automationExecutionDao(): AutomationExecutionDao
    abstract fun whatsAppDao(): WhatsAppDao

    companion object {
        @Volatile
        private var INSTANCE: JarvisDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Safeguard all user memories by adding semantic fields
                db.execSQL("ALTER TABLE memories ADD COLUMN source TEXT NOT NULL DEFAULT 'user_interaction'")
                db.execSQL("ALTER TABLE memories ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memories ADD COLUMN importance REAL NOT NULL DEFAULT 0.5")
                db.execSQL("ALTER TABLE memories ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE memories ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE memories ADD COLUMN embeddingVector TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE memories ADD COLUMN metadata TEXT NOT NULL DEFAULT '{}'")

                // Create geofences table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `geofences` (
                        `requestId` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `radiusMeters` REAL NOT NULL,
                        `transitionTypes` INTEGER NOT NULL,
                        `actionPayload` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastTriggeredAt` INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automation_rules ADD COLUMN `schedule` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE automation_rules ADD COLUMN `action` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE automation_rules ADD COLUMN `arguments` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE automation_rules ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE automation_rules ADD COLUMN `nextRunAt` INTEGER")
                db.execSQL("ALTER TABLE automation_rules ADD COLUMN `lastRunAt` INTEGER")
                db.execSQL("ALTER TABLE automation_rules ADD COLUMN `executionState` TEXT NOT NULL DEFAULT 'SCHEDULED'")
                db.execSQL("ALTER TABLE automation_rules ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE automation_rules ADD COLUMN `errorMessage` TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `automation_execution_history` (
                        `executionId` TEXT NOT NULL PRIMARY KEY,
                        `automationId` INTEGER NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        `state` TEXT NOT NULL,
                        `result` TEXT,
                        `error` TEXT,
                        `retryCount` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversation_messages ADD COLUMN `source` TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE conversation_messages ADD COLUMN `responseType` TEXT NOT NULL DEFAULT 'INFORMATIONAL'")
                db.execSQL("ALTER TABLE conversation_messages ADD COLUMN `executionState` TEXT NOT NULL DEFAULT 'SUCCESS'")
                db.execSQL("ALTER TABLE conversation_messages ADD COLUMN `requiredPermission` TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `whatsapp_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `whatsappMessageId` TEXT NOT NULL,
                        `senderOrRecipientNumber` TEXT NOT NULL,
                        `contactName` TEXT NOT NULL DEFAULT '',
                        `text` TEXT NOT NULL,
                        `messageType` TEXT NOT NULL DEFAULT 'text',
                        `direction` TEXT NOT NULL DEFAULT 'INCOMING',
                        `status` TEXT NOT NULL DEFAULT 'RECEIVED',
                        `isAiReply` INTEGER NOT NULL DEFAULT 0,
                        `replyToMessageId` TEXT,
                        `errorMessage` TEXT,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_whatsapp_messages_whatsappMessageId` ON `whatsapp_messages` (`whatsappMessageId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_whatsapp_messages_senderOrRecipientNumber` ON `whatsapp_messages` (`senderOrRecipientNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_whatsapp_messages_timestamp` ON `whatsapp_messages` (`timestamp`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `whatsapp_contacts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `phoneNumber` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `lastMessageSnippet` TEXT NOT NULL DEFAULT '',
                        `lastActiveTimestamp` INTEGER NOT NULL,
                        `isAutomationAllowed` INTEGER NOT NULL DEFAULT 1,
                        `optInVerified` INTEGER NOT NULL DEFAULT 1,
                        `notes` TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_whatsapp_contacts_phoneNumber` ON `whatsapp_contacts` (`phoneNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_whatsapp_contacts_lastActiveTimestamp` ON `whatsapp_contacts` (`lastActiveTimestamp`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `whatsapp_rules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `ruleType` TEXT NOT NULL,
                        `matchKeyword` TEXT NOT NULL DEFAULT '',
                        `predefinedReply` TEXT NOT NULL DEFAULT '',
                        `workingHoursStart` TEXT NOT NULL DEFAULT '09:00',
                        `workingHoursEnd` TEXT NOT NULL DEFAULT '18:00',
                        `preventInfiniteLoops` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `whatsapp_scheduled_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `recipientNumber` TEXT NOT NULL,
                        `recipientName` TEXT NOT NULL DEFAULT '',
                        `messageText` TEXT NOT NULL,
                        `scheduledTimeMillis` INTEGER NOT NULL,
                        `repeatInterval` TEXT NOT NULL DEFAULT 'ONCE',
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `deliveryStatus` TEXT NOT NULL DEFAULT 'PENDING',
                        `lastRunTimestamp` INTEGER NOT NULL DEFAULT 0,
                        `errorMessage` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `whatsapp_audit_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `details` TEXT NOT NULL,
                        `targetNumber` TEXT NOT NULL DEFAULT '',
                        `status` TEXT NOT NULL DEFAULT 'SUCCESS',
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): JarvisDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    JarvisDatabase::class.java,
                    "jarvis_core.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                INSTANCE = instance
                instance
            }
        }

        fun getInstance(context: Context): JarvisDatabase = getDatabase(context)
    }
}
