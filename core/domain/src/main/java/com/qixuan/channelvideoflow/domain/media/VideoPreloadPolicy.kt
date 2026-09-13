package com.qixuan.channelvideoflow.domain.media

import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoKey
import com.qixuan.channelvideoflow.model.video.VideoQualityPreference
import kotlinx.coroutines.flow.StateFlow

enum class NetworkTransport {
    OFFLINE,
    WIFI,
    MOBILE,
    OTHER,
}

enum class DeviceThermalState {
    UNKNOWN,
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
}

data class DevicePreloadSignals(
    val network: NetworkTransport = NetworkTransport.OFFLINE,
    val isMetered: Boolean = false,
    val isPowerSaveMode: Boolean = false,
    val isStorageLow: Boolean = false,
    val isMemoryLow: Boolean = false,
    val thermalState: DeviceThermalState = DeviceThermalState.UNKNOWN,
    /** Monotonic in-memory generation; it never contains an SSID, BSSID, IP, or network name. */
    val networkGeneration: Long = 0L,
)

interface DevicePreloadPolicySource {
    val signals: StateFlow<DevicePreloadSignals>
}

enum class PreloadBlockedReason {
    OFFLINE,
    NETWORK_NOT_ALLOWED,
    POWER_SAVE,
    STORAGE_LOW,
    MEMORY_LOW,
    THERMAL,
}

data class PreloadDecision(
    val allowed: Boolean,
    val blockedReason: PreloadBlockedReason? = null,
)

object VideoPreloadPolicy {
    fun evaluate(
        signals: DevicePreloadSignals,
        mobileDataEnabled: Boolean,
    ): PreloadDecision {
        if (signals.network == NetworkTransport.OFFLINE) {
            return PreloadDecision(false, PreloadBlockedReason.OFFLINE)
        }
        if (signals.isStorageLow) {
            return PreloadDecision(false, PreloadBlockedReason.STORAGE_LOW)
        }
        if (signals.isMemoryLow) {
            return PreloadDecision(false, PreloadBlockedReason.MEMORY_LOW)
        }
        if (signals.isPowerSaveMode) {
            return PreloadDecision(false, PreloadBlockedReason.POWER_SAVE)
        }
        if (signals.thermalState >= DeviceThermalState.MODERATE) {
            return PreloadDecision(false, PreloadBlockedReason.THERMAL)
        }
        return when (signals.network) {
            NetworkTransport.WIFI -> if (!signals.isMetered || mobileDataEnabled) {
                PreloadDecision(true)
            } else {
                PreloadDecision(false, PreloadBlockedReason.NETWORK_NOT_ALLOWED)
            }
            NetworkTransport.MOBILE -> if (mobileDataEnabled) {
                PreloadDecision(true)
            } else {
                PreloadDecision(false, PreloadBlockedReason.NETWORK_NOT_ALLOWED)
            }
            NetworkTransport.OTHER ->
                PreloadDecision(false, PreloadBlockedReason.NETWORK_NOT_ALLOWED)
            NetworkTransport.OFFLINE -> PreloadDecision(false, PreloadBlockedReason.OFFLINE)
        }
    }
}

enum class AdaptivePreloadState {
    OFF,
    CONSERVATIVE,
    NORMAL,
}

enum class AdaptivePreloadReason {
    OFFLINE,
    NETWORK_NOT_ALLOWED,
    POWER_SAVE,
    STORAGE_LOW,
    MEMORY_LOW,
    THERMAL,
    NETWORK_CHANGED,
    CURRENT_NOT_STABLE,
    CONSECUTIVE_FAILURES,
    REBUFFER,
    RECENT_FIRST_FRAME_TAIL,
    CACHE_MISS,
    METERED_NETWORK,
    RECOVERING,
    STABLE,
}

data class AdaptivePreloadEnvironment(
    val signals: DevicePreloadSignals,
    val mobileDataEnabled: Boolean,
    /** Observed to keep policy decisions aligned with the user's choice; never rewritten here. */
    val qualityPreference: VideoQualityPreference,
)

data class AdaptivePreloadDecision(
    val state: AdaptivePreloadState,
    val reason: AdaptivePreloadReason,
    val maxPreloadBytes: Long,
    val recentSampleCount: Int,
    val recentP90Millis: Long?,
    /** Safe network class only; never contains an SSID, address, or other network identity. */
    val isUnmeteredWifi: Boolean = false,
    /** User choice propagated to every preloader, including the standby player. */
    val mobileDataPreloadEnabled: Boolean = false,
)

interface AdaptivePreloadController {
    val decision: StateFlow<AdaptivePreloadDecision>

    fun onCurrentBind(cacheHit: Boolean)

    fun onFirstFrame(bindToFirstFrameMillis: Long)

    fun onPlaybackFailure()

    fun onRebufferStarted()

    fun onRebufferRecovered()

    fun onCurrentReleased()
}

/**
 * Small, deterministic in-memory policy for the single next-video range.
 *
 * Degradation is immediate. Recovery from OFF/CONSERVATIVE to NORMAL needs two consecutive
 * normal-eligible first frames, while a cache miss needs three recent fast samples before it can
 * begin that recovery. No device/account/content identity is retained.
 */
class AdaptivePreloadPolicyStateMachine(
    initialEnvironment: AdaptivePreloadEnvironment,
) {
    private var environment = initialEnvironment
    private val recentFirstFrameMillis = ArrayDeque<Long>()
    private var currentHasFirstFrame = false
    private var currentCacheHit = false
    private var failureStreak = 0
    private var rebuffering = false
    private var networkChangePending = false
    private var normalEligibilityStreak = 0

    var decision: AdaptivePreloadDecision = evaluate()
        private set

    fun updateEnvironment(next: AdaptivePreloadEnvironment): AdaptivePreloadDecision {
        val previousSignals = environment.signals
        val nextSignals = next.signals
        val networkChanged = previousSignals.networkGeneration != nextSignals.networkGeneration ||
            previousSignals.network != nextSignals.network ||
            previousSignals.isMetered != nextSignals.isMetered
        environment = next
        if (networkChanged) {
            networkChangePending = true
            currentHasFirstFrame = false
            recentFirstFrameMillis.clear()
            failureStreak = 0
            normalEligibilityStreak = 0
        }
        return updateDecision(resetRecoveryOnHardBlock = true)
    }

    fun onCurrentBind(cacheHit: Boolean): AdaptivePreloadDecision {
        currentHasFirstFrame = false
        currentCacheHit = cacheHit
        rebuffering = false
        return updateDecision()
    }

    fun onFirstFrame(bindToFirstFrameMillis: Long): AdaptivePreloadDecision {
        currentHasFirstFrame = true
        rebuffering = false
        networkChangePending = false
        failureStreak = 0
        recentFirstFrameMillis.addLast(bindToFirstFrameMillis.coerceAtLeast(0L))
        while (recentFirstFrameMillis.size > RECENT_SAMPLE_LIMIT) {
            recentFirstFrameMillis.removeFirst()
        }
        if (isNormalEligible()) {
            normalEligibilityStreak += 1
        } else {
            normalEligibilityStreak = 0
        }
        return updateDecision()
    }

    fun onPlaybackFailure(): AdaptivePreloadDecision {
        failureStreak += 1
        currentHasFirstFrame = false
        rebuffering = false
        normalEligibilityStreak = 0
        return updateDecision()
    }

    fun onRebufferStarted(): AdaptivePreloadDecision {
        rebuffering = true
        normalEligibilityStreak = 0
        return updateDecision()
    }

    fun onRebufferRecovered(): AdaptivePreloadDecision {
        rebuffering = false
        currentHasFirstFrame = true
        return updateDecision()
    }

    fun onCurrentReleased(): AdaptivePreloadDecision {
        currentHasFirstFrame = false
        rebuffering = false
        return updateDecision()
    }

    private fun updateDecision(resetRecoveryOnHardBlock: Boolean = false): AdaptivePreloadDecision {
        decision = evaluate()
        if (resetRecoveryOnHardBlock && decision.state == AdaptivePreloadState.OFF) {
            normalEligibilityStreak = 0
            decision = evaluate()
        }
        return decision
    }

    private fun evaluate(): AdaptivePreloadDecision {
        val hardReason = hardBlockedReason()
        if (hardReason != null) return decision(AdaptivePreloadState.OFF, hardReason)
        // "Current has not rendered a first frame yet" degrades to the bounded conservative
        // reserve instead of a hard block. Device evidence: a slow high-bitrate video can wait
        // many seconds for its own startup reserve, and a hard block for that whole window
        // meant the next item received zero preload bytes, so the following swipe was always a
        // cold start. The conservative reserve is a single 256 KiB chunk whose TDLib priority
        // (NEXT_PRELOAD) is below every current-playback priority, so it only consumes link
        // capacity the playback stream is not using.
        if (!currentHasFirstFrame) {
            return decision(
                AdaptivePreloadState.CONSERVATIVE,
                AdaptivePreloadReason.CURRENT_NOT_STABLE,
            )
        }
        val recentP90 = recentP90Millis()
        if (recentP90 != null && recentP90 > SLOW_FIRST_FRAME_P90_MILLIS) {
            return decision(
                AdaptivePreloadState.CONSERVATIVE,
                AdaptivePreloadReason.RECENT_FIRST_FRAME_TAIL,
            )
        }
        if (environment.signals.network == NetworkTransport.MOBILE || environment.signals.isMetered) {
            return decision(
                AdaptivePreloadState.CONSERVATIVE,
                AdaptivePreloadReason.METERED_NETWORK,
            )
        }
        if (!currentCacheHit && recentFirstFrameMillis.size < MIN_FAST_CACHE_MISS_SAMPLES) {
            return decision(
                AdaptivePreloadState.CONSERVATIVE,
                AdaptivePreloadReason.CACHE_MISS,
            )
        }
        return if (normalEligibilityStreak >= NORMAL_RECOVERY_STREAK) {
            decision(AdaptivePreloadState.NORMAL, AdaptivePreloadReason.STABLE)
        } else {
            decision(AdaptivePreloadState.CONSERVATIVE, AdaptivePreloadReason.RECOVERING)
        }
    }

    private fun hardBlockedReason(): AdaptivePreloadReason? {
        val signals = environment.signals
        if (signals.network == NetworkTransport.OFFLINE) return AdaptivePreloadReason.OFFLINE
        if (signals.isStorageLow) return AdaptivePreloadReason.STORAGE_LOW
        if (signals.isMemoryLow) return AdaptivePreloadReason.MEMORY_LOW
        if (signals.isPowerSaveMode) return AdaptivePreloadReason.POWER_SAVE
        if (signals.thermalState >= DeviceThermalState.MODERATE) {
            return AdaptivePreloadReason.THERMAL
        }
        if (
            signals.network == NetworkTransport.OTHER ||
            ((signals.network == NetworkTransport.MOBILE || signals.isMetered) &&
                !environment.mobileDataEnabled)
        ) {
            return AdaptivePreloadReason.NETWORK_NOT_ALLOWED
        }
        if (failureStreak >= CONSECUTIVE_FAILURE_LIMIT) {
            return AdaptivePreloadReason.CONSECUTIVE_FAILURES
        }
        if (networkChangePending) return AdaptivePreloadReason.NETWORK_CHANGED
        if (rebuffering) return AdaptivePreloadReason.REBUFFER
        return null
    }

    private fun isNormalEligible(): Boolean {
        val recentP90 = recentP90Millis() ?: return false
        if (recentP90 > SLOW_FIRST_FRAME_P90_MILLIS) return false
        if (environment.signals.network != NetworkTransport.WIFI) return false
        if (environment.signals.isMetered) return false
        return currentCacheHit || recentFirstFrameMillis.size >= MIN_FAST_CACHE_MISS_SAMPLES
    }

    private fun recentP90Millis(): Long? {
        if (recentFirstFrameMillis.isEmpty()) return null
        val sorted = recentFirstFrameMillis.sorted()
        val rank = kotlin.math.ceil(sorted.size * 0.90).toInt().coerceAtLeast(1)
        return sorted[rank - 1]
    }

    private fun decision(
        state: AdaptivePreloadState,
        reason: AdaptivePreloadReason,
    ) = AdaptivePreloadDecision(
        state = state,
        reason = reason,
        maxPreloadBytes = when (state) {
            AdaptivePreloadState.OFF -> 0L
            AdaptivePreloadState.CONSERVATIVE -> CONSERVATIVE_PRELOAD_BYTES
            AdaptivePreloadState.NORMAL -> NORMAL_PRELOAD_BYTES
        },
        recentSampleCount = recentFirstFrameMillis.size,
        recentP90Millis = recentP90Millis(),
        isUnmeteredWifi = environment.signals.network == NetworkTransport.WIFI &&
            !environment.signals.isMetered,
        mobileDataPreloadEnabled = environment.mobileDataEnabled,
    )

    companion object {
        // Real-device 64/128 KiB candidates regressed natural-cold first-frame completion.
        // Conservative therefore keeps the already-proven single-next 256 KiB ceiling; its
        // safety comes from OFF gating and hysteresis, not from an unproven smaller prefix.
        const val CONSERVATIVE_PRELOAD_BYTES = 256L * 1024L
        const val NORMAL_PRELOAD_BYTES = 256L * 1024L
        const val SLOW_FIRST_FRAME_P90_MILLIS = 650L
        private const val RECENT_SAMPLE_LIMIT = 5
        private const val MIN_FAST_CACHE_MISS_SAMPLES = 3
        private const val NORMAL_RECOVERY_STREAK = 2
        private const val CONSECUTIVE_FAILURE_LIMIT = 2
    }
}

enum class PreloadOwnerHandoffPhase {
    IDLE,
    NEXT_WARMING,
    TARGET_PENDING,
    TARGET_COMMITTED,
    SHARED_WITH_CURRENT,
    ABANDONED,
    CANCELLED,
    RELEASED,
}

/**
 * Sanitized, in-memory single source of truth for the one speculative file owner.
 *
 * It intentionally exposes neither the TDLib lease/token nor a local path. The generation is
 * monotonic inside one process and lets tests reject late target/current callbacks.
 */
data class PreloadOwnerHandoffSnapshot(
    val phase: PreloadOwnerHandoffPhase = PreloadOwnerHandoffPhase.IDLE,
    val generation: Long = 0L,
    val key: VideoKey? = null,
    val fileId: Int? = null,
    val hasSpeculativeOwner: Boolean = false,
    val promotionAttempt: Boolean = false,
    val promotionMatched: Boolean = false,
    val reusedActiveRequest: Boolean? = null,
    val cancelledBeforeCurrentAcquire: Boolean? = null,
    val handoffMillis: Long? = null,
)

interface VideoPreloadController {
    val ownerHandoff: StateFlow<PreloadOwnerHandoffSnapshot>

    fun setNextVideo(video: IndexedVideo?)

    fun updateCurrentPlaybackSafety(snapshot: NextPreloadSafetySnapshot) = Unit

    fun currentBudgetDecision(): NextPreloadBudgetDecision? = null

    /**
     * New-network bytes already charged to *this exact target* by this controller, including
     * requests that were cancelled before they completed.
     *
     * The single next target can be served first by the lightweight byte stage and then by the
     * playback pool. Without this hand-over the pool would start a fresh budget for the same
     * target and could spend its full ceiling on top of the bytes the byte stage already asked
     * for, breaking both the per-target request ceiling and "cancelling does not refund".
     * Implementations that never share a target return 0.
     */
    fun requestedBytesFor(video: IndexedVideo): Long = 0L

    fun beginTargetPromotion()

    fun commitTargetPromotion(video: IndexedVideo)

    fun abandonTargetPromotion()

    /** Preserve only an exact committed key/fileId while Media3 is about to acquire current. */
    fun onCurrentPlaybackStarting(video: IndexedVideo)

    /** Called only after a CURRENT_* Telegram range lease has been inserted successfully. */
    fun onCurrentPlaybackRangeAcquired(video: IndexedVideo)

    fun onCurrentPlaybackRangeAcquireFailed(video: IndexedVideo)

    fun stop()
}
