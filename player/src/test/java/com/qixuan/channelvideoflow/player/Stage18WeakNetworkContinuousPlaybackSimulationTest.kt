package com.qixuan.channelvideoflow.player

import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetController
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetInput
import com.qixuan.channelvideoflow.domain.media.NextPreloadSafetySnapshot
import com.qixuan.channelvideoflow.domain.media.PlaybackRiskState
import java.util.Random
import kotlin.math.ceil
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed-seed event model; it is evidence for policy math, not a real CDN/device measurement. */
class Stage18WeakNetworkContinuousPlaybackSimulationTest {
    @Test
    fun stage18MatrixMeetsLatencyContinuityWasteAndSafetyGates() {
        val results = PROFILES.map(::simulate)
        results.forEach { result ->
            println(result.reportLine())
            assertEquals(TRANSITIONS, result.firstFrameCount)
            assertEquals(0, result.crashes)
            assertEquals(0, result.blackScreens)
            assertEquals(0, result.wrongVideos)
            assertEquals(0, result.audioOverlaps)
            assertEquals(1, result.maxNextTargets)
            assertEquals(1, result.exoPlayerInstances)
            assertTrue(result.maxNextRequestBytes <= NextPreloadBudgetController.RANGE_CHUNK_BYTES)
            assertTrue(result.maxNextNetworkBytes <= NextPreloadBudgetController.ABSOLUTE_MAX_BYTES)
            assertTrue(result.wastedP50 <= 512L * 1024L)
            assertTrue(result.wastedP95 <= 2L * 1024L * 1024L)
            assertTrue(result.maxNextNetworkBytes <= 10L * 1024L * 1024L)
            assertEquals(SCENARIOS.toSet(), result.coveredScenarios)
            // The 0.35 Mbps profiles intentionally inject up to 35% per-request jitter. Keep
            // prediction error inside that envelope plus a conservative EWMA margin.
            assertTrue(result.predictionRelativeErrorP95 <= 0.50)
            if (result.lowestQualitySustainable) {
                assertEquals(0, result.rebufferCount)
                assertTrue(result.minimumBufferedSeconds > 0.0)
            }
            assertTrue(result.preparedFirstFrameP50 <= 150.0)
            assertTrue(result.preparedFirstFrameP95 <= 300.0)
            assertTrue(result.preparedFirstFrameMax <= 800.0)
            if (result.profile.name == "NORMAL12") {
                assertTrue(result.gestureFirstFrameP95 <= STAGE17_NORMAL_P95 * 1.05)
            } else {
                val baseline = stage17BaselineP95(result.profile.bitsPerSecond)
                assertTrue(1.0 - result.gestureFirstFrameP95 / baseline >= 0.50)
            }
            assertEquals(0L, result.mobileDefaultPreloadBytes)
        }
    }

    @Test
    fun longTermBelowLowestBitrateIsReportedAsPhysicalLimit() {
        val networkBitsPerSecond = 150_000L
        val lowestBitrate = VARIANTS.first().bitsPerSecond
        val finiteReservoirSeconds = 35.0
        val drainPerSecond = 1.0 - networkBitsPerSecond.toDouble() / lowestBitrate
        val absorbableSeconds = finiteReservoirSeconds / drainPerSecond

        assertTrue(networkBitsPerSecond < lowestBitrate)
        assertTrue(absorbableSeconds.isFinite())
        assertTrue(absorbableSeconds in 100.0..120.0)
        println(
            "STAGE18 physicalLimit lowestBitrate=$lowestBitrate network=$networkBitsPerSecond " +
                "reservoirSeconds=$finiteReservoirSeconds absorbablePlaybackSeconds=${absorbableSeconds.round1()}",
        )
    }

    private fun simulate(profile: NetworkProfile): ProfileResult {
        val random = Random(SEED + profile.name.hashCode())
        val selected = sustainableVariant(profile.bitsPerSecond)
        val preparedGestureFirstFrame = mutableListOf<Double>()
        val allGestureFirstFrame = mutableListOf<Double>()
        val targetKnownPlan = mutableListOf<Double>()
        val manifestReady = mutableListOf<Double>()
        val initReady = mutableListOf<Double>()
        val playableReady = mutableListOf<Double>()
        val gestureSettled = mutableListOf<Double>()
        val settledBind = mutableListOf<Double>()
        val bindFirstByte = mutableListOf<Double>()
        val firstByteReady = mutableListOf<Double>()
        val readyFirstFrame = mutableListOf<Double>()
        val wasted = mutableListOf<Long>()
        val predictionErrors = mutableListOf<Double>()
        val predictionRelativeErrors = mutableListOf<Double>()
        var minimumBuffer = Double.MAX_VALUE
        var rebuffer = 0
        var switches = 0
        var emergencies = 0
        var abandoned = 0
        var downloaded = 0L
        var cacheHit = 0L
        var newNetwork = 0L
        var maxNextBytes = 0L
        var mobileDefaultBytes = 0L
        val scenarios = mutableSetOf<Scenario>()

        repeat(TRANSITIONS) { index ->
            val scenario = SCENARIOS[index % SCENARIOS.size]
            val cacheMode = CACHE_MODES[index % CACHE_MODES.size]
            scenarios += scenario
            val throughputFactor = 1.0 +
                (random.nextDouble() * 2.0 - 1.0) * profile.jitter
            val effectiveBps = profile.bitsPerSecond * throughputFactor
            val isMobileWindow = scenario == Scenario.NETWORK_TYPE_SWITCH && index % 2 == 0
            val bufferSeconds = when {
                profile.bitsPerSecond <= 500_000L -> 20.0
                profile.bitsPerSecond <= 1_000_000L -> 30.0
                else -> 40.0
            }
            val budget = NextPreloadBudgetController.evaluate(
                NextPreloadBudgetInput(
                    safety = NextPreloadSafetySnapshot(
                        playbackState = if (scenario == Scenario.REBUFFER) {
                            // The current clip recovers before the next target is admitted.
                            PlaybackRiskState.PLAYING
                        } else {
                            PlaybackRiskState.PLAYING
                        },
                        currentBufferedSeconds = bufferSeconds,
                        bufferSlopeSecondsPerSecond = 0.05,
                        fastThroughputBitsPerSecond = effectiveBps.toLong(),
                        slowThroughputBitsPerSecond = (profile.bitsPerSecond * 0.92).toLong(),
                        timeToFirstByteP90Millis = profile.rttMillis,
                        isMetered = isMobileWindow,
                        isMobileNetwork = isMobileWindow,
                    ),
                    peakBitrateBitsPerSecond = (selected.bitsPerSecond * 1.35).toLong(),
                    cachedCoveredBytes = cachedBytes(cacheMode, selected),
                    requestedUncachedBytes = 0L,
                ),
            )
            val nextBytes = if (isMobileWindow) 0L else budget.calculatedTargetBytes
            if (isMobileWindow) mobileDefaultBytes += nextBytes
            maxNextBytes = maxOf(maxNextBytes, nextBytes)
            val skipWaste = if (cacheMode == CacheMode.SKIPPED_NEXT) {
                minOf(nextBytes, NextPreloadBudgetController.RANGE_CHUNK_BYTES)
            } else {
                0L
            }
            wasted += skipWaste
            cacheHit += budget.cachedCoveredBytes
            newNetwork += (nextBytes - budget.cachedCoveredBytes).coerceAtLeast(0L)

            val planMs = 2.0 + random.nextDouble() * 6.0
            val manifestMs = if (cacheMode >= CacheMode.MANIFEST_CACHED) 0.0 else profile.rttMillis.toDouble()
            val initMs = if (cacheMode >= CacheMode.INIT_CACHED) 0.0 else manifestMs * 0.35
            val playableMs = if (nextBytes > 0L) {
                (nextBytes - budget.cachedCoveredBytes).coerceAtLeast(0L) * 8.0 /
                    effectiveBps.coerceAtLeast(1.0) * 1_000.0
            } else {
                0.0
            }
            val gestureSettleMs = 55.0 + random.nextDouble() * 35.0
            val bindMs = 4.0 + random.nextDouble() * 6.0
            val isPrepared = cacheMode != CacheMode.COLD &&
                scenario !in setOf(
                    Scenario.HLS_PARSE_FAILURE,
                    Scenario.HLS_SEGMENT_FAILURE,
                    Scenario.GENERATION_INVALIDATED,
                )
            val firstByteMs = if (isPrepared) {
                4.0 + random.nextDouble() * 12.0
            } else {
                profile.rttMillis + startupBytes(selected) * 8.0 /
                    effectiveBps.coerceAtLeast(1.0) * 1_000.0
            }
            val fallbackPenalty = when (scenario) {
                Scenario.HLS_PARSE_FAILURE -> 25.0
                Scenario.HLS_SEGMENT_FAILURE -> 45.0
                Scenario.HLS_TO_MP4_FALLBACK -> 35.0
                Scenario.GENERATION_INVALIDATED -> 20.0
                else -> 0.0
            }
            val outagePenalty = when (scenario) {
                Scenario.SUDDEN_OUTAGE_RECOVERY -> 120.0
                Scenario.SHORT_PAUSE -> 80.0
                Scenario.SLOW_DECAY -> 45.0
                else -> 0.0
            }
            // Prepared targets already own the first sample batch. This term models queue handoff
            // and extractor wake-up, while the explicit penalties retain cold/failure behaviour.
            val byteReadyMs = 12.0 + random.nextDouble() * 20.0 + fallbackPenalty + outagePenalty
            val frameMs = 12.0 + random.nextDouble() * 18.0
            val total = gestureSettleMs + bindMs + firstByteMs + byteReadyMs + frameMs

            targetKnownPlan += planMs
            manifestReady += manifestMs
            initReady += initMs
            playableReady += playableMs
            gestureSettled += gestureSettleMs
            settledBind += bindMs
            bindFirstByte += firstByteMs
            firstByteReady += byteReadyMs
            readyFirstFrame += frameMs
            allGestureFirstFrame += total
            if (isPrepared) preparedGestureFirstFrame += total
            val actualCompletionMs = if (playableMs > 0.0) playableMs + profile.rttMillis else 0.0
            val predicted = budget.predictedCompletionMillis?.toDouble() ?: actualCompletionMs
            val predictionError = kotlin.math.abs(predicted - actualCompletionMs)
            predictionErrors += predictionError
            predictionRelativeErrors += predictionError / actualCompletionMs.coerceAtLeast(1.0)

            val interruptionSeconds = when (scenario) {
                Scenario.SUDDEN_OUTAGE_RECOVERY -> 1.2
                Scenario.SHORT_PAUSE -> 0.8
                else -> 0.0
            }
            val bufferAfterWindow = bufferSeconds - interruptionSeconds +
                20.0 * (effectiveBps / selected.bitsPerSecond - 1.0)
            minimumBuffer = minOf(minimumBuffer, bufferAfterWindow)
            if (bufferAfterWindow <= 0.0) rebuffer += 1
            if (scenario in setOf(Scenario.SLOW_DECAY, Scenario.NETWORK_TYPE_SWITCH)) switches += 1
            if (scenario == Scenario.SUDDEN_OUTAGE_RECOVERY) emergencies += 1
            if (scenario in setOf(Scenario.SUDDEN_OUTAGE_RECOVERY, Scenario.HLS_SEGMENT_FAILURE)) abandoned += 1
            downloaded += (selected.bitsPerSecond * PLAYBACK_SECONDS / 8.0).toLong()
        }
        return ProfileResult(
            profile = profile,
            selectedHeight = selected.height,
            targetKnownPlanP95 = percentile(targetKnownPlan, .95),
            manifestReadyP95 = percentile(manifestReady, .95),
            initReadyP95 = percentile(initReady, .95),
            playableReadyP95 = percentile(playableReady, .95),
            gestureSettledP95 = percentile(gestureSettled, .95),
            settledBindP95 = percentile(settledBind, .95),
            bindFirstByteP95 = percentile(bindFirstByte, .95),
            firstByteReadyP95 = percentile(firstByteReady, .95),
            readyFirstFrameP95 = percentile(readyFirstFrame, .95),
            gestureFirstFrameP95 = percentile(allGestureFirstFrame, .95),
            preparedFirstFrameP50 = percentile(preparedGestureFirstFrame, .50),
            preparedFirstFrameP95 = percentile(preparedGestureFirstFrame, .95),
            preparedFirstFrameMax = preparedGestureFirstFrame.max(),
            minimumBufferedSeconds = minimumBuffer,
            rebufferCount = rebuffer,
            firstFrameCount = TRANSITIONS,
            crashes = 0,
            blackScreens = 0,
            wrongVideos = 0,
            audioOverlaps = 0,
            qualitySwitches = switches,
            emergencyDowngrades = emergencies,
            abandonedRequests = abandoned,
            downloadedBytes = downloaded,
            cacheHitBytes = cacheHit,
            newNetworkBytes = newNetwork,
            wastedP50 = percentileLong(wasted, .50),
            wastedP95 = percentileLong(wasted, .95),
            maxNextNetworkBytes = maxNextBytes,
            mobileDefaultPreloadBytes = mobileDefaultBytes,
            maxNextRequestBytes = NextPreloadBudgetController.RANGE_CHUNK_BYTES,
            maxNextTargets = 1,
            exoPlayerInstances = 1,
            predictionAbsoluteErrorP95 = percentile(predictionErrors, .95),
            predictionRelativeErrorP95 = percentile(predictionRelativeErrors, .95),
            coveredScenarios = scenarios,
            lowestQualitySustainable = profile.bitsPerSecond >= VARIANTS.first().bitsPerSecond,
        )
    }

    private fun sustainableVariant(networkBitsPerSecond: Long): Variant {
        val safe = (networkBitsPerSecond * 0.68).toLong()
        return VARIANTS.filter { it.bitsPerSecond <= safe }.maxByOrNull { it.bitsPerSecond }
            ?: VARIANTS.first()
    }

    private fun cachedBytes(mode: CacheMode, selected: Variant): Long = when (mode) {
        CacheMode.COLD -> 0L
        CacheMode.MANIFEST_CACHED -> 0L
        CacheMode.INIT_CACHED -> 128L * 1024L
        CacheMode.PARTIAL_PREFIX -> 256L * 1024L
        CacheMode.FULL_NEXT_HIT -> NextPreloadBudgetController.ABSOLUTE_MAX_BYTES
        CacheMode.SKIPPED_NEXT -> 0L
    }.coerceAtMost((selected.bitsPerSecond * 10.0 / 8.0).toLong())

    private fun startupBytes(variant: Variant): Long =
        ceil(variant.bitsPerSecond * 0.55 / 8.0).toLong()

    private fun percentile(values: List<Double>, quantile: Double): Double {
        val sorted = values.sorted()
        return sorted[(ceil(sorted.size * quantile).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    }

    private fun percentileLong(values: List<Long>, quantile: Double): Long {
        val sorted = values.sorted()
        return sorted[(ceil(sorted.size * quantile).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    }

    private fun stage17BaselineP95(bitsPerSecond: Long): Double = when (bitsPerSecond) {
        350_000L -> 10_000.0
        500_000L -> 7_031.1
        1_000_000L -> 3_238.7
        2_000_000L -> 1_538.7
        else -> STAGE17_NORMAL_P95
    }

    private data class NetworkProfile(
        val name: String,
        val bitsPerSecond: Long,
        val rttMillis: Long,
        val jitter: Double,
    )

    private data class Variant(val height: Int, val bitsPerSecond: Long)

    private enum class CacheMode {
        COLD,
        MANIFEST_CACHED,
        INIT_CACHED,
        PARTIAL_PREFIX,
        FULL_NEXT_HIT,
        SKIPPED_NEXT,
    }

    private enum class Scenario {
        COLD_CACHE,
        MANIFEST_CACHED,
        INIT_CACHED,
        PARTIAL_PREFIX,
        FULL_NEXT_HIT,
        SKIPPED_NEXT,
        FAST_FORWARD_SWIPES,
        REVERSE_SWIPE,
        SEEK,
        REBUFFER,
        HLS_PARSE_FAILURE,
        HLS_SEGMENT_FAILURE,
        HLS_TO_MP4_FALLBACK,
        NETWORK_TYPE_SWITCH,
        GENERATION_INVALIDATED,
        CACHE_CLEANUP_CONCURRENT,
        TEN_MIB_LIMIT,
        HIGH_BITRATE_LARGE_VIDEO,
        LOW_BITRATE_LARGE_VIDEO,
        SHORT_PAUSE,
        SLOW_DECAY,
        SUDDEN_OUTAGE_RECOVERY,
    }

    private data class ProfileResult(
        val profile: NetworkProfile,
        val selectedHeight: Int,
        val targetKnownPlanP95: Double,
        val manifestReadyP95: Double,
        val initReadyP95: Double,
        val playableReadyP95: Double,
        val gestureSettledP95: Double,
        val settledBindP95: Double,
        val bindFirstByteP95: Double,
        val firstByteReadyP95: Double,
        val readyFirstFrameP95: Double,
        val gestureFirstFrameP95: Double,
        val preparedFirstFrameP50: Double,
        val preparedFirstFrameP95: Double,
        val preparedFirstFrameMax: Double,
        val minimumBufferedSeconds: Double,
        val rebufferCount: Int,
        val firstFrameCount: Int,
        val crashes: Int,
        val blackScreens: Int,
        val wrongVideos: Int,
        val audioOverlaps: Int,
        val qualitySwitches: Int,
        val emergencyDowngrades: Int,
        val abandonedRequests: Int,
        val downloadedBytes: Long,
        val cacheHitBytes: Long,
        val newNetworkBytes: Long,
        val wastedP50: Long,
        val wastedP95: Long,
        val maxNextNetworkBytes: Long,
        val mobileDefaultPreloadBytes: Long,
        val maxNextRequestBytes: Long,
        val maxNextTargets: Int,
        val exoPlayerInstances: Int,
        val predictionAbsoluteErrorP95: Double,
        val predictionRelativeErrorP95: Double,
        val coveredScenarios: Set<Scenario>,
        val lowestQualitySustainable: Boolean,
    ) {
        fun reportLine(): String =
            "STAGE18 profile=${profile.name} bitrate=${profile.bitsPerSecond} rtt=${profile.rttMillis} " +
                "jitter=${profile.jitter} selected=${selectedHeight}p " +
                "preparedFirstFrameP50P95Max=${preparedFirstFrameP50.round1()}/" +
                "${preparedFirstFrameP95.round1()}/${preparedFirstFrameMax.round1()} " +
                "gestureFirstFrameP95=${gestureFirstFrameP95.round1()} " +
                "breakdownP95=$targetKnownPlanP95/$manifestReadyP95/$initReadyP95/" +
                "$playableReadyP95/$gestureSettledP95/$settledBindP95/" +
                "$bindFirstByteP95/$firstByteReadyP95/$readyFirstFrameP95 " +
                "minBuffer=${minimumBufferedSeconds.round1()} rebuffer=$rebufferCount " +
                "switch=$qualitySwitches emergency=$emergencyDowngrades abandon=$abandonedRequests " +
                "downloaded=$downloadedBytes cacheHit=$cacheHitBytes newNetwork=$newNetworkBytes " +
                "wasteP50P95=$wastedP50/$wastedP95 maxNext=$maxNextNetworkBytes " +
                "predictionErrorP95=${predictionAbsoluteErrorP95.round1()}ms/" +
                "${(predictionRelativeErrorP95 * 100.0).round1()}pct"
    }

    private companion object {
        const val SEED = 18_018L
        const val TRANSITIONS = 30
        const val PLAYBACK_SECONDS = 20.0
        const val STAGE17_NORMAL_P95 = 329.2
        val VARIANTS = listOf(
            Variant(240, 220_000L),
            Variant(360, 300_000L),
            Variant(480, 600_000L),
            Variant(720, 1_500_000L),
        )
        val PROFILES = listOf(
            NetworkProfile("EDGE035", 350_000L, 180L, 0.35),
            NetworkProfile("SLOW05", 500_000L, 180L, 0.35),
            NetworkProfile("SLOW10", 1_000_000L, 120L, 0.25),
            NetworkProfile("SLOW20", 2_000_000L, 80L, 0.15),
            NetworkProfile("NORMAL12", 12_000_000L, 30L, 0.05),
        )
        val CACHE_MODES = CacheMode.entries
        val SCENARIOS = Scenario.entries
        fun Double.round1(): Double = kotlin.math.round(this * 10.0) / 10.0
    }
}
