package com.qixuan.channelvideoflow.player

import android.net.TestUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.qixuan.channelvideoflow.domain.media.TelegramFileGateway
import com.qixuan.channelvideoflow.domain.media.TelegramFileDeleteResult
import com.qixuan.channelvideoflow.domain.media.TelegramFileOwnerKind
import com.qixuan.channelvideoflow.domain.media.TelegramFileProtectionLease
import com.qixuan.channelvideoflow.domain.media.TelegramFileRangeLease
import com.qixuan.channelvideoflow.domain.media.TelegramFileRequestPriority
import com.qixuan.channelvideoflow.domain.media.TelegramFileSnapshot
import com.qixuan.channelvideoflow.domain.media.TelegramFileTimeoutException
import com.qixuan.channelvideoflow.domain.media.TelegramFileUnavailableException
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
class TelegramMediaDataSourceTest {
    @Test
    fun extractorCanReadTheFirstHeaderBeforeTheFullRollingChunkArrives() {
        val path = tempFile("x".repeat(64 * 1024))
        val gateway = FakeGateway(partialSnapshot(path, 1024L * 1024L, 64L * 1024L))
        val source = TelegramMediaDataSource(gateway, isMainThread = { false }, fileIdOverride = 1)
        try {
            source.open(DataSpec(TestUri(), 0, C.LENGTH_UNSET.toLong()))
            val header = ByteArray(16)
            assertEquals(16, source.read(header, 0, header.size))
            assertArrayEquals("x".repeat(16).toByteArray(), header)
            assertEquals(1024L * 1024L, gateway.readAheadBytes.single())
        } finally {
            source.close()
            java.io.File(path).delete()
        }
    }

    @Test
    fun samplePreloadSessionStartsAsNextAndPromotesFutureRangesToCurrent() {
        val path = tempFile("0123456789")
        val gateway = FakeGateway(snapshot(path, 10))
        val session = PlaybackRangeRequestSession(preloadOnly = true)
        val preload = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = session,
        )
        preload.open(DataSpec(TestUri(), 0, 4))
        preload.close()
        assertEquals(TelegramFileRequestPriority.NEXT_PRELOAD, gateway.priorities.single())
        assertEquals(TelegramFileOwnerKind.NEXT_PRELOAD, gateway.ownerKinds.single())

        session.promoteToCurrent()
        val current = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = session,
        )
        current.open(DataSpec(TestUri(), 4, 4))

        assertEquals(TelegramFileRequestPriority.CURRENT_STARTUP, gateway.priorities.last())
        assertEquals(TelegramFileOwnerKind.CURRENT_PLAYBACK, gateway.ownerKinds.last())
        current.close()
    }

    @Test
    fun currentOwnerCallbackRunsAfterLeaseAcquireButBeforeWaitingForBytes() {
        val path = tempFile("0123456789")
        val gateway = FakeGateway(snapshot(path, 10))
        val source = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            onCurrentRangeLeaseAcquired = { acquired ->
                gateway.events += "callback:$acquired"
            },
        )

        source.open(DataSpec(TestUri(), 0, 4))

        assertEquals(listOf("acquire", "callback:true", "await"), gateway.events)
        source.close()
    }

    @Test
    fun failedCurrentLeaseAcquireReportsFailureWithoutWaiting() {
        val gateway = FakeGateway(snapshot = null, failAcquire = true)
        val callbacks = mutableListOf<Boolean>()
        val source = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            onCurrentRangeLeaseAcquired = callbacks::add,
        )

        assertThrows(TelegramMediaUnavailableException::class.java) {
            source.open(DataSpec(TestUri(), 0, 4))
        }

        assertEquals(listOf(false), callbacks)
        assertEquals(listOf("acquire"), gateway.events)
    }

    @Test
    fun sessionCapturesOnlyTheFirstContinuousRangeForBindFirstByteMetrics() {
        val path = tempFile("0123456789")
        val gateway = FakeGateway(snapshot(path, 10))
        var nowNanos = 1_000L
        val requestSession = PlaybackRangeRequestSession(nowNanos = { nowNanos })
        val first = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = requestSession,
        )
        val second = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = requestSession,
        )

        first.open(DataSpec(TestUri(), 0, 4))
        nowNanos = 9_000L
        second.open(DataSpec(TestUri(), 4, 4))

        assertEquals(
            FirstRangeReady(
                fileId = 4,
                priority = TelegramFileRequestPriority.CURRENT_STARTUP,
                atNanos = 1_000L,
            ),
            requestSession.firstRangeReady(),
        )
        first.close()
        second.close()
    }

    @Test
    fun sharedSessionObservesExtractorDataSpecsButNotInternalChunkAdvances() {
        val path = tempFile("0123456789")
        val gateway = FakeGateway(snapshot(path, 10))
        val requestSession = PlaybackRangeRequestSession()
        val first = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = requestSession,
            chunkSizeBytes = 2,
        )
        val second = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = requestSession,
            chunkSizeBytes = 2,
        )

        first.open(DataSpec(TestUri(), 0, 4))
        first.read(ByteArray(4), 0, 4)
        second.open(DataSpec(TestUri(), 6, 2))

        assertEquals(2, requestSession.startupRangeObservation().dataSpecOpenCount)
        assertEquals(1, requestSession.startupRangeObservation().extractorRangeSwitchCount)
        first.close()
        second.close()
    }

    @Test
    fun firstFrameReprioritizesAnAlreadyActiveStartupLease() {
        val path = tempFile("0123456789")
        val gateway = FakeGateway(snapshot(path, 10))
        val requestSession = PlaybackRangeRequestSession()
        val source = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = requestSession,
        )
        source.open(DataSpec(TestUri(), 0, 4))

        requestSession.onFirstFrame()

        assertEquals(
            listOf(TelegramFileRequestPriority.CURRENT_CONTINUATION),
            gateway.priorityUpdates,
        )
        source.close()
    }

    @Test
    fun firstFrameDowngradesFutureCurrentRangesToContinuationPriority() {
        val path = tempFile("0123456789")
        val gateway = FakeGateway(snapshot(path, 10))
        val requestSession = PlaybackRangeRequestSession()
        val startup = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = requestSession,
        )
        startup.open(DataSpec(TestUri(), 0, 4))

        requestSession.onFirstFrame()
        val continuation = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = requestSession,
        )
        continuation.open(DataSpec(TestUri(), 4, 4))

        assertEquals(
            listOf(
                TelegramFileRequestPriority.CURRENT_STARTUP,
                TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ),
            gateway.priorities,
        )
        startup.close()
        continuation.close()
    }

    @Test
    fun bindingReleaseCancelsEveryActiveDataSourceInTheSession() {
        val path = tempFile("0123456789")
        val gateway = FakeGateway(snapshot(path, 10))
        val requestSession = PlaybackRangeRequestSession()
        val first = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = requestSession,
        )
        val second = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = requestSession,
        )
        first.open(DataSpec(TestUri(), 0, 4))
        second.open(DataSpec(TestUri(), 6, 2))

        requestSession.close()

        assertEquals(2, gateway.closedLeases)
        assertThrows(TelegramMediaUnavailableException::class.java) {
            requestSession.currentPriority()
        }
        first.close()
        second.close()
    }

    @Test
    fun closeCancelsAnOpenThatIsStillWaitingForItsRange() {
        val gateway = FakeGateway(snapshot = null, blockUntilClosed = true)
        val requestSession = PlaybackRangeRequestSession()
        val source = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 4,
            requestSession = requestSession,
        )
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit(
            Callable { source.open(DataSpec(TestUri(), 0, 4)) },
        )

        try {
            assertTrue(gateway.awaitStarted.await(1, TimeUnit.SECONDS))
            source.close()

            val failure = assertThrows(ExecutionException::class.java) {
                result.get(1, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is TelegramMediaUnavailableException)
        } finally {
            requestSession.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun startupRetainsTheProvenBoundedReadAheadAfterFirstWindowAbFails() {
        val path = tempFile("abcdefghij")
        val gateway = FakeGateway(snapshot(path, 10))
        val source = TelegramMediaDataSource(
            gateway = gateway,
            chunkSizeBytes = 4,
            isMainThread = { false },
            fileIdOverride = 5,
        )
        val output = ByteArray(8)

        source.open(DataSpec(TestUri(), 0, 10))
        assertEquals(4, source.read(output, 0, 4))
        assertEquals(4, source.read(output, 4, 4))

        assertEquals(listOf(10L), gateway.readAheadBytes)
        assertTrue(
            gateway.readAheadBytes.all { bytes ->
                bytes <= TelegramMediaDataSource.MAX_CURRENT_READ_AHEAD_BYTES
            },
        )
        source.close()
    }

    @Test
    fun continuousCachedBytesAreReusedAcrossChunkBoundariesWithoutAnotherLease() {
        val path = tempFile("abcdefghij")
        val gateway = FakeGateway(snapshot(path, 10))
        val source = TelegramMediaDataSource(
            gateway = gateway,
            chunkSizeBytes = 4,
            isMainThread = { false },
            fileIdOverride = 6,
        )
        val output = ByteArray(8)

        source.open(DataSpec(TestUri(), 0, 8))
        assertEquals(8, source.read(output, 0, output.size))

        assertArrayEquals("abcdefgh".toByteArray(), output)
        assertEquals(listOf(Triple(6, 0L, 4L)), gateway.requests)
        source.close()
    }

    @Test
    fun userSeekCancelsTheOldStartupRangeAndPrioritizesTheNextDataSpec() {
        val path = tempFile("0123456789")
        val gateway = FakeGateway(snapshot(path, 10))
        val requestSession = PlaybackRangeRequestSession()
        val startup = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 42,
            requestSession = requestSession,
        )

        startup.open(DataSpec(TestUri(), 0, 4))
        assertEquals(
            listOf(TelegramFileRequestPriority.CURRENT_STARTUP),
            gateway.priorities,
        )

        requestSession.onUserSeek()
        assertEquals(1, gateway.closedLeases)

        val seek = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 42,
            requestSession = requestSession,
        )
        seek.open(DataSpec(TestUri(), 6, 2))

        assertEquals(
            listOf(
                TelegramFileRequestPriority.CURRENT_STARTUP,
                TelegramFileRequestPriority.CURRENT_SEEK,
            ),
            gateway.priorities,
        )
        startup.close()
        seek.close()
    }

    @Test
    fun openUsesDataSpecPositionAndReadsThatRange() {
        val path = tempFile("0123456789")
        val gateway = FakeGateway(snapshot(path, 10))
        val source = TelegramMediaDataSource(
            gateway,
            isMainThread = { false },
            fileIdOverride = 42,
        )
        val spec = DataSpec(TestUri(), 5, 4)

        assertEquals(4L, source.open(spec))
        val buffer = ByteArray(4)
        assertEquals(4, source.read(buffer, 0, buffer.size))
        assertArrayEquals("5678".toByteArray(), buffer)
        source.close()

        assertEquals(listOf(Triple(42, 5L, 4L)), gateway.requests)
        assertEquals(1, gateway.closedLeases)
    }

    @Test
    fun readAdvancesToNextBoundedRangeWithoutFullDownload() {
        val path = tempFile("abcdefghij")
        val firstWindow = partialSnapshot(path = path, size = 10, prefixSize = 4)
        val secondWindow = partialSnapshot(path = path, size = 10, prefixSize = 8)
        val gateway = FakeGateway(
            snapshot = firstWindow,
            snapshots = listOf(firstWindow, secondWindow),
        )
        val source = TelegramMediaDataSource(
            gateway = gateway,
            chunkSizeBytes = 4,
            isMainThread = { false },
            fileIdOverride = 7,
        )
        val spec = DataSpec(TestUri(), 0, 8)
        source.open(spec)
        val output = ByteArray(8)
        var read = 0
        while (read < output.size) {
            read += source.read(output, read, output.size - read)
        }
        source.close()

        assertArrayEquals("abcdefgh".toByteArray(), output)
        assertEquals(
            listOf(Triple(7, 0L, 4L), Triple(7, 4L, 4L)),
            gateway.requests,
        )
    }

    @Test
    fun nextRangeIsAcquiredBeforeTheCurrentOwnerIsReleased() {
        val path = tempFile("abcdefghij")
        val firstWindow = partialSnapshot(path = path, size = 10, prefixSize = 4)
        val secondWindow = partialSnapshot(path = path, size = 10, prefixSize = 8)
        val gateway = FakeGateway(
            snapshot = firstWindow,
            snapshots = listOf(firstWindow, secondWindow),
        )
        val source = TelegramMediaDataSource(
            gateway = gateway,
            chunkSizeBytes = 4,
            isMainThread = { false },
            fileIdOverride = 8,
        )
        source.open(DataSpec(TestUri(), 0, 8))
        val output = ByteArray(8)

        assertEquals(4, source.read(output, 0, 4))
        assertEquals(4, source.read(output, 4, 4))

        assertEquals(listOf(1, 2), gateway.activeLeaseCountsAtAcquire)
        source.close()
        assertEquals(0, gateway.activeLeases)
    }

    @Test
    fun timeoutBecomesExplicitMediaTimeout() {
        val gateway = FakeGateway(snapshot = null, timeout = true)
        val source = TelegramMediaDataSource(
            gateway,
            isMainThread = { false },
            fileIdOverride = 9,
        )
        val spec = DataSpec(TestUri(), 0, 10)

        assertThrows(TelegramMediaTimeoutException::class.java) { source.open(spec) }
        assertEquals(1, gateway.closedLeases)
    }

    @Test
    fun closeReleasesRangeOwner() {
        val gateway = FakeGateway(snapshot(tempFile("data"), 4))
        val source = TelegramMediaDataSource(
            gateway,
            isMainThread = { false },
            fileIdOverride = 11,
        )
        source.open(DataSpec(TestUri(), 0, 4))
        source.close()
        source.close()
        assertEquals(1, gateway.closedLeases)
    }

    @Test
    fun mainThreadAccessIsRejectedBeforeAnyRequest() {
        val gateway = FakeGateway(snapshot(tempFile("data"), 4))
        val source = TelegramMediaDataSource(
            gateway,
            isMainThread = { true },
            fileIdOverride = 12,
        )
        assertThrows(TelegramMediaDataSourceException::class.java) {
            source.open(DataSpec(TestUri(), 0, 1))
        }
        assertEquals(emptyList<Triple<Int, Long, Long>>(), gateway.requests)
    }

    @Test
    fun knownEndOfFileDoesNotQueueAnotherRange() {
        val gateway = FakeGateway(snapshot(tempFile("done"), 4))
        val source = TelegramMediaDataSource(
            gateway,
            chunkSizeBytes = 4,
            isMainThread = { false },
            fileIdOverride = 14,
        )
        assertEquals(
            4L,
            source.open(DataSpec(TestUri(), 0, C.LENGTH_UNSET.toLong())),
        )
        val buffer = ByteArray(4)

        assertEquals(4, source.read(buffer, 0, 4))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 4))
        assertEquals(listOf(Triple(14, 0L, 4L)), gateway.requests)
        source.close()
    }

    @Test
    fun tailRangeIsCappedToTheKnownRemainingFileBytes() {
        val path = tempFile("abcdefghij")
        val gateway = FakeGateway(snapshot(path, 10))
        val source = TelegramMediaDataSource(
            gateway,
            chunkSizeBytes = 4,
            isMainThread = { false },
            fileIdOverride = 18,
        )

        assertEquals(
            2L,
            source.open(DataSpec(TestUri(), 8, C.LENGTH_UNSET.toLong())),
        )
        val buffer = ByteArray(4)

        assertEquals(2, source.read(buffer, 0, buffer.size))
        assertArrayEquals("ij".toByteArray(), buffer.copyOf(2))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        assertEquals(listOf(Triple(18, 8L, 2L)), gateway.requests)
        source.close()
    }

    @Test
    fun unknownFileSizeKeepsTheOpenedLengthUnresolved() {
        val path = tempFile("data")
        val gateway = FakeGateway(
            TelegramFileSnapshot(
                fileId = 21,
                size = 0,
                expectedSize = 4,
                localPath = path,
                canBeDownloaded = true,
                isDownloadingActive = true,
                isDownloadingCompleted = false,
                downloadOffset = 0,
                downloadedPrefixSize = 4,
                downloadedSize = 4,
            ),
        )
        val source = TelegramMediaDataSource(
            gateway,
            chunkSizeBytes = 4,
            isMainThread = { false },
            fileIdOverride = 21,
        )

        assertEquals(
            C.LENGTH_UNSET.toLong(),
            source.open(DataSpec(TestUri(), 0, C.LENGTH_UNSET.toLong())),
        )
        source.close()
    }

    @Test
    fun openParsesTheInternalFileAuthorityUri() {
        val gateway = FakeGateway(snapshot(tempFile("data"), 4))
        val source = TelegramMediaDataSource(gateway, isMainThread = { false })
        val uri = TestUri(
            scheme = TelegramMediaDataSource.SCHEME,
            authority = "file",
            pathSegments = listOf("42"),
        )

        source.open(DataSpec(uri, 0, 4))
        source.close()

        assertEquals(listOf(Triple(42, 0L, 4L)), gateway.requests)
    }

    @Test
    fun staleCoveredLocalPathIsInvalidatedAndRetriedBeforeFirstRangeIsReportedReady() {
        val stalePath = tempFile("stale")
        Files.delete(java.nio.file.Path.of(stalePath))
        val replacementPath = tempFile("fresh")
        val stale = snapshot(stalePath, 5)
        val replacement = snapshot(replacementPath, 5)
        val gateway = FakeGateway(
            snapshot = stale,
            snapshots = listOf(stale, replacement),
        )
        val session = PlaybackRangeRequestSession()
        val source = TelegramMediaDataSource(
            gateway = gateway,
            isMainThread = { false },
            fileIdOverride = 42,
            requestSession = session,
        )

        assertEquals(5L, source.open(DataSpec(TestUri(), 0, 5)))
        val output = ByteArray(5)
        assertEquals(5, source.read(output, 0, output.size))

        assertArrayEquals("fresh".toByteArray(), output)
        assertEquals(listOf(stalePath), gateway.invalidatedPaths)
        assertEquals(2, gateway.requests.size)
        assertEquals(42, session.firstRangeReady()?.fileId)
        source.close()
    }

    private fun tempFile(content: String): String =
        Files.createTempFile("cvf-player", ".bin").also {
            Files.write(it, content.toByteArray())
        }.toFile().absolutePath

    private fun snapshot(path: String, size: Long) = TelegramFileSnapshot(
        fileId = 1,
        size = size,
        expectedSize = size,
        localPath = path,
        canBeDownloaded = true,
        isDownloadingActive = false,
        isDownloadingCompleted = true,
        downloadOffset = 0,
        downloadedPrefixSize = size,
        downloadedSize = size,
    )

    private fun partialSnapshot(
        path: String,
        size: Long,
        prefixSize: Long,
    ) = TelegramFileSnapshot(
        fileId = 1,
        size = size,
        expectedSize = size,
        localPath = path,
        canBeDownloaded = true,
        isDownloadingActive = true,
        isDownloadingCompleted = false,
        downloadOffset = 0,
        downloadedPrefixSize = prefixSize,
        downloadedSize = prefixSize,
    )

    private class FakeGateway(
        snapshot: TelegramFileSnapshot?,
        private val timeout: Boolean = false,
        private val blockUntilClosed: Boolean = false,
        private val snapshots: List<TelegramFileSnapshot?> = listOf(snapshot),
        private val failAcquire: Boolean = false,
    ) : TelegramFileGateway {
        val requests = CopyOnWriteArrayList<Triple<Int, Long, Long>>()
        val priorities = CopyOnWriteArrayList<TelegramFileRequestPriority>()
        val ownerKinds = CopyOnWriteArrayList<TelegramFileOwnerKind>()
        val readAheadBytes = CopyOnWriteArrayList<Long>()
        val priorityUpdates = CopyOnWriteArrayList<TelegramFileRequestPriority>()
        val activeLeaseCountsAtAcquire = CopyOnWriteArrayList<Int>()
        val events = CopyOnWriteArrayList<String>()
        val invalidatedPaths = CopyOnWriteArrayList<String>()
        var closedLeases = 0
        var activeLeases = 0
        val awaitStarted = CountDownLatch(1)
        @Volatile
        private var latestSnapshot = snapshot

        override fun acquireRange(
            fileId: Int,
            offset: Long,
            length: Long,
            priority: TelegramFileRequestPriority,
            ownerToken: String,
            ownerKind: TelegramFileOwnerKind,
            readAheadBytes: Long,
        ): TelegramFileRangeLease {
            events += "acquire"
            if (failAcquire) error("acquire failed")
            val requestSnapshot = synchronized(this) {
                snapshots.getOrElse(requests.size) { snapshots.lastOrNull() }
                    .also { latestSnapshot = it }
            }
            requests += Triple(fileId, offset, length)
            priorities += priority
            ownerKinds += ownerKind
            this.readAheadBytes += readAheadBytes
            activeLeases += 1
            activeLeaseCountsAtAcquire += activeLeases
            return object : TelegramFileRangeLease {
                override val fileId: Int = fileId
                override val offset: Long = offset
                override val length: Long = length
                private var closed = false
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                private val monitor = Object()

                override fun awaitAvailable(timeoutMillis: Long): TelegramFileSnapshot {
                    events += "await"
                    if (timeout) throw TelegramFileTimeoutException("test")
                    if (blockUntilClosed) {
                        awaitStarted.countDown()
                        synchronized(monitor) {
                            while (!closed) monitor.wait()
                        }
                        throw TelegramFileUnavailableException("closed")
                    }
                    return requestSnapshot ?: error("missing snapshot")
                }

                override fun updatePriority(priority: TelegramFileRequestPriority) {
                    priorityUpdates += priority
                }

                override fun close() {
                    if (closed) return
                    synchronized(monitor) {
                        closed = true
                        monitor.notifyAll()
                    }
                    closedLeases += 1
                    activeLeases -= 1
                }
            }
        }

        override fun observeFile(fileId: Int): Flow<TelegramFileSnapshot> = emptyFlow()

        override fun currentSnapshot(fileId: Int): TelegramFileSnapshot? = latestSnapshot

        override fun invalidateLocalSnapshot(fileId: Int, expectedLocalPath: String) {
            invalidatedPaths += expectedLocalPath
        }

        override fun pinFile(
            fileId: Int,
            ownerToken: String,
            ownerKind: TelegramFileOwnerKind,
        ): TelegramFileProtectionLease = object : TelegramFileProtectionLease {
            override val fileId: Int = fileId
            override val ownerKind: TelegramFileOwnerKind = ownerKind
            override fun close() = Unit
        }

        override fun protectedFileIds(): Set<Int> = emptySet()

        override suspend fun deleteCachedFile(fileId: Int): TelegramFileDeleteResult =
            TelegramFileDeleteResult.DELETED

        override fun release(ownerToken: String) = Unit
    }
}
