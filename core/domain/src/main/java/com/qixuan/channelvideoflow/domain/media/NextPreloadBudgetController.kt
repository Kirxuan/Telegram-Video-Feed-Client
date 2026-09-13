package com.qixuan.channelvideoflow.domain.media

import kotlin.math.ceil

enum class NextPreloadBudgetTier(val ceilingBytes: Long) {
    BLOCKED(0L),
    METADATA_ONLY(0L),

    /**
     * Flat byte ceiling for the decoder-free next-item byte prefix while the current stream has not
     * rendered its first frame yet. It is deliberately flat instead of "seconds x bitrate": the
     * only job of this stage is to cover the swipe's TTFB, and a flat ceiling cannot grow with an
     * unknown or optimistic bitrate estimate. The heavy pool standby never receives this tier.
     *
     * The ordinal must stay below [TWO_MIB] so that every `>= TWO_MIB` comparison keeps meaning
     * "the pool may prepare a real reserve".
     */
    CONSERVATIVE_STARTUP(256L * 1024L),
    TWO_MIB(2L * 1024L * 1024L),
    FIVE_MIB(5L * 1024L * 1024L),
    TEN_MIB(10L * 1024L * 1024L),
    TWENTY_MIB(20L * 1024L * 1024L),
}

enum class NextPreloadStopReason {
    NONE,
    STARTUP_SEEK_REBUFFER,
    CURRENT_BUFFER_LOW,
    BUFFER_FALLING,
    METERED_DEFAULT_DISABLED,
    DEVICE_PRESSURE,
    METADATA_ONLY,
    UNRELIABLE_BITRATE,
    SEGMENT_EXCEEDS_TIER,
    TARGET_REACHED,
    HARD_LIMIT,
    TARGET_CHANGED,

    /**
     * The adaptive preload policy is in its hard-block state (offline, network not allowed, power
     * save, thermal, storage, memory, network change, consecutive failures or rebuffer). The safety
     * snapshot cannot express all of those on its own, so the owning preloader reports it here.
     */
    ADAPTIVE_HARD_BLOCK,
}

data class NextPreloadSafetySnapshot(
    val playbackState: PlaybackRiskState = PlaybackRiskState.STARTUP,
    val currentBufferedSeconds: Double = 0.0,
    val bufferSlopeSecondsPerSecond: Double = 0.0,
    val fastThroughputBitsPerSecond: Long? = null,
    val slowThroughputBitsPerSecond: Long? = null,
    val timeToFirstByteP90Millis: Long? = null,
    val isMetered: Boolean = true,
    val isMobileNetwork: Boolean = false,
    val isPowerSaver: Boolean = false,
    val hasThermalPressure: Boolean = false,
    val hasStoragePressure: Boolean = false,
    val networkGeneration: Long = 0L,
    /** True only when the player has buffered the entire remaining timeline. */
    val remainingTimelineBuffered: Boolean = false,
    val hasMemoryPressure: Boolean = false,
    val mobileDataPreloadEnabled: Boolean = false,
    /**
     * Retention only. A preparation that already owns bytes keeps its budget through a short
     * buffer dip so the bytes already spent are not wasted; a *new* preparation additionally
     * requires a non-falling buffer. Both share one admission floor, because the byte ceiling and
     * the TDLib NEXT_PRELOAD priority already keep the current stream ahead of the standby.
     */
    val standbyPreparationActive: Boolean = false,
)

data class HlsPlayableBoundary(
    val playableSeconds: Double,
    val requiredEndOffsetBytes: Long,
)

data class NextPreloadBudgetInput(
    val safety: NextPreloadSafetySnapshot,
    val peakBitrateBitsPerSecond: Long?,
    val cachedCoveredBytes: Long,
    val requestedUncachedBytes: Long,
    val hlsBoundaries: List<HlsPlayableBoundary> = emptyList(),
    /**
     * True only for the decoder-free byte preloader (`VideoPreloadManager`), which owns the single
     * next target and never binds a second ExoPlayer. When true, a current stream that has not yet
     * rendered its first frame degrades to [NextPreloadBudgetTier.CONSERVATIVE_STARTUP] instead of a
     * hard block, so the bounded 256 KiB TTFB prefix documented for that window can actually be
     * requested. The pool standby leaves this false, so its "PLAYING plus 3 s buffered" admission is
     * untouched.
     */
    val lightweightStartupPrefetch: Boolean = false,
    /**
     * The adaptive preload policy currently reports a hard block (offline, network not allowed,
     * power save, thermal, storage, memory, network change, consecutive failures or rebuffer).
     *
     * [NextPreloadSafetySnapshot] cannot carry every one of those — it has no offline field, and a
     * metered opt-in makes its metered gate pass — so the caller that owns the policy decision must
     * report it here. A hard block outranks every allowance, including the startup byte prefix:
     * otherwise a device with no link but a stored "mobile preload on" preference would still ask
     * TDLib for bytes.
     */
    val hasAdaptiveHardBlock: Boolean = false,
)

data class NextPreloadBudgetDecision(
    val calculatedTargetSeconds: Double,
    val calculatedTargetBytes: Long,
    val allowedBudgetTier: NextPreloadBudgetTier,
    val remainingNewNetworkBudgetBytes: Long,
    val cachedCoveredBytes: Long,
    val requestedUncachedBytes: Long,
    val canceledBytes: Long = 0L,
    val skippedNextWastedBytes: Long = 0L,
    val currentBufferedSeconds: Double,
    val bufferSlopeSecondsPerSecond: Double,
    val predictedCompletionMillis: Long?,
    val starvationDeadlineMillis: Long,
    val preloadStopReason: NextPreloadStopReason,
)

/**
 * Duration-first budget: the target is a number of **seconds** and the byte count is derived from
 * the media bitrate, capped by a non-negotiable 20 MiB new-network ceiling.
 *
 * Rationale (see docs/STAGE27_GITHUB_LOADING_RESEARCH.md): Media3's own preloading API expresses
 * its target as `PreloadStatus.specifiedRangeLoaded(durationMs)`, Telegram X derives its download
 * limit from "seconds x bitrate", and mpv limits network read-ahead by seconds *and* bytes.
 * A seconds target therefore self-scales: a 480p standby costs a few hundred KiB for five seconds
 * while a 4K original is allowed to spend up to the ceiling for the same wall-clock reserve.
 */
object NextPreloadBudgetController {
    const val ABSOLUTE_MAX_BYTES = 20L * 1024L * 1024L
    const val MIN_PROGRESSIVE_BYTES = 256L * 1024L
    const val RANGE_CHUNK_BYTES = 512L * 1024L

    /** The reserve the next target should have ready before the user swipes to it. */
    const val STANDBY_TARGET_SECONDS = 5.0

    /**
     * A standby may start once the current stream has this many seconds buffered ahead. The old
     * 8 s floor made "READY hits" impossible on a metered link: Media3 fills toward a 50 s min
     * buffer, so waiting for 8 s spent most of the user's viewing time doing nothing.
     */
    const val STANDBY_ADMISSION_MIN_CURRENT_SECONDS = 3.0

    /** Retention floor for a preparation that already owns bytes. */
    const val STANDBY_RETENTION_MIN_CURRENT_SECONDS = 3.0

    fun evaluate(input: NextPreloadBudgetInput): NextPreloadBudgetDecision {
        val tierAndSeconds = tier(input)
        val tier = tierAndSeconds.first
        val targetSeconds = tierAndSeconds.second
        val deadline = ((input.safety.currentBufferedSeconds - 1.2).coerceAtLeast(0.0) * 1_000.0).toLong()
        val hardConsumed = input.requestedUncachedBytes.coerceAtLeast(0L)
        val stop = blockedReason(input, tier)
        if (tier.ceilingBytes == 0L) {
            return decision(input, targetSeconds, 0L, tier, 0L, deadline, stop)
        }
        val progressiveTarget = input.peakBitrateBitsPerSecond
            ?.takeIf { it > 0L }
            ?.let { bitrate ->
                ceil(bitrate.toDouble() * targetSeconds / 8.0 * 1.25).toLong()
                    .coerceIn(MIN_PROGRESSIVE_BYTES, minOf(tier.ceilingBytes, ABSOLUTE_MAX_BYTES))
            }
        val hlsTarget = input.hlsBoundaries
            .sortedBy(HlsPlayableBoundary::playableSeconds)
            .firstOrNull { boundary ->
                boundary.playableSeconds >= targetSeconds &&
                    boundary.requiredEndOffsetBytes in 1..minOf(tier.ceilingBytes, ABSOLUTE_MAX_BYTES)
            }
            ?.requiredEndOffsetBytes
        val target = when {
            input.hlsBoundaries.isNotEmpty() && hlsTarget == null -> 0L
            hlsTarget != null -> hlsTarget
            progressiveTarget != null -> progressiveTarget
            else -> MIN_PROGRESSIVE_BYTES.coerceAtMost(tier.ceilingBytes)
        }.coerceAtMost(ABSOLUTE_MAX_BYTES)
        val covered = input.cachedCoveredBytes.coerceIn(0L, target)
        val allowedNewTotal = (target - covered).coerceAtLeast(0L)
            .coerceAtMost(ABSOLUTE_MAX_BYTES)
        val remaining = (allowedNewTotal - hardConsumed).coerceAtLeast(0L)
        val completion = predictedCompletionMillis(
            remaining,
            listOfNotNull(
                input.safety.fastThroughputBitsPerSecond,
                input.safety.slowThroughputBitsPerSecond,
            ).minOrNull(),
            input.safety.timeToFirstByteP90Millis,
        )
        val finalStop = when {
            target == 0L && input.hlsBoundaries.isNotEmpty() -> NextPreloadStopReason.SEGMENT_EXCEEDS_TIER
            input.peakBitrateBitsPerSecond == null && input.hlsBoundaries.isEmpty() ->
                NextPreloadStopReason.UNRELIABLE_BITRATE
            remaining == 0L && hardConsumed >= ABSOLUTE_MAX_BYTES -> NextPreloadStopReason.HARD_LIMIT
            remaining == 0L -> NextPreloadStopReason.TARGET_REACHED
            else -> NextPreloadStopReason.NONE
        }
        return decision(input, targetSeconds, target, tier, remaining, deadline, finalStop, completion)
    }

    private fun tier(input: NextPreloadBudgetInput): Pair<NextPreloadBudgetTier, Double> {
        val safety = input.safety
        return when {
            // The adaptive policy's own hard block is the strongest statement available: it can
            // say "offline" and "network changed", which the safety snapshot cannot express, and it
            // already folded in the user's device and metered preferences.
            input.hasAdaptiveHardBlock -> NextPreloadBudgetTier.BLOCKED to 0.0
            // Hard blocks are evaluated before the startup allowance: a constrained device or a
            // link the user has not opted into can never be talked into spending bytes by the
            // lightweight stage. Only the tier outcome changed (BLOCKED stays BLOCKED); the
            // reported reason is produced by [blockedReason] with the same ordering.
            safety.isPowerSaver || safety.hasThermalPressure || safety.hasStoragePressure || safety.hasMemoryPressure ->
                NextPreloadBudgetTier.BLOCKED to 0.0
            (safety.isMobileNetwork || safety.isMetered) && !safety.mobileDataPreloadEnabled ->
                NextPreloadBudgetTier.BLOCKED to 0.0
            safety.playbackState == PlaybackRiskState.STARTUP && input.lightweightStartupPrefetch ->
                NextPreloadBudgetTier.CONSERVATIVE_STARTUP to 0.0
            // SEEK and REBUFFER stay hard-blocked for every caller: the current stream owns the
            // link until it is playing again.
            safety.playbackState != PlaybackRiskState.PLAYING -> NextPreloadBudgetTier.BLOCKED to 0.0
            safety.remainingTimelineBuffered -> reservoirTier(safety) to STANDBY_TARGET_SECONDS
        // Retention comes before the slope guard: an in-flight preparation is not cancelled by a
        // per-chunk buffer dip, which would throw away the bytes it already paid for.
        safety.standbyPreparationActive &&
            safety.currentBufferedSeconds >= STANDBY_RETENTION_MIN_CURRENT_SECONDS ->
            reservoirTier(safety) to STANDBY_TARGET_SECONDS
        safety.currentBufferedSeconds < STANDBY_ADMISSION_MIN_CURRENT_SECONDS ->
            NextPreloadBudgetTier.BLOCKED to 0.0
        safety.bufferSlopeSecondsPerSecond < -0.05 -> NextPreloadBudgetTier.BLOCKED to 0.0
        // Duration is the target; the ceiling accommodates high bitrate without requiring 20 MiB.
        safety.isMobileNetwork || safety.isMetered -> NextPreloadBudgetTier.TWENTY_MIB to STANDBY_TARGET_SECONDS
        safety.currentBufferedSeconds < 15.0 -> NextPreloadBudgetTier.METADATA_ONLY to 0.0
        safety.currentBufferedSeconds < 25.0 -> NextPreloadBudgetTier.TWO_MIB to STANDBY_TARGET_SECONDS
        safety.currentBufferedSeconds < 35.0 -> NextPreloadBudgetTier.FIVE_MIB to STANDBY_TARGET_SECONDS
            reliable(safety) -> NextPreloadBudgetTier.TWENTY_MIB to STANDBY_TARGET_SECONDS
            else -> NextPreloadBudgetTier.TEN_MIB to STANDBY_TARGET_SECONDS
        }
    }

    private fun reservoirTier(safety: NextPreloadSafetySnapshot) =
        if (safety.isMobileNetwork || safety.isMetered) NextPreloadBudgetTier.TWENTY_MIB
        else NextPreloadBudgetTier.TWO_MIB

    private fun reliable(safety: NextPreloadSafetySnapshot): Boolean {
        val fast = safety.fastThroughputBitsPerSecond ?: return false
        val slow = safety.slowThroughputBitsPerSecond ?: return false
        val ratio = minOf(fast, slow).toDouble() / maxOf(fast, slow).coerceAtLeast(1L)
        return ratio >= 0.70 && safety.bufferSlopeSecondsPerSecond >= 0.0
    }

    private fun blockedReason(
        input: NextPreloadBudgetInput,
        tier: NextPreloadBudgetTier,
    ): NextPreloadStopReason {
        val safety = input.safety
        return when {
            input.hasAdaptiveHardBlock -> NextPreloadStopReason.ADAPTIVE_HARD_BLOCK
            safety.isPowerSaver || safety.hasThermalPressure || safety.hasStoragePressure || safety.hasMemoryPressure ->
                NextPreloadStopReason.DEVICE_PRESSURE
            (safety.isMobileNetwork || safety.isMetered) && !safety.mobileDataPreloadEnabled ->
                NextPreloadStopReason.METERED_DEFAULT_DISABLED
            tier == NextPreloadBudgetTier.CONSERVATIVE_STARTUP -> NextPreloadStopReason.NONE
            safety.playbackState != PlaybackRiskState.PLAYING ->
                NextPreloadStopReason.STARTUP_SEEK_REBUFFER
            safety.remainingTimelineBuffered -> NextPreloadStopReason.NONE
            safety.standbyPreparationActive &&
                safety.currentBufferedSeconds >= STANDBY_RETENTION_MIN_CURRENT_SECONDS -> NextPreloadStopReason.NONE
            safety.currentBufferedSeconds < STANDBY_ADMISSION_MIN_CURRENT_SECONDS ->
                NextPreloadStopReason.CURRENT_BUFFER_LOW
            safety.bufferSlopeSecondsPerSecond < -0.05 -> NextPreloadStopReason.BUFFER_FALLING
            tier == NextPreloadBudgetTier.METADATA_ONLY -> NextPreloadStopReason.METADATA_ONLY
            else -> NextPreloadStopReason.NONE
        }
    }

    private fun predictedCompletionMillis(bytes: Long, throughput: Long?, ttfb: Long?): Long? {
        if (bytes <= 0L) return 0L
        throughput?.takeIf { it > 0L } ?: return null
        return ceil(bytes.toDouble() * 8_000.0 / throughput.toDouble()).toLong()
            .saturatedAdd(ttfb ?: 0L)
    }

    private fun decision(
        input: NextPreloadBudgetInput,
        seconds: Double,
        bytes: Long,
        tier: NextPreloadBudgetTier,
        remaining: Long,
        deadline: Long,
        reason: NextPreloadStopReason,
        completion: Long? = null,
    ) = NextPreloadBudgetDecision(
        calculatedTargetSeconds = seconds,
        calculatedTargetBytes = bytes,
        allowedBudgetTier = tier,
        remainingNewNetworkBudgetBytes = remaining.coerceAtMost(ABSOLUTE_MAX_BYTES),
        cachedCoveredBytes = input.cachedCoveredBytes.coerceAtLeast(0L),
        requestedUncachedBytes = input.requestedUncachedBytes.coerceIn(0L, ABSOLUTE_MAX_BYTES),
        canceledBytes = 0L,
        skippedNextWastedBytes = 0L,
        currentBufferedSeconds = input.safety.currentBufferedSeconds,
        bufferSlopeSecondsPerSecond = input.safety.bufferSlopeSecondsPerSecond,
        predictedCompletionMillis = completion,
        starvationDeadlineMillis = deadline,
        preloadStopReason = reason,
    )

    private fun Long.saturatedAdd(value: Long): Long =
        if (this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value
}
