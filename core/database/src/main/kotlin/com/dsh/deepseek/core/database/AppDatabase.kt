package com.dsh.deepseek.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dsh.deepseek.core.model.TokenPricing

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        BlockEntity::class,
        AttachmentEntity::class,
        DraftEntity::class,
        ScrollAnchorEntity::class,
        UsageRecordEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun blockDao(): BlockDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun draftDao(): DraftDao
    abstract fun scrollAnchorDao(): ScrollAnchorDao
    abstract fun usageDao(): UsageDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "ALTER TABLE conversations ADD COLUMN pinnedAt INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL("ALTER TABLE blocks ADD COLUMN durationMs INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_records` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `messageId` TEXT,
                        `model` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `cacheHitTokens` INTEGER NOT NULL,
                        `cacheMissTokens` INTEGER NOT NULL,
                        `outputTokens` INTEGER NOT NULL,
                        `webSearchRequests` INTEGER NOT NULL,
                        `estimatedCostUsdMicros` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_records_sessionId` ON `usage_records` (`sessionId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_records_dayKey` ON `usage_records` (`dayKey`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_usage_records_messageId` ON `usage_records` (`messageId`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_records_new` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `messageId` TEXT,
                        `model` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `cacheHitTokens` INTEGER NOT NULL,
                        `cacheMissTokens` INTEGER NOT NULL,
                        `outputTokens` INTEGER NOT NULL,
                        `webSearchRequests` INTEGER NOT NULL,
                        `estimatedCostCnyMicros` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    INSERT INTO `usage_records_new` (
                        id, sessionId, messageId, model, createdAt, dayKey,
                        cacheHitTokens, cacheMissTokens, outputTokens, webSearchRequests, estimatedCostCnyMicros
                    )
                    SELECT
                        id, sessionId, messageId, model, createdAt, dayKey,
                        cacheHitTokens, cacheMissTokens, outputTokens, webSearchRequests, estimatedCostUsdMicros
                    FROM `usage_records`
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE `usage_records`")
                database.execSQL("ALTER TABLE `usage_records_new` RENAME TO `usage_records`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_records_sessionId` ON `usage_records` (`sessionId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_records_dayKey` ON `usage_records` (`dayKey`)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_usage_records_messageId` ON `usage_records` (`messageId`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE usage_records ADD COLUMN isPeak INTEGER NOT NULL DEFAULT 0",
                )
                val cursor = database.query("SELECT id, createdAt FROM usage_records")
                cursor.use {
                    while (it.moveToNext()) {
                        val id = it.getString(0)
                        val createdAt = it.getLong(1)
                        if (TokenPricing.isPeak(createdAt)) {
                            database.execSQL(
                                "UPDATE usage_records SET isPeak = 1 WHERE id = ?",
                                arrayOf(id),
                            )
                        }
                    }
                }
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "deepseek_chat.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
