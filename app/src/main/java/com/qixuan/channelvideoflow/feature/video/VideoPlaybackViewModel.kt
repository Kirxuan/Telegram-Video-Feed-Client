package com.qixuan.channelvideoflow.feature.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import com.qixuan.channelvideoflow.domain.channel.TelegramChatRepository
import com.qixuan.channelvideoflow.domain.cache.MediaCacheController
import com.qixuan.channelvideoflow.domain.message.TelegramMessageRepository
import com.qixuan.channelvideoflow.domain.message.RepositoryObservationFailure
import com.qixuan.channelvideoflow.domain.message.VideoFeedKeySnapshot
import com.qixuan.channelvideoflow.domain.message.VideoReferenceFailure
import com.qixuan.channelvideoflow.domain.message.VideoReferenceResolution
import com.qixuan.channelvideoflow.domain.media.DevicePreloadPolicySource
import com.qixuan.channelvideoflow.domain.media.NetworkTransport
import com.qixuan.channelvideoflow.domain.media.NoOpStreamingNetworkMetricsRepository
import com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase
import com.qixuan.channelvideoflow.domain.media.StreamingNetworkMetricsRepository
import com.qixuan.channelvideoflow.domain.media.OriginalPlaybackAdmission
import com.qixuan.channelvideoflow.domain.media.VideoQualitySelector
import com.qixuan.channelvideoflow.domain.media.VideoPreloadController
import com.qixuan.channelvideoflow.domain.video.PlaybackFeedRoundEntry
import com.qixuan.channelvideoflow.domain.video.PlaybackFeedSession
import com.qixuan.channelvideoflow.domain.video.PlaybackFeedSnapshot
import com.qixuan.channelvideoflow.domain.video.VideoFeedOnboardingPreferences
import com.qixuan.channelvideoflow.model.video.DEFAULT_VIDEO_FEED_ORDER
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.OriginalMessageLinkResult
import com.qixuan.channelvideoflow.model.video.TagFilterMode
import com.qixuan.channelvideoflow.model.video.VideoFeedOrder
import com.qixuan.channelvideoflow.model.video.VideoFilter
import com.qixuan.channelvideoflow.model.video.VideoKey
import com.qixuan.channelvideoflow.model.video.VideoPlaybackVariant
import com.qixuan.channelvideoflow.model.video.VideoQualityPreference
import com.qixuan.channelvideoflow.player.PlaybackPlanRefreshOutcome
import com.qixuan.channelvideoflow.player.PlaybackTransitionDirection
import com.qixuan.channelvideoflow.player.PlaybackTransitionEvent
import com.qixuan.channelvideoflow.player.TransparentRecoveryOutcome
import com.qixuan.channelvideoflow.player.PlaybackPreparationContext
import com.qixuan.channelvideoflow.player.VideoPlaybackController
import com.qixuan.channelvideoflow.player.VideoPlaybackFailure
import com.qixuan.channelvideoflow.player.VideoPlaybackState
import com.qixuan.channelvideoflow.player.VideoPlayerSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

@HiltViewModel
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlaybackViewModel private constructor(
    private val chatRepository: TelegramChatRepository,
    private val messageRepository: TelegramMessageRepository,
    private val playerController: VideoPlaybackController,
    private val preloadController: VideoPreloadController,
    private val cacheController: MediaCacheController,
    private val devicePolicySource: DevicePreloadPolicySource,
    private val onboardingPreferences: VideoFeedOnboardingPreferences,
    private val feedSession: PlaybackFeedSession,
    private val networkMetrics: StreamingNetworkMetricsRepository,
) : ViewModel() {
    @Inject
    constructor(
        chatRepository: TelegramChatRepository,
        messageRepository: TelegramMessageRepository,
        playerController: VideoPlaybackController,
        preloadController: VideoPreloadController,
        cacheController: MediaCacheController,
        devicePolicySource: DevicePreloadPolicySource,
        onboardingPreferences: VideoFeedOnboardingPreferences,
        networkMetrics: StreamingNetworkMetricsRepository,
    ) : this(
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        playerController = playerController,
        preloadController = preloadController,
        cacheController = cacheController,
        devicePolicySource = devicePolicySource,
        onboardingPreferences = onboardingPreferences,
        feedSession = PlaybackFeedSession(),
        networkMetrics = networkMetrics,
    )

    internal constructor(
        chatRepository: TelegramChatRepository,
        messageRepository: TelegramMessageRepository,
        playerController: VideoPlaybackController,
        preloadController: VideoPreloadController,
        cacheController: MediaCacheController,
        devicePolicySource: DevicePreloadPolicySource,
        feedSession: PlaybackFeedSession,
        onboardingPreferences: VideoFeedOnboardingPreferences,
        networkMetrics: StreamingNetworkMetricsRepository =
            NoOpStreamingNetworkMetricsRepository,
        @Suppress("UNUSED_PARAMETER") testMarker: Unit,
    ) : this(
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        playerController = playerController,
        preloadController = preloadController,
        cacheController = cacheController,
        devicePolicySource = devicePolicySource,
        onboardingPreferences = onboardingPreferences,
        feedSession = feedSession,
        networkMetrics = networkMetrics,
    )

    private val mutableUiState = MutableStateFlow(VideoPlaybackUiState())
    val uiState: StateFlow<VideoPlaybackUiState> = mutableUiState.asStateFlow()
    private val mutablePlaybackProgress = MutableStateFlow(VideoPlaybackProgressUiState())
    val playbackProgress: StateFlow<VideoPlaybackProgressUiState> =
        mutablePlaybackProgress.asStateFlow()

    private val criteria = MutableStateFlow(FeedCriteria())
    private val feedObservationRetry = MutableStateFlow(0L)
    private val mutableOpenOriginalMessageLinks = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openOriginalMessageLinks: SharedFlow<String> = mutableOpenOriginalMessageLinks.asSharedFlow()

    private var items = emptyList<FeedVideoItem>()
    private val hydratedVideos = linkedMapOf<VideoKey, com.qixuan.channelvideoflow.model.video.IndexedVideo>()
    private var feedSnapshot = feedSession.current()
    private var currentFeedKeySet = emptySet<VideoKey>()
    private var upcomingFeedKeySet = emptySet<VideoKey>()
    private var hydrationJob: Job? = null
    private var hydrationRequestGeneration = 0L
    private var uiHydrationGeneration = 0L
    private var playerSnapshot = VideoPlayerSnapshot()
    private var currentPage = 0
    private var sourceLoaded = false
    private var keyObservationFailure: RepositoryObservationFailure? = null
    private var feedObservationFailure: RepositoryObservationFailure? = null
    private var lastAppliedSource: FeedSource? = null
    private var latestChannelTitles = emptyMap<Long, String>()
    private var randomRoundStartPagerPage: Int? = null
    private var queueGeneration = 0L
    private var playbackQueueGeneration = 0L
    private var accountGeneration = 0L
    private var qualitySelectionGeneration = 0L
    private var qualitySelection = devicePolicySource.signals.value.let { signals ->
        networkMetrics.resetNetworkContext(signals.network, signals.networkGeneration)
        val preference = cacheController.state.value.videoQualityPreference
        QualitySelection(
            preference = preference,
            network = signals.network,
            networkGeneration = signals.networkGeneration,
        )
    }
    private val playbackPlans = AtomicReference(PlaybackPlanSlots())
    private val targetCoordinator = PlaybackTargetCoordinator()
    private var stablePageGeneration = 0L
    private var planPreparationGeneration = 0L
    private var stablePageJob: Job? = null
    private var planPreparation: PlanPreparation? = null
    private var linkJob: Job? = null
    private var retryJob: Job? = null
    private var retryGeneration = 0L
    private var transparentRecoveryJob: Job? = null
    private var transparentRecoveryAttempt: TransparentRecoveryAttempt? = null
    private val activeReferenceResolutionCounts = mutableMapOf<VideoKey, Int>()
    private var swipeHintPreferenceLoaded = false
    private var swipeHintSeen = false
    private var swipeHintVisible = false
    private var swipeHintUserInteracted = false
    private var swipeHintHandledThisSession = false
    private var swipeHintMarkStarted = false
    private var swipeHintTimeoutJob: Job? = null
    private var pendingOriginalPlayback: Pair<IndexedVideo, PlaybackPlanToken>? = null
    private var approvedOriginalPlayback: Pair<VideoKey, Int>? = null
    private var isForeground = true

    init {
        viewModelScope.launch {
            onboardingPreferences.hasSeenSwipeHint.collect { seen ->
                swipeHintPreferenceLoaded = true
                swipeHintSeen = seen
                if (seen) swipeHintHandledThisSession = true
                if (seen && swipeHintVisible) {
                    swipeHintTimeoutJob?.cancel()
                    swipeHintTimeoutJob = null
                    swipeHintVisible = false
                    rebuildUiState()
                } else if (!seen) {
                    maybeShowSwipeHint()
                }
            }
        }
        viewModelScope.launch {
            combine(chatRepository.channels, criteria, feedObservationRetry) { channels, selection, retryToken ->
                val channelIds = selection.channelIds ?: channels
                    .asSequence()
                    .filter { channel -> channel.isSelected }
                    .map { channel -> channel.chatId }
                    .toSet()
                FeedSource(
                    filter = VideoFilter(
                        channelIds = channelIds,
                        normalizedTags = selection.normalizedTags,
                        tagMode = selection.tagMode,
                    ),
                    order = selection.order,
                    channelTitles = channels.associate { channel -> channel.chatId to channel.title },
                    retryToken = retryToken,
                )
            }
                .distinctUntilChanged()
                .flatMapLatest { source ->
                    messageRepository.observeVideoKeyObservation(source.filter).map { observation ->
                        FeedSourceResult(source, observation.value, observation.failure)
                    }
                }
                .collect { result -> reconcileFeedKeys(result) }
        }
        viewModelScope.launch {
            playerController.snapshot.collect { snapshot ->
                if (interceptFileUnavailableForRecovery(snapshot)) return@collect
                val previous = playerSnapshot
                playerSnapshot = snapshot
                mutablePlaybackProgress.value = snapshot.toProgressUiState()
                maybeShowSwipeHint()
                if (!snapshot.hasRenderedFirstFrame) {
                    val loading = snapshot.playbackState as? VideoPlaybackState.Loading
                    if (loading != null) {
                        preloadController.onCurrentPlaybackStarting(loading.video)
                    } else {
                        preloadController.stop()
                    }
                } else if (
                    !previous.hasRenderedFirstFrame ||
                    previous.playbackState.videoKeyOrNull() !=
                    snapshot.playbackState.videoKeyOrNull()
                ) {
                    transparentRecoveryAttempt = null
                    transparentRecoveryJob = null
                    prepareNextVideoPlan()
                }
                if (previous.toPresentationSnapshot() != snapshot.toPresentationSnapshot()) {
                    rebuildUiState()
                }
            }
        }
        viewModelScope.launch {
            combine(
                cacheController.state.map { state -> state.videoQualityPreference },
                devicePolicySource.signals,
            ) { preference, signals ->
                networkMetrics.resetNetworkContext(signals.network, signals.networkGeneration)
                QualitySelection(
                    preference = preference,
                    network = signals.network,
                    networkGeneration = signals.networkGeneration,
                )
            }
                .distinctUntilChanged()
                .collect { selection ->
                    if (selection == qualitySelection) return@collect
                    qualitySelection = selection
                    qualitySelectionGeneration += 1L
                    cancelTransparentRecovery(clearAttempt = true)
                    invalidatePlaybackPlans()
                    val bound = when (val state = playerSnapshot.playbackState) {
                        is VideoPlaybackState.Loading -> state.video
                        is VideoPlaybackState.Ready -> state.video
                        else -> null
                    }
                    if (bound != null && needsOriginalConfirmation(bound)) admitOriginalPlayback(bound)
                    if (
                        isForeground &&
                        playerSnapshot.hasRenderedFirstFrame &&
                        !targetCoordinator.state.isUnstable
                    ) {
                        prepareNextVideoPlan()
                    }
                }
        }
        viewModelScope.launch {
            networkMetrics.estimate.map { it?.availableBitsPerSecond }
                .distinctUntilChanged()
                .collect { bandwidth ->
                    if (bandwidth == null || qualitySelection.preference != VideoQualityPreference.AUTO) {
                        return@collect
                    }
                    // A throughput sample is not a new playback identity. Keep in-flight
                    // preparation and prepared bytes unless the selected representation changes.
                    val next = nextTarget() ?: return@collect
                    val plan = playbackPlans.get().next ?: return@collect
                    if (!plan.matches(next.video.key, currentPlanToken(next.randomEntry?.roundGeneration))) {
                        return@collect
                    }
                    val selected = selectStandbyVideo(
                        plan.toVideo(next.video).copy(alternativeVariants = plan.availableVariants),
                        qualitySelection,
                    )
                    if (selected.playbackFileId == plan.playbackFileId &&
                        selected.selectedAlternative == plan.selectedAlternative
                    ) return@collect
                    // Once a gesture has committed to a prepared target, let its exact plan
                    // finish. Future plans still sample the latest throughput below.
                    if (!isForeground || targetCoordinator.state.isUnstable) return@collect
                    qualitySelectionGeneration += 1L
                    invalidatePlaybackPlans()
                    if (playerSnapshot.hasRenderedFirstFrame) prepareNextVideoPlan()
                }
        }
    }

    /** Called by the pager as soon as it starts moving, before the target is stable. */
    fun onPageUnstable() {
        playerController.setTemporaryPlaybackSpeed(active = false)
        val decision = targetCoordinator.dispatch(PlaybackTargetIntent.PageBecameUnstable)
        val started = decision as? PlaybackTargetDecision.TransitionStarted ?: return
        pendingOriginalPlayback = null
        rebuildUiState()
        playerController.recordTransition(
            if (started.pointerDownAtMillis == null) {
                PlaybackTransitionEvent.PageUnstable
            } else {
                PlaybackTransitionEvent.GestureStarted(started.pointerDownAtMillis)
            },
        )
        stablePageGeneration += 1
        stablePageJob?.cancel()
        stablePageJob = null
        retryJob?.cancel()
        retryJob = null
        cancelTransparentRecovery(clearAttempt = true)
        playerController.pauseForPageTransition()
    }

    fun onPagerPointerDown(observedAtMillis: Long) {
        recordSwipeHintInteraction()
        val decision = targetCoordinator.dispatch(
            PlaybackTargetIntent.PointerDown(observedAtMillis),
        ) as? PlaybackTargetDecision.PointerStarted ?: return
        if (decision.whileUnstable) {
            playerController.recordTransition(
                PlaybackTransitionEvent.GestureStarted(observedAtMillis),
            )
            cancelPlanPreparation(clearNextPlan = false)
        }
    }

    fun onPagerPointerReleased(observedAtMillis: Long) {
        val decision = targetCoordinator.dispatch(
            PlaybackTargetIntent.PointerReleased(observedAtMillis),
        )
        if (decision is PlaybackTargetDecision.PointerFinished) {
            playerController.recordTransition(
                PlaybackTransitionEvent.GestureReleased(observedAtMillis),
            )
        }
    }

    /**
     * Starts cancelable metadata/quality preparation for Compose Pager's current target.
     * It never binds the shared player; binding remains gated by [onPageSettled].
     */
    fun onPageTargeted(pagerPage: Int, logicalPage: Int) {
        val keyTarget = resolveQueueKeyTarget(pagerPage, logicalPage) ?: return
        val target = hydrateQueueTarget(keyTarget) ?: run {
            scheduleHydration(keyTarget) { onPageTargeted(pagerPage, logicalPage) }
            return
        }
        val item = buildItem(target.video)
        val currentKey = currentItem()?.video?.key
        val decision = targetCoordinator.dispatch(
            PlaybackTargetIntent.Targeted(
                target = target.toIdentity(pagerPage, logicalPage),
                currentKey = currentKey,
            ),
        ) as? PlaybackTargetDecision.TargetEvaluated ?: return
        when (decision.outcome) {
            PlaybackTargetOutcome.BOUNCE_TO_CURRENT_IGNORED,
            PlaybackTargetOutcome.CURRENT_TARGET,
            -> return
            PlaybackTargetOutcome.CURRENT_TARGET_ABANDONED -> {
                playerController.recordTransition(PlaybackTransitionEvent.TargetAbandoned)
                preloadController.abandonTargetPromotion()
                cancelPlanPreparation(clearNextPlan = true)
                return
            }
            PlaybackTargetOutcome.EXISTING_TARGET -> Unit
            PlaybackTargetOutcome.NEW_TARGET -> {
                val replacesExistingCandidate =
                    decision.previous?.key != null && decision.previous.key != currentKey
                val replacesForwardCandidate = nextTarget()?.samePosition(decision.target) != true
                if (replacesExistingCandidate || replacesForwardCandidate) {
                    preloadController.setNextVideo(null)
                }
                playerController.recordTransition(targetKnownEvent(target, pagerPage))
                playerController.recordTransition(
                    PlaybackTransitionEvent.PlanPreparationStarted(item.video.key),
                )
            }
        }
        preloadController.commitTargetPromotion(promotionVideo(target))
        ensurePlanPreparation(target)
    }

    /** Only the latest settled page is allowed to bind the shared player. */
    fun onPageSettled(pagerPage: Int, logicalPage: Int) {
        val keyTarget = resolveQueueKeyTarget(pagerPage, logicalPage) ?: return
        val reportedTarget = hydrateQueueTarget(keyTarget) ?: run {
            scheduleHydration(keyTarget) { onPageSettled(pagerPage, logicalPage) }
            return
        }
        val reportedItem = buildItem(reportedTarget.video)
        val reportedSettledEvent = pageSettledEvent(reportedTarget, pagerPage)
        val decision = targetCoordinator.dispatch(
            PlaybackTargetIntent.Settled(
                target = reportedTarget.toIdentity(pagerPage, logicalPage),
                queueGeneration = playbackQueueGeneration,
            ),
        ) as PlaybackTargetDecision.PageSettled
        if (decision.expectedTargetMismatch) {
            if (reportedItem.video.key == currentItem()?.video?.key) {
                playerController.recordTransition(PlaybackTransitionEvent.TargetAbandoned)
            } else {
                playerController.recordTransition(
                    targetKnownEvent(reportedTarget, pagerPage),
                )
            }
            preloadController.abandonTargetPromotion()
            cancelPlanPreparation(clearNextPlan = true)
        }
        val wasUnstable = decision.wasUnstable
        val settledTarget = if (reportedTarget.randomEntry == null) {
            feedSnapshot = feedSession.settle(logicalPage)
            reportedTarget
        } else {
            val previousGeneration = feedSnapshot.roundGeneration
            val settledState = feedSession.settleRandom(reportedTarget.randomEntry)
            feedSnapshot = settledState
            currentFeedKeySet = settledState.keys.toHashSet()
            upcomingFeedKeySet = settledState.upcoming?.keys.orEmpty().toHashSet()
            if (settledState.roundGeneration != previousGeneration) {
                items = buildItemsFromKeys(
                    hydratedVideos.keys.filter(currentFeedKeySet::contains),
                    latestChannelTitles,
                )
                randomRoundStartPagerPage = pagerPage - reportedTarget.randomEntry.index
            } else if (randomRoundStartPagerPage == null) {
                randomRoundStartPagerPage = pagerPage - reportedTarget.randomEntry.index
            }
            val currentEntry = requireNotNull(settledState.currentEntry())
            QueueTarget(
                video = requireNotNull(hydratedVideos[currentEntry.key]),
                randomEntry = currentEntry,
            )
        }
        val item = buildItem(settledTarget.video)
        if (decision.duplicate) return
        if (approvedOriginalPlayback?.first != item.video.key) approvedOriginalPlayback = null
        playerController.recordTransition(reportedSettledEvent)
        currentPage = reportedTarget.randomEntry?.index ?: logicalPage
        if (
            wasUnstable &&
            playerSnapshot.playbackState.videoKeyOrNull() == item.video.key
        ) {
            preloadController.abandonTargetPromotion()
            playerController.resume()
            prepareNextVideoPlan()
            rebuildUiState(originalMessageLink = OriginalMessageLinkUiState.Idle)
            return
        }
        if (wasUnstable) {
            preloadController.commitTargetPromotion(promotionVideo(settledTarget))
        }
        if (planPreparation != null && planPreparation?.key != item.video.key) {
            cancelPlanPreparation(clearNextPlan = true)
        }
        val requestGeneration = stablePageGeneration + 1
        stablePageGeneration = requestGeneration
        stablePageJob?.cancel()
        stablePageJob = viewModelScope.launch {
            if (
                requestGeneration != stablePageGeneration ||
                currentItem()?.video?.key != item.video.key
            ) {
                return@launch
            }
            val token = currentPlanToken(settledTarget.randomEntry?.roundGeneration)
            var plan = promoteNextPlan(item.video.key, token)
            if (plan == null) {
                val preparation = planPreparation?.takeIf { pending ->
                    pending.key == item.video.key && pending.token == token
                }
                if (preparation != null) {
                    preparation.deferred.await()
                    plan = promoteNextPlan(item.video.key, token)
                }
            }
            if (plan == null) {
                playerController.recordTransition(
                    PlaybackTransitionEvent.PlanStarted(
                        key = item.video.key,
                        promoted = false,
                    ),
                )
                plan = preparePlaybackPlan(
                    video = item.video,
                    token = token,
                    recordRefresh = true,
                )
                if (plan != null && isPlanRequestCurrent(token, item.video.key)) {
                    installCurrentPlan(plan)
                }
            } else {
                playerController.recordTransition(
                    PlaybackTransitionEvent.PlanStarted(
                        key = item.video.key,
                        promoted = true,
                        planAgeMillis =
                            (monotonicTimeMillis() - plan.preparedAtMillis).coerceAtLeast(0L),
                        preparedRefreshOutcome = plan.refreshOutcome,
                        preparedRefreshMillis = plan.refreshMillis,
                    ),
                )
            }
            val currentItem = currentItem()
            val terminalFailure = plan?.terminalFailure
            if (
                terminalFailure != null &&
                targetCoordinator.state.lastSettled?.key == item.video.key &&
                !targetCoordinator.state.isUnstable
            ) {
                planPreparation = null
                preloadController.stop()
                playerController.showFailure(
                    requireNotNull(plan).toVideo(item.video),
                    terminalFailure,
                )
                return@launch
            }
            if (
                requestGeneration != stablePageGeneration ||
                currentItem?.video?.key != item.video.key ||
                plan == null ||
                !plan.matches(
                    item.video.key,
                    currentPlanToken(settledTarget.randomEntry?.roundGeneration),
                )
            ) {
                return@launch
            }
            planPreparation = null
            val plannedVideo = plan.toVideo(currentItem.video)
            if (plan.terminalFailure == null) {
                bindStableItem(plannedVideo)
            } else {
                preloadController.stop()
                playerController.showFailure(plannedVideo, plan.terminalFailure)
            }
        }
        rebuildUiState()
        scheduleHydration(feedSnapshot.currentIndex)
    }

    fun togglePause() {
        if (playerSnapshot.isPaused) {
            playerController.resume()
        } else {
            playerController.setTemporaryPlaybackSpeed(active = false)
            playerController.pause()
        }
    }

    fun setTemporaryPlaybackSpeed(active: Boolean) {
        if (!active) {
            playerController.setTemporaryPlaybackSpeed(active = false)
            return
        }
        val ready = playerSnapshot.playbackState as? VideoPlaybackState.Ready
        val currentKey = currentItem()?.video?.key
        val canActivate = ready != null &&
            ready.video.key == currentKey &&
            targetCoordinator.state.lastSettled?.key == currentKey &&
            !targetCoordinator.state.isUnstable &&
            playerSnapshot.hasRenderedFirstFrame &&
            playerSnapshot.isPlaying &&
            !playerSnapshot.isPaused
        playerController.setTemporaryPlaybackSpeed(active = canActivate)
    }

    fun attachPlayer(playerView: PlayerView) {
        playerController.attach(playerView)
    }

    fun detachPlayer(playerView: PlayerView) {
        playerController.detach(playerView)
    }

    fun seekTo(positionMillis: Long) {
        playerController.setTemporaryPlaybackSpeed(active = false)
        preloadController.stop()
        playerController.seekTo(positionMillis)
    }

    fun toggleMute() {
        playerController.setMuted(!playerSnapshot.isMuted)
    }

    fun retry() {
        val failed = playerSnapshot.playbackState as? VideoPlaybackState.Failed
        if (failed?.reason == VideoPlaybackFailure.MESSAGE_UNAVAILABLE) return
        if (failed?.reason != VideoPlaybackFailure.FILE_UNAVAILABLE) {
            playerController.retry()
            return
        }
        val failedKey = failed.video.key
        cancelTransparentRecovery(clearAttempt = true)
        val requestGeneration = stablePageGeneration
        val token = currentPlanToken()
        val retryRequestGeneration = retryGeneration + 1L
        retryGeneration = retryRequestGeneration
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            val resolution = resolveVideoReference(failedKey)
            if (!isManualRetryPresentationCurrent(failedKey, retryRequestGeneration, token)) {
                return@launch
            }
            when (resolution) {
                is VideoReferenceResolution.Resolved -> {
                    val currentItem = currentItem()
                    if (
                        requestGeneration != stablePageGeneration ||
                        currentItem?.video?.key != failedKey ||
                        token != currentPlanToken()
                    ) {
                        return@launch
                    }
                    val video = selectPlaybackVideo(resolution.video, token.selection)
                    if (admitOriginalPlayback(video)) playerController.bind(video, poolContext(token))
                }
                VideoReferenceResolution.MessageMissing,
                VideoReferenceResolution.UnsupportedMessage,
                -> publishManualRetryMessageUnavailable(failed.video)
                is VideoReferenceResolution.Unavailable,
                null,
                -> Unit
            }
        }
    }

    fun retryFeedObservation() {
        feedObservationRetry.value += 1L
    }

    private fun isManualRetryPresentationCurrent(
        failedKey: VideoKey,
        retryRequestGeneration: Long,
        token: PlaybackPlanToken,
    ): Boolean {
        val failed = playerSnapshot.playbackState as? VideoPlaybackState.Failed ?: return false
        return retryRequestGeneration == retryGeneration &&
            retryJob?.isActive == true &&
            !targetCoordinator.state.isUnstable &&
            failed.reason == VideoPlaybackFailure.FILE_UNAVAILABLE &&
            failed.video.key == failedKey &&
            token.qualitySelectionGeneration == qualitySelectionGeneration &&
            token.accountGeneration == accountGeneration &&
            token.selection == qualitySelection
    }

    private fun publishManualRetryMessageUnavailable(
        video: com.qixuan.channelvideoflow.model.video.IndexedVideo,
    ) {
        playerSnapshot = playerSnapshot.copy(
            playbackState = VideoPlaybackState.Failed(
                video,
                VideoPlaybackFailure.MESSAGE_UNAVAILABLE,
            ),
            isPaused = false,
            hasRenderedFirstFrame = false,
        )
        mutablePlaybackProgress.value = playerSnapshot.toProgressUiState()
        playerController.showFailure(video, VideoPlaybackFailure.MESSAGE_UNAVAILABLE)
        rebuildUiState()
    }

    fun setOrder(order: VideoFeedOrder) {
        if (criteria.value.order == order) return
        stopOldFeedRequests()
        criteria.value = criteria.value.copy(order = order)
    }

    /**
     * Allows a future channel/tag screen to atomically replace the feed source.
     * An empty channel set intentionally means an empty feed, never all channels.
     */
    fun setFilter(filter: VideoFilter) {
        if (
            criteria.value.channelIds == filter.channelIds &&
            criteria.value.normalizedTags == filter.normalizedTags &&
            criteria.value.tagMode == filter.tagMode
        ) return
        stopOldFeedRequests()
        criteria.value = criteria.value.copy(
            channelIds = filter.channelIds,
            normalizedTags = filter.normalizedTags,
            tagMode = filter.tagMode,
        )
    }

    fun setFeedSource(filter: VideoFilter, order: VideoFeedOrder) {
        val next = FeedCriteria(
            channelIds = filter.channelIds,
            normalizedTags = filter.normalizedTags,
            tagMode = filter.tagMode,
            order = order,
        )
        if (criteria.value == next) return
        stopOldFeedRequests()
        criteria.value = next
    }

    fun requestOriginalMessageLink() {
        val item = currentItem() ?: return
        linkJob?.cancel()
        rebuildUiState(originalMessageLink = OriginalMessageLinkUiState.Loading)
        linkJob = viewModelScope.launch {
            when (val result = messageRepository.getOriginalMessageLink(item.video.key)) {
                is OriginalMessageLinkResult.Available -> {
                    rebuildUiState(originalMessageLink = OriginalMessageLinkUiState.Idle)
                    mutableOpenOriginalMessageLinks.emit(result.httpsUrl)
                }
                OriginalMessageLinkResult.Unavailable -> showUnavailableLink("无法打开 Telegram 原消息")
                OriginalMessageLinkResult.NetworkUnavailable -> showUnavailableLink("网络不可用，无法打开 Telegram 原消息")
                OriginalMessageLinkResult.Unknown -> showUnavailableLink("无法打开 Telegram 原消息")
            }
        }
    }

    fun onOriginalMessageLinkOpenFailed() {
        showUnavailableLink("无法打开 Telegram 原消息")
    }

    fun onForegroundChanged(isForeground: Boolean) {
        this.isForeground = isForeground
        if (!isForeground) {
            playerController.setTemporaryPlaybackSpeed(active = false)
            cancelPlanPreparation(clearNextPlan = false)
            preloadController.stop()
            playerController.onAppBackgrounded()
        } else if (playerSnapshot.playbackState.videoKeyOrNull() != null) {
            prepareNextVideoPlan()
        }
    }

    fun releasePage() {
        pendingOriginalPlayback = null
        approvedOriginalPlayback = null
        playerController.setTemporaryPlaybackSpeed(active = false)
        dismissSwipeHintIfVisible()
        accountGeneration += 1L
        stablePageJob?.cancel()
        stablePageJob = null
        cancelPlanPreparation(clearNextPlan = true)
        playbackPlans.set(PlaybackPlanSlots())
        linkJob?.cancel()
        retryJob?.cancel()
        retryJob = null
        cancelTransparentRecovery(clearAttempt = true)
        hydrationRequestGeneration += 1L
        hydrationJob?.cancel()
        hydrationJob = null
        preloadController.stop()
        playerController.release()
        viewModelScope.launch { cacheController.trimToLimit() }
    }

    override fun onCleared() {
        releasePage()
        super.onCleared()
    }

    private fun reconcileFeedKeys(result: FeedSourceResult) {
        if (result.failure != null && result.rows.isEmpty() && feedSnapshot.keys.isNotEmpty()) {
            sourceLoaded = true
            feedObservationFailure = result.failure
            rebuildUiState()
            return
        }
        val sourceChanged = lastAppliedSource?.let { previous ->
            previous.filter != result.source.filter || previous.order != result.source.order
        } ?: true
        if (sourceChanged) {
            stopOldFeedRequests()
            queueGeneration += 1
            currentPage = 0
            randomRoundStartPagerPage = null
            feedSession.reset()
        }
        lastAppliedSource = result.source
        sourceLoaded = true
        keyObservationFailure = result.failure
        feedObservationFailure = result.failure
        latestChannelTitles = result.source.channelTitles

        val previousSnapshot = feedSnapshot
        feedSnapshot = feedSession.replace(result.rows, result.source.order)
        currentFeedKeySet = feedSnapshot.keys.toHashSet()
        upcomingFeedKeySet = feedSnapshot.upcoming?.keys.orEmpty().toHashSet()
        val currentAndUpcomingKeys = buildSet {
            addAll(currentFeedKeySet)
            addAll(upcomingFeedKeySet)
        }
        val rebuiltItems = items.mapNotNull { item ->
            item.video.key.takeIf(currentFeedKeySet::contains)
                ?.let(hydratedVideos::get)
                ?.let { video -> buildItem(video, result.source.channelTitles) }
        }
        val randomSessionStructureChanged =
            previousSnapshot.roundGeneration != feedSnapshot.roundGeneration ||
                previousSnapshot.upcoming?.generation != feedSnapshot.upcoming?.generation ||
                previousSnapshot.upcoming?.keys != feedSnapshot.upcoming?.keys
        val queueStructureChanged = !sourceChanged &&
            (
                previousSnapshot.keys != feedSnapshot.keys ||
                    randomSessionStructureChanged
                )
        val queueMetadataChanged = !sourceChanged && !queueStructureChanged &&
            rebuiltItems.map(FeedVideoItem::video) != items.map(FeedVideoItem::video)
        val recoveryKey = transparentRecoveryAttempt?.key?.videoKey
        val preserveRemovedCurrentRecovery = queueStructureChanged &&
            recoveryKey != null &&
            recoveryKey !in currentAndUpcomingKeys &&
            playerSnapshot.playbackState.videoKeyOrNull() == recoveryKey
        val resolvingSettledKey = targetCoordinator.state.lastSettled?.key?.takeIf { key ->
            activeReferenceResolutionCounts[key]?.let { count -> count > 0 } == true
        }
        val preserveRemovedCurrentPlan = queueStructureChanged &&
            resolvingSettledKey != null &&
            resolvingSettledKey !in currentAndUpcomingKeys &&
            stablePageJob?.isActive == true
        val retryingFailedKey = (playerSnapshot.playbackState as? VideoPlaybackState.Failed)
            ?.takeIf { failed -> failed.reason == VideoPlaybackFailure.FILE_UNAVAILABLE }
            ?.video
            ?.key
        val preserveRemovedCurrentRetry = queueStructureChanged &&
            retryingFailedKey != null &&
            retryingFailedKey !in currentAndUpcomingKeys &&
            retryJob?.isActive == true
        val messageUnavailableKey = (playerSnapshot.playbackState as? VideoPlaybackState.Failed)
            ?.takeIf { failed -> failed.reason == VideoPlaybackFailure.MESSAGE_UNAVAILABLE }
            ?.video
            ?.key
        val preserveRemovedMessageUnavailable = queueStructureChanged &&
            messageUnavailableKey != null &&
            messageUnavailableKey !in currentAndUpcomingKeys
        val preserveRemovedCurrentPresentation =
            preserveRemovedCurrentRecovery || preserveRemovedCurrentPlan ||
                preserveRemovedCurrentRetry || preserveRemovedMessageUnavailable
        if (queueStructureChanged) {
            preloadController.stop()
            invalidatePlaybackPlans(
                queueChanged = true,
                preserveTransparentRecovery = preserveRemovedCurrentRecovery,
                preserveStablePageJob = preserveRemovedCurrentPlan,
                preserveRetryJob = preserveRemovedCurrentRetry,
            )
        } else if (queueMetadataChanged) {
            // A refresh may publish its same-key Room write just after the prepared plan is
            // installed. Keep only plans whose source fields still describe that exact metadata;
            // this preserves single-flight target -> settle without accepting stale references.
            reconcilePlaybackPlansWith(rebuiltItems)
        }
        items = rebuiltItems
        if (feedSnapshot.order == VideoFeedOrder.RANDOM) {
            randomRoundStartPagerPage = if (feedSnapshot.keys.isEmpty()) {
                null
            } else {
                targetCoordinator.state.lastSettled?.pagerPage?.minus(feedSnapshot.currentIndex)
                    ?: randomRoundStartPagerPage
            }
        }

        val currentVideoStillExists = playerSnapshot.playbackState.videoKeyOrNull()
            ?.let(currentAndUpcomingKeys::contains) == true
        if (
            !currentVideoStillExists &&
            !preserveRemovedCurrentPresentation &&
            playerSnapshot.playbackState !is VideoPlaybackState.Idle
        ) {
            preloadController.stop()
            playerController.releaseBinding()
        }
        currentPage = feedSnapshot.currentIndex
        if (
            queueStructureChanged &&
            currentVideoStillExists &&
            playerSnapshot.hasRenderedFirstFrame
        ) {
            prepareNextVideoPlan()
        }
        rebuildUiState()
        scheduleHydration(feedSnapshot.currentIndex)
    }

    private fun needsOriginalConfirmation(video: IndexedVideo): Boolean =
        OriginalPlaybackAdmission.requiresConfirmation(
            video, qualitySelection.preference, qualitySelection.network,
        ) && approvedOriginalPlayback != (video.key to video.playbackFileId)

    private fun admitOriginalPlayback(video: IndexedVideo): Boolean {
        if (!needsOriginalConfirmation(video)) {
            pendingOriginalPlayback = null
            return true
        }
        pendingOriginalPlayback = video to currentPlanToken()
        cancelTransparentRecovery(clearAttempt = true)
        preloadController.stop()
        playerController.discardStandby()
        playerController.releaseBinding()
        rebuildUiState()
        return false
    }

    fun confirmOriginalPlayback(key: VideoKey, fileId: Int) {
        val (video, token) = pendingOriginalPlayback ?: return
        if (video.key != key || video.playbackFileId != fileId || !isForeground ||
            targetCoordinator.state.isUnstable || currentItem()?.video?.key != key
        ) return
        if (!isPlanRequestCurrent(token, key)) {
            pendingOriginalPlayback = null
            rebuildUiState()
            // Resolve the current representation again after a preference/network change.
            val pagerPage = targetCoordinator.state.lastSettled?.pagerPage ?: currentPage
            targetCoordinator.dispatch(PlaybackTargetIntent.Reset)
            onPageSettled(pagerPage, currentPage)
            return
        }
        approvedOriginalPlayback = key to fileId
        pendingOriginalPlayback = null
        bindStableItem(video)
    }

    private fun bindStableItem(
        video: com.qixuan.channelvideoflow.model.video.IndexedVideo,
    ) {
        if (!admitOriginalPlayback(video)) return
        feedSession.recordPlayed(video.key, criteria.value.order)
        val boundKey = playerSnapshot.playbackState.videoKeyOrNull()
        if (boundKey == video.key) {
            playerController.resume()
        } else {
            preloadController.onCurrentPlaybackStarting(video)
            playerController.bind(video, poolContext())
        }
        if (
            preloadController.ownerHandoff.value.phase !=
            PreloadOwnerHandoffPhase.TARGET_COMMITTED
        ) {
            prepareNextVideoPlan()
        }
        viewModelScope.launch { cacheController.trimToLimit() }
        rebuildUiState(originalMessageLink = OriginalMessageLinkUiState.Idle)
    }

    private fun stopOldFeedRequests() {
        pendingOriginalPlayback = null
        approvedOriginalPlayback = null
        playerController.setTemporaryPlaybackSpeed(active = false)
        playbackQueueGeneration += 1L
        targetCoordinator.dispatch(PlaybackTargetIntent.Reset)
        stablePageGeneration += 1
        stablePageJob?.cancel()
        stablePageJob = null
        cancelPlanPreparation(clearNextPlan = true)
        playbackPlans.set(PlaybackPlanSlots())
        retryJob?.cancel()
        retryJob = null
        cancelTransparentRecovery(clearAttempt = true)
        hydrationRequestGeneration += 1L
        hydrationJob?.cancel()
        hydrationJob = null
        preloadController.stop()
        playerController.releaseBinding()
    }

    private fun prepareNextVideoPlan() {
        val next = nextTarget()
        if (next == null) {
            cancelPlanPreparation(clearNextPlan = true)
            preloadController.stop()
            return
        }
        val token = currentPlanToken(next.randomEntry?.roundGeneration)
        val readyPlan = playbackPlans.get().next
        if (readyPlan?.matches(next.video.key, token) == true) {
            activateNextPreloadIfEligible(readyPlan, next)
            return
        }
        ensurePlanPreparation(next)
    }

    private fun promotionVideo(
        target: QueueTarget,
    ): com.qixuan.channelvideoflow.model.video.IndexedVideo {
        val video = target.video
        val token = currentPlanToken(target.randomEntry?.roundGeneration)
        val prepared = playbackPlans.get().next
            ?.takeIf { plan -> plan.matches(video.key, token) }
        return prepared?.toVideo(video) ?: video
    }

    private fun ensurePlanPreparation(
        target: QueueTarget,
    ) {
        val video = target.video
        val token = currentPlanToken(target.randomEntry?.roundGeneration)
        val readyPlan = playbackPlans.get().next
        if (readyPlan?.matches(video.key, token) == true) {
            recordTargetPlanPreparedIfApplicable(target)
            activateNextPreloadIfEligible(readyPlan, target)
            return
        }
        val existing = planPreparation
        if (
            existing?.key == video.key &&
            existing.token == token &&
            existing.deferred.isActive
        ) {
            return
        }

        cancelPlanPreparation(clearNextPlan = true)
        val generation = planPreparationGeneration + 1L
        planPreparationGeneration = generation
        val deferred = viewModelScope.async(start = CoroutineStart.LAZY) {
            val plan = preparePlaybackPlan(
                video = video,
                token = token,
                recordRefresh = false,
                forStandby = true,
            )
            if (
                plan != null &&
                planPreparation?.generation == generation &&
                isPlanRequestCurrent(token, video.key)
            ) {
                installNextPlan(plan)
                recordTargetPlanPreparedIfApplicable(target)
                activateNextPreloadIfEligible(plan, target)
            }
            plan
        }
        planPreparation = PlanPreparation(
            generation = generation,
            key = video.key,
            token = token,
            deferred = deferred,
        )
        deferred.start()
    }

    private fun activateNextPreloadIfEligible(
        plan: PlaybackPlan,
        target: QueueTarget,
    ) {
        val video = target.video
        val currentKey = currentItem()?.video?.key
        val next = nextTarget()
        val committedTarget = targetCoordinator.state.unstableTarget
            ?.takeIf { targetCoordinator.state.isUnstable }
            ?.takeIf { target.samePosition(it) }
        if (
            plan.terminalFailure != null ||
            playerSnapshot.playbackState.videoKeyOrNull() != currentKey ||
            (
                committedTarget == null &&
                    (
                        next?.video?.key != plan.key ||
                            next.randomEntry?.roundGeneration != plan.token.randomRoundGeneration
                        )
                ) ||
            video.key != plan.key ||
            !plan.matches(
                plan.key,
                currentPlanToken(target.randomEntry?.roundGeneration),
            )
        ) {
            return
        }
        val preparedVideo = plan.toVideo(video)
        if (needsOriginalConfirmation(preparedVideo)) return
        if (!playerSnapshot.hasRenderedFirstFrame) {
            // Lightweight stage: register only the single next target so the decoder-free byte
            // preloader can issue its bounded 256 KiB TTFB prefix. The heavy pool standby is NOT
            // started here, so the current item keeps the link to finish its own startup reserve.
            preloadController.setNextVideo(preparedVideo)
            return
        }
        if (playerController.supportsStandbyPreparation) {
            preloadController.stop()
            if (!playerController.prepareStandby(preparedVideo, poolContext(plan.token))) {
                preloadController.setNextVideo(preparedVideo)
            }
        } else {
            preloadController.setNextVideo(preparedVideo)
        }
    }

    private fun poolContext(token: PlaybackPlanToken = currentPlanToken()) = PlaybackPreparationContext(
        qualityGeneration = token.qualitySelectionGeneration,
        accountGeneration = token.accountGeneration,
        networkGeneration = devicePolicySource.signals.value.networkGeneration,
        queueGeneration = token.queueGeneration,
        roundGeneration = token.randomRoundGeneration,
    )

    private suspend fun preparePlaybackPlan(
        video: com.qixuan.channelvideoflow.model.video.IndexedVideo,
        token: PlaybackPlanToken,
        recordRefresh: Boolean,
        forStandby: Boolean = false,
    ): PlaybackPlan? {
        if (!isPlanRequestCurrent(token, video.key)) return null
        if (recordRefresh) {
            playerController.recordTransition(PlaybackTransitionEvent.RefreshStarted(video.key))
        }
        val refreshStartedAtMillis = monotonicTimeMillis()
        val refreshResult = refreshVideoForPlayback(video, token.selection)
        val refreshMillis =
            (monotonicTimeMillis() - refreshStartedAtMillis).coerceAtLeast(0L)
        if (recordRefresh) {
            playerController.recordTransition(
                PlaybackTransitionEvent.RefreshFinished(
                    key = video.key,
                    outcome = refreshResult.outcome,
                ),
            )
        }
        if (!isPlanRequestCurrent(token, video.key) && refreshResult.terminalFailure == null) {
            return null
        }
        val source = refreshResult.video ?: video
        val selected = if (forStandby) selectStandbyVideo(source, token.selection)
            else selectPlaybackVideo(source, token.selection)
        return PlaybackPlan.from(
            video = selected,
            token = token,
            refreshOutcome = refreshResult.outcome,
            refreshMillis = refreshMillis,
            preparedAtMillis = monotonicTimeMillis(),
            terminalFailure = refreshResult.terminalFailure,
        )
    }

    private fun currentPlanToken(
        randomRoundGeneration: Long? = feedSnapshot.roundGeneration
            ?.takeIf { criteria.value.order == VideoFeedOrder.RANDOM },
    ): PlaybackPlanToken = PlaybackPlanToken(
        qualitySelectionGeneration = qualitySelectionGeneration,
        selection = qualitySelection,
        accountGeneration = accountGeneration,
        queueGeneration = playbackQueueGeneration,
        randomRoundGeneration = randomRoundGeneration,
    )

    private fun isPlanRequestCurrent(
        token: PlaybackPlanToken,
        key: VideoKey,
    ): Boolean {
        if (token != currentPlanToken(token.randomRoundGeneration)) return false
        if (criteria.value.order != VideoFeedOrder.RANDOM) {
            return key in feedSnapshot.keys
        }
        val keys = when (token.randomRoundGeneration) {
            feedSnapshot.roundGeneration -> feedSnapshot.keys
            feedSnapshot.upcoming?.generation -> feedSnapshot.upcoming?.keys
            else -> null
        }
        return key in keys.orEmpty()
    }

    private fun installCurrentPlan(plan: PlaybackPlan) {
        while (true) {
            val current = playbackPlans.get()
            val updated = current.copy(current = plan)
            if (playbackPlans.compareAndSet(current, updated)) return
        }
    }

    private fun installNextPlan(plan: PlaybackPlan) {
        while (true) {
            val current = playbackPlans.get()
            val updated = current.copy(next = plan)
            if (playbackPlans.compareAndSet(current, updated)) return
        }
    }

    private fun reconcilePlaybackPlansWith(rebuiltItems: List<FeedVideoItem>) {
        val metadataByKey = rebuiltItems.associate { item -> item.video.key to item.video }
        var removedNextPlan = false
        while (true) {
            val current = playbackPlans.get()
            val updated = PlaybackPlanSlots(
                current = current.current?.takeIf { plan ->
                    metadataByKey[plan.key]?.let(plan::isCompatibleWith) == true
                },
                next = current.next?.takeIf { plan ->
                    metadataByKey[plan.key]?.let(plan::isCompatibleWith) == true
                },
            )
            if (updated == current) return
            if (playbackPlans.compareAndSet(current, updated)) {
                removedNextPlan = current.next != null && updated.next == null
                break
            }
        }
        if (removedNextPlan) preloadController.stop()
    }

    private fun promoteNextPlan(
        key: VideoKey,
        token: PlaybackPlanToken,
    ): PlaybackPlan? {
        while (true) {
            val current = playbackPlans.get()
            val candidate = current.next ?: return null
            if (!candidate.matches(key, token)) return null
            val promoted = PlaybackPlanSlots(current = candidate, next = null)
            if (playbackPlans.compareAndSet(current, promoted)) return candidate
        }
    }

    private fun cancelPlanPreparation(clearNextPlan: Boolean) {
        planPreparationGeneration += 1L
        planPreparation?.deferred?.cancel()
        planPreparation = null
        if (clearNextPlan) {
            playerController.discardStandby()
            while (true) {
                val current = playbackPlans.get()
                if (current.next == null) break
                if (playbackPlans.compareAndSet(current, current.copy(next = null))) break
            }
            preloadController.stop()
        }
    }

    private fun invalidatePlaybackPlans(
        queueChanged: Boolean = false,
        preserveTransparentRecovery: Boolean = false,
        preserveStablePageJob: Boolean = false,
        preserveRetryJob: Boolean = false,
    ) {
        if (queueChanged) playbackQueueGeneration += 1L
        stablePageGeneration += 1L
        if (!preserveStablePageJob) {
            stablePageJob?.cancel()
            stablePageJob = null
        }
        if (!preserveRetryJob) {
            retryJob?.cancel()
            retryJob = null
        }
        if (!preserveTransparentRecovery) cancelTransparentRecovery(clearAttempt = true)
        cancelPlanPreparation(clearNextPlan = true)
        playbackPlans.set(PlaybackPlanSlots())
    }

    private fun recordTargetPlanPreparedIfApplicable(target: QueueTarget) {
        if (
            targetCoordinator.state.isUnstable &&
            targetCoordinator.state.unstableTarget?.let(target::samePosition) == true
        ) {
            playerController.recordTransition(PlaybackTransitionEvent.PlanPrepared(target.video.key))
        }
    }

    private fun selectStandbyVideo(
        video: com.qixuan.channelvideoflow.model.video.IndexedVideo,
        selection: QualitySelection,
    ) = VideoQualitySelector.selectForStandby(video, selection.preference, selection.network)

    private fun selectPlaybackVideo(
        video: com.qixuan.channelvideoflow.model.video.IndexedVideo,
        selection: QualitySelection,
    ): com.qixuan.channelvideoflow.model.video.IndexedVideo =
        VideoQualitySelector.select(
            video = video,
            preference = selection.preference,
            network = selection.network,
            availableBandwidthBitsPerSecond = networkMetrics.estimate.value
                ?.availableBitsPerSecond?.takeIf { selection.preference == VideoQualityPreference.AUTO },
        )

    private suspend fun refreshVideoForPlayback(
        video: com.qixuan.channelvideoflow.model.video.IndexedVideo,
        selection: QualitySelection,
    ): PlaybackRefreshResult {
        val preference = selection.preference
        val requiresRandomReferenceRefresh = criteria.value.order == VideoFeedOrder.RANDOM
        if (
            preference == VideoQualityPreference.ORIGINAL &&
            !requiresRandomReferenceRefresh
        ) {
            return PlaybackRefreshResult(
                video = null,
                outcome = PlaybackPlanRefreshOutcome.SKIPPED,
            )
        }
        val resolution = resolveVideoReference(video.key)
        return when (resolution) {
            is VideoReferenceResolution.Resolved -> PlaybackRefreshResult(
                video = resolution.video,
                outcome = PlaybackPlanRefreshOutcome.SUCCESS,
            )
            VideoReferenceResolution.MessageMissing,
            VideoReferenceResolution.UnsupportedMessage,
            -> PlaybackRefreshResult(
                video = null,
                outcome = PlaybackPlanRefreshOutcome.FALLBACK,
                terminalFailure = VideoPlaybackFailure.MESSAGE_UNAVAILABLE,
            )
            is VideoReferenceResolution.Unavailable,
            null,
            -> PlaybackRefreshResult(
                video = null,
                outcome = PlaybackPlanRefreshOutcome.FALLBACK,
            )
        }
    }

    private suspend fun resolveVideoReference(videoKey: VideoKey): VideoReferenceResolution? {
        activeReferenceResolutionCounts[videoKey] =
            activeReferenceResolutionCounts.getOrDefault(videoKey, 0) + 1
        return try {
            withTimeoutOrNull(QUALITY_REFRESH_TIMEOUT_MILLIS) {
                messageRepository.refreshVideo(videoKey)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            VideoReferenceResolution.Unavailable(VideoReferenceFailure.Unknown)
        } finally {
            val remaining = activeReferenceResolutionCounts.getOrDefault(videoKey, 1) - 1
            if (remaining <= 0) {
                activeReferenceResolutionCounts.remove(videoKey)
            } else {
                activeReferenceResolutionCounts[videoKey] = remaining
            }
        }
    }

    private fun interceptFileUnavailableForRecovery(snapshot: VideoPlayerSnapshot): Boolean {
        val failed = snapshot.playbackState as? VideoPlaybackState.Failed ?: return false
        if (failed.reason != VideoPlaybackFailure.FILE_UNAVAILABLE) return false
        val currentItem = currentItem() ?: return false
        if (currentItem.video.key != failed.video.key) return false
        val token = currentPlanToken()
        val recoveryKey = TransparentRecoveryKey(
            videoKey = failed.video.key,
            stablePageGeneration = stablePageGeneration,
            qualitySelectionGeneration = token.qualitySelectionGeneration,
            accountGeneration = token.accountGeneration,
            queueGeneration = token.queueGeneration,
        )
        val prior = transparentRecoveryAttempt
        if (prior?.key == recoveryKey && prior.attempted) {
            playerController.recordTransition(
                PlaybackTransitionEvent.TransparentRecoveryFinished(
                    failed.video.key,
                    TransparentRecoveryOutcome.REFRESHED_FILE_UNAVAILABLE,
                ),
            )
            playerController.finishFileRecoveryFailure(failed.video.key)
            return false
        }

        cancelTransparentRecovery(clearAttempt = true)
        val attempt = TransparentRecoveryAttempt(
            key = recoveryKey,
            token = token,
            failedSnapshot = snapshot,
            failedPlaybackFileId = failed.video.playbackFileId,
        )
        transparentRecoveryAttempt = attempt
        preloadController.stop()
        playerSnapshot = snapshot.copy(
            playbackState = VideoPlaybackState.Loading(failed.video),
            hasRenderedFirstFrame = false,
        )
        mutablePlaybackProgress.value = playerSnapshot.toProgressUiState()
        rebuildUiState()
        playerController.recordTransition(
            PlaybackTransitionEvent.TransparentRecoveryStarted(failed.video.key),
        )
        transparentRecoveryJob = viewModelScope.launch {
            val resolution = resolveVideoReference(failed.video.key)
            if (!isTransparentRecoveryPresentationCurrent(attempt)) return@launch
            when (resolution) {
                is VideoReferenceResolution.Resolved -> {
                    if (!isTransparentRecoveryCurrent(attempt)) {
                        cancelTransparentRecovery(clearAttempt = true)
                        playerController.releaseBinding()
                        return@launch
                    }
                    val selected = selectPlaybackVideo(resolution.video, attempt.token.selection)
                    if (selected.playbackFileId == attempt.failedPlaybackFileId) {
                        recordTransparentRecoveryFinished(
                            attempt,
                            TransparentRecoveryOutcome.STALE_REFERENCE,
                        )
                        publishDeferredFileFailure(attempt)
                    } else if (admitOriginalPlayback(selected)) {
                        recordTransparentRecoveryFinished(
                            attempt,
                            TransparentRecoveryOutcome.REBOUND,
                        )
                        playerController.bind(selected, poolContext())
                    }
                }
                VideoReferenceResolution.MessageMissing,
                VideoReferenceResolution.UnsupportedMessage,
                -> {
                    recordTransparentRecoveryFinished(
                        attempt,
                        TransparentRecoveryOutcome.MESSAGE_UNAVAILABLE,
                    )
                    playerController.showFailure(
                        failed.video,
                        VideoPlaybackFailure.MESSAGE_UNAVAILABLE,
                    )
                }
                is VideoReferenceResolution.Unavailable -> {
                    recordTransparentRecoveryFinished(
                        attempt,
                        TransparentRecoveryOutcome.UNAVAILABLE,
                    )
                    publishDeferredFileFailure(attempt)
                }
                null -> {
                    recordTransparentRecoveryFinished(
                        attempt,
                        TransparentRecoveryOutcome.SOFT_TIMEOUT,
                    )
                    publishDeferredFileFailure(attempt)
                }
            }
        }
        return true
    }

    private fun isTransparentRecoveryCurrent(attempt: TransparentRecoveryAttempt): Boolean =
        isTransparentRecoveryPresentationCurrent(attempt) &&
            attempt.key.stablePageGeneration == stablePageGeneration &&
            currentItem()?.video?.key == attempt.key.videoKey &&
            attempt.token == currentPlanToken(attempt.token.randomRoundGeneration)

    private fun isTransparentRecoveryPresentationCurrent(
        attempt: TransparentRecoveryAttempt,
    ): Boolean =
        transparentRecoveryAttempt == attempt &&
            !targetCoordinator.state.isUnstable &&
            attempt.key.qualitySelectionGeneration == qualitySelectionGeneration &&
            attempt.key.accountGeneration == accountGeneration &&
            attempt.token.selection == qualitySelection &&
            playerSnapshot.playbackState.videoKeyOrNull() == attempt.key.videoKey

    private fun publishDeferredFileFailure(attempt: TransparentRecoveryAttempt) {
        if (!isTransparentRecoveryPresentationCurrent(attempt)) return
        playerController.finishFileRecoveryFailure(attempt.key.videoKey)
        playerSnapshot = attempt.failedSnapshot
        mutablePlaybackProgress.value = playerSnapshot.toProgressUiState()
        rebuildUiState()
    }

    private fun recordTransparentRecoveryFinished(
        attempt: TransparentRecoveryAttempt,
        outcome: TransparentRecoveryOutcome,
    ) {
        if (!isTransparentRecoveryPresentationCurrent(attempt)) return
        playerController.recordTransition(
            PlaybackTransitionEvent.TransparentRecoveryFinished(attempt.key.videoKey, outcome),
        )
    }

    private fun cancelTransparentRecovery(clearAttempt: Boolean) {
        transparentRecoveryJob?.cancel()
        transparentRecoveryJob = null
        if (clearAttempt) transparentRecoveryAttempt = null
    }

    private fun nextTarget(): QueueTarget? {
        if (feedSnapshot.keys.isEmpty()) return null
        val keyTarget = if (criteria.value.order == VideoFeedOrder.RANDOM) {
            feedSnapshot.nextRandomEntry()?.let { entry ->
                QueueKeyTarget(entry.key, entry.index, entry)
            }
        } else {
            feedSnapshot.keys.getOrNull(currentPage + 1)?.let { key ->
                QueueKeyTarget(key, currentPage + 1, null)
            }
        }
        return keyTarget?.let(::hydrateQueueTarget)
    }

    private fun resolveQueueKeyTarget(
        pagerPage: Int,
        logicalPage: Int,
    ): QueueKeyTarget? {
        if (criteria.value.order != VideoFeedOrder.RANDOM) {
            return feedSnapshot.keys.getOrNull(logicalPage)?.let { key ->
                QueueKeyTarget(key, logicalPage, null)
            }
        }
        if (feedSnapshot.keys.isEmpty()) return null
        val roundStart = randomRoundStartPagerPage
        val entry = if (roundStart == null) {
            feedSnapshot.keys.getOrNull(logicalPage)?.let {
                requireNotNull(feedSnapshot.roundGeneration)
                PlaybackFeedRoundEntry(it, requireNotNull(feedSnapshot.roundGeneration), logicalPage)
            }
        } else {
            val offset = pagerPage - roundStart
            when {
                offset in feedSnapshot.keys.indices -> PlaybackFeedRoundEntry(
                    feedSnapshot.keys[offset],
                    requireNotNull(feedSnapshot.roundGeneration),
                    offset,
                )
                offset >= feedSnapshot.keys.size -> {
                    val upcoming = feedSnapshot.upcoming
                    if (upcoming == null || upcoming.keys.isEmpty()) {
                        val index = Math.floorMod(offset, feedSnapshot.keys.size)
                        PlaybackFeedRoundEntry(
                            feedSnapshot.keys[index],
                            requireNotNull(feedSnapshot.roundGeneration),
                            index,
                        )
                    } else {
                        val upcomingIndex = Math.floorMod(
                            offset - feedSnapshot.keys.size,
                            upcoming.keys.size,
                        )
                        upcoming.entry(upcomingIndex)
                    }
                }
                feedSnapshot.keys.isNotEmpty() -> {
                    val index = Math.floorMod(offset, feedSnapshot.keys.size)
                    PlaybackFeedRoundEntry(
                        feedSnapshot.keys[index],
                        requireNotNull(feedSnapshot.roundGeneration),
                        index,
                    )
                }
                else -> null
            }
        }
        return entry?.let { QueueKeyTarget(it.key, it.index, it) }
    }

    private fun hydrateQueueTarget(target: QueueKeyTarget): QueueTarget? =
        hydratedVideos[target.key]?.let { video -> QueueTarget(video, target.randomEntry) }

    private fun scheduleHydration(
        centerIndex: Int,
        onHydrated: () -> Unit = {},
    ) {
        val snapshot = feedSnapshot
        val centerKey = snapshot.keys.getOrNull(centerIndex) ?: return
        val entry = snapshot.roundGeneration?.let { generation ->
            PlaybackFeedRoundEntry(centerKey, generation, centerIndex)
        }
        scheduleHydration(QueueKeyTarget(centerKey, centerIndex, entry), onHydrated)
    }

    private fun scheduleHydration(
        target: QueueKeyTarget,
        onHydrated: () -> Unit,
    ) {
        val snapshot = feedSnapshot
        val requestedKeys = hydrationKeysFor(snapshot, target)
        if (requestedKeys.isEmpty()) return
        val sessionGeneration = snapshot.generation
        val requestGeneration = hydrationRequestGeneration + 1L
        hydrationRequestGeneration = requestGeneration
        hydrationJob?.cancel()
        hydrationJob = viewModelScope.launch {
            val observation = messageRepository.hydrateVideos(requestedKeys)
            if (
                requestGeneration != hydrationRequestGeneration ||
                sessionGeneration != feedSnapshot.generation
            ) {
                return@launch
            }
            if (observation.failure != null) {
                feedObservationFailure = observation.failure
                hydrationJob = null
                rebuildUiState()
                return@launch
            }
            val requestedKeySet = requestedKeys.toHashSet()
            observation.value.forEach { video ->
                if (video.key in requestedKeySet) hydratedVideos[video.key] = video
            }
            val retainedKeys = buildSet {
                addAll(requestedKeys)
                playerSnapshot.playbackState.videoKeyOrNull()?.let(::add)
                targetCoordinator.state.lastSettled?.key?.let(::add)
                playbackPlans.get().current?.key?.let(::add)
                playbackPlans.get().next?.key?.let(::add)
                transparentRecoveryAttempt?.key?.videoKey?.let(::add)
            }
            hydratedVideos.keys.retainAll(retainedKeys)
            items = buildItemsFromKeys(
                retainedKeys.filter(currentFeedKeySet::contains),
                latestChannelTitles,
            )
            reconcilePlaybackPlansWith(items + buildItemsFromKeys(
                retainedKeys.filter(upcomingFeedKeySet::contains),
                latestChannelTitles,
            ))
            feedObservationFailure = keyObservationFailure
            hydrationJob = null
            uiHydrationGeneration += 1L
            rebuildUiState()
            if (hydratedVideos[target.key] != null) onHydrated()
        }
    }

    private fun hydrationKeysFor(
        snapshot: PlaybackFeedSnapshot,
        target: QueueKeyTarget,
    ): List<VideoKey> {
        val entry = target.randomEntry
        val upcoming = snapshot.upcoming
        if (entry != null && entry.roundGeneration == upcoming?.generation) {
            val start = (entry.index - FEED_HYDRATION_RADIUS).coerceAtLeast(0)
            val endExclusive = (entry.index + FEED_HYDRATION_RADIUS + 1)
                .coerceAtMost(upcoming.keys.size)
            return buildList {
                addAll(snapshot.keys.takeLast(FEED_HYDRATION_RADIUS + 1))
                addAll(upcoming.keys.subList(start, endExclusive))
            }.distinct()
        }
        return snapshot.windowAround(
            centerIndex = target.logicalIndex,
            radius = FEED_HYDRATION_RADIUS,
        ).keys
    }

    private fun showUnavailableLink(message: String) {
        rebuildUiState(originalMessageLink = OriginalMessageLinkUiState.Unavailable(message))
    }

    private fun targetKnownEvent(
        target: QueueTarget,
        pagerPage: Int,
    ): PlaybackTransitionEvent.TargetKnown {
        val context = transitionContext(pagerPage, target)
        return PlaybackTransitionEvent.TargetKnown(
            key = target.video.key,
            order = context.order,
            direction = context.direction,
            randomRoundBoundary = context.randomRoundBoundary,
        )
    }

    private fun pageSettledEvent(
        target: QueueTarget,
        pagerPage: Int,
    ): PlaybackTransitionEvent.PageSettled {
        val context = transitionContext(pagerPage, target)
        return PlaybackTransitionEvent.PageSettled(
            key = target.video.key,
            order = context.order,
            direction = context.direction,
            randomRoundBoundary = context.randomRoundBoundary,
        )
    }

    private fun transitionContext(
        pagerPage: Int,
        target: QueueTarget,
    ): PlaybackTransitionContext {
        val previousPagerPage = targetCoordinator.state.lastSettled?.pagerPage
        val direction = when {
            previousPagerPage == null -> PlaybackTransitionDirection.INITIAL
            pagerPage > previousPagerPage -> PlaybackTransitionDirection.FORWARD
            pagerPage < previousPagerPage -> PlaybackTransitionDirection.REVERSE
            else -> PlaybackTransitionDirection.UNCHANGED
        }
        val order = criteria.value.order
        val randomRoundBoundary = order == VideoFeedOrder.RANDOM &&
            previousPagerPage != null &&
            target.randomEntry?.roundGeneration !=
            feedSnapshot.roundGeneration
        return PlaybackTransitionContext(
            order = order,
            direction = direction,
            randomRoundBoundary = randomRoundBoundary,
        )
    }

    private fun buildItemsFromKeys(
        keys: Iterable<VideoKey>,
        channelTitles: Map<Long, String>,
    ): List<FeedVideoItem> = keys.mapNotNull { key ->
        hydratedVideos[key]?.let { video -> buildItem(video, channelTitles) }
    }

    private fun currentItem(): FeedVideoItem? = feedSnapshot.keys
        .getOrNull(currentPage)
        ?.let(hydratedVideos::get)
        ?.let(::buildItem)

    private fun buildItem(
        video: com.qixuan.channelvideoflow.model.video.IndexedVideo,
        channelTitles: Map<Long, String> = latestChannelTitles,
    ): FeedVideoItem = FeedVideoItem(
        video = video,
        channelTitle = channelTitles[video.key.chatId] ?: "未知频道",
    )

    private fun rebuildUiState(
        originalMessageLink: OriginalMessageLinkUiState = mutableUiState.value.originalMessageLink,
    ) {
        mutableUiState.value = VideoPlaybackUiState(
            phase = when {
                !sourceLoaded -> VideoFeedPhase.LOADING
                feedObservationFailure != null && feedSnapshot.keys.isEmpty() -> VideoFeedPhase.ERROR
                feedSnapshot.keys.isEmpty() -> VideoFeedPhase.EMPTY
                else -> VideoFeedPhase.CONTENT
            },
            feedKeys = feedSnapshot.keys,
            items = items,
            upcomingKeys = feedSnapshot.upcoming?.keys.orEmpty(),
            upcomingItems = if (criteria.value.order == VideoFeedOrder.RANDOM) {
                buildItemsFromKeys(
                    hydratedVideos.keys.filter(upcomingFeedKeySet::contains),
                    latestChannelTitles,
                )
            } else {
                emptyList()
            },
            feedGeneration = feedSnapshot.generation,
            hydrationGeneration = uiHydrationGeneration,
            order = criteria.value.order,
            queueGeneration = queueGeneration,
            randomRoundStartPagerPage = randomRoundStartPagerPage,
            currentPage = currentPage,
            player = playerSnapshot.toPresentationSnapshot(),
            originalPlaybackAwaitingConfirmation = pendingOriginalPlayback?.first,
            showSwipeHint = swipeHintVisible,
            originalMessageLink = originalMessageLink,
            feedFailure = feedObservationFailure,
        )
    }

    private fun maybeShowSwipeHint() {
        if (
            !swipeHintPreferenceLoaded ||
            swipeHintSeen ||
            swipeHintVisible ||
            swipeHintUserInteracted ||
            swipeHintHandledThisSession
        ) {
            return
        }
        val ready = playerSnapshot.playbackState as? VideoPlaybackState.Ready ?: return
        val currentVideo = currentItem()?.video ?: return
        if (
            !currentVideo.supportsStreaming ||
            ready.video.key != currentVideo.key ||
            !playerSnapshot.hasRenderedFirstFrame
        ) {
            return
        }
        swipeHintVisible = true
        rebuildUiState()
        swipeHintTimeoutJob?.cancel()
        swipeHintTimeoutJob = viewModelScope.launch {
            delay(SWIPE_HINT_DURATION_MILLIS)
            swipeHintTimeoutJob = null
            dismissSwipeHintIfVisible()
        }
    }

    private fun recordSwipeHintInteraction() {
        if (swipeHintUserInteracted) return
        swipeHintUserInteracted = true
        completeSwipeHintForSession()
    }

    private fun dismissSwipeHintIfVisible() {
        if (!swipeHintVisible) return
        completeSwipeHintForSession()
    }

    private fun completeSwipeHintForSession() {
        swipeHintTimeoutJob?.cancel()
        swipeHintTimeoutJob = null
        val wasVisible = swipeHintVisible
        swipeHintVisible = false
        swipeHintHandledThisSession = true
        if (wasVisible) rebuildUiState()
        if (swipeHintSeen || swipeHintMarkStarted) return
        swipeHintMarkStarted = true
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    onboardingPreferences.markSwipeHintSeen()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The current session remains handled even when persistence is unavailable.
            }
        }
    }

    private fun VideoPlayerSnapshot.toPresentationSnapshot(): VideoPlayerSnapshot = copy(
        positionMillis = 0L,
        durationMillis = 0L,
        bufferedPositionMillis = 0L,
        isSeekable = false,
    )

    private fun VideoPlayerSnapshot.toProgressUiState(): VideoPlaybackProgressUiState =
        VideoPlaybackProgressUiState(
            key = playbackState.videoKeyOrNull(),
            positionMillis = positionMillis,
            durationMillis = durationMillis,
            bufferedPositionMillis = bufferedPositionMillis,
            isSeekable = isSeekable,
        )

    private fun VideoPlaybackState.videoKeyOrNull(): VideoKey? = when (this) {
        VideoPlaybackState.Idle -> null
        is VideoPlaybackState.Loading -> video.key
        is VideoPlaybackState.Ready -> video.key
        is VideoPlaybackState.Unsupported -> video.key
        is VideoPlaybackState.Failed -> video.key
    }

    private data class FeedCriteria(
        val channelIds: Set<Long>? = null,
        val normalizedTags: Set<String> = emptySet(),
        val tagMode: TagFilterMode = TagFilterMode.OR,
        val order: VideoFeedOrder = DEFAULT_VIDEO_FEED_ORDER,
    )

    private data class FeedSource(
        val filter: VideoFilter,
        val order: VideoFeedOrder,
        val channelTitles: Map<Long, String>,
        val retryToken: Long,
    )

    private data class FeedSourceResult(
        val source: FeedSource,
        val rows: List<VideoFeedKeySnapshot>,
        val failure: RepositoryObservationFailure?,
    )

    private data class PlaybackRefreshResult(
        val video: com.qixuan.channelvideoflow.model.video.IndexedVideo?,
        val outcome: PlaybackPlanRefreshOutcome,
        val terminalFailure: VideoPlaybackFailure? = null,
    )

    private data class QualitySelection(
        val preference: VideoQualityPreference,
        val network: NetworkTransport,
        val networkGeneration: Long,
    )

    private data class PlaybackPlanToken(
        val qualitySelectionGeneration: Long,
        val selection: QualitySelection,
        val accountGeneration: Long,
        val queueGeneration: Long,
        val randomRoundGeneration: Long?,
    )

    private enum class PlaybackSelectionResult {
        ORIGINAL,
        SERVER_VARIANT,
    }

    /**
     * Session-only playback metadata. It intentionally contains no caption, tags, or TDLib object.
     */
    private data class PlaybackPlan(
        val key: VideoKey,
        val sourceFileId: Int,
        val sourceRemoteUniqueId: String,
        val sourceFileSize: Long?,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val durationSeconds: Int,
        val canBeSaved: Boolean,
        val playbackFileId: Int,
        val supportsStreaming: Boolean,
        val selectedAlternative: VideoPlaybackVariant?,
        val availableVariants: List<VideoPlaybackVariant>,
        val selectionResult: PlaybackSelectionResult,
        val token: PlaybackPlanToken,
        val refreshOutcome: PlaybackPlanRefreshOutcome,
        val refreshMillis: Long,
        val preparedAtMillis: Long,
        val terminalFailure: VideoPlaybackFailure?,
    ) {
        fun matches(expectedKey: VideoKey, expectedToken: PlaybackPlanToken): Boolean =
            key == expectedKey &&
                token == expectedToken &&
                playbackFileId == (selectedAlternative?.fileId ?: sourceFileId)

        fun isCompatibleWith(
            metadata: com.qixuan.channelvideoflow.model.video.IndexedVideo,
        ): Boolean =
            key == metadata.key &&
                sourceFileId == metadata.fileId &&
                sourceRemoteUniqueId == metadata.remoteUniqueId &&
                sourceFileSize == metadata.fileSize &&
                sourceWidth == metadata.width &&
                sourceHeight == metadata.height &&
                durationSeconds == metadata.durationSeconds &&
                canBeSaved == metadata.canBeSaved &&
                supportsStreaming == metadata.supportsStreaming &&
                (
                    selectedAlternative == null ||
                        selectedAlternative in metadata.alternativeVariants
                    )

        fun toVideo(base: com.qixuan.channelvideoflow.model.video.IndexedVideo):
            com.qixuan.channelvideoflow.model.video.IndexedVideo {
            require(base.key == key) { "PlaybackPlan key mismatch" }
            return base.copy(
                fileId = sourceFileId,
                remoteUniqueId = sourceRemoteUniqueId,
                supportsStreaming = supportsStreaming,
                fileSize = sourceFileSize,
                durationSeconds = durationSeconds,
                width = sourceWidth,
                height = sourceHeight,
                canBeSaved = canBeSaved,
                alternativeVariants = emptyList(),
                selectedAlternative = selectedAlternative,
            )
        }

        companion object {
            fun from(
                video: com.qixuan.channelvideoflow.model.video.IndexedVideo,
                token: PlaybackPlanToken,
                refreshOutcome: PlaybackPlanRefreshOutcome,
                refreshMillis: Long,
                preparedAtMillis: Long,
                terminalFailure: VideoPlaybackFailure? = null,
            ): PlaybackPlan = PlaybackPlan(
                key = video.key,
                sourceFileId = video.fileId,
                sourceRemoteUniqueId = video.remoteUniqueId,
                sourceFileSize = video.fileSize,
                sourceWidth = video.width,
                sourceHeight = video.height,
                durationSeconds = video.durationSeconds,
                canBeSaved = video.canBeSaved,
                playbackFileId = video.playbackFileId,
                supportsStreaming = video.supportsStreaming,
                selectedAlternative = video.selectedAlternative,
                availableVariants = video.alternativeVariants,
                selectionResult = if (video.selectedAlternative == null) {
                    PlaybackSelectionResult.ORIGINAL
                } else {
                    PlaybackSelectionResult.SERVER_VARIANT
                },
                token = token,
                refreshOutcome = refreshOutcome,
                refreshMillis = refreshMillis,
                preparedAtMillis = preparedAtMillis,
                terminalFailure = terminalFailure,
            )
        }
    }

    private data class PlaybackPlanSlots(
        val current: PlaybackPlan? = null,
        val next: PlaybackPlan? = null,
    )

    private data class PlanPreparation(
        val generation: Long,
        val key: VideoKey,
        val token: PlaybackPlanToken,
        val deferred: Deferred<PlaybackPlan?>,
    )

    private data class TransparentRecoveryKey(
        val videoKey: VideoKey,
        val stablePageGeneration: Long,
        val qualitySelectionGeneration: Long,
        val accountGeneration: Long,
        val queueGeneration: Long,
    )

    private data class TransparentRecoveryAttempt(
        val key: TransparentRecoveryKey,
        val token: PlaybackPlanToken,
        val failedSnapshot: VideoPlayerSnapshot,
        val failedPlaybackFileId: Int,
        val attempted: Boolean = true,
    )

    private data class QueueTarget(
        val video: com.qixuan.channelvideoflow.model.video.IndexedVideo,
        val randomEntry: PlaybackFeedRoundEntry?,
    ) {
        fun samePosition(other: QueueTarget): Boolean =
            video.key == other.video.key &&
                randomEntry?.roundGeneration == other.randomEntry?.roundGeneration &&
                randomEntry?.index == other.randomEntry?.index

        fun samePosition(other: PlaybackTargetIdentity): Boolean =
            video.key == other.key &&
                randomEntry?.roundGeneration == other.randomRoundGeneration &&
                randomEntry?.index == other.randomRoundIndex

        fun toIdentity(pagerPage: Int, logicalPage: Int) = PlaybackTargetIdentity(
            pagerPage = pagerPage,
            logicalPage = logicalPage,
            key = video.key,
            randomRoundGeneration = randomEntry?.roundGeneration,
            randomRoundIndex = randomEntry?.index,
        )
    }

    private data class QueueKeyTarget(
        val key: VideoKey,
        val logicalIndex: Int,
        val randomEntry: PlaybackFeedRoundEntry?,
    )

    private data class PlaybackTransitionContext(
        val order: VideoFeedOrder,
        val direction: PlaybackTransitionDirection,
        val randomRoundBoundary: Boolean,
    )

    private companion object {
        const val QUALITY_REFRESH_TIMEOUT_MILLIS = 3_000L
        const val SWIPE_HINT_DURATION_MILLIS = 2_000L
        const val FEED_HYDRATION_RADIUS = 3

        fun monotonicTimeMillis(): Long = System.nanoTime() / 1_000_000L
    }
}
