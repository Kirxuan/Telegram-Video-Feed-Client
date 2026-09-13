package com.qixuan.channelvideoflow.player

import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadController
import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadDecision
import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadPolicyStateMachine
import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadReason
import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadState
import com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetController
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetTier
import com.qixuan.channelvideoflow.domain.media.NextPreloadSafetySnapshot
import com.qixuan.channelvideoflow.domain.media.NextPreloadStopReason
import com.qixuan.channelvideoflow.domain.media.PlaybackRiskState
import com.qixuan.channelvideoflow.domain.media.TelegramFileDeleteResult
import com.qixuan.channelvideoflow.domain.media.TelegramFileGateway
import com.qixuan.channelvideoflow.domain.media.TelegramFileOwnerKind
import com.qixuan.channelvideoflow.domain.media.TelegramFileProtectionLease
import com.qixuan.channelvideoflow.domain.media.TelegramFileRangeLease
import com.qixuan.channelvideoflow.domain.media.TelegramFileRequestPriority
import com.qixuan.channelvideoflow.domain.media.TelegramFileSnapshot
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.TelegramMediaFileReference
import com.qixuan.channelvideoflow.model.video.VideoKey
import com.qixuan.channelvideoflow.model.video.VideoPlaybackVariant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPreloadManagerTest {
    @Test
    fun productionOwnerPromotionRemainsDisabledAfterTheRejectedBenchmarkCandidate() {
        assertEquals(false, VideoPreloadManager.PRODUCTION_OWNER_PROMOTION_ENABLED)
    }

    @Test
    fun dynamicBudgetUsesBoundedChunksAndNeverExceedsTwentyMib() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway = gateway,
            adaptivePolicy = FakeAdaptivePolicy(normalDecision()),
            scope = scope,
            dynamicNextPreloadEnabled = true,
            initialSafety = safe(bufferSeconds = 40.0),
        )
        val highBitrate = video(40).copy(
            fileSize = 100L * 1024L * 1024L,
            durationSeconds = 10,
        )

        manager.setNextVideo(highBitrate)
        advanceUntilIdle()

        val requests = gateway.requests.filter { it.fileId == 40 }
        assertEquals(20L * 1024L * 1024L, requests.sumOf(Request::length))
        assertTrue(requests.all { it.length <= NextPreloadBudgetController.RANGE_CHUNK_BYTES })
        assertEquals(NextPreloadBudgetTier.TWENTY_MIB, manager.currentBudgetDecision()?.allowedBudgetTier)
        assertEquals(20L * 1024L * 1024L, manager.currentBudgetDecision()?.requestedUncachedBytes)
        scope.cancel()
    }

    @Test
    fun mobileDefaultAndCurrentLowWatermarkIssueNoNextPayload() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
            dynamicNextPreloadEnabled = true,
            initialSafety = safe(40.0).copy(isMobileNetwork = true, isMetered = true),
        )

        manager.setNextVideo(video(41))
        advanceUntilIdle()
        assertTrue(gateway.requests.isEmpty())

        manager.updateCurrentPlaybackSafety(safe(20.0))
        advanceUntilIdle()
        assertTrue(gateway.requests.isNotEmpty())
        manager.updateCurrentPlaybackSafety(safe(2.5))
        advanceUntilIdle()
        assertEquals(0, gateway.activeLeases)
        assertEquals(NextPreloadBudgetTier.BLOCKED, manager.currentBudgetDecision()?.allowedBudgetTier)
        scope.cancel()
    }

    @Test
    fun cachedPrefixIsCoveredWithoutChargingNewNetworkBudget() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway().apply {
            snapshots[42] = snapshot(
                downloadOffset = 0L,
                downloadedPrefixSize = 1L * 1024L * 1024L,
                size = 10L * 1024L * 1024L,
            ).copy(fileId = 42)
        }
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
            dynamicNextPreloadEnabled = true,
            initialSafety = safe(20.0),
        )
        val target = video(42).copy(fileSize = 10L * 1024L * 1024L, durationSeconds = 10)

        manager.setNextVideo(target)
        advanceUntilIdle()

        assertEquals(1L * 1024L * 1024L, gateway.requests.first().offset)
        assertEquals(1L * 1024L * 1024L, manager.currentBudgetDecision()?.cachedCoveredBytes)
        assertTrue(manager.currentBudgetDecision()!!.requestedUncachedBytes <= 1L * 1024L * 1024L)
        scope.cancel()
    }

    @Test
    fun changingTargetCancelsEveryOldRangeKeepsOnlyOneNextAndRecordsWaste() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
            dynamicNextPreloadEnabled = true,
            initialSafety = safe(20.0),
        )

        manager.setNextVideo(video(43))
        advanceUntilIdle()
        manager.setNextVideo(video(44))
        advanceUntilIdle()

        assertEquals(setOf(44), gateway.activeFileIds())
        assertTrue(manager.currentBudgetDecision()!!.skippedNextWastedBytes > 0L)
        assertTrue(
            manager.currentBudgetDecision()!!.skippedNextWastedBytes <=
                NextPreloadBudgetController.ABSOLUTE_MAX_BYTES,
        )
        scope.cancel()
    }

    @Test
    fun onlyLatestNextVideoOwnsOneSmallLowPriorityRange() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val policy = FakeAdaptivePolicy(normalDecision())
        val manager = VideoPreloadManager(gateway, policy, scope)
        runCurrent()

        manager.setNextVideo(video(1))
        runCurrent()
        manager.setNextVideo(video(2))
        manager.setNextVideo(video(3))
        runCurrent()

        assertEquals(listOf(1, 2, 3), gateway.requests.map(Request::fileId))
        assertEquals(
            List(3) { VideoPreloadManager.PRELOAD_BYTES },
            gateway.requests.map(Request::length),
        )
        assertEquals(
            List(3) { VideoPreloadManager.PRELOAD_PRIORITY },
            gateway.requests.map(Request::priority),
        )
        assertTrue(
            gateway.requests.all { it.ownerKind == TelegramFileOwnerKind.NEXT_PRELOAD },
        )
        assertEquals(listOf(1, 2), gateway.closedFileIds)
        manager.stop()
        runCurrent()
        assertEquals(listOf(1, 2, 3), gateway.closedFileIds)
        scope.cancel()
    }

    @Test
    fun policyChangesCancelAndResumeOnlyTheSameNextTarget() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val policy = FakeAdaptivePolicy(normalDecision())
        val manager = VideoPreloadManager(gateway, policy, scope)
        runCurrent()
        manager.setNextVideo(video(9))
        runCurrent()

        policy.mutable.value = offDecision()
        runCurrent()
        assertEquals(listOf(9), gateway.closedFileIds)

        policy.mutable.value = conservativeDecision()
        runCurrent()
        assertEquals(listOf(9, 9), gateway.requests.map(Request::fileId))
        assertEquals(
            AdaptivePreloadPolicyStateMachine.CONSERVATIVE_PRELOAD_BYTES,
            gateway.requests.last().length,
        )
        scope.cancel()
    }

    @Test
    fun currentBindDecisionClearsThePreviousItemsStaleNextTarget() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val policy = FakeAdaptivePolicy(normalDecision())
        val manager = VideoPreloadManager(gateway, policy, scope)
        runCurrent()
        manager.setNextVideo(video(9))
        runCurrent()

        policy.mutable.value = offDecision(AdaptivePreloadReason.CURRENT_NOT_STABLE)
        runCurrent()
        policy.mutable.value = conservativeDecision()
        runCurrent()

        assertEquals(listOf(9), gateway.requests.map(Request::fileId))
        assertEquals(listOf(9), gateway.closedFileIds)
        scope.cancel()
    }

    @Test
    fun selectedServerVariantDrivesPreloadFileAndLength() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
        )
        val selected = video(9).copy(
            selectedAlternative = VideoPlaybackVariant(
                fileId = 109,
                remoteUniqueId = "alternative-109",
                fileSize = 128L * 1024L,
                width = 640,
                height = 360,
                codec = "h264",
            ),
        )
        runCurrent()

        manager.setNextVideo(selected)
        runCurrent()

        assertEquals(109, gateway.requests.single().fileId)
        assertEquals(128L * 1024L, gateway.requests.single().length)
        scope.cancel()
    }

    @Test
    fun unsupportedStreamingNeverAcquiresNextPreloadRange() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
            startupCandidate = StartupPreloadCandidate.TAIL_128,
        )
        runCurrent()

        manager.setNextVideo(video(9).copy(supportsStreaming = false))
        runCurrent()

        assertTrue(gateway.requests.isEmpty())
        scope.cancel()
    }

    @Test
    fun head512CandidateUsesOneHeadOnlyOnUnmeteredWifi() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
            startupCandidate = StartupPreloadCandidate.HEAD_512_WIFI,
        )
        runCurrent()

        manager.setNextVideo(video(9))
        runCurrent()

        assertEquals(listOf(0L), gateway.requests.map(Request::offset))
        assertEquals(listOf(512L * 1024L), gateway.requests.map(Request::length))
        assertEquals(1, gateway.activeLeases)
        manager.stop()
        runCurrent()
        assertEquals(0, gateway.activeLeases)
        scope.cancel()
    }

    @Test
    fun mobileDefaultOffProducesZeroRequestsForExperimentalCandidates() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(offDecision(AdaptivePreloadReason.NETWORK_NOT_ALLOWED)),
            scope,
            startupCandidate = StartupPreloadCandidate.HEAD_512_WIFI,
        )
        runCurrent()

        manager.setNextVideo(video(9))
        runCurrent()

        assertTrue(gateway.requests.isEmpty())
        assertEquals(0, gateway.activeLeases)
        scope.cancel()
    }

    @Test
    fun tailCandidateUsesSequentialRangesForOnlyOneTargetAndTargetChangeCancelsBoth() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
            startupCandidate = StartupPreloadCandidate.TAIL_64,
        )
        runCurrent()

        manager.setNextVideo(video(1))
        runCurrent()

        assertEquals(listOf(0L, 1_000_000L - 64L * 1024L), gateway.requests.map(Request::offset))
        assertEquals(listOf(256L * 1024L, 64L * 1024L), gateway.requests.map(Request::length))
        assertEquals(setOf(1), gateway.activeFileIds())
        assertEquals(2, gateway.activeLeases)

        manager.setNextVideo(video(2))
        runCurrent()

        assertEquals(setOf(2), gateway.activeFileIds())
        assertEquals(2, gateway.activeLeases)
        assertEquals(listOf(1, 1), gateway.closedFileIds.take(2))
        manager.stop()
        runCurrent()
        assertEquals(0, gateway.activeLeases)
        scope.cancel()
    }

    @Test
    fun cachedTailIsNotRequestedAgain() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway().apply {
            snapshots[9] = snapshot(
                downloadOffset = 1_000_000L - 128L * 1024L,
                downloadedPrefixSize = 128L * 1024L,
            )
        }
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
            startupCandidate = StartupPreloadCandidate.TAIL_128,
        )
        runCurrent()

        manager.setNextVideo(video(9))
        runCurrent()

        assertEquals(1, gateway.requests.size)
        assertEquals(0L, gateway.requests.single().offset)
        scope.cancel()
    }

    @Test
    fun failedTailClosesOnlyTailAndKeepsSafeHeadUntilStop() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val tailOffset = 1_000_000L - 64L * 1024L
        val gateway = FakeGateway().apply { failAwaitOffsets += tailOffset }
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
            startupCandidate = StartupPreloadCandidate.TAIL_64,
        )
        runCurrent()

        manager.setNextVideo(video(9))
        runCurrent()

        assertEquals(2, gateway.requests.size)
        assertEquals(listOf(tailOffset), gateway.closedRequests.map(Request::offset))
        assertEquals(1, gateway.activeLeases)
        assertEquals(true, manager.ownerHandoff.value.hasSpeculativeOwner)

        manager.stop()
        runCurrent()
        assertEquals(0, gateway.activeLeases)
        scope.cancel()
    }

    @Test
    fun matchingTargetKeepsNextOwnerUntilCurrentRangeAcquireSucceeds() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
        )
        val target = video(9)
        runCurrent()

        manager.setNextVideo(target)
        manager.beginTargetPromotion()
        manager.commitTargetPromotion(target)

        assertTrue(gateway.closedFileIds.isEmpty())
        assertEquals(
            com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase.TARGET_COMMITTED,
            manager.ownerHandoff.value.phase,
        )

        manager.onCurrentPlaybackStarting(target)
        assertTrue(gateway.closedFileIds.isEmpty())

        manager.onCurrentPlaybackRangeAcquired(target)
        assertEquals(listOf(9), gateway.closedFileIds)
        assertEquals(
            com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase.SHARED_WITH_CURRENT,
            manager.ownerHandoff.value.phase,
        )
        assertEquals(false, manager.ownerHandoff.value.cancelledBeforeCurrentAcquire)
        scope.cancel()
    }

    @Test
    fun differentPlaybackFileForTheSameVideoRejectsPromotionAndCancelsTheOldOwner() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
        )
        val warmed = video(9)
        val changedQuality = warmed.copy(
            selectedAlternative = VideoPlaybackVariant(
                fileId = 109,
                remoteUniqueId = "alternative-109",
                fileSize = 128L * 1024L,
                width = 640,
                height = 360,
                codec = "h264",
            ),
        )
        runCurrent()

        manager.setNextVideo(warmed)
        manager.beginTargetPromotion()
        manager.commitTargetPromotion(changedQuality)

        assertEquals(listOf(9), gateway.closedFileIds)
        assertEquals(
            com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase.ABANDONED,
            manager.ownerHandoff.value.phase,
        )
        assertEquals(false, manager.ownerHandoff.value.promotionMatched)
        scope.cancel()
    }

    @Test
    fun changedTargetInvalidatesTheOldGenerationAndLateAcquireCannotRestoreIt() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
        )
        val warmed = video(2)
        val finalTarget = video(3)

        manager.setNextVideo(warmed)
        manager.beginTargetPromotion()
        manager.commitTargetPromotion(warmed)
        val committedGeneration = manager.ownerHandoff.value.generation
        manager.commitTargetPromotion(finalTarget)
        val abandoned = manager.ownerHandoff.value

        assertEquals(listOf(2), gateway.closedFileIds)
        assertEquals(
            com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase.ABANDONED,
            abandoned.phase,
        )
        assertTrue(abandoned.generation > committedGeneration)
        assertEquals(false, abandoned.hasSpeculativeOwner)

        manager.onCurrentPlaybackRangeAcquired(warmed)
        assertEquals(abandoned, manager.ownerHandoff.value)

        manager.setNextVideo(finalTarget)
        assertEquals(3, manager.ownerHandoff.value.fileId)
        assertEquals(1, gateway.activeLeases)
        assertEquals(1, gateway.maxActiveLeases)
        scope.cancel()
    }

    @Test
    fun currentNotStablePolicyRetainsCommittedOwnerButHardPolicyStillCancelsIt() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val policy = FakeAdaptivePolicy(normalDecision())
        val manager = VideoPreloadManager(gateway, policy, scope)
        val target = video(9)
        runCurrent()

        manager.setNextVideo(target)
        manager.beginTargetPromotion()
        manager.commitTargetPromotion(target)
        policy.mutable.value = offDecision(AdaptivePreloadReason.CURRENT_NOT_STABLE)
        runCurrent()

        assertTrue(gateway.closedFileIds.isEmpty())
        assertEquals(
            com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase.TARGET_COMMITTED,
            manager.ownerHandoff.value.phase,
        )

        policy.mutable.value = offDecision(AdaptivePreloadReason.NETWORK_CHANGED)
        runCurrent()
        assertEquals(listOf(9), gateway.closedFileIds)
        assertEquals(
            com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase.CANCELLED,
            manager.ownerHandoff.value.phase,
        )
        scope.cancel()
    }

    @Test
    fun failedCurrentAcquireAndFullStopReleaseTheCommittedOwner() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway,
            FakeAdaptivePolicy(normalDecision()),
            scope,
        )
        val first = video(8)
        manager.setNextVideo(first)
        manager.beginTargetPromotion()
        manager.commitTargetPromotion(first)

        manager.onCurrentPlaybackRangeAcquireFailed(first)

        assertEquals(listOf(8), gateway.closedFileIds)
        assertEquals(
            com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase.CANCELLED,
            manager.ownerHandoff.value.phase,
        )
        assertEquals(true, manager.ownerHandoff.value.cancelledBeforeCurrentAcquire)

        val second = video(10)
        manager.setNextVideo(second)
        manager.beginTargetPromotion()
        manager.commitTargetPromotion(second)
        manager.stop()

        assertEquals(listOf(8, 10), gateway.closedFileIds)
        assertEquals(
            com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase.RELEASED,
            manager.ownerHandoff.value.phase,
        )
        scope.cancel()
    }

    @Test
    fun startupCacheHitRequiresAContiguousPrefixFromZero() {
        assertTrue(
            hasStartupCacheHit(
                snapshot = snapshot(downloadOffset = 0L, downloadedPrefixSize = 65_536L),
                fileSize = 1_000_000L,
            ),
        )
        assertTrue(
            !hasStartupCacheHit(
                snapshot = snapshot(downloadOffset = 65_536L, downloadedPrefixSize = 65_536L),
                fileSize = 1_000_000L,
            ),
        )
        assertTrue(
            hasStartupCacheHit(
                snapshot = snapshot(
                    downloadOffset = 0L,
                    downloadedPrefixSize = 20_000L,
                    isCompleted = true,
                    size = 20_000L,
                ),
                fileSize = 20_000L,
            ),
        )
    }

    @Test
    fun startupSafetyWithoutAFirstFrameStillIssuesOneBoundedPrefixForTheNextTarget() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway = gateway,
            adaptivePolicy = FakeAdaptivePolicy(conservativeDecision()),
            scope = scope,
            dynamicNextPreloadEnabled = true,
            initialSafety = safe(0.0).copy(
                playbackState = PlaybackRiskState.STARTUP,
                isMobileNetwork = true,
                isMetered = true,
                mobileDataPreloadEnabled = true,
            ),
        )

        manager.setNextVideo(video(50))
        advanceUntilIdle()

        assertEquals(listOf(50), gateway.requests.map(Request::fileId))
        assertEquals(
            listOf(AdaptivePreloadPolicyStateMachine.CONSERVATIVE_PRELOAD_BYTES),
            gateway.requests.map(Request::length),
        )
        assertEquals(
            NextPreloadBudgetTier.CONSERVATIVE_STARTUP,
            manager.currentBudgetDecision()?.allowedBudgetTier,
        )
        assertEquals(
            NextPreloadBudgetController.MIN_PROGRESSIVE_BYTES,
            manager.currentBudgetDecision()?.requestedUncachedBytes,
        )
        scope.cancel()
    }

    @Test
    fun loadingSnapshotsForTheCurrentItemDoNotCancelADifferentNextTarget() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway = gateway,
            adaptivePolicy = FakeAdaptivePolicy(conservativeDecision()),
            scope = scope,
            ownerPromotionEnabled = false,
            dynamicNextPreloadEnabled = true,
            initialSafety = safe(0.0).copy(
                playbackState = PlaybackRiskState.STARTUP,
                isMobileNetwork = true,
                isMetered = true,
                mobileDataPreloadEnabled = true,
            ),
        )
        val current = video(60)
        val next = video(61)

        manager.setNextVideo(next)
        advanceUntilIdle()
        val requestsBefore = gateway.requests.size

        repeat(4) { manager.onCurrentPlaybackStarting(current) }
        advanceUntilIdle()

        assertEquals(requestsBefore, gateway.requests.size)
        assertTrue("a different next target must keep its speculative owner", gateway.closedFileIds.isEmpty())
        assertEquals(next.key, manager.ownerHandoff.value.key)
        scope.cancel()
    }

    @Test
    fun theItemThatBecomesCurrentStillReleasesItsOwnSpeculativeOwner() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway = gateway,
            adaptivePolicy = FakeAdaptivePolicy(conservativeDecision()),
            scope = scope,
            ownerPromotionEnabled = false,
            dynamicNextPreloadEnabled = true,
            initialSafety = safe(0.0).copy(
                playbackState = PlaybackRiskState.STARTUP,
                isMobileNetwork = true,
                isMetered = true,
                mobileDataPreloadEnabled = true,
            ),
        )
        val promoted = video(62)

        manager.setNextVideo(promoted)
        advanceUntilIdle()
        manager.onCurrentPlaybackStarting(promoted)
        advanceUntilIdle()

        assertEquals(listOf(62), gateway.closedFileIds)
        assertEquals(0, gateway.activeLeases)
        // Cancelling the speculative owner must not refund the target's lifetime accounting: the
        // pool that takes this target over inherits exactly these bytes.
        assertEquals(
            NextPreloadBudgetController.MIN_PROGRESSIVE_BYTES,
            manager.requestedBytesFor(promoted),
        )
        scope.cancel()
    }

    @Test
    fun startupBytePrefixStillHonoursEveryHardBlock() = runTest {
        listOf(
            "metered opt-out" to safe(0.0).copy(
                playbackState = PlaybackRiskState.STARTUP,
                isMobileNetwork = true,
                isMetered = true,
                mobileDataPreloadEnabled = false,
            ),
            "rebuffer" to safe(0.0).copy(playbackState = PlaybackRiskState.REBUFFER),
            "seek" to safe(0.0).copy(playbackState = PlaybackRiskState.SEEK),
            "memory pressure" to safe(0.0).copy(
                playbackState = PlaybackRiskState.STARTUP,
                hasMemoryPressure = true,
            ),
        ).forEach { (label, safety) ->
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val gateway = FakeGateway()
            val manager = VideoPreloadManager(
                gateway = gateway,
                adaptivePolicy = FakeAdaptivePolicy(conservativeDecision()),
                scope = scope,
                dynamicNextPreloadEnabled = true,
                initialSafety = safety,
            )

            manager.setNextVideo(video(63))
            advanceUntilIdle()

            assertTrue("$label must not issue a next-payload request", gateway.requests.isEmpty())
            scope.cancel()
        }
    }

    @Test
    fun offlinePolicyBlocksTheStartupPrefixEvenWithMobilePreloadOptedIn() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val policy = FakeAdaptivePolicy(
            conservativeDecision().copy(
                state = AdaptivePreloadState.OFF,
                reason = AdaptivePreloadReason.OFFLINE,
                maxPreloadBytes = 0L,
                mobileDataPreloadEnabled = true,
            ),
        )
        val manager = VideoPreloadManager(
            gateway = gateway,
            adaptivePolicy = policy,
            scope = scope,
            dynamicNextPreloadEnabled = true,
            initialSafety = safe(0.0).copy(
                playbackState = PlaybackRiskState.STARTUP,
                isMobileNetwork = true,
                isMetered = true,
                mobileDataPreloadEnabled = true,
            ),
        )

        manager.setNextVideo(video(70))
        advanceUntilIdle()

        assertTrue("an offline policy must issue nothing", gateway.requests.isEmpty())
        assertEquals(0, gateway.activeLeases)
        assertEquals(NextPreloadBudgetTier.BLOCKED, manager.currentBudgetDecision()?.allowedBudgetTier)
        assertEquals(
            NextPreloadStopReason.ADAPTIVE_HARD_BLOCK,
            manager.currentBudgetDecision()?.preloadStopReason,
        )
        scope.cancel()
    }

    @Test
    fun policyTurningHardBlockedMidFlightRevokesAnAlreadyIssuedPrefix() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val policy = FakeAdaptivePolicy(normalDecision())
        val manager = VideoPreloadManager(
            gateway = gateway,
            adaptivePolicy = policy,
            scope = scope,
            dynamicNextPreloadEnabled = true,
            initialSafety = safe(20.0).copy(
                playbackState = PlaybackRiskState.STARTUP,
                isMobileNetwork = true,
                isMetered = true,
                mobileDataPreloadEnabled = true,
            ),
        )

        manager.setNextVideo(video(71))
        advanceUntilIdle()
        val issued = gateway.requests.sumOf(Request::length)
        assertTrue("the allowed startup stage must issue its bounded prefix", issued > 0L)

        policy.mutable.value = offDecision(AdaptivePreloadReason.NETWORK_CHANGED)
        advanceUntilIdle()

        assertEquals(0, gateway.activeLeases)
        assertTrue(gateway.closedFileIds.isNotEmpty())
        assertEquals(NextPreloadBudgetTier.BLOCKED, manager.currentBudgetDecision()?.allowedBudgetTier)

        // A later re-registration of the same target must stay blocked while the hard block lasts.
        manager.setNextVideo(video(71))
        advanceUntilIdle()
        assertEquals(issued, gateway.requests.sumOf(Request::length))
        scope.cancel()
    }

    @Test
    fun lightweightStartupStageNeverRequestsAnHlsManifest() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway = gateway,
            adaptivePolicy = FakeAdaptivePolicy(conservativeDecision()),
            scope = scope,
            dynamicNextPreloadEnabled = true,
            initialSafety = safe(0.0).copy(
                playbackState = PlaybackRiskState.STARTUP,
                isMobileNetwork = true,
                isMetered = true,
                mobileDataPreloadEnabled = true,
            ),
        )
        val manifestFileId = 801
        val hlsTarget = video(80).copy(
            alternativeVariants = listOf(
                VideoPlaybackVariant(
                    fileId = 800,
                    remoteUniqueId = "alternative-800",
                    fileSize = 128L * 1024L,
                    width = 640,
                    height = 360,
                    codec = "h264",
                    hlsManifestFile = TelegramMediaFileReference(
                        fileId = manifestFileId,
                        remoteUniqueId = "manifest-800",
                        fileSize = 200L * 1024L,
                    ),
                ),
            ),
        )

        manager.setNextVideo(hlsTarget)
        advanceUntilIdle()

        // The whole lightweight stage is one progressive prefix: no manifest range, and therefore
        // no uncharged request stacked on top of the flat ceiling and no fallback after failure.
        assertTrue(
            "no manifest range may be requested during the startup stage",
            gateway.requests.none { it.fileId == manifestFileId },
        )
        assertEquals(listOf(80), gateway.requests.map(Request::fileId))
        assertTrue(gateway.requests.all { it.length <= NextPreloadBudgetController.MIN_PROGRESSIVE_BYTES })
        scope.cancel()
    }

    @Test
    fun requestedBytesAreHandedOverPerExactTarget() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gateway = FakeGateway()
        val manager = VideoPreloadManager(
            gateway = gateway,
            adaptivePolicy = FakeAdaptivePolicy(conservativeDecision()),
            scope = scope,
            dynamicNextPreloadEnabled = true,
            initialSafety = safe(0.0).copy(
                playbackState = PlaybackRiskState.STARTUP,
                isMobileNetwork = true,
                isMetered = true,
                mobileDataPreloadEnabled = true,
            ),
        )
        val target = video(90)

        assertEquals(0L, manager.requestedBytesFor(target))
        manager.setNextVideo(target)
        advanceUntilIdle()

        assertEquals(
            NextPreloadBudgetController.MIN_PROGRESSIVE_BYTES,
            manager.requestedBytesFor(target),
        )
        // A different item must not inherit the ledger of its predecessor.
        assertEquals(0L, manager.requestedBytesFor(video(91)))
        scope.cancel()
    }

    private fun video(fileId: Int) = IndexedVideo(
        key = VideoKey(chatId = 1L, messageId = fileId.toLong()),
        fileId = fileId,
        remoteUniqueId = "remote-$fileId",
        caption = "",
        supportsStreaming = true,
        fileSize = 1_000_000L,
        durationSeconds = 10,
        width = 720,
        height = 1280,
        publishTime = fileId.toLong(),
        editTime = null,
        canBeSaved = true,
        tags = emptyList(),
    )

    private data class Request(
        val fileId: Int,
        val offset: Long,
        val length: Long,
        val priority: TelegramFileRequestPriority,
        val ownerKind: TelegramFileOwnerKind,
    )

    private class FakeGateway : TelegramFileGateway {
        val requests = CopyOnWriteArrayList<Request>()
        val closedRequests = CopyOnWriteArrayList<Request>()
        val closedFileIds = CopyOnWriteArrayList<Int>()
        val snapshots = mutableMapOf<Int, TelegramFileSnapshot>()
        val failAwaitOffsets = mutableSetOf<Long>()
        var activeLeases = 0
        var maxActiveLeases = 0

        override fun acquireRange(
            fileId: Int,
            offset: Long,
            length: Long,
            priority: TelegramFileRequestPriority,
            ownerToken: String,
            ownerKind: TelegramFileOwnerKind,
            readAheadBytes: Long,
        ): TelegramFileRangeLease {
            val request = Request(fileId, offset, length, priority, ownerKind)
            requests += request
            activeLeases += 1
            maxActiveLeases = maxOf(maxActiveLeases, activeLeases)
            return object : TelegramFileRangeLease {
                override val fileId: Int = fileId
                override val offset: Long = offset
                override val length: Long = length
                private var closed = false

                override fun awaitAvailable(timeoutMillis: Long): TelegramFileSnapshot {
                    if (offset in failAwaitOffsets) error("synthetic range failure")
                    return TelegramFileSnapshot(
                        fileId = fileId,
                        size = length,
                        expectedSize = length,
                        localPath = "private",
                        canBeDownloaded = true,
                        isDownloadingActive = false,
                        isDownloadingCompleted = false,
                        downloadOffset = offset,
                        downloadedPrefixSize = length,
                        downloadedSize = length,
                    )
                }

                override fun updatePriority(priority: TelegramFileRequestPriority) = Unit

                override fun close() {
                    if (closed) return
                    closed = true
                    activeLeases -= 1
                    closedRequests += request
                    closedFileIds += fileId
                }
            }
        }

        override fun pinFile(
            fileId: Int,
            ownerToken: String,
            ownerKind: TelegramFileOwnerKind,
        ): TelegramFileProtectionLease = error("not used")

        override fun observeFile(fileId: Int): Flow<TelegramFileSnapshot> = emptyFlow()
        override fun currentSnapshot(fileId: Int): TelegramFileSnapshot? = snapshots[fileId]
        override fun protectedFileIds(): Set<Int> = emptySet()
        override suspend fun deleteCachedFile(fileId: Int): TelegramFileDeleteResult =
            TelegramFileDeleteResult.DELETED
        override fun release(ownerToken: String) = Unit

        fun activeFileIds(): Set<Int> {
            val closedCounts = closedRequests.groupingBy(Request::fileId).eachCount().toMutableMap()
            return requests.mapNotNull { request ->
                val remaining = closedCounts[request.fileId] ?: 0
                if (remaining > 0) {
                    closedCounts[request.fileId] = remaining - 1
                    null
                } else {
                    request.fileId
                }
            }.toSet()
        }
    }

    private fun snapshot(
        downloadOffset: Long,
        downloadedPrefixSize: Long,
        isCompleted: Boolean = false,
        size: Long = 1_000_000L,
    ) = TelegramFileSnapshot(
        fileId = 1,
        size = size,
        expectedSize = size,
        localPath = "private",
        canBeDownloaded = true,
        isDownloadingActive = false,
        isDownloadingCompleted = isCompleted,
        downloadOffset = downloadOffset,
        downloadedPrefixSize = downloadedPrefixSize,
        downloadedSize = downloadedPrefixSize,
    )

    private fun safe(bufferSeconds: Double) = NextPreloadSafetySnapshot(
        playbackState = PlaybackRiskState.PLAYING,
        currentBufferedSeconds = bufferSeconds,
        bufferSlopeSecondsPerSecond = 0.1,
        fastThroughputBitsPerSecond = 12_000_000L,
        slowThroughputBitsPerSecond = 11_000_000L,
        timeToFirstByteP90Millis = 120L,
        isMetered = false,
        isMobileNetwork = false,
    )

    private class FakeAdaptivePolicy(initial: AdaptivePreloadDecision) :
        AdaptivePreloadController {
        val mutable = MutableStateFlow(initial)
        override val decision: StateFlow<AdaptivePreloadDecision> = mutable
        override fun onCurrentBind(cacheHit: Boolean) = Unit
        override fun onFirstFrame(bindToFirstFrameMillis: Long) = Unit
        override fun onPlaybackFailure() = Unit
        override fun onRebufferStarted() = Unit
        override fun onRebufferRecovered() = Unit
        override fun onCurrentReleased() = Unit
    }

    private fun normalDecision() = AdaptivePreloadDecision(
        state = AdaptivePreloadState.NORMAL,
        reason = AdaptivePreloadReason.STABLE,
        maxPreloadBytes = AdaptivePreloadPolicyStateMachine.NORMAL_PRELOAD_BYTES,
        recentSampleCount = 5,
        recentP90Millis = 300L,
        isUnmeteredWifi = true,
    )

    private fun conservativeDecision() = AdaptivePreloadDecision(
        state = AdaptivePreloadState.CONSERVATIVE,
        reason = AdaptivePreloadReason.RECOVERING,
        maxPreloadBytes = AdaptivePreloadPolicyStateMachine.CONSERVATIVE_PRELOAD_BYTES,
        recentSampleCount = 1,
        recentP90Millis = 300L,
    )

    private fun offDecision(
        reason: AdaptivePreloadReason = AdaptivePreloadReason.STORAGE_LOW,
    ) = AdaptivePreloadDecision(
        state = AdaptivePreloadState.OFF,
        reason = reason,
        maxPreloadBytes = 0L,
        recentSampleCount = 0,
        recentP90Millis = null,
    )
}
