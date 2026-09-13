package com.qixuan.channelvideoflow.domain.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextPreloadBudgetControllerTest {
    @Test fun mobileFiveSecondsScalesWithBitrateAndNeverExceedsTwentyMib() {
        val safety = safe(20.0).copy(isMobileNetwork = true, isMetered = true, mobileDataPreloadEnabled = true)
        fun budget(bitrate: Long, consumed: Long = 0) = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(safety, bitrate, 0, consumed),
        )
        assertEquals(5.0, budget(800_000).calculatedTargetSeconds, 0.0)
        assertEquals(625_000L, budget(800_000).calculatedTargetBytes)
        assertEquals(12_500_000L, budget(16_000_000).calculatedTargetBytes)
        assertEquals(20L * MIB, budget(100_000_000).calculatedTargetBytes)
        assertEquals(0L, budget(100_000_000, 20L * MIB).remainingNewNetworkBudgetBytes)
        assertEquals(NextPreloadBudgetTier.TWENTY_MIB, budget(800_000).allowedBudgetTier)
    }

    @Test
    fun bufferReservoirProducesBlockedTwoFiveAndTwentyMibTiers() {
        val zero = evaluate(buffer = 2.9, bitrate = 8_000_000L)
        val two = evaluate(buffer = 20.0, bitrate = 8_000_000L)
        val five = evaluate(buffer = 30.0, bitrate = 8_000_000L)
        val twenty = evaluate(buffer = 40.0, bitrate = 8_000_000L)

        assertEquals(NextPreloadBudgetTier.BLOCKED, zero.allowedBudgetTier)
        assertEquals(0L, zero.calculatedTargetBytes)
        // The tier is a ceiling, not a promise: five seconds of 8 Mbps is 6.25 MB, which the lower
        // tiers clamp and the top tier does not.
        assertEquals(NextPreloadBudgetTier.TWO_MIB, two.allowedBudgetTier)
        assertEquals(2L * MIB, two.calculatedTargetBytes)
        assertEquals(NextPreloadBudgetTier.FIVE_MIB, five.allowedBudgetTier)
        assertEquals(5L * MIB, five.calculatedTargetBytes)
        assertEquals(NextPreloadBudgetTier.TWENTY_MIB, twenty.allowedBudgetTier)
        assertEquals(6_250_000L, twenty.calculatedTargetBytes)
    }

    @Test
    fun mobileMeteredStartupSeekAndRebufferAlwaysHaveZeroMediaBudget() {
        listOf(
            safe(40.0).copy(isMobileNetwork = true),
            safe(40.0).copy(isMetered = true),
            safe(40.0).copy(playbackState = PlaybackRiskState.STARTUP),
            safe(40.0).copy(playbackState = PlaybackRiskState.SEEK),
            safe(40.0).copy(playbackState = PlaybackRiskState.REBUFFER),
        ).forEach { safety ->
            val decision = NextPreloadBudgetController.evaluate(
                NextPreloadBudgetInput(safety, 8_000_000L, 0L, 0L),
            )
            assertEquals(0L, decision.remainingNewNetworkBudgetBytes)
        }
    }

    @Test
    fun fallingLowBufferCancelsWhileAtOrAboveAdmissionFloorStaysMetadataOnly() {
        val falling = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(
                safe(20.0).copy(bufferSlopeSecondsPerSecond = -0.2),
                1_000_000L,
                0L,
                0L,
            ),
        )
        val metadata = evaluate(buffer = 12.0, bitrate = 1_000_000L)

        assertEquals(NextPreloadStopReason.BUFFER_FALLING, falling.preloadStopReason)
        assertEquals(NextPreloadBudgetTier.METADATA_ONLY, metadata.allowedBudgetTier)
        assertEquals(0L, metadata.calculatedTargetBytes)
    }

    @Test
    fun cachedBytesDoNotCountAsNewNetworkAndHardCeilingCanNeverBeExceeded() {
        val target = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(
                safety = safe(40.0),
                peakBitrateBitsPerSecond = 20_000_000L,
                cachedCoveredBytes = 3L * MIB,
                requestedUncachedBytes = 6L * MIB,
            ),
        )

        assertEquals(15_625_000L, target.calculatedTargetBytes)
        assertEquals(15_625_000L - 3L * MIB - 6L * MIB, target.remainingNewNetworkBudgetBytes)
        val exceededInput = target.copy(requestedUncachedBytes = 20L * MIB)
        assertTrue(exceededInput.requestedUncachedBytes <= NextPreloadBudgetController.ABSOLUTE_MAX_BYTES)
    }

    @Test
    fun unknownBitrateStaysAtMinimumInsteadOfJumpingToTenMib() {
        val decision = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(safe(40.0), null, 0L, 0L),
        )

        assertEquals(256L * 1024L, decision.calculatedTargetBytes)
        assertEquals(NextPreloadStopReason.UNRELIABLE_BITRATE, decision.preloadStopReason)
    }

    @Test
    fun hlsRoundsToCompleteSegmentAndRefusesSegmentBeyondCurrentTier() {
        val boundaries = listOf(
            HlsPlayableBoundary(3.0, 1L * MIB),
            HlsPlayableBoundary(6.0, 3L * MIB),
            HlsPlayableBoundary(10.0, 7L * MIB),
        )
        val fiveSecond = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(safe(30.0), 8_000_000L, 0L, 0L, boundaries),
        )
        assertEquals(3L * MIB, fiveSecond.calculatedTargetBytes)

        val tooLargeFirstSegment = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(
                safe(20.0),
                8_000_000L,
                0L,
                0L,
                listOf(HlsPlayableBoundary(3.0, 3L * MIB)),
            ),
        )
        assertEquals(0L, tooLargeFirstSegment.calculatedTargetBytes)
        assertEquals(NextPreloadStopReason.SEGMENT_EXCEEDS_TIER, tooLargeFirstSegment.preloadStopReason)
    }

    @Test
    fun eachReevaluationExposesAtMostOneBoundedChunk() {
        val decision = evaluate(buffer = 40.0, bitrate = 20_000_000L)
        val chunk = minOf(
            decision.remainingNewNetworkBudgetBytes,
            NextPreloadBudgetController.RANGE_CHUNK_BYTES,
        )
        assertEquals(512L * 1024L, chunk)
    }

    @Test
    fun fullyBufferedShortTailDoesNotLookLikeDownloadStarvation() {
        val decision = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(
                safe(buffer = 1.0).copy(
                    bufferSlopeSecondsPerSecond = -0.8,
                    remainingTimelineBuffered = true,
                ),
                peakBitrateBitsPerSecond = 2_000_000L,
                cachedCoveredBytes = 0L,
                requestedUncachedBytes = 0L,
            ),
        )

        assertEquals(NextPreloadBudgetTier.TWO_MIB, decision.allowedBudgetTier)
        assertEquals(NextPreloadStopReason.NONE, decision.preloadStopReason)
        assertTrue(decision.calculatedTargetBytes > 0L)
    }

    @Test
    fun memoryPressureBlocksSpeculativeBytes() {
        val decision = evaluate(buffer = 40.0, bitrate = 8_000_000L).let {
            NextPreloadBudgetController.evaluate(
                NextPreloadBudgetInput(safe(40.0).copy(hasMemoryPressure = true), 8_000_000L, 0L, 0L),
            )
        }
        assertEquals(NextPreloadBudgetTier.BLOCKED, decision.allowedBudgetTier)
        assertEquals(0L, decision.calculatedTargetBytes)
    }

    @Test
    fun retainedMobilePreparationSurvivesChunkGapsButStopsBeforeCurrentStarves() {
        val retained = safe(5.0).copy(
            isMobileNetwork = true,
            isMetered = true,
            mobileDataPreloadEnabled = true,
            standbyPreparationActive = true,
            bufferSlopeSecondsPerSecond = -1.0,
        )
        fun budget(safety: NextPreloadSafetySnapshot) = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(safety, 2_000_000L, 0L, 0L),
        )
        assertTrue(budget(retained).calculatedTargetBytes > 0L)
        listOf(
            retained.copy(currentBufferedSeconds = 2.9),
            retained.copy(mobileDataPreloadEnabled = false),
            retained.copy(hasMemoryPressure = true),
            retained.copy(playbackState = PlaybackRiskState.REBUFFER),
        ).forEach { assertEquals(0L, budget(it).calculatedTargetBytes) }
    }

    private fun evaluate(buffer: Double, bitrate: Long?) = NextPreloadBudgetController.evaluate(
        NextPreloadBudgetInput(safe(buffer), bitrate, 0L, 0L),
    )

    @Test
    fun lightweightStartupPrefetchGetsOneFlatPrefixWhileEveryHardBlockStaysBlocked() {
        val startup = safe(0.0).copy(playbackState = PlaybackRiskState.STARTUP)
        fun budget(
            safety: NextPreloadSafetySnapshot = startup,
            consumed: Long = 0L,
        ) = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(
                safety = safety,
                peakBitrateBitsPerSecond = 8_000_000L,
                cachedCoveredBytes = 0L,
                requestedUncachedBytes = consumed,
                lightweightStartupPrefetch = true,
            ),
        )

        val allowed = budget()
        assertEquals(NextPreloadBudgetTier.CONSERVATIVE_STARTUP, allowed.allowedBudgetTier)
        assertEquals(256L * 1024L, allowed.calculatedTargetBytes)
        assertEquals(256L * 1024L, allowed.remainingNewNetworkBudgetBytes)
        assertEquals(NextPreloadStopReason.NONE, allowed.preloadStopReason)
        // The flat ceiling is a total, not a per-reevaluation allowance.
        assertEquals(0L, budget(consumed = 256L * 1024L).remainingNewNetworkBudgetBytes)
        // An unknown bitrate must not silently raise or remove the bounded prefix.
        assertEquals(
            256L * 1024L,
            NextPreloadBudgetController.evaluate(
                NextPreloadBudgetInput(startup, null, 0L, 0L, lightweightStartupPrefetch = true),
            ).calculatedTargetBytes,
        )

        listOf(
            "offline-style metered opt-out" to startup.copy(isMetered = true, mobileDataPreloadEnabled = false),
            "mobile opt-out" to startup.copy(isMobileNetwork = true, mobileDataPreloadEnabled = false),
            "power save" to startup.copy(isPowerSaver = true),
            "thermal" to startup.copy(hasThermalPressure = true),
            "storage" to startup.copy(hasStoragePressure = true),
            "memory" to startup.copy(hasMemoryPressure = true),
            "seek" to startup.copy(playbackState = PlaybackRiskState.SEEK),
            "rebuffer" to startup.copy(playbackState = PlaybackRiskState.REBUFFER),
        ).forEach { (label, safety) ->
            val decision = budget(safety)
            assertEquals("$label must stay blocked", 0L, decision.remainingNewNetworkBudgetBytes)
            assertEquals("$label must not reach the byte prefix", 0L, decision.calculatedTargetBytes)
        }

        // A fully buffered *current* timeline is not a hard block: it says nothing about the next
        // item, so the bounded prefix is still the cheapest way to cover the coming swipe.
        assertEquals(
            256L * 1024L,
            budget(startup.copy(remainingTimelineBuffered = true)).calculatedTargetBytes,
        )
    }

    @Test
    fun adaptiveHardBlockOutranksTheLightweightStartupAllowance() {
        // An opted-in metered link passes every metered gate, and the safety snapshot has no way to
        // say "offline" — only the owning policy knows. Its hard block must win over the prefix.
        val optedInStartup = safe(0.0).copy(
            playbackState = PlaybackRiskState.STARTUP,
            isMobileNetwork = true,
            isMetered = true,
            mobileDataPreloadEnabled = true,
        )
        fun budget(safety: NextPreloadSafetySnapshot, hardBlocked: Boolean) =
            NextPreloadBudgetController.evaluate(
                NextPreloadBudgetInput(
                    safety = safety,
                    peakBitrateBitsPerSecond = 8_000_000L,
                    cachedCoveredBytes = 0L,
                    requestedUncachedBytes = 0L,
                    lightweightStartupPrefetch = true,
                    hasAdaptiveHardBlock = hardBlocked,
                ),
            )

        assertEquals(256L * 1024L, budget(optedInStartup, hardBlocked = false).calculatedTargetBytes)

        val blocked = budget(optedInStartup, hardBlocked = true)
        assertEquals(NextPreloadBudgetTier.BLOCKED, blocked.allowedBudgetTier)
        assertEquals(0L, blocked.calculatedTargetBytes)
        assertEquals(0L, blocked.remainingNewNetworkBudgetBytes)
        assertEquals(NextPreloadStopReason.ADAPTIVE_HARD_BLOCK, blocked.preloadStopReason)

        // The same hard block also closes the pre-existing playing-state hole, so an opted-in
        // offline device cannot spend bytes on a next target at all.
        val playing = safe(40.0).copy(isMobileNetwork = true, isMetered = true, mobileDataPreloadEnabled = true)
        assertEquals(0L, budget(playing, hardBlocked = true).calculatedTargetBytes)
        assertTrue(budget(playing, hardBlocked = false).calculatedTargetBytes > 0L)
    }

    @Test
    fun heavyStandbyKeepsTheThreeSecondAdmissionAndNeverUsesTheStartupTier() {
        val startup = safe(0.0).copy(playbackState = PlaybackRiskState.STARTUP)
        val heavyStartup = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(startup, 8_000_000L, 0L, 0L),
        )
        assertEquals(NextPreloadBudgetTier.BLOCKED, heavyStartup.allowedBudgetTier)
        assertEquals(0L, heavyStartup.calculatedTargetBytes)

        val barelyBuffered = safe(2.9)
        assertEquals(
            NextPreloadBudgetTier.BLOCKED,
            NextPreloadBudgetController.evaluate(
                NextPreloadBudgetInput(barelyBuffered, 8_000_000L, 0L, 0L),
            ).allowedBudgetTier,
        )
    }

    private fun safe(buffer: Double) = NextPreloadSafetySnapshot(
        playbackState = PlaybackRiskState.PLAYING,
        currentBufferedSeconds = buffer,
        bufferSlopeSecondsPerSecond = 0.1,
        fastThroughputBitsPerSecond = 12_000_000L,
        slowThroughputBitsPerSecond = 11_000_000L,
        timeToFirstByteP90Millis = 120L,
        isMetered = false,
        isMobileNetwork = false,
    )

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
