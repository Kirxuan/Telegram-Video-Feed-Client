package com.qixuan.channelvideoflow.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MediaCacheEntryDao {
    @Transaction
    suspend fun upsertBatch(entries: List<MediaCacheEntryEntity>) {
        entries.forEach { upsertMonotonic(it.fileId, it.cachedBytes, it.lastAccessedAtMillis) }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MediaCacheEntryEntity)

    /**
     * Uses only primitives supported by the SQLite versions shipped with minSdk 26.
     * The metadata writer serializes calls, so the read/insert-or-update pair remains
     * deterministic while avoiding SQLite 3.24's UPSERT syntax.
     */
    @Transaction
    suspend fun upsertMonotonic(
        fileId: Int,
        cachedBytes: Long,
        lastAccessedAtMillis: Long,
    ) {
        val existing = get(fileId)
        if (existing == null) {
            upsert(
                MediaCacheEntryEntity(
                    fileId = fileId,
                    cachedBytes = cachedBytes.coerceAtLeast(0L),
                    lastAccessedAtMillis = lastAccessedAtMillis.coerceAtLeast(0L),
                ),
            )
        } else {
            updateMonotonic(
                fileId = fileId,
                cachedBytes = cachedBytes.coerceAtLeast(0L),
                lastAccessedAtMillis = maxOf(
                    existing.lastAccessedAtMillis,
                    lastAccessedAtMillis.coerceAtLeast(0L),
                ),
            )
        }
    }

    @Query(
        """
        UPDATE media_cache_entries
        SET cached_bytes = :cachedBytes,
            last_accessed_at = :lastAccessedAtMillis
        WHERE file_id = :fileId
        """,
    )
    suspend fun updateMonotonic(
        fileId: Int,
        cachedBytes: Long,
        lastAccessedAtMillis: Long,
    )

    @Query("SELECT * FROM media_cache_entries WHERE file_id = :fileId LIMIT 1")
    suspend fun get(fileId: Int): MediaCacheEntryEntity?

    @Query(
        """
        SELECT * FROM media_cache_entries
        WHERE cached_bytes > 0
        ORDER BY last_accessed_at ASC, file_id ASC
        """,
    )
    suspend fun getLruEntries(): List<MediaCacheEntryEntity>

    @Query("DELETE FROM media_cache_entries WHERE file_id = :fileId")
    suspend fun delete(fileId: Int)

    @Query("DELETE FROM media_cache_entries")
    suspend fun clear()
}
