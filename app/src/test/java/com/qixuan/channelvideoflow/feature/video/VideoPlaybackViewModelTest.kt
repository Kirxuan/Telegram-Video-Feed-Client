package com.qixuan.channelvideoflow.feature.video

import androidx.media3.ui.PlayerView
import com.qixuan.channelvideoflow.domain.channel.TelegramChatRepository
import com.qixuan.channelvideoflow.domain.cache.MediaCacheController
import com.qixuan.channelvideoflow.domain.cache.MediaCacheState
import com.qixuan.channelvideoflow.domain.message.TelegramMessageRepository
import com.qixuan.channelvideoflow.domain.message.VideoReferenceFailure
import com.qixuan.channelvideoflow.domain.message.VideoReferenceResolution
import com.qixuan.channelvideoflow.domain.media.DevicePreloadPolicySource
import com.qixuan.channelvideoflow.domain.media.DevicePreloadSignals
import com.qixuan.channelvideoflow.domain.media.NetworkTransport
import com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffSnapshot
import com.qixuan.channelvideoflow.domain.media.PreloadOwnerHandoffPhase
import com.qixuan.channelvideoflow.domain.media.StreamingNetworkMetricsEstimator
import com.qixuan.channelvideoflow.domain.media.StreamingNetworkMetricsRepository
import com.qixuan.channelvideoflow.domain.media.VideoPreloadController
import com.qixuan.channelvideoflow.domain.message.RepositoryObservation
import com.qixuan.channelvideoflow.domain.message.RepositoryObservationFailure
import com.qixuan.channelvideoflow.domain.message.VideoFeedKeySnapshot
import com.qixuan.channelvideoflow.domain.video.PlaybackFeedSession
import com.qixuan.channelvideoflow.domain.video.VideoFeedOnboardingPreferences
import com.qixuan.channelvideoflow.domain.video.VideoQueueRandomSource
import com.qixuan.channelvideoflow.model.channel.TelegramChannel
import com.qixuan.channelvideoflow.model.channel.TelegramChatSyncState
import com.qixuan.channelvideoflow.model.video.ChannelVideoScanProgress
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.OriginalMessageLinkResult
import com.qixuan.channelvideoflow.model.video.TagSummary
import com.qixuan.channelvideoflow.model.video.VideoFeedOrder
import com.qixuan.channelvideoflow.model.video.VideoFilter
import com.qixuan.channelvideoflow.model.video.VideoKey
import com.qixuan.channelvideoflow.model.video.VideoPlaybackVariant
import com.qixuan.channelvideoflow.model.video.VideoQualityPreference
import com.qixuan.channelvideoflow.player.VideoPlaybackController
import com.qixuan.channelvideoflow.player.VideoPlaybackFailure
import com.qixuan.channelvideoflow.player.PlaybackPlanRefreshOutcome
import com.qixuan.channelvideoflow.player.PlaybackTransitionDirection
import com.qixuan.channelvideoflow.player.PlaybackTransitionEvent
import com.qixuan.channelvideoflow.player.VideoPlaybackState
import com.qixuan.channelvideoflow.player.VideoPlaybackSpeeds
import com.qixuan.channelvideoflow.player.VideoPlayerSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlaybackViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun swipeHintStaysHiddenUntilPreferencesFinishLoading() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preferences = FakeVideoFeedOnboardingPreferences()
        val viewModel = viewModel(controller, onboardingPreferences = preferences)
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitReadyFirstFrame()
        runCurrent()

        assertEquals(false, viewModel.uiState.value.showSwipeHint)
    }

    @Test
    fun pagerPointerDownBeforePreferencesLoadPermanentlyHandlesSwipeHintForThisSession() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val preferences = FakeVideoFeedOnboardingPreferences()
            val viewModel = viewModel(controller, onboardingPreferences = preferences)
            runCurrent()

            viewModel.onPagerPointerDown(observedAtMillis = 10L)
            runCurrent()
            preferences.emitSeen(false)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            controller.emitReadyFirstFrame()
            runCurrent()

            assertEquals(false, viewModel.uiState.value.showSwipeHint)
            assertEquals(1, preferences.markCalls)
        }

    @Test
    fun pagerPointerDownAfterUnseenPreferenceButBeforeFirstFrameHandlesSwipeHint() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val preferences = FakeVideoFeedOnboardingPreferences()
            val viewModel = viewModel(controller, onboardingPreferences = preferences)
            preferences.emitSeen(false)
            runCurrent()

            viewModel.onPagerPointerDown(observedAtMillis = 10L)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            controller.emitReadyFirstFrame()
            runCurrent()

            assertEquals(false, viewModel.uiState.value.showSwipeHint)
            assertEquals(1, preferences.markCalls)
        }

    @Test
    fun repeatedPagerPointerDownBeforeSwipeHintAppearsMarksItOnlyOnce() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preferences = FakeVideoFeedOnboardingPreferences()
        val viewModel = viewModel(controller, onboardingPreferences = preferences)
        preferences.emitSeen(false)
        runCurrent()

        viewModel.onPagerPointerDown(observedAtMillis = 10L)
        viewModel.onPagerPointerDown(observedAtMillis = 11L)
        viewModel.onPagerPointerReleased(observedAtMillis = 12L)
        viewModel.onPagerPointerDown(observedAtMillis = 13L)
        runCurrent()

        assertEquals(false, viewModel.uiState.value.showSwipeHint)
        assertEquals(1, preferences.markCalls)
    }

    @Test
    fun unseenSwipeHintAppearsAfterTheCurrentVideoRendersItsFirstFrame() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preferences = FakeVideoFeedOnboardingPreferences()
        val viewModel = viewModel(controller, onboardingPreferences = preferences)
        preferences.emitSeen(false)
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitReadyFirstFrame()
        runCurrent()

        assertEquals(true, viewModel.uiState.value.showSwipeHint)
    }

    @Test
    fun pagerPointerDownImmediatelyHidesSwipeHintAndMarksItOnce() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preferences = FakeVideoFeedOnboardingPreferences()
        val viewModel = viewModel(controller, onboardingPreferences = preferences)
        preferences.emitSeen(false)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitReadyFirstFrame()
        runCurrent()

        viewModel.onPagerPointerDown(observedAtMillis = 10L)
        runCurrent()
        viewModel.onPagerPointerDown(observedAtMillis = 11L)
        runCurrent()

        assertEquals(false, viewModel.uiState.value.showSwipeHint)
        assertEquals(1, preferences.markCalls)
    }

    @Test
    fun swipeHintAutomaticallyHidesAfterTwoSecondsAndMarksItOnce() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preferences = FakeVideoFeedOnboardingPreferences()
        val viewModel = viewModel(controller, onboardingPreferences = preferences)
        preferences.emitSeen(false)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitReadyFirstFrame()
        runCurrent()

        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(true, viewModel.uiState.value.showSwipeHint)

        advanceTimeBy(1L)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(false, viewModel.uiState.value.showSwipeHint)
        assertEquals(1, preferences.markCalls)
    }

    @Test
    fun failedSwipeHintWriteStaysHiddenThisSessionButCanAppearInANewSession() =
        runTest(dispatcher) {
            val preferences = FakeVideoFeedOnboardingPreferences(
                writeFailure = IllegalStateException("write unavailable"),
            )
            preferences.emitSeen(false)
            val firstController = FakeVideoPlaybackController()
            val firstViewModel = viewModel(
                firstController,
                onboardingPreferences = preferences,
            )
            runCurrent()
            firstViewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            firstController.emitReadyFirstFrame()
            runCurrent()

            firstViewModel.onPagerPointerDown(observedAtMillis = 10L)
            runCurrent()

            assertEquals(false, firstViewModel.uiState.value.showSwipeHint)
            assertEquals(1, preferences.markCalls)

            val newController = FakeVideoPlaybackController()
            val newViewModel = viewModel(newController, onboardingPreferences = preferences)
            runCurrent()
            newViewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            newController.emitReadyFirstFrame()
            runCurrent()

            assertEquals(true, newViewModel.uiState.value.showSwipeHint)
        }

    @Test
    fun seenSwipeHintNeverAppears() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preferences = FakeVideoFeedOnboardingPreferences()
        val viewModel = viewModel(controller, onboardingPreferences = preferences)
        preferences.emitSeen(true)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitReadyFirstFrame()
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(false, viewModel.uiState.value.showSwipeHint)
        assertEquals(0, preferences.markCalls)
    }

    @Test
    fun lateFirstFrameFromAnOldVideoCannotShowSwipeHintForTheCurrentPage() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val preferences = FakeVideoFeedOnboardingPreferences()
            val viewModel = viewModel(controller, onboardingPreferences = preferences)
            preferences.emitSeen(false)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            val oldVideo = controller.binds.last()

            viewModel.onPageUnstable()
            viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
            runCurrent()
            val currentVideo = controller.binds.last()

            controller.emitReadyFirstFrame(oldVideo)
            runCurrent()
            assertEquals(false, viewModel.uiState.value.showSwipeHint)

            controller.emitReadyFirstFrame(currentVideo)
            runCurrent()
            assertEquals(true, viewModel.uiState.value.showSwipeHint)
        }

    @Test
    fun leavingThePlaybackPageDoesNotLeaveSwipeHintBehind() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preferences = FakeVideoFeedOnboardingPreferences()
        val viewModel = viewModel(controller, onboardingPreferences = preferences)
        preferences.emitSeen(false)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitReadyFirstFrame()
        runCurrent()

        viewModel.releasePage()
        runCurrent()

        assertEquals(false, viewModel.uiState.value.showSwipeHint)
        assertEquals(1, preferences.markCalls)
    }

    @Test
    fun replayedPreferenceStateDoesNotCreateADuplicateSwipeHintTimer() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preferences = FakeVideoFeedOnboardingPreferences()
        val viewModel = viewModel(controller, onboardingPreferences = preferences)
        preferences.emitSeen(false)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitReadyFirstFrame()
        runCurrent()

        advanceTimeBy(1_000L)
        preferences.emitSeen(false)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(false, viewModel.uiState.value.showSwipeHint)
        assertEquals(1, preferences.markCalls)
    }

    @Test
    fun temporarySpeedActivatesOnlyForStableReadyRenderedAndActuallyPlayingCurrentVideo() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(controller)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            controller.temporarySpeedChanges.clear()

            viewModel.setTemporaryPlaybackSpeed(active = true)
            assertEquals(listOf(false), controller.temporarySpeedChanges)

            controller.emitReadyFirstFrame(isPlaying = false)
            runCurrent()
            controller.temporarySpeedChanges.clear()
            viewModel.setTemporaryPlaybackSpeed(active = true)
            assertEquals(listOf(false), controller.temporarySpeedChanges)

            controller.emitReadyFirstFrame(isPlaying = true)
            runCurrent()
            controller.temporarySpeedChanges.clear()
            viewModel.setTemporaryPlaybackSpeed(active = true)
            runCurrent()

            assertEquals(listOf(true), controller.temporarySpeedChanges)
            assertEquals(
                VideoPlaybackSpeeds.TEMPORARY_FAST_FORWARD,
                viewModel.uiState.value.player.playbackSpeed,
            )
        }

    @Test
    fun temporarySpeedRejectsPausedFailedUnsupportedAndUnstableStates() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        val current = controller.binds.last()
        controller.emitReadyFirstFrame(isPlaying = true)
        runCurrent()

        controller.pause()
        runCurrent()
        controller.temporarySpeedChanges.clear()
        viewModel.setTemporaryPlaybackSpeed(active = true)
        assertEquals(listOf(false), controller.temporarySpeedChanges)

        controller.emitFailure(VideoPlaybackFailure.NETWORK, current)
        runCurrent()
        controller.temporarySpeedChanges.clear()
        viewModel.setTemporaryPlaybackSpeed(active = true)
        assertEquals(listOf(false), controller.temporarySpeedChanges)

        controller.emitUnsupported(current)
        runCurrent()
        controller.temporarySpeedChanges.clear()
        viewModel.setTemporaryPlaybackSpeed(active = true)
        assertEquals(listOf(false), controller.temporarySpeedChanges)

        controller.emitReadyFirstFrame(current, isPlaying = true)
        runCurrent()
        viewModel.onPageUnstable()
        controller.temporarySpeedChanges.clear()
        viewModel.setTemporaryPlaybackSpeed(active = true)
        assertEquals(listOf(false), controller.temporarySpeedChanges)
    }

    @Test
    fun inactiveIntentAndEveryPageLifecycleBoundaryAlwaysRequestSafeReset() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller)
        runCurrent()
        controller.temporarySpeedChanges.clear()

        viewModel.setTemporaryPlaybackSpeed(active = false)
        viewModel.onPageUnstable()
        viewModel.onForegroundChanged(isForeground = false)
        viewModel.releasePage()
        runCurrent()

        assertEquals(listOf(false, false, false, false), controller.temporarySpeedChanges)
    }

    @Test
    fun pausingAnActiveTemporarySpeedResetsBeforePausing() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitReadyFirstFrame(isPlaying = true)
        runCurrent()
        viewModel.setTemporaryPlaybackSpeed(active = true)
        runCurrent()

        controller.events.clear()
        viewModel.togglePause()

        assertEquals(listOf("speed:false", "pause"), controller.events)
        assertEquals(VideoPlaybackSpeeds.NORMAL, controller.snapshot.value.playbackSpeed)
    }

    @Test
    fun fastPageChangeAndLateOldKeyCannotCarryTemporarySpeedIntoTheNewVideo() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(controller)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            val oldVideo = controller.binds.last()
            controller.emitReadyFirstFrame(oldVideo, isPlaying = true)
            runCurrent()
            viewModel.setTemporaryPlaybackSpeed(active = true)
            runCurrent()

            viewModel.onPageUnstable()
            viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
            runCurrent()
            val currentVideo = controller.binds.last()
            assertTrue(currentVideo.key != oldVideo.key)
            assertEquals(VideoPlaybackSpeeds.NORMAL, controller.snapshot.value.playbackSpeed)

            controller.emitReadyFirstFrame(oldVideo, isPlaying = true)
            runCurrent()
            controller.temporarySpeedChanges.clear()
            viewModel.setTemporaryPlaybackSpeed(active = true)
            assertEquals(listOf(false), controller.temporarySpeedChanges)

            controller.emitReadyFirstFrame(currentVideo, isPlaying = true)
            runCurrent()
            controller.temporarySpeedChanges.clear()
            viewModel.setTemporaryPlaybackSpeed(active = true)
            assertEquals(listOf(true), controller.temporarySpeedChanges)
        }

    @Test
    fun newPlaybackSessionPublishesRandomOrderBeforeRepositoryEmission() = runTest(dispatcher) {
        val viewModel = viewModel(
            FakeVideoPlaybackController(),
            initialOrder = null,
        )

        assertEquals(VideoFeedOrder.RANDOM, viewModel.uiState.value.order)
        assertEquals(VideoFeedPhase.LOADING, viewModel.uiState.value.phase)

        runCurrent()

        assertEquals(VideoFeedOrder.RANDOM, viewModel.uiState.value.order)
        assertEquals(VideoFeedPhase.CONTENT, viewModel.uiState.value.phase)
    }

    @Test
    fun currentSessionLatestOrderSurvivesRoomFlowUpdates() = runTest(dispatcher) {
        val repository = FakeMessageRepository(
            listOf(
                video(messageId = 1, publishTime = 1),
                video(messageId = 2, publishTime = 2),
            ),
        )
        val viewModel = viewModel(
            FakeVideoPlaybackController(),
            repository,
            initialOrder = null,
        )
        runCurrent()

        viewModel.setOrder(VideoFeedOrder.LATEST)
        runCurrent()
        repository.emitVideos(
            listOf(
                video(messageId = 1, publishTime = 1),
                video(messageId = 2, publishTime = 2),
                video(messageId = 3, publishTime = 3),
            ),
        )
        runCurrent()

        assertEquals(VideoFeedOrder.LATEST, viewModel.uiState.value.order)
        assertEquals(
            listOf(3L, 2L, 1L),
            viewModel.uiState.value.items.map { it.video.key.messageId },
        )
    }

    @Test
    fun productionFeedObservesAllKeysButHydratesOnlyBoundedWindows() = runTest(dispatcher) {
        val repository = FakeMessageRepository(
            (1L..100L).map { messageId -> video(messageId, messageId) },
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller, repository, initialOrder = VideoFeedOrder.LATEST)

        runCurrent()

        assertEquals(100, viewModel.uiState.value.feedKeys.size)
        assertTrue(viewModel.uiState.value.items.size <= 7)
        assertTrue(repository.hydrationRequests.single().size <= 7)

        viewModel.onPageSettled(pagerPage = 50, logicalPage = 50)
        runCurrent()

        assertEquals(VideoKey(1, 50), controller.binds.last().key)
        assertTrue(repository.hydrationRequests.last().size <= 7)
        assertTrue(viewModel.uiState.value.items.size <= 8)
    }

    @Test
    fun lateHydrationFromReplacedFilterCannotRestoreOldFeed() = runTest(dispatcher) {
        val oldHydration = CompletableDeferred<Unit>()
        val repository = FakeMessageRepository(
            initialVideos = listOf(video(1, 1)),
            hydrationGates = ArrayDeque(listOf(oldHydration)),
            ignoreHydrationCancellation = true,
        )
        val viewModel = viewModel(
            FakeVideoPlaybackController(),
            repository,
            initialOrder = VideoFeedOrder.LATEST,
        )
        runCurrent()

        viewModel.setFilter(VideoFilter(channelIds = emptySet()))
        runCurrent()
        oldHydration.complete(Unit)
        runCurrent()

        assertEquals(VideoFeedPhase.EMPTY, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.feedKeys.isEmpty())
        assertTrue(viewModel.uiState.value.items.isEmpty())
    }

    @Test
    fun retryActuallyResubscribesAfterKeyObservationFailure() = runTest(dispatcher) {
        val repository = FakeMessageRepository(
            initialVideos = listOf(video(1, 1)),
            keyObservationFailuresBeforeSuccess = 1,
        )
        val viewModel = viewModel(
            FakeVideoPlaybackController(),
            repository,
            initialOrder = VideoFeedOrder.LATEST,
        )
        runCurrent()
        assertEquals(VideoFeedPhase.ERROR, viewModel.uiState.value.phase)

        viewModel.retryFeedObservation()
        runCurrent()

        assertEquals(2, repository.keyObservationSubscriptions)
        assertEquals(VideoFeedPhase.CONTENT, viewModel.uiState.value.phase)
        assertEquals(listOf(VideoKey(1, 1)), viewModel.uiState.value.feedKeys)
    }

    @Test
    fun newPlaybackSessionReturnsToRandomAfterPreviousSessionSelectedLatest() =
        runTest(dispatcher) {
            val firstSession = viewModel(
                FakeVideoPlaybackController(),
                initialOrder = null,
            )
            runCurrent()
            firstSession.setOrder(VideoFeedOrder.LATEST)
            runCurrent()

            val secondSession = viewModel(
                FakeVideoPlaybackController(),
                initialOrder = null,
            )

            assertEquals(VideoFeedOrder.LATEST, firstSession.uiState.value.order)
            assertEquals(VideoFeedOrder.RANDOM, secondSession.uiState.value.order)
        }

    @Test
    fun emptyFilterKeepsRandomOrderAndDoesNotCreatePlaybackRequest() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller,
            initialOrder = null,
        )
        runCurrent()

        viewModel.setFilter(VideoFilter(channelIds = emptySet()))
        runCurrent()

        assertEquals(VideoFeedOrder.RANDOM, viewModel.uiState.value.order)
        assertEquals(VideoFeedPhase.EMPTY, viewModel.uiState.value.phase)
        assertTrue(controller.binds.isEmpty())
    }

    @Test
    fun randomTransitionsReportDirectionAndRoundBoundaryWithoutChangingQueueRules() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(
                controller,
                initialOrder = null,
            )
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()

            viewModel.onPageUnstable()
            viewModel.onPageSettled(pagerPage = 3, logicalPage = 0)
            runCurrent()

            val settled = controller.transitionEvents
                .filterIsInstance<PlaybackTransitionEvent.PageSettled>()
                .last()
            assertEquals(VideoFeedOrder.RANDOM, settled.order)
            assertEquals(PlaybackTransitionDirection.FORWARD, settled.direction)
            assertEquals(true, settled.randomRoundBoundary)
        }

    @Test
    fun randomBoundaryPreloadsAndBindsTheSamePregeneratedUpcomingFirstItem() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val preloader = FakeVideoPreloadController()
            val queue = PlaybackFeedSession(FixedVideoQueueRandom(0, 0, 1, 0, 0, 0, 0, 0))
            val repository = FakeMessageRepository(
                (1L..3L).map { messageId -> video(messageId, messageId) },
            )
            val viewModel = viewModel(
                controller = controller,
                repository = repository,
                preloader = preloader,
                initialOrder = null,
                feedSession = queue,
            )
            runCurrent()
            val currentItems = viewModel.uiState.value.items
            currentItems.indices.forEach { page ->
                viewModel.onPageSettled(pagerPage = page, logicalPage = page)
                runCurrent()
                controller.emitFirstFrame()
                runCurrent()
            }
            val upcomingFirst = requireNotNull(queue.current().upcoming).keys.first()

            assertEquals(upcomingFirst, preloader.targets.mapNotNull { it }.last().key)
            val refreshCountBeforePromotion =
                repository.refreshedKeys.count { it == upcomingFirst }

            viewModel.onPageUnstable()
            viewModel.onPageTargeted(pagerPage = currentItems.size, logicalPage = 0)
            runCurrent()
            viewModel.onPageSettled(pagerPage = currentItems.size, logicalPage = 0)
            runCurrent()

            assertEquals(upcomingFirst, controller.binds.last().key)
            assertEquals(
                upcomingFirst,
                queue.current().keys.firstOrNull(),
            )
            assertEquals(
                refreshCountBeforePromotion,
                repository.refreshedKeys.count { it == upcomingFirst },
            )
            assertEquals(
                true,
                controller.transitionEvents
                    .filterIsInstance<PlaybackTransitionEvent.PlanStarted>()
                    .last()
                    .promoted,
            )
        }

    @Test
    fun randomCommittedTargetReplacesTheSingleForwardCandidateAfterMidpoint() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val preloader = FakeVideoPreloadController()
            val queue = PlaybackFeedSession(FixedVideoQueueRandom(0, 0, 0, 1, 0, 0))
            val viewModel = viewModel(
                controller = controller,
                preloader = preloader,
                initialOrder = null,
                feedSession = queue,
            )
            runCurrent()
            val current = viewModel.uiState.value.items
            viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
            runCurrent()
            controller.emitFirstFrame()
            runCurrent()
            assertEquals(current[2].video.key, preloader.targets.mapNotNull { it }.last().key)
            val bindsBeforeGesture = controller.binds.size

            viewModel.onPagerPointerDown(100L)
            viewModel.onPageUnstable()
            viewModel.onPageTargeted(pagerPage = 0, logicalPage = 0)
            runCurrent()

            assertEquals(current[0].video.key, preloader.targets.mapNotNull { it }.last().key)
            assertEquals(
                listOf(current[2].video.key, null, current[0].video.key),
                preloader.targets.takeLast(3).map { it?.key },
            )
            assertEquals(1, if (preloader.ownerHandoff.value.hasSpeculativeOwner) 1 else 0)
            assertEquals(bindsBeforeGesture, controller.binds.size)

            viewModel.onPageTargeted(pagerPage = 2, logicalPage = 2)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 2, logicalPage = 2)
            runCurrent()

            assertEquals(current[2].video.key, preloader.targets.mapNotNull { it }.last().key)
            assertEquals(current[2].video.key, controller.binds.last().key)
        }

    @Test
    fun randomRoomRefreshKeepsRoundKeysAndReplacesCurrentAndUpcomingMetadata() =
        runTest(dispatcher) {
            val original = (1L..3L).map { messageId ->
                video(messageId = messageId, publishTime = messageId, fileId = messageId.toInt())
            }
            val repository = FakeMessageRepository(original)
            val queue = PlaybackFeedSession(FixedVideoQueueRandom(0, 0, 1, 0))
            val viewModel = viewModel(
                controller = FakeVideoPlaybackController(),
                repository = repository,
                initialOrder = null,
                feedSession = queue,
            )
            runCurrent()
            val currentKeys = viewModel.uiState.value.items.map { it.video.key }
            val upcomingKeys = viewModel.uiState.value.upcomingItems.map { it.video.key }

            repository.emitVideos(original.reversed().map { it.copy(fileId = it.fileId + 100) })
            runCurrent()

            assertEquals(currentKeys, viewModel.uiState.value.items.map { it.video.key })
            assertEquals(upcomingKeys, viewModel.uiState.value.upcomingItems.map { it.video.key })
            assertEquals(
                currentKeys.map { it.messageId.toInt() + 100 },
                viewModel.uiState.value.items.map { it.video.fileId },
            )
            assertEquals(
                upcomingKeys.map { it.messageId.toInt() + 100 },
                viewModel.uiState.value.upcomingItems.map { it.video.fileId },
            )
        }

    @Test
    fun deletingUpcomingVideoImmediatelyReplacesItsPlanAndSpeculativeTarget() =
        runTest(dispatcher) {
            val original = (1L..3L).map { messageId ->
                video(messageId = messageId, publishTime = messageId)
            }
            val repository = FakeMessageRepository(original)
            val preloader = FakeVideoPreloadController()
            val controller = FakeVideoPlaybackController()
            val queue = PlaybackFeedSession(FixedVideoQueueRandom(0, 0, 1, 0, 0, 0))
            val viewModel = viewModel(
                controller = controller,
                repository = repository,
                preloader = preloader,
                initialOrder = null,
                feedSession = queue,
            )
            runCurrent()
            val currentLastIndex = viewModel.uiState.value.items.lastIndex
            viewModel.onPageSettled(currentLastIndex, currentLastIndex)
            runCurrent()
            controller.emitFirstFrame()
            runCurrent()
            val removedKey = requireNotNull(queue.current().upcoming).keys.first()
            assertEquals(removedKey, preloader.targets.mapNotNull { it }.last().key)
            val stopsBeforeDeletion = preloader.stopCalls

            repository.emitVideos(original.filterNot { it.key == removedKey })
            runCurrent()

            assertTrue(preloader.stopCalls > stopsBeforeDeletion)
            assertTrue(preloader.targets.mapNotNull { it }.last().key != removedKey)
        }

    @Test
    fun staleUpcomingRoundRefreshCannotBindAfterFilterInvalidatesBothRounds() =
        runTest(dispatcher) {
            val queue = PlaybackFeedSession(FixedVideoQueueRandom(0, 0, 1, 0))
            val upcomingGate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(
                initialVideos = (1L..3L).map { messageId -> video(messageId, messageId) },
                refreshGates = mapOf(VideoKey(1, 3) to upcomingGate),
            )
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(
                controller = controller,
                repository = repository,
                initialOrder = null,
                feedSession = queue,
            )
            runCurrent()
            val lastIndex = viewModel.uiState.value.items.lastIndex
            viewModel.onPageSettled(pagerPage = lastIndex, logicalPage = lastIndex)
            runCurrent()
            controller.emitFirstFrame()
            runCurrent()
            val bindsBeforeInvalidation = controller.binds.size

            viewModel.setFilter(VideoFilter(channelIds = emptySet()))
            runCurrent()
            upcomingGate.complete(Unit)
            runCurrent()

            assertEquals(VideoFeedPhase.EMPTY, viewModel.uiState.value.phase)
            assertEquals(bindsBeforeInvalidation, controller.binds.size)
        }

    @Test
    fun onlyFinalStablePageBindsAndTransitionPausesOldAudio() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val viewModel = viewModel(controller, preloader = preloader)
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        viewModel.onPageUnstable()
        viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
        runCurrent()
        controller.emitFirstFrame()
        runCurrent()

        assertEquals(listOf(VideoKey(1, 2)), controller.binds.map { it.key })
        // The bounded byte stage can already have registered the target of the superseded page;
        // what this case is about is the final next target of the actually bound video.
        assertEquals(VideoKey(1, 1), preloader.targets.mapNotNull { it?.key }.last())
        assertTrue(controller.transitionPauses >= 1)
    }

    @Test
    fun settledPageStartsPlaybackWithoutFixedDelay() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller)
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertEquals(listOf(VideoKey(1, 3)), controller.binds.map { it.key })
    }

    @Test
    fun preparedTargetPlanIsPromotedWithoutRefreshingTheSameVideoTwice() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 2, fileId = 101)
        val second = video(messageId = 2, publishTime = 1, fileId = 102)
        val repository = FakeMessageRepository(
            initialVideos = listOf(first, second),
            refreshedVideos = mapOf(
                first.key to first.copy(fileId = 201),
                second.key to second.copy(fileId = 202),
            ),
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        viewModel.onPageUnstable()
        viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
        runCurrent()

        assertEquals(1, repository.refreshedKeys.count { it == second.key })
        assertEquals(listOf(201, 202), controller.binds.map(IndexedVideo::playbackFileId))
        val promoted = controller.transitionEvents
            .filterIsInstance<PlaybackTransitionEvent.PlanStarted>()
            .single { it.promoted }
        assertEquals(PlaybackPlanRefreshOutcome.SUCCESS, promoted.preparedRefreshOutcome)
        assertTrue(promoted.preparedRefreshMillis != null)
    }

    @Test
    fun sameKeyRoomWriteFromTargetRefreshKeepsThePreparedPlanSingleFlight() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 2, fileId = 101)
        val second = video(messageId = 2, publishTime = 1, fileId = 102)
        val refreshedSecond = second.copy(fileId = 202)
        val repository = FakeMessageRepository(
            initialVideos = listOf(first, second),
            refreshedVideos = mapOf(second.key to refreshedSecond),
            emitRefreshResultsToVideos = true,
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        viewModel.onPageUnstable()
        viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
        runCurrent()

        assertEquals(1, repository.refreshedKeys.count { it == second.key })
        assertEquals(202, controller.binds.last().playbackFileId)
    }

    @Test
    fun boundedNextBytePrefixIsRegisteredWhileTheCurrentItemStillHasNoFirstFrame() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val viewModel = viewModel(controller, preloader = preloader)
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        // The decoder-free byte stage only needs the single next target, so its bounded 256 KiB
        // TTFB prefix must not wait for the current item's first frame. No standby engine is
        // created here, so the current startup reserve keeps the link.
        assertEquals(listOf(VideoKey(1, 2)), preloader.targets.mapNotNull { it?.key })
        assertTrue(controller.standbyPreparations.isEmpty())
    }

    @Test
    fun heavyPoolStandbyStillWaitsForTheFirstFrameBeforeTakingTheNextTarget() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController(standbySupported = true)
        val preloader = FakeVideoPreloadController()
        val viewModel = viewModel(controller, preloader = preloader)
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertEquals(listOf(VideoKey(1, 2)), preloader.targets.mapNotNull { it?.key })
        assertTrue(
            "the heavy standby must not start before the current item renders a frame",
            controller.standbyPreparations.isEmpty(),
        )

        controller.emitFirstFrame()
        runCurrent()

        assertEquals(listOf(VideoKey(1, 2)), controller.standbyPreparations.map { it.key })
    }

    @Test
    fun nextNetworkPreloadResumesAfterCurrentItemRendersFirstFrame() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val viewModel = viewModel(controller, preloader = preloader)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        controller.emitFirstFrame()
        runCurrent()

        assertEquals(listOf(VideoKey(1, 2)), preloader.targets.mapNotNull { it?.key })
    }

    @Test
    fun matchingNextOwnerIsNotStoppedWhenThePageFirstBecomesUnstable() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val viewModel = viewModel(controller, preloader = preloader)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitFirstFrame()
        runCurrent()
        val stopsBeforeGesture = preloader.stopCalls

        viewModel.onPageUnstable()

        assertEquals(stopsBeforeGesture, preloader.stopCalls)
    }

    @Test
    fun matchingPreparedTargetIsCommittedAndStartsCurrentWithoutAnIntermediateStop() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val preloader = FakeVideoPreloadController()
            val viewModel = viewModel(controller, preloader = preloader)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            controller.emitFirstFrame()
            runCurrent()
            val stopsBeforeGesture = preloader.stopCalls

            viewModel.onPageUnstable()
            viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
            viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
            runCurrent()

            assertEquals(0, preloader.beginPromotionCalls)
            assertEquals(VideoKey(1, 2), preloader.committedTargets.last().key)
            assertEquals(VideoKey(1, 2), preloader.currentStarting.last().key)
            assertEquals(stopsBeforeGesture, preloader.stopCalls)
            assertEquals(VideoKey(1, 2), controller.binds.last().key)
        }

    @Test
    fun reverseBounceAbandonsTheTargetAndDoesNotBindTheWrongPage() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val viewModel = viewModel(controller, preloader = preloader)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitFirstFrame()
        runCurrent()
        val bindsBeforeGesture = controller.binds.size
        val initialKey = (controller.snapshot.value.playbackState as VideoPlaybackState.Loading).video.key

        viewModel.onPagerPointerDown(observedAtMillis = 100L)
        viewModel.onPageUnstable()
        viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
        viewModel.onPageTargeted(pagerPage = 0, logicalPage = 0)
        viewModel.onPagerPointerReleased(observedAtMillis = 200L)
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertTrue(preloader.abandonPromotionCalls >= 1)
        assertEquals(bindsBeforeGesture, controller.binds.size)
        assertEquals(
            initialKey,
            (controller.snapshot.value.playbackState as VideoPlaybackState.Loading).video.key,
        )
    }

    @Test
    fun userSeekImmediatelyStopsTheNextNetworkPreload() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val viewModel = viewModel(controller, preloader = preloader)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitFirstFrame()
        runCurrent()
        val stopsBeforeSeek = preloader.stopCalls
        val targetsBeforeSeek = preloader.targets.size

        viewModel.seekTo(1_000L)

        assertEquals(stopsBeforeSeek + 1, preloader.stopCalls)
        assertEquals(listOf(1_000L), controller.seekCalls)
        runCurrent()
        controller.emitFirstFrame()
        runCurrent()
        assertEquals(targetsBeforeSeek + 1, preloader.targets.size)
    }

    @Test
    fun preparedPlanForAnotherVideoCannotBePromoted() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val repository = FakeMessageRepository(
            listOf(
                video(messageId = 1, publishTime = 1),
                video(messageId = 2, publishTime = 2),
                video(messageId = 3, publishTime = 3),
            ),
        )
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        viewModel.onPageUnstable()
        viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
        runCurrent()
        viewModel.onPageTargeted(pagerPage = 2, logicalPage = 2)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 2, logicalPage = 2)
        runCurrent()

        assertEquals(listOf(VideoKey(1, 3), VideoKey(1, 1)), controller.binds.map { it.key })
    }

    @Test
    fun qualityPreferenceChangeInvalidatesPreparedPlan() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 2, fileId = 101)
        val second = video(messageId = 2, publishTime = 1, fileId = 102)
        val cache = FakeMediaCacheController()
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = FakeMessageRepository(
                initialVideos = listOf(first, second),
                refreshedVideos = mapOf(
                    first.key to first.withAlternative(fileId = 201),
                    second.key to second.withAlternative(fileId = 202),
                ),
            ),
            cacheController = cache,
        )
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        cache.setVideoQualityPreference(VideoQualityPreference.ORIGINAL)
        runCurrent()
        viewModel.onPageUnstable()
        viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
        runCurrent()

        assertEquals(102, controller.binds.last().playbackFileId)
    }

    @Test
    fun queueRebuildInvalidatesPreparedPlan() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 2, fileId = 101)
        val second = video(messageId = 2, publishTime = 1, fileId = 102)
        val repository = FakeMessageRepository(
            initialVideos = listOf(first, second),
            refreshedVideos = mapOf(
                first.key to first.copy(fileId = 201),
                second.key to second.copy(fileId = 202),
            ),
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        repository.setRefreshed(second.copy(fileId = 302))

        viewModel.setFilter(
            VideoFilter(
                channelIds = setOf(1),
                normalizedTags = setOf("stage12b"),
            ),
        )
        runCurrent()
        viewModel.onPageUnstable()
        viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
        runCurrent()

        assertEquals(302, controller.binds.last().playbackFileId)
    }

    @Test
    fun accountReleaseInvalidatesPreparedPlan() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 2, fileId = 101)
        val second = video(messageId = 2, publishTime = 1, fileId = 102)
        val repository = FakeMessageRepository(
            initialVideos = listOf(first, second),
            refreshedVideos = mapOf(
                first.key to first.copy(fileId = 201),
                second.key to second.copy(fileId = 202),
            ),
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        viewModel.releasePage()
        repository.setRefreshed(second.copy(fileId = 302))
        viewModel.onPageUnstable()
        viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
        runCurrent()

        assertEquals(302, controller.binds.last().playbackFileId)
    }

    @Test
    fun rapidTargetChangesCancelOldPreparationAndNeverBindItsVideo() = runTest(dispatcher) {
        val videos = listOf(
            video(messageId = 1, publishTime = 1),
            video(messageId = 2, publishTime = 2),
            video(messageId = 3, publishTime = 3),
        )
        val oldTargetGate = CompletableDeferred<Unit>()
        val repository = FakeMessageRepository(
            initialVideos = videos,
            refreshGates = mapOf(videos[1].key to oldTargetGate),
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller, repository)
        runCurrent()

        viewModel.onPageUnstable()
        viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
        runCurrent()
        viewModel.onPageTargeted(pagerPage = 2, logicalPage = 2)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 2, logicalPage = 2)
        runCurrent()
        oldTargetGate.complete(Unit)
        runCurrent()

        assertEquals(listOf(VideoKey(1, 1)), controller.binds.map { it.key })
    }

    @Test
    fun repeatedPagerCallbacksAreIdempotent() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller)
        runCurrent()

        viewModel.onPageUnstable()
        viewModel.onPageUnstable()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertEquals(1, controller.transitionEvents.count { it == PlaybackTransitionEvent.PageUnstable })
        assertEquals(1, controller.binds.size)
    }

    @Test
    fun pointerReleaseAndReliableTargetAreForwardedAsSeparateTransitionBoundaries() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(controller)
            runCurrent()

            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            viewModel.onPagerPointerDown(observedAtMillis = 100L)
            viewModel.onPageUnstable()
            viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
            viewModel.onPagerPointerReleased(observedAtMillis = 250L)
            viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
            runCurrent()

            assertTrue(
                controller.transitionEvents.contains(
                    PlaybackTransitionEvent.GestureStarted(observedAtMillis = 100L),
                ),
            )
            assertTrue(
                controller.transitionEvents.contains(
                    PlaybackTransitionEvent.TargetKnown(
                        key = VideoKey(1, 2),
                        order = VideoFeedOrder.LATEST,
                        direction = PlaybackTransitionDirection.FORWARD,
                        randomRoundBoundary = false,
                    ),
                ),
            )
            assertTrue(
                controller.transitionEvents.contains(
                    PlaybackTransitionEvent.GestureReleased(observedAtMillis = 250L),
                ),
            )
        }

    @Test
    fun transientOldPageSettlementDuringFlingDoesNotSplitReliableGesture() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(controller)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            controller.transitionEvents.clear()

            viewModel.onPagerPointerDown(observedAtMillis = 100L)
            viewModel.onPageUnstable()
            viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
            viewModel.onPagerPointerReleased(observedAtMillis = 250L)

            // Compose Pager can briefly move targetPage back to the old page during
            // the release/fling hand-off before restoring the actual target.
            viewModel.onPageTargeted(pagerPage = 0, logicalPage = 0)
            viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
            viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
            runCurrent()

            assertEquals(
                1,
                controller.transitionEvents.count {
                    it is PlaybackTransitionEvent.GestureStarted
                },
            )
            assertEquals(
                listOf(
                    PlaybackTransitionEvent.PageSettled(
                        key = VideoKey(1, 2),
                        order = VideoFeedOrder.LATEST,
                        direction = PlaybackTransitionDirection.FORWARD,
                        randomRoundBoundary = false,
                    ),
                ),
                controller.transitionEvents.filterIsInstance<PlaybackTransitionEvent.PageSettled>(),
            )
            assertTrue(
                controller.transitionEvents.none { it == PlaybackTransitionEvent.PageUnstable },
            )
            assertTrue(
                controller.transitionEvents.none {
                    it == PlaybackTransitionEvent.TargetAbandoned
                },
            )
            assertEquals(
                listOf(VideoKey(1, 3), VideoKey(1, 2)),
                controller.binds.map { it.key },
            )
        }

    @Test
    fun settledCurrentPageAfterReleaseAuthoritativelyAbandonsPreparedTarget() =
        runTest(dispatcher) {
            val first = video(messageId = 1, publishTime = 2)
            val second = video(messageId = 2, publishTime = 1)
            val gate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(
                initialVideos = listOf(first, second),
                refreshGates = mapOf(second.key to gate),
            )
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(controller, repository)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()

            viewModel.onPagerPointerDown(observedAtMillis = 100L)
            viewModel.onPageUnstable()
            viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
            runCurrent()
            viewModel.onPagerPointerReleased(observedAtMillis = 250L)
            viewModel.onPageTargeted(pagerPage = 0, logicalPage = 0)
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf(first.key), controller.binds.map { it.key })
            assertTrue(
                controller.transitionEvents.contains(PlaybackTransitionEvent.TargetAbandoned),
            )
        }

    @Test
    fun newPointerGestureDuringFlingStartsNewGenerationAndCancelsOldTarget() =
        runTest(dispatcher) {
            val first = video(messageId = 1, publishTime = 2)
            val second = video(messageId = 2, publishTime = 1)
            val gate = CompletableDeferred<Unit>()
            val repository = FakeMessageRepository(
                initialVideos = listOf(first, second),
                refreshGates = mapOf(second.key to gate),
            )
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(controller, repository)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()

            viewModel.onPagerPointerDown(observedAtMillis = 100L)
            viewModel.onPageUnstable()
            viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
            runCurrent()
            viewModel.onPagerPointerReleased(observedAtMillis = 250L)
            viewModel.onPagerPointerDown(observedAtMillis = 300L)
            viewModel.onPageTargeted(pagerPage = 0, logicalPage = 0)
            viewModel.onPagerPointerReleased(observedAtMillis = 450L)
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            gate.complete(Unit)
            runCurrent()

            assertEquals(
                2,
                controller.transitionEvents.count {
                    it is PlaybackTransitionEvent.GestureStarted
                },
            )
            assertEquals(listOf(first.key), controller.binds.map { it.key })
        }

    @Test
    fun dragWithoutPageCrossingDoesNotPrepareOrBindAgain() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 2)
        val second = video(messageId = 2, publishTime = 1)
        val repository = FakeMessageRepository(listOf(first, second))
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        val refreshesBeforeDrag = repository.refreshedKeys.size

        viewModel.onPagerPointerDown(observedAtMillis = 1_000L)
        viewModel.onPageUnstable()
        viewModel.onPageTargeted(pagerPage = 0, logicalPage = 0)
        viewModel.onPagerPointerReleased(observedAtMillis = 1_200L)
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertEquals(listOf(first.key), controller.binds.map { it.key })
        assertEquals(refreshesBeforeDrag, repository.refreshedKeys.size)
    }

    @Test
    fun reversingToCurrentPageCancelsOldTargetAndNeverBindsIt() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 2)
        val second = video(messageId = 2, publishTime = 1)
        val oldTargetGate = CompletableDeferred<Unit>()
        val repository = FakeMessageRepository(
            initialVideos = listOf(first, second),
            refreshGates = mapOf(second.key to oldTargetGate),
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        viewModel.onPagerPointerDown(observedAtMillis = 2_000L)
        viewModel.onPageUnstable()
        viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
        runCurrent()
        viewModel.onPageTargeted(pagerPage = 0, logicalPage = 0)
        viewModel.onPagerPointerReleased(observedAtMillis = 2_200L)
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        oldTargetGate.complete(Unit)
        runCurrent()

        assertEquals(listOf(first.key), controller.binds.map { it.key })
    }

    @Test
    fun positionTickerUpdatesLeafProgressWithoutRebuildingStructuralUiState() =
        runTest(dispatcher) {
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(controller)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            val structuralState = viewModel.uiState.value
            val key = controller.binds.single().key

            controller.emitProgress(
                positionMillis = 12_345L,
                durationMillis = 60_000L,
                bufferedPositionMillis = 30_000L,
            )
            runCurrent()

            assertSame(structuralState, viewModel.uiState.value)
            assertEquals(key, viewModel.playbackProgress.value.key)
            assertEquals(12_345L, viewModel.playbackProgress.value.positionMillis)
            assertEquals(60_000L, viewModel.playbackProgress.value.durationMillis)
        }

    @Test
    fun refreshFailureFallsBackToIndexedOriginal() = runTest(dispatcher) {
        val current = video(messageId = 1, publishTime = 1, fileId = 101)
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = FakeMessageRepository(
                initialVideos = listOf(current),
                refreshFailures = mapOf(current.key to IllegalStateException("sanitized failure")),
            ),
        )
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertEquals(listOf(101), controller.binds.map(IndexedVideo::playbackFileId))
    }

    @Test(expected = FatalRefreshError::class)
    fun fatalRefreshErrorIsNotSilentlyConvertedToOriginalFallback() = runTest(dispatcher) {
        val current = video(messageId = 1, publishTime = 1, fileId = 101)
        val fatal = FatalRefreshError()
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = FakeMessageRepository(
                initialVideos = listOf(current),
                refreshFailures = mapOf(current.key to fatal),
            ),
        )
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        assertTrue(controller.binds.isEmpty())
    }

    @Test
    fun unsupportedStreamingNeverStartsPlayableBindingOrNextPreload() = runTest(dispatcher) {
        val unsupported = video(messageId = 1, publishTime = 1).copy(supportsStreaming = false)
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val viewModel = viewModel(
            controller = controller,
            repository = FakeMessageRepository(listOf(unsupported)),
            preloader = preloader,
        )
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertTrue(controller.playableBinds.isEmpty())
        assertTrue(preloader.targets.mapNotNull { it }.isEmpty())
    }

    @Test
    fun filterChangeReleasesOldPlayerRequestAndShowsEmptyState() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        viewModel.setFilter(VideoFilter(channelIds = emptySet()))
        runCurrent()

        assertTrue(controller.releaseCalls >= 1)
        assertEquals(VideoFeedPhase.EMPTY, viewModel.uiState.value.phase)
    }

    @Test
    fun pauseMuteRetryAndPageReleaseDelegateToTheSingleController() = runTest(dispatcher) {
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller)
        runCurrent()

        viewModel.togglePause()
        viewModel.seekTo(12_345L)
        viewModel.toggleMute()
        viewModel.retry()
        viewModel.releasePage()

        assertEquals(1, controller.pauseCalls)
        assertEquals(listOf(12_345L), controller.seekCalls)
        assertEquals(listOf(true), controller.muteChanges)
        assertEquals(1, controller.retryCalls)
        assertTrue(controller.releaseCalls >= 1)
        assertEquals(1, controller.fullReleaseCalls)
    }

    @Test
    fun randomFileUnavailableRetryRefreshesTelegramFileReferenceBeforeBinding() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101)
        val refreshed = stale.copy(fileId = 202)
        val controller = FakeVideoPlaybackController()
        val repository = FakeMessageRepository(
            initialVideos = listOf(stale),
            refreshedVideo = refreshed,
            emitRefreshResultsToVideos = true,
        )
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.setOrder(VideoFeedOrder.RANDOM)
        runCurrent()

        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
        runCurrent()
        viewModel.retry()
        runCurrent()

        assertEquals(listOf(202), controller.binds.map { it.fileId })
        assertSame(refreshed, repository.lastRefreshed)
    }

    @Test
    fun manualFileUnavailableRetryShowsMessageUnavailableWhenMessageIsMissing() =
        runTest(dispatcher) {
            val stale = video(messageId = 1, publishTime = 1, fileId = 101)
            val repository = FakeMessageRepository(listOf(stale)).apply {
                queuedRefreshResults += VideoReferenceResolution.Unavailable(
                    VideoReferenceFailure.Network,
                )
                queuedRefreshResults += VideoReferenceResolution.MessageMissing
            }
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(
                controller = controller,
                repository = repository,
                cacheController = FakeMediaCacheController(
                    MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
                ),
                initialOrder = VideoFeedOrder.LATEST,
            )
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
            runCurrent()

            viewModel.retry()
            runCurrent()

            val failed = viewModel.uiState.value.player.playbackState as VideoPlaybackState.Failed
            assertEquals(VideoPlaybackFailure.MESSAGE_UNAVAILABLE, failed.reason)
        }

    @Test
    fun manualFileUnavailableRetryRebindsTheRefreshedFileReference() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101)
        val refreshed = stale.copy(fileId = 202)
        val repository = FakeMessageRepository(
            initialVideos = listOf(stale),
            emitRefreshResultsToVideos = true,
        ).apply {
            queuedRefreshResults += VideoReferenceResolution.Unavailable(
                VideoReferenceFailure.Network,
            )
            queuedRefreshResults += VideoReferenceResolution.Resolved(refreshed)
        }
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
            ),
            initialOrder = VideoFeedOrder.LATEST,
        )
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
        runCurrent()

        viewModel.retry()
        runCurrent()

        assertEquals(listOf(101, 202), controller.binds.map(IndexedVideo::playbackFileId))
    }

    @Test
    fun manualFileUnavailableRetryShowsMessageUnavailableForUnsupportedMessage() =
        runTest(dispatcher) {
            val stale = video(messageId = 1, publishTime = 1, fileId = 101)
            val repository = FakeMessageRepository(listOf(stale)).apply {
                queuedRefreshResults += VideoReferenceResolution.Unavailable(
                    VideoReferenceFailure.Network,
                )
                queuedRefreshResults += VideoReferenceResolution.UnsupportedMessage
            }
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(
                controller = controller,
                repository = repository,
                cacheController = FakeMediaCacheController(
                    MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
                ),
                initialOrder = VideoFeedOrder.LATEST,
            )
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
            runCurrent()

            viewModel.retry()
            runCurrent()

            val failed = viewModel.uiState.value.player.playbackState as VideoPlaybackState.Failed
            assertEquals(VideoPlaybackFailure.MESSAGE_UNAVAILABLE, failed.reason)
        }

    @Test
    fun transientManualReferenceFailuresRemainVisibleAndRetryable() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101)
        val repository = FakeMessageRepository(listOf(stale)).apply {
            queuedRefreshResults += VideoReferenceResolution.Unavailable(
                VideoReferenceFailure.Network,
            )
            queuedRefreshResults += VideoReferenceResolution.Unavailable(
                VideoReferenceFailure.Timeout,
            )
            queuedRefreshResults += VideoReferenceResolution.Unavailable(
                VideoReferenceFailure.FloodWait(30),
            )
            queuedRefreshResults += VideoReferenceResolution.Unavailable(
                VideoReferenceFailure.Network,
            )
            queuedRefreshResults += VideoReferenceResolution.Unavailable(
                VideoReferenceFailure.Unknown,
            )
        }
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
            ),
            initialOrder = VideoFeedOrder.LATEST,
        )
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
        runCurrent()

        repeat(4) {
            viewModel.retry()
            runCurrent()
            val failed = viewModel.uiState.value.player.playbackState as VideoPlaybackState.Failed
            assertEquals(VideoPlaybackFailure.FILE_UNAVAILABLE, failed.reason)
        }

        assertEquals(5, repository.refreshedKeys.size)
        assertTrue(controller.binds.map(IndexedVideo::playbackFileId) == listOf(101))
    }

    @Test
    fun lateManualRetryResultCannotBindAfterTheUserChangesPage() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 2, fileId = 101)
        val second = video(messageId = 2, publishTime = 1, fileId = 102)
        val refreshed = first.copy(fileId = 202)
        val repository = FakeMessageRepository(listOf(first, second)).apply {
            queuedRefreshResults += VideoReferenceResolution.Unavailable(
                VideoReferenceFailure.Network,
            )
            queuedRefreshResults += VideoReferenceResolution.Resolved(refreshed)
        }
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
            ),
            initialOrder = VideoFeedOrder.LATEST,
        )
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, first)
        runCurrent()
        val retryGate = CompletableDeferred<Unit>()
        repository.queuedRefreshGates += retryGate

        viewModel.retry()
        runCurrent()
        viewModel.onPageUnstable()
        viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
        runCurrent()
        retryGate.complete(Unit)
        runCurrent()

        assertEquals(second.key, controller.binds.last().key)
        assertTrue(controller.binds.none { video -> video.playbackFileId == 202 })
    }

    @Test
    fun roomDeletionDuringManualRetryKeepsMessageUnavailableVisible() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101)
        val repository = FakeMessageRepository(
            initialVideos = listOf(stale),
            emitRefreshResultsToVideos = true,
        ).apply {
            queuedRefreshResults += VideoReferenceResolution.Unavailable(
                VideoReferenceFailure.Network,
            )
            queuedRefreshResults += VideoReferenceResolution.MessageMissing
        }
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
            ),
            initialOrder = VideoFeedOrder.LATEST,
        )
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
        runCurrent()

        viewModel.retry()
        runCurrent()

        val failed = viewModel.uiState.value.player.playbackState as VideoPlaybackState.Failed
        assertEquals(VideoPlaybackFailure.MESSAGE_UNAVAILABLE, failed.reason)
        assertEquals(VideoFeedPhase.EMPTY, viewModel.uiState.value.phase)
    }

    @Test
    fun randomStablePageRefreshesTelegramFileReferenceBeforeFirstBinding() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101)
        val refreshed = stale.copy(fileId = 202)
        val controller = FakeVideoPlaybackController()
        val repository = FakeMessageRepository(listOf(stale), refreshed)
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.setOrder(VideoFeedOrder.RANDOM)
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertEquals(listOf(202), controller.binds.map { it.fileId })
        assertEquals(listOf(stale.key), repository.refreshedKeys)
    }

    @Test
    fun everyRandomPageRefreshesItsOwnReferenceBeforeBinding() = runTest(dispatcher) {
        val staleVideos = listOf(
            video(messageId = 1, publishTime = 1, fileId = 101),
            video(messageId = 2, publishTime = 2, fileId = 102),
            video(messageId = 3, publishTime = 3, fileId = 103),
        )
        val refreshedByKey = staleVideos.associate { video ->
            video.key to video.copy(fileId = video.fileId + 100)
        }
        val controller = FakeVideoPlaybackController()
        val repository = FakeMessageRepository(
            initialVideos = staleVideos,
            refreshedVideos = refreshedByKey,
        )
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.setOrder(VideoFeedOrder.RANDOM)
        runCurrent()
        val randomKeys = viewModel.uiState.value.items.map { it.video.key }

        randomKeys.indices.forEach { page ->
            if (page > 0) viewModel.onPageUnstable()
            viewModel.onPageSettled(pagerPage = page, logicalPage = page)
            runCurrent()
        }

        randomKeys.forEach { key -> assertTrue(key in repository.refreshedKeys) }
        assertEquals(
            randomKeys.map { key -> refreshedByKey.getValue(key).fileId },
            controller.binds.map { video -> video.fileId },
        )
    }

    @Test
    fun leavingRandomPageCancelsItsPendingRefreshWithoutLateBinding() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101)
        val refreshGate = CompletableDeferred<Unit>()
        val controller = FakeVideoPlaybackController()
        val repository = FakeMessageRepository(
            initialVideos = listOf(stale),
            refreshedVideo = stale.copy(fileId = 202),
            refreshGate = refreshGate,
        )
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.setOrder(VideoFeedOrder.RANDOM)
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        viewModel.releasePage()
        refreshGate.complete(Unit)
        runCurrent()

        assertEquals(listOf(stale.key), repository.refreshedKeys)
        assertTrue(controller.binds.isEmpty())
    }

    @Test
    fun latestStablePageRefreshesTelegramReferenceForServerVariants() = runTest(dispatcher) {
        val current = video(messageId = 1, publishTime = 1, fileId = 101)
        val controller = FakeVideoPlaybackController()
        val repository = FakeMessageRepository(
            initialVideos = listOf(current),
            refreshedVideo = current.copy(fileId = 202),
        )
        val viewModel = viewModel(controller, repository)
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertEquals(listOf(202), controller.binds.map { it.fileId })
        assertEquals(listOf(current.key), repository.refreshedKeys)
    }

    @Test
    fun stablePageReportsTransitionAndRefreshBoundariesBeforeBinding() = runTest(dispatcher) {
        val current = video(messageId = 1, publishTime = 1, fileId = 101)
        val controller = FakeVideoPlaybackController()
        val repository = FakeMessageRepository(
            initialVideos = listOf(current),
            refreshedVideo = current.copy(fileId = 202),
        )
        val viewModel = viewModel(controller, repository)
        runCurrent()

        viewModel.onPageUnstable()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertEquals(
            listOf(
                PlaybackTransitionEvent.PageUnstable,
                PlaybackTransitionEvent.PageSettled(
                    key = current.key,
                    order = VideoFeedOrder.LATEST,
                    direction = PlaybackTransitionDirection.INITIAL,
                    randomRoundBoundary = false,
                ),
                PlaybackTransitionEvent.PlanStarted(current.key),
                PlaybackTransitionEvent.RefreshStarted(current.key),
                PlaybackTransitionEvent.RefreshFinished(
                    key = current.key,
                    outcome = PlaybackPlanRefreshOutcome.SUCCESS,
                ),
            ),
            controller.transitionEvents,
        )
        assertEquals(listOf(202), controller.binds.map(IndexedVideo::fileId))
    }

    @Test
    fun latestOriginalQualityBindsIndexedFileWithoutExtraMessageRefresh() = runTest(dispatcher) {
        val current = video(messageId = 1, publishTime = 1, fileId = 101)
        val controller = FakeVideoPlaybackController()
        val repository = FakeMessageRepository(
            initialVideos = listOf(current),
            refreshedVideo = current.copy(fileId = 202),
        )
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(
                    videoQualityPreference = VideoQualityPreference.ORIGINAL,
                ),
            ),
        )
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertEquals(listOf(101), controller.binds.map { it.fileId })
        assertTrue(repository.refreshedKeys.isEmpty())
    }

    @Test
    fun qualityRefreshTimeoutFallsBackToIndexedOriginal() = runTest(dispatcher) {
        val current = video(messageId = 1, publishTime = 1, fileId = 101)
        val repository = FakeMessageRepository(
            initialVideos = listOf(current),
            refreshedVideo = current.copy(fileId = 202),
            refreshGate = CompletableDeferred(),
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller, repository)
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(listOf(101), controller.binds.map { it.fileId })
        assertEquals(listOf(current.key), repository.refreshedKeys)
    }

    @Test
    fun randomOriginalRefreshUsesTheThreeSecondSoftDeadline() = runTest(dispatcher) {
        val current = video(messageId = 1, publishTime = 1, fileId = 101)
        val repository = FakeMessageRepository(
            initialVideos = listOf(current),
            refreshedVideo = current.copy(fileId = 202),
            refreshGate = CompletableDeferred(),
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
            ),
            initialOrder = VideoFeedOrder.RANDOM,
        )
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(listOf(101), controller.binds.map(IndexedVideo::playbackFileId))
        assertEquals(listOf(current.key), repository.refreshedKeys)
    }

    @Test
    fun sameKeyRoomWriteDuringInitialRefreshDoesNotCancelFinalBinding() = runTest(dispatcher) {
        val indexed = video(messageId = 1, publishTime = 1, fileId = 101)
        val refreshed = indexed.copy(fileId = 202)
        val repository = FakeMessageRepository(
            initialVideos = listOf(indexed),
            refreshedVideo = refreshed,
            emitRefreshResultsToVideos = true,
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            initialOrder = VideoFeedOrder.RANDOM,
        )
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertEquals(listOf(202), controller.binds.map(IndexedVideo::playbackFileId))
        assertEquals(listOf(indexed.key), repository.refreshedKeys)
    }

    @Test
    fun deletedMessageDuringInitialRefreshStillShowsExplicitUnplayableFailure() =
        runTest(dispatcher) {
            val indexed = video(messageId = 1, publishTime = 1, fileId = 101)
            val repository = FakeMessageRepository(
                initialVideos = listOf(indexed),
                emitRefreshResultsToVideos = true,
            ).apply {
                queuedRefreshResults += VideoReferenceResolution.MessageMissing
            }
            val controller = FakeVideoPlaybackController()
            val viewModel = viewModel(
                controller = controller,
                repository = repository,
                initialOrder = VideoFeedOrder.RANDOM,
            )
            runCurrent()

            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()

            val failed = viewModel.uiState.value.player.playbackState as VideoPlaybackState.Failed
            assertEquals(VideoPlaybackFailure.MESSAGE_UNAVAILABLE, failed.reason)
            assertEquals(VideoFeedPhase.EMPTY, viewModel.uiState.value.phase)
            assertEquals(listOf(indexed.key), repository.refreshedKeys)
            assertTrue(controller.binds.isEmpty())
        }

    @Test
    fun fileUnavailableAutomaticallyRefreshesAndRebindsAtMostOnce() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101)
        val refreshed = stale.copy(fileId = 202)
        val repository = FakeMessageRepository(
            initialVideos = listOf(stale),
            refreshedVideo = refreshed,
            emitRefreshResultsToVideos = true,
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
            ),
            initialOrder = VideoFeedOrder.LATEST,
        )
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
        runCurrent()

        assertEquals(listOf(101, 202), controller.binds.map(IndexedVideo::playbackFileId))
        assertTrue(viewModel.uiState.value.player.playbackState is VideoPlaybackState.Loading)
        assertEquals(listOf(stale.key), repository.refreshedKeys)

        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, refreshed)
        runCurrent()

        assertEquals(1, repository.refreshedKeys.size)
        assertTrue(viewModel.uiState.value.player.playbackState is VideoPlaybackState.Failed)
    }

    @Test
    fun targetPreparationAndStableBindingShareTheInFlightRefresh() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 2, fileId = 101)
        val second = video(messageId = 2, publishTime = 1, fileId = 102)
        val gate = CompletableDeferred<Unit>()
        val repository = FakeMessageRepository(
            initialVideos = listOf(first, second),
            refreshedVideos = mapOf(second.key to second.copy(fileId = 202)),
            refreshGates = mapOf(second.key to gate),
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller, repository)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        viewModel.onPageUnstable()
        viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
        runCurrent()
        viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
        runCurrent()

        assertEquals(1, repository.refreshedKeys.count { it == second.key })
        gate.complete(Unit)
        runCurrent()
        assertEquals(202, controller.binds.last().fileId)
    }

    @Test
    fun transparentRecoverySelectsTheCurrentQualityAfterRefreshing() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101).copy(fileSize = 1_000)
        val refreshed = stale.withAlternative(fileId = 303)
        val repository = FakeMessageRepository(
            initialVideos = listOf(stale),
            emitRefreshResultsToVideos = true,
        ).apply {
            queuedRefreshResults += VideoReferenceResolution.Unavailable(VideoReferenceFailure.Network)
            queuedRefreshResults += VideoReferenceResolution.Resolved(refreshed)
        }
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.DATA_SAVER),
            ),
        )
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        assertEquals(101, controller.binds.single().playbackFileId)

        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
        runCurrent()

        assertEquals(listOf(101, 303), controller.binds.map(IndexedVideo::playbackFileId))
    }

    @Test
    fun transparentRecoverySoftTimeoutPublishesFailureWithoutLateBinding() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101)
        val gate = CompletableDeferred<Unit>()
        val repository = FakeMessageRepository(
            initialVideos = listOf(stale),
            refreshedVideo = stale.copy(fileId = 202),
            refreshGate = gate,
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
            ),
            initialOrder = VideoFeedOrder.LATEST,
        )
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
        runCurrent()
        assertTrue(viewModel.uiState.value.player.playbackState is VideoPlaybackState.Loading)

        advanceTimeBy(3_000)
        runCurrent()

        val failed = viewModel.uiState.value.player.playbackState as VideoPlaybackState.Failed
        assertEquals(VideoPlaybackFailure.FILE_UNAVAILABLE, failed.reason)
        assertEquals(listOf(101), controller.binds.map(IndexedVideo::playbackFileId))
        assertEquals(listOf(stale.key), repository.refreshedKeys)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(101), controller.binds.map(IndexedVideo::playbackFileId))
    }

    @Test
    fun transparentRecoveryWithSameFileIdFailsWithoutRebinding() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101)
        val repository = FakeMessageRepository(
            initialVideos = listOf(stale),
            refreshedVideo = stale,
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
            ),
            initialOrder = VideoFeedOrder.LATEST,
        )
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
        runCurrent()

        val failed = viewModel.uiState.value.player.playbackState as VideoPlaybackState.Failed
        assertEquals(VideoPlaybackFailure.FILE_UNAVAILABLE, failed.reason)
        assertEquals(listOf(101), controller.binds.map(IndexedVideo::playbackFileId))
        assertEquals(listOf(stale.key), repository.refreshedKeys)
    }

    @Test
    fun leavingDuringTransparentRecoveryPreventsLateBinding() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101)
        val gate = CompletableDeferred<Unit>()
        val repository = FakeMessageRepository(
            initialVideos = listOf(stale),
            refreshedVideo = stale.copy(fileId = 202),
            refreshGate = gate,
        )
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
            ),
            initialOrder = VideoFeedOrder.LATEST,
        )
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
        runCurrent()
        viewModel.onPageUnstable()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(101), controller.binds.map(IndexedVideo::playbackFileId))
    }

    @Test
    fun deletedMessageDuringRecoveryShowsAnExplicitUnplayableFailure() = runTest(dispatcher) {
        val stale = video(messageId = 1, publishTime = 1, fileId = 101)
        val repository = FakeMessageRepository(
            initialVideos = listOf(stale),
            emitRefreshResultsToVideos = true,
        ).apply {
            queuedRefreshResults += VideoReferenceResolution.MessageMissing
        }
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(
            controller = controller,
            repository = repository,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.ORIGINAL),
            ),
            initialOrder = VideoFeedOrder.LATEST,
        )
        runCurrent()
        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        controller.emitFailure(VideoPlaybackFailure.FILE_UNAVAILABLE, stale)
        runCurrent()

        val failed = viewModel.uiState.value.player.playbackState as VideoPlaybackState.Failed
        assertEquals(VideoPlaybackFailure.MESSAGE_UNAVAILABLE, failed.reason)
        assertEquals(VideoFeedPhase.EMPTY, viewModel.uiState.value.phase)
        assertEquals(1, repository.refreshedKeys.size)
    }

    @Test
    fun floodWaitFallsBackWithoutRefreshRetryStorm() = runTest(dispatcher) {
        val current = video(messageId = 1, publishTime = 1, fileId = 101)
        val repository = FakeMessageRepository(listOf(current)).apply {
            queuedRefreshResults += VideoReferenceResolution.Unavailable(
                VideoReferenceFailure.FloodWait(30),
            )
        }
        val controller = FakeVideoPlaybackController()
        val viewModel = viewModel(controller, repository, initialOrder = VideoFeedOrder.RANDOM)
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()

        assertEquals(listOf(101), controller.binds.map(IndexedVideo::playbackFileId))
        assertEquals(listOf(current.key), repository.refreshedKeys)
    }

    @Test
    fun dataSaverUsesServerVariantForCurrentPlaybackAndNextPreload() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 1, fileId = 101)
        val second = video(messageId = 2, publishTime = 2, fileId = 102)
        val refreshed = mapOf(
            first.key to first.withAlternative(fileId = 301),
            second.key to second.withAlternative(fileId = 302),
        )
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val viewModel = viewModel(
            controller = controller,
            repository = FakeMessageRepository(
                initialVideos = listOf(first, second),
                refreshedVideos = refreshed,
            ),
            preloader = preloader,
            cacheController = FakeMediaCacheController(
                MediaCacheState(
                    videoQualityPreference = VideoQualityPreference.DATA_SAVER,
                ),
            ),
            policySource = FakeDevicePolicySource(NetworkTransport.MOBILE),
        )
        runCurrent()

        viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
        runCurrent()
        controller.emitFirstFrame()
        runCurrent()

        assertEquals(302, controller.binds.single().playbackFileId)
        assertEquals(301, preloader.targets.mapNotNull { it }.single().playbackFileId)
    }

    @Test
    fun autoThroughputChangeKeepsCurrentBindingAndSelectsNextBeforePreload() =
        runTest(dispatcher) {
            val first = video(messageId = 1, publishTime = 1, fileId = 101)
                .withAdaptiveAlternatives(lowFileId = 301, highFileId = 401)
            val second = video(messageId = 2, publishTime = 2, fileId = 102)
                .withAdaptiveAlternatives(lowFileId = 302, highFileId = 402)
            val repository = FakeMessageRepository(
                initialVideos = listOf(first, second),
                refreshedVideos = mapOf(first.key to first, second.key to second),
            )
            val controller = FakeVideoPlaybackController()
            val preloader = FakeVideoPreloadController()
            val metrics = StreamingNetworkMetricsEstimator()
            val viewModel = viewModel(
                controller = controller,
                repository = repository,
                preloader = preloader,
                cacheController = FakeMediaCacheController(
                    MediaCacheState(videoQualityPreference = VideoQualityPreference.AUTO),
                ),
                policySource = FakeDevicePolicySource(NetworkTransport.WIFI),
                networkMetrics = metrics,
            )
            runCurrent()
            viewModel.onPageSettled(pagerPage = 0, logicalPage = 0)
            runCurrent()
            assertEquals(402, controller.binds.single().playbackFileId)
            controller.emitFirstFrame()
            runCurrent()
            assertEquals(301, preloader.targets.mapNotNull { it }.last().playbackFileId)

            repeat(3) { metrics.recordAtBitsPerSecond(500_000L) }
            runCurrent()

            assertEquals(listOf(402), controller.binds.map(IndexedVideo::playbackFileId))
            assertEquals(301, preloader.targets.mapNotNull { it }.last().playbackFileId)
            viewModel.onPageUnstable()
            viewModel.onPageTargeted(pagerPage = 1, logicalPage = 1)
            runCurrent()
            viewModel.onPageSettled(pagerPage = 1, logicalPage = 1)
            runCurrent()

            assertEquals(301, controller.binds.last().playbackFileId)
            assertEquals(
                preloader.targets.mapNotNull { it }.last().playbackFileId,
                controller.binds.last().playbackFileId,
            )
        }

    @Test
    fun throughputEstimateCannotInvalidateAnExplicitQualityPlan() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 1, fileId = 101)
            .withAdaptiveAlternatives(301, 401)
        val second = video(messageId = 2, publishTime = 2, fileId = 102)
            .withAdaptiveAlternatives(302, 402)
        val metrics = StreamingNetworkMetricsEstimator()
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val viewModel = viewModel(
            controller = controller,
            repository = FakeMessageRepository(
                initialVideos = listOf(first, second),
                refreshedVideos = mapOf(first.key to first, second.key to second),
            ),
            preloader = preloader,
            cacheController = FakeMediaCacheController(
                MediaCacheState(videoQualityPreference = VideoQualityPreference.HD_720),
            ),
            networkMetrics = metrics,
        )
        runCurrent()
        viewModel.onPageSettled(0, 0)
        runCurrent()
        controller.emitFirstFrame()
        runCurrent()
        val targetCount = preloader.targets.size

        repeat(3) { metrics.recordAtBitsPerSecond(500_000L) }
        runCurrent()

        assertEquals(targetCount, preloader.targets.size)
        assertEquals(402, controller.binds.single().playbackFileId)
    }

    @Test
    fun autoThroughputSamplesDoNotRebuildTheSamePreparedRepresentation() = runTest(dispatcher) {
        val first = video(messageId = 1, publishTime = 1, fileId = 101)
            .withAdaptiveAlternatives(301, 401)
        val second = video(messageId = 2, publishTime = 2, fileId = 102)
            .withAdaptiveAlternatives(302, 402)
        val metrics = StreamingNetworkMetricsEstimator()
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val repository = FakeMessageRepository(listOf(first, second),
            refreshedVideos = mapOf(first.key to first, second.key to second))
        val viewModel = viewModel(controller, repository, preloader = preloader,
            cacheController = FakeMediaCacheController(MediaCacheState(videoQualityPreference = VideoQualityPreference.AUTO)),
            policySource = FakeDevicePolicySource(NetworkTransport.MOBILE), networkMetrics = metrics)
        runCurrent()
        viewModel.onPageSettled(0, 0)
        runCurrent()
        controller.emitFirstFrame()
        runCurrent()
        repeat(3) { metrics.recordAtBitsPerSecond(500_000L) }
        runCurrent()
        val refreshCount = repository.refreshedKeys.size
        val preparedFile = preloader.targets.mapNotNull { it }.last().playbackFileId
        repeat(12) { index ->
            metrics.recordAtBitsPerSecond(250_000L - index * 2_000L)
            runCurrent()
        }
        assertEquals("Same representation must not trigger refresh/cancel storms", refreshCount, repository.refreshedKeys.size)
        assertEquals(preparedFile, preloader.targets.mapNotNull { it }.last().playbackFileId)
        viewModel.onPageUnstable()
        viewModel.onPageTargeted(1, 1)
        runCurrent()
        viewModel.onPageSettled(1, 1)
        runCurrent()
        assertEquals(preparedFile, controller.binds.last().playbackFileId)
    }

    @Test
    fun largeMobileOriginalDoesNotBindBeforeConsent() = runTest(dispatcher) {
        val large = video(1, 1).copy(fileSize = 300L * 1024 * 1024)
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val vm = viewModel(controller, FakeMessageRepository(listOf(large)), preloader,
            policySource = FakeDevicePolicySource(NetworkTransport.MOBILE))
        runCurrent()
        vm.onPageSettled(0, 0)
        runCurrent()
        assertTrue(controller.binds.isEmpty())
        assertTrue(preloader.currentStarting.isEmpty())
    }

    @Test
    fun largeMobileNextOriginalDoesNotPreloadBeforeConsent() = runTest(dispatcher) {
        val large = video(1, 1).copy(fileSize = 300L * 1024 * 1024)
        val controller = FakeVideoPlaybackController()
        val preloader = FakeVideoPreloadController()
        val vm = viewModel(controller, FakeMessageRepository(listOf(video(2, 2), large)), preloader,
            policySource = FakeDevicePolicySource(NetworkTransport.MOBILE))
        runCurrent()
        vm.onPageSettled(0, 0)
        runCurrent()
        controller.emitReadyFirstFrame()
        runCurrent()
        assertTrue(preloader.targets.none { it?.key == large.key })
    }

    @Test
    fun originalConsentIsSingleUseAndCannotApproveADifferentPage() = runTest(dispatcher) {
        val first = video(2, 2).copy(fileSize = 300L * 1024 * 1024)
        val second = video(1, 1).copy(fileSize = 400L * 1024 * 1024)
        val controller = FakeVideoPlaybackController()
        val vm = viewModel(controller, FakeMessageRepository(listOf(first, second)),
            policySource = FakeDevicePolicySource(NetworkTransport.MOBILE))
        runCurrent()
        vm.onPageSettled(0, 0)
        runCurrent()
        assertEquals(first, vm.uiState.value.originalPlaybackAwaitingConfirmation)
        vm.confirmOriginalPlayback(first.key, first.fileId)
        vm.confirmOriginalPlayback(first.key, first.fileId)
        runCurrent()
        assertEquals(listOf(first), controller.binds)
        vm.onPageUnstable()
        vm.onPageSettled(1, 1)
        runCurrent()
        vm.confirmOriginalPlayback(first.key, first.fileId)
        runCurrent()
        assertEquals(listOf(first), controller.binds)
        assertEquals(second, vm.uiState.value.originalPlaybackAwaitingConfirmation)
        vm.onPageUnstable()
        vm.onPageSettled(0, 0)
        runCurrent()
        assertEquals(first, vm.uiState.value.originalPlaybackAwaitingConfirmation)
        assertEquals(1, controller.binds.size)
    }

    @Test
    fun mobileSwitchStopsAnUnapprovedLargeWifiOriginal() = runTest(dispatcher) {
        val large = video(1, 1).copy(fileSize = 300L * 1024 * 1024)
        val controller = FakeVideoPlaybackController()
        val policy = FakeDevicePolicySource(NetworkTransport.WIFI)
        val vm = viewModel(controller, FakeMessageRepository(listOf(large)), policySource = policy)
        runCurrent()
        vm.onPageSettled(0, 0)
        runCurrent()
        assertEquals(1, controller.binds.size)
        policy.setNetwork(NetworkTransport.MOBILE)
        runCurrent()
        assertEquals(large, vm.uiState.value.originalPlaybackAwaitingConfirmation)
        assertEquals(VideoPlaybackState.Idle, controller.snapshot.value.playbackState)
        vm.onForegroundChanged(false)
        vm.confirmOriginalPlayback(large.key, large.fileId)
        runCurrent()
        assertEquals(1, controller.binds.size)
        vm.onForegroundChanged(true)
        vm.confirmOriginalPlayback(large.key, large.fileId)
        runCurrent()
        assertEquals(2, controller.binds.size)
    }

    @Test
    fun changedQualityReevaluatesPendingOriginalInsteadOfApprovingStalePlan() = runTest(dispatcher) {
        val large = video(1, 1).copy(fileSize = 300L * 1024 * 1024)
        val controller = FakeVideoPlaybackController()
        val cache = FakeMediaCacheController()
        val vm = viewModel(controller, FakeMessageRepository(listOf(large)), cacheController = cache,
            policySource = FakeDevicePolicySource(NetworkTransport.MOBILE))
        runCurrent()
        vm.onPageSettled(0, 0)
        runCurrent()
        cache.setVideoQualityPreference(VideoQualityPreference.DATA_SAVER)
        runCurrent()
        vm.confirmOriginalPlayback(large.key, large.fileId)
        runCurrent()
        assertTrue(controller.binds.isEmpty())
        assertEquals(large, vm.uiState.value.originalPlaybackAwaitingConfirmation)
        vm.confirmOriginalPlayback(large.key, large.fileId)
        runCurrent()
        assertEquals(listOf(large), controller.binds)
    }

    private fun viewModel(
        controller: FakeVideoPlaybackController,
        repository: FakeMessageRepository = FakeMessageRepository(
            listOf(
                video(messageId = 1, publishTime = 1),
                video(messageId = 2, publishTime = 2),
                video(messageId = 3, publishTime = 3),
            ),
        ),
        preloader: FakeVideoPreloadController = FakeVideoPreloadController(),
        cacheController: FakeMediaCacheController = FakeMediaCacheController(),
        policySource: FakeDevicePolicySource = FakeDevicePolicySource(NetworkTransport.WIFI),
        initialOrder: VideoFeedOrder? = VideoFeedOrder.LATEST,
        feedSession: PlaybackFeedSession = PlaybackFeedSession(),
        onboardingPreferences: FakeVideoFeedOnboardingPreferences =
            FakeVideoFeedOnboardingPreferences(),
        networkMetrics: StreamingNetworkMetricsRepository =
            StreamingNetworkMetricsEstimator(),
    ): VideoPlaybackViewModel {
        val viewModel = VideoPlaybackViewModel(
            chatRepository = FakeChatRepository(),
            messageRepository = repository,
            playerController = controller,
            preloadController = preloader,
            cacheController = cacheController,
            devicePolicySource = policySource,
            feedSession = feedSession,
            onboardingPreferences = onboardingPreferences,
            networkMetrics = networkMetrics,
            testMarker = Unit,
        )
        if (initialOrder != null) viewModel.setOrder(initialOrder)
        return viewModel
    }

    private fun video(messageId: Long, publishTime: Long, fileId: Int = messageId.toInt()) = IndexedVideo(
        key = VideoKey(1, messageId),
        fileId = fileId,
        remoteUniqueId = "remote-$messageId",
        caption = "caption-$messageId",
        supportsStreaming = true,
        fileSize = 1,
        durationSeconds = 1,
        width = 1,
        height = 1,
        publishTime = publishTime,
        editTime = null,
        canBeSaved = true,
        tags = emptyList(),
    )

    private fun IndexedVideo.withAlternative(fileId: Int): IndexedVideo = copy(
        fileSize = 1_000,
        alternativeVariants = listOf(
            VideoPlaybackVariant(
                fileId = fileId,
                remoteUniqueId = "alternative-$fileId",
                fileSize = 1,
                width = 1,
                height = 1,
                codec = "h264",
            ),
        ),
    )

    private fun IndexedVideo.withAdaptiveAlternatives(
        lowFileId: Int,
        highFileId: Int,
    ): IndexedVideo = copy(
        fileSize = 8_000_000L,
        durationSeconds = 20,
        width = 1920,
        height = 1080,
        alternativeVariants = listOf(
            VideoPlaybackVariant(
                fileId = lowFileId,
                remoteUniqueId = "low-$lowFileId",
                fileSize = 875_000L,
                width = 640,
                height = 360,
                codec = "h264",
            ),
            VideoPlaybackVariant(
                fileId = highFileId,
                remoteUniqueId = "high-$highFileId",
                fileSize = 4_000_000L,
                width = 1280,
                height = 720,
                codec = "h264",
            ),
        ),
    )

    private fun StreamingNetworkMetricsEstimator.recordAtBitsPerSecond(bitsPerSecond: Long) {
        val bytes = 64L * 1024L
        recordNetworkProgress(
            bytes = bytes,
            durationNanos = bytes * 8_000_000_000L / bitsPerSecond,
            contextRevision = contextRevision,
        )
    }

    private class FakeChatRepository : TelegramChatRepository {
        override val channels: Flow<List<TelegramChannel>> = flowOf(
            listOf(TelegramChannel(1, "测试频道", null, isSelected = true)),
        )
        override val syncState: StateFlow<TelegramChatSyncState> =
            MutableStateFlow(TelegramChatSyncState.Ready)

        override suspend fun refresh() = Unit
        override suspend fun saveSelectedChannelIds(chatIds: Set<Long>) = Unit
        override suspend fun setChannelPinned(chatId: Long, isPinned: Boolean) = Unit
    }

    private class FakeMessageRepository(
        initialVideos: List<IndexedVideo>,
        private val refreshedVideo: IndexedVideo? = null,
        private val refreshGate: CompletableDeferred<Unit>? = null,
        refreshedVideos: Map<VideoKey, IndexedVideo> = emptyMap(),
        private val refreshGates: Map<VideoKey, CompletableDeferred<Unit>> = emptyMap(),
        private val refreshFailures: Map<VideoKey, Throwable> = emptyMap(),
        private val emitRefreshResultsToVideos: Boolean = false,
        private val hydrationGates: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque(),
        private val ignoreHydrationCancellation: Boolean = false,
        private val keyObservationFailuresBeforeSuccess: Int = 0,
    ) : TelegramMessageRepository {
        private val videos = MutableStateFlow(initialVideos)
        private val refreshedByKey = refreshedVideos.toMutableMap()
        var lastRefreshed: IndexedVideo? = null
        val refreshedKeys = mutableListOf<VideoKey>()
        val hydrationRequests = mutableListOf<List<VideoKey>>()
        var keyObservationSubscriptions = 0
        val queuedRefreshResults = ArrayDeque<VideoReferenceResolution>()
        val queuedRefreshGates = ArrayDeque<CompletableDeferred<Unit>>()
        override val scanProgress: Flow<List<ChannelVideoScanProgress>> = flowOf(emptyList())

        override fun observeVideos(filter: VideoFilter): Flow<List<IndexedVideo>> = videos
            .let { flow ->
                if (filter.channelIds.isEmpty()) flowOf(emptyList()) else flow
            }

        override fun observeVideoKeyObservation(
            filter: VideoFilter,
        ): Flow<RepositoryObservation<List<VideoFeedKeySnapshot>>> = flow {
            keyObservationSubscriptions += 1
            if (keyObservationSubscriptions <= keyObservationFailuresBeforeSuccess) {
                emit(RepositoryObservation(emptyList(), RepositoryObservationFailure.DATABASE))
                return@flow
            }
            emitAll(
                observeVideos(filter).map { source ->
                    RepositoryObservation(
                        source.map { video ->
                            VideoFeedKeySnapshot(video.key, video.publishTime, video.editTime)
                        },
                    )
                },
            )
        }

        override suspend fun hydrateVideos(keys: List<VideoKey>): RepositoryObservation<List<IndexedVideo>> {
            hydrationRequests += keys
            val byKey = videos.value.associateBy(IndexedVideo::key)
            val hydrated = keys.mapNotNull(byKey::get)
            hydrationGates.removeFirstOrNull()?.let { gate ->
                if (ignoreHydrationCancellation) {
                    withContext(NonCancellable) { gate.await() }
                } else {
                    gate.await()
                }
            }
            return RepositoryObservation(hydrated)
        }

        override fun observeTags(channelIds: Set<Long>): Flow<List<TagSummary>> = flowOf(emptyList())
        override suspend fun refreshVideo(videoKey: VideoKey): VideoReferenceResolution {
            refreshedKeys += videoKey
            queuedRefreshGates.removeFirstOrNull()?.await()
            refreshGates[videoKey]?.await() ?: refreshGate?.await()
            refreshFailures[videoKey]?.let { throw it }
            val refreshed = refreshedByKey[videoKey] ?: refreshedVideo
            lastRefreshed = refreshed
            val result = queuedRefreshResults.removeFirstOrNull()
                ?: refreshed?.let(VideoReferenceResolution::Resolved)
                ?: VideoReferenceResolution.Unavailable(VideoReferenceFailure.Unknown)
            if (emitRefreshResultsToVideos) {
                videos.value = when (result) {
                    is VideoReferenceResolution.Resolved -> videos.value.map { video ->
                        if (video.key == videoKey) result.video else video
                    }
                    VideoReferenceResolution.MessageMissing,
                    VideoReferenceResolution.UnsupportedMessage,
                    -> videos.value.filterNot { video -> video.key == videoKey }
                    is VideoReferenceResolution.Unavailable -> videos.value
                }
                yield()
            }
            return result
        }
        fun setRefreshed(video: IndexedVideo) {
            refreshedByKey[video.key] = video
        }
        fun emitVideos(updated: List<IndexedVideo>) {
            videos.value = updated
        }
        override suspend fun getOriginalMessageLink(videoKey: VideoKey): OriginalMessageLinkResult =
            OriginalMessageLinkResult.Unavailable
        override suspend fun setForeground(isForeground: Boolean) = Unit
        override suspend fun refreshSelection() = Unit
        override suspend fun pauseScanning() = Unit
        override suspend fun resumeScanning() = Unit
    }

    private class FakeVideoPlaybackController(
        private val standbySupported: Boolean = false,
    ) : VideoPlaybackController {
        private val mutableSnapshot = MutableStateFlow(VideoPlayerSnapshot())
        override val snapshot: StateFlow<VideoPlayerSnapshot> = mutableSnapshot.asStateFlow()
        override val supportsStandbyPreparation: Boolean get() = standbySupported
        val standbyPreparations = mutableListOf<IndexedVideo>()
        val binds = mutableListOf<IndexedVideo>()
        val playableBinds = mutableListOf<IndexedVideo>()
        var transitionPauses = 0
        var pauseCalls = 0
        val seekCalls = mutableListOf<Long>()
        var retryCalls = 0
        var releaseCalls = 0
        var fullReleaseCalls = 0
        val muteChanges = mutableListOf<Boolean>()
        val transitionEvents = mutableListOf<PlaybackTransitionEvent>()
        val temporarySpeedChanges = mutableListOf<Boolean>()
        val events = mutableListOf<String>()

        override fun prepareStandby(
            video: IndexedVideo,
            context: com.qixuan.channelvideoflow.player.PlaybackPreparationContext,
        ): Boolean {
            if (!standbySupported) return false
            standbyPreparations += video
            return true
        }

        override fun recordTransition(event: PlaybackTransitionEvent) {
            transitionEvents += event
        }
        override fun attach(playerView: PlayerView) = Unit
        override fun detach(playerView: PlayerView) = Unit
        override fun bind(video: IndexedVideo) {
            binds += video
            if (video.supportsStreaming) playableBinds += video
            mutableSnapshot.value = VideoPlayerSnapshot(VideoPlaybackState.Loading(video))
        }
        override fun showFailure(video: IndexedVideo, failure: VideoPlaybackFailure) {
            mutableSnapshot.value = VideoPlayerSnapshot(VideoPlaybackState.Failed(video, failure))
        }
        fun emitFailure(reason: VideoPlaybackFailure, video: IndexedVideo) {
            mutableSnapshot.value = VideoPlayerSnapshot(VideoPlaybackState.Failed(video, reason))
        }
        fun emitFirstFrame() {
            mutableSnapshot.value = mutableSnapshot.value.copy(hasRenderedFirstFrame = true)
        }
        fun emitReadyFirstFrame(
            video: IndexedVideo? = null,
            isPlaying: Boolean = true,
        ) {
            val readyVideo = video ?: when (val state = mutableSnapshot.value.playbackState) {
                is VideoPlaybackState.Loading -> state.video
                is VideoPlaybackState.Ready -> state.video
                else -> error("No playable video is bound")
            }
            mutableSnapshot.value = mutableSnapshot.value.copy(
                playbackState = VideoPlaybackState.Ready(
                    video = readyVideo,
                    firstReadyWaitMillis = null,
                    observedLocalBytes = null,
                ),
                hasRenderedFirstFrame = true,
                isPlaying = isPlaying,
            )
        }
        fun emitUnsupported(video: IndexedVideo) {
            mutableSnapshot.value = VideoPlayerSnapshot(
                playbackState = VideoPlaybackState.Unsupported(video),
            )
        }
        fun emitProgress(
            positionMillis: Long,
            durationMillis: Long,
            bufferedPositionMillis: Long,
        ) {
            mutableSnapshot.value = mutableSnapshot.value.copy(
                positionMillis = positionMillis,
                durationMillis = durationMillis,
                bufferedPositionMillis = bufferedPositionMillis,
                isSeekable = true,
            )
        }
        override fun retry() {
            retryCalls += 1
        }
        override fun pause() {
            pauseCalls += 1
            events += "pause"
            mutableSnapshot.value = mutableSnapshot.value.copy(
                isPaused = true,
                isPlaying = false,
                playbackSpeed = VideoPlaybackSpeeds.NORMAL,
            )
        }
        override fun resume() {
            mutableSnapshot.value = mutableSnapshot.value.copy(isPaused = false)
        }
        override fun seekTo(positionMillis: Long) {
            seekCalls += positionMillis
            mutableSnapshot.value = mutableSnapshot.value.copy(hasRenderedFirstFrame = false)
        }
        override fun pauseForPageTransition() {
            transitionPauses += 1
        }
        override fun setMuted(muted: Boolean) {
            muteChanges += muted
            mutableSnapshot.value = mutableSnapshot.value.copy(isMuted = muted)
        }
        override fun setTemporaryPlaybackSpeed(active: Boolean) {
            temporarySpeedChanges += active
            events += "speed:$active"
            val snapshot = mutableSnapshot.value
            val canActivate = active &&
                snapshot.playbackState is VideoPlaybackState.Ready &&
                snapshot.hasRenderedFirstFrame &&
                snapshot.isPlaying &&
                !snapshot.isPaused
            mutableSnapshot.value = snapshot.copy(
                playbackSpeed = if (canActivate) {
                    VideoPlaybackSpeeds.TEMPORARY_FAST_FORWARD
                } else {
                    VideoPlaybackSpeeds.NORMAL
                },
            )
        }
        override fun onAppBackgrounded() = Unit
        override fun releaseBinding() {
            releaseCalls += 1
            mutableSnapshot.value = VideoPlayerSnapshot()
        }
        override fun release() {
            fullReleaseCalls += 1
            releaseBinding()
        }
    }

    private class FakeVideoPreloadController : VideoPreloadController {
        val targets = mutableListOf<IndexedVideo?>()
        val committedTargets = mutableListOf<IndexedVideo>()
        val currentStarting = mutableListOf<IndexedVideo>()
        var beginPromotionCalls = 0
        var abandonPromotionCalls = 0
        var currentAcquireCalls = 0
        var currentAcquireFailureCalls = 0
        var stopCalls = 0
        private val mutableOwnerHandoff = MutableStateFlow(PreloadOwnerHandoffSnapshot())
        override val ownerHandoff: StateFlow<PreloadOwnerHandoffSnapshot> = mutableOwnerHandoff

        override fun setNextVideo(video: IndexedVideo?) {
            val current = mutableOwnerHandoff.value
            if (
                current.hasSpeculativeOwner &&
                current.key == video?.key &&
                current.fileId == video?.playbackFileId
            ) {
                return
            }
            targets += video
            mutableOwnerHandoff.value = mutableOwnerHandoff.value.copy(
                phase = if (video == null) {
                    PreloadOwnerHandoffPhase.RELEASED
                } else {
                    PreloadOwnerHandoffPhase.NEXT_WARMING
                },
                key = video?.key,
                fileId = video?.playbackFileId,
                hasSpeculativeOwner = video != null,
            )
        }

        override fun beginTargetPromotion() {
            beginPromotionCalls += 1
        }

        override fun commitTargetPromotion(video: IndexedVideo) {
            committedTargets += video
            mutableOwnerHandoff.value = mutableOwnerHandoff.value.copy(
                phase = PreloadOwnerHandoffPhase.TARGET_COMMITTED,
                key = video.key,
                fileId = video.playbackFileId,
                hasSpeculativeOwner = true,
                promotionAttempt = true,
                promotionMatched = true,
            )
        }

        override fun abandonTargetPromotion() {
            abandonPromotionCalls += 1
            mutableOwnerHandoff.value = mutableOwnerHandoff.value.copy(
                phase = PreloadOwnerHandoffPhase.ABANDONED,
                hasSpeculativeOwner = false,
            )
        }

        override fun onCurrentPlaybackStarting(video: IndexedVideo) {
            currentStarting += video
        }

        override fun onCurrentPlaybackRangeAcquired(video: IndexedVideo) {
            currentAcquireCalls += 1
        }

        override fun onCurrentPlaybackRangeAcquireFailed(video: IndexedVideo) {
            currentAcquireFailureCalls += 1
        }

        override fun stop() {
            stopCalls += 1
            mutableOwnerHandoff.value = mutableOwnerHandoff.value.copy(
                phase = PreloadOwnerHandoffPhase.RELEASED,
                hasSpeculativeOwner = false,
            )
        }
    }

    private class FakeMediaCacheController(initial: MediaCacheState = MediaCacheState()) :
        MediaCacheController {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<MediaCacheState> = mutableState
        override fun start() = Unit
        override suspend fun refresh() = Unit
        override suspend fun setLimitBytes(bytes: Long) = Unit
        override suspend fun setMobileDataPreloadEnabled(enabled: Boolean) = Unit
        override suspend fun setVideoQualityPreference(preference: VideoQualityPreference) {
            mutableState.value = mutableState.value.copy(videoQualityPreference = preference)
        }
        override suspend fun trimToLimit() = Unit
        override suspend fun clearMediaCache() = Unit
    }

    private class FakeDevicePolicySource(network: NetworkTransport) : DevicePreloadPolicySource {
        private val mutableSignals = MutableStateFlow(
            DevicePreloadSignals(network = network),
        )
        override val signals: StateFlow<DevicePreloadSignals> = mutableSignals

        fun setNetwork(network: NetworkTransport) {
            mutableSignals.value = mutableSignals.value.copy(network = network)
        }
    }

    private class FakeVideoFeedOnboardingPreferences(
        private val writeFailure: Throwable? = null,
    ) : VideoFeedOnboardingPreferences {
        override val hasSeenSwipeHint = MutableSharedFlow<Boolean>(replay = 1)
        var markCalls = 0

        suspend fun emitSeen(seen: Boolean) {
            hasSeenSwipeHint.emit(seen)
        }

        override suspend fun markSwipeHintSeen() {
            markCalls += 1
            writeFailure?.let { throw it }
            hasSeenSwipeHint.emit(true)
        }
    }

    private class FixedVideoQueueRandom(vararg values: Int) : VideoQueueRandomSource {
        private val sequence = values.toList()
        private var index = 0

        override fun nextInt(until: Int): Int = sequence.getOrElse(index++) { 0 }.mod(until)
    }

    private class FatalRefreshError : Error("fatal refresh invariant")
}
