package com.qixuan.channelvideoflow.feature.video

import com.qixuan.channelvideoflow.model.video.VideoKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTargetCoordinatorTest {
    @Test
    fun fastTargetReplacementKeepsOnlyTheLatestCandidate() {
        val coordinator = PlaybackTargetCoordinator()
        coordinator.dispatch(PlaybackTargetIntent.PointerDown(10L))
        coordinator.dispatch(PlaybackTargetIntent.PageBecameUnstable)

        val first = coordinator.dispatch(
            PlaybackTargetIntent.Targeted(target(1), currentKey = key(0)),
        ) as PlaybackTargetDecision.TargetEvaluated
        val second = coordinator.dispatch(
            PlaybackTargetIntent.Targeted(target(2), currentKey = key(0)),
        ) as PlaybackTargetDecision.TargetEvaluated

        assertEquals(PlaybackTargetOutcome.NEW_TARGET, first.outcome)
        assertEquals(PlaybackTargetOutcome.NEW_TARGET, second.outcome)
        assertEquals(target(1), second.previous)
        assertEquals(target(2), coordinator.state.unstableTarget)
    }

    @Test
    fun releaseBounceToCurrentDoesNotDiscardTheCommittedCandidate() {
        val coordinator = PlaybackTargetCoordinator()
        coordinator.dispatch(PlaybackTargetIntent.PointerDown(10L))
        coordinator.dispatch(PlaybackTargetIntent.PageBecameUnstable)
        coordinator.dispatch(PlaybackTargetIntent.Targeted(target(1), currentKey = key(0)))
        coordinator.dispatch(PlaybackTargetIntent.PointerReleased(20L))

        val bounce = coordinator.dispatch(
            PlaybackTargetIntent.Targeted(target(0), currentKey = key(0)),
        ) as PlaybackTargetDecision.TargetEvaluated

        assertEquals(PlaybackTargetOutcome.BOUNCE_TO_CURRENT_IGNORED, bounce.outcome)
        assertEquals(target(1), coordinator.state.unstableTarget)
    }

    @Test
    fun settledMismatchIsExplicitAndDuplicateSettleIsIgnored() {
        val coordinator = PlaybackTargetCoordinator()
        coordinator.dispatch(PlaybackTargetIntent.PageBecameUnstable)
        coordinator.dispatch(PlaybackTargetIntent.Targeted(target(1), currentKey = key(0)))

        val mismatch = coordinator.dispatch(
            PlaybackTargetIntent.Settled(target(2), queueGeneration = 4L),
        ) as PlaybackTargetDecision.PageSettled
        val duplicate = coordinator.dispatch(
            PlaybackTargetIntent.Settled(target(2), queueGeneration = 4L),
        ) as PlaybackTargetDecision.PageSettled

        assertTrue(mismatch.wasUnstable)
        assertTrue(mismatch.expectedTargetMismatch)
        assertFalse(mismatch.duplicate)
        assertTrue(duplicate.duplicate)
        assertEquals(key(2), coordinator.state.lastSettled?.key)
    }

    @Test
    fun resetDropsStaleGenerationAndGestureState() {
        val coordinator = PlaybackTargetCoordinator()
        coordinator.dispatch(PlaybackTargetIntent.PointerDown(10L))
        coordinator.dispatch(PlaybackTargetIntent.PageBecameUnstable)
        coordinator.dispatch(PlaybackTargetIntent.Targeted(target(1), currentKey = key(0)))

        coordinator.dispatch(PlaybackTargetIntent.Reset)

        assertEquals(PlaybackTargetState(), coordinator.state)
    }

    private fun target(index: Int) = PlaybackTargetIdentity(
        pagerPage = index,
        logicalPage = index,
        key = key(index),
        randomRoundGeneration = null,
        randomRoundIndex = null,
    )

    private fun key(index: Int) = VideoKey(chatId = 1L, messageId = index.toLong())
}
