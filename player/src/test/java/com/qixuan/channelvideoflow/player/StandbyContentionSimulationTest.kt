package com.qixuan.channelvideoflow.player

import java.util.Random
import kotlin.math.ceil
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic bandwidth-contention model for the bounded next item.
 *
 * Why this exists: the Stage 18 simulation decides `isPrepared` from a cache-mode enum and charges
 * the standby download against the whole link, so it cannot express the mechanism measured on the
 * device. There, the current stream runs at a much higher TDLib priority and keeps requesting until
 * its own forward target is met, so the next item accumulates almost nothing inside the window
 * between "admitted" and "user swipes". Three 20-swipe device rounds showed exactly that, but the
 * media and network window differ between rounds, so the device data cannot separate cause from
 * variance.
 *
 * This model removes that confound: identical media, identical network trace, identical seeds, one
 * variable at a time. It is a policy model, not a device measurement. TDLib does not document its
 * exact arbitration, so the scheduler is a parameter and a candidate only ships if it wins under
 * every plausible policy.
 */
class StandbyContentionSimulationTest {
    @Test
    fun cappingTheCurrentReserveWhileANextItemWaitsWinsUnderEverySharingPolicy() {
        val baseline = simulateAll(Candidate.BASELINE)
        val capped = simulateAll(Candidate.CAPPED_20S)

        SharingPolicy.entries.forEach { policy ->
            LinkProfile.entries.forEach { link ->
                val before = baseline.getValue(policy).getValue(link)
                val after = capped.getValue(policy).getValue(link)
                println(
                    "CONTENTION policy=$policy link=${link.label} " +
                        "prepared=${before.preparedProbability.pct()}->${after.preparedProbability.pct()} " +
                        "swapInP95=${before.swapInP95.round1()}->${after.swapInP95.round1()} " +
                        "activeReserveP50=${before.activeReserveP50.round1()}->${after.activeReserveP50.round1()} " +
                        "standbyKiB=${before.standbyKib}->${after.standbyKib}",
                )
                assertTrue(
                    "A capped current reserve must not reduce the next item's chance of being ready " +
                        "($policy/${link.label})",
                    after.preparedProbability >= before.preparedProbability,
                )
                // The cap may only cost reserve that the uncapped link had already banked above the
                // rebuffer threshold *plus a margin*. A bare threshold is not enough: the reserve
                // measured at swap time is exactly what has to absorb the handoff and any stall
                // immediately after it, so a ceiling that lands on the threshold has spent the whole
                // safety margin. Where the link cannot even bank that much, the cap does not bind
                // and must change nothing.
                assertTrue(
                    "The cap must not spend the current stream's rebuffer safety " +
                        "($policy/${link.label})",
                    after.activeReserveP50 + EPSILON >=
                        minOf(before.activeReserveP50, REQUIRED_ACTIVE_RESERVE_SECONDS),
                )
                assertTrue(
                    "Waste must stay bounded ($policy/${link.label})",
                    after.standbyKib <= Candidate.CAPPED_20S.standbyTargetSeconds *
                        MEDIA.standbyBitsPerSecond / 8.0 / 1024.0 * 1.35,
                )
            }
        }

        // Strict priority is the policy the device evidence matches; it must show a real
        // improvement rather than a rounding-level one.
        val strictBefore = baseline.getValue(SharingPolicy.STRICT_PRIORITY).getValue(LinkProfile.GOOD_4G)
        val strictAfter = capped.getValue(SharingPolicy.STRICT_PRIORITY).getValue(LinkProfile.GOOD_4G)
        assertTrue(
            "Under strict priority the next item must end the five second window prepared",
            strictAfter.preparedProbability >= 0.9,
        )
        assertTrue(
            "The measured device profile must move, not just tie",
            strictAfter.preparedProbability - strictBefore.preparedProbability >= 0.5,
        )
        // And on the fair profile, where the current cannot even bank the cap inside the window,
        // the cap must be inert rather than harmful.
        val fairBefore = baseline.getValue(SharingPolicy.STRICT_PRIORITY).getValue(LinkProfile.FAIR_4G)
        val fairAfter = capped.getValue(SharingPolicy.STRICT_PRIORITY).getValue(LinkProfile.FAIR_4G)
        assertTrue(fairAfter.activeReserveP50 + EPSILON >= fairBefore.activeReserveP50)
    }

    @Test
    fun aReserveAtTheRebufferThresholdIsRejectedEvenThoughItPreparesMore() {
        val aggressive = simulateAll(Candidate.CAPPED_12S)
        val before = simulateAll(Candidate.BASELINE)
            .getValue(SharingPolicy.STRICT_PRIORITY).getValue(LinkProfile.GOOD_4G)
        val strict = aggressive.getValue(SharingPolicy.STRICT_PRIORITY).getValue(LinkProfile.GOOD_4G)
        println(
            "CONTENTION rejected candidate name=${Candidate.CAPPED_12S.name} " +
                "prepared=${strict.preparedProbability.pct()} reserveP50=${strict.activeReserveP50.round1()} " +
                "baselineReserveP50=${before.activeReserveP50.round1()}",
        )
        // It prepares at least as much as the shipped candidate, which is exactly why a safety gate
        // has to exist rather than a pure throughput comparison.
        assertTrue(strict.preparedProbability >= 0.9)
        // ...but it lands the current stream on the rebuffer threshold instead of above it, spending
        // the whole margin the handoff depends on. Rejected.
        assertTrue(
            "A ceiling that lands on the rebuffer threshold must be rejected as unsafe",
            strict.activeReserveP50 < REQUIRED_ACTIVE_RESERVE_SECONDS,
        )
    }

    @Test
    fun raisingTheStandbyShareAloneCannotFixStrictPriority() {
        val boosted = simulateAll(Candidate.PRIORITY_BOOST)
        val strict = boosted.getValue(SharingPolicy.STRICT_PRIORITY).getValue(LinkProfile.GOOD_4G)
        println(
            "CONTENTION priorityBoost strictPrepared=${strict.preparedProbability.pct()} " +
                "strictReserveP50=${strict.activeReserveP50.round1()}",
        )
        // Under strict priority the higher weight is never consulted: the current stream is asked
        // first and always has something to ask for, so the next item still waits out the fill.
        assertTrue(strict.preparedProbability < 0.5)
        // Under proportional sharing the same weight does help, so the two levers must not be
        // confused with each other.
        val proportional = boosted.getValue(SharingPolicy.PROPORTIONAL).getValue(LinkProfile.GOOD_4G)
        assertTrue(proportional.preparedProbability > 0.5)
    }

    private fun simulateAll(candidate: Candidate): Map<SharingPolicy, Map<LinkProfile, Outcome>> =
        SharingPolicy.entries.associateWith { policy ->
            LinkProfile.entries.associateWith { link -> simulate(candidate, policy, link) }
        }

    private fun simulate(
        candidate: Candidate,
        policy: SharingPolicy,
        link: LinkProfile,
    ): Outcome {
        val prepared = mutableListOf<Double>()
        val swapIn = mutableListOf<Double>()
        val reserves = mutableListOf<Double>()
        var standbyKib = 0.0
        SEEDS.forEach { seed ->
            val random = Random(seed)
            var activeSeconds = MEDIA.activeStartBufferSeconds
            var standbySeconds = 0.0
            var standbyHeld = false
            var standbyBytes = 0.0
            var elapsed = 0.0
            // The current stream stops at its own time target or at the sample byte ceiling,
            // whichever binds first, exactly like the shipped LoadControl.
            val activeTarget = minOf(
                candidate.activeTargetSeconds,
                ACTIVE_BYTE_CEILING_BYTES * 8.0 / MEDIA.activeBitsPerSecond,
            )
            while (elapsed < WATCH_SECONDS - 1e-9) {
                if (!standbyHeld && activeSeconds >= candidate.admissionSeconds) standbyHeld = true
                val capacity = link.capacityBitsPerSecond *
                    (1.0 + (random.nextDouble() * 2.0 - 1.0) * link.jitter) * TICK_SECONDS
                val activeWants = activeSeconds < activeTarget
                val standbyWants = standbyHeld && standbySeconds < candidate.standbyTargetSeconds
                val activeBits: Double
                val standbyBits: Double
                when {
                    activeWants && standbyWants -> {
                        val activeShare = when (policy) {
                            SharingPolicy.STRICT_PRIORITY -> 1.0
                            SharingPolicy.PROPORTIONAL ->
                                candidate.activeWeight.toDouble() / (candidate.activeWeight + candidate.standbyWeight)
                            SharingPolicy.EQUAL -> 0.5
                        }
                        activeBits = capacity * activeShare
                        standbyBits = capacity * (1.0 - activeShare)
                    }
                    activeWants -> {
                        activeBits = capacity
                        standbyBits = 0.0
                    }
                    standbyWants -> {
                        activeBits = 0.0
                        standbyBits = capacity
                    }
                    else -> {
                        activeBits = 0.0
                        standbyBits = 0.0
                    }
                }
                // The current stream also drains by one second per second while it plays.
                activeSeconds = (activeSeconds + activeBits / MEDIA.activeBitsPerSecond - TICK_SECONDS)
                    .coerceAtLeast(0.0)
                standbySeconds += standbyBits / MEDIA.standbyBitsPerSecond
                standbyBytes += standbyBits / 8.0
                elapsed += TICK_SECONDS
            }
            val ready = standbyHeld && standbySeconds >= candidate.standbyTargetSeconds
            prepared += if (ready) 1.0 else 0.0
            reserves += activeSeconds
            standbyKib += standbyBytes / 1024.0
            swapIn += if (ready) {
                // Promoting an already reserved target: queue swap and decoder wake-up only.
                HANDOFF_MS + random.nextDouble() * 40.0
            } else {
                // No usable reserve: the swap pays the network path for the startup threshold.
                val deficitSeconds =
                    (STARTUP_THRESHOLD_SECONDS - standbySeconds).coerceAtLeast(0.0)
                link.rttMillis +
                    deficitSeconds * MEDIA.standbyBitsPerSecond / link.capacityBitsPerSecond * 1_000.0
            }
        }
        return Outcome(
            preparedProbability = prepared.average(),
            swapInP95 = percentile(swapIn, 0.95),
            activeReserveP50 = percentile(reserves, 0.50),
            standbyKib = (standbyKib / SEEDS.size).toLong(),
        )
    }

    private enum class SharingPolicy { STRICT_PRIORITY, PROPORTIONAL, EQUAL }

    private enum class LinkProfile(
        val label: String,
        val capacityBitsPerSecond: Double,
        val jitter: Double,
        val rttMillis: Double,
    ) {
        GOOD_4G("good4g", 8_000_000.0, 0.15, 60.0),
        FAIR_4G("fair4g", 3_000_000.0, 0.25, 90.0),
        WEAK_4G("weak4g", 1_500_000.0, 0.35, 130.0),
    }

    private data class MediaProfile(
        val activeBitsPerSecond: Double,
        val standbyBitsPerSecond: Double,
        val activeStartBufferSeconds: Double,
    )

    /**
     * `activeTargetSeconds` is the current stream's forward-buffer ceiling while a next item is
     * admitted. The shipped value is effectively unbounded, which is what the device evidence
     * implicates: the current keeps the link until it has banked its full 50 second reserve.
     */
    private enum class Candidate(
        val activeTargetSeconds: Double,
        val standbyTargetSeconds: Double,
        val admissionSeconds: Double,
        val activeWeight: Int,
        val standbyWeight: Int,
    ) {
        BASELINE(50.0, 5.0, 3.0, 24, 8),
        CAPPED_20S(20.0, 5.0, 3.0, 24, 8),
        CAPPED_12S(12.0, 5.0, 3.0, 24, 8),
        PRIORITY_BOOST(50.0, 5.0, 3.0, 24, 16),
    }

    private data class Outcome(
        val preparedProbability: Double,
        val swapInP95: Double,
        val activeReserveP50: Double,
        val standbyKib: Long,
    )

    private fun percentile(values: List<Double>, quantile: Double): Double {
        val sorted = values.sorted()
        return sorted[(ceil(sorted.size * quantile).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    }

    private fun Double.round1(): Double = kotlin.math.round(this * 10.0) / 10.0
    private fun Double.pct(): String = "${kotlin.math.round(this * 1000.0) / 10.0}%"

    private companion object {
        val SEEDS = (1L..24L).toList()
        const val TICK_SECONDS = 0.1
        const val WATCH_SECONDS = 5.0
        const val STARTUP_THRESHOLD_SECONDS = 0.8
        const val HANDOFF_MS = 60.0
        const val REBUFFER_THRESHOLD_SECONDS = 12.0
        /** How far above the rebuffer threshold the current stream must stay after any yield. */
        const val ACTIVE_RESERVE_MARGIN_SECONDS = 4.0
        const val REQUIRED_ACTIVE_RESERVE_SECONDS =
            REBUFFER_THRESHOLD_SECONDS + ACTIVE_RESERVE_MARGIN_SECONDS
        const val EPSILON = 1e-9
        const val ACTIVE_BYTE_CEILING_BYTES = 32.0 * 1024.0 * 1024.0
        val MEDIA = MediaProfile(
            activeBitsPerSecond = 1_200_000.0,
            standbyBitsPerSecond = 600_000.0,
            activeStartBufferSeconds = 1.5,
        )
    }
}
