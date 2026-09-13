package com.qixuan.channelvideoflow.player

import com.qixuan.channelvideoflow.domain.media.*
import com.qixuan.channelvideoflow.model.video.VideoQualityPreference
import org.junit.Assert.*
import org.junit.Test

class MobilePoolAdmissionTest {
    @Test
    fun mobileOptInReachesBudgetAndPoolInsteadOfBeingLostBetweenLayers() {
        val policy = AdaptivePreloadPolicyStateMachine(
            AdaptivePreloadEnvironment(
                DevicePreloadSignals(network = NetworkTransport.MOBILE, isMetered = true),
                mobileDataEnabled = true,
                qualityPreference = VideoQualityPreference.AUTO,
            ),
        )
        policy.onCurrentBind(cacheHit = false)
        val adaptive = policy.onFirstFrame(3_000L)
        val budget = NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(
                NextPreloadSafetySnapshot(
                    playbackState = PlaybackRiskState.PLAYING,
                    currentBufferedSeconds = 20.0,
                    isMobileNetwork = true,
                    isMetered = true,
                    mobileDataPreloadEnabled = adaptive.mobileDataPreloadEnabled,
                ),
                peakBitrateBitsPerSecond = 16_000_000L,
                cachedCoveredBytes = 0L,
                requestedUncachedBytes = 0L,
            ),
        )
        assertTrue("Mobile choice must survive the policy boundary", adaptive.mobileDataPreloadEnabled)
        assertTrue("An opted-in mobile next item must receive a bounded budget", budget.calculatedTargetBytes > 0)
        assertEquals(5.0, budget.calculatedTargetSeconds, 0.0)
        assertEquals(12_500_000L, budget.calculatedTargetBytes)
        assertTrue(budget.calculatedTargetBytes <= 20L * 1024 * 1024)
        assertNull(PlaybackPoolSafety(
            currentReservoirSafe = budget.calculatedTargetBytes > 0,
            isMetered = true,
            mobilePreloadEnabled = adaptive.mobileDataPreloadEnabled,
        ).blockingReason())
    }
}
