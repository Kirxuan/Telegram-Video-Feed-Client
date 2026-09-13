package com.qixuan.channelvideoflow.player

import com.qixuan.channelvideoflow.model.video.VideoKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPoolCoordinatorTest {
    @Test
    fun productionCandidateIsC1AndPoolAlwaysHasExactlyTwoSlots() {
        assertEquals(PlaybackPoolCandidate.C1, PlaybackPoolCandidate.fromBuildValue(
            BuildConfig.PLAYBACK_POOL_CANDIDATE,
        ))
        val coordinator = PlaybackPoolCoordinator(PlaybackPoolCandidate.C1)

        assertEquals(2, coordinator.state.slots.size)
        assertEquals(2, coordinator.state.slots.map { it.playerSlot }.distinct().size)
    }

    @Test
    fun c2SurfaceFailureDowngradesToC1WithoutDisablingPool() {
        val coordinator = PlaybackPoolCoordinator(PlaybackPoolCandidate.C2)

        val decision = coordinator.dispatch(PlaybackPoolIntent.DowngradeC2ToC1)

        assertEquals(
            PlaybackPoolDecision.SessionFallback(PlaybackPoolFallbackReason.SURFACE_FAILURE),
            decision,
        )
        assertEquals(PlaybackPoolCandidate.C1, coordinator.state.candidate)
        assertTrue(coordinator.state.isExperimentAvailable)
        assertEquals(StandbySurfaceMode.NONE, coordinator.state.standby.standbySurfaceMode)
    }

    @Test
    fun c1SurfaceFailureSignalIsIdempotent() {
        val coordinator = PlaybackPoolCoordinator(PlaybackPoolCandidate.C1)

        assertEquals(PlaybackPoolDecision.NoChange, coordinator.dispatch(PlaybackPoolIntent.DowngradeC2ToC1))
        assertTrue(coordinator.state.isExperimentAvailable)
    }

    @Test
    fun standbyIsSilentFocuslessAndBoundToOneExactNextOwnerAndBudget() {
        val coordinator = PlaybackPoolCoordinator(PlaybackPoolCandidate.C2)
        coordinator.dispatch(PlaybackPoolIntent.BindActive(media(1), userMuted = false))
        val decision = coordinator.dispatch(
            PlaybackPoolIntent.PrepareStandby(
                media = media(2),
                nextOwnerToken = "pool-next-1",
                byteBudget = 5L * 1024L * 1024L,
                safety = PlaybackPoolSafety(),
            ),
        ) as PlaybackPoolDecision.StandbyPrepared

        assertFalse(decision.slot.hasAudioFocus)
        assertFalse(decision.slot.canProduceAudio)
        assertEquals("pool-next-1", decision.slot.nextOwnerToken)
        assertEquals(StandbySurfaceMode.PERSISTENT, decision.slot.standbySurfaceMode)
        assertEquals(1, coordinator.state.slots.count { it.hasAudioFocus })
    }

    @Test
    fun exactPromotionSwapsFixedSlotsAndNeverOverlapsAudio() {
        val coordinator = PlaybackPoolCoordinator(PlaybackPoolCandidate.C1)
        val current = media(1)
        val next = media(2)
        coordinator.dispatch(PlaybackPoolIntent.BindActive(current, userMuted = false))
        coordinator.dispatch(
            PlaybackPoolIntent.PrepareStandby(
                next, "pool-next-2", 2L * 1024L * 1024L, PlaybackPoolSafety(),
            ),
        )

        val result = coordinator.dispatch(
            PlaybackPoolIntent.PromoteStandby(next, userMuted = false),
        ) as PlaybackPoolDecision.Promoted

        assertEquals(1, result.slot.playerSlot)
        assertEquals(next, coordinator.state.active.media)
        assertEquals(0, coordinator.state.standby.playerSlot)
        assertEquals(null, coordinator.state.standby.media)
        assertEquals(1, coordinator.state.slots.count { it.canProduceAudio })
    }

    @Test
    fun staleGenerationRejectsOnlyTheStalePromotionAndPoolCanRecover() {
        val coordinator = PlaybackPoolCoordinator(PlaybackPoolCandidate.C1)
        coordinator.dispatch(
            PlaybackPoolIntent.PrepareStandby(
                media(2), "pool-next-2", 2L * 1024L * 1024L, PlaybackPoolSafety(),
            ),
        )
        val stale = media(2).copy(queueGeneration = 99L)

        val result = coordinator.dispatch(
            PlaybackPoolIntent.PromoteStandby(stale, userMuted = false),
        )

        assertEquals(
            PlaybackPoolDecision.Rejected(PlaybackPoolFallbackReason.GENERATION_OR_TARGET_MISMATCH),
            result,
        )
        assertTrue(coordinator.state.isExperimentAvailable)
        assertTrue(coordinator.dispatch(
            PlaybackPoolIntent.PrepareStandby(
                media(3), "pool-next-3", 2L * 1024L * 1024L, PlaybackPoolSafety(),
            ),
        ) is PlaybackPoolDecision.StandbyPrepared)
    }

    @Test
    fun surfaceFailureSurvivesBindingReleaseUntilFeedSessionEnds() {
        val coordinator = PlaybackPoolCoordinator(PlaybackPoolCandidate.C2)
        coordinator.dispatch(PlaybackPoolIntent.FailSession(PlaybackPoolFallbackReason.SURFACE_FAILURE))
        coordinator.dispatch(PlaybackPoolIntent.ReleaseBinding)
        coordinator.dispatch(PlaybackPoolIntent.BindActive(media(2), userMuted = false))
        assertFalse(coordinator.state.isExperimentAvailable)
        assertEquals(PlaybackPoolFallbackReason.SURFACE_FAILURE, coordinator.state.fallbackReason)
        coordinator.dispatch(PlaybackPoolIntent.Release)
        assertTrue(coordinator.state.isExperimentAvailable)
    }

    @Test
    fun unsafeDeviceStateAndInvalidBudgetFailClosed() {
        val lowMemory = PlaybackPoolCoordinator(PlaybackPoolCandidate.C1)
        lowMemory.dispatch(
            PlaybackPoolIntent.PrepareStandby(
                media(2), "pool-next-2", 2L * 1024L * 1024L,
                PlaybackPoolSafety(isLowMemory = true),
            ),
        )
        assertEquals(null, lowMemory.state.fallbackReason)
        assertTrue(lowMemory.state.isExperimentAvailable)
        assertTrue(lowMemory.dispatch(
            PlaybackPoolIntent.PrepareStandby(
                media(3), "pool-next-3", 2L * 1024L * 1024L, PlaybackPoolSafety(),
            ),
        ) is PlaybackPoolDecision.StandbyPrepared)

        val invalidBudget = PlaybackPoolCoordinator(PlaybackPoolCandidate.C1)
        invalidBudget.dispatch(
            PlaybackPoolIntent.PrepareStandby(
                media(2), "pool-next-2", 20L * 1024L * 1024L + 1L, PlaybackPoolSafety(),
            ),
        )
        assertEquals(
            PlaybackPoolFallbackReason.NEXT_BUDGET_EXCEEDED,
            invalidBudget.state.fallbackReason,
        )
    }

    @Test
    fun durationBudgetBelowTierCeilingIsAccepted() {
        val coordinator = PlaybackPoolCoordinator(PlaybackPoolCandidate.C1)
        val decision = coordinator.dispatch(PlaybackPoolIntent.PrepareStandby(
            media(2), "pool-next-1", 187_500L, PlaybackPoolSafety(),
        ))
        assertTrue(decision is PlaybackPoolDecision.StandbyPrepared)
        assertEquals(187_500L, coordinator.state.standby.nextByteBudget)
    }

    @Test
    fun longSessionRetainsBoundedTokenHistoryWithoutDisablingPreparation() {
        val coordinator = PlaybackPoolCoordinator(PlaybackPoolCandidate.C1)
        repeat(700) { index ->
            val decision = coordinator.dispatch(
                PlaybackPoolIntent.PrepareStandby(
                    media(index + 1),
                    "pool-next-${index + 1}",
                    256L * 1024L,
                    PlaybackPoolSafety(),
                ),
            )
            assertTrue(decision is PlaybackPoolDecision.StandbyPrepared)
        }
        assertTrue(coordinator.state.isExperimentAvailable)
        assertTrue(
            coordinator.dispatch(
                PlaybackPoolIntent.PrepareStandby(
                    media(701), "pool-next-701", 256L * 1024L, PlaybackPoolSafety(),
                ),
            ) is PlaybackPoolDecision.StandbyPrepared,
        )
        coordinator.dispatch(PlaybackPoolIntent.Release)
        assertEquals(
            PlaybackPoolDecision.SessionFallback(PlaybackPoolFallbackReason.OWNER_TOKEN_REUSED),
            coordinator.dispatch(PlaybackPoolIntent.PrepareStandby(
                media(1), "pool-next-1", 256L * 1024L, PlaybackPoolSafety(),
            )),
        )
    }

    @Test
    fun logoutClearsStandbyAndRevokesAllAudio() {
        val coordinator = PlaybackPoolCoordinator(PlaybackPoolCandidate.C1)
        coordinator.dispatch(PlaybackPoolIntent.BindActive(media(1), userMuted = false))
        coordinator.dispatch(
            PlaybackPoolIntent.PrepareStandby(
                media(2), "pool-next-2", 2L * 1024L * 1024L, PlaybackPoolSafety(),
            ),
        )

        coordinator.dispatch(PlaybackPoolIntent.Logout)

        assertEquals(null, coordinator.state.standby.media)
        assertEquals(null, coordinator.state.active.media)
        assertFalse(coordinator.state.active.hasAudioFocus)
        assertFalse(coordinator.state.active.canProduceAudio)
        assertFalse(coordinator.state.standby.hasAudioFocus)
        assertFalse(coordinator.state.standby.canProduceAudio)
        assertEquals(PlaybackPoolFallbackReason.LOGOUT, coordinator.state.fallbackReason)
    }

    @Test
    fun abGateRequiresEveryPerformanceSafetyAndFallbackCondition() {
        val passing = passingEvidence()
        assertTrue(PlaybackPoolAbEvaluator.evaluate(passing).eligibleForDiscussion)

        val failed = PlaybackPoolAbEvaluator.evaluate(
            passing.copy(
                candidateGestureP95Millis = 440L,
                candidatePeakPssBytes = 140L * 1024L * 1024L,
                candidateJankPercent = 5.1,
                safetyFailureCount = 1,
                fallbackVerified = false,
            ),
        )

        assertFalse(failed.eligibleForDiscussion)
        assertTrue(PlaybackPoolAbRejection.P95_IMPROVEMENT_TOO_SMALL in failed.rejectionReasons)
        assertTrue(PlaybackPoolAbRejection.PSS_REGRESSION in failed.rejectionReasons)
        assertTrue(PlaybackPoolAbRejection.JANK_REGRESSION in failed.rejectionReasons)
        assertTrue(PlaybackPoolAbRejection.SAFETY_FAILURE in failed.rejectionReasons)
        assertTrue(PlaybackPoolAbRejection.FALLBACK_UNVERIFIED in failed.rejectionReasons)
    }

    private fun passingEvidence() = PlaybackPoolAbEvidence(
        normalForwardSamples = 30,
        reverseSamples = 30,
        fastSwipeSamples = 30,
        baselineGestureP95Millis = 500L,
        candidateGestureP95Millis = 400L,
        firstFrameCount = 90,
        transitionCount = 90,
        baselineSkippedNextWasteP95Bytes = 1_000L,
        candidateSkippedNextWasteP95Bytes = 1_200L,
        baselinePeakPssBytes = 100L * 1024L * 1024L,
        candidatePeakPssBytes = 120L * 1024L * 1024L,
        baselineJankPercent = 2.0,
        candidateJankPercent = 3.0,
        baselineRebufferCount = 0,
        candidateRebufferCount = 0,
        safetyFailureCount = 0,
        codecResourceFailureCount = 0,
        thermalRegression = false,
        fallbackVerified = true,
    )

    private fun media(id: Int) = PlaybackPoolMedia(
        key = VideoKey(1L, id.toLong()),
        fileId = id,
        qualityGeneration = 1L,
        accountGeneration = 1L,
        networkGeneration = 1L,
        queueGeneration = 1L,
    )
}
