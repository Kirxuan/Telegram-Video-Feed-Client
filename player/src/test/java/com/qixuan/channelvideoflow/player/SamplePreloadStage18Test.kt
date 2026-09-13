package com.qixuan.channelvideoflow.player

import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetController
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetInput
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetTier
import com.qixuan.channelvideoflow.domain.media.NextPreloadSafetySnapshot
import com.qixuan.channelvideoflow.domain.media.PlaybackRiskState
import com.qixuan.channelvideoflow.domain.media.TelegramFileDeleteResult
import com.qixuan.channelvideoflow.domain.media.TelegramFileGateway
import com.qixuan.channelvideoflow.domain.media.TelegramFileOwnerKind
import com.qixuan.channelvideoflow.domain.media.TelegramFileProtectionLease
import com.qixuan.channelvideoflow.domain.media.TelegramFileRangeLease
import com.qixuan.channelvideoflow.domain.media.TelegramFileRequestPriority
import com.qixuan.channelvideoflow.domain.media.TelegramFileSnapshot
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplePreloadStage18Test {
    @Test
    fun metadataOnlyTierAdmitsTrackPreparationWithoutAdmittingBlockedPayload() {
        fun decision(bufferedSeconds: Double) = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(
                safety = NextPreloadSafetySnapshot(
                    playbackState = PlaybackRiskState.PLAYING,
                    currentBufferedSeconds = bufferedSeconds,
                    bufferSlopeSecondsPerSecond = 0.1,
                    isMetered = false,
                ),
                peakBitrateBitsPerSecond = 1_000_000L,
                cachedCoveredBytes = 0L,
                requestedUncachedBytes = 0L,
            ),
        )

        val metadata = decision(12.0)
        val blocked = decision(2.5)
        assertEquals(NextPreloadBudgetTier.METADATA_ONLY, metadata.allowedBudgetTier)
        assertEquals(0L, metadata.calculatedTargetBytes)
        assertTrue(metadata.permitsSamplePreload())
        assertEquals(NextPreloadBudgetTier.BLOCKED, blocked.allowedBudgetTier)
        assertFalse(blocked.permitsSamplePreload())
    }

    @Test
    fun screeningRequiresRelativeAndAbsoluteImprovementAndNeverChangesDefault() {
        assertFalse(BuildConfig.SAMPLE_QUEUE_PRELOAD_ENABLED)
        val belowGate = SamplePreloadAbEvaluator.evaluate(
            baselineMillis = List(30) { 300L },
            candidateMillis = List(30) { 258L },
            firstFrameCount = 30,
            transitionCount = 30,
            safetyFailures = 0,
        )
        val pass = SamplePreloadAbEvaluator.evaluate(
            baselineMillis = List(30) { 300L },
            candidateMillis = List(30) { 225L },
            firstFrameCount = 30,
            transitionCount = 30,
            safetyFailures = 0,
        )

        assertFalse(belowGate.screeningPassed)
        assertTrue(pass.screeningPassed)
        assertTrue(pass.improvementFraction >= 0.15)
        assertTrue(pass.sampleCountComplete)
        assertTrue(pass.wastedBytesWithinLimit)
    }

    @Test
    fun missingFirstFrameOrAnyWrongVideoBlackScreenAudioOrCrashFailsTheGate() {
        assertFalse(
            SamplePreloadAbEvaluator.evaluate(
                List(30) { 300L }, List(30) { 100L }, 29, 30, 0,
            ).screeningPassed,
        )
        assertFalse(
            SamplePreloadAbEvaluator.evaluate(
                List(30) { 300L }, List(30) { 100L }, 30, 30, 1,
            ).screeningPassed,
        )
    }

    @Test
    fun insufficientSamplesOrMoreThanTwentyFivePercentWasteFailsTheGate() {
        assertFalse(
            SamplePreloadAbEvaluator.evaluate(
                baselineMillis = List(29) { 300L },
                candidateMillis = List(29) { 100L },
                firstFrameCount = 29,
                transitionCount = 29,
                safetyFailures = 0,
            ).screeningPassed,
        )
        val excessiveWaste = SamplePreloadAbEvaluator.evaluate(
            baselineMillis = List(30) { 300L },
            candidateMillis = List(30) { 100L },
            firstFrameCount = 30,
            transitionCount = 30,
            safetyFailures = 0,
            baselineSkippedNextWastedBytes = List(30) { 1_000L },
            candidateSkippedNextWastedBytes = List(30) { 1_251L },
        )

        assertFalse(excessiveWaste.wastedBytesWithinLimit)
        assertFalse(excessiveWaste.screeningPassed)
    }

    @Test
    fun exactCommittedTargetCanBeConsumedOnceAndWrongOrCancelledTargetCannot() {
        val gate = SamplePreloadHandoffGate()
        val first = video(1)
        val wrong = video(2)
        gate.register(first)
        assertFalse(gate.take(first))
        assertFalse(gate.commit(wrong))
        assertTrue(gate.commit(first))
        assertFalse(gate.take(wrong))
        assertTrue(gate.take(first))
        assertFalse(gate.take(first))

        gate.register(first)
        assertTrue(gate.cancelUnless(wrong))
        assertFalse(gate.commit(first))
    }

    @Test
    fun sampleDataSourceCannotReadPastStage18dTargetOrUseOversizedChunk() {
        val gateway = RecordingGateway()
        val requestSession = PlaybackRangeRequestSession(preloadOnly = true)
        val capped = CappedNextSampleGateway(
            delegate = gateway,
            payloadFileIds = setOf(10, 11),
            allowedPayloadEnd = 1_024L,
            requestSession = requestSession,
        )
        capped.acquireRange(
            10, 512L, 512L, TelegramFileRequestPriority.NEXT_PRELOAD,
            "owner", TelegramFileOwnerKind.NEXT_PRELOAD, 512L,
        ).close()
        assertEquals(1, gateway.requests)
        assertThrows(IllegalArgumentException::class.java) {
            capped.acquireRange(
                11, 1_024L, 1L, TelegramFileRequestPriority.NEXT_PRELOAD,
                "owner", TelegramFileOwnerKind.NEXT_PRELOAD, 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            capped.acquireRange(
                10, 0L, 513L * 1024L, TelegramFileRequestPriority.NEXT_PRELOAD,
                "owner", TelegramFileOwnerKind.NEXT_PRELOAD, 513L * 1024L,
            )
        }

        requestSession.promoteToCurrent()
        capped.acquireRange(
            10, 1_024L, 512L, TelegramFileRequestPriority.CURRENT_STARTUP,
            "current", TelegramFileOwnerKind.CURRENT_PLAYBACK, 512L,
        ).close()
        assertEquals(2, gateway.requests)
    }

    @Test
    fun standbyReadAheadCannotEscapeReservedRangeButCurrentPlaybackCanReadAhead() {
        val gateway = RecordingGateway()
        val session = PlaybackRangeRequestSession(preloadOnly = true)
        val ledger = SampleRequestBudget()
        val capped = CappedNextSampleGateway(gateway, setOf(10), 1_024L, session, ledger)

        capped.acquireRange(
            10, 768L, 256L, TelegramFileRequestPriority.NEXT_PRELOAD,
            "next", TelegramFileOwnerKind.NEXT_PRELOAD, 512L * 1024L,
        ).close()
        assertEquals(256L, gateway.lastReadAheadBytes)
        assertEquals(256L, ledger.reservedBytes)

        session.promoteToCurrent()
        capped.acquireRange(
            10, 1_024L, 256L, TelegramFileRequestPriority.CURRENT_STARTUP,
            "current", TelegramFileOwnerKind.CURRENT_PLAYBACK, 512L * 1024L,
        ).close()
        assertEquals(512L * 1024L, gateway.lastReadAheadBytes)
        assertEquals(256L, ledger.reservedBytes)
    }

    @Test
    fun poolParsesSparseIndexAndManifestWithinOneBudgetThenRestoresForegroundReadAhead() {
        val gateway = RecordingGateway()
        val session = PlaybackRangeRequestSession(preloadOnly = true)
        val ledger = SampleRequestBudget()
        val capped = CappedNextSampleGateway(gateway, setOf(10), 1_024L, session, ledger,
            allowSparsePayloadRanges = true)
        capped.acquireRange(10, 10_000_000L, 512L, TelegramFileRequestPriority.NEXT_PRELOAD,
            "index", TelegramFileOwnerKind.NEXT_PRELOAD, 512L).close()
        capped.acquireRange(99, 0L, 512L, TelegramFileRequestPriority.NEXT_PRELOAD,
            "manifest", TelegramFileOwnerKind.NEXT_PRELOAD, 512L).close()
        assertEquals(1_024L, ledger.reservedBytes)
        assertThrows(IllegalArgumentException::class.java) {
            capped.acquireRange(10, 0L, 1L, TelegramFileRequestPriority.NEXT_PRELOAD,
                "over-budget", TelegramFileOwnerKind.NEXT_PRELOAD, 1L)
        }
        assertEquals(2, gateway.requests)
        session.promoteToCurrent()
        capped.acquireRange(10, 0L, 512L, TelegramFileRequestPriority.CURRENT_STARTUP,
            "active", TelegramFileOwnerKind.CURRENT_PLAYBACK, 4L * 1024 * 1024).close()
        assertEquals(4L * 1024 * 1024, gateway.lastReadAheadBytes)
        assertEquals(1_024L, ledger.reservedBytes)
    }

    private fun video(id: Int) = IndexedVideo(
        key = VideoKey(1L, id.toLong()),
        fileId = id,
        remoteUniqueId = "r$id",
        caption = "",
        supportsStreaming = true,
        fileSize = 1_000L,
        durationSeconds = 1,
        width = 640,
        height = 360,
        publishTime = 0L,
        editTime = null,
        canBeSaved = false,
        tags = emptyList(),
    )

    private class RecordingGateway : TelegramFileGateway {
        var requests = 0
        var lastReadAheadBytes = 0L
        override fun acquireRange(
            fileId: Int,
            offset: Long,
            length: Long,
            priority: TelegramFileRequestPriority,
            ownerToken: String,
            ownerKind: TelegramFileOwnerKind,
            readAheadBytes: Long,
        ): TelegramFileRangeLease {
            requests += 1
            lastReadAheadBytes = readAheadBytes
            return object : TelegramFileRangeLease {
                override val fileId = fileId
                override val offset = offset
                override val length = length
                override fun awaitAvailable(timeoutMillis: Long): TelegramFileSnapshot = error("unused")
                override fun updatePriority(priority: TelegramFileRequestPriority) = Unit
                override fun close() = Unit
            }
        }
        override fun pinFile(fileId: Int, ownerToken: String, ownerKind: TelegramFileOwnerKind) =
            object : TelegramFileProtectionLease {
                override val fileId = fileId
                override val ownerKind = ownerKind
                override fun close() = Unit
            }
        override fun observeFile(fileId: Int): Flow<TelegramFileSnapshot> = emptyFlow()
        override fun currentSnapshot(fileId: Int): TelegramFileSnapshot? = null
        override fun protectedFileIds(): Set<Int> = emptySet()
        override suspend fun deleteCachedFile(fileId: Int) = TelegramFileDeleteResult.DELETED
        override fun release(ownerToken: String) = Unit
    }
}
