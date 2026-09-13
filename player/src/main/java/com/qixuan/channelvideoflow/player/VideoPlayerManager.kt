package com.qixuan.channelvideoflow.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadController
import com.qixuan.channelvideoflow.domain.media.NoOpStreamingNetworkMetricsRepository
import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadReason
import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadState
import com.qixuan.channelvideoflow.domain.media.PlaybackRiskAction
import com.qixuan.channelvideoflow.domain.media.PlaybackRiskController
import com.qixuan.channelvideoflow.domain.media.PlaybackRiskInput
import com.qixuan.channelvideoflow.domain.media.PlaybackRiskReason
import com.qixuan.channelvideoflow.domain.media.PlaybackRiskState
import com.qixuan.channelvideoflow.domain.media.NextPreloadSafetySnapshot
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetController
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetInput
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetDecision
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetTier
import com.qixuan.channelvideoflow.domain.media.NetworkTransport
import com.qixuan.channelvideoflow.domain.media.StreamingNetworkMetricsRepository
import com.qixuan.channelvideoflow.domain.media.TelegramFileGateway
import com.qixuan.channelvideoflow.domain.media.TelegramFileOwnerKind
import com.qixuan.channelvideoflow.domain.media.TelegramFileProtectionLease
import com.qixuan.channelvideoflow.domain.media.VideoPreloadController
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

sealed interface VideoPlaybackState {
    data object Idle : VideoPlaybackState
    data class Loading(val video: IndexedVideo) : VideoPlaybackState
    data class Ready(
        val video: IndexedVideo,
        val firstReadyWaitMillis: Long?,
        val observedLocalBytes: Long?,
    ) : VideoPlaybackState

    data class Unsupported(val video: IndexedVideo) : VideoPlaybackState
    data class Failed(
        val video: IndexedVideo,
        val reason: VideoPlaybackFailure,
    ) : VideoPlaybackState
}

enum class VideoPlaybackFailure {
    NETWORK,
    TIMEOUT,
    FILE_UNAVAILABLE,
    MESSAGE_UNAVAILABLE,
    DECODER_UNSUPPORTED,
    PLAYER,
    UNKNOWN,
}

/**
 * Application-scoped owner of a fixed ACTIVE/STANDBY pair.
 */
@Singleton
@UnstableApi
class VideoPlayerManager @Inject internal constructor(
    @ApplicationContext context: Context,
    private val gateway: TelegramFileGateway,
    private val adaptivePreloadController: AdaptivePreloadController,
    private val videoPreloadController: VideoPreloadController,
    private val samplePreloadController: SamplePreloadController,
    private val networkMetrics: StreamingNetworkMetricsRepository =
        NoOpStreamingNetworkMetricsRepository,
) : VideoPlaybackController {
    private val playbackPoolCandidate = PlaybackPoolCandidate.fromBuildValue(
        BuildConfig.PLAYBACK_POOL_CANDIDATE,
    )
    private val playbackPoolCoordinator = PlaybackPoolCoordinator(playbackPoolCandidate)
    private var standbyRecord: StandbyRecord? = null
    private var poolQueueGeneration = 0L
    private var desiredStandby: Pair<IndexedVideo, PlaybackPreparationContext>? = null
    private var attemptedStandby: Pair<IndexedVideo, PlaybackPreparationContext>? = null
    private var unavailableStandbyKey: VideoKey? = null
    private var latestPreloadSafety = NextPreloadSafetySnapshot()
    private var activeWasPoolPromoted = false
    private var activePoolPreparedReady = false
    private var activePoolDecodedFrame = false
    private var standbySurface: StandbyVideoSurface? = null
    private var standbyOutput: android.view.Surface? = null
    private var standbySurfaceUnavailable = false
    private var standbyBudget = SampleRequestBudget()
    private val activeStartupMillis = AtomicLong(BuildConfig.PLAYBACK_STARTUP_BUFFER_MILLIS.toLong())
    private var standbyBudgetTarget: VideoKey? = null
    /**
     * True while an admitted next item wants bytes on a metered link, so the ACTIVE engine holds a
     * bounded forward buffer and yields the rest of the link instead of starving the preparation.
     * Cleared on every bind, discard, and blocked admission so the current stream always reclaims
     * its full budget the moment no next item actually wants the link.
     */
    private val standbyBandwidthClaim = java.util.concurrent.atomic.AtomicBoolean(false)
    /**
     * Last evaluated admission conjuncts. A yield that silently never arms looks exactly like a
     * yield that does not help, so the inputs are recorded rather than inferred from its effect.
     */
    @Volatile
    private var standbyGateTrace: String = "notEvaluated"
    private var observedPoolNetworkGeneration: Long? = null

    override val supportsStandbyPreparation: Boolean
        get() = playbackPoolCoordinator.state.candidate != PlaybackPoolCandidate.DISABLED &&
            playbackPoolCoordinator.state.isExperimentAvailable
    private val appContext = context.applicationContext
    private val bufferPolicy = PlaybackBufferPolicy(
        candidateId = BuildConfig.PLAYBACK_TUNING_CANDIDATE,
        minBufferMillis = MIN_BUFFER_MILLIS,
        maxBufferMillis = MAX_BUFFER_MILLIS,
        bufferForPlaybackMillis = BuildConfig.PLAYBACK_STARTUP_BUFFER_MILLIS,
        bufferForPlaybackAfterRebufferMillis = BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MILLIS,
        prioritizeTimeOverSizeThresholds = BuildConfig.PLAYBACK_PRIORITIZE_TIME_OVER_SIZE,
        backBufferMillis = BuildConfig.PLAYBACK_BACK_BUFFER_MILLIS,
        targetBufferBytes = BuildConfig.PLAYBACK_TARGET_BUFFER_BYTES,
        startOrder = if (BuildConfig.PLAYBACK_PLAY_BEFORE_PREPARE) {
            PlaybackStartOrder.PLAY_THEN_PREPARE
        } else {
            PlaybackStartOrder.PREPARE_THEN_PLAY
        },
    )
    private val mutableState = MutableStateFlow<VideoPlaybackState>(VideoPlaybackState.Idle)
    val state: StateFlow<VideoPlaybackState> = mutableState.asStateFlow()
    private val mutableSnapshot = MutableStateFlow(VideoPlayerSnapshot())
    override val snapshot: StateFlow<VideoPlayerSnapshot> = mutableSnapshot.asStateFlow()

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            refreshPlaybackProgress()
            if (progressTickerRunning) {
                progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MILLIS)
            }
        }
    }

    private val playerLifecycle = ReusablePlayerLifecycle<MediaSource>(
        factory = { createPlayerEngine() },
        startOrder = bufferPolicy.startOrder,
    )
    private var activePlayerListener: ActivePlaybackListener? = null
    private val playerViewBinding = StablePlayerViewBinding<PlayerView, Player>(
        currentPlayer = PlayerView::getPlayer,
        setPlayer = PlayerView::setPlayer,
    )
    private var surfaceAttachCount = 0
    private var surfaceDetachCount = 0
    private val callbackGate = PlaybackCallbackGate()
    private var boundVideo: IndexedVideo? = null
    private var bindStartedAtNanos: Long = 0L
    private var firstByteMetricLogged = false
    private var firstReadyWaitMillis: Long? = null
    private var promotionStartupPending = false
    // Pager suspension is temporary and must not become a user/system pause in the UI.
    private var pageTransitionPaused = false
    private var progressTickerRunning = false
    private var currentProtection: TelegramFileProtectionLease? = null
    private var activeHlsSession: TelegramHlsPlaybackSession? = null
    private var activeSourceKind = PlaybackSourceKind.PROGRESSIVE
    private val hlsFallbackGate = HlsFallbackGate()
    private val tdLibBandwidthMeter = TdLibBandwidthMeter(networkMetrics)
    private val playbackRiskController = PlaybackRiskController()
    private var activeRangeSession: PlaybackRangeRequestSession? = null
    private val playbackMetrics = PlaybackSessionMetrics()
    private val transitionMetrics = PlaybackTransitionMetrics()
    private var lastPlaybackSampleAtNanos = 0L
    private var adaptiveRebufferActive = false
    private var reportedAdaptiveRebufferCount = 0
    private var seekRiskActive = false
    private var lastAbrBufferedAheadMillis = 0L
    private var lastAbrEvaluationMillis = 0L
    private var lastAbrMaxBitrate = Int.MAX_VALUE
    private var lastAbrReason: PlaybackRiskReason? = null

    private val poolSafetyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var poolSafetyJob: Job? = null

    private fun startPoolSafetyMonitoring() {
        if (playbackPoolCandidate == PlaybackPoolCandidate.DISABLED || poolSafetyJob?.isActive == true) return
        poolSafetyJob = poolSafetyScope.launch {
            launch {
                networkMetrics.estimate.collect { estimate ->
                    checkPoolNetworkGeneration(currentNetworkGeneration())
                }
            }
            launch {
                // contextRevision is intentionally a plain value for the shared metrics API;
                // poll it so a reset that clears estimate still invalidates NEXT promptly.
                while (isActive) {
                    checkPoolNetworkGeneration(currentNetworkGeneration())
                    delay(250L)
                }
            }
            adaptivePreloadController.decision.collect { decision ->
                if (!supportsStandbyPreparation) return@collect
                // Bind handles CURRENT_NOT_STABLE synchronously while handing off its exact
                // prepared target. Environmental changes must also work without a progress tick.
                if ((decision.state == AdaptivePreloadState.OFF &&
                        decision.reason != AdaptivePreloadReason.CURRENT_NOT_STABLE) ||
                    (!decision.isUnmeteredWifi && !decision.mobileDataPreloadEnabled) ||
                    decision.reason in STANDBY_BLOCKING_REASONS
                ) {
                    suspendStandbyPreparation()
                }
            }
        }
    }

    private fun checkPoolNetworkGeneration(generation: Long) {
        val previous = observedPoolNetworkGeneration
        observedPoolNetworkGeneration = generation
        if (previous != null && previous != generation && supportsStandbyPreparation) {
            playbackPoolCoordinator.dispatch(PlaybackPoolIntent.NetworkChanged)
            suspendStandbyPreparation()
        }
    }

    override fun attach(playerView: PlayerView) {
        val exoPlayer = ensurePlayer()
        val change = playerViewBinding.attach(playerView, exoPlayer)
        if (!change.attached) return
        if (change.detached) surfaceDetachCount += 1
        surfaceAttachCount += 1
        trace(
            "surface attach count=$surfaceAttachCount " +
                "detachCount=$surfaceDetachCount playerInstances=${playerLifecycle.instanceCount}",
        )
    }

    override fun detach(playerView: PlayerView) {
        if (!playerViewBinding.detach(playerView)) return
        surfaceDetachCount += 1
        trace("surface detach count=$surfaceDetachCount attachCount=$surfaceAttachCount")
    }

    override fun recordTransition(event: PlaybackTransitionEvent) {
        if (!BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) return
        transitionMetrics.onEvent(event)?.let(::traceTransitionSummary)
    }

    override fun bind(video: IndexedVideo) = bindInternal(video, null)

    override fun bind(video: IndexedVideo, context: PlaybackPreparationContext) =
        bindInternal(video, context)

    private fun bindInternal(video: IndexedVideo, context: PlaybackPreparationContext?) {
        pageTransitionPaused = false
        promotionStartupPending = false
        desiredStandby = null
        // A new binding owns the link again until a fresh next item is admitted.
        standbyBandwidthClaim.set(false)
        val preparedStandby = standbyRecord?.takeIf {
            it.matches(
                candidate = video,
                accountGeneration = gateway.currentAccountGeneration(),
                networkGeneration = currentNetworkGeneration(),
                context = context,
            )
        }
        if (standbyRecord != null && preparedStandby == null) clearStandbyCandidate()
        restoreNormalPlaybackSpeed(TemporaryPlaybackSpeedTermination.NEW_BINDING)
        adaptivePreloadController.onCurrentBind(
            cacheHit = hasStartupCacheHit(
                snapshot = gateway.currentSnapshot(video.playbackFileId),
                fileSize = video.playbackFileSize,
            ),
        )
        if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) transitionMetrics.onBindStarted(video.key)
        activePoolPreparedReady = preparedStandby != null &&
            (playerLifecycle.preparedEngine as? ExoPlayerReusableEngine)?.player?.let {
                it.playbackState == Player.STATE_READY && hasStartupReservoir(it)
            } == true
        activePoolDecodedFrame = preparedStandby?.decodedFrame == true
        if (!video.supportsStreaming) {
            // Metadata can change after a standby was prepared. Never leave a now-unsupported
            // target holding a decoder, request session, or NEXT protection lease.
            clearStandbyCandidate()
            callbackGate.invalidate()
            detachActivePlayerListener()
            clearCurrentBinding()
            currentProtection?.close()
            currentProtection = null
            boundVideo = video
            updatePlaybackState(VideoPlaybackState.Unsupported(video), isPaused = false)
            if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) {
                transitionMetrics.onUnsupported(video.key)?.let(::traceTransitionSummary)
            }
            return
        }
        playerViewBinding.forActiveView { view ->
            if (!video.canBeSaved) {
                var context: android.content.Context? = view.context
                while (context is android.content.ContextWrapper && context !is android.app.Activity) {
                    context = context.baseContext
                }
                (context as? android.app.Activity)?.window?.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        val nextProtection = gateway.pinFile(
            fileId = video.playbackFileId,
            ownerToken = "current-playback-${PROTECTION_COUNTER.incrementAndGet()}",
            ownerKind = TelegramFileOwnerKind.CURRENT_PLAYBACK,
        )
        val binding = callbackGate.begin(video.key)
        var exoPlayer = ensurePlayer()
        detachActivePlayerListener()
        retireCurrentBindingForReplacement()
        currentProtection?.close()
        currentProtection = nextProtection
        boundVideo = video
        firstReadyWaitMillis = null
        activeStartupMillis.set(startupMillisFor(video))
        bindStartedAtNanos = System.nanoTime()
        firstByteMetricLogged = false
        playbackMetrics.start()
        adaptiveRebufferActive = false
        reportedAdaptiveRebufferCount = 0
        seekRiskActive = false
        lastAbrBufferedAheadMillis = 0L
        lastAbrEvaluationMillis = 0L
        lastAbrMaxBitrate = Int.MAX_VALUE
        lastAbrReason = null
        lastPlaybackSampleAtNanos = 0L
        trace(
            "quality alternative=${video.selectedAlternative != null} " +
                "fileId=${video.playbackFileId} width=${video.playbackWidth} " +
                "height=${video.playbackHeight} size=${video.playbackFileSize} startupMs=${activeStartupMillis.get()}",
        )
        updatePlaybackState(VideoPlaybackState.Loading(video), isPaused = false)

        val promotedStandby = preparedStandby?.let { candidate ->
            val decision = playbackPoolCoordinator.dispatch(
                PlaybackPoolIntent.PromoteStandby(
                    media = candidate.media,
                    userMuted = mutableSnapshot.value.isMuted,
                ),
            )
            if (decision is PlaybackPoolDecision.Promoted) {
                val engine = try {
                    playerLifecycle.promotePrepared(candidate.token, binding.generation)?.also { promoted ->
                    exoPlayer = (promoted as ExoPlayerReusableEngine).player
                    exoPlayer.clearVideoSurface()
                    playerViewBinding.replacePlayer(exoPlayer)
                    exoPlayer.volume = if (mutableSnapshot.value.isMuted) {
                        MUTED_VOLUME
                    } else {
                        NORMAL_VOLUME
                    }
                    }
                } catch (_: Exception) {
                    null
                } catch (_: LinkageError) {
                    null
                }
                engine ?: run {
                    playbackPoolCoordinator.dispatch(PlaybackPoolIntent.FailSession(
                        PlaybackPoolFallbackReason.DECODER_RESOURCE,
                    ))
                    standbyRecord?.listener?.let { listener ->
                        cleanupPoolResource { currentPlayer()?.removeListener(listener) }
                    }
                    clearStandbyCandidate()
                    exoPlayer = ensurePlayer()
                    playerViewBinding.replacePlayer(exoPlayer)
                    null
                }
            } else {
                // The coordinator fail-closes on any target/generation mismatch. Drop the
                // physical standby as well so the reliable single-player path owns all requests.
                clearStandbyCandidate()
                null
            }
        }
        if (promotedStandby != null) {
            standbyRecord?.requestSession?.promoteToCurrent()
            activeRangeSession = standbyRecord?.requestSession
            activeHlsSession = standbyRecord?.hlsSession
            activeSourceKind = standbyRecord?.sourceKind ?: PlaybackSourceKind.PROGRESSIVE
            standbyRecord?.listener?.let(exoPlayer::removeListener)
            standbyRecord?.protection?.close()
            standbyRecord = null
            if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) transitionMetrics.onPrepare(video.key)
        }
        activeWasPoolPromoted = promotedStandby != null
        createPlayerListener(exoPlayer, video, binding).also { listener ->
            activePlayerListener = listener
            exoPlayer.addListener(listener)
            exoPlayer.addAnalyticsListener(listener)
        }
        if (promotedStandby != null) {
            // STANDBY is prepared with playWhenReady=false. Promotion must explicitly grant the
            // active engine its only playback/audio opportunity after the listener is installed.
            (playerLifecycle.currentEngine as? ExoPlayerReusableEngine)?.setActive(true)
            exoPlayer.volume = if (mutableSnapshot.value.isMuted) MUTED_VOLUME else NORMAL_VOLUME
            // Media3 can enter READY when a speculative byte/memory ceiling stops loading,
            // even below shouldStartPlayback's threshold. Keep that exact player and source,
            // resume foreground loading, and wait for a real startup reservoir before play.
            promotionStartupPending = !hasStartupReservoir(exoPlayer)
            activePlayerListener?.onPlaybackStateChanged(exoPlayer.playbackState)
            exoPlayer.playWhenReady = !promotionStartupPending
        } else {
            playbackPoolCoordinator.dispatch(
                PlaybackPoolIntent.BindActive(
                    media = poolMedia(video, queueGeneration = poolQueueGeneration),
                    userMuted = mutableSnapshot.value.isMuted,
                ),
            )
        }
        val sampleHandoff = if (promotedStandby == null) {
            samplePreloadController.takeForPlayback(video)
        } else {
            null
        }
        val requestSession = if (promotedStandby != null) {
            activeRangeSession ?: PlaybackRangeRequestSession()
        } else {
            sampleHandoff?.requestSession ?: PlaybackRangeRequestSession()
        }
        val hlsSession = if (promotedStandby != null) {
            activeHlsSession
        } else if (sampleHandoff?.hlsSession != null) {
            sampleHandoff.hlsSession
        } else if (BuildConfig.TELEGRAM_HLS_ENABLED) {
            runCatching { TelegramHlsPlaybackSession.create(video, gateway) }
                .onFailure { trace("hls registration result=MP4_FALLBACK") }
                .getOrNull()
        } else {
            null
        }
        activeHlsSession = hlsSession
        activeSourceKind = if (promotedStandby != null) {
            activeSourceKind
        } else sampleHandoff?.sourceKind ?: if (hlsSession != null) {
            PlaybackSourceKind.HLS
        } else {
            PlaybackSourceKind.PROGRESSIVE
        }
        hlsFallbackGate.begin(binding.generation, activeSourceKind)
        val playbackSource = if (promotedStandby != null) {
            null
        } else sampleHandoff?.source ?: if (hlsSession != null) {
            createHlsMediaSource(hlsSession, requestSession)
        } else {
            createProgressiveMediaSource(
                video = video,
                rangeSession = requestSession,
                acceptsRangeCallback = { callbackGate.accepts(binding) },
            )
        }
        activeRangeSession = requestSession
        if (promotedStandby == null) {
            playerLifecycle.bind(
                media = requireNotNull(playbackSource),
                bindingGeneration = binding.generation,
                onPrepare = {
                    if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) transitionMetrics.onPrepare(video.key)
                },
            )
        }
        exoPlayer.volume = if (mutableSnapshot.value.isMuted) MUTED_VOLUME else NORMAL_VOLUME
        startProgressUpdates()
    }

    override fun prepareStandby(video: IndexedVideo, context: PlaybackPreparationContext): Boolean {
        if (!supportsStandbyPreparation || !video.supportsStreaming) return false
        val target = video to context
        if (desiredStandby != target) {
            clearStandbyCandidate(releaseEngine = false)
            desiredStandby = target
            attemptedStandby = null
        }
        if (standbyBudgetTarget != video.key) {
            standbyBudgetTarget = video.key
            standbyBudget = SampleRequestBudget().also { budget ->
                // Hand over whatever the lightweight byte stage already charged to this same
                // target, cancelled requests included. One target therefore keeps one lifetime
                // request ceiling across both stages instead of giving the pool a fresh 20 MiB on
                // top of the prefix the byte stage already asked TDLib for.
                budget.seed(videoPreloadController.requestedBytesFor(video))
            }
            unavailableStandbyKey = null
        }
        updatePoolPreparation()
        // Own the pending target even while admission is blocked. The byte preloader
        // must not compete with a pool preparation that becomes safe on a later tick.
        return supportsStandbyPreparation
    }

    override fun discardStandby() {
        desiredStandby = null
        standbyBandwidthClaim.set(false)
        suspendStandbyPreparation()
    }

    private fun suspendStandbyPreparation(releaseEngine: Boolean = true) {
        attemptedStandby = null
        clearStandbyCandidate(releaseEngine)
    }

    private fun updatePoolPreparation() {
        val target = desiredStandby
        if (target == null) {
            standbyBandwidthClaim.set(false)
            return
        }
        if (!supportsStandbyPreparation) {
            standbyBandwidthClaim.set(false)
            return
        }
        val metered = latestPreloadSafety.isMobileNetwork || latestPreloadSafety.isMetered
        val adaptive = adaptivePreloadController.decision.value
        val video = target.first
        if (unavailableStandbyKey == video.key) {
            standbyBandwidthClaim.set(false)
            return
        }
        val input = NextPreloadBudgetInput(
            safety = latestPreloadSafety.copy(standbyPreparationActive = standbyRecord != null),
            // Peak estimate: average bitrate plus headroom for variable-rate encodes. The budget
            // controller converts this into "seconds x bitrate" and applies the byte ceiling itself.
            peakBitrateBitsPerSecond = video.playbackFileSize
                ?.takeIf { video.durationSeconds > 0 && it > 0L }
                ?.let { size -> size.toDouble() * 8.0 * BITRATE_PEAK_HEADROOM / video.durationSeconds }
                ?.toLong(),
            cachedCoveredBytes = 0,
            requestedUncachedBytes = standbyBudget.reservedBytes,
        )
        val budget = NextPreloadBudgetController.evaluate(input)
        val safe = budget.calculatedTargetBytes > 0 && adaptive.maxPreloadBytes > 0 &&
            (adaptive.isUnmeteredWifi || adaptive.mobileDataPreloadEnabled) &&
            adaptive.reason !in STANDBY_BLOCKING_REASONS &&
            mutableSnapshot.value.hasRenderedFirstFrame && !mutableSnapshot.value.isPaused
        // Only yield link capacity while the next item is genuinely admitted on a metered link.
        // A blocked budget (current buffer too low, device pressure) immediately restores the full
        // current budget; an unmetered link needs no yield at all.
        standbyBandwidthClaim.set(safe && metered)
        standbyGateTrace = "budgetBytes=${budget.calculatedTargetBytes}" +
            " maxPreload=${adaptive.maxPreloadBytes}" +
            " wifi=${adaptive.isUnmeteredWifi} mobile=${adaptive.mobileDataPreloadEnabled}" +
            " reason=${adaptive.reason} firstFrame=${mutableSnapshot.value.hasRenderedFirstFrame}" +
            " paused=${mutableSnapshot.value.isPaused} metered=$metered safe=$safe" +
            " claim=${standbyBandwidthClaim.get()}"
        if (!safe) {
            suspendStandbyPreparation(releaseEngine = false)
            return
        }
        if (standbyRecord != null || attemptedStandby == target) return
        attemptedStandby = target
        preparePoolEngine(video, target.second, budget)
    }

    private fun activeForwardBufferCapMicros(): Long =
        if (standbyBandwidthClaim.get()) {
            PoolLoadControl.METERED_ACTIVE_FORWARD_BUFFER_CAP_MICROS
        } else {
            Long.MAX_VALUE
        }

    /**
     * Per-request read-ahead ceiling for the current stream. While a bounded next item is waiting
     * for the link, the current stream's in-flight window is narrowed so the handover costs one
     * small window instead of a full 4 MiB one; otherwise the production value is unchanged.
     */
    private fun currentReadAheadBytes(): Long =
        if (standbyBandwidthClaim.get()) {
            YIELDING_ACTIVE_READ_AHEAD_BYTES
        } else {
            TelegramMediaDataSource.MAX_CURRENT_READ_AHEAD_BYTES
        }

    private fun preparePoolEngine(
        video: IndexedVideo,
        context: PlaybackPreparationContext,
        budget: NextPreloadBudgetDecision,
    ) {
        // The estimate is for scheduling, not a premature byte stop for variable bitrate media.
        // LoadControl stops at three seconds; all requested resources share this lifetime cap.
        val boundedBudget = budget.allowedBudgetTier.ceilingBytes
            .coerceAtMost(NextPreloadBudgetController.ABSOLUTE_MAX_BYTES)
        if (boundedBudget <= 0) {
            attemptedStandby = null
            return
        }
        var requestSession: PlaybackRangeRequestSession? = null
        var hlsSession: TelegramHlsPlaybackSession? = null
        var protection: TelegramFileProtectionLease? = null
        var resourcesTransferred = false
        try {
            requestSession = PlaybackRangeRequestSession(preloadOnly = true)
            val cappedGateway = CappedNextSampleGateway(
                delegate = gateway,
                payloadFileIds = video.hlsCapableVariants.map { it.fileId }.toSet() + video.playbackFileId,
                allowedPayloadEnd = boundedBudget,
                requestSession = requestSession,
                requestBudget = standbyBudget,
                allowSparsePayloadRanges = true,
            )
            hlsSession = if (BuildConfig.TELEGRAM_HLS_ENABLED) {
                runCatching { TelegramHlsPlaybackSession.create(video, cappedGateway) }.getOrNull()
            } else null
            val source = if (hlsSession != null) {
                HlsMediaSource.Factory(
                    TelegramHlsDataSource.Factory(
                        gateway = cappedGateway,
                        session = hlsSession,
                        rangeSession = requestSession,
                        ownerKindOverride = null,
                        maxReadAheadBytes = TelegramMediaDataSource.MAX_CURRENT_READ_AHEAD_BYTES,
                    ),
                ).createMediaSource(MediaItem.fromUri(hlsSession.masterUri))
            } else {
                ProgressiveMediaSource.Factory(
                    TelegramMediaDataSource.Factory(
                        gateway = cappedGateway,
                        requestSession = requestSession,
                        ownerKindOverride = null,
                        maxReadAheadBytes = TelegramMediaDataSource.MAX_CURRENT_READ_AHEAD_BYTES,
                    ),
                ).createMediaSource(
                    MediaItem.fromUri(TelegramMediaDataSource.uriForFile(video.playbackFileId)),
                )
            }
            val token = "pool-next-${POOL_TOKEN_COUNTER.incrementAndGet()}"
            val media = PlaybackPoolMedia(
                key = video.key,
                fileId = video.playbackFileId,
                qualityGeneration = video.selectedAlternative?.alternativeId ?: 0L,
                accountGeneration = gateway.currentAccountGeneration(),
                networkGeneration = currentNetworkGeneration(),
                queueGeneration = ++poolQueueGeneration,
            )
            val decision = playbackPoolCoordinator.dispatch(
                PlaybackPoolIntent.PrepareStandby(
                    media = media,
                    nextOwnerToken = token,
                    byteBudget = boundedBudget,
                    safety = poolSafety(budget),
                ),
            )
            if (decision !is PlaybackPoolDecision.StandbyPrepared) {
                attemptedStandby = null
                return
            }
            val acquiredProtection = gateway.pinFile(
                video.playbackFileId,
                token,
                TelegramFileOwnerKind.NEXT_PRELOAD,
            )
            protection = acquiredProtection
            playerLifecycle.prepareStandby(
                media = source,
                token = token,
                onPrepare = {
                    if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) trace("pool standby prepare key=${video.key}")
                },
            )
            // Preparation may synchronously trigger a safety callback. Do not install a
            // standby record after its target, generation, or owner has already been retired.
            val stillCurrent = desiredStandby == (video to context) &&
                playbackPoolCoordinator.state.standby.nextOwnerToken == token
            if (!stillCurrent) {
                cleanupPoolResource { playerLifecycle.releaseStandby() }
                playbackPoolCoordinator.dispatch(PlaybackPoolIntent.DiscardStandby)
                return
            }
            standbyRecord = StandbyRecord(
                video = video,
                media = media,
                token = token,
                requestSession = requestSession,
                hlsSession = hlsSession,
                sourceKind = if (hlsSession != null) PlaybackSourceKind.HLS else PlaybackSourceKind.PROGRESSIVE,
                context = context,
                protection = acquiredProtection,
            )
            resourcesTransferred = true
            val standby = (playerLifecycle.preparedEngine as ExoPlayerReusableEngine).player
            val listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (standbyRecord?.token == token) {
                        standbyRecord = standbyRecord?.copy(decodedFrame = true)
                        trace("pool standbyFirstFrame=true visible=false")
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (standbyRecord?.token != token) return
                    if (error.errorCode in 2000..3999) {
                        // Source/parse/budget failure belongs to this target. Hardware failure
                        // may disable the pool, but one unavailable file must not disable all NEXTs.
                        unavailableStandbyKey = video.key
                        clearStandbyCandidate()
                        trace("pool standby source unavailable code=${error.errorCode}")
                    } else {
                        failPoolPreparation()
                    }
                }
            }
            standbyRecord = standbyRecord?.copy(listener = listener)
            standby.addListener(listener)
            if (playbackPoolCoordinator.state.candidate == PlaybackPoolCandidate.C2 && video.canBeSaved) {
                prepareStandbySurface()
            }
        } catch (failure: Exception) {
            trace("pool standby prepare failed category=${failure.javaClass.simpleName}")
            failPoolPreparation()
        } catch (failure: LinkageError) {
            trace("pool standby prepare failed category=${failure.javaClass.simpleName}")
            failPoolPreparation()
        } finally {
            if (!resourcesTransferred) {
                cleanupPoolResource { requestSession?.close() }
                cleanupPoolResource { hlsSession?.close() }
                cleanupPoolResource { protection?.close() }
            }
        }
    }

    private fun failPoolPreparation() {
        playbackPoolCoordinator.dispatch(PlaybackPoolIntent.FailSession(
            PlaybackPoolFallbackReason.DECODER_RESOURCE))
        discardStandby()
        cleanupPoolResource { playerLifecycle.releaseStandby() }
        closeStandbySurface()
        trace("pool standby unavailable fallback=SINGLE")
    }

    private fun prepareStandbySurface() {
        if (standbySurfaceUnavailable) return
        standbyOutput?.let { output ->
            bindStandbySurface(output)
            return
        }
        if (standbySurface != null) return
        standbySurface = StandbyVideoSurface(
            callbackHandler = progressHandler,
            onReady = { output ->
                standbyOutput = output
                bindStandbySurface(output)
            },
            onFailure = ::disableStandbySurface,
        )
    }

    private fun bindStandbySurface(output: android.view.Surface) {
        if (standbyRecord == null || standbyRecord?.video?.canBeSaved != true) return
        try {
            (playerLifecycle.preparedEngine as? ExoPlayerReusableEngine)?.player?.setVideoSurface(output)
        } catch (_: Exception) {
            disableStandbySurface()
        } catch (_: LinkageError) {
            disableStandbySurface()
        }
    }

    private fun disableStandbySurface() {
        standbySurfaceUnavailable = true
        // A C2 output failure removes only the offscreen output. The bounded two-player
        // coordinator remains available as C1 for this feed session.
        playbackPoolCoordinator.dispatch(PlaybackPoolIntent.DowngradeC2ToC1)
        discardStandby()
        trace("pool C2 surface unavailable fallback=C1")
    }

    private fun closeStandbySurface() {
        standbySurface?.close()
        standbySurface = null
        standbyOutput = null
    }

    override fun retry() {
        boundVideo?.let(::bind)
    }

    override fun showFailure(video: IndexedVideo, failure: VideoPlaybackFailure) {
        discardStandby()
        restoreNormalPlaybackSpeed(TemporaryPlaybackSpeedTermination.FAILURE)
        callbackGate.invalidate()
        detachActivePlayerListener()
        clearCurrentBinding()
        currentProtection?.close()
        currentProtection = null
        boundVideo = video
        updatePlaybackState(VideoPlaybackState.Failed(video, failure), isPaused = false)
        adaptivePreloadController.onPlaybackFailure()
        if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) {
            transitionMetrics.onFailure(video.key)?.let(::traceTransitionSummary)
        }
    }

    override fun finishFileRecoveryFailure(key: com.qixuan.channelvideoflow.model.video.VideoKey) {
        if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) {
            transitionMetrics.onFailure(key)?.let(::traceTransitionSummary)
        }
    }

    override fun pause() {
        trace("pause requested")
        pageTransitionPaused = false
        discardStandby()
        restoreNormalPlaybackSpeed(TemporaryPlaybackSpeedTermination.PAUSE)
        playbackMetrics.markPaused()
        currentPlayer()?.pause()
        mutableSnapshot.value = mutableSnapshot.value.copy(
            isPaused = true,
            isPlaying = false,
        )
        stopProgressUpdates()
    }

    override fun resume() {
        if (boundVideo == null || mutableSnapshot.value.playbackState is VideoPlaybackState.Unsupported) {
            return
        }
        // A one-item random round (or a return to an ended page) reuses this binding.
        // Media3 play() alone cannot leave ENDED, so the media must be rewound first; seekTo also
        // clears the completion flag. A non-seekable item that already ended cannot be restarted,
        // so its completion stands instead of being reported as playback that never happens.
        if (mutableSnapshot.value.hasEnded) {
            seekTo(0L)
            if (mutableSnapshot.value.hasEnded) return
        }
        trace("resume requested")
        pageTransitionPaused = false
        if (!promotionStartupPending) currentPlayer()?.play()
        mutableSnapshot.value = mutableSnapshot.value.copy(isPaused = false)
        startProgressUpdates()
    }

    override fun seekTo(positionMillis: Long) {
        discardStandby()
        restoreNormalPlaybackSpeed(TemporaryPlaybackSpeedTermination.SEEK)
        val exoPlayer = currentPlayer() ?: return
        if (boundVideo == null || !exoPlayer.isCurrentMediaItemSeekable) return
        val duration = normalizedDuration(exoPlayer.duration)
        if (duration <= 0L) return
        val target = positionMillis.coerceIn(0L, duration)
        playbackMetrics.markSeek()
        // A seek keeps the same binding and its visible picture. Media3 may reuse a
        // decoded frame without another onRenderedFirstFrame callback, especially while
        // paused; clearing this flag would hide the controls behind a permanent loader.
        activeRangeSession?.onUserSeek()
        seekRiskActive = true
        val wasAwaitingPromotion = promotionStartupPending
        promotionStartupPending = false
        mutableSnapshot.value = mutableSnapshot.value.copy(hasEnded = false)
        exoPlayer.seekTo(target)
        if (wasAwaitingPromotion && !mutableSnapshot.value.isPaused) exoPlayer.play()
        refreshPlaybackProgress()
    }

    override fun pauseForPageTransition() {
        trace("pause reason=PAGE_TRANSITION")
        pageTransitionPaused = true
        playerLifecycle.pauseForPageTransition()
        playbackMetrics.markPaused()
        mutableSnapshot.value = mutableSnapshot.value.copy(isPlaying = false)
        stopProgressUpdates()
    }

    override fun setMuted(muted: Boolean) {
        currentPlayer()?.volume = if (muted) MUTED_VOLUME else NORMAL_VOLUME
        mutableSnapshot.value = mutableSnapshot.value.copy(isMuted = muted)
    }

    override fun setTemporaryPlaybackSpeed(active: Boolean) {
        val current = mutableSnapshot.value
        val ready = current.playbackState as? VideoPlaybackState.Ready
        val canActivate = active &&
            ready != null &&
            ready.video.key == boundVideo?.key &&
            current.hasRenderedFirstFrame &&
            current.isPlaying &&
            !current.isPaused
        val applied = if (active && canActivate) {
            playerLifecycle.setTemporaryPlaybackSpeed(active = true)
        } else {
            playerLifecycle.terminateTemporaryPlaybackSpeed(
                TemporaryPlaybackSpeedTermination.USER_RELEASE,
            )
        }
        mutableSnapshot.value = mutableSnapshot.value.copy(playbackSpeed = applied)
    }

    override fun onAppBackgrounded() {
        trace("background pause")
        discardStandby()
        playerLifecycle.releaseStandby()
        closeStandbySurface()
        restoreNormalPlaybackSpeed(TemporaryPlaybackSpeedTermination.BACKGROUND)
        pause()
    }

    override fun releaseBinding() {
        pageTransitionPaused = false
        trace("release binding")
        poolSafetyJob?.cancel()
        poolSafetyJob = null
        observedPoolNetworkGeneration = null
        discardStandby()
        playerLifecycle.releaseStandby()
        playbackPoolCoordinator.dispatch(PlaybackPoolIntent.ReleaseBinding)
        closeStandbySurface()
        standbySurfaceUnavailable = false
        restoreNormalPlaybackSpeed(TemporaryPlaybackSpeedTermination.UNBIND)
        if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) {
            transitionMetrics.onRelease()?.let(::traceTransitionSummary)
        }
        callbackGate.invalidate()
        detachActivePlayerListener()
        clearCurrentBinding()
        currentProtection?.close()
        currentProtection = null
        boundVideo = null
        adaptivePreloadController.onCurrentReleased()
        updatePlaybackState(VideoPlaybackState.Idle, isPaused = false)
    }

    override fun release() {
        releaseBinding()
        stopProgressUpdates()
        if (playerViewBinding.detachActive()) surfaceDetachCount += 1
        playerLifecycle.release()
        playbackPoolCoordinator.dispatch(PlaybackPoolIntent.Release)
        standbyBudgetTarget = null
        unavailableStandbyKey = null
        standbyBudget = SampleRequestBudget()
        samplePreloadController.release()
        progressHandler.removeCallbacks(progressTicker)
    }

    private fun clearStandbyCandidate(releaseEngine: Boolean = true) {
        val stale = standbyRecord
        standbyRecord = null
        stale?.listener?.let { listener ->
            cleanupPoolResource {
                (playerLifecycle.preparedEngine as? ExoPlayerReusableEngine)?.player?.removeListener(listener)
            }
        }
        cleanupPoolResource { stale?.requestSession?.close() }
        cleanupPoolResource { stale?.hlsSession?.close() }
        cleanupPoolResource {
            (playerLifecycle.preparedEngine as? ExoPlayerReusableEngine)?.player?.clearVideoSurface()
        }
        cleanupPoolResource {
            if (releaseEngine) playerLifecycle.releaseStandby() else playerLifecycle.clearStandby()
        }
        cleanupPoolResource { stale?.protection?.close() }
        cleanupPoolResource { closeStandbySurface() }
        playbackPoolCoordinator.dispatch(PlaybackPoolIntent.DiscardStandby)
    }

    // Optional preparation must release every owner even if a vendor teardown call fails.
    private inline fun cleanupPoolResource(action: () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            playbackPoolCoordinator.dispatch(PlaybackPoolIntent.FailSession(PlaybackPoolFallbackReason.DECODER_RESOURCE))
            trace("pool cleanup failed category=${failure.javaClass.simpleName}")
        } catch (failure: LinkageError) {
            playbackPoolCoordinator.dispatch(PlaybackPoolIntent.FailSession(PlaybackPoolFallbackReason.DECODER_RESOURCE))
            trace("pool cleanup failed category=${failure.javaClass.simpleName}")
        }
    }

    private fun poolMedia(video: IndexedVideo, queueGeneration: Long): PlaybackPoolMedia =
        PlaybackPoolMedia(
            key = video.key,
            fileId = video.playbackFileId,
            qualityGeneration = video.selectedAlternative?.alternativeId ?: 0L,
            accountGeneration = gateway.currentAccountGeneration(),
            networkGeneration = currentNetworkGeneration(),
            queueGeneration = queueGeneration,
        )

    private fun currentNetworkGeneration(): Long =
        // Context revision and device generation are different counters. Publishing the
        // first estimate must not look like a network handoff and destroy a prepared NEXT.
        networkMetrics.contextRevision

    private fun poolSafety(decision: NextPreloadBudgetDecision): PlaybackPoolSafety {
        val adaptive = adaptivePreloadController.decision.value
        return PlaybackPoolSafety(
            // Single source of truth: the budget controller already encodes the current-buffer
            // admission floor and the falling-buffer guard. Re-checking an independent threshold
            // here previously kept the pool blocked for eight seconds even when the budget allowed
            // a bounded preparation, which is why mobile "READY hits" stayed at zero.
            currentReservoirSafe = decision.allowedBudgetTier != NextPreloadBudgetTier.BLOCKED &&
                decision.allowedBudgetTier != NextPreloadBudgetTier.METADATA_ONLY,
            isMetered = !adaptive.isUnmeteredWifi,
            mobilePreloadEnabled = adaptive.mobileDataPreloadEnabled,
            isLowMemory = adaptive.reason == AdaptivePreloadReason.MEMORY_LOW,
            isLowStorage = adaptive.reason == AdaptivePreloadReason.STORAGE_LOW,
            isPowerSave = adaptive.reason == AdaptivePreloadReason.POWER_SAVE,
            thermalStatus = if (adaptive.reason == AdaptivePreloadReason.THERMAL) {
                PlaybackPoolThermalStatus.MODERATE
            } else {
                PlaybackPoolThermalStatus.NONE
            },
        )
    }

    private data class StandbyRecord(
        val video: IndexedVideo,
        val media: PlaybackPoolMedia,
        val token: String,
        val requestSession: PlaybackRangeRequestSession,
        val hlsSession: TelegramHlsPlaybackSession?,
        val sourceKind: PlaybackSourceKind,
        val context: PlaybackPreparationContext,
        val protection: TelegramFileProtectionLease,
        val listener: Player.Listener? = null,
        val decodedFrame: Boolean = false,
    ) {
        fun matches(
            candidate: IndexedVideo,
            accountGeneration: Long,
            networkGeneration: Long,
            context: PlaybackPreparationContext?,
        ): Boolean = this.context == context &&
            video.key == candidate.key &&
                video.canBeSaved == candidate.canBeSaved &&
                video.playbackFileId == candidate.playbackFileId &&
                video.selectedAlternative?.alternativeId == candidate.selectedAlternative?.alternativeId &&
                media.accountGeneration == accountGeneration &&
                media.networkGeneration == networkGeneration
    }

    private fun ensurePlayer(): ExoPlayer {
        startPoolSafetyMonitoring()
        return ensurePlayerEngine().player
    }

    private fun ensurePlayerEngine(): ExoPlayerReusableEngine =
        playerLifecycle.ensureEngine() as ExoPlayerReusableEngine

    private fun createPlayerEngine(): ReusablePlayerEngine<MediaSource> {
        val builder = ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                VideoAudioPolicy.attributes,
                false,
            )
            .setHandleAudioBecomingNoisy(true)
        if (BuildConfig.HYBRID_ABR_ENABLED) {
            builder.setBandwidthMeter(tdLibBandwidthMeter)
        }
        val role = java.util.concurrent.atomic.AtomicBoolean(true)
        val allocator = androidx.media3.exoplayer.upstream.DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = if (playbackPoolCandidate != PlaybackPoolCandidate.DISABLED) {
            PoolLoadControl(
                buildLoadControl(allocator),
                role,
                activeBufferCapMicros = ::activeForwardBufferCapMicros,
                startupMillis = activeStartupMillis::get,
            )
        } else buildLoadControl(allocator)
        val player = samplePreloadController.buildPlayer(builder, loadControl)
            .also { created ->
                created.volume = if (mutableSnapshot.value.isMuted) MUTED_VOLUME else NORMAL_VOLUME
            }

        tracePlayerConfiguration()
        return ExoPlayerReusableEngine(player, role, allocator)
    }

    private fun tracePlayerConfiguration() {
        trace(
                "config candidate=${bufferPolicy.candidateId} " +
                "minBufferMs=${bufferPolicy.minBufferMillis} " +
                "maxBufferMs=${bufferPolicy.maxBufferMillis} " +
                "startupMs=${bufferPolicy.bufferForPlaybackMillis} " +
                "rebufferMs=${bufferPolicy.bufferForPlaybackAfterRebufferMillis} " +
                "prioritizeTime=${bufferPolicy.prioritizeTimeOverSizeThresholds} " +
                "backBufferMs=${bufferPolicy.backBufferMillis} " +
                "targetBufferBytes=${bufferPolicy.targetBufferBytes} " +
                "activeSampleCeilingBytes=${PoolLoadControl.ACTIVE_SAMPLE_CEILING_BYTES} " +
                "startOrder=${bufferPolicy.startOrder}",
        )
    }

    private fun createProgressiveMediaSource(
        video: IndexedVideo,
        rangeSession: PlaybackRangeRequestSession,
        acceptsRangeCallback: () -> Boolean,
    ): MediaSource = ProgressiveMediaSource.Factory(
            TelegramMediaDataSource.Factory(
                gateway = gateway,
                requestSession = rangeSession,
                dynamicMaxReadAheadBytes = ::currentReadAheadBytes,
                onCurrentRangeLeaseAcquired = { acquired ->
                    if (acceptsRangeCallback()) {
                        if (acquired) {
                            videoPreloadController.onCurrentPlaybackRangeAcquired(video)
                        } else {
                            videoPreloadController.onCurrentPlaybackRangeAcquireFailed(video)
                        }
                    }
                },
            ),
        )
            .setLoadErrorHandlingPolicy(
                DefaultLoadErrorHandlingPolicy(MAX_PLAYER_LOAD_RETRY_COUNT),
            )
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(TelegramMediaDataSource.uriForFile(video.playbackFileId))
                    .build(),
            )

    private fun createHlsMediaSource(
        session: TelegramHlsPlaybackSession,
        rangeSession: PlaybackRangeRequestSession,
    ): MediaSource = HlsMediaSource.Factory(
        TelegramHlsDataSource.Factory(
            gateway = gateway,
            session = session,
            rangeSession = rangeSession,
        ),
    )
        .setLoadErrorHandlingPolicy(
            DefaultLoadErrorHandlingPolicy(MAX_PLAYER_LOAD_RETRY_COUNT),
        )
        .createMediaSource(MediaItem.fromUri(session.masterUri))

    private fun buildLoadControl(allocator: androidx.media3.exoplayer.upstream.DefaultAllocator): DefaultLoadControl {
        val builder = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                bufferPolicy.minBufferMillis,
                bufferPolicy.maxBufferMillis,
                bufferPolicy.bufferForPlaybackMillis,
                bufferPolicy.bufferForPlaybackAfterRebufferMillis,
            )
            .setPrioritizeTimeOverSizeThresholds(
                bufferPolicy.prioritizeTimeOverSizeThresholds,
            )
            .setBackBuffer(
                bufferPolicy.backBufferMillis,
                false,
            )
        if (bufferPolicy.targetBufferBytes > 0) {
            builder.setTargetBufferBytes(bufferPolicy.targetBufferBytes)
        }
        return builder.build()
    }

    private fun createPlayerListener(
        exoPlayer: ExoPlayer,
        video: IndexedVideo,
        binding: PlaybackBindingToken,
    ): ActivePlaybackListener = object : ActivePlaybackListener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!callbackGate.accepts(binding)) return
            trace("isPlaying=$isPlaying")
            mutableSnapshot.value = mutableSnapshot.value.copy(isPlaying = isPlaying)
            if (isPlaying && !pageTransitionPaused) startProgressUpdates()
            reconcileTemporaryPlaybackSpeed(exoPlayer, binding)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!callbackGate.accepts(binding)) return
            mutableSnapshot.value = mutableSnapshot.value.copy(
                hasEnded = playbackState == Player.STATE_ENDED,
            )
            if (playbackState == Player.STATE_ENDED) {
                trace("ended chatId=${video.key.chatId} messageId=${video.key.messageId}")
            }
            reconcileTemporaryPlaybackSpeed(exoPlayer, binding)
            if (!playbackMetrics.isActive) return
            val metrics = playbackMetrics.onPlaybackStateChanged(
                newPlaybackState = playbackState,
                playbackExpected = exoPlayer.playWhenReady &&
                    !mutableSnapshot.value.isPaused,
            )
            if (metrics.rebufferCount > reportedAdaptiveRebufferCount) {
                trace("rebuffer started count=${metrics.rebufferCount}")
                reportedAdaptiveRebufferCount = metrics.rebufferCount
                adaptiveRebufferActive = true
                adaptivePreloadController.onRebufferStarted()
                networkMetrics.onRebuffer()
                // Current playback takes priority over speculative work during
                // rebuffering, even if the progress ticker is paused.
                discardStandby()
                playerLifecycle.releaseStandby()
            }
            if (playbackState == Player.STATE_READY && adaptiveRebufferActive) {
                adaptiveRebufferActive = false
                adaptivePreloadController.onRebufferRecovered()
            }
            if (playbackState == Player.STATE_READY && seekRiskActive) {
                seekRiskActive = false
                activeRangeSession?.onFirstFrame()
            }
            refreshPlaybackProgress()
            tracePlaybackMetrics("state", metrics)
            if (playbackState == Player.STATE_ENDED) stopProgressUpdates()
            if (playbackState == Player.STATE_READY && exoPlayer.playWhenReady &&
                !mutableSnapshot.value.isPaused
            ) {
                startProgressUpdates()
            }
            if (playbackState == Player.STATE_READY && !promotionStartupPending) {
                if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) transitionMetrics.onReady(video.key)
                val firstReady = firstReadyWaitMillis == null
                val firstReadyWait = firstReadyWaitMillis ?: (
                    (System.nanoTime() - bindStartedAtNanos) / 1_000_000L
                ).also { firstReadyWaitMillis = it }
                if (firstReady) trace("playable chatId=${video.key.chatId} messageId=${video.key.messageId} bindToReadyMs=$firstReadyWait")
                val ready = VideoPlaybackState.Ready(
                    video = video,
                    firstReadyWaitMillis = firstReadyWait,
                    observedLocalBytes =
                        gateway.currentSnapshot(video.playbackFileId)?.downloadedSize,
                )
                updatePlaybackState(ready)
                trace(
                    "ready waitMillis=${ready.firstReadyWaitMillis} " +
                        "localBytes=${ready.observedLocalBytes}",
                )
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!callbackGate.accepts(binding)) return
            trace("playIntent playWhenReady=$playWhenReady reason=$reason " +
                "pageTransition=$pageTransitionPaused promotionPending=$promotionStartupPending")
            // Media3 also changes intent for audio focus, noisy output and remote controls.
            // READY describes buffered media, not permission to play. Observe that intent;
            // never force play() merely because position has stopped advancing.
            if (!pageTransitionPaused && !promotionStartupPending &&
                exoPlayer.playbackState != Player.STATE_ENDED
            ) {
                val wantsPlayback = exoPlayer.playWhenReady
                mutableSnapshot.value = mutableSnapshot.value.copy(isPaused = !wantsPlayback)
                if (wantsPlayback) startProgressUpdates() else stopProgressUpdates()
            }
            reconcileTemporaryPlaybackSpeed(exoPlayer, binding)
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            if (!callbackGate.accepts(binding)) return
            trace("suppression reason=$playbackSuppressionReason")
            reconcileTemporaryPlaybackSpeed(exoPlayer, binding)
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            if (!callbackGate.accepts(binding) || !playerViewBinding.isAttachedTo(exoPlayer)) return
            if (mutableSnapshot.value.playbackState.videoKeyOrNull() != video.key) return
            var currentVisibleOutput = false
            playerViewBinding.forActiveView { view ->
                val surfaceView = view.videoSurfaceView as? android.view.SurfaceView
                currentVisibleOutput = view.isShown && view.windowVisibility == android.view.View.VISIBLE &&
                    surfaceView?.holder?.surface === output
            }
            // Analytics carries the rendered output. A queued callback from C2's
            // offscreen Surface cannot become a visible frame after promotion.
            if (!currentVisibleOutput) return
            val firstFrameObservedAtMillis = System.nanoTime() / NANOS_PER_MILLISECOND
            val firstFrameBufferedDurationMillis =
                (exoPlayer.bufferedPosition - exoPlayer.currentPosition).coerceAtLeast(0L)
            val firstRangeReady = if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED && !firstByteMetricLogged) {
                activeRangeSession?.firstRangeReady()
            } else {
                null
            }
            val transitionSnapshot = if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) {
                // Capture the physical callback boundary before StateFlow observers,
                // priority demotion, and next-preload restoration do synchronous work.
                firstRangeReady?.let { firstRange ->
                    firstByteMetricLogged = true
                    transitionMetrics.onFirstByte(
                        key = video.key,
                        observedAtMillis = firstRange.atNanos / NANOS_PER_MILLISECOND,
                    )
                }
                if (exoPlayer.playbackState == Player.STATE_READY && !promotionStartupPending) {
                    transitionMetrics.onReady(video.key, firstFrameObservedAtMillis)
                }
                transitionMetrics.onFirstFrame(
                    key = video.key,
                    observedAtMillis = firstFrameObservedAtMillis,
                    bufferedDurationMillis = firstFrameBufferedDurationMillis,
                )
            } else {
                null
            }
            mutableSnapshot.value = mutableSnapshot.value.copy(
                hasRenderedFirstFrame = true,
            )
            seekRiskActive = false
            playbackMetrics.markFirstFrame()
            activeRangeSession?.onFirstFrame()
            adaptivePreloadController.onFirstFrame(
                bindToFirstFrameMillis = (
                    firstFrameObservedAtMillis - bindStartedAtNanos / NANOS_PER_MILLISECOND
                    ).coerceAtLeast(0L),
            )
            if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) {
                firstRangeReady?.let { firstRange ->
                    val bindToFirstByteMillis =
                        (firstRange.atNanos - bindStartedAtNanos)
                            .coerceAtLeast(0L) / NANOS_PER_MILLISECOND
                    trace(
                        "range first-byte fileId=${firstRange.fileId} " +
                            "priority=${firstRange.priority} " +
                            "bindToFirstByteMs=$bindToFirstByteMillis",
                    )
                }
                // A visible still frame may precede playable READY. Never fabricate its
                // timestamp; the independent playable event records actual readiness.
                traceStartupRangeSummary(
                    requestSession = activeRangeSession,
                    transitionSnapshot = transitionSnapshot,
                )
                transitionSnapshot?.let(::traceTransitionSummary)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!callbackGate.accepts(binding)) return
            // Do not keep NEXT decoder/request/lease resources alive while the
            // current item is failing or being retried.
            discardStandby()
            playerLifecycle.releaseStandby()
            if (activeWasPoolPromoted) {
                activeWasPoolPromoted = false
                playbackPoolCoordinator.dispatch(PlaybackPoolIntent.FailSession(
                    PlaybackPoolFallbackReason.DECODER_RESOURCE))
                trace("pool promotion fallback code=${error.errorCode}")
                bindInternal(video, null)
                return
            }
            if (fallbackFromHls(video, binding)) {
                trace("hls error code=${error.errorCode} result=MP4_FALLBACK")
                return
            }
            restoreNormalPlaybackSpeed(TemporaryPlaybackSpeedTermination.FAILURE)
            stopProgressUpdates()
            activeRangeSession?.close()
            activeRangeSession = null
            currentProtection?.close()
            currentProtection = null
            val failure = mapVideoPlaybackFailure(error.errorCode, error.cause)
            val diagnostic = mapPlaybackFailureDiagnostic(error.errorCode, error.cause)
            updatePlaybackState(
                VideoPlaybackState.Failed(
                    video = video,
                    reason = failure,
                ),
            )
            adaptivePreloadController.onPlaybackFailure()
            trace(
                "error category=$failure diagnostic=$diagnostic code=${error.errorCode}",
            )
            if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED && failure != VideoPlaybackFailure.FILE_UNAVAILABLE) {
                transitionMetrics.onFailure(video.key)?.let(::traceTransitionSummary)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (callbackGate.accepts(binding)) refreshPlaybackProgress()
        }

        override fun onTimelineChanged(
            timeline: androidx.media3.common.Timeline,
            reason: Int,
        ) {
            if (callbackGate.accepts(binding)) refreshPlaybackProgress()
        }
    }

    private fun detachActivePlayerListener() {
        val listener = activePlayerListener ?: return
        currentPlayer()?.removeListener(listener)
        currentPlayer()?.removeAnalyticsListener(listener)
        activePlayerListener = null
    }

    private fun retireCurrentBindingForReplacement() {
        promotionStartupPending = false
        // A replaced binding must never carry a completion into the next video. The listener
        // callback would normally overwrite this, but a promoted standby keeps its own engine
        // and state across the swap, and the auto-advance consumer must see a clean false before
        // the new binding reports READY.
        mutableSnapshot.value = mutableSnapshot.value.copy(hasEnded = false)
        restoreNormalPlaybackSpeed(TemporaryPlaybackSpeedTermination.NEW_BINDING)
        activeRangeSession?.close()
        activeRangeSession = null
        activeHlsSession?.close()
        activeHlsSession = null
        activeSourceKind = PlaybackSourceKind.PROGRESSIVE
        hlsFallbackGate.clear()
        tracePlaybackSummary()
        playbackMetrics.reset()
        stopProgressUpdates()
        resetPlaybackProgress()
    }

    private fun clearCurrentBinding() {
        retireCurrentBindingForReplacement()
        playerLifecycle.releaseBinding()
    }

    private fun fallbackFromHls(
        video: IndexedVideo,
        binding: PlaybackBindingToken,
    ): Boolean {
        if (!hlsFallbackGate.tryFallback(binding.generation, activeSourceKind)) return false
        promotionStartupPending = false
        activeRangeSession?.close()
        activeHlsSession?.close()
        activeHlsSession = null
        activeSourceKind = PlaybackSourceKind.PROGRESSIVE
        val rangeSession = PlaybackRangeRequestSession()
        activeRangeSession = rangeSession
        updatePlaybackState(VideoPlaybackState.Loading(video), isPaused = false)
        playerLifecycle.bind(
            media = createProgressiveMediaSource(
                video = video,
                rangeSession = rangeSession,
                acceptsRangeCallback = { callbackGate.accepts(binding) },
            ),
            bindingGeneration = binding.generation,
            onPrepare = {
                if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) transitionMetrics.onPrepare(video.key)
            },
        )
        return true
    }

    private fun resetPlaybackProgress() {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            positionMillis = 0L,
            durationMillis = 0L,
            bufferedPositionMillis = 0L,
            isSeekable = false,
        )
    }

    private fun updatePlaybackState(
        state: VideoPlaybackState,
        isPaused: Boolean = mutableSnapshot.value.isPaused,
    ) {
        mutableState.value = state
        mutableSnapshot.value = mutableSnapshot.value.copy(
            playbackState = state,
            isPaused = isPaused,
            hasEnded = false,
            isPlaying = when (state) {
                is VideoPlaybackState.Ready -> mutableSnapshot.value.isPlaying
                VideoPlaybackState.Idle,
                is VideoPlaybackState.Loading,
                is VideoPlaybackState.Unsupported,
                is VideoPlaybackState.Failed,
                -> false
            },
            playbackSpeed = when (state) {
                is VideoPlaybackState.Ready -> mutableSnapshot.value.playbackSpeed
                VideoPlaybackState.Idle,
                is VideoPlaybackState.Loading,
                is VideoPlaybackState.Unsupported,
                is VideoPlaybackState.Failed,
                -> VideoPlaybackSpeeds.NORMAL
            },
            hasRenderedFirstFrame = when (state) {
                is VideoPlaybackState.Ready ->
                    mutableSnapshot.value.playbackState.videoKeyOrNull() == state.video.key &&
                        mutableSnapshot.value.hasRenderedFirstFrame
                VideoPlaybackState.Idle,
                is VideoPlaybackState.Loading,
                is VideoPlaybackState.Unsupported,
                is VideoPlaybackState.Failed,
                -> false
            },
        )
    }

    private fun restoreNormalPlaybackSpeed(reason: TemporaryPlaybackSpeedTermination) {
        val applied = playerLifecycle.terminateTemporaryPlaybackSpeed(reason)
        if (mutableSnapshot.value.playbackSpeed != applied) {
            mutableSnapshot.value = mutableSnapshot.value.copy(playbackSpeed = applied)
        }
    }

    private fun reconcileTemporaryPlaybackSpeed(
        exoPlayer: ExoPlayer,
        binding: PlaybackBindingToken,
    ) {
        val applied = playerLifecycle.reconcileTemporaryPlaybackSpeed(
            bindingGeneration = binding.generation,
            playbackState = when (exoPlayer.playbackState) {
                Player.STATE_BUFFERING -> ReusablePlaybackState.BUFFERING
                Player.STATE_READY -> ReusablePlaybackState.READY
                Player.STATE_ENDED -> ReusablePlaybackState.ENDED
                else -> ReusablePlaybackState.IDLE
            },
            isPlaying = exoPlayer.isPlaying,
            playWhenReady = exoPlayer.playWhenReady,
            isSuppressed = exoPlayer.playbackSuppressionReason !=
                Player.PLAYBACK_SUPPRESSION_REASON_NONE,
        )
        if (mutableSnapshot.value.playbackSpeed != applied) {
            mutableSnapshot.value = mutableSnapshot.value.copy(playbackSpeed = applied)
        }
    }

    private fun trace(message: String) {
        if (BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED) Log.i(LOG_TAG, message)
    }

    private fun startProgressUpdates() {
        if (progressTickerRunning) return
        progressTickerRunning = true
        progressHandler.removeCallbacks(progressTicker)
        progressHandler.post(progressTicker)
    }

    private fun stopProgressUpdates() {
        progressTickerRunning = false
        progressHandler.removeCallbacks(progressTicker)
    }

    private fun refreshPlaybackProgress() {
        val exoPlayer = currentPlayer() ?: return
        reconcileStartupThreshold()
        if (promotionStartupPending && progressTickerRunning &&
            !mutableSnapshot.value.isPaused && exoPlayer.playbackState == Player.STATE_READY &&
            hasStartupReservoir(exoPlayer)
        ) {
            promotionStartupPending = false
            activePlayerListener?.onPlaybackStateChanged(Player.STATE_READY)
            exoPlayer.play()
        }
        val duration = normalizedDuration(exoPlayer.duration)
        val position = exoPlayer.currentPosition
            .coerceAtLeast(0L)
            .let { current -> if (duration > 0L) current.coerceAtMost(duration) else current }
        val buffered = exoPlayer.bufferedPosition
            .coerceAtLeast(position)
            .let { current -> if (duration > 0L) current.coerceAtMost(duration) else current }
        mutableSnapshot.value = mutableSnapshot.value.copy(
            positionMillis = position,
            durationMillis = duration,
            bufferedPositionMillis = buffered,
            isSeekable = duration > 0L && exoPlayer.isCurrentMediaItemSeekable,
        )
        applyPlaybackRisk(exoPlayer, bufferedAheadMillis = (buffered - position).coerceAtLeast(0L))
        maybeTracePlaybackSample()
    }

    private fun hasStartupReservoir(player: ExoPlayer): Boolean {
        val current = playerLifecycle.currentEngine as? ExoPlayerReusableEngine
        val prepared = playerLifecycle.preparedEngine as? ExoPlayerReusableEngine
        val engine = if (current?.player === player) current else prepared?.takeIf { it.player === player }
        return hasPoolStartupReservoir(
            positionMillis = player.currentPosition,
            bufferedPositionMillis = player.bufferedPosition,
            durationMillis = normalizedDuration(player.duration),
            requiredMillis = if (current?.player === player) activeStartupMillis.get()
                else standbyRecord?.video?.let(::startupMillisFor)
                    ?: bufferPolicy.bufferForPlaybackMillis.toLong(),
            sampleMemoryCeilingReached = (engine?.allocatedSampleBytes ?: 0) >=
                PoolLoadControl.ACTIVE_SAMPLE_CEILING_BYTES,
        )
    }

    /**
     * Bounds the worst-case black screen for slow high-bitrate media.
     *
     * The static startup thresholds are calibrated for a link whose throughput is at least
     * comparable to the media bitrate. A 19 Mbps original on an 8 Mbps link can never reach a
     * 2.5 s reserve quickly, and device evidence showed 10-16 s of waiting with zero rendered
     * frames; while waiting, no first frame means the adaptive preloader stays degraded and the
     * following swipe is always a cold start. After a short grace window the threshold decays
     * linearly to a floor that still covers one strong throughput dip, so the picture appears
     * early and the remaining buffer builds during playback. The rebuffer-recovery threshold is
     * a different constant and is not touched.
     */
    private fun reconcileStartupThreshold() {
        val video = boundVideo ?: return
        if (mutableSnapshot.value.hasRenderedFirstFrame) return
        when (mutableSnapshot.value.playbackState) {
            is VideoPlaybackState.Unsupported, is VideoPlaybackState.Failed -> return
            else -> Unit
        }
        val startedAt = bindStartedAtNanos
        if (startedAt == 0L) return
        val elapsedMillis = ((System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND)
            .coerceAtLeast(0L)
        val base = startupMillisFor(video)
        val relaxed = relaxedStartupMillis(base, elapsedMillis)
        if (relaxed < activeStartupMillis.get()) {
            activeStartupMillis.set(relaxed)
            trace(
                "startup threshold relaxed base=${base}ms relaxed=${relaxed}ms " +
                    "elapsedMs=$elapsedMillis",
            )
        }
    }

    private fun startupMillisFor(video: IndexedVideo): Long {
        val estimate = networkMetrics.estimate.value
        return conditionalStartupMillis(
            video.playbackFileSize, video.durationSeconds,
            minOf(video.playbackWidth, video.playbackHeight),
            estimate?.fastBitsPerSecond, estimate?.slowBitsPerSecond,
            estimate?.timeToFirstByteP90Millis,
            bufferPolicy.bufferForPlaybackMillis.toLong(),
        )
    }

    private fun applyPlaybackRisk(exoPlayer: ExoPlayer, bufferedAheadMillis: Long) {
        val estimate = networkMetrics.estimate.value
        val now = System.nanoTime() / NANOS_PER_MILLISECOND
        val elapsed = (now - lastAbrEvaluationMillis).coerceAtLeast(1L)
        val slope = if (lastAbrEvaluationMillis == 0L) {
            0.0
        } else {
            (bufferedAheadMillis - lastAbrBufferedAheadMillis).toDouble() / elapsed.toDouble()
        }
        lastAbrEvaluationMillis = now
        lastAbrBufferedAheadMillis = bufferedAheadMillis
        val preloadDecision = adaptivePreloadController.decision.value
        val riskState = when {
            adaptiveRebufferActive -> PlaybackRiskState.REBUFFER
            seekRiskActive -> PlaybackRiskState.SEEK
            promotionStartupPending || !mutableSnapshot.value.hasRenderedFirstFrame -> PlaybackRiskState.STARTUP
            else -> PlaybackRiskState.PLAYING
        }
        latestPreloadSafety = NextPreloadSafetySnapshot(
                playbackState = riskState,
                currentBufferedSeconds = bufferedAheadMillis / 1_000.0,
                bufferSlopeSecondsPerSecond = slope,
                fastThroughputBitsPerSecond = estimate?.fastBitsPerSecond,
                slowThroughputBitsPerSecond = estimate?.slowBitsPerSecond,
                timeToFirstByteP90Millis = estimate?.timeToFirstByteP90Millis,
                isMetered = !preloadDecision.isUnmeteredWifi,
                isMobileNetwork = estimate?.network == NetworkTransport.MOBILE,
                mobileDataPreloadEnabled = preloadDecision.mobileDataPreloadEnabled,
                isPowerSaver = preloadDecision.reason == AdaptivePreloadReason.POWER_SAVE,
                hasThermalPressure = preloadDecision.reason == AdaptivePreloadReason.THERMAL,
                hasStoragePressure = preloadDecision.reason == AdaptivePreloadReason.STORAGE_LOW,
                hasMemoryPressure = preloadDecision.reason == AdaptivePreloadReason.MEMORY_LOW,
                remainingTimelineBuffered = normalizedDuration(exoPlayer.duration) > 0L &&
                    exoPlayer.bufferedPosition >= normalizedDuration(exoPlayer.duration),
                networkGeneration = currentNetworkGeneration(),
            )
        videoPreloadController.updateCurrentPlaybackSafety(latestPreloadSafety)
        updatePoolPreparation()
        if (!BuildConfig.HYBRID_ABR_ENABLED || activeSourceKind != PlaybackSourceKind.HLS) return
        val video = boundVideo ?: return
        val bitrates = video.hlsCapableVariants
            .map { variant ->
                variant.fileSize
                    ?.takeIf { it > 0L && video.durationSeconds > 0 }
                    ?.let { it * 8L / video.durationSeconds }
                    ?: when {
                        variant.height <= 360 -> 450_000L
                        variant.height <= 480 -> 800_000L
                        variant.height <= 720 -> 1_500_000L
                        else -> 3_000_000L
                    }
            }
            .sorted()
        if (bitrates.isEmpty()) return
        val format = exoPlayer.videoFormat
        val currentBitrate = listOfNotNull(
            format?.peakBitrate?.takeIf { it > 0 }?.toLong(),
            format?.averageBitrate?.takeIf { it > 0 }?.toLong(),
            format?.bitrate?.takeIf { it > 0 }?.toLong(),
        ).maxOrNull() ?: bitrates.first()
        val candidate = bitrates.firstOrNull { it > currentBitrate } ?: currentBitrate
        val request = gateway.currentNetworkRequest()
            ?.takeIf { it.ownerKind == TelegramFileOwnerKind.CURRENT_PLAYBACK }
        val decision = playbackRiskController.evaluate(
            PlaybackRiskInput(
                fastThroughputBitsPerSecond = estimate?.fastBitsPerSecond,
                slowThroughputBitsPerSecond = estimate?.slowBitsPerSecond,
                timeToFirstByteP50Millis = estimate?.timeToFirstByteP50Millis,
                timeToFirstByteP90Millis = estimate?.timeToFirstByteP90Millis,
                currentBufferedDurationMillis = bufferedAheadMillis,
                bufferSlopeSecondsPerSecond = slope,
                currentRepresentationBitrate = currentBitrate,
                candidatePeakBitrate = candidate,
                minimumRepresentationBitrate = bitrates.first(),
                currentRequestDownloadedBytes = request?.downloadedBytes ?: 0L,
                currentRequestRemainingBytes = request?.remainingBytes ?: 0L,
                nextPlayableSeconds = 0.0,
                playbackState = riskState,
                isMetered = !preloadDecision.isUnmeteredWifi,
                isPowerSaver = preloadDecision.reason == AdaptivePreloadReason.POWER_SAVE,
                hasThermalPressure = preloadDecision.reason == AdaptivePreloadReason.THERMAL,
                hasStoragePressure = preloadDecision.reason == AdaptivePreloadReason.STORAGE_LOW,
                networkGeneration = estimate?.networkGeneration ?: networkMetrics.contextRevision,
                nowMillis = now,
            ),
        )
        val nextMax = when (decision.action) {
            PlaybackRiskAction.DOWNGRADE,
            PlaybackRiskAction.ABANDON_REQUEST,
            PlaybackRiskAction.ACCUMULATE_RESERVOIR,
            -> decision.maximumSafeBitrate.coerceAtMost(currentBitrate)
            PlaybackRiskAction.UPGRADE -> candidate
            PlaybackRiskAction.KEEP -> null
        }?.coerceIn(bitrates.first(), Int.MAX_VALUE.toLong())?.toInt()
        if (nextMax != null && nextMax != lastAbrMaxBitrate) {
            if (decision.action == PlaybackRiskAction.ABANDON_REQUEST) {
                // Cancels the bounded TDLib lease. Media3's HLS loader retries under the new
                // track ceiling; the reusable player and playback binding stay intact.
                activeRangeSession?.onUserSeek()
            }
            (exoPlayer.trackSelector as? DefaultTrackSelector)?.let { selector ->
                selector.setParameters(
                    selector.buildUponParameters().setMaxVideoBitrate(nextMax),
                )
                lastAbrMaxBitrate = nextMax
            }
        }
        if (decision.action != PlaybackRiskAction.KEEP || decision.reason != lastAbrReason) {
            trace(
                "abr action=${decision.action} reason=${decision.reason} " +
                    "bufferMs=$bufferedAheadMillis slope=$slope " +
                    "completeMs=${decision.predictedCompletionMillis} " +
                    "deadlineMs=${decision.starvationDeadlineMillis} maxBitrate=${decision.maximumSafeBitrate}",
            )
            lastAbrReason = decision.reason
        }
    }

    private fun maybeTracePlaybackSample() {
        if (!BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED || !playbackMetrics.isActive) return
        val now = System.nanoTime()
        if (
            lastPlaybackSampleAtNanos != 0L &&
            now - lastPlaybackSampleAtNanos < PLAYBACK_SAMPLE_INTERVAL_NANOS
        ) {
            return
        }
        lastPlaybackSampleAtNanos = now
        tracePlaybackMetrics("sample", playbackMetrics.snapshot())
    }

    private fun tracePlaybackSummary() {
        if (!BuildConfig.PLAYBACK_DIAGNOSTICS_ENABLED || !playbackMetrics.isActive) return
        val snapshot = mutableSnapshot.value
        val metrics = playbackMetrics.snapshot()
        trace(
                "summary firstReadyWaitMs=$firstReadyWaitMillis " +
                "state=${metrics.stateName} positionMs=${snapshot.positionMillis} " +
                "bufferedMs=${snapshot.bufferedPositionMillis} " +
                "aheadMs=${(snapshot.bufferedPositionMillis - snapshot.positionMillis).coerceAtLeast(0L)} " +
                "sampleBufferBytes=${(playerLifecycle.currentEngine as? ExoPlayerReusableEngine)?.allocatedSampleBytes ?: 0} " +
                "rebufferCount=${metrics.rebufferCount} " +
                "totalRebufferMs=${metrics.totalRebufferDurationMillis}" +
                metrics.windowLogFields(),
        )
    }

    private fun tracePlaybackMetrics(
        event: String,
        metrics: PlaybackBufferingMetrics,
    ) {
        val snapshot = mutableSnapshot.value
        trace(
            "$event state=${metrics.stateName} positionMs=${snapshot.positionMillis} " +
                "bufferedMs=${snapshot.bufferedPositionMillis} " +
                "aheadMs=${(snapshot.bufferedPositionMillis - snapshot.positionMillis).coerceAtLeast(0L)} " +
                "rebufferCount=${metrics.rebufferCount} " +
                "activeRebufferMs=${metrics.activeRebufferDurationMillis} " +
                "totalRebufferMs=${metrics.totalRebufferDurationMillis}" +
                metrics.windowLogFields() +
                standbyDiagnostics() +
                (
                    metrics.recoveredRebufferDurationMillis?.let { recovered ->
                        " recoveredRebufferMs=$recovered"
                    } ?: ""
                    ),
        )
    }

    /**
     * Makes the contention between the current stream and the bounded next item observable instead
     * of inferred. `standbyHeld` is 1 while a next item owns an engine; `standbyAheadMs` is the
     * reserve that next item has actually accumulated, which is the quantity the swap-in speed
     * depends on. Without this, a stalled preparation is indistinguishable from an idle one.
     */
    private fun standbyDiagnostics(): String {
        val gate = " standbyGate[$standbyGateTrace]"
        val record = standbyRecord ?: return " standbyHeld=0$gate"
        val player = (playerLifecycle.preparedEngine as? ExoPlayerReusableEngine)?.player
        val ahead = player?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L) } ?: 0L
        return " standbyHeld=1 standbyYielding=${standbyBandwidthClaim.get()}" +
            " standbyReadAheadBytes=${currentReadAheadBytes()}" +
            " standbyAheadMs=$ahead" +
            " standbyReady=${player?.playbackState == Player.STATE_READY}" +
            " standbyDecoded=${record.decodedFrame}" +
            gate
    }

    private fun traceTransitionSummary(snapshot: PlaybackTransitionSnapshot) {
        val key = snapshot.key
        Log.i(
            TRANSITION_LOG_TAG,
            "summary outcome=${snapshot.outcome} " +
                "candidate=${bufferPolicy.candidateId} " +
                "poolCandidate=$playbackPoolCandidate poolPromoted=$activeWasPoolPromoted " +
                "poolPreparedReady=$activePoolPreparedReady poolDecodedFrame=$activePoolDecodedFrame " +
                "order=${snapshot.order} direction=${snapshot.direction} " +
                "randomRoundBoundary=${snapshot.randomRoundBoundary} " +
                "chatId=${key?.chatId} messageId=${key?.messageId} " +
                "refreshOutcome=${snapshot.refreshOutcome} " +
                "transparentRecoveryAttempts=${snapshot.transparentRecoveryAttemptCount} " +
                "transparentRecoveryOutcome=${snapshot.transparentRecoveryOutcome} " +
                "promoted=${snapshot.promoted} planAgeMs=${snapshot.planAgeMillis} " +
                "gestureToSettleMs=${snapshot.gestureToSettledMillis} " +
                "settleToPlanMs=${snapshot.settledToPlanMillis} " +
                "refreshMs=${snapshot.refreshMillis} " +
                "planToBindMs=${snapshot.planToBindMillis} " +
                "bindToPrepareMs=${snapshot.bindToPrepareMillis} " +
                "prepareToReadyMs=${snapshot.prepareToReadyMillis} " +
                "bindToFirstByteMs=${snapshot.bindToFirstByteMillis} " +
                "bindToReadyMs=${snapshot.bindToReadyMillis} " +
                "readyToFirstFrameMs=${snapshot.readyToFirstFrameMillis} " +
                "firstFrameBufferedMs=${snapshot.firstFrameBufferedDurationMillis} " +
                "surfaceAttachCount=$surfaceAttachCount " +
                "surfaceDetachCount=$surfaceDetachCount " +
                "playerInstances=${playerLifecycle.instanceCount} " +
                "gestureToReleaseMs=${snapshot.gestureToReleaseMillis} " +
                "gestureToTargetKnownMs=${snapshot.gestureToTargetKnownMillis} " +
                "releaseToSettleMs=${snapshot.releaseToSettledMillis} " +
                "targetKnownToSettleMs=${snapshot.targetKnownToSettledMillis} " +
                "targetKnownToPlanReadyMs=${snapshot.targetKnownToPlanPreparedMillis} " +
                "planReadyToSettleMs=${snapshot.planPreparedToSettledMillis} " +
                "bindToTerminalMs=${snapshot.bindToTerminalMillis} " +
                "settleToTerminalMs=${snapshot.settledToTerminalMillis} " +
                "releaseToTerminalMs=${snapshot.releaseToTerminalMillis} " +
                "targetKnownToTerminalMs=${snapshot.targetKnownToTerminalMillis} " +
                "gestureToTerminalMs=${snapshot.gestureToTerminalMillis}",
        )
    }

    private fun traceStartupRangeSummary(
        requestSession: PlaybackRangeRequestSession?,
        transitionSnapshot: PlaybackTransitionSnapshot?,
    ) {
        val observation = requestSession?.startupRangeObservation() ?: return
        val firstByteToReadyMillis = transitionSnapshot?.let { transition ->
            val firstByte = transition.bindToFirstByteMillis ?: return@let null
            val ready = transition.bindToReadyMillis ?: return@let null
            (ready - firstByte).coerceAtLeast(0L)
        }
        val handoff = videoPreloadController.ownerHandoff.value
        val reusedNextOwner = handoff.promotionMatched && handoff.reusedActiveRequest == true
        Log.i(
            STARTUP_RANGE_LOG_TAG,
            "summary candidate=${BuildConfig.STARTUP_RANGE_CANDIDATE} " +
                "firstMissCategory=${observation.firstMissCategory ?: "NONE"} " +
                "coveredBeforeCurrentBytes=${observation.coveredBeforeCurrentBytes} " +
                "dataSpecOpenCount=${observation.dataSpecOpenCount} " +
                "extractorRangeSwitchCount=${observation.extractorRangeSwitchCount} " +
                "currentReusedNextOwner=$reusedNextOwner " +
                "firstByteToReadyMs=$firstByteToReadyMillis",
        )
    }

    private fun normalizedDuration(durationMillis: Long): Long =
        durationMillis.takeUnless { it == C.TIME_UNSET || it < 0L } ?: 0L

    private fun currentPlayer(): ExoPlayer? =
        (playerLifecycle.currentEngine as? ExoPlayerReusableEngine)?.player

    private fun PlaybackBufferingMetrics.windowLogFields(): String =
        " firstFrameElapsedMs=$firstFrameElapsedMillis" +
            " rebuffer30Count=${rebufferAt30Seconds?.count}" +
            " rebuffer30Ms=${rebufferAt30Seconds?.durationMillis}" +
            " rebuffer60Count=${rebufferAt60Seconds?.count}" +
            " rebuffer60Ms=${rebufferAt60Seconds?.durationMillis}"

    private companion object {
        const val MIN_BUFFER_MILLIS = 50_000
        const val MAX_BUFFER_MILLIS = 60_000
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MILLIS = 12_000
        const val PROGRESS_UPDATE_INTERVAL_MILLIS = 250L
        const val PLAYBACK_SAMPLE_INTERVAL_MILLIS = 5_000L
        const val PLAYBACK_SAMPLE_INTERVAL_NANOS =
            PLAYBACK_SAMPLE_INTERVAL_MILLIS * 1_000_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_PLAYER_LOAD_RETRY_COUNT = 3
        const val NORMAL_VOLUME = 1f
        const val MUTED_VOLUME = 0f
        const val LOG_TAG = "CVF-Player"
        const val TRANSITION_LOG_TAG = "CVF-Transition"
        const val STARTUP_RANGE_LOG_TAG = "CVF-StartupRange"
        const val MIB = 1024L * 1024L

        /** Peak bitrate allowance over the average for variable-rate encodes. */
        const val BITRATE_PEAK_HEADROOM = 1.25

        /**
         * Read-ahead window the current stream uses per request while it is yielding the link to an
         * admitted next item. One mebibyte is a few hundred milliseconds of media at typical mobile
         * bitrates, so the handover drains quickly, while staying above the 512 KiB preload chunk
         * and Media3's own request size so one request is still satisfied by one window.
         */
        const val YIELDING_ACTIVE_READ_AHEAD_BYTES = 1L * 1024L * 1024L
        val STANDBY_BLOCKING_REASONS = setOf(
            AdaptivePreloadReason.OFFLINE,
            AdaptivePreloadReason.NETWORK_NOT_ALLOWED,
            AdaptivePreloadReason.POWER_SAVE,
            AdaptivePreloadReason.STORAGE_LOW,
            AdaptivePreloadReason.MEMORY_LOW,
            AdaptivePreloadReason.THERMAL,
            AdaptivePreloadReason.NETWORK_CHANGED,
            AdaptivePreloadReason.REBUFFER,
            AdaptivePreloadReason.CONSECUTIVE_FAILURES,
        )
        val PROTECTION_COUNTER = AtomicLong()
        val POOL_TOKEN_COUNTER = AtomicLong()
    }

    private fun VideoPlaybackState.videoKeyOrNull() = when (this) {
        VideoPlaybackState.Idle -> null
        is VideoPlaybackState.Loading -> video.key
        is VideoPlaybackState.Ready -> video.key
        is VideoPlaybackState.Unsupported -> video.key
        is VideoPlaybackState.Failed -> video.key
    }
}

@UnstableApi
private interface ActivePlaybackListener : Player.Listener, AnalyticsListener

@UnstableApi
internal class ExoPlayerReusableEngine(
    val player: ExoPlayer,
    private val active: java.util.concurrent.atomic.AtomicBoolean,
    private val allocator: androidx.media3.exoplayer.upstream.Allocator? = null,
) : ReusablePlayerEngine<MediaSource> {
    val allocatedSampleBytes: Int get() = allocator?.totalBytesAllocated ?: 0
    override val playbackSpeed: Float
        get() = player.playbackParameters.speed

    override fun setActive(active: Boolean) {
        this.active.set(active)
        player.setAudioAttributes(VideoAudioPolicy.attributes, active && VideoAudioPolicy.handleAudioFocus)
        if (!active) {
            player.playWhenReady = false
            player.volume = 0f
        }
    }

    override fun setMedia(media: MediaSource) {
        player.setMediaSource(media)
    }

    override fun prepare() {
        player.prepare()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        player.playWhenReady = playWhenReady
    }

    override fun pause() {
        player.pause()
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun clearMedia() {
        player.clearMediaItems()
    }

    override fun release() {
        player.release()
    }
}

internal data class PlaybackBindingToken(
    val generation: Long,
    val key: com.qixuan.channelvideoflow.model.video.VideoKey,
)

internal enum class PlaybackSourceKind {
    PROGRESSIVE,
    HLS,
}

/** Allows one HLS-to-MP4 fallback for the active binding without rebuilding the player. */
internal class HlsFallbackGate {
    private var generation: Long? = null
    private var fallbackUsed = false

    @Synchronized
    fun begin(bindingGeneration: Long, sourceKind: PlaybackSourceKind) {
        generation = bindingGeneration
        fallbackUsed = sourceKind != PlaybackSourceKind.HLS
    }

    @Synchronized
    fun tryFallback(bindingGeneration: Long, sourceKind: PlaybackSourceKind): Boolean {
        if (
            generation != bindingGeneration ||
            sourceKind != PlaybackSourceKind.HLS ||
            fallbackUsed
        ) {
            return false
        }
        fallbackUsed = true
        return true
    }

    @Synchronized
    fun clear() {
        generation = null
        fallbackUsed = false
    }
}

/** Rejects READY, first-frame, and error callbacks from superseded Media3 bindings. */
internal class PlaybackCallbackGate {
    private var generation = 0L
    private var active: PlaybackBindingToken? = null

    @Synchronized
    fun begin(key: com.qixuan.channelvideoflow.model.video.VideoKey): PlaybackBindingToken {
        generation += 1L
        return PlaybackBindingToken(generation, key).also { active = it }
    }

    @Synchronized
    fun accepts(token: PlaybackBindingToken): Boolean = active == token

    @Synchronized
    fun invalidate() {
        generation += 1L
        active = null
    }
}

internal fun mapVideoPlaybackFailure(
    errorCode: Int,
    cause: Throwable?,
): VideoPlaybackFailure = when {
    cause is TelegramMediaTimeoutException -> VideoPlaybackFailure.TIMEOUT
    cause is TelegramMediaUnavailableException -> VideoPlaybackFailure.FILE_UNAVAILABLE
    cause is TelegramMediaReadException -> VideoPlaybackFailure.FILE_UNAVAILABLE
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
        VideoPlaybackFailure.NETWORK
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        VideoPlaybackFailure.TIMEOUT
    errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
        VideoPlaybackFailure.FILE_UNAVAILABLE
    errorCode in setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    ) -> VideoPlaybackFailure.DECODER_UNSUPPORTED
    else -> VideoPlaybackFailure.PLAYER
}

internal enum class PlaybackFailureDiagnostic {
    DECODER_INITIALIZATION,
    DECODER_QUERY,
    DECODING,
    FORMAT,
    NETWORK,
    TIMEOUT,
    FILE,
    PLAYER,
}

internal fun mapPlaybackFailureDiagnostic(
    errorCode: Int,
    cause: Throwable?,
): PlaybackFailureDiagnostic = when {
    cause is TelegramMediaTimeoutException -> PlaybackFailureDiagnostic.TIMEOUT
    cause is TelegramMediaUnavailableException || cause is TelegramMediaReadException ->
        PlaybackFailureDiagnostic.FILE
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
        PlaybackFailureDiagnostic.NETWORK
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        PlaybackFailureDiagnostic.TIMEOUT
    errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
        PlaybackFailureDiagnostic.FILE
    errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
        PlaybackFailureDiagnostic.DECODER_INITIALIZATION
    errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
        PlaybackFailureDiagnostic.DECODER_QUERY
    errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    ) -> PlaybackFailureDiagnostic.DECODING
    errorCode in setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    ) -> PlaybackFailureDiagnostic.FORMAT
    else -> PlaybackFailureDiagnostic.PLAYER
}

internal object VideoAudioPolicy {
    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    const val handleAudioFocus: Boolean = true
}

/** Uses the production buffer policy after promotion; speculative sample memory stays bounded. */
@UnstableApi
internal class PoolLoadControl(
    delegate: androidx.media3.exoplayer.LoadControl,
    private val active: java.util.concurrent.atomic.AtomicBoolean,
    /**
     * Forward-buffer ceiling for the ACTIVE engine while a bounded next item is admitted.
     *
     * Rationale (docs/STAGE27_MOBILE_FAST_START.md §8): TDLib serves the current stream first, so
     * while the current still wants bytes the next item receives nothing at all. Device sampling
     * confirmed it — the next item held 0 ms of reserve in 13 of 16 observed windows, including
     * windows where the current had already banked 36 s. Letting the current stop at a large but
     * finite reserve releases the link for the remainder of the viewing window.
     *
     * The ceiling must stay strictly above the 12 s rebuffer threshold plus a margin, because the
     * reserve measured at swap time is exactly what absorbs the handoff. The deterministic
     * contention model in StandbyContentionSimulationTest rejects a 12 s ceiling for that reason.
     * Kept last-but-one so an existing trailing lambda still means "startup threshold".
     */
    private val activeBufferCapMicros: () -> Long = { Long.MAX_VALUE },
    private val startupMillis: () -> Long = { 2_500L },
) : ForwardingLoadControl(delegate) {
    override fun shouldContinueLoading(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean =
        if (active.get()) parameters.bufferedDurationUs < activeBufferCapMicros() &&
            delegate.getAllocator(parameters.playerId).totalBytesAllocated < ACTIVE_SAMPLE_CEILING_BYTES &&
            delegate.shouldContinueLoading(parameters)
        else parameters.bufferedDurationUs < STANDBY_BUFFER_TARGET_MICROS &&
            delegate.getAllocator(parameters.playerId).totalBytesAllocated < STANDBY_SAMPLE_CEILING_BYTES

    override fun shouldStartPlayback(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean =
        (active.get() && !parameters.rebuffering &&
            parameters.bufferedDurationUs / parameters.playbackSpeed.coerceAtLeast(1f) >= startupMillis() * 1_000L) ||
        (active.get() && parameters.bufferedDurationUs > 0L &&
            delegate.getAllocator(parameters.playerId).totalBytesAllocated >= ACTIVE_SAMPLE_CEILING_BYTES) ||
            delegate.shouldStartPlayback(parameters)

    companion object {
        // Time-only buffering can exceed the Redmi's 256 MiB Java heap for high bitrate
        // media. Stop loading at this sample allocation threshold, including while paused.
        // An in-flight sample may overshoot it; this is not a total-process memory limit.
        const val ACTIVE_SAMPLE_CEILING_BYTES = 32 * 1024 * 1024
        // Matches NextPreloadBudgetController.ABSOLUTE_MAX_BYTES so the decoder-side ceiling and the
        // request-side budget cannot disagree. A 480p standby naturally stays far below it.
        const val STANDBY_SAMPLE_CEILING_BYTES = 20 * 1024 * 1024
        /** Standby keeps loading until the same 5 second reserve the budget asks TDLib for. */
        const val STANDBY_BUFFER_TARGET_MICROS = 5_000_000L
        /**
         * Forward-buffer ceiling the current stream keeps while a metered link is preparing the
         * next item. Above the 12 s rebuffer threshold plus a 4 s margin, far below the 50 s
         * unmetered target, and only in effect while a next item is genuinely admitted.
         */
        const val METERED_ACTIVE_FORWARD_BUFFER_CAP_MICROS = 20_000_000L
    }
}

/**
 * Startup threshold derived from *sustainable headroom* instead of a fixed 2.5 s.
 *
 * With observed throughput at R times the media bitrate and R > 1, the reserve grows while the
 * video plays, so the initial buffer only has to absorb jitter rather than cover playback time.
 * Media3's short-form demo starts at 500 ms with no guard at all and the eatshots player documents
 * 2.5 s -> 500 ms as its single biggest win; VELORA keeps a guard so a metered link never trades a
 * rebuffer for a fast first frame. Only a low cost representation may use the fastest tier, because
 * a 480p standby avoids the decoder init and first-keyframe cost of an original.
 * Anything unknown falls back to the conservative value.
 */
internal fun conditionalStartupMillis(
    fileSize: Long?, durationSeconds: Int, shortEdge: Int,
    fastBitsPerSecond: Long?, slowBitsPerSecond: Long?, ttfbP90Millis: Long?,
    fallbackMillis: Long = 2_500L,
): Long {
    if (fileSize == null || fileSize <= 0 || durationSeconds <= 0) return fallbackMillis
    val fast = fastBitsPerSecond?.takeIf { it > 0L } ?: return fallbackMillis
    val slow = slowBitsPerSecond?.takeIf { it > 0L } ?: return fallbackMillis
    val ttfb = ttfbP90Millis?.takeIf { it >= 0L } ?: return fallbackMillis
    val bitrate = fileSize.toDouble() * 8.0 / durationSeconds
    if (bitrate <= 0.0) return fallbackMillis
    val headroom = minOf(fast, slow) / bitrate
    val lowCost = shortEdge in 1..FAST_START_MAX_SHORT_EDGE
    return when {
        lowCost && headroom >= 2.0 && ttfb <= 350L -> FAST_START_MILLIS
        lowCost && headroom >= 1.6 && ttfb <= 500L -> SLOW_FAST_START_MILLIS
        headroom >= 2.5 && ttfb <= 500L -> MODERATE_FAST_START_MILLIS
        else -> fallbackMillis
    }
}

/** Smallest reserve that still covers a throughput dip when headroom is at least 2x. */
internal const val FAST_START_MILLIS = 800L
internal const val SLOW_FAST_START_MILLIS = 1_200L
internal const val MODERATE_FAST_START_MILLIS = 1_800L
internal const val FAST_START_MAX_SHORT_EDGE = 480

/** Grace window before a waiting video's startup threshold starts decaying. */
internal const val STARTUP_RELAX_GRACE_MILLIS = 2_000L
/** Decay rate of the startup threshold while no first frame has been rendered. */
internal const val STARTUP_RELAX_STEP_PER_SECOND_MILLIS = 500L
/** Hard floor for the decayed threshold: one 1 s reserve still absorbs a strong dip. */
internal const val STARTUP_RELAX_FLOOR_MILLIS = 1_000L

/**
 * Decays the startup threshold while a video has not rendered its first frame yet.
 *
 * The base threshold never increases and thresholds already at or below the floor are left
 * untouched. See [VideoPlayerManager.reconcileStartupThreshold] for the rationale.
 */
internal fun relaxedStartupMillis(
    baseMillis: Long,
    elapsedMillis: Long,
    graceMillis: Long = STARTUP_RELAX_GRACE_MILLIS,
    stepPerSecondMillis: Long = STARTUP_RELAX_STEP_PER_SECOND_MILLIS,
    floorMillis: Long = STARTUP_RELAX_FLOOR_MILLIS,
): Long {
    if (baseMillis <= floorMillis) return baseMillis
    if (elapsedMillis <= graceMillis) return baseMillis
    val decay = ((elapsedMillis - graceMillis) / 1_000L) * stepPerSecondMillis
    return (baseMillis - decay).coerceAtLeast(floorMillis)
}

/** A bounded standby can be decoder-ready with too little media to sustain foreground playback. */
internal fun hasPoolStartupReservoir(
    positionMillis: Long,
    bufferedPositionMillis: Long,
    durationMillis: Long,
    requiredMillis: Long,
    sampleMemoryCeilingReached: Boolean = false,
): Boolean = (bufferedPositionMillis - positionMillis.coerceAtLeast(0L)).coerceAtLeast(0L) >= requiredMillis ||
    (durationMillis > 0L && bufferedPositionMillis >= durationMillis) ||
    (sampleMemoryCeilingReached && bufferedPositionMillis > positionMillis.coerceAtLeast(0L))
