package com.qixuan.channelvideoflow.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val database = db
            database.execSQL(
                "ALTER TABLE channels ADD COLUMN scan_paused_by_user INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL("ALTER TABLE channels ADD COLUMN scan_retry_at INTEGER")
            database.execSQL(
                "ALTER TABLE channels ADD COLUMN scan_retry_count INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL("ALTER TABLE channels ADD COLUMN scan_failure_code TEXT")
            database.execSQL("ALTER TABLE channels ADD COLUMN scan_failure_detail INTEGER")
            database.execSQL(
                "ALTER TABLE channels ADD COLUMN scanned_message_count INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL(
                "ALTER TABLE channels ADD COLUMN scanned_page_count INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL(
                "ALTER TABLE channels ADD COLUMN duplicate_video_encounter_count INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL(
                "ALTER TABLE channels ADD COLUMN scan_exception_count INTEGER NOT NULL DEFAULT 0",
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS videos (
                    chat_id INTEGER NOT NULL,
                    message_id INTEGER NOT NULL,
                    file_id INTEGER NOT NULL,
                    remote_unique_id TEXT NOT NULL,
                    caption TEXT NOT NULL,
                    duration_seconds INTEGER NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    file_size INTEGER,
                    supports_streaming INTEGER NOT NULL,
                    publish_time INTEGER NOT NULL,
                    edit_time INTEGER,
                    can_be_saved INTEGER NOT NULL,
                    is_deleted INTEGER NOT NULL,
                    indexed_at INTEGER NOT NULL,
                    PRIMARY KEY(chat_id, message_id),
                    FOREIGN KEY(chat_id) REFERENCES channels(chat_id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_videos_chat_id ON videos(chat_id)")
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_videos_publish_time_chat_id_message_id " +
                    "ON videos(publish_time, chat_id, message_id)",
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tags (
                    normalized_name TEXT NOT NULL,
                    canonical_display_name TEXT NOT NULL,
                    PRIMARY KEY(normalized_name)
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS video_tags (
                    chat_id INTEGER NOT NULL,
                    message_id INTEGER NOT NULL,
                    normalized_tag_name TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    PRIMARY KEY(chat_id, message_id, normalized_tag_name),
                    FOREIGN KEY(chat_id, message_id) REFERENCES videos(chat_id, message_id)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(normalized_tag_name) REFERENCES tags(normalized_name)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_video_tags_chat_id_message_id " +
                    "ON video_tags(chat_id, message_id)",
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_video_tags_normalized_tag_name " +
                    "ON video_tags(normalized_tag_name)",
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE channels ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS media_cache_entries (
                    file_id INTEGER NOT NULL,
                    cached_bytes INTEGER NOT NULL,
                    last_accessed_at INTEGER NOT NULL,
                    PRIMARY KEY(file_id)
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * Stage 23 keeps the legacy full-history cursor for audit compatibility but never reinterprets
     * it as a filtered-search cursor. Completed legacy scans retain their completion fact; partial
     * scans restart filtered video search from the safe newest boundary while preserving videos.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE channels ADD COLUMN scan_strategy_version INTEGER NOT NULL DEFAULT 2",
            )
            db.execSQL(
                "ALTER TABLE channels ADD COLUMN video_search_cursor INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE channels ADD COLUMN video_search_completed INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE channels ADD COLUMN video_candidate_count INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE channels ADD COLUMN video_search_page_count INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL("ALTER TABLE channels ADD COLUMN approximate_video_count INTEGER")
            db.execSQL(
                """
                UPDATE channels
                SET video_search_completed = initial_scan_completed,
                    video_search_cursor = 0,
                    scan_state = CASE
                        WHEN initial_scan_completed = 1 THEN 'COMPLETED'
                        WHEN scan_state = 'COMPLETED' THEN 'NOT_STARTED'
                        ELSE scan_state
                    END
                """.trimIndent(),
            )
        }
    }

    /**
     * Records when a video became invalid so physical deletion is retention-policy driven.
     * Legacy soft-deleted rows use indexed_at as a conservative lower bound.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE videos ADD COLUMN invalidated_at INTEGER")
            db.execSQL(
                "UPDATE videos SET invalidated_at = indexed_at " +
                    "WHERE is_deleted = 1 AND invalidated_at IS NULL",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_videos_is_deleted_invalidated_at " +
                    "ON videos(is_deleted, invalidated_at)",
            )
        }
    }
}
