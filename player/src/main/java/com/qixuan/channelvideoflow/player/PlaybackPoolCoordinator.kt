package com.qixuan.channelvideoflow.player

import com.qixuan.channelvideoflow.model.video.VideoKey

/**
 * Host-testable state contract for the bounded two-player runtime. ExoPlayer ownership stays in
 * [VideoPlayerManager]; this coordinator enforces the fixed slots and fail-closed transitions.
 */
internal class PlaybackPoolCoordinator(
    candidate: PlaybackPoolCandidate = PlaybackPoolCandidate.fromBuildValue(
        BuildConfig.PLAYBACK_POOL_CANDIDATE,
    ),
) {
    // Production tokens carry a process-wide monotonic sequence. A watermark rejects even
    // very old tokens without retaining one String for every video in a long session.
    private var lastOwnerSequence = 0L
    var state = PlaybackPoolState.initial(candidate)
        private set

    fun dispatch(intent: PlaybackPoolIntent): PlaybackPoolDecision = when (intent) {
        is PlaybackPoolIntent.BindActive -> bindActive(intent)
        is PlaybackPoolIntent.PrepareStandby -> prepareStandby(intent)
        is PlaybackPoolIntent.PromoteStandby -> promoteStandby(intent)
        is PlaybackPoolIntent.SafetyChanged -> safetyChanged(intent.safety)
        is PlaybackPoolIntent.FailSession -> fallback(intent.reason)
        PlaybackPoolIntent.DowngradeC2ToC1 -> downgradeC2ToC1()
        PlaybackPoolIntent.NetworkChanged -> discardStandby()
        PlaybackPoolIntent.Backgrounded -> fallback(PlaybackPoolFallbackReason.BACKGROUNDED)
        PlaybackPoolIntent.Logout -> fallback(PlaybackPoolFallbackReason.LOGOUT)
        PlaybackPoolIntent.ReleaseBinding -> {
            state = PlaybackPoolState.initial(state.candidate).copy(fallbackReason = state.fallbackReason)
            PlaybackPoolDecision.Released
        }
        PlaybackPoolIntent.Release -> {
            state = PlaybackPoolState.initial(state.candidate)
            PlaybackPoolDecision.Released
        }
        PlaybackPoolIntent.DiscardStandby -> {
            state = state.copy(
                standby = state.standby.copy(
                    media = null,
                    hasAudioFocus = false,
                    canProduceAudio = false,
                    nextOwnerToken = null,
                    nextByteBudget = 0L,
                ),
            )
            checkInvariants()
            PlaybackPoolDecision.NoChange
        }
    }

    private fun bindActive(intent: PlaybackPoolIntent.BindActive): PlaybackPoolDecision {
        val active = state.active.copy(
            media = intent.media,
            hasAudioFocus = !intent.userMuted,
            canProduceAudio = !intent.userMuted,
            nextOwnerToken = null,
            nextByteBudget = 0L,
        )
        state = state.copy(active = active)
        checkInvariants()
        return PlaybackPoolDecision.ActiveBound(active)
    }

    private fun prepareStandby(
        intent: PlaybackPoolIntent.PrepareStandby,
    ): PlaybackPoolDecision {
        if (!state.isExperimentAvailable) {
            return PlaybackPoolDecision.Rejected(
                state.fallbackReason ?: PlaybackPoolFallbackReason.CANDIDATE_DISABLED,
            )
        }
        val safetyFailure = intent.safety.blockingReason()
        if (safetyFailure != null) {
            // Environmental pressure is a transient admission failure. Release NEXT now,
            // while keeping the bounded pool available for a later safe window.
            discardStandby()
            return PlaybackPoolDecision.Rejected(safetyFailure)
        }
        val sequence = intent.nextOwnerToken.removePrefix("pool-next-").toLongOrNull()
        if (sequence == null || sequence <= 0 || intent.nextOwnerToken != "pool-next-$sequence") {
            return fallback(PlaybackPoolFallbackReason.INVALID_OWNER_TOKEN)
        }
        if (intent.byteBudget !in ALLOWED_NEXT_BYTE_BUDGETS) {
            return fallback(PlaybackPoolFallbackReason.NEXT_BUDGET_EXCEEDED)
        }
        val currentStandby = state.standby
        val idempotent = currentStandby.media == intent.media &&
            currentStandby.nextOwnerToken == intent.nextOwnerToken
        if (!idempotent && sequence <= lastOwnerSequence) {
            return fallback(PlaybackPoolFallbackReason.OWNER_TOKEN_REUSED)
        }
        if (!idempotent) lastOwnerSequence = sequence
        val standby = currentStandby.copy(
            media = intent.media,
            hasAudioFocus = false,
            canProduceAudio = false,
            nextOwnerToken = intent.nextOwnerToken,
            nextByteBudget = intent.byteBudget,
        )
        state = state.copy(standby = standby)
        checkInvariants()
        return PlaybackPoolDecision.StandbyPrepared(standby)
    }

    private fun promoteStandby(
        intent: PlaybackPoolIntent.PromoteStandby,
    ): PlaybackPoolDecision {
        if (!state.isExperimentAvailable) {
            return PlaybackPoolDecision.Rejected(
                state.fallbackReason ?: PlaybackPoolFallbackReason.CANDIDATE_DISABLED,
            )
        }
        val prepared = state.standby
        if (prepared.media != intent.media || prepared.nextOwnerToken == null) {
            discardStandby()
            return PlaybackPoolDecision.Rejected(PlaybackPoolFallbackReason.GENERATION_OR_TARGET_MISMATCH)
        }
        val previousActive = state.active
        val promoted = prepared.copy(
            role = PlaybackPoolRole.ACTIVE,
            hasAudioFocus = !intent.userMuted,
            canProduceAudio = !intent.userMuted,
            nextOwnerToken = null,
            nextByteBudget = 0L,
        )
        val retired = previousActive.copy(
            role = PlaybackPoolRole.STANDBY,
            media = null,
            hasAudioFocus = false,
            canProduceAudio = false,
            nextOwnerToken = null,
            nextByteBudget = 0L,
        )
        state = state.copy(active = promoted, standby = retired)
        checkInvariants()
        return PlaybackPoolDecision.Promoted(promoted)
    }

    private fun safetyChanged(safety: PlaybackPoolSafety): PlaybackPoolDecision {
        val failure = safety.blockingReason() ?: return PlaybackPoolDecision.NoChange
        discardStandby()
        return PlaybackPoolDecision.Rejected(failure)
    }

    private fun downgradeC2ToC1(): PlaybackPoolDecision {
        if (state.candidate != PlaybackPoolCandidate.C2) return PlaybackPoolDecision.NoChange
        state = state.copy(
            candidate = PlaybackPoolCandidate.C1,
            standby = state.standby.copy(standbySurfaceMode = StandbySurfaceMode.NONE),
        )
        checkInvariants()
        return PlaybackPoolDecision.SessionFallback(PlaybackPoolFallbackReason.SURFACE_FAILURE)
    }

    private fun discardStandby(): PlaybackPoolDecision {
        state = state.copy(
            standby = state.standby.copy(
                media = null,
                hasAudioFocus = false,
                canProduceAudio = false,
                nextOwnerToken = null,
                nextByteBudget = 0L,
            ),
        )
        checkInvariants()
        return PlaybackPoolDecision.NoChange
    }

    private fun fallback(reason: PlaybackPoolFallbackReason): PlaybackPoolDecision {
        val stopActive = reason == PlaybackPoolFallbackReason.BACKGROUNDED ||
            reason == PlaybackPoolFallbackReason.LOGOUT
        val active = state.active.copy(
            role = PlaybackPoolRole.ACTIVE,
            media = if (reason == PlaybackPoolFallbackReason.LOGOUT) null else state.active.media,
            hasAudioFocus = if (stopActive) false else state.active.hasAudioFocus,
            canProduceAudio = if (stopActive) false else state.active.canProduceAudio,
            nextOwnerToken = null,
            nextByteBudget = 0L,
        )
        val standby = state.standby.copy(
            role = PlaybackPoolRole.STANDBY,
            media = null,
            hasAudioFocus = false,
            canProduceAudio = false,
            nextOwnerToken = null,
            nextByteBudget = 0L,
        )
        state = state.copy(active = active, standby = standby, fallbackReason = reason)
        checkInvariants()
        return PlaybackPoolDecision.SessionFallback(reason)
    }

    private fun checkInvariants() {
        check(state.slots.size == MAX_PLAYER_COUNT)
        check(state.slots.map(PlaybackPoolSlot::playerSlot).distinct().size == MAX_PLAYER_COUNT)
        check(state.active.role == PlaybackPoolRole.ACTIVE)
        check(state.standby.role == PlaybackPoolRole.STANDBY)
        check(!state.standby.hasAudioFocus)
        check(!state.standby.canProduceAudio)
        check(state.slots.count(PlaybackPoolSlot::hasAudioFocus) <= 1)
        check(state.slots.count(PlaybackPoolSlot::canProduceAudio) <= 1)
        check(state.standby.nextByteBudget in ALLOWED_NEXT_BYTE_BUDGETS)
    }

    private companion object {
        const val MAX_PLAYER_COUNT = 2
        const val MIB = 1024L * 1024L
        // Tiers are ceilings. Duration/bitrate planning may choose any smaller byte count.
        // The top tier matches NextPreloadBudgetController.ABSOLUTE_MAX_BYTES so a 5 second reserve
        // of a high bitrate original is still representable.
        val ALLOWED_NEXT_BYTE_BUDGETS = 0L..(20L * MIB)
    }
}

internal enum class PlaybackPoolCandidate {
    DISABLED,
    C1,
    C2;

    companion object {
        fun fromBuildValue(value: String): PlaybackPoolCandidate =
            entries.firstOrNull { it.name == value } ?: DISABLED
    }
}

internal enum class PlaybackPoolRole { ACTIVE, STANDBY }
internal enum class StandbySurfaceMode { NONE, PERSISTENT }

internal data class PlaybackPoolMedia(
    val key: VideoKey,
    val fileId: Int,
    val qualityGeneration: Long,
    val accountGeneration: Long,
    val networkGeneration: Long,
    val queueGeneration: Long,
)

internal data class PlaybackPoolSlot(
    val playerSlot: Int,
    val role: PlaybackPoolRole,
    val media: PlaybackPoolMedia? = null,
    val hasAudioFocus: Boolean = false,
    val canProduceAudio: Boolean = false,
    val nextOwnerToken: String? = null,
    val nextByteBudget: Long = 0L,
    val standbySurfaceMode: StandbySurfaceMode = StandbySurfaceMode.NONE,
)

internal data class PlaybackPoolState(
    val candidate: PlaybackPoolCandidate,
    val active: PlaybackPoolSlot,
    val standby: PlaybackPoolSlot,
    val fallbackReason: PlaybackPoolFallbackReason? = null,
) {
    val slots: List<PlaybackPoolSlot> get() = listOf(active, standby)
    val isExperimentAvailable: Boolean
        get() = candidate != PlaybackPoolCandidate.DISABLED && fallbackReason == null

    companion object {
        fun initial(candidate: PlaybackPoolCandidate): PlaybackPoolState = PlaybackPoolState(
            candidate = candidate,
            active = PlaybackPoolSlot(playerSlot = 0, role = PlaybackPoolRole.ACTIVE),
            standby = PlaybackPoolSlot(
                playerSlot = 1,
                role = PlaybackPoolRole.STANDBY,
                standbySurfaceMode = if (candidate == PlaybackPoolCandidate.C2) {
                    StandbySurfaceMode.PERSISTENT
                } else {
                    StandbySurfaceMode.NONE
                },
            ),
        )
    }
}

internal data class PlaybackPoolSafety(
    val currentReservoirSafe: Boolean = true,
    val isMetered: Boolean = false,
    val mobilePreloadEnabled: Boolean = false,
    val isLowMemory: Boolean = false,
    val isLowStorage: Boolean = false,
    val isPowerSave: Boolean = false,
    val thermalStatus: PlaybackPoolThermalStatus = PlaybackPoolThermalStatus.NONE,
) {
    fun blockingReason(): PlaybackPoolFallbackReason? = when {
        !currentReservoirSafe -> PlaybackPoolFallbackReason.CURRENT_RESERVOIR_LOW
        isMetered && !mobilePreloadEnabled -> PlaybackPoolFallbackReason.METERED_NETWORK
        isLowMemory -> PlaybackPoolFallbackReason.LOW_MEMORY
        isLowStorage -> PlaybackPoolFallbackReason.LOW_STORAGE
        isPowerSave -> PlaybackPoolFallbackReason.POWER_SAVE
        thermalStatus >= PlaybackPoolThermalStatus.MODERATE -> PlaybackPoolFallbackReason.THERMAL
        else -> null
    }
}

internal enum class PlaybackPoolThermalStatus { NONE, LIGHT, MODERATE, SEVERE }

internal sealed interface PlaybackPoolIntent {
    data class BindActive(val media: PlaybackPoolMedia, val userMuted: Boolean) : PlaybackPoolIntent
    data class PrepareStandby(
        val media: PlaybackPoolMedia,
        val nextOwnerToken: String,
        val byteBudget: Long,
        val safety: PlaybackPoolSafety,
    ) : PlaybackPoolIntent
    data class PromoteStandby(
        val media: PlaybackPoolMedia,
        val userMuted: Boolean,
    ) : PlaybackPoolIntent
    data class SafetyChanged(val safety: PlaybackPoolSafety) : PlaybackPoolIntent
    data class FailSession(val reason: PlaybackPoolFallbackReason) : PlaybackPoolIntent
    data object DowngradeC2ToC1 : PlaybackPoolIntent
    data object NetworkChanged : PlaybackPoolIntent
    data object Backgrounded : PlaybackPoolIntent
    data object Logout : PlaybackPoolIntent
    data object ReleaseBinding : PlaybackPoolIntent
    data object Release : PlaybackPoolIntent
    data object DiscardStandby : PlaybackPoolIntent
}

internal sealed interface PlaybackPoolDecision {
    data object NoChange : PlaybackPoolDecision
    data object Released : PlaybackPoolDecision
    data class ActiveBound(val slot: PlaybackPoolSlot) : PlaybackPoolDecision
    data class StandbyPrepared(val slot: PlaybackPoolSlot) : PlaybackPoolDecision
    data class Promoted(val slot: PlaybackPoolSlot) : PlaybackPoolDecision
    data class SessionFallback(val reason: PlaybackPoolFallbackReason) : PlaybackPoolDecision
    data class Rejected(val reason: PlaybackPoolFallbackReason) : PlaybackPoolDecision
}

internal enum class PlaybackPoolFallbackReason {
    CANDIDATE_DISABLED,
    INVALID_OWNER_TOKEN,
    OWNER_TOKEN_REUSED,
    NEXT_BUDGET_EXCEEDED,
    GENERATION_OR_TARGET_MISMATCH,
    CURRENT_RESERVOIR_LOW,
    METERED_NETWORK,
    LOW_MEMORY,
    LOW_STORAGE,
    POWER_SAVE,
    THERMAL,
    DECODER_RESOURCE,
    SURFACE_FAILURE,
    BLACK_OR_WRONG_FRAME,
    AUDIO_OVERLAP,
    REBUFFER,
    NETWORK_CHANGED,
    FAST_TARGET_INVALIDATED,
    BACKGROUNDED,
    LOGOUT,
    CACHE_CLEAR,
}

internal data class PlaybackPoolAbEvidence(
    val normalForwardSamples: Int,
    val reverseSamples: Int,
    val fastSwipeSamples: Int,
    val baselineGestureP95Millis: Long,
    val candidateGestureP95Millis: Long,
    val firstFrameCount: Int,
    val transitionCount: Int,
    val baselineSkippedNextWasteP95Bytes: Long,
    val candidateSkippedNextWasteP95Bytes: Long,
    val baselinePeakPssBytes: Long,
    val candidatePeakPssBytes: Long,
    val baselineJankPercent: Double,
    val candidateJankPercent: Double,
    val baselineRebufferCount: Int,
    val candidateRebufferCount: Int,
    val safetyFailureCount: Int,
    val codecResourceFailureCount: Int,
    val thermalRegression: Boolean,
    val fallbackVerified: Boolean,
)

internal data class PlaybackPoolAbResult(
    val eligibleForDiscussion: Boolean,
    val p95ImprovementFraction: Double,
    val absoluteP95ImprovementMillis: Long,
    val rejectionReasons: Set<PlaybackPoolAbRejection>,
)

internal enum class PlaybackPoolAbRejection {
    INSUFFICIENT_SAMPLES,
    FIRST_FRAME_INCOMPLETE,
    P95_IMPROVEMENT_TOO_SMALL,
    ABSOLUTE_IMPROVEMENT_TOO_SMALL,
    WASTE_REGRESSION,
    PSS_REGRESSION,
    JANK_REGRESSION,
    REBUFFER_REGRESSION,
    SAFETY_FAILURE,
    CODEC_RESOURCE_FAILURE,
    THERMAL_REGRESSION,
    FALLBACK_UNVERIFIED,
}

internal object PlaybackPoolAbEvaluator {
    fun evaluate(evidence: PlaybackPoolAbEvidence): PlaybackPoolAbResult {
        val absoluteImprovement =
            evidence.baselineGestureP95Millis - evidence.candidateGestureP95Millis
        val improvementFraction = if (evidence.baselineGestureP95Millis > 0L) {
            absoluteImprovement.toDouble() / evidence.baselineGestureP95Millis
        } else {
            0.0
        }
        val maximumPss = minOf(
            evidence.baselinePeakPssBytes + 64L * 1024L * 1024L,
            (evidence.baselinePeakPssBytes * 1.25).toLong(),
        )
        val maximumWaste = if (evidence.baselineSkippedNextWasteP95Bytes == 0L) {
            0L
        } else {
            (evidence.baselineSkippedNextWasteP95Bytes * 1.25).toLong()
        }
        val rejectionReasons = buildSet {
            if (
                evidence.normalForwardSamples < 30 ||
                evidence.reverseSamples < 30 ||
                evidence.fastSwipeSamples < 30
            ) add(PlaybackPoolAbRejection.INSUFFICIENT_SAMPLES)
            if (
                evidence.transitionCount <= 0 ||
                evidence.firstFrameCount != evidence.transitionCount
            ) add(PlaybackPoolAbRejection.FIRST_FRAME_INCOMPLETE)
            if (improvementFraction < 0.15) {
                add(PlaybackPoolAbRejection.P95_IMPROVEMENT_TOO_SMALL)
            }
            if (absoluteImprovement < 75L) {
                add(PlaybackPoolAbRejection.ABSOLUTE_IMPROVEMENT_TOO_SMALL)
            }
            if (evidence.candidateSkippedNextWasteP95Bytes > maximumWaste) {
                add(PlaybackPoolAbRejection.WASTE_REGRESSION)
            }
            if (evidence.candidatePeakPssBytes > maximumPss) {
                add(PlaybackPoolAbRejection.PSS_REGRESSION)
            }
            if (evidence.candidateJankPercent - evidence.baselineJankPercent > 2.0) {
                add(PlaybackPoolAbRejection.JANK_REGRESSION)
            }
            if (evidence.candidateRebufferCount > evidence.baselineRebufferCount) {
                add(PlaybackPoolAbRejection.REBUFFER_REGRESSION)
            }
            if (evidence.safetyFailureCount != 0) {
                add(PlaybackPoolAbRejection.SAFETY_FAILURE)
            }
            if (evidence.codecResourceFailureCount != 0) {
                add(PlaybackPoolAbRejection.CODEC_RESOURCE_FAILURE)
            }
            if (evidence.thermalRegression) add(PlaybackPoolAbRejection.THERMAL_REGRESSION)
            if (!evidence.fallbackVerified) add(PlaybackPoolAbRejection.FALLBACK_UNVERIFIED)
        }
        return PlaybackPoolAbResult(
            eligibleForDiscussion = rejectionReasons.isEmpty(),
            p95ImprovementFraction = improvementFraction,
            absoluteP95ImprovementMillis = absoluteImprovement,
            rejectionReasons = rejectionReasons,
        )
    }
}
