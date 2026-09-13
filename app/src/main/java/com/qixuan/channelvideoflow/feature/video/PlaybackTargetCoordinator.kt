package com.qixuan.channelvideoflow.feature.video

import com.qixuan.channelvideoflow.model.video.VideoKey

/** Pure pager-target state machine. It deliberately knows nothing about Compose or playback. */
internal class PlaybackTargetCoordinator {
    var state: PlaybackTargetState = PlaybackTargetState()
        private set

    fun dispatch(intent: PlaybackTargetIntent): PlaybackTargetDecision {
        val decision = when (intent) {
            PlaybackTargetIntent.PageBecameUnstable -> beginTransition()
            is PlaybackTargetIntent.PointerDown -> pointerDown(intent.observedAtMillis)
            is PlaybackTargetIntent.PointerReleased -> pointerReleased(intent.observedAtMillis)
            is PlaybackTargetIntent.Targeted -> targeted(intent.target, intent.currentKey)
            is PlaybackTargetIntent.Settled -> settled(intent.target, intent.queueGeneration)
            PlaybackTargetIntent.Reset -> {
                state = PlaybackTargetState()
                PlaybackTargetDecision.Reset
            }
        }
        return decision
    }

    private fun beginTransition(): PlaybackTargetDecision {
        if (state.isUnstable) return PlaybackTargetDecision.Ignored
        val pointerDownAtMillis = state.pointerDownAtMillis
        state = state.copy(isUnstable = true, unstableTarget = null)
        return PlaybackTargetDecision.TransitionStarted(pointerDownAtMillis)
    }

    private fun pointerDown(observedAtMillis: Long): PlaybackTargetDecision {
        if (state.pointerDownAtMillis != null) return PlaybackTargetDecision.Ignored
        val wasUnstable = state.isUnstable
        state = state.copy(
            pointerDownAtMillis = observedAtMillis,
            unstableTarget = if (wasUnstable) null else state.unstableTarget,
        )
        return PlaybackTargetDecision.PointerStarted(observedAtMillis, wasUnstable)
    }

    private fun pointerReleased(observedAtMillis: Long): PlaybackTargetDecision {
        val startedAtMillis = state.pointerDownAtMillis
        val shouldRecord = state.isUnstable && startedAtMillis != null
        state = state.copy(pointerDownAtMillis = null)
        return if (shouldRecord) {
            PlaybackTargetDecision.PointerFinished(
                startedAtMillis = requireNotNull(startedAtMillis),
                observedAtMillis = observedAtMillis,
            )
        } else {
            PlaybackTargetDecision.Ignored
        }
    }

    private fun targeted(
        target: PlaybackTargetIdentity,
        currentKey: VideoKey?,
    ): PlaybackTargetDecision {
        if (!state.isUnstable) return PlaybackTargetDecision.Ignored
        val previous = state.unstableTarget
        state = state.copy(unstableTarget = target)
        if (target.key == currentKey) {
            if (previous != null && !previous.samePosition(target)) {
                if (state.pointerDownAtMillis == null) {
                    state = state.copy(unstableTarget = previous)
                    return PlaybackTargetDecision.TargetEvaluated(
                        target = target,
                        previous = previous,
                        outcome = PlaybackTargetOutcome.BOUNCE_TO_CURRENT_IGNORED,
                    )
                }
                return PlaybackTargetDecision.TargetEvaluated(
                    target = target,
                    previous = previous,
                    outcome = PlaybackTargetOutcome.CURRENT_TARGET_ABANDONED,
                )
            }
            return PlaybackTargetDecision.TargetEvaluated(
                target = target,
                previous = previous,
                outcome = PlaybackTargetOutcome.CURRENT_TARGET,
            )
        }
        return PlaybackTargetDecision.TargetEvaluated(
            target = target,
            previous = previous,
            outcome = if (previous?.samePosition(target) == true) {
                PlaybackTargetOutcome.EXISTING_TARGET
            } else {
                PlaybackTargetOutcome.NEW_TARGET
            },
        )
    }

    private fun settled(
        target: PlaybackTargetIdentity,
        queueGeneration: Long,
    ): PlaybackTargetDecision {
        val wasUnstable = state.isUnstable
        val expectedTarget = state.unstableTarget
        val previousSettled = state.lastSettled
        val settled = SettledPlaybackTarget(
            pagerPage = target.pagerPage,
            key = target.key,
            queueGeneration = queueGeneration,
        )
        val duplicate = !wasUnstable && previousSettled == settled
        state = state.copy(
            isUnstable = false,
            pointerDownAtMillis = null,
            unstableTarget = null,
            lastSettled = settled,
        )
        return PlaybackTargetDecision.PageSettled(
            target = target,
            expectedTarget = expectedTarget,
            previousSettled = previousSettled,
            wasUnstable = wasUnstable,
            expectedTargetMismatch = wasUnstable &&
                expectedTarget != null &&
                !expectedTarget.samePosition(target),
            duplicate = duplicate,
        )
    }
}

internal sealed interface PlaybackTargetIntent {
    data object PageBecameUnstable : PlaybackTargetIntent
    data class PointerDown(val observedAtMillis: Long) : PlaybackTargetIntent
    data class PointerReleased(val observedAtMillis: Long) : PlaybackTargetIntent
    data class Targeted(
        val target: PlaybackTargetIdentity,
        val currentKey: VideoKey?,
    ) : PlaybackTargetIntent
    data class Settled(
        val target: PlaybackTargetIdentity,
        val queueGeneration: Long,
    ) : PlaybackTargetIntent
    data object Reset : PlaybackTargetIntent
}

internal sealed interface PlaybackTargetDecision {
    data object Ignored : PlaybackTargetDecision
    data object Reset : PlaybackTargetDecision
    data class TransitionStarted(val pointerDownAtMillis: Long?) : PlaybackTargetDecision
    data class PointerStarted(
        val observedAtMillis: Long,
        val whileUnstable: Boolean,
    ) : PlaybackTargetDecision
    data class PointerFinished(
        val startedAtMillis: Long,
        val observedAtMillis: Long,
    ) : PlaybackTargetDecision
    data class TargetEvaluated(
        val target: PlaybackTargetIdentity,
        val previous: PlaybackTargetIdentity?,
        val outcome: PlaybackTargetOutcome,
    ) : PlaybackTargetDecision
    data class PageSettled(
        val target: PlaybackTargetIdentity,
        val expectedTarget: PlaybackTargetIdentity?,
        val previousSettled: SettledPlaybackTarget?,
        val wasUnstable: Boolean,
        val expectedTargetMismatch: Boolean,
        val duplicate: Boolean,
    ) : PlaybackTargetDecision
}

internal enum class PlaybackTargetOutcome {
    CURRENT_TARGET,
    CURRENT_TARGET_ABANDONED,
    BOUNCE_TO_CURRENT_IGNORED,
    EXISTING_TARGET,
    NEW_TARGET,
}

internal data class PlaybackTargetState(
    val isUnstable: Boolean = false,
    val pointerDownAtMillis: Long? = null,
    val unstableTarget: PlaybackTargetIdentity? = null,
    val lastSettled: SettledPlaybackTarget? = null,
)

internal data class PlaybackTargetIdentity(
    val pagerPage: Int,
    val logicalPage: Int,
    val key: VideoKey,
    val randomRoundGeneration: Long?,
    val randomRoundIndex: Int?,
) {
    fun samePosition(other: PlaybackTargetIdentity): Boolean =
        key == other.key &&
            randomRoundGeneration == other.randomRoundGeneration &&
            randomRoundIndex == other.randomRoundIndex
}

internal data class SettledPlaybackTarget(
    val pagerPage: Int,
    val key: VideoKey,
    val queueGeneration: Long,
)
