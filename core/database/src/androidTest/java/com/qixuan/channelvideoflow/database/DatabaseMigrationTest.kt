package com.qixuan.channelvideoflow.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChannelVideoFlowDatabase::class.java,
    )

    @Test
    fun migration1To2PreservesChannelSelectionAndAddsEmptyVideoIndex() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO channels(
                    chat_id, title, username, is_selected, last_new_message_id,
                    oldest_scanned_message_id, initial_scan_completed, last_sync_time,
                    access_state, scan_state
                ) VALUES(1, '测试频道', NULL, 1, NULL, NULL, 0, NULL, 'AVAILABLE', 'NOT_STARTED')
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            DatabaseMigrations.MIGRATION_1_2,
        ).use { database ->
            database.query(
                "SELECT is_selected, scanned_message_count FROM channels WHERE chat_id = 1",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(0, cursor.getLong(1))
            }
            database.query("SELECT COUNT(*) FROM videos").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migration2To3PreservesSelectionAndDefaultsManualPinToFalse() {
        helper.createDatabase(PIN_DATABASE_NAME, 2).apply {
            execSQL(
                """
                INSERT INTO channels(
                    chat_id, title, username, is_selected, last_new_message_id,
                    oldest_scanned_message_id, initial_scan_completed, last_sync_time,
                    access_state, scan_state, scan_paused_by_user, scan_retry_count,
                    scanned_message_count, scanned_page_count,
                    duplicate_video_encounter_count, scan_exception_count
                ) VALUES(
                    1, '测试频道', NULL, 1, NULL, NULL, 0, NULL,
                    'AVAILABLE', 'NOT_STARTED', 0, 0, 0, 0, 0, 0
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            PIN_DATABASE_NAME,
            3,
            true,
            DatabaseMigrations.MIGRATION_2_3,
        ).use { database ->
            database.query(
                "SELECT is_selected, is_pinned FROM channels WHERE chat_id = 1",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    @Test
    fun migration3To4PreservesIndexAndAddsEmptyMediaCacheLru() {
        helper.createDatabase(CACHE_DATABASE_NAME, 3).apply {
            execSQL(
                """
                INSERT INTO channels(
                    chat_id, title, username, is_selected, last_new_message_id,
                    oldest_scanned_message_id, initial_scan_completed, last_sync_time,
                    access_state, scan_state, is_pinned, scan_paused_by_user,
                    scan_retry_count, scanned_message_count, scanned_page_count,
                    duplicate_video_encounter_count, scan_exception_count
                ) VALUES(
                    1, '测试频道', NULL, 1, NULL, NULL, 0, NULL,
                    'AVAILABLE', 'NOT_STARTED', 0, 0, 0, 0, 0, 0, 0
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            CACHE_DATABASE_NAME,
            4,
            true,
            DatabaseMigrations.MIGRATION_3_4,
        ).use { database ->
            database.query("SELECT COUNT(*) FROM channels").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM media_cache_entries").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migration4To5PreservesExistingIndexAndSeparatesLegacyAndVideoSearchCursors() {
        helper.createDatabase(VIDEO_SEARCH_DATABASE_NAME, 4).apply {
            execSQL(
                """
                INSERT INTO channels(
                    chat_id, title, username, is_selected, last_new_message_id,
                    oldest_scanned_message_id, initial_scan_completed, last_sync_time,
                    access_state, scan_state, is_pinned, scan_paused_by_user,
                    scan_retry_count, scanned_message_count, scanned_page_count,
                    duplicate_video_encounter_count, scan_exception_count
                ) VALUES
                    (1, '旧完整频道', NULL, 1, 900, 100, 1, 1000,
                     'AVAILABLE', 'COMPLETED', 0, 0, 0, 801, 9, 0, 0),
                    (2, '旧未完成频道', NULL, 1, 800, 400, 0, 1000,
                     'AVAILABLE', 'SCANNING', 0, 0, 0, 401, 5, 0, 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO videos(
                    chat_id, message_id, file_id, remote_unique_id, caption,
                    duration_seconds, width, height, file_size, supports_streaming,
                    publish_time, edit_time, can_be_saved, is_deleted, indexed_at
                ) VALUES(2, 777, 7, 'synthetic-remote', '', 10, 720, 1280, 1024, 1,
                         777, NULL, 1, 0, 1000)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            VIDEO_SEARCH_DATABASE_NAME,
            5,
            true,
            DatabaseMigrations.MIGRATION_4_5,
        ).use { database ->
            database.query(
                """
                SELECT chat_id, oldest_scanned_message_id, video_search_cursor,
                       video_search_completed, scan_strategy_version,
                       video_candidate_count, video_search_page_count
                FROM channels ORDER BY chat_id
                """.trimIndent(),
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(1L, cursor.getLong(0))
                assertEquals(100L, cursor.getLong(1))
                assertEquals(0L, cursor.getLong(2))
                assertEquals(1, cursor.getInt(3))
                assertEquals(2, cursor.getInt(4))
                assertEquals(0L, cursor.getLong(5))
                assertEquals(0, cursor.getInt(6))

                cursor.moveToNext()
                assertEquals(2L, cursor.getLong(0))
                assertEquals(400L, cursor.getLong(1))
                assertEquals(0L, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals(2, cursor.getInt(4))
            }
            database.query("SELECT chat_id, message_id FROM videos").use { cursor ->
                cursor.moveToFirst()
                assertEquals(2L, cursor.getLong(0))
                assertEquals(777L, cursor.getLong(1))
            }
        }
    }

    @Test
    fun migration5To6PreservesRowsAndBackfillsOnlySoftDeletedInvalidationTime() {
        helper.createDatabase(RETENTION_DATABASE_NAME, 5).apply {
            execSQL(
                """
                INSERT INTO channels(
                    chat_id, title, username, is_selected, last_new_message_id,
                    oldest_scanned_message_id, initial_scan_completed, last_sync_time,
                    access_state, scan_state, is_pinned, scan_paused_by_user,
                    scan_retry_count, scanned_message_count, scanned_page_count,
                    duplicate_video_encounter_count, scan_exception_count,
                    scan_strategy_version, video_search_cursor, video_search_completed,
                    video_candidate_count, video_search_page_count
                ) VALUES(
                    1, '保留期频道', NULL, 1, NULL, NULL, 1, 1000,
                    'AVAILABLE', 'COMPLETED', 0, 0, 0, 0, 0, 0, 0,
                    2, 0, 1, 0, 0
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO videos(
                    chat_id, message_id, file_id, remote_unique_id, caption,
                    duration_seconds, width, height, file_size, supports_streaming,
                    publish_time, edit_time, can_be_saved, is_deleted, indexed_at
                ) VALUES
                    (1, 10, 10, 'active', '', 10, 720, 1280, 1024, 1,
                     10, NULL, 1, 0, 1000),
                    (1, 11, 11, 'deleted', '', 10, 720, 1280, 1024, 1,
                     11, NULL, 1, 1, 1100)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            RETENTION_DATABASE_NAME,
            6,
            true,
            DatabaseMigrations.MIGRATION_5_6,
        ).use { database ->
            database.query(
                "SELECT message_id, invalidated_at FROM videos ORDER BY message_id",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(10L, cursor.getLong(0))
                assertEquals(true, cursor.isNull(1))
                cursor.moveToNext()
                assertEquals(11L, cursor.getLong(0))
                assertEquals(1100L, cursor.getLong(1))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-stage-4"
        const val PIN_DATABASE_NAME = "migration-channel-pin"
        const val CACHE_DATABASE_NAME = "migration-media-cache"
        const val VIDEO_SEARCH_DATABASE_NAME = "migration-stage-23-video-search"
        const val RETENTION_DATABASE_NAME = "migration-stage-25-retention"
    }
}
