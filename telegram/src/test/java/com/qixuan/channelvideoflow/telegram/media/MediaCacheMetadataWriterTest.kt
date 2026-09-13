package com.qixuan.channelvideoflow.telegram.media

import com.qixuan.channelvideoflow.database.MediaCacheEntryEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaCacheMetadataWriterTest {
    @Test
    fun eventFloodIsBoundedAndDeletionIsOrderedAfterTheInFlightBatch() = runTest {
        val written = mutableListOf<List<MediaCacheEntryEntity>>()
        val deleted = mutableListOf<Int>()
        val writer = MediaCacheMetadataWriter(backgroundScope, { written += it }, {},
            { deleted += it }, batchDelayMillis = 0, retryDelayMillis = 0)
        repeat(10_000) { writer.record(it, 1, 1) }
        org.junit.Assert.assertTrue(writer.pendingCount <= 256)
        writer.delete(9_999)
        runCurrent()
        assertEquals(listOf(9_999), deleted)
        org.junit.Assert.assertTrue(written.flatten().none { it.fileId == 9_999 })
    }

    @Test
    fun failedAccountClearBlocksNewAccountWritesUntilResetRecovers() = runTest {
        var failClear = true
        val written = mutableListOf<MediaCacheEntryEntity>()
        val writer = MediaCacheMetadataWriter(backgroundScope, { written += it },
            { if (failClear) error("synthetic") }, batchDelayMillis = 0, retryDelayMillis = 0)
        writer.resetAndClear()
        writer.record(1, 1, 1)
        runCurrent()
        assertEquals(emptyList<MediaCacheEntryEntity>(), written)
        failClear = false
        writer.resetAndClear()
        writer.record(2, 2, 2)
        runCurrent()
        assertEquals(listOf(2), written.map { it.fileId })
    }

    @Test
    fun writesAreConflatedAndLastAccessTimeNeverMovesBackwards() = runTest {
        val written = mutableListOf<List<MediaCacheEntryEntity>>()
        val writer = MediaCacheMetadataWriter(
            scope = backgroundScope,
            writeBatch = { written += it },
            clearAll = {},
            batchDelayMillis = 0L,
            retryDelayMillis = 0L,
        )

        writer.record(fileId = 7, cachedBytes = 10L, lastAccessedAtMillis = 100L)
        writer.record(fileId = 7, cachedBytes = 20L, lastAccessedAtMillis = 50L)
        runCurrent()

        assertEquals(1, written.size)
        assertEquals(1L, writer.committedRows)
        assertEquals(1L, writer.committedBatches)
        assertEquals(
            MediaCacheEntryEntity(
                fileId = 7,
                cachedBytes = 20L,
                lastAccessedAtMillis = 100L,
            ),
            written.single().single(),
        )
    }

    @Test
    fun logoutClearsPendingOldGenerationBeforeWritingNewAccountData() = runTest {
        val written = mutableListOf<List<MediaCacheEntryEntity>>()
        var clearCount = 0
        val writer = MediaCacheMetadataWriter(
            scope = backgroundScope,
            writeBatch = { written += it },
            clearAll = { clearCount += 1 },
            batchDelayMillis = 0L,
            retryDelayMillis = 0L,
        )

        writer.record(fileId = 1, cachedBytes = 10L, lastAccessedAtMillis = 10L)
        writer.resetAndClear()
        writer.record(fileId = 2, cachedBytes = 20L, lastAccessedAtMillis = 20L)
        runCurrent()

        assertEquals(1, clearCount)
        assertEquals(listOf(2), written.flatten().map { it.fileId })
    }

    @Test
    fun transientDatabaseFailureRetriesAtMostThreeTimes() = runTest {
        var attempts = 0
        val writer = MediaCacheMetadataWriter(
            scope = backgroundScope,
            writeBatch = {
                attempts += 1
                error("synthetic database failure")
            },
            clearAll = {},
            batchDelayMillis = 0L,
            retryDelayMillis = 0L,
        )

        writer.record(fileId = 3, cachedBytes = 30L, lastAccessedAtMillis = 30L)
        runCurrent()

        assertEquals(3, attempts)
        assertEquals(0L, writer.committedRows)
        assertEquals(0L, writer.committedBatches)
    }
}
