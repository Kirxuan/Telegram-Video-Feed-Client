package com.qixuan.channelvideoflow.telegram.client

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileUpdateConflatorTest {
    @Test
    fun updateStormPublishesOnlyTheLatestSnapshotForEachFile() = runTest {
        val published = mutableListOf<TelegramClientFileSnapshot>()
        val conflator = FileUpdateConflator(backgroundScope, published::add)

        repeat(10_000) { index ->
            conflator.offer(snapshot(fileId = 42, downloadedSize = index.toLong()))
        }
        runCurrent()

        assertEquals(listOf(9_999L), published.map { it.downloadedSize })
        assertEquals(
            FileUpdateCounters(
                received = 10_000L,
                applied = 1L,
                coalesced = 9_999L,
                pending = 0,
            ),
            conflator.counters(),
        )
    }

    @Test
    fun generationAdvanceDropsQueuedOldAccountUpdates() = runTest {
        val published = mutableListOf<TelegramClientFileSnapshot>()
        val conflator = FileUpdateConflator(backgroundScope, published::add)

        conflator.offer(snapshot(fileId = 1, downloadedSize = 10L))
        conflator.advanceGeneration()
        conflator.offer(snapshot(fileId = 2, downloadedSize = 20L))
        runCurrent()

        assertEquals(listOf(2), published.map { it.fileId })
        assertEquals(2L, conflator.counters().received)
        assertEquals(1L, conflator.counters().applied)
    }

    private fun snapshot(
        fileId: Int,
        downloadedSize: Long,
    ) = TelegramClientFileSnapshot(
        fileId = fileId,
        size = 100L,
        expectedSize = 100L,
        localPath = "private/path/$fileId",
        canBeDownloaded = true,
        isDownloadingActive = true,
        isDownloadingCompleted = false,
        downloadOffset = 0L,
        downloadedPrefixSize = downloadedSize,
        downloadedSize = downloadedSize,
    )
}
