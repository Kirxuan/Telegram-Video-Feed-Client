package com.qixuan.channelvideoflow.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetDecision
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetTier
import com.qixuan.channelvideoflow.domain.media.NextPreloadStopReason
import com.qixuan.channelvideoflow.domain.media.StreamingNetworkMetricsRepository
import com.qixuan.channelvideoflow.domain.media.TelegramFileGateway
import com.qixuan.channelvideoflow.domain.media.TelegramFileOwnerKind
import com.qixuan.channelvideoflow.domain.media.TelegramFileRangeLease
import com.qixuan.channelvideoflow.domain.media.TelegramFileRequestPriority
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(markerClass = [UnstableApi::class])
interface SamplePreloadController {
    val enabled: Boolean
    fun buildPlayer(builder: ExoPlayer.Builder, playbackLoadControl: LoadControl): ExoPlayer
    fun preload(video: IndexedVideo, budget: NextPreloadBudgetDecision)
    fun commitForPlayback(video: IndexedVideo)
    fun takeForPlayback(video: IndexedVideo): SamplePreloadHandoff?
    fun cancelUnless(video: IndexedVideo? = null)
    fun release()
}

class SamplePreloadHandoff internal constructor(
    val source: MediaSource,
    internal val sourceKind: PlaybackSourceKind,
    internal val requestSession: PlaybackRangeRequestSession,
    internal val hlsSession: TelegramHlsPlaybackSession?,
)

@Singleton
@OptIn(markerClass = [UnstableApi::class])
class Media3SamplePreloadController @Inject constructor(
    @ApplicationContext context: Context,
    private val gateway: TelegramFileGateway,
    networkMetrics: StreamingNetworkMetricsRepository,
) : SamplePreloadController {
    override val enabled: Boolean = BuildConfig.SAMPLE_QUEUE_PRELOAD_ENABLED
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val budget = AtomicReference<NextPreloadBudgetDecision?>(null)
    private val bandwidthMeter = TdLibBandwidthMeter(networkMetrics)
    private var builder: DefaultPreloadManager.Builder? = null
    private var manager: DefaultPreloadManager? = null
    private var nextRecord: Record? = null
    private var currentManagedMediaItem: MediaItem? = null
    private var currentRanking = CURRENT_INDEX
    private val handoffGate = SamplePreloadHandoffGate()

    override fun buildPlayer(builder: ExoPlayer.Builder, playbackLoadControl: LoadControl): ExoPlayer {
        if (!enabled) return builder.setLoadControl(playbackLoadControl).build()
        check(this.builder == null) { "sample preload builder already initialized" }
        val sharedLoadControl = ReservoirAwarePreloadLoadControl(playbackLoadControl) {
            val current = budget.get()
            current != null &&
                current.allowedBudgetTier >= NextPreloadBudgetTier.TWO_MIB &&
                current.remainingNewNetworkBudgetBytes == 0L &&
                current.preloadStopReason in setOf(
                    NextPreloadStopReason.TARGET_REACHED,
                    NextPreloadStopReason.HARD_LIMIT,
                )
        }
        val targetControl = TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
            val current = budget.get()
            if (current == null) {
                DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
            } else {
                when {
                    current.calculatedTargetSeconds > 0.0 ->
                        DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(
                            (current.calculatedTargetSeconds * 1_000.0).toLong(),
                        )
                    current.allowedBudgetTier == NextPreloadBudgetTier.METADATA_ONLY ->
                        DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
                    else -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
                }
            }
        }
        val preloadBuilder = DefaultPreloadManager.Builder(appContext, targetControl)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(sharedLoadControl)
        this.builder = preloadBuilder
        manager = preloadBuilder.build()
        return preloadBuilder.buildExoPlayer(builder)
    }

    override fun preload(video: IndexedVideo, budget: NextPreloadBudgetDecision) {
        if (!enabled || !budget.permitsSamplePreload()) return
        this.budget.set(budget)
        handler.post {
            val preloadManager = manager ?: return@post
            if (nextRecord?.matches(video) == true) {
                preloadManager.invalidate()
                return@post
            }
            clearNextOnMain(preloadManager)
            val requestSession = PlaybackRangeRequestSession(preloadOnly = true)
            val cappedGateway = CappedNextSampleGateway(
                delegate = gateway,
                payloadFileIds = video.hlsCapableVariants.map { it.fileId }.toSet()
                    .ifEmpty { setOf(video.playbackFileId) },
                allowedPayloadEnd = budget.calculatedTargetBytes,
                requestSession = requestSession,
            )
            val hlsSession = if (video.hlsCapableVariants.isNotEmpty()) {
                runCatching { TelegramHlsPlaybackSession.create(video, cappedGateway) }.getOrNull()
            } else {
                null
            }
            val mediaItem = if (hlsSession != null) {
                MediaItem.fromUri(hlsSession.masterUri)
            } else {
                MediaItem.fromUri(TelegramMediaDataSource.uriForFile(video.playbackFileId))
            }
            val source = if (hlsSession != null) {
                HlsMediaSource.Factory(
                    TelegramHlsDataSource.Factory(
                        gateway = cappedGateway,
                        session = hlsSession,
                        rangeSession = requestSession,
                        ownerKindOverride = null,
                        maxReadAheadBytes = NextPreloadBudgetControllerBridge.CHUNK_BYTES,
                    ),
                ).createMediaSource(mediaItem)
            } else {
                ProgressiveMediaSource.Factory(
                    TelegramMediaDataSource.Factory(
                        gateway = cappedGateway,
                        chunkSizeBytes = NextPreloadBudgetControllerBridge.CHUNK_BYTES,
                        requestSession = requestSession,
                        ownerKindOverride = null,
                        maxReadAheadBytes = NextPreloadBudgetControllerBridge.CHUNK_BYTES,
                    ),
                ).createMediaSource(mediaItem)
            }
            val ranking = currentRanking + 1
            nextRecord = Record(
                video.key,
                video.playbackFileId,
                mediaItem,
                ranking,
                requestSession,
                hlsSession,
            )
            handoffGate.register(video)
            preloadManager.add(source, ranking)
            preloadManager.setCurrentPlayingIndex(currentRanking)
            preloadManager.invalidate()
        }
    }

    override fun commitForPlayback(video: IndexedVideo) {
        if (!enabled) return
        runOnMain {
            val current = nextRecord
            if (current?.matches(video) == true && handoffGate.commit(video)) {
                current.requestSession.promoteToCurrent()
            } else {
                manager?.let(::clearNextOnMain)
            }
        }
    }

    override fun takeForPlayback(video: IndexedVideo): SamplePreloadHandoff? {
        if (!enabled || Looper.myLooper() != Looper.getMainLooper()) return null
        val current = nextRecord?.takeIf { it.matches(video) }
        if (current == null || !handoffGate.take(video)) {
            manager?.let(::clearNextOnMain)
            retireCurrentAfterPlayerSwitch()
            return null
        }
        current.requestSession.promoteToCurrent()
        val source = manager?.getMediaSource(current.mediaItem) ?: return null
        val session = current.hlsSession
        current.hlsSession = null
        val previousManagedItem = currentManagedMediaItem
        currentManagedMediaItem = current.mediaItem
        currentRanking = current.ranking
        nextRecord = null
        budget.set(null)
        manager?.setCurrentPlayingIndex(currentRanking)
        if (previousManagedItem != null && previousManagedItem != current.mediaItem) {
            handler.post { manager?.remove(previousManagedItem) }
        }
        return SamplePreloadHandoff(
            source = source,
            sourceKind = if (session != null) PlaybackSourceKind.HLS else PlaybackSourceKind.PROGRESSIVE,
            requestSession = current.requestSession,
            hlsSession = session,
        )
    }

    override fun cancelUnless(video: IndexedVideo?) {
        if (!enabled) return
        handler.post {
            if (video != null && nextRecord?.matches(video) == true) return@post
            handoffGate.cancelUnless(video)
            manager?.let(::clearNextOnMain)
        }
    }

    override fun release() {
        if (!enabled) return
        handler.post {
            manager?.let(::clearNextOnMain)
            manager?.release()
            manager = null
            builder = null
            currentManagedMediaItem = null
            currentRanking = CURRENT_INDEX
        }
    }

    private fun clearNextOnMain(preloadManager: DefaultPreloadManager) {
        val stale = nextRecord
        if (stale != null) {
            preloadManager.remove(stale.mediaItem)
            stale.requestSession.close()
            stale.hlsSession?.close()
        }
        nextRecord = null
        handoffGate.cancelUnless(null)
        budget.set(null)
    }

    private fun retireCurrentAfterPlayerSwitch() {
        val stale = currentManagedMediaItem ?: return
        currentManagedMediaItem = null
        currentRanking = CURRENT_INDEX
        handler.post { manager?.remove(stale) }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    private data class Record(
        val key: VideoKey,
        val fallbackFileId: Int,
        val mediaItem: MediaItem,
        val ranking: Int,
        val requestSession: PlaybackRangeRequestSession,
        var hlsSession: TelegramHlsPlaybackSession?,
    ) {
        fun matches(video: IndexedVideo): Boolean =
            key == video.key && fallbackFileId == video.playbackFileId
    }

    private companion object {
        const val CURRENT_INDEX = 0
    }
}

internal fun NextPreloadBudgetDecision.permitsSamplePreload(): Boolean =
    allowedBudgetTier == NextPreloadBudgetTier.METADATA_ONLY || calculatedTargetSeconds > 0.0

@OptIn(markerClass = [UnstableApi::class])
internal class ReservoirAwarePreloadLoadControl(
    delegate: LoadControl,
    private val preloadAllowed: () -> Boolean,
) : ForwardingLoadControl(delegate) {
    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: androidx.media3.common.Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = preloadAllowed()
}

@OptIn(markerClass = [UnstableApi::class])
internal class CappedNextSampleGateway(
    private val delegate: TelegramFileGateway,
    private val payloadFileIds: Set<Int>,
    private val allowedPayloadEnd: Long,
    private val requestSession: PlaybackRangeRequestSession,
    private val requestBudget: SampleRequestBudget = SampleRequestBudget(),
    /** Pool parsing may need an MP4 tail index; every requested resource shares the same cap. */
    private val allowSparsePayloadRanges: Boolean = false,
) : TelegramFileGateway by delegate {

    override fun acquireRange(
        fileId: Int,
        offset: Long,
        length: Long,
        priority: TelegramFileRequestPriority,
        ownerToken: String,
        ownerKind: TelegramFileOwnerKind,
        readAheadBytes: Long,
    ): TelegramFileRangeLease {
        val preloadOnly = requestSession.isPreloadOnly()
        val readAheadCeiling = if (preloadOnly) NextPreloadBudgetControllerBridge.CHUNK_BYTES
            else TelegramMediaDataSource.MAX_CURRENT_READ_AHEAD_BYTES
        require(readAheadBytes >= length && readAheadBytes <= readAheadCeiling)
        val effectiveReadAhead = if (preloadOnly) length else readAheadBytes
        if (preloadOnly && (allowSparsePayloadRanges || fileId in payloadFileIds)) {
            require(offset >= 0L && length > 0L && offset <= Long.MAX_VALUE - length)
            require(allowSparsePayloadRanges || offset + length <= allowedPayloadEnd) {
                "sample preload requested bytes outside the Stage 18D budget"
            }
            val snapshot = delegate.currentSnapshot(fileId)
            val cachedStart = if (snapshot?.isDownloadingCompleted == true) 0L else snapshot?.downloadOffset ?: 0L
            val cachedEnd = if (snapshot?.isDownloadingCompleted == true) snapshot.size else
                cachedStart + (snapshot?.downloadedPrefixSize ?: 0L)
            val covered = if (snapshot?.localPath == null) 0L else
                (minOf(offset + length, cachedEnd) - maxOf(offset, cachedStart)).coerceIn(0L, length)
            requestBudget.reserve(length - covered, allowedPayloadEnd)
        }
        return delegate.acquireRange(
            fileId,
            offset,
            length,
            priority,
            ownerToken,
            ownerKind,
            // TDLib schedules readAheadBytes, so speculative reads must not exceed the
            // range reserved above. Current playback regains its normal read-ahead.
            effectiveReadAhead,
        )
    }
}

/** Shared by every attempt for one target; cancellations never refund requested bytes. */
internal class SampleRequestBudget {
    @Volatile
    var reservedBytes: Long = 0L
        private set

    /**
     * Hands over the bytes another stage already charged to the same target, so this budget cannot
     * spend its full ceiling on top of them. Cancelled requests are part of that handed-over
     * amount: the target's lifetime accounting does not refund.
     */
    @Synchronized
    fun seed(alreadyRequestedBytes: Long) {
        reservedBytes = alreadyRequestedBytes.coerceAtLeast(0L)
    }

    @Synchronized
    fun reserve(uncachedBytes: Long, limit: Long) {
        require(uncachedBytes >= 0L && limit >= 0L)
        require(uncachedBytes <= (limit - reservedBytes).coerceAtLeast(0L)) {
            "sample preload exhausted its target lifetime request budget"
        }
        reservedBytes += uncachedBytes
    }
}

internal object NextPreloadBudgetControllerBridge {
    const val CHUNK_BYTES = 512L * 1024L
}

@OptIn(markerClass = [UnstableApi::class])
internal object NoOpSamplePreloadController : SamplePreloadController {
    override val enabled = false
    override fun buildPlayer(builder: ExoPlayer.Builder, playbackLoadControl: LoadControl) =
        builder.setLoadControl(playbackLoadControl).build()
    override fun preload(video: IndexedVideo, budget: NextPreloadBudgetDecision) = Unit
    override fun commitForPlayback(video: IndexedVideo) = Unit
    override fun takeForPlayback(video: IndexedVideo): SamplePreloadHandoff? = null
    override fun cancelUnless(video: IndexedVideo?) = Unit
    override fun release() = Unit
}

internal data class SamplePreloadAbResult(
    val baselineP95Millis: Long,
    val candidateP95Millis: Long,
    val improvementFraction: Double,
    val firstFrameComplete: Boolean,
    val sampleCountComplete: Boolean,
    val wastedBytesWithinLimit: Boolean,
    val safetyFailures: Int,
    // Thirty samples are screening only; this result cannot authorize production enablement.
    val screeningPassed: Boolean,
)

internal object SamplePreloadAbEvaluator {
    fun evaluate(
        baselineMillis: List<Long>,
        candidateMillis: List<Long>,
        firstFrameCount: Int,
        transitionCount: Int,
        safetyFailures: Int,
        baselineSkippedNextWastedBytes: List<Long> = emptyList(),
        candidateSkippedNextWastedBytes: List<Long> = emptyList(),
    ): SamplePreloadAbResult {
        val baseline = p95(baselineMillis)
        val candidate = p95(candidateMillis)
        val improvement = if (baseline > 0L) (baseline - candidate).toDouble() / baseline else 0.0
        val complete = transitionCount > 0 && firstFrameCount == transitionCount
        val sampleCountComplete = baselineMillis.size >= MINIMUM_SAMPLE_COUNT &&
            candidateMillis.size >= MINIMUM_SAMPLE_COUNT &&
            transitionCount >= MINIMUM_SAMPLE_COUNT
        val baselineWaste = p95OrZero(baselineSkippedNextWastedBytes)
        val candidateWaste = p95OrZero(candidateSkippedNextWastedBytes)
        val wasteLimit = if (baselineWaste == 0L) 0L else (baselineWaste * 1.25).toLong()
        val wastedBytesWithinLimit = candidateWaste <= wasteLimit
        return SamplePreloadAbResult(
            baseline,
            candidate,
            improvement,
            complete,
            sampleCountComplete,
            wastedBytesWithinLimit,
            safetyFailures,
            improvement >= MINIMUM_P95_IMPROVEMENT &&
                baseline - candidate >= MINIMUM_ABSOLUTE_IMPROVEMENT_MILLIS &&
                complete &&
                sampleCountComplete &&
                wastedBytesWithinLimit &&
                safetyFailures == 0,
        )
    }

    private fun p95(values: List<Long>): Long {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        return sorted[(kotlin.math.ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    }

    private fun p95OrZero(values: List<Long>): Long = if (values.isEmpty()) 0L else p95(values)

    private const val MINIMUM_ABSOLUTE_IMPROVEMENT_MILLIS = 75L
    private const val MINIMUM_SAMPLE_COUNT = 30
    private const val MINIMUM_P95_IMPROVEMENT = 0.15
}
