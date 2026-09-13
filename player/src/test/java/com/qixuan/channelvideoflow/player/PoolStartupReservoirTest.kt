package com.qixuan.channelvideoflow.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolStartupReservoirTest {
    @Test fun fastStartTiersFollowLowCostThroughputHeadroomAndLatency() {
        fun threshold(size: Long? = 6_000_000, edge: Int = 480, fast: Long? = 2_000_000,
            slow: Long? = 2_000_000, latency: Long? = 200) =
            conditionalStartupMillis(size, 60, edge, fast, slow, latency)
        // 800 kbps target with 2.5x headroom on a low cost representation: fastest legal tier.
        org.junit.Assert.assertEquals(800L, threshold())
        org.junit.Assert.assertEquals(800L, threshold(slow = 1_600_000))
        // Same representation, thinner headroom or a slower first byte: one tier up.
        org.junit.Assert.assertEquals(1_200L, threshold(slow = 1_300_000))
        org.junit.Assert.assertEquals(1_200L, threshold(latency = 500))
        // A high resolution original only gets the moderate tier, never the aggressive one.
        org.junit.Assert.assertEquals(1_800L, threshold(edge = 720))
        org.junit.Assert.assertEquals(1_800L, threshold(edge = 1080))
        // Anything unknown or genuinely weak keeps the conservative baseline.
        org.junit.Assert.assertEquals(2_500L, threshold(slow = 1_000_000))
        org.junit.Assert.assertEquals(2_500L, threshold(slow = 1_300_000, latency = 900))
        org.junit.Assert.assertEquals(2_500L, threshold(size = null))
        org.junit.Assert.assertEquals(2_500L, threshold(fast = null))
        org.junit.Assert.assertEquals(2_500L, threshold(latency = null))
        org.junit.Assert.assertEquals(2_500L, threshold(size = Long.MAX_VALUE))
        assertFalse(hasPoolStartupReservoir(0, 799, 60_000, threshold()))
        assertTrue(hasPoolStartupReservoir(0, 800, 60_000, threshold()))
    }

    @Test fun aFullMemoryBudgetAllowsBoundedPlaybackButNeverAnEmptyReservoir() {
        assertTrue(hasPoolStartupReservoir(0, 500, 60_000, 2_500, sampleMemoryCeilingReached = true))
        assertFalse(hasPoolStartupReservoir(0, 0, 60_000, 2_500, sampleMemoryCeilingReached = true))
        assertFalse(hasPoolStartupReservoir(100, 50, 60_000, 2_500, sampleMemoryCeilingReached = true))
    }

    @Test fun decoderReadyHalfSecondDoesNotAuthorizeForegroundPlayback() {
        assertFalse(hasPoolStartupReservoir(0, 500, 60_000, 2_500))
        assertFalse(hasPoolStartupReservoir(0, 2_499, 60_000, 2_500))
        assertTrue(hasPoolStartupReservoir(0, 2_500, 60_000, 2_500))
    }

    @Test fun completelyBufferedShortClipDoesNotWaitForAnImpossibleThreshold() {
        assertTrue(hasPoolStartupReservoir(0, 900, 900, 2_500))
        assertFalse(hasPoolStartupReservoir(0, 899, 900, 2_500))
    }

    @Test fun unknownDurationAndBytesBehindThePlayheadCannotBypassTheGate() {
        assertFalse(hasPoolStartupReservoir(0, 0, 0, 2_500))
        assertFalse(hasPoolStartupReservoir(4_000, 4_500, 60_000, 2_500))
        assertFalse(hasPoolStartupReservoir(4_000, 3_000, 0, 2_500))
        assertTrue(hasPoolStartupReservoir(4_000, 6_500, 0, 2_500))
    }

    @Test fun startupThresholdDecaysWhileNoFirstFrameIsRendered() {
        // No decay inside the grace window; the configured threshold applies unchanged.
        org.junit.Assert.assertEquals(2_500L, relaxedStartupMillis(2_500, 0))
        org.junit.Assert.assertEquals(2_500L, relaxedStartupMillis(2_500, 2_000))
        // Decay is linear at 500 ms per second of waiting past the grace window.
        org.junit.Assert.assertEquals(2_000L, relaxedStartupMillis(2_500, 3_000))
        org.junit.Assert.assertEquals(1_500L, relaxedStartupMillis(2_500, 4_000))
        // The floor bounds the worst case and is never crossed.
        org.junit.Assert.assertEquals(1_000L, relaxedStartupMillis(2_500, 5_000))
        org.junit.Assert.assertEquals(1_000L, relaxedStartupMillis(2_500, 60_000))
        // Thresholds already at or below the floor are left untouched, never raised.
        org.junit.Assert.assertEquals(800L, relaxedStartupMillis(800, 60_000))
        org.junit.Assert.assertEquals(1_000L, relaxedStartupMillis(1_000, 60_000))
        org.junit.Assert.assertEquals(1_200L, relaxedStartupMillis(1_200, 2_500))
        org.junit.Assert.assertEquals(1_000L, relaxedStartupMillis(1_200, 5_000))
    }
}
