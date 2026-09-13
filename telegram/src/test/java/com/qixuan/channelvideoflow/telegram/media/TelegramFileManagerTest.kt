package com.qixuan.channelvideoflow.telegram.media

import com.qixuan.channelvideoflow.domain.media.TelegramFileDeleteResult
import com.qixuan.channelvideoflow.domain.media.TelegramFileOwnerKind
import com.qixuan.channelvideoflow.domain.media.TelegramFileRequestPriority
import com.qixuan.channelvideoflow.domain.media.TelegramFileUnavailableException
import com.qixuan.channelvideoflow.domain.media.TelegramInternalResourceKind
import com.qixuan.channelvideoflow.domain.media.NetworkTransport
import com.qixuan.channelvideoflow.domain.media.StreamingNetworkMetricsEstimator
import com.qixuan.channelvideoflow.telegram.client.TelegramClientFileSnapshot
import com.qixuan.channelvideoflow.telegram.client.TelegramClientResult
import com.qixuan.channelvideoflow.telegram.client.TelegramFileClient
import com.qixuan.channelvideoflow.telegram.client.TelegramFileClientEvent
import com.qixuan.channelvideoflow.telegram.client.TelegramClientStorageStatistics
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramFileManagerTest {
    @Test
    fun internalHlsResourcesAreGenerationIsolatedProtectedAndRevokedOnLogout() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope, privateFileReadable = { true })
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.Ready)
        runCurrent()
        val generation = manager.currentAccountGeneration()
        val media = manager.registerInternalResource(
            fileId = 801,
            ownerToken = "stage18-owner",
            kind = TelegramInternalResourceKind.HLS_MEDIA,
            expectedSize = 4_096L,
        )
        val manifest = manager.registerInternalResource(
            fileId = 802,
            ownerToken = "stage18-owner",
            kind = TelegramInternalResourceKind.HLS_MANIFEST,
            expectedSize = 256L,
            referencedResources = mapOf(801 to media),
        )

        assertEquals(generation, manifest.accountGeneration)
        assertEquals(802, manager.resolveInternalResource(generation, manifest.opaqueToken)?.fileId)
        assertEquals(setOf(801, 802), manager.protectedFileIds())
        assertEquals(TelegramFileDeleteResult.PROTECTED, manager.deleteCachedFile(801))
        assertEquals(null, manager.resolveInternalResource(generation + 1L, manifest.opaqueToken))

        client.updates.emit(TelegramFileClientEvent.AccountLoggingOut)
        runCurrent()

        assertTrue(manager.currentAccountGeneration() > generation)
        assertEquals(null, manager.resolveInternalResource(generation, manifest.opaqueToken))
        assertTrue(manager.protectedFileIds().isEmpty())
        scope.cancel()
    }

    @Test
    fun internalManifestCannotReferenceAnotherOwnerResource() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val manager = TelegramFileManager(FakeFileClient(), scope, privateFileReadable = { true })
        val foreign = manager.registerInternalResource(
            fileId = 901,
            ownerToken = "foreign-owner",
            kind = TelegramInternalResourceKind.HLS_MEDIA,
        )

        assertThrows(IllegalArgumentException::class.java) {
            manager.registerInternalResource(
                fileId = 902,
                ownerToken = "manifest-owner",
                kind = TelegramInternalResourceKind.HLS_MANIFEST,
                referencedResources = mapOf(901 to foreign),
            )
        }
        manager.revokeInternalResources("foreign-owner")
        assertTrue(manager.protectedFileIds().isEmpty())
        scope.cancel()
    }

    @Test
    fun productionContainedRequestReuseRemainsDisabledAfterTheRejectedBenchmarkCandidate() {
        assertEquals(false, TelegramFileManager.PRODUCTION_REUSE_CONTAINED_ACTIVE_REQUEST)
        assertEquals(10L, TelegramFileManager.PREFIX_QUERY_TIMEOUT_MILLIS)
    }

    @Test
    fun shiftedSnapshotUsesDownloadedPrefixWithoutDuplicateDownload() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val client = FakeFileClient().apply {
            prefixHandler = { _, offset ->
                assertEquals(8L, offset)
                TelegramClientResult.Success(8L)
            }
        }
        val manager = TelegramFileManager(client, scope, privateFileReadable = { true })
        val seed = manager.acquireRange(
            fileId = 50,
            offset = 0,
            length = 8,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "seed",
        )
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.FileUpdated(snapshot(50, 0, 8)))
        runCurrent()
        val shifted = manager.acquireRange(
            fileId = 50,
            offset = 8,
            length = 8,
            priority = TelegramFileRequestPriority.CURRENT_SEEK,
            ownerToken = "shifted",
        )
        advanceUntilIdle()

        val available = shifted.awaitAvailable(100)
        assertEquals(8L, available.downloadOffset)
        assertTrue(available.covers(8, 8))
        assertEquals(listOf(50 to 8L), client.prefixRequests)
        assertEquals(1, client.requests.size)
        seed.close()
        shifted.close()
        scope.cancel()
    }

    @Test
    fun insufficientPrefixFallsBackToOriginalBoundedRange() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val client = FakeFileClient().apply {
            prefixHandler = { _, _ -> TelegramClientResult.Success(2L) }
        }
        val manager = TelegramFileManager(client, scope, privateFileReadable = { true })
        val seed = manager.acquireRange(
            51, 0, 8, TelegramFileRequestPriority.CURRENT_CONTINUATION, "seed",
        )
        runCurrent()
        client.completeNext(TelegramClientResult.Success(snapshot(51, 0, 8)))
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.FileUpdated(snapshot(51, 0, 8)))
        runCurrent()
        val shifted = manager.acquireRange(
            51, 8, 8, TelegramFileRequestPriority.CURRENT_SEEK, "shifted",
        )
        advanceUntilIdle()

        assertEquals(2, client.requests.size)
        assertEquals(8L, client.requests.last().offset)
        assertEquals(8L, client.requests.last().limit)
        seed.close()
        shifted.close()
        scope.cancel()
    }

    @Test
    fun concurrentOwnersShareOnePrefixFlightAndOneCancellationDoesNotBreakTheOther() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val prefixResult = CompletableDeferred<TelegramClientResult<Long>>()
        val client = FakeFileClient().apply { prefixHandler = { _, _ -> prefixResult.await() } }
        val manager = TelegramFileManager(client, scope, privateFileReadable = { true })
        val seed = manager.acquireRange(52, 0, 8, TelegramFileRequestPriority.CURRENT_CONTINUATION, "seed")
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.FileUpdated(snapshot(52, 0, 8)))
        runCurrent()
        val first = manager.acquireRange(52, 8, 8, TelegramFileRequestPriority.CURRENT_SEEK, "first")
        val second = manager.acquireRange(52, 8, 4, TelegramFileRequestPriority.CURRENT_SEEK, "second")
        runCurrent()
        first.close()
        runCurrent()

        assertEquals(1, client.prefixRequests.size)
        prefixResult.complete(TelegramClientResult.Success(8L))
        advanceUntilIdle()
        assertTrue(second.awaitAvailable(100).covers(8, 4))
        assertEquals(1, client.requests.size)
        seed.close()
        second.close()
        scope.cancel()
    }

    @Test
    fun releasingLastOwnerCancelsItsPrefixFlightWithoutStartingDownload() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val cancelled = CompletableDeferred<Unit>()
        val client = FakeFileClient().apply {
            prefixHandler = { _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val manager = TelegramFileManager(client, scope, privateFileReadable = { true })
        val seed = manager.acquireRange(58, 0, 8, TelegramFileRequestPriority.CURRENT_CONTINUATION, "seed")
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.FileUpdated(snapshot(58, 0, 8)))
        runCurrent()
        val shifted = manager.acquireRange(58, 8, 8, TelegramFileRequestPriority.CURRENT_SEEK, "shifted")
        runCurrent()

        shifted.close()
        runCurrent()

        assertTrue(cancelled.isCompleted)
        assertEquals(1, client.requests.size)
        seed.close()
        scope.cancel()
    }

    @Test
    fun seekAtArbitraryOffsetQueriesThatExactPrefix() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val seekOffset = 5L * 1024L * 1024L
        val client = FakeFileClient().apply {
            prefixHandler = { _, offset ->
                assertEquals(seekOffset, offset)
                TelegramClientResult.Success(256L * 1024L)
            }
        }
        val manager = TelegramFileManager(client, scope, privateFileReadable = { true })
        val seed = manager.acquireRange(59, 0, 8, TelegramFileRequestPriority.CURRENT_CONTINUATION, "seed")
        runCurrent()
        client.updates.emit(
            TelegramFileClientEvent.FileUpdated(snapshot(59, 0, 8, size = 10L * 1024L * 1024L)),
        )
        runCurrent()
        val seek = manager.acquireRange(
            59,
            seekOffset,
            256L * 1024L,
            TelegramFileRequestPriority.CURRENT_SEEK,
            "seek",
        )
        advanceUntilIdle()

        assertTrue(seek.awaitAvailable(100).covers(seekOffset, 256L * 1024L))
        assertEquals(listOf(59 to seekOffset), client.prefixRequests)
        assertEquals(1, client.requests.size)
        seed.close()
        seek.close()
        scope.cancel()
    }

    @Test
    fun prefixTimeoutFallsBackAndLateResultCannotPublish() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val prefixResult = CompletableDeferred<TelegramClientResult<Long>>()
        val client = FakeFileClient().apply { prefixHandler = { _, _ -> prefixResult.await() } }
        val manager = TelegramFileManager(
            client = client,
            scope = scope,
            prefixQueryTimeoutMillis = 25L,
            privateFileReadable = { true },
        )
        val seed = manager.acquireRange(53, 0, 8, TelegramFileRequestPriority.CURRENT_CONTINUATION, "seed")
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.FileUpdated(snapshot(53, 0, 8)))
        runCurrent()
        val shifted = manager.acquireRange(53, 8, 8, TelegramFileRequestPriority.CURRENT_SEEK, "shifted")
        runCurrent()
        advanceTimeBy(25L)
        runCurrent()

        assertEquals(2, client.requests.size)
        prefixResult.complete(TelegramClientResult.Success(8L))
        runCurrent()
        assertEquals(2, client.requests.size)
        seed.close()
        shifted.close()
        scope.cancel()
    }

    @Test
    fun stalePrefixPathRefreshesOnceBeforeReturningLeaseLocalHit() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val client = FakeFileClient().apply {
            prefixHandler = { _, _ -> TelegramClientResult.Success(8L) }
            getFileHandler = { fileId ->
                TelegramClientResult.Success(
                    snapshot(fileId, 0, 8, localPath = "private/refreshed"),
                )
            }
        }
        val manager = TelegramFileManager(
            client = client,
            scope = scope,
            privateFileReadable = { path -> path == "private/refreshed" },
        )
        val seed = manager.acquireRange(54, 0, 8, TelegramFileRequestPriority.CURRENT_CONTINUATION, "seed")
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.FileUpdated(snapshot(54, 0, 8, "private/stale")))
        runCurrent()
        val shifted = manager.acquireRange(54, 8, 8, TelegramFileRequestPriority.CURRENT_SEEK, "shifted")
        advanceUntilIdle()

        assertTrue(shifted.awaitAvailable(100).covers(8, 8))
        assertEquals(listOf(54), client.getFileRequests)
        assertEquals(2, client.prefixRequests.size)
        assertEquals(1, client.requests.size)
        seed.close()
        shifted.close()
        scope.cancel()
    }

    @Test
    fun logoutCancelsPrefixFlightAndIgnoresItsLateCompletion() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val prefixStarted = CompletableDeferred<Unit>()
        val client = FakeFileClient().apply {
            prefixHandler = { _, _ ->
                prefixStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val manager = TelegramFileManager(client, scope, privateFileReadable = { true })
        val seed = manager.acquireRange(55, 0, 8, TelegramFileRequestPriority.CURRENT_CONTINUATION, "seed")
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.FileUpdated(snapshot(55, 0, 8)))
        runCurrent()
        val shifted = manager.acquireRange(55, 8, 8, TelegramFileRequestPriority.CURRENT_SEEK, "shifted")
        runCurrent()
        assertTrue(prefixStarted.isCompleted)

        client.updates.emit(TelegramFileClientEvent.AccountLoggingOut)
        runCurrent()

        assertThrows(TelegramFileUnavailableException::class.java) {
            shifted.awaitAvailable(100)
        }
        assertEquals(1, client.requests.size)
        seed.close()
        shifted.close()
        scope.cancel()
    }

    @Test
    fun nextPrefixMissNeverExpandsTheExistingPreloadBudget() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val client = FakeFileClient().apply {
            prefixHandler = { _, _ -> TelegramClientResult.Success(1L) }
        }
        val manager = TelegramFileManager(client, scope, privateFileReadable = { true })
        // A satisfied playback owner on a different file must not affect this file's
        // preload sizing; playback ownership only steers the file it belongs to.
        val seed = manager.acquireRange(55, 0, 8, TelegramFileRequestPriority.CURRENT_CONTINUATION, "seed")
        runCurrent()
        client.completeNext(
            TelegramClientResult.Success(snapshot(55, 0, 8, size = 1_000_000L)),
        )
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.FileUpdated(snapshot(55, 0, 8, size = 1_000_000L)))
        runCurrent()
        val next = manager.acquireRange(
            fileId = 56,
            offset = 0,
            length = 256L * 1024L,
            priority = TelegramFileRequestPriority.NEXT_PRELOAD,
            ownerToken = "next",
            ownerKind = TelegramFileOwnerKind.NEXT_PRELOAD,
            readAheadBytes = 256L * 1024L,
        )
        advanceUntilIdle()

        val nextRequest = client.requests.last { request -> request.fileId == 56 }
        assertEquals(256L * 1024L, nextRequest.limit)
        assertTrue(nextRequest.limit <= 256L * 1024L)
        seed.close()
        next.close()
        scope.cancel()
    }

    @Test
    fun residualPreloadOwnerYieldsToPlaybackOwnershipOnTheSameFile() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val client = FakeFileClient().apply {
            prefixHandler = { _, _ -> TelegramClientResult.Success(1L) }
        }
        val manager = TelegramFileManager(client, scope, privateFileReadable = { true })
        val seed = manager.acquireRange(56, 0, 8, TelegramFileRequestPriority.CURRENT_CONTINUATION, "seed")
        runCurrent()
        client.completeNext(
            TelegramClientResult.Success(snapshot(56, 0, 8, size = 1_000_000L)),
        )
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.FileUpdated(snapshot(56, 0, 8, size = 1_000_000L)))
        runCurrent()
        val requestsBeforeResidual = client.requests.size
        val next = manager.acquireRange(
            fileId = 56,
            offset = 8,
            length = 256L * 1024L,
            priority = TelegramFileRequestPriority.NEXT_PRELOAD,
            ownerToken = "next",
            ownerKind = TelegramFileOwnerKind.NEXT_PRELOAD,
            readAheadBytes = 256L * 1024L,
        )
        advanceUntilIdle()

        // The same file still carries a playback owner (the seed). A residual preload
        // owner must not steer the download window back to its own offset any more: this
        // suppression is what stops the cancel/SWITCH oscillation observed on the device,
        // and the range it wanted is covered by the playback read-ahead when consumption
        // advances.
        val afterResidual = client.requests.drop(requestsBeforeResidual)
        assertTrue(
            "residual preload must not publish new requests while playback owns the file, " +
                "got " + afterResidual.map(Request::offset),
            afterResidual.isEmpty(),
        )
        seed.close()
        next.close()
        scope.cancel()
    }

    @Test
    fun onlyActiveUpdateFileProgressFeedsTheNetworkEstimator() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val client = FakeFileClient()
        val metrics = StreamingNetworkMetricsEstimator().apply {
            resetNetworkContext(NetworkTransport.WIFI, 1L)
        }
        var nanos = 1L
        val manager = TelegramFileManager(
            client = client,
            scope = scope,
            networkMetrics = metrics,
            monotonicNanos = { nanos },
        )
        val lease = manager.acquireRange(
            fileId = 57,
            offset = 0,
            length = 128L * 1024L,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "current",
        )
        runCurrent()
        repeat(3) { index ->
            nanos += 1_000_000_000L
            client.updates.emit(
                TelegramFileClientEvent.FileUpdated(
                    snapshot(
                        fileId = 57,
                        offset = 0,
                        prefixSize = (index + 1L) * 32L * 1024L,
                        size = 1_000_000L,
                    ),
                ),
            )
            runCurrent()
        }

        assertTrue(metrics.estimate.value != null)
        val revision = metrics.estimate.value!!.revision
        lease.close()
        nanos += 1_000_000_000L
        client.updates.emit(
            TelegramFileClientEvent.FileUpdated(
                snapshot(57, 0, 128L * 1024L, size = 1_000_000L),
            ),
        )
        runCurrent()
        assertEquals(revision, metrics.estimate.value!!.revision)
        scope.cancel()
    }

    @Test
    fun fullyOverlappingActiveRangesTriggerOnlyOneEffectiveTdlibRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)

        val first = manager.acquireRange(
            fileId = 5,
            offset = 0,
            length = 256L * 1024L,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "first",
        )
        runCurrent()
        val duplicate = manager.acquireRange(
            fileId = 5,
            offset = 0,
            length = 256L * 1024L,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "duplicate",
        )
        runCurrent()

        assertEquals(1, client.requests.size)
        first.close()
        duplicate.close()
        scope.cancel()
    }

    @Test
    fun containedRangeSharesTheOuterRequestAndBothWaitersReadTheSameProgress() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val outer = manager.acquireRange(
            fileId = 6,
            offset = 0,
            length = 8,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "outer",
        )
        runCurrent()
        val inner = manager.acquireRange(
            fileId = 6,
            offset = 2,
            length = 4,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "inner",
        )
        runCurrent()
        client.updates.emit(
            TelegramFileClientEvent.FileUpdated(
                snapshot(fileId = 6, offset = 0, prefixSize = 8),
            ),
        )
        runCurrent()

        assertTrue(outer.awaitAvailable(100).covers(0, 8))
        assertTrue(inner.awaitAvailable(100).covers(2, 4))
        assertEquals(1, client.requests.size)
        outer.close()
        inner.close()
        scope.cancel()
    }

    @Test
    fun rangesForDifferentFileIdsNeverMerge() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)

        val first = manager.acquireRange(
            fileId = 7,
            offset = 0,
            length = 8,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "first-file",
        )
        val second = manager.acquireRange(
            fileId = 8,
            offset = 0,
            length = 8,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "second-file",
        )
        runCurrent()

        assertEquals(setOf(7, 8), client.requests.map(Request::fileId).toSet())
        assertEquals(2, client.requests.size)
        first.close()
        second.close()
        scope.cancel()
    }

    @Test
    fun closingALeaseReleasesOnlyItsOwnFileWhenOwnerTokensCollide() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val first = manager.acquireRange(
            fileId = 70,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "shared-token",
        )
        val second = manager.acquireRange(
            fileId = 71,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "shared-token",
        )

        first.close()

        assertEquals(setOf(71), manager.protectedFileIds())
        second.close()
        assertTrue(manager.protectedFileIds().isEmpty())
        scope.cancel()
    }

    @Test
    fun staleUpdateFileForAnotherFileIdDoesNotWakeTheCurrentRange() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val lease = manager.acquireRange(
            fileId = 9,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "current",
        )
        runCurrent()
        client.updates.emit(
            TelegramFileClientEvent.FileUpdated(
                snapshot(fileId = 10, offset = 0, prefixSize = 4),
            ),
        )
        runCurrent()

        assertEquals(null, manager.currentSnapshot(9))
        lease.close()
        scope.cancel()
    }

    @Test
    fun staleDownloadResponseForAnotherFileDoesNotCompleteTheCurrentRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val lease = manager.acquireRange(
            fileId = 4,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "current",
        )
        runCurrent()

        client.completeNext(
            TelegramClientResult.Success(snapshot(fileId = 99, offset = 0, prefixSize = 4)),
        )
        runCurrent()

        assertEquals(null, manager.currentSnapshot(4))
        lease.close()
        scope.cancel()
    }

    @Test
    fun partiallyOverlappingActiveRangesAreReissuedAsOneBoundedUnion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)

        val first = manager.acquireRange(
            fileId = 3,
            offset = 0,
            length = 256L * 1024L,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "first",
        )
        runCurrent()
        val overlapping = manager.acquireRange(
            fileId = 3,
            offset = 128L * 1024L,
            length = 256L * 1024L,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "overlapping",
        )
        runCurrent()

        assertEquals(
            listOf(
                Request(3, 0, 256L * 1024L, 24),
                Request(3, 0, 384L * 1024L, 24),
            ),
            client.requests,
        )
        assertTrue(client.requests.all { request -> request.limit <= 4L * 1024L * 1024L })
        first.close()
        overlapping.close()
        scope.cancel()
    }

    @Test
    fun currentStartupPreemptsNextPreloadAcrossFilesAndPreloadResumesAfterStartup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)

        val preload = manager.acquireRange(
            fileId = 1,
            offset = 0,
            length = 256L * 1024L,
            priority = TelegramFileRequestPriority.NEXT_PRELOAD,
            ownerToken = "next",
            ownerKind = TelegramFileOwnerKind.NEXT_PRELOAD,
        )
        runCurrent()
        val startup = manager.acquireRange(
            fileId = 2,
            offset = 0,
            length = 256L * 1024L,
            priority = TelegramFileRequestPriority.CURRENT_STARTUP,
            ownerToken = "current",
            ownerKind = TelegramFileOwnerKind.CURRENT_PLAYBACK,
        )
        runCurrent()

        assertEquals(
            listOf(
                Request(
                    1,
                    0,
                    256L * 1024L,
                    TelegramFileRequestPriority.NEXT_PRELOAD.tdLibPriority,
                ),
                Request(
                    2,
                    0,
                    256L * 1024L,
                    TelegramFileRequestPriority.CURRENT_STARTUP.tdLibPriority,
                ),
            ),
            client.requests,
        )
        assertEquals(listOf(1), client.cancelledFileIds)

        startup.close()
        runCurrent()

        assertEquals(
            listOf(1, 2, 1),
            client.requests.map(Request::fileId),
        )
        preload.close()
        scope.cancel()
    }

    @Test
    fun sameFileCurrentStartupReusesTheActivePreloadRequestWhileRaisingPriority() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(
            client = client,
            scope = scope,
            reuseContainedActiveRequest = true,
        )
        val preload = manager.acquireRange(
            fileId = 21,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.NEXT_PRELOAD,
            ownerToken = "next",
            ownerKind = TelegramFileOwnerKind.NEXT_PRELOAD,
        )
        runCurrent()
        val current = manager.acquireRange(
            fileId = 21,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.CURRENT_STARTUP,
            ownerToken = "current",
            ownerKind = TelegramFileOwnerKind.CURRENT_PLAYBACK,
        )
        runCurrent()

        assertEquals(listOf(8, 32), client.requests.map(Request::priority))
        assertTrue(client.cancelledFileIds.isEmpty())

        client.completeNext(
            TelegramClientResult.Success(snapshot(fileId = 21, offset = 0, prefixSize = 4)),
        )
        runCurrent()

        assertTrue(manager.currentSnapshot(21)?.covers(0, 4) == true)
        preload.close()
        assertEquals(setOf(21), manager.protectedFileIds())
        current.close()
        scope.cancel()
    }

    @Test
    fun firstFrameReprioritizationResumesTheYieldedNextPreload() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val preload = manager.acquireRange(
            fileId = 11,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.NEXT_PRELOAD,
            ownerToken = "next",
            ownerKind = TelegramFileOwnerKind.NEXT_PRELOAD,
        )
        runCurrent()
        val current = manager.acquireRange(
            fileId = 12,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.CURRENT_STARTUP,
            ownerToken = "current",
        )
        runCurrent()

        current.updatePriority(TelegramFileRequestPriority.CURRENT_CONTINUATION)
        runCurrent()

        assertTrue(
            client.requests.any { request ->
                request.fileId == 12 &&
                    request.priority ==
                    TelegramFileRequestPriority.CURRENT_CONTINUATION.tdLibPriority
            },
        )
        assertEquals(2, client.requests.count { request -> request.fileId == 11 })
        current.close()
        preload.close()
        scope.cancel()
    }

    @Test
    fun accountLogoutCancelsOwnersAndWakesAllRangeWaiters() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val lease = manager.acquireRange(
            fileId = 13,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "current",
        )
        runCurrent()

        client.updates.emit(TelegramFileClientEvent.AccountLoggingOut)
        runCurrent()

        assertThrows(TelegramFileUnavailableException::class.java) {
            lease.awaitAvailable(5_000L)
        }
        assertTrue(manager.protectedFileIds().isEmpty())
        lease.close()
        scope.cancel()
    }

    @Test
    fun currentAndNextOwnersAreProtectedFromDeletionUntilTheirLeasesClose() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val current = manager.pinFile(
            fileId = 1,
            ownerToken = "current",
            ownerKind = TelegramFileOwnerKind.CURRENT_PLAYBACK,
        )
        val next = manager.acquireRange(
            fileId = 2,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.NEXT_PRELOAD,
            ownerToken = "next",
            ownerKind = TelegramFileOwnerKind.NEXT_PRELOAD,
        )

        assertEquals(setOf(1, 2), manager.protectedFileIds())
        assertEquals(TelegramFileDeleteResult.PROTECTED, manager.deleteCachedFile(1))
        assertEquals(TelegramFileDeleteResult.PROTECTED, manager.deleteCachedFile(2))
        assertTrue(client.deletedFileIds.isEmpty())

        current.close()
        next.close()
        assertEquals(TelegramFileDeleteResult.DELETED, manager.deleteCachedFile(1))
        assertEquals(TelegramFileDeleteResult.DELETED, manager.deleteCachedFile(2))
        assertEquals(listOf(1, 2), client.deletedFileIds)
        scope.cancel()
    }

    @Test
    fun concurrentOwnersShareOneMergedTdlibRangeAndLastReleaseCancels() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)

        val first = manager.acquireRange(
            7,
            0,
            4,
            priority = TelegramFileRequestPriority.CURRENT_STARTUP,
            ownerToken = "a",
        )
        val second = manager.acquireRange(
            7,
            2,
            6,
            priority = TelegramFileRequestPriority.CURRENT_STARTUP,
            ownerToken = "b",
        )
        runCurrent()

        assertEquals(1, client.requests.size)
        assertEquals(Request(7, 0, 8, 32), client.requests.single())
        first.close()
        runCurrent()
        assertEquals(0, client.cancelCount)
        second.close()
        advanceTimeBy(TelegramFileManager.CANCEL_GRACE_MILLIS)
        runCurrent()
        assertEquals(1, client.cancelCount)
        scope.cancel()
    }

    @Test
    fun updateFileSnapshotWakesLeaseAndUsesThreadSafeState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val lease = manager.acquireRange(
            9,
            4,
            3,
            TelegramFileRequestPriority.CURRENT_STARTUP,
            "owner",
        )
        runCurrent()

        client.updates.emit(
            TelegramFileClientEvent.FileUpdated(
                TelegramClientFileSnapshot(
                    fileId = 9,
                    size = 10,
                    expectedSize = 10,
                    localPath = "private/path",
                    canBeDownloaded = true,
                    isDownloadingActive = false,
                    isDownloadingCompleted = false,
                    downloadOffset = 0,
                    downloadedPrefixSize = 7,
                    downloadedSize = 7,
                ),
            ),
        )
        runCurrent()

        val snapshot = lease.awaitAvailable(100)
        assertTrue(snapshot.covers(4, 3))
        lease.close()
        scope.cancel()
    }

    @Test
    fun invalidatedCoveredLocalPathForcesTheRetryThroughOfficialTdlibRangeRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        manager.observeFile(31)
        runCurrent()
        client.updates.emit(
            TelegramFileClientEvent.FileUpdated(
                snapshot(fileId = 31, offset = 0, prefixSize = 4),
            ),
        )
        runCurrent()

        val cachedLease = manager.acquireRange(
            fileId = 31,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.CURRENT_STARTUP,
            ownerToken = "cached",
        )
        runCurrent()
        assertTrue(client.requests.isEmpty())
        cachedLease.close()

        manager.invalidateLocalSnapshot(fileId = 31, expectedLocalPath = "private/path")
        assertEquals(null, manager.currentSnapshot(31))
        val retryLease = manager.acquireRange(
            fileId = 31,
            offset = 0,
            length = 4,
            priority = TelegramFileRequestPriority.CURRENT_STARTUP,
            ownerToken = "retry",
        )
        runCurrent()

        assertEquals(listOf(Request(31, 0, 4, 32)), client.requests)
        retryLease.close()
        scope.cancel()
    }

    @Test
    fun releaseBeforeTheQueuedRequestRunsDoesNotStartDownload() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)

        manager.acquireRange(
            13,
            0,
            4,
            TelegramFileRequestPriority.CURRENT_STARTUP,
            "owner",
        ).close()
        advanceTimeBy(TelegramFileManager.CANCEL_GRACE_MILLIS)
        runCurrent()

        assertTrue(client.requests.isEmpty())
        assertEquals(1, client.cancelCount)
        scope.cancel()
    }

    @Test
    fun replacingAnUnusedRangeCancelsTheOldTdlibCursorBeforeStartingTheNewOne() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)

        manager.acquireRange(
            15,
            0,
            4,
            TelegramFileRequestPriority.CURRENT_STARTUP,
            "first",
        ).close()
        val next = manager.acquireRange(
            15,
            4,
            4,
            TelegramFileRequestPriority.CURRENT_STARTUP,
            "second",
        )
        runCurrent()

        assertEquals(1, client.cancelCount)
        assertEquals(listOf(Request(15, 4, 4, 32)), client.requests)
        next.close()
        scope.cancel()
    }

    @Test
    fun replacingAStartedRangeCancelsOnlyTheSupersededTdlibCursor() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)

        val first = manager.acquireRange(
            16,
            0,
            4,
            TelegramFileRequestPriority.CURRENT_STARTUP,
            "first",
        )
        runCurrent()
        first.close()
        val next = manager.acquireRange(
            16,
            4,
            4,
            TelegramFileRequestPriority.CURRENT_STARTUP,
            "second",
        )
        runCurrent()

        assertEquals(listOf(Request(16, 0, 4, 32), Request(16, 4, 4, 32)), client.requests)
        advanceTimeBy(TelegramFileManager.CANCEL_GRACE_MILLIS)
        runCurrent()
        assertEquals(1, client.cancelCount)

        next.close()
        advanceTimeBy(TelegramFileManager.CANCEL_GRACE_MILLIS)
        runCurrent()
        assertEquals(2, client.cancelCount)
        scope.cancel()
    }

    @Test
    fun satisfiedEarlierOwnerDoesNotBlockLaterConcurrentRange() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val first = manager.acquireRange(
            17,
            0,
            4,
            TelegramFileRequestPriority.CURRENT_STARTUP,
            "first",
        )
        val later = manager.acquireRange(
            17,
            1_024_000,
            4,
            TelegramFileRequestPriority.CURRENT_STARTUP,
            "later",
        )
        runCurrent()

        val firstSnapshot = snapshot(fileId = 17, offset = 0, prefixSize = 512L * 1024L)
        client.updates.emit(TelegramFileClientEvent.FileUpdated(firstSnapshot))
        client.completeNext(TelegramClientResult.Success(firstSnapshot))
        runCurrent()

        assertEquals(
            listOf(Request(17, 0, 4, 32), Request(17, 1_024_000, 4, 32)),
            client.requests,
        )
        first.close()
        later.close()
        scope.cancel()
    }

    @Test
    fun startupRangeRequestsOnlyItsFirstWindow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val requestedWindow = TelegramFileManager.FOREGROUND_READ_AHEAD_TRIGGER_BYTES
        val lease = manager.acquireRange(
            fileId = 18,
            offset = 0,
            length = requestedWindow,
            priority = TelegramFileManager.FOREGROUND_PRIORITY,
            ownerToken = "foreground",
        )
        runCurrent()

        assertEquals(
            listOf(
                Request(
                    fileId = 18,
                    offset = 0,
                    limit = requestedWindow,
                    priority = TelegramFileManager.FOREGROUND_PRIORITY.tdLibPriority,
                ),
            ),
            client.requests,
        )

        client.updates.emit(
            TelegramFileClientEvent.FileUpdated(
                snapshot(fileId = 18, offset = 0, prefixSize = requestedWindow),
            ),
        )
        runCurrent()

        assertTrue(lease.awaitAvailable(100).covers(0, requestedWindow))
        lease.close()
        scope.cancel()
    }

    @Test
    fun confirmedContinuationMayUseFourMiBToAbsorbTransferJitter() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val lease = manager.acquireRange(
            fileId = 19,
            offset = 0,
            length = TelegramFileManager.FOREGROUND_READ_AHEAD_TRIGGER_BYTES,
            priority = TelegramFileManager.FOREGROUND_PRIORITY,
            ownerToken = "foreground",
            readAheadBytes = TelegramFileManager.MAX_FOREGROUND_REQUEST_BYTES,
        )
        runCurrent()

        assertEquals(
            listOf(
                Request(
                    fileId = 19,
                    offset = 0,
                    limit = 4L * 1024L * 1024L,
                    priority = TelegramFileManager.FOREGROUND_PRIORITY.tdLibPriority,
                ),
            ),
            client.requests,
        )

        lease.close()
        scope.cancel()
    }

    @Test
    fun relevantDownloadProgressExtendsTheStallDeadline() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val lease = manager.acquireRange(
            fileId = 20,
            offset = 0,
            length = 4,
            priority = TelegramFileManager.FOREGROUND_PRIORITY,
            ownerToken = "slow-progress",
        )
        val executor = Executors.newSingleThreadExecutor()
        val waitStarted = CountDownLatch(1)
        val result = executor.submit(
            Callable {
                waitStarted.countDown()
                lease.awaitAvailable(STALL_TIMEOUT_MILLIS)
            },
        )

        try {
            assertTrue(waitStarted.await(1, TimeUnit.SECONDS))
            Thread.sleep(PROGRESS_INTERVAL_MILLIS)
            assertTrue(
                client.updates.tryEmit(
                    TelegramFileClientEvent.FileUpdated(
                        snapshot(fileId = 20, offset = 0, prefixSize = 1),
                    ),
                ),
            )
            Thread.sleep(PROGRESS_INTERVAL_MILLIS)
            assertTrue(
                client.updates.tryEmit(
                    TelegramFileClientEvent.FileUpdated(
                        snapshot(fileId = 20, offset = 0, prefixSize = 4),
                    ),
                ),
            )

            assertTrue(result.get(1, TimeUnit.SECONDS).covers(0, 4))
        } finally {
            lease.close()
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun closingALeaseWakesAWaitingLoaderImmediately() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope)
        val lease = manager.acquireRange(
            fileId = 21,
            offset = 0,
            length = 4,
            priority = TelegramFileManager.FOREGROUND_PRIORITY,
            ownerToken = "cancel-wait",
        )
        val executor = Executors.newSingleThreadExecutor()
        val waitStarted = CountDownLatch(1)
        val result = executor.submit(
            Callable {
                waitStarted.countDown()
                lease.awaitAvailable(5_000L)
            },
        )

        try {
            assertTrue(waitStarted.await(1, TimeUnit.SECONDS))
            lease.close()

            val failure = assertThrows(ExecutionException::class.java) {
                result.get(1, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is TelegramFileUnavailableException)
        } finally {
            lease.close()
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun progressAwareWaitBudgetResetsStallButKeepsAHardLimit() {
        val budget = ProgressAwareRangeWaitBudget(
            stallTimeoutMillis = 100L,
            startedAtMillis = 0L,
            hardTimeoutMultiplier = 6,
        )

        assertEquals(100L, budget.remainingWaitMillis(0L))
        budget.observeProgress(progressBytes = 1L, nowMillis = 90L)
        assertEquals(100L, budget.remainingWaitMillis(90L))
        budget.observeProgress(progressBytes = 2L, nowMillis = 180L)
        budget.observeProgress(progressBytes = 3L, nowMillis = 270L)
        budget.observeProgress(progressBytes = 4L, nowMillis = 360L)
        budget.observeProgress(progressBytes = 5L, nowMillis = 450L)
        assertEquals(50L, budget.remainingWaitMillis(500L))
        assertEquals(0L, budget.remainingWaitMillis(600L))
        assertEquals("HARD_LIMIT", budget.timeoutReason(600L))
    }

    @Test
    fun unchangedProgressDoesNotResetTheStallDeadline() {
        val budget = ProgressAwareRangeWaitBudget(
            stallTimeoutMillis = 100L,
            startedAtMillis = 0L,
            hardTimeoutMultiplier = 6,
        )

        budget.observeProgress(progressBytes = 1L, nowMillis = 50L)
        budget.observeProgress(progressBytes = 1L, nowMillis = 140L)

        assertEquals(0L, budget.remainingWaitMillis(150L))
        assertEquals("NO_PROGRESS", budget.timeoutReason(150L))
    }

    @Test
    fun playbackOwnershipSuppressesResidualPreloadSteering() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope, privateFileReadable = { true })

        // A leftover "next" preload owner parks at a forward offset, as when an unfinished
        // tail chunk of a promoted preparation stays behind after a swipe.
        val residual = manager.acquireRange(
            fileId = 61,
            offset = 4_096,
            length = 256,
            priority = TelegramFileRequestPriority.NEXT_PRELOAD,
            ownerToken = "residual-preload",
            ownerKind = TelegramFileOwnerKind.NEXT_PRELOAD,
            readAheadBytes = 256,
        )
        runCurrent()
        val beforePlayback = client.requests.size
        assertTrue(
            "the single preload owner drives its own request first",
            client.requests.any { request -> request.offset == 4_096L },
        )

        // Playback takes over the same file at the head and its range becomes local.
        val playback = manager.acquireRange(
            fileId = 61,
            offset = 0,
            length = 256,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "current-playback",
            ownerKind = TelegramFileOwnerKind.CURRENT_PLAYBACK,
            readAheadBytes = 256,
        )
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.FileUpdated(snapshot(61, 0, 256)))
        runCurrent()
        advanceUntilIdle()

        // Consumption advances; every playback range is satisfied while the residual
        // preload range never arrives. Every evaluation in this state used to steer the
        // download window back to the residual offset, and each oscillation cancelled
        // in-flight parts (device evidence: such files stayed near 479 KiB/s while
        // unobstructed files reached 1.3-3.4 MiB/s).
        val probe = manager.acquireRange(
            fileId = 61,
            offset = 256,
            length = 256,
            priority = TelegramFileRequestPriority.CURRENT_CONTINUATION,
            ownerToken = "probe",
            ownerKind = TelegramFileOwnerKind.CURRENT_PLAYBACK,
            readAheadBytes = 256,
        )
        runCurrent()
        client.updates.emit(TelegramFileClientEvent.FileUpdated(snapshot(61, 0, 512)))
        runCurrent()
        advanceUntilIdle()

        val afterPlayback = client.requests.drop(beforePlayback)
        assertTrue(
            "playback ownership must suppress residual preload steering, requests=" +
                afterPlayback.map(Request::offset),
            afterPlayback.none { request -> request.offset >= 4_096L },
        )

        residual.close()
        playback.close()
        probe.close()
        scope.cancel()
    }

    @Test
    fun preloadOwnersStillDriveTheirOwnFileWithoutPlaybackOwnership() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val client = FakeFileClient()
        val manager = TelegramFileManager(client, scope, privateFileReadable = { true })

        val preload = manager.acquireRange(
            fileId = 62,
            offset = 0,
            length = 256,
            priority = TelegramFileRequestPriority.NEXT_PRELOAD,
            ownerToken = "next-owner",
            ownerKind = TelegramFileOwnerKind.NEXT_PRELOAD,
            readAheadBytes = 256,
        )
        runCurrent()

        assertTrue(
            "a file without playback owners must still let the preload owner request data",
            client.requests.any { request -> request.fileId == 62 && request.offset == 0L },
        )

        preload.close()
        scope.cancel()
    }

    private fun snapshot(
        fileId: Int,
        offset: Long,
        prefixSize: Long,
        localPath: String = "private/path",
        size: Long = 20L,
    ) = TelegramClientFileSnapshot(
        fileId = fileId,
        size = size,
        expectedSize = size,
        localPath = localPath,
        canBeDownloaded = true,
        isDownloadingActive = false,
        isDownloadingCompleted = false,
        downloadOffset = offset,
        downloadedPrefixSize = prefixSize,
        downloadedSize = prefixSize,
    )

    private data class Request(
        val fileId: Int,
        val offset: Long,
        val limit: Long,
        val priority: Int,
    )

    private class FakeFileClient : TelegramFileClient {
        val updates = MutableSharedFlow<TelegramFileClientEvent>(extraBufferCapacity = 8)
        val requests = CopyOnWriteArrayList<Request>()
        var cancelCount = 0
        val cancelledFileIds = CopyOnWriteArrayList<Int>()
        val deletedFileIds = CopyOnWriteArrayList<Int>()
        val getFileRequests = CopyOnWriteArrayList<Int>()
        val prefixRequests = CopyOnWriteArrayList<Pair<Int, Long>>()
        var prefixHandler: suspend (Int, Long) -> TelegramClientResult<Long> = { _, _ ->
            TelegramClientResult.Failure(
                com.qixuan.channelvideoflow.telegram.client.TelegramClientFailure.SessionUnavailable,
            )
        }
        var getFileHandler: suspend (Int) ->
            TelegramClientResult<TelegramClientFileSnapshot> = {
                TelegramClientResult.Failure(
                    com.qixuan.channelvideoflow.telegram.client.TelegramClientFailure.SessionUnavailable,
                )
            }
        private val responses =
            ArrayDeque<CompletableDeferred<TelegramClientResult<TelegramClientFileSnapshot>>>()

        override val fileEvents: Flow<TelegramFileClientEvent> = updates

        override suspend fun downloadFile(
            fileId: Int,
            priority: Int,
            offset: Long,
            limit: Long,
        ): TelegramClientResult<TelegramClientFileSnapshot> {
            requests += Request(fileId, offset, limit, priority)
            val deferred = CompletableDeferred<TelegramClientResult<TelegramClientFileSnapshot>>()
            responses += deferred
            return deferred.await()
        }

        override suspend fun cancelDownloadFile(fileId: Int): TelegramClientResult<Unit> {
            cancelCount += 1
            cancelledFileIds += fileId
            return TelegramClientResult.Success(Unit)
        }

        override suspend fun getFile(
            fileId: Int,
        ): TelegramClientResult<TelegramClientFileSnapshot> {
            getFileRequests += fileId
            return getFileHandler(fileId)
        }

        override suspend fun getFileDownloadedPrefixSize(
            fileId: Int,
            offset: Long,
        ): TelegramClientResult<Long> {
            prefixRequests += fileId to offset
            return prefixHandler(fileId, offset)
        }

        override suspend fun deleteFile(fileId: Int): TelegramClientResult<Unit> =
            TelegramClientResult.Success(Unit).also { deletedFileIds += fileId }

        override suspend fun getStorageStatistics():
            TelegramClientResult<TelegramClientStorageStatistics> =
            TelegramClientResult.Success(TelegramClientStorageStatistics(0L, 0))

        override suspend fun optimizeVideoStorage(
            maxBytes: Long,
        ): TelegramClientResult<TelegramClientStorageStatistics> =
            TelegramClientResult.Success(TelegramClientStorageStatistics(0L, 0))

        fun completeNext(result: TelegramClientResult<TelegramClientFileSnapshot>) {
            responses.removeFirst().complete(result)
        }
    }

    private companion object {
        const val STALL_TIMEOUT_MILLIS = 500L
        const val PROGRESS_INTERVAL_MILLIS = 300L
    }
}
