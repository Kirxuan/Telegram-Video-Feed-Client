package com.qixuan.channelvideoflow.player

import android.util.Log
import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadController
import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadPolicyStateMachine
import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadReason
import com.qixuan.channelvideoflow.domain.media.AdaptivePreloadState
import com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase
import com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffSnapshot
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetController
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetDecision
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetInput
import com.qixuan.channelvideoflow.domain.media.NextPreloadSafetySnapshot
import com.qixuan.channelvideoflow.domain.media.NextPreloadStopReason
import com.qixuan.channelvideoflow.domain.media.NextPreloadBudgetTier
import com.qixuan.channelvideoflow.domain.media.TelegramFileGateway
import com.qixuan.channelvideoflow.domain.media.TelegramFileOwnerKind
import com.qixuan.channelvideoflow.domain.media.TelegramFileRequestPriority
import com.qixuan.channelvideoflow.domain.media.TelegramFileRangeLease
import com.qixuan.channelvideoflow.domain.media.VideoPreloadController
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Singleton
class VideoPreloadManager private constructor(
    private val gateway: TelegramFileGateway,
    private val adaptivePolicy: AdaptivePreloadController,
    private val scope: CoroutineScope,
    private val nowNanos: () -> Long,
    private val ownerPromotionEnabled: Boolean,
    private val startupCandidate: StartupPreloadCandidate,
    private val dynamicNextPreloadEnabled: Boolean,
    private val samplePreloadController: SamplePreloadController,
    initialSafety: NextPreloadSafetySnapshot,
) : VideoPreloadController {
    @Inject
    internal constructor(
        gateway: TelegramFileGateway,
        adaptivePolicy: AdaptivePreloadController,
        samplePreloadController: SamplePreloadController,
    ) : this(
        gateway = gateway,
        adaptivePolicy = adaptivePolicy,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        nowNanos = System::nanoTime,
        ownerPromotionEnabled = PRODUCTION_OWNER_PROMOTION_ENABLED,
        startupCandidate = StartupPreloadCandidate.fromBuildValue(
            BuildConfig.STARTUP_RANGE_CANDIDATE,
        ),
        dynamicNextPreloadEnabled = BuildConfig.DYNAMIC_NEXT_PRELOAD_ENABLED,
        samplePreloadController = samplePreloadController,
        initialSafety = NextPreloadSafetySnapshot(),
    )

    internal constructor(
        gateway: TelegramFileGateway,
        adaptivePolicy: AdaptivePreloadController,
        scope: CoroutineScope,
        nowNanos: () -> Long = System::nanoTime,
        ownerPromotionEnabled: Boolean = true,
        startupCandidate: StartupPreloadCandidate = StartupPreloadCandidate.BASELINE,
        dynamicNextPreloadEnabled: Boolean = false,
        samplePreloadController: SamplePreloadController = NoOpSamplePreloadController,
        initialSafety: NextPreloadSafetySnapshot = NextPreloadSafetySnapshot(),
        @Suppress("UNUSED_PARAMETER") testMarker: Unit = Unit,
    ) : this(
        gateway,
        adaptivePolicy,
        scope,
        nowNanos,
        ownerPromotionEnabled,
        startupCandidate,
        dynamicNextPreloadEnabled,
        samplePreloadController,
        initialSafety,
    )

    private val lock = Any()
    private var target: IndexedVideo? = null
    private var requestGeneration = 0L
    private var preloadJob: Job? = null
    private var activeLease: TelegramFileRangeLease? = null
    private var activeTailLease: TelegramFileRangeLease? = null
    private var activeRangeReady = false
    private val activeChunkLeases = mutableListOf<TelegramFileRangeLease>()
    private var activeChunkLength = 0L
    private var dynamicRequestedBytes = 0L
    private var dynamicCompletedBytes = 0L
    private var ledgerTarget: Pair<com.qixuan.channelvideoflow.model.video.VideoKey, Int>? = null
    private var dynamicCachedCoveredBytes = 0L
    private var dynamicCanceledBytes = 0L
    private var skippedNextWastedBytes = 0L
    private var currentSafety = initialSafety
    private var budgetDecision: NextPreloadBudgetDecision? = null
    private var dynamicHlsPlan: NextHlsPreloadPlan? = null
    private var dynamicPreloadFileId: Int? = null
    private var committedPromotion: CommittedPromotion? = null
    private var decision = adaptivePolicy.decision.value
    private var policyYieldPending = false
    private var handoffGeneration = 0L
    private val mutableOwnerHandoff = MutableStateFlow(PreloadOwnerHandoffSnapshot())

    override val ownerHandoff: StateFlow<PreloadOwnerHandoffSnapshot> =
        mutableOwnerHandoff.asStateFlow()

    override fun currentBudgetDecision(): NextPreloadBudgetDecision? = synchronized(lock) {
        budgetDecision?.copy(
            requestedUncachedBytes = dynamicRequestedBytes,
            cachedCoveredBytes = dynamicCachedCoveredBytes,
            canceledBytes = dynamicCanceledBytes,
            skippedNextWastedBytes = skippedNextWastedBytes,
        )
    }

    override fun requestedBytesFor(video: IndexedVideo): Long = synchronized(lock) {
        if (ledgerTarget != video.key to video.playbackFileId) return@synchronized 0L
        dynamicRequestedBytes
    }

    override fun updateCurrentPlaybackSafety(snapshot: NextPreloadSafetySnapshot) {
        if (!dynamicNextPreloadEnabled) return
        synchronized(lock) {
            val previous = target?.let(::calculateBudgetLocked)
            currentSafety = snapshot
            val next = target?.let(::calculateBudgetLocked)
            budgetDecision = next
            if (
                (next?.allowedBudgetTier == NextPreloadBudgetTier.BLOCKED &&
                    previous?.allowedBudgetTier != NextPreloadBudgetTier.BLOCKED) ||
                (preloadJob?.isActive != true && next != previous &&
                    (next?.remainingNewNetworkBudgetBytes ?: 0L) > 0L)
            ) {
                restartLocked()
            }
        }
    }

    init {
        scope.launch {
            adaptivePolicy.decision.collect { next ->
                synchronized(lock) {
                    if (decision == next) return@synchronized
                    val previous = decision
                    if (
                        activeLease != null &&
                        next.maxPreloadBytes < previous.maxPreloadBytes
                    ) {
                        policyYieldPending = true
                        trace(
                            "action=YIELD state=${next.state} reason=${next.reason} " +
                                "bytes=${next.maxPreloadBytes}",
                        )
                    }
                    decision = next
                    if (
                        committedPromotion != null &&
                        next.state == AdaptivePreloadState.OFF &&
                        next.reason == AdaptivePreloadReason.CURRENT_NOT_STABLE
                    ) {
                        trace(
                            "action=RETAIN phase=TARGET_COMMITTED " +
                                "reason=CURRENT_NOT_STABLE bytes=${previous.maxPreloadBytes}",
                        )
                        return@synchronized
                    }
                    if (
                        next.state == AdaptivePreloadState.OFF &&
                        next.reason.name in CLEAR_TARGET_REASONS
                    ) {
                        val cleared = target
                        target = null
                        cancelSpeculativeLocked()
                        committedPromotion = null
                        // Keep the published decision truthful after the target is dropped: the
                        // device diagnostics read this value, and a stale tier would report the
                        // stage as still admitted while nothing is being requested.
                        budgetDecision = cleared?.let(::calculateBudgetLocked)
                        publishLocked(PreloadOwnerHandoffPhase.CANCELLED)
                        return@synchronized
                    }
                    restartLocked()
                }
            }
        }
    }

    override fun setNextVideo(video: IndexedVideo?) {
        synchronized(lock) {
            val safeTarget = video?.takeIf(IndexedVideo::supportsStreaming)
            if (
                target?.key == safeTarget?.key &&
                target?.playbackFileId == safeTarget?.playbackFileId
            ) {
                return
            }
            if (dynamicNextPreloadEnabled && target != null && target?.key != safeTarget?.key) {
                skippedNextWastedBytes = skippedNextWastedBytes.saturatedAdd(dynamicRequestedBytes)
            }
            committedPromotion = null
            samplePreloadController.cancelUnless(safeTarget)
            target = safeTarget
            restartLocked()
        }
    }

    override fun beginTargetPromotion() {
        if (!ownerPromotionEnabled) {
            synchronized(lock) { target?.let(samplePreloadController::commitForPlayback) }
            return
        }
        synchronized(lock) {
            val video = target ?: return
            if (activeLease == null) return
            if (
                mutableOwnerHandoff.value.phase == PreloadOwnerHandoffPhase.TARGET_PENDING ||
                mutableOwnerHandoff.value.phase == PreloadOwnerHandoffPhase.TARGET_COMMITTED
            ) {
                return
            }
            publishLocked(PreloadOwnerHandoffPhase.TARGET_PENDING, video)
        }
    }

    override fun commitTargetPromotion(video: IndexedVideo) {
        if (!ownerPromotionEnabled) {
            samplePreloadController.commitForPlayback(video)
            return
        }
        synchronized(lock) {
            val existing = committedPromotion
            if (existing != null && existing.matches(video)) return
            val warmed = target
            val matched = activeLease != null &&
                warmed?.key == video.key &&
                warmed.playbackFileId == video.playbackFileId
            trace("promotionAttempt=true promotionMatched=$matched")
            if (!matched) {
                val cancelled = activeLease != null
                cancelSpeculativeLocked()
                target = null
                committedPromotion = null
                publishLocked(
                    phase = PreloadOwnerHandoffPhase.ABANDONED,
                    video = warmed ?: video,
                    promotionAttempt = true,
                    promotionMatched = false,
                    cancelledBeforeCurrentAcquire = cancelled,
                )
                trace(
                    "promotionTerminal=true promotionMatched=false " +
                        "reusedActiveRequest=false " +
                        "cancelledBeforeCurrentAcquire=$cancelled ownerHandoffMs=0",
                )
                return
            }
            val snapshot = publishLocked(
                phase = PreloadOwnerHandoffPhase.TARGET_COMMITTED,
                video = video,
                promotionAttempt = true,
                promotionMatched = true,
            )
            committedPromotion = CommittedPromotion(
                generation = snapshot.generation,
                video = video,
                startedAtNanos = nowNanos(),
                reusedActiveRequest = !activeRangeReady,
            )
        }
    }

    override fun abandonTargetPromotion() {
        if (!ownerPromotionEnabled) return
        synchronized(lock) {
            val video = target
            val committed = committedPromotion
            val cancelled = activeLease != null
            cancelSpeculativeLocked()
            target = null
            committedPromotion = null
            publishLocked(
                phase = PreloadOwnerHandoffPhase.ABANDONED,
                video = committed?.video ?: video,
                promotionAttempt = committed != null,
                promotionMatched = committed != null,
                reusedActiveRequest = committed?.reusedActiveRequest,
                cancelledBeforeCurrentAcquire = cancelled.takeIf { committed != null },
                handoffMillis = committed?.elapsedMillis(nowNanos()),
            )
            if (committed != null) {
                traceTerminal(
                    promotion = committed,
                    cancelledBeforeCurrentAcquire = cancelled,
                )
            }
        }
    }

    override fun onCurrentPlaybackStarting(video: IndexedVideo) {
        if (!ownerPromotionEnabled) {
            samplePreloadController.commitForPlayback(video)
            synchronized(lock) {
                val pending = target
                // A different item is still the one next target. The bounded byte prefix it holds
                // must survive the current item's Loading snapshots, which arrive repeatedly while
                // its first frame is pending; only the item that is itself becoming current has to
                // release its speculative owner, because there a CURRENT_PLAYBACK owner takes over
                // the same file and drives it (Stage 28 single-dominant window).
                if (pending != null && pending.key != video.key) return
                if (pending == null && activeLease == null) {
                    budgetDecision = null
                    return
                }
                cancelSpeculativeLocked()
                target = null
                budgetDecision = null
            }
            return
        }
        synchronized(lock) {
            val promotion = committedPromotion
            if (promotion != null && promotion.matches(video)) return
            if (activeLease == null && target == null) return
            val cancelled = activeLease != null
            cancelSpeculativeLocked()
            target = null
            committedPromotion = null
            publishLocked(
                phase = PreloadOwnerHandoffPhase.CANCELLED,
                video = promotion?.video ?: video,
                promotionAttempt = promotion != null,
                promotionMatched = promotion != null,
                reusedActiveRequest = promotion?.reusedActiveRequest,
                cancelledBeforeCurrentAcquire = cancelled.takeIf { promotion != null },
                handoffMillis = promotion?.elapsedMillis(nowNanos()),
            )
            if (promotion != null) traceTerminal(promotion, cancelled)
        }
    }

    override fun onCurrentPlaybackRangeAcquired(video: IndexedVideo) {
        if (!ownerPromotionEnabled) return
        synchronized(lock) {
            val promotion = committedPromotion ?: return
            if (!promotion.matches(video)) return
            if (
                mutableOwnerHandoff.value.phase != PreloadOwnerHandoffPhase.TARGET_COMMITTED ||
                mutableOwnerHandoff.value.generation != promotion.generation
            ) {
                return
            }
            val elapsed = promotion.elapsedMillis(nowNanos())
            cancelSpeculativeLocked()
            target = null
            committedPromotion = null
            publishLocked(
                phase = PreloadOwnerHandoffPhase.SHARED_WITH_CURRENT,
                video = video,
                promotionAttempt = true,
                promotionMatched = true,
                reusedActiveRequest = promotion.reusedActiveRequest,
                cancelledBeforeCurrentAcquire = false,
                handoffMillis = elapsed,
            )
            traceTerminal(promotion, cancelledBeforeCurrentAcquire = false, elapsedMillis = elapsed)
        }
    }

    override fun onCurrentPlaybackRangeAcquireFailed(video: IndexedVideo) {
        if (!ownerPromotionEnabled) return
        synchronized(lock) {
            val promotion = committedPromotion ?: return
            if (!promotion.matches(video)) return
            val cancelled = activeLease != null
            val elapsed = promotion.elapsedMillis(nowNanos())
            cancelSpeculativeLocked()
            target = null
            committedPromotion = null
            publishLocked(
                phase = PreloadOwnerHandoffPhase.CANCELLED,
                video = video,
                promotionAttempt = true,
                promotionMatched = true,
                reusedActiveRequest = promotion.reusedActiveRequest,
                cancelledBeforeCurrentAcquire = cancelled,
                handoffMillis = elapsed,
            )
            traceTerminal(promotion, cancelled, elapsed)
        }
    }

    override fun stop() {
        samplePreloadController.cancelUnless(null)
        synchronized(lock) {
            val promotion = committedPromotion
            val cancelled = activeLease != null
            if (target != null && activeLease != null) {
                policyYieldPending = true
                trace(
                    "action=YIELD state=OFF reason=CURRENT_NOT_STABLE bytes=0",
                )
            }
            cancelSpeculativeLocked()
            target = null
            committedPromotion = null
            publishLocked(
                phase = PreloadOwnerHandoffPhase.RELEASED,
                video = promotion?.video,
                promotionAttempt = promotion != null,
                promotionMatched = promotion != null,
                reusedActiveRequest = promotion?.reusedActiveRequest,
                cancelledBeforeCurrentAcquire = cancelled.takeIf { promotion != null },
                handoffMillis = promotion?.elapsedMillis(nowNanos()),
            )
            if (promotion != null) traceTerminal(promotion, cancelled)
        }
    }

    private fun restartLocked() {
        if (dynamicNextPreloadEnabled) {
            restartDynamicLocked()
            return
        }
        val hadSpeculativeOwner = activeLease != null
        cancelSpeculativeLocked()
        val video = target
        if (video == null) {
            if (hadSpeculativeOwner) publishLocked(PreloadOwnerHandoffPhase.RELEASED)
            return
        }
        if (decision.state == AdaptivePreloadState.OFF || decision.maxPreloadBytes <= 0L) {
            publishLocked(PreloadOwnerHandoffPhase.CANCELLED, video)
            return
        }
        val plan = StartupPreloadPlanner.plan(
            candidate = startupCandidate,
            decision = decision,
            fileSize = video.playbackFileSize,
        )
        val head = plan.head ?: run {
            publishLocked(PreloadOwnerHandoffPhase.CANCELLED, video)
            return
        }
        val nextRequestGeneration = ++requestGeneration
        val lease = runCatching {
            gateway.acquireRange(
                fileId = video.playbackFileId,
                offset = head.offset,
                length = head.length,
                priority = TelegramFileRequestPriority.NEXT_PRELOAD,
                ownerToken = "next-preload-${TOKEN_COUNTER.incrementAndGet()}",
                ownerKind = TelegramFileOwnerKind.NEXT_PRELOAD,
            )
        }.getOrElse {
            target = null
            publishLocked(PreloadOwnerHandoffPhase.CANCELLED, video)
            return
        }
        activeLease = lease
        activeRangeReady = false
        publishLocked(PreloadOwnerHandoffPhase.NEXT_WARMING, video)
        trace(
            "action=${if (policyYieldPending) "RESUME" else "START"} " +
                "state=${decision.state} reason=${decision.reason} " +
                "candidate=${startupCandidate.name} bytes=${head.length} " +
                "requestedExtraBytes=${(head.length - StartupPreloadPlanner.BASELINE_HEAD_BYTES).coerceAtLeast(0L)}",
        )
        policyYieldPending = false
        preloadJob = scope.launch {
            val accepted = synchronized(lock) {
                if (
                    requestGeneration == nextRequestGeneration &&
                    target?.key == video.key &&
                    target?.playbackFileId == video.playbackFileId
                ) {
                    activeLease = lease
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                lease.close()
                return@launch
            }
            try {
                lease.awaitAvailable(PRELOAD_TIMEOUT_MILLIS)
                val headStillCurrent = synchronized(lock) {
                    if (
                        requestGeneration == nextRequestGeneration &&
                        activeLease === lease
                    ) {
                        activeRangeReady = true
                        true
                    } else {
                        false
                    }
                }
                if (!headStillCurrent) return@launch
                preloadTailIfNeeded(
                    generation = nextRequestGeneration,
                    video = video,
                    plan = plan,
                    headLease = lease,
                )
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (activeLease === lease) {
                        activeLease = null
                        activeRangeReady = false
                        target = null
                        committedPromotion = null
                        publishLocked(PreloadOwnerHandoffPhase.CANCELLED, video)
                    }
                }
                lease.close()
            } finally {
                val tailToClose = synchronized(lock) {
                    val tail = activeTailLease.takeIf { activeLease === lease }
                    if (tail != null) activeTailLease = null
                    if (activeLease === lease) {
                        activeLease = null
                        activeRangeReady = false
                    }
                    tail
                }
                tailToClose?.close()
                lease.close()
            }
        }
    }

    private fun cancelSpeculativeLocked() {
        requestGeneration += 1L
        preloadJob?.cancel()
        preloadJob = null
        if (activeChunkLength > 0L) {
            dynamicCanceledBytes = dynamicCanceledBytes.saturatedAdd(activeChunkLength)
            activeChunkLength = 0L
        }
        activeChunkLeases.forEach { lease -> lease.close() }
        activeChunkLeases.clear()
        dynamicHlsPlan?.manifestLease?.close()
        dynamicHlsPlan?.session?.close()
        dynamicHlsPlan = null
        dynamicPreloadFileId = null
        activeTailLease?.close()
        activeTailLease = null
        activeLease?.close()
        activeLease = null
        activeRangeReady = false
    }

    private fun restartDynamicLocked() {
        val hadSpeculativeOwner = activeLease != null || activeChunkLeases.isNotEmpty()
        cancelSpeculativeLocked()
        val video = target
        if (video == null) {
            budgetDecision = null
            dynamicRequestedBytes = 0L
            dynamicCachedCoveredBytes = 0L
            dynamicCompletedBytes = 0L
            ledgerTarget = null
            if (hadSpeculativeOwner) publishLocked(PreloadOwnerHandoffPhase.RELEASED)
            return
        }
        val identity = video.key to video.playbackFileId
        val newTarget = ledgerTarget != identity
        if (newTarget) {
            ledgerTarget = identity
            dynamicRequestedBytes = 0L
            dynamicCompletedBytes = 0L
            dynamicCanceledBytes = 0L
        }
        val snapshot = gateway.currentSnapshot(video.playbackFileId)
        if (newTarget) dynamicCachedCoveredBytes = snapshot
            ?.takeIf { it.downloadOffset == 0L }
            ?.downloadedPrefixSize
            ?.coerceAtLeast(0L)
            ?: 0L
        val initialBudget = calculateBudgetLocked(video)
        budgetDecision = initialBudget
        val permitsHlsMetadata = initialBudget.allowedBudgetTier == NextPreloadBudgetTier.METADATA_ONLY &&
            video.hlsCapableVariants.isNotEmpty()
        if (initialBudget.remainingNewNetworkBudgetBytes <= 0L && !permitsHlsMetadata) {
            publishLocked(PreloadOwnerHandoffPhase.CANCELLED, video)
            traceDynamicDecision(initialBudget)
            return
        }
        val generation = ++requestGeneration
        publishLocked(PreloadOwnerHandoffPhase.NEXT_WARMING, video)
        // The lightweight startup stage is one bounded progressive byte prefix and nothing else.
        // An HLS target needs a manifest plus segment ranges; requesting the manifest here would
        // stack a second, uncharged request on top of the flat ceiling, and its failure path would
        // add a further prefix on top of that. Both belong to the heavy preparation, which has its
        // own duration-based budget. The prefix still helps an HLS promotion because Telegram's
        // segments are byte ranges of the same media file.
        val conservativeStartup = initialBudget.allowedBudgetTier == NextPreloadBudgetTier.CONSERVATIVE_STARTUP
        preloadJob = scope.launch {
            try {
                val hlsPlan = if (!conservativeStartup && video.hlsCapableVariants.isNotEmpty()) {
                    NextHlsPreloadManifestLoader.load(
                        video = video,
                        gateway = gateway,
                        ownerToken = "next-hls-${TOKEN_COUNTER.incrementAndGet()}",
                        timeoutMillis = PRELOAD_TIMEOUT_MILLIS,
                    )
                } else {
                    null
                }
                val acceptedHlsPlan = synchronized(lock) {
                    if (requestGeneration != generation || target?.key != video.key) {
                        false
                    } else {
                        if (hlsPlan != null) {
                            dynamicHlsPlan = hlsPlan
                            dynamicPreloadFileId = hlsPlan.mediaFileId
                            if (newTarget) dynamicCachedCoveredBytes = gateway.currentSnapshot(hlsPlan.mediaFileId)
                                ?.takeIf { it.downloadOffset == 0L }
                                ?.downloadedPrefixSize
                                ?.coerceAtLeast(0L)
                                ?: 0L
                            budgetDecision = calculateBudgetLocked(video)
                            trace(
                                "hlsManifestBytes=${hlsPlan.manifestBytes} " +
                                    "hlsManifestCached=${hlsPlan.manifestWasCached} " +
                                    "hlsBoundaryCount=${hlsPlan.boundaries.size}",
                            )
                        } else {
                            dynamicPreloadFileId = video.playbackFileId
                        }
                        true
                    }
                }
                if (!acceptedHlsPlan) {
                    hlsPlan?.manifestLease?.close()
                    hlsPlan?.session?.close()
                    return@launch
                }
                val postManifestBudget = synchronized(lock) {
                    calculateBudgetLocked(video).also { budgetDecision = it }
                }
                if (postManifestBudget.allowedBudgetTier == NextPreloadBudgetTier.METADATA_ONLY) {
                    samplePreloadController.preload(video, postManifestBudget)
                    awaitCancellation()
                }
                while (true) {
                    val request = synchronized(lock) {
                        if (
                            requestGeneration != generation || target?.key != video.key ||
                            target?.playbackFileId != video.playbackFileId
                        ) return@launch
                        val current = calculateBudgetLocked(video)
                        budgetDecision = current
                        traceDynamicDecision(current)
                        if (current.remainingNewNetworkBudgetBytes <= 0L) {
                            samplePreloadController.preload(video, current)
                            return@launch
                        }
                        val preloadFileId = dynamicPreloadFileId ?: video.playbackFileId
                        val offset = dynamicCachedCoveredBytes.saturatedAdd(dynamicCompletedBytes)
                        val preloadFileSize = video.alternativeVariants
                            .firstOrNull { it.fileId == preloadFileId }
                            ?.fileSize
                            ?: video.playbackFileSize
                        val fileRemaining = preloadFileSize
                            ?.let { (it - offset).coerceAtLeast(0L) }
                            ?: Long.MAX_VALUE
                        val length = minOf(
                            current.remainingNewNetworkBudgetBytes,
                            NextPreloadBudgetController.RANGE_CHUNK_BYTES,
                            fileRemaining,
                        )
                        if (length <= 0L) return@launch
                        val lease = gateway.acquireRange(
                            fileId = preloadFileId,
                            offset = offset,
                            length = length,
                            priority = TelegramFileRequestPriority.NEXT_PRELOAD,
                            ownerToken = "next-dynamic-${TOKEN_COUNTER.incrementAndGet()}",
                            ownerKind = TelegramFileOwnerKind.NEXT_PRELOAD,
                            readAheadBytes = length,
                        )
                        // Charge requested, uncached payload before waiting. Cancel/retry does
                        // not erase the target's ledger and cannot reset its hard ceiling.
                        dynamicRequestedBytes = dynamicRequestedBytes.saturatedAdd(length)
                        activeChunkLength = length
                        activeChunkLeases += lease
                        if (activeLease == null) activeLease = lease
                        DynamicChunk(lease, length)
                    }
                    request.lease.awaitAvailable(PRELOAD_TIMEOUT_MILLIS)
                    synchronized(lock) {
                        if (requestGeneration != generation) return@synchronized
                        activeChunkLength = 0L
                        dynamicCompletedBytes = dynamicCompletedBytes
                            .saturatedAdd(request.length)
                            .coerceAtMost(NextPreloadBudgetController.ABSOLUTE_MAX_BYTES)
                        activeRangeReady = true
                        budgetDecision = calculateBudgetLocked(video)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (requestGeneration == generation) {
                        budgetDecision = calculateBudgetLocked(video).copy(
                            preloadStopReason = NextPreloadStopReason.TARGET_CHANGED,
                        )
                        publishLocked(PreloadOwnerHandoffPhase.CANCELLED, video)
                    }
                }
            }
        }
    }

    private fun calculateBudgetLocked(video: IndexedVideo): NextPreloadBudgetDecision {
        val averageBitrate = video.playbackFileSize
            ?.takeIf { it > 0L && video.durationSeconds > 0 }
            ?.let { size -> size.saturatedMultiply(8L) / video.durationSeconds }
        val conservativePeak = averageBitrate
            ?.let { average -> (average.toDouble() * PROGRESSIVE_PEAK_FACTOR).toLong() }
        return NextPreloadBudgetController.evaluate(
            NextPreloadBudgetInput(
                safety = currentSafety,
                peakBitrateBitsPerSecond = conservativePeak,
                cachedCoveredBytes = dynamicCachedCoveredBytes,
                requestedUncachedBytes = dynamicRequestedBytes,
                hlsBoundaries = dynamicHlsPlan?.boundaries.orEmpty(),
                // This owner never binds a decoder, so while the current item has not rendered its
                // first frame it degrades to the flat 256 KiB byte ceiling instead of a hard block.
                // The pool standby builds its own input and keeps the strict admission.
                lightweightStartupPrefetch = true,
                // The dynamic path bypasses restartLocked's OFF check, and the safety snapshot
                // cannot express an offline link or a network change. The policy decision is the
                // only place that knows, so its hard block is reported explicitly and outranks
                // every allowance below.
                hasAdaptiveHardBlock = decision.state == AdaptivePreloadState.OFF,
            ),
        )
    }

    private fun traceDynamicDecision(current: NextPreloadBudgetDecision) {
        trace(
            "calculatedTargetSeconds=${current.calculatedTargetSeconds} " +
                "calculatedTargetBytes=${current.calculatedTargetBytes} " +
                "allowedBudgetTier=${current.allowedBudgetTier} " +
                "requestedUncachedBytes=$dynamicRequestedBytes " +
                "cachedCoveredBytes=$dynamicCachedCoveredBytes canceledBytes=$dynamicCanceledBytes " +
                "skippedNextWastedBytes=$skippedNextWastedBytes " +
                "currentBufferedSeconds=${current.currentBufferedSeconds} " +
                "bufferSlope=${current.bufferSlopeSecondsPerSecond} " +
                "predictedCompletionMillis=${current.predictedCompletionMillis} " +
                "starvationDeadlineMillis=${current.starvationDeadlineMillis} " +
                "preloadStopReason=${current.preloadStopReason}",
        )
    }

    private data class DynamicChunk(
        val lease: TelegramFileRangeLease,
        val length: Long,
    )

    private suspend fun preloadTailIfNeeded(
        generation: Long,
        video: IndexedVideo,
        plan: StartupPreloadPlan,
        headLease: TelegramFileRangeLease,
    ) {
        val tail = plan.tail
        if (tail == null) {
            traceCandidateReady(plan, requestedTailBytes = 0L)
            return
        }
        if (gateway.currentSnapshot(video.playbackFileId)?.covers(tail.offset, tail.length) == true) {
            traceCandidateReady(plan, requestedTailBytes = 0L, cachedTailBytes = tail.length)
            return
        }

        var acquireFailed = false
        val tailLease = synchronized(lock) {
            if (
                requestGeneration == generation &&
                activeLease === headLease &&
                target?.key == video.key &&
                target?.playbackFileId == video.playbackFileId
            ) {
                runCatching {
                    gateway.acquireRange(
                        fileId = video.playbackFileId,
                        offset = tail.offset,
                        length = tail.length,
                        priority = TelegramFileRequestPriority.NEXT_PRELOAD,
                        ownerToken = "next-tail-preload-${TOKEN_COUNTER.incrementAndGet()}",
                        ownerKind = TelegramFileOwnerKind.NEXT_PRELOAD,
                    )
                }.onFailure {
                    acquireFailed = true
                }.getOrNull()?.also {
                    activeTailLease = it
                    trace(
                        "action=TAIL_START candidate=${plan.candidate.name} " +
                            "bytes=${tail.length} requestedExtraBytes=${tail.length}",
                    )
                }
            } else {
                null
            }
        }
        if (tailLease == null) {
            if (acquireFailed) traceTailFailure(plan, "ACQUIRE_FAILED")
            return
        }
        try {
            tailLease.awaitAvailable(PRELOAD_TIMEOUT_MILLIS)
            val stillCurrent = synchronized(lock) {
                requestGeneration == generation &&
                    activeLease === headLease &&
                    activeTailLease === tailLease
            }
            if (stillCurrent) traceCandidateReady(plan, requestedTailBytes = tail.length)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) {
                if (activeTailLease === tailLease) activeTailLease = null
            }
            tailLease.close()
            traceTailFailure(plan, "AWAIT_FAILED")
        }
    }

    private fun traceCandidateReady(
        plan: StartupPreloadPlan,
        requestedTailBytes: Long,
        cachedTailBytes: Long = 0L,
    ) {
        val requestedTotal = (plan.head?.length ?: 0L) + requestedTailBytes
        val baselineBytes = (plan.head?.length ?: 0L)
            .coerceAtMost(StartupPreloadPlanner.BASELINE_HEAD_BYTES)
        trace(
            "action=CANDIDATE_READY candidate=${plan.candidate.name} " +
                "headBytes=${plan.head?.length ?: 0L} tailBytes=$requestedTailBytes " +
                "cachedTailBytes=$cachedTailBytes totalBytes=$requestedTotal " +
                "extraBytes=${(requestedTotal - baselineBytes).coerceAtLeast(0L)}",
        )
    }

    private fun traceTailFailure(plan: StartupPreloadPlan, reason: String) {
        trace(
            "action=CANDIDATE_TAIL_FAILED candidate=${plan.candidate.name} reason=$reason " +
                "headBytes=${plan.head?.length ?: 0L} tailBytes=0 " +
                "totalBytes=${plan.head?.length ?: 0L} extraBytes=0",
        )
    }

    private fun publishLocked(
        phase: PreloadOwnerHandoffPhase,
        video: IndexedVideo? = target,
        promotionAttempt: Boolean = false,
        promotionMatched: Boolean = false,
        reusedActiveRequest: Boolean? = null,
        cancelledBeforeCurrentAcquire: Boolean? = null,
        handoffMillis: Long? = null,
    ): PreloadOwnerHandoffSnapshot {
        val snapshot = PreloadOwnerHandoffSnapshot(
            phase = phase,
            generation = ++handoffGeneration,
            key = video?.key,
            fileId = video?.playbackFileId,
            hasSpeculativeOwner = activeLease != null,
            promotionAttempt = promotionAttempt,
            promotionMatched = promotionMatched,
            reusedActiveRequest = reusedActiveRequest,
            cancelledBeforeCurrentAcquire = cancelledBeforeCurrentAcquire,
            handoffMillis = handoffMillis,
        )
        mutableOwnerHandoff.value = snapshot
        return snapshot
    }

    private fun traceTerminal(
        promotion: CommittedPromotion,
        cancelledBeforeCurrentAcquire: Boolean,
        elapsedMillis: Long = promotion.elapsedMillis(nowNanos()),
    ) {
        trace(
            "promotionTerminal=true promotionMatched=true " +
                "reusedActiveRequest=${promotion.reusedActiveRequest} " +
                "cancelledBeforeCurrentAcquire=$cancelledBeforeCurrentAcquire " +
                "ownerHandoffMs=$elapsedMillis",
        )
    }

    private data class CommittedPromotion(
        val generation: Long,
        val video: IndexedVideo,
        val startedAtNanos: Long,
        val reusedActiveRequest: Boolean,
    ) {
        fun matches(candidate: IndexedVideo): Boolean =
            video.key == candidate.key && video.playbackFileId == candidate.playbackFileId

        fun elapsedMillis(nowNanos: Long): Long =
            ((nowNanos - startedAtNanos).coerceAtLeast(0L) / 1_000_000L)
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) runCatching { Log.i(LOG_TAG, message) }
    }

    internal companion object {
        const val PRELOAD_BYTES = AdaptivePreloadPolicyStateMachine.NORMAL_PRELOAD_BYTES
        val PRELOAD_PRIORITY = TelegramFileRequestPriority.NEXT_PRELOAD
        const val PRELOAD_TIMEOUT_MILLIS = 15_000L
        const val LOG_TAG = "CVF-Preload"
        const val PRODUCTION_OWNER_PROMOTION_ENABLED = false
        const val PROGRESSIVE_PEAK_FACTOR = 1.50
        val CLEAR_TARGET_REASONS = setOf(
            "CURRENT_NOT_STABLE",
            "NETWORK_CHANGED",
            "CONSECUTIVE_FAILURES",
        )
        private val TOKEN_COUNTER = AtomicLong()
    }

    private fun Long.saturatedAdd(value: Long): Long =
        if (this > Long.MAX_VALUE - value) Long.MAX_VALUE else this + value

    private fun Long.saturatedMultiply(value: Long): Long =
        if (this > Long.MAX_VALUE / value) Long.MAX_VALUE else this * value
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class VideoPreloadModule {
    @Binds
    @Singleton
    abstract fun bindSamplePreloadController(
        implementation: Media3SamplePreloadController,
    ): SamplePreloadController

    @Binds
    @Singleton
    abstract fun bindAdaptivePreloadController(
        implementation: AdaptivePreloadPolicyManager,
    ): AdaptivePreloadController

    @Binds
    @Singleton
    abstract fun bindVideoPreloadController(
        implementation: VideoPreloadManager,
    ): VideoPreloadController
}
