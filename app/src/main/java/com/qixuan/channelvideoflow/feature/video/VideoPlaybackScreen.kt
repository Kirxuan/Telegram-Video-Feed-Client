package com.qixuan.channelvideoflow.feature.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.qixuan.channelvideoflow.R
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoFeedOrder
import com.qixuan.channelvideoflow.model.video.VideoFilter
import com.qixuan.channelvideoflow.model.video.DEFAULT_VIDEO_FEED_ORDER
import com.qixuan.channelvideoflow.model.video.VideoKey
import com.qixuan.channelvideoflow.player.VideoPlaybackFailure
import com.qixuan.channelvideoflow.player.VideoPlaybackState
import com.qixuan.channelvideoflow.player.VideoPlaybackSpeeds
import com.qixuan.channelvideoflow.ui.theme.ChannelVideoFlowTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToLong

internal object VideoFeedTestTags {
    const val Loading = "video-feed-loading"
    const val Empty = "video-feed-empty"
    const val EmptyAction = "video-feed-empty-action"
    const val FeedError = "video-feed-error"
    const val Retry = "video-feed-retry"
    const val TapSurface = "video-feed-tap-surface"
    const val PausedOverlay = "video-feed-paused-overlay"
    const val Progress = "video-feed-progress"
    const val Metadata = "video-feed-metadata"
    const val Fullscreen = "video-feed-fullscreen"
    const val ExitFullscreen = "video-feed-exit-fullscreen"
    const val Mute = "video-feed-mute"
    const val OriginalLink = "video-feed-original-link"
    const val Pager = "video-feed-pager"
    const val LatestOrder = "video-feed-order-latest"
    const val RandomOrder = "video-feed-order-random"
    const val Logout = "video-feed-logout"
    const val SwipeHint = "video-feed-swipe-hint"
    const val TemporarySpeed = "video-feed-temporary-speed"
    const val DetailsExpand = "video-feed-details-expand"
    const val MetadataSummary = "video-feed-metadata-summary"
    const val DetailsSheet = "video-feed-details-sheet"
    const val DetailsContent = "video-feed-details-content"
    const val DetailsClose = "video-feed-details-close"
    const val DetailsCaption = "video-feed-details-caption"
    const val DetailsTags = "video-feed-details-tags"
    const val DetailsPublishTime = "video-feed-details-publish-time"
    const val LoadingPoster = "video-feed-loading-poster"
}

internal val LoadingPosterAlphaSemanticsKey = SemanticsPropertyKey<Float>(
    name = "LoadingPosterAlpha",
)
internal var SemanticsPropertyReceiver.loadingPosterAlpha by LoadingPosterAlphaSemanticsKey

internal val LoadingPosterPaletteSemanticsKey = SemanticsPropertyKey<Int>(
    name = "LoadingPosterPalette",
)
internal var SemanticsPropertyReceiver.loadingPosterPalette by LoadingPosterPaletteSemanticsKey

internal val LoadingPosterVideoIdentitySemanticsKey = SemanticsPropertyKey<String>(
    name = "LoadingPosterVideoIdentity",
)
internal var SemanticsPropertyReceiver.loadingPosterVideoIdentity by
    LoadingPosterVideoIdentitySemanticsKey

@Composable
@UnstableApi
fun VideoPlaybackRoute(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    initialFilter: VideoFilter? = null,
    initialOrder: VideoFeedOrder = DEFAULT_VIDEO_FEED_ORDER,
    onOrderPersisted: (VideoFeedOrder) -> Unit = {},
    viewModel: VideoPlaybackViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var isFullscreen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel, initialFilter, initialOrder) {
        initialFilter?.let { filter -> viewModel.setFeedSource(filter, initialOrder) }
    }

    FullscreenSystemUiEffect(isFullscreen = isFullscreen)
    KeepScreenOnEffect(keepScreenOn = shouldKeepScreenOn(uiState))
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForegroundChanged(true)
                Lifecycle.Event.ON_STOP -> viewModel.onForegroundChanged(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.releasePage()
        }
    }
    LaunchedEffect(viewModel, context) {
        viewModel.openOriginalMessageLinks.collect { httpsUrl ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(httpsUrl)))
            }.onFailure {
                viewModel.onOriginalMessageLinkOpenFailed()
            }
        }
    }
    VideoPlaybackScreen(
        uiState = uiState,
        playbackProgress = viewModel.playbackProgress,
        onBack = onBack,
        onLogout = {
            viewModel.releasePage()
            onLogout()
        },
        onRetry = viewModel::retry,
        onConfirmOriginalPlayback = viewModel::confirmOriginalPlayback,
        onFeedRetry = viewModel::retryFeedObservation,
        onTogglePause = viewModel::togglePause,
        onTemporaryPlaybackSpeedChanged = viewModel::setTemporaryPlaybackSpeed,
        onSeek = viewModel::seekTo,
        onToggleMute = viewModel::toggleMute,
        onOriginalMessage = viewModel::requestOriginalMessageLink,
        onOrderChanged = { order ->
            viewModel.setOrder(order)
            onOrderPersisted(order)
        },
        onPageUnstable = viewModel::onPageUnstable,
        onPageTargeted = viewModel::onPageTargeted,
        onPageSettled = viewModel::onPageSettled,
        onPagerPointerDown = viewModel::onPagerPointerDown,
        onPagerPointerReleased = viewModel::onPagerPointerReleased,
        onAttachPlayer = viewModel::attachPlayer,
        onDetachPlayer = viewModel::detachPlayer,
        isFullscreen = isFullscreen,
        onFullscreenChanged = { isFullscreen = it },
        autoHideControls = true,
    )
}

@Composable
@UnstableApi
internal fun VideoPlaybackScreen(
    uiState: VideoPlaybackUiState,
    playbackProgress: StateFlow<VideoPlaybackProgressUiState>? = null,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onRetry: () -> Unit,
    onConfirmOriginalPlayback: (VideoKey, Int) -> Unit = { _, _ -> },
    onTogglePause: () -> Unit,
    onTemporaryPlaybackSpeedChanged: (Boolean) -> Unit = {},
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onOriginalMessage: () -> Unit,
    onOrderChanged: (VideoFeedOrder) -> Unit,
    onPageUnstable: () -> Unit,
    onPageTargeted: (Int, Int) -> Unit = { _, _ -> },
    onPageSettled: (Int, Int) -> Unit,
    onPagerPointerDown: (Long) -> Unit = {},
    onPagerPointerReleased: (Long) -> Unit = {},
    onAttachPlayer: (PlayerView) -> Unit,
    onDetachPlayer: (PlayerView) -> Unit = {},
    onPagerComposed: () -> Unit = {},
    /** Observation hook for tests: receives the feed pager state once it exists. */
    onPagerState: ((PagerState) -> Unit)? = null,
    isFullscreen: Boolean = false,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onFeedRetry: () -> Unit = {},
    autoHideControls: Boolean = false,
) {
    var detailVideoKey by remember { mutableStateOf<VideoKey?>(null) }
    var expandedMetadataKey by remember { mutableStateOf<VideoKey?>(null) }
    var currentVideoKey by remember { mutableStateOf<VideoKey?>(null) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    val detailItem = detailVideoKey?.let { key ->
        (uiState.items + uiState.upcomingItems).firstOrNull { item -> item.video.key == key }
    }
    val activeDetailItem = detailItem?.takeIf {
        uiState.phase == VideoFeedPhase.CONTENT && !isFullscreen
    }
    // The description is collapsed by default so it cannot cover the frame; an explicitly
    // expanded panel belongs to one video only and suspends the auto-hide timer.
    val metadataExpanded = expandedMetadataKey != null && expandedMetadataKey == currentVideoKey
    val autoHideEligible = autoHideControls &&
        uiState.phase == VideoFeedPhase.CONTENT &&
        uiState.player.isPlaying &&
        activeDetailItem == null &&
        !metadataExpanded &&
        !isFullscreen

    LaunchedEffect(
        uiState.queueGeneration,
        uiState.player.playbackState.videoKeyOrNull(),
        autoHideEligible,
        controlsVisible,
    ) {
        if (!autoHideEligible) {
            controlsVisible = true
        } else if (controlsVisible) {
            delay(CONTROL_AUTO_HIDE_MILLIS)
            controlsVisible = false
        }
    }

    LaunchedEffect(uiState.queueGeneration) {
        detailVideoKey = null
        expandedMetadataKey = null
    }
    LaunchedEffect(uiState.phase, detailItem, isFullscreen) {
        if (
            detailVideoKey != null &&
            (uiState.phase != VideoFeedPhase.CONTENT || detailItem == null || isFullscreen)
        ) {
            detailVideoKey = null
        }
    }
    BackHandler {
        if (detailVideoKey != null) {
            detailVideoKey = null
        } else if (isFullscreen) {
            onFullscreenChanged(false)
        } else {
            onBack()
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (activeDetailItem == null) {
                        Modifier
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                ),
        ) {
            val detachedMessageFailure = (uiState.player.playbackState as? VideoPlaybackState.Failed)
                ?.takeIf { failed ->
                    failed.reason == VideoPlaybackFailure.MESSAGE_UNAVAILABLE &&
                        uiState.items.none { item -> item.video.key == failed.video.key }
                }
            if (detachedMessageFailure != null) {
                ImmersiveMessageUnavailableState(onBack = onBack)
            } else when (uiState.phase) {
                VideoFeedPhase.LOADING -> ImmersiveLoadingState()
                VideoFeedPhase.EMPTY -> ImmersiveEmptyState(onBack = onBack)
                VideoFeedPhase.ERROR -> ImmersiveFeedFailureState(onRetry = onFeedRetry)
                VideoFeedPhase.CONTENT -> FeedPager(
                    uiState = uiState,
                    playbackProgress = playbackProgress,
                    onRetry = onRetry,
                    onConfirmOriginalPlayback = onConfirmOriginalPlayback,
                    onTogglePause = onTogglePause,
                    onTemporaryPlaybackSpeedChanged = onTemporaryPlaybackSpeedChanged,
                    onSeek = onSeek,
                    onToggleMute = onToggleMute,
                    onOriginalMessage = onOriginalMessage,
                    onPageUnstable = {
                        controlsVisible = true
                        onPageUnstable()
                    },
                    onPageTargeted = onPageTargeted,
                    onPageSettled = { pagerPage, logicalPage ->
                        controlsVisible = true
                        onPageSettled(pagerPage, logicalPage)
                    },
                    onPagerPointerDown = onPagerPointerDown,
                    onPagerPointerReleased = onPagerPointerReleased,
                    onAttachPlayer = onAttachPlayer,
                    onDetachPlayer = onDetachPlayer,
                    onPagerComposed = onPagerComposed,
                    onPagerState = onPagerState,
                    isFullscreen = isFullscreen,
                    onFullscreenChanged = onFullscreenChanged,
                    detailsVisible = activeDetailItem != null,
                    controlsVisible = controlsVisible,
                    onControlsInteraction = { controlsVisible = true },
                    onShowDetails = { key -> detailVideoKey = key },
                    metadataExpanded = metadataExpanded,
                    onToggleMetadata = {
                        expandedMetadataKey = if (metadataExpanded) null else currentVideoKey
                    },
                    onCurrentVideoKeyChanged = { currentKey ->
                        currentVideoKey = currentKey
                        if (detailVideoKey != null && detailVideoKey != currentKey) {
                            detailVideoKey = null
                        }
                        if (expandedMetadataKey != null && expandedMetadataKey != currentKey) {
                            expandedMetadataKey = null
                        }
                    },
                )
            }
            if (uiState.phase == VideoFeedPhase.CONTENT && uiState.feedFailure != null) {
                TextButton(
                    onClick = onFeedRetry,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .padding(top = 64.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.72f),
                            shape = ChannelVideoFlowTokens.Shapes.pill,
                        )
                        .testTag(VideoFeedTestTags.FeedError),
                ) {
                    Text(
                        text = stringResource(R.string.video_feed_database_error_retry),
                        color = Color.White,
                    )
                }
            }
            if (!isFullscreen) {
                AnimatedVisibility(
                    visible = controlsVisible || uiState.phase != VideoFeedPhase.CONTENT,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn(tween(CONTROL_VISIBILITY_ANIMATION_MILLIS)),
                    exit = fadeOut(tween(CONTROL_VISIBILITY_ANIMATION_MILLIS)),
                    label = "feed top controls",
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        FeedTopBar(
                            order = uiState.order,
                            onBack = onBack,
                            onLogout = onLogout,
                            onOrderChanged = onOrderChanged,
                        )
                    }
                }
            }
        }

        activeDetailItem?.let { item ->
            VideoDetailsBottomSheet(
                item = item,
                onDismiss = { detailVideoKey = null },
            )
        }
    }
}

@Composable
private fun FeedTopBar(
    order: VideoFeedOrder,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onOrderChanged: (VideoFeedOrder) -> Unit,
) {
    val backLabel = stringResource(R.string.video_back_channels)
    val logoutLabel = stringResource(R.string.video_logout)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(124.dp)
            .zIndex(2f)
            .drawWithCache {
                val scrim = Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                )
                onDrawBehind { drawRect(scrim) }
            },
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                ),
            )
            .height(56.dp)
            .zIndex(3f)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            FeedIcon(FeedIconType.BACK, backLabel)
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FeedOrderTab(
                text = stringResource(R.string.video_order_latest),
                selected = order == VideoFeedOrder.LATEST,
                testTag = VideoFeedTestTags.LatestOrder,
                onClick = { onOrderChanged(VideoFeedOrder.LATEST) },
            )
            FeedOrderTab(
                text = stringResource(R.string.video_order_random),
                selected = order == VideoFeedOrder.RANDOM,
                testTag = VideoFeedTestTags.RandomOrder,
                onClick = { onOrderChanged(VideoFeedOrder.RANDOM) },
            )
        }
        IconButton(
            onClick = onLogout,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .testTag(VideoFeedTestTags.Logout),
        ) {
            FeedIcon(FeedIconType.LOGOUT, logoutLabel)
        }
    }
}

@Composable
private fun FeedOrderTab(
    text: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(ChannelVideoFlowTokens.Shapes.pill)
            .background(
                if (selected) {
                    ChannelVideoFlowTokens.Feed.electricBlue.copy(alpha = 0.24f)
                } else {
                    ChannelVideoFlowTokens.Feed.overlay.copy(alpha = 0.54f)
                },
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    ChannelVideoFlowTokens.Feed.electricBlue.copy(alpha = 0.54f)
                } else {
                    ChannelVideoFlowTokens.Feed.outline
                },
                shape = ChannelVideoFlowTokens.Shapes.pill,
            )
            .clickable(
                enabled = !selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .testTag(testTag)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.58f),
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
@UnstableApi
private fun FeedPager(
    uiState: VideoPlaybackUiState,
    playbackProgress: StateFlow<VideoPlaybackProgressUiState>?,
    onRetry: () -> Unit,
    onConfirmOriginalPlayback: (VideoKey, Int) -> Unit,
    onTogglePause: () -> Unit,
    onTemporaryPlaybackSpeedChanged: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onOriginalMessage: () -> Unit,
    onPageUnstable: () -> Unit,
    onPageTargeted: (Int, Int) -> Unit,
    onPageSettled: (Int, Int) -> Unit,
    onPagerPointerDown: (Long) -> Unit,
    onPagerPointerReleased: (Long) -> Unit,
    onAttachPlayer: (PlayerView) -> Unit,
    onDetachPlayer: (PlayerView) -> Unit,
    onPagerComposed: () -> Unit,
    onPagerState: ((PagerState) -> Unit)?,
    isFullscreen: Boolean,
    onFullscreenChanged: (Boolean) -> Unit,
    detailsVisible: Boolean,
    controlsVisible: Boolean,
    onControlsInteraction: () -> Unit,
    onShowDetails: (VideoKey) -> Unit,
    metadataExpanded: Boolean,
    onToggleMetadata: () -> Unit,
    onCurrentVideoKeyChanged: (VideoKey?) -> Unit,
) {
    SideEffect(onPagerComposed)
    val context = LocalContext.current
    val currentKeys = uiState.logicalFeedKeys()
    val pagerState = rememberPagerState(
        pageCount = {
            if (uiState.order == VideoFeedOrder.RANDOM) RANDOM_PAGER_PAGE_COUNT else currentKeys.size
        },
    )
    if (onPagerState != null) {
        SideEffect { onPagerState(pagerState) }
    }
    LaunchedEffect(uiState.queueGeneration) {
        if (currentKeys.isNotEmpty()) {
            pagerState.scrollToPage(
                if (uiState.order == VideoFeedOrder.RANDOM) {
                    randomPagerStart(currentKeys.size)
                } else {
                    0
                },
            )
        }
    }
    LaunchedEffect(currentKeys.size) {
        if (
            uiState.order == VideoFeedOrder.LATEST &&
            pagerState.currentPage >= currentKeys.size &&
            currentKeys.isNotEmpty()
        ) {
            pagerState.scrollToPage(currentKeys.lastIndex)
        }
    }
    LaunchedEffect(
        pagerState,
        uiState.feedGeneration,
        uiState.hydrationGeneration,
        uiState.randomRoundStartPagerPage,
    ) {
        snapshotFlow {
            PagerSignal(
                currentPage = pagerState.currentPage,
                targetPage = pagerState.targetPage,
                settledPage = pagerState.settledPage,
                isScrollInProgress = pagerState.isScrollInProgress,
            )
        }
            .collectLatest { signal ->
                if (signal.isScrollInProgress) {
                    onPageUnstable()
                    val committedTargetPage = committedPagerTargetPage(
                        currentPage = signal.currentPage,
                        predictedTargetPage = signal.targetPage,
                    )
                    resolvePagerSlot(uiState, committedTargetPage)?.let { target ->
                        onPageTargeted(committedTargetPage, target.logicalPage)
                    }
                } else {
                    resolvePagerSlot(uiState, signal.settledPage)?.let { settled ->
                        onPageSettled(signal.settledPage, settled.logicalPage)
                    }
                }
            }
    }

    val currentSlot = resolvePagerSlot(uiState, pagerState.currentPage)
    val currentItem = currentSlot?.item
    val pagerInteractionEnabled =
        !isFullscreen &&
            !detailsVisible
    LaunchedEffect(currentItem?.video?.key) {
        onCurrentVideoKeyChanged(currentItem?.video?.key)
    }
    val currentPointerDown = rememberUpdatedState(onPagerPointerDown)
    val currentPointerReleased = rememberUpdatedState(onPagerPointerReleased)
    var pointerHeld by remember { mutableStateOf(false) }
    val autoAdvancePointerDown = rememberUpdatedState<(Long) -> Unit>({ time ->
        pointerHeld = true
        currentPointerDown.value(time)
    })
    val autoAdvancePointerReleased = rememberUpdatedState<(Long) -> Unit>({ time ->
        pointerHeld = false
        currentPointerReleased.value(time)
    })
    val latestUiState by rememberUpdatedState(uiState)
    val latestDetailsVisible by rememberUpdatedState(detailsVisible)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val endedKey = (uiState.player.playbackState as? VideoPlaybackState.Ready)
        ?.video?.key?.takeIf { uiState.player.hasEnded }
    // One logical advance per completion. Recomposition and progress updates cannot enqueue
    // more pages, and the normal pager observer stays the sole owner of preparation and
    // binding. The target page is anchored when the completion is observed so that a
    // lifecycle interruption mid-flight can finish the same transition instead of skipping a
    // page; a cancelled animateScrollToPage leaves the pager snapped to no page (device probe),
    // so the advance may only be consumed after it completes, and any user gesture on the pager
    // consumes it for good.
    LaunchedEffect(endedKey, uiState.queueGeneration, lifecycle) {
        if (endedKey == null) return@LaunchedEffect
        var consumed = false
        var targetPage: Int? = null
        var resumeAfterInterruption = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (consumed) return@repeatOnLifecycle
            if (!resumeAfterInterruption) {
                snapshotFlow {
                    !latestDetailsVisible && !pointerHeld &&
                        !pagerState.isScrollInProgress && !latestUiState.player.isPaused &&
                        resolvePagerSlot(latestUiState, pagerState.settledPage)?.key == endedKey
                }.first { it }
                targetPage = pagerState.settledPage + 1
            }
            val nextPage = requireNotNull(targetPage)
            if (nextPage >= pagerState.pageCount ||
                resolvePagerSlot(latestUiState, nextPage) == null
            ) {
                consumed = true
                return@repeatOnLifecycle
            }
            resumeAfterInterruption = false
            try {
                pagerState.animateScrollToPage(nextPage, animationSpec = tween(300))
                consumed = true
            } catch (cancelled: CancellationException) {
                // A gesture or a queue change while resumed keeps the user in charge; a
                // lifecycle interruption lets the next RESUMED pass finish the advance.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    consumed = true
                } else {
                    resumeAfterInterruption = true
                }
                throw cancelled
            }
        }
    }
    val boundVideoProtected = when (val state = uiState.player.playbackState) {
        VideoPlaybackState.Idle -> false
        is VideoPlaybackState.Loading -> !state.video.canBeSaved
        is VideoPlaybackState.Ready -> !state.video.canBeSaved
        is VideoPlaybackState.Unsupported -> !state.video.canBeSaved
        is VideoPlaybackState.Failed -> !state.video.canBeSaved
    }
    ProtectedContentWindowEffect(
        isProtected = currentItem?.video?.canBeSaved == false || boundVideoProtected,
    )
    if (uiState.items.any { item -> item.video.supportsStreaming }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                PlayerView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    onAttachPlayer(this)
                }
            },
            onRelease = onDetachPlayer,
        )
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (!pagerInteractionEnabled) {
                    Modifier
                } else {
                    Modifier.observePagerPointerLifecycle(
                        onPointerDown = autoAdvancePointerDown,
                        onPointerReleased = autoAdvancePointerReleased,
                    )
                },
            )
            .testTag(VideoFeedTestTags.Pager),
        userScrollEnabled = pagerInteractionEnabled,
        key = { page ->
            val resolved = requireNotNull(resolvePagerSlot(uiState, page))
            pagerItemKey(
                pagerPage = page,
                order = uiState.order,
                videoKey = resolved.key,
            )
        },
    ) { page ->
        val resolved = requireNotNull(resolvePagerSlot(uiState, page))
        val item = resolved.item
        if (item == null) {
            ImmersiveLoadingState()
        } else {
            FeedPage(
                item = item,
                isCurrentPage = page == pagerState.currentPage,
                isPageScrolling = pagerState.isScrollInProgress,
                isFullscreen = isFullscreen,
                uiState = uiState,
                playbackProgress = playbackProgress,
                onRetry = onRetry,
                    onConfirmOriginalPlayback = onConfirmOriginalPlayback,
                onTogglePause = onTogglePause,
                onTemporaryPlaybackSpeedChanged = onTemporaryPlaybackSpeedChanged,
                onSeek = onSeek,
                onToggleMute = onToggleMute,
                onOriginalMessage = onOriginalMessage,
                onFullscreenChanged = onFullscreenChanged,
                detailsVisible = detailsVisible,
                controlsVisible = controlsVisible,
                onControlsInteraction = onControlsInteraction,
                onShowDetails = onShowDetails,
                metadataExpanded = metadataExpanded,
                onToggleMetadata = onToggleMetadata,
            )
        }
    }
}

private data class PagerSignal(
    val currentPage: Int,
    val targetPage: Int,
    val settledPage: Int,
    val isScrollInProgress: Boolean,
)

/**
 * Treats crossing Pager's snap midpoint as the spatial hysteresis for preparation.
 * targetPage is a useful prediction, but device traces show it can point at the next
 * page during a small drag that ultimately settles back on the current page.
 */
internal fun committedPagerTargetPage(
    currentPage: Int,
    predictedTargetPage: Int,
): Int = if (currentPage == predictedTargetPage) predictedTargetPage else currentPage

internal fun pagerItemKey(
    pagerPage: Int,
    order: VideoFeedOrder,
    videoKey: VideoKey,
): String = if (order == VideoFeedOrder.RANDOM) {
    "$pagerPage:${videoKey.chatId}:${videoKey.messageId}"
} else {
    "${videoKey.chatId}:${videoKey.messageId}"
}

internal data class ResolvedPagerItem(
    val item: FeedVideoItem,
    val logicalPage: Int,
)

private data class ResolvedPagerSlot(
    val key: VideoKey,
    val item: FeedVideoItem?,
    val logicalPage: Int,
)

private fun VideoPlaybackUiState.logicalFeedKeys(): List<VideoKey> =
    feedKeys.ifEmpty { items.map { item -> item.video.key } }

private fun VideoPlaybackUiState.logicalUpcomingKeys(): List<VideoKey> =
    upcomingKeys.ifEmpty { upcomingItems.map { item -> item.video.key } }

private fun resolvePagerSlot(
    uiState: VideoPlaybackUiState,
    pagerPage: Int,
): ResolvedPagerSlot? {
    val current = uiState.logicalFeedKeys()
    if (current.isEmpty()) return null
    val hydrated = (uiState.items + uiState.upcomingItems).associateBy { item -> item.video.key }
    if (uiState.order != VideoFeedOrder.RANDOM) {
        val key = current.getOrNull(pagerPage) ?: return null
        return ResolvedPagerSlot(key, hydrated[key], pagerPage)
    }
    val roundStart = uiState.randomRoundStartPagerPage
    if (roundStart == null) {
        val index = Math.floorMod(pagerPage, current.size)
        val key = current[index]
        return ResolvedPagerSlot(key, hydrated[key], index)
    }
    val offset = pagerPage - roundStart
    val upcoming = uiState.logicalUpcomingKeys()
    if (offset < 0 || upcoming.isEmpty()) {
        val index = Math.floorMod(offset, current.size)
        val key = current[index]
        return ResolvedPagerSlot(key, hydrated[key], index)
    }
    if (offset < current.size) {
        val key = current[offset]
        return ResolvedPagerSlot(key, hydrated[key], offset)
    }
    val upcomingIndex = Math.floorMod(offset - current.size, upcoming.size)
    val key = upcoming[upcomingIndex]
    return ResolvedPagerSlot(key, hydrated[key], upcomingIndex)
}

internal fun resolvePagerItem(
    uiState: VideoPlaybackUiState,
    pagerPage: Int,
): ResolvedPagerItem? = resolvePagerSlot(uiState, pagerPage)?.let { resolved ->
    resolved.item?.let { item -> ResolvedPagerItem(item, resolved.logicalPage) }
}

private sealed interface FeedPagePresentation {
    data object Content : FeedPagePresentation
    data object Loading : FeedPagePresentation
    data object Unsupported : FeedPagePresentation
    data class Failure(
        val reason: VideoPlaybackFailure,
    ) : FeedPagePresentation
}

private fun feedPagePresentation(
    item: FeedVideoItem,
    player: com.qixuan.channelvideoflow.player.VideoPlayerSnapshot,
): FeedPagePresentation {
    val playbackState = player.playbackState
    if (!item.video.supportsStreaming) return FeedPagePresentation.Unsupported
    return when {
        playbackState is VideoPlaybackState.Unsupported &&
            playbackState.video.key == item.video.key -> FeedPagePresentation.Unsupported
        playbackState is VideoPlaybackState.Failed &&
            playbackState.video.key == item.video.key ->
            FeedPagePresentation.Failure(playbackState.reason)
        playbackState is VideoPlaybackState.Ready &&
            playbackState.video.key == item.video.key &&
            player.hasRenderedFirstFrame -> FeedPagePresentation.Content
        else -> FeedPagePresentation.Loading
    }
}

private fun Modifier.observePagerPointerLifecycle(
    onPointerDown: State<(Long) -> Unit>,
    onPointerReleased: State<(Long) -> Unit>,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        onPointerDown.value(monotonicTimeMillis())
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val anyPressed = event.changes.any { change -> change.pressed }
            } while (anyPressed)
        } finally {
            // Fullscreen/details can remove this modifier before an UP event arrives.
            onPointerReleased.value(monotonicTimeMillis())
        }
    }
}

@Composable
private fun FeedPage(
    item: FeedVideoItem,
    isCurrentPage: Boolean,
    isPageScrolling: Boolean,
    isFullscreen: Boolean,
    uiState: VideoPlaybackUiState,
    playbackProgress: StateFlow<VideoPlaybackProgressUiState>?,
    onRetry: () -> Unit,
    onConfirmOriginalPlayback: (VideoKey, Int) -> Unit,
    onTogglePause: () -> Unit,
    onTemporaryPlaybackSpeedChanged: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onOriginalMessage: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    detailsVisible: Boolean,
    controlsVisible: Boolean,
    onControlsInteraction: () -> Unit,
    onShowDetails: (VideoKey) -> Unit,
    metadataExpanded: Boolean,
    onToggleMetadata: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (item.video.supportsStreaming) Modifier else Modifier.background(Color.Black),
            ),
    ) {
        if (!isCurrentPage) return@Box

        val pending = uiState.originalPlaybackAwaitingConfirmation?.takeIf { it.key == item.video.key }
        if (pending != null) {
            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.video_original_confirmation_title), color = Color.White)
                Text(
                    stringResource(R.string.video_original_confirmation_body,
                        com.qixuan.channelvideoflow.feature.settings.formatByteSize(pending.playbackFileSize ?: 0L)),
                    color = Color.White,
                )
                TextButton(
                    onClick = { onConfirmOriginalPlayback(pending.key, pending.playbackFileId) },
                    modifier = Modifier.testTag("video-original-confirm"),
                ) {
                    Text(stringResource(R.string.video_original_confirmation_play))
                }
                Text(stringResource(R.string.video_original_confirmation_skip), color = Color.White)
            }
            return@Box
        }

        val presentation = feedPagePresentation(item, uiState.player)
        when (presentation) {
            is FeedPagePresentation.Failure -> {
                ImmersivePlaybackFailure(
                    failure = presentation.reason,
                    onRetry = onRetry,
                )
            }

            FeedPagePresentation.Unsupported -> {
                ImmersiveUnsupportedState(
                    onOriginalMessage = onOriginalMessage,
                    linkLoading = uiState.originalMessageLink is OriginalMessageLinkUiState.Loading,
                )
            }

            FeedPagePresentation.Content,
            FeedPagePresentation.Loading,
            -> {
                if (presentation == FeedPagePresentation.Content) {
                    FeedContentOverlay(
                        item = item,
                        uiState = uiState,
                        playbackProgress = playbackProgress,
                        isPageScrolling = isPageScrolling,
                        isFullscreen = isFullscreen,
                        onTogglePause = onTogglePause,
                        onTemporaryPlaybackSpeedChanged = onTemporaryPlaybackSpeedChanged,
                        onSeek = onSeek,
                        onToggleMute = onToggleMute,
                        onOriginalMessage = onOriginalMessage,
                        onFullscreenChanged = onFullscreenChanged,
                        detailsVisible = detailsVisible,
                        controlsVisible = controlsVisible,
                        onControlsInteraction = onControlsInteraction,
                        onShowDetails = onShowDetails,
                        metadataExpanded = metadataExpanded,
                        onToggleMetadata = onToggleMetadata,
                    )
                }
                ImmersiveVideoLoadingState(
                    video = item.video,
                    visible = presentation == FeedPagePresentation.Loading,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.FeedContentOverlay(
    item: FeedVideoItem,
    uiState: VideoPlaybackUiState,
    playbackProgress: StateFlow<VideoPlaybackProgressUiState>?,
    isPageScrolling: Boolean,
    isFullscreen: Boolean,
    onTogglePause: () -> Unit,
    onTemporaryPlaybackSpeedChanged: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onOriginalMessage: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    detailsVisible: Boolean,
    controlsVisible: Boolean,
    onControlsInteraction: () -> Unit,
    onShowDetails: (VideoKey) -> Unit,
    metadataExpanded: Boolean,
    onToggleMetadata: () -> Unit,
) {
    var isScrubbing by remember(item.video.key) { mutableStateOf(false) }
    var isMetadataDimmed by remember(item.video.key) { mutableStateOf(false) }
    val pauseLabel = stringResource(R.string.video_pause)
    val metadataDimmedDescription = stringResource(R.string.video_metadata_dimmed)
    val metadataVisibleDescription = stringResource(R.string.video_metadata_visible)
    val showControlsLabel = stringResource(R.string.video_show_controls)
    val isInteracting = isPageScrolling || isScrubbing
    LaunchedEffect(item.video.key, isInteracting) {
        if (isInteracting) {
            isMetadataDimmed = true
        } else if (isMetadataDimmed) {
            delay(METADATA_RESTORE_DELAY_MILLIS)
            isMetadataDimmed = false
        }
    }
    val metadataAlpha by animateFloatAsState(
        targetValue = if (isMetadataDimmed) METADATA_INTERACTION_ALPHA else 1f,
        animationSpec = tween(
            durationMillis = if (isMetadataDimmed) {
                METADATA_FADE_OUT_MILLIS
            } else {
                METADATA_FADE_IN_MILLIS
            },
        ),
        label = "video metadata interaction alpha",
    )
    val currentTogglePause = rememberUpdatedState(onTogglePause)
    val currentTemporarySpeedChanged = rememberUpdatedState(onTemporaryPlaybackSpeedChanged)

    if (!isFullscreen && controlsVisible) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(BOTTOM_SCRIM_HEIGHT_FRACTION)
                .graphicsLayer { alpha = metadataAlpha }
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.48f to Color.Black.copy(alpha = 0.24f),
                        1f to Color.Black.copy(alpha = 0.86f),
                    ),
                ),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (detailsVisible) {
                    Modifier
                } else if (!controlsVisible) {
                    Modifier.clickable(role = Role.Button, onClick = onControlsInteraction)
                } else {
                    Modifier.temporarySpeedTapGesture(
                        onTap = currentTogglePause,
                        onTemporarySpeedChanged = currentTemporarySpeedChanged,
                    )
                },
            )
            .then(
                if (uiState.player.isPaused || detailsVisible) {
                    Modifier.clearAndSetSemantics { }
                } else if (!controlsVisible) {
                    Modifier.semantics {
                        role = Role.Button
                        contentDescription = showControlsLabel
                        onClick(label = showControlsLabel) {
                            onControlsInteraction()
                            true
                        }
                    }
                } else {
                    Modifier.semantics {
                        role = Role.Button
                        contentDescription = pauseLabel
                        onClick(label = pauseLabel) {
                            currentTogglePause.value()
                            true
                        }
                    }
                },
            )
            .testTag(VideoFeedTestTags.TapSurface),
    )
    AnimatedVisibility(
        visible = uiState.player.isPaused,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(
            animationSpec = tween(PAUSED_OVERLAY_ANIMATION_MILLIS),
        ) + scaleIn(
            initialScale = PAUSED_OVERLAY_INITIAL_SCALE,
            animationSpec = tween(PAUSED_OVERLAY_ANIMATION_MILLIS),
        ),
        exit = fadeOut(
            animationSpec = tween(PAUSED_OVERLAY_ANIMATION_MILLIS),
        ) + scaleOut(
            targetScale = PAUSED_OVERLAY_INITIAL_SCALE,
            animationSpec = tween(PAUSED_OVERLAY_ANIMATION_MILLIS),
        ),
        label = "paused playback overlay",
    ) {
        PausedPlaybackOverlay(
            enabled = uiState.player.isPaused && !detailsVisible,
            onClick = onTogglePause,
        )
    }
    val temporarySpeedActive =
        uiState.player.playbackSpeed == VideoPlaybackSpeeds.TEMPORARY_FAST_FORWARD
    AnimatedVisibility(
        visible = temporarySpeedActive,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                ),
            )
            .padding(top = TEMPORARY_SPEED_TOP_PADDING),
        enter = fadeIn(tween(TEMPORARY_SPEED_ANIMATION_MILLIS)) + scaleIn(
            initialScale = TEMPORARY_SPEED_INITIAL_SCALE,
            animationSpec = tween(TEMPORARY_SPEED_ANIMATION_MILLIS),
        ),
        exit = fadeOut(tween(TEMPORARY_SPEED_ANIMATION_MILLIS)) + scaleOut(
            targetScale = TEMPORARY_SPEED_INITIAL_SCALE,
            animationSpec = tween(TEMPORARY_SPEED_ANIMATION_MILLIS),
        ),
        label = "temporary playback speed",
    ) {
        TemporarySpeedIndicator(enabled = temporarySpeedActive)
    }
    if (uiState.showSwipeHint && !isFullscreen && controlsVisible) {
        SwipeHint(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 78.dp),
        )
    }
    if (!isFullscreen) {
        if (controlsVisible) {
            FeedActionRail(
            isMuted = uiState.player.isMuted,
            originalLinkLoading = uiState.originalMessageLink is OriginalMessageLinkUiState.Loading,
            interactionEnabled = !detailsVisible,
            onToggleMute = {
                onControlsInteraction()
                onToggleMute()
            },
            onOriginalMessage = {
                onControlsInteraction()
                onOriginalMessage()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(
                    WindowInsets.safeContent.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                .padding(end = 10.dp, bottom = ACTION_RAIL_BOTTOM_OFFSET),
        )
            MetadataPanel(
            item = item,
            playbackState = uiState.player.playbackState,
            linkState = uiState.originalMessageLink,
            expanded = metadataExpanded,
            onToggleExpanded = onToggleMetadata,
            onShowDetails = onShowDetails,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = VIDEO_METADATA_MAX_WIDTH)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeContent.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                .padding(start = 16.dp, end = 92.dp, bottom = METADATA_BOTTOM_OFFSET)
                .graphicsLayer { alpha = metadataAlpha }
                .semantics {
                    stateDescription = if (isMetadataDimmed) {
                        metadataDimmedDescription
                    } else {
                        metadataVisibleDescription
                    }
                }
                .testTag(VideoFeedTestTags.Metadata),
        )
            if (item.video.isLandscapeVideo()) {
                LandscapeFullscreenPrompt(
                    videoWidth = item.video.width,
                    videoHeight = item.video.height,
                    onClick = {
                        if (!detailsVisible) onFullscreenChanged(true)
                    },
                )
            }
        }
    } else {
        ExitFullscreenButton(
            onClick = { onFullscreenChanged(false) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp),
        )
    }
    if (controlsVisible) {
        PlaybackProgressState(
            key = item.video.key,
            playbackProgress = playbackProgress,
            fallbackPlayer = uiState.player,
            onSeek = if (detailsVisible) ({ _ -> }) else onSeek,
            onScrubbingChanged = { isScrubbing = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.safeContent.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                .padding(bottom = PROGRESS_BOTTOM_OFFSET),
        )
    }
}

private enum class BeforeLongPressResult {
    TAP,
    CANCELLED,
}

private fun Modifier.temporarySpeedTapGesture(
    onTap: State<() -> Unit>,
    onTemporarySpeedChanged: State<(Boolean) -> Unit>,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        var temporarySpeedRequested = false
        try {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.isConsumed) return@awaitEachGesture
            val pointerId = down.id
            val touchSlop = viewConfiguration.touchSlop
            val result = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { candidate ->
                        candidate.id == pointerId
                    } ?: return@withTimeoutOrNull BeforeLongPressResult.CANCELLED
                    if (change.isConsumed) {
                        return@withTimeoutOrNull BeforeLongPressResult.CANCELLED
                    }
                    if (!change.pressed) {
                        change.consume()
                        return@withTimeoutOrNull BeforeLongPressResult.TAP
                    }
                    if ((change.position - down.position).getDistance() > touchSlop) {
                        return@withTimeoutOrNull BeforeLongPressResult.CANCELLED
                    }
                }
            }
            when (result) {
                BeforeLongPressResult.TAP -> onTap.value()
                BeforeLongPressResult.CANCELLED -> Unit
                null -> {
                    temporarySpeedRequested = true
                    onTemporarySpeedChanged.value(true)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { candidate ->
                            candidate.id == pointerId
                        }
                        event.changes.forEach { pointerChange -> pointerChange.consume() }
                    } while (change?.pressed == true)
                }
            }
        } finally {
            if (temporarySpeedRequested) {
                onTemporarySpeedChanged.value(false)
            }
        }
    }
}

@Composable
private fun TemporarySpeedIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.video_temporary_speed)
    Box(
        modifier = modifier
            .then(
                if (enabled) {
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = label
                    }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            )
            .testTag(VideoFeedTestTags.TemporarySpeed)
            .background(
                color = Color.Black.copy(alpha = 0.68f),
                shape = RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SwipeHint(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.video_swipe_hint)
    val description = stringResource(R.string.video_swipe_hint_description)
    var animationStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animationStarted = true }
    val alpha by animateFloatAsState(
        targetValue = if (animationStarted) 1f else 0f,
        animationSpec = tween(SWIPE_HINT_ENTRANCE_MILLIS),
        label = "swipe hint alpha",
    )
    val verticalOffset by animateDpAsState(
        targetValue = if (animationStarted) 0.dp else 10.dp,
        animationSpec = tween(SWIPE_HINT_ENTRANCE_MILLIS),
        label = "swipe hint vertical offset",
    )
    Column(
        modifier = modifier
            .offset(y = verticalOffset)
            .graphicsLayer { this.alpha = alpha }
            .background(Color.Black.copy(alpha = 0.52f), RoundedCornerShape(20.dp))
            .semantics { contentDescription = description }
            .testTag(VideoFeedTestTags.SwipeHint)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 28.dp)) {
            val strokeWidth = 2.dp.toPx()
            val centerX = size.width / 2f
            drawLine(
                color = Color.White.copy(alpha = 0.92f),
                start = Offset(centerX, size.height * 0.84f),
                end = Offset(centerX, size.height * 0.18f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.92f),
                start = Offset(centerX, size.height * 0.18f),
                end = Offset(size.width * 0.28f, size.height * 0.42f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.92f),
                start = Offset(centerX, size.height * 0.18f),
                end = Offset(size.width * 0.72f, size.height * 0.42f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.94f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PlaybackProgressState(
    key: com.qixuan.channelvideoflow.model.video.VideoKey,
    playbackProgress: StateFlow<VideoPlaybackProgressUiState>?,
    fallbackPlayer: com.qixuan.channelvideoflow.player.VideoPlayerSnapshot,
    onSeek: (Long) -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val collectedProgress = playbackProgress?.collectAsStateWithLifecycle()?.value
    val progress = collectedProgress ?: VideoPlaybackProgressUiState(
        key = fallbackPlayer.playbackState.videoKeyOrNull(),
        positionMillis = fallbackPlayer.positionMillis,
        durationMillis = fallbackPlayer.durationMillis,
        bufferedPositionMillis = fallbackPlayer.bufferedPositionMillis,
        isSeekable = fallbackPlayer.isSeekable,
    )
    val aligned = if (progress.key == key) progress else VideoPlaybackProgressUiState(key = key)
    PlaybackProgressBar(
        positionMillis = aligned.positionMillis,
        durationMillis = aligned.durationMillis,
        isSeekable = aligned.isSeekable,
        onSeek = onSeek,
        onScrubbingChanged = onScrubbingChanged,
        modifier = modifier,
    )
}

/**
 * Bottom description surface.
 *
 * The default state is a single compact row so the description never covers the frame; the full
 * metadata block is only mounted after an explicit tap and collapses again as soon as the user
 * moves to another video.
 */
@Composable
private fun MetadataPanel(
    item: FeedVideoItem,
    playbackState: VideoPlaybackState,
    linkState: OriginalMessageLinkUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onShowDetails: (VideoKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val panelFade = tween<Float>(METADATA_PANEL_ANIMATION_MILLIS)
    val panelSize = tween<IntSize>(METADATA_PANEL_ANIMATION_MILLIS)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(panelFade) +
                expandVertically(
                    animationSpec = panelSize,
                    expandFrom = Alignment.Bottom,
                ),
            exit = fadeOut(panelFade) +
                shrinkVertically(
                    animationSpec = panelSize,
                    shrinkTowards = Alignment.Bottom,
                ),
            label = "video metadata panel",
        ) {
            FeedMetadata(
                item = item,
                playbackState = playbackState,
                linkState = linkState,
                onShowDetails = onShowDetails,
            )
        }
        MetadataSummaryBar(
            channelTitle = item.channelTitle,
            descriptionPreview = item.video.descriptionPreview(),
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
        )
    }
}

@Composable
private fun MetadataSummaryBar(
    channelTitle: String,
    descriptionPreview: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val actionLabel = stringResource(
        if (expanded) R.string.video_metadata_collapse else R.string.video_metadata_expand,
    )
    val actionDescription = stringResource(
        if (expanded) {
            R.string.video_metadata_collapse_description
        } else {
            R.string.video_metadata_expand_description
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChannelVideoFlowTokens.Sizes.touchTarget)
            .clip(METADATA_SUMMARY_SHAPE)
            .background(Color.Black.copy(alpha = 0.58f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), METADATA_SUMMARY_SHAPE)
            .clickable(role = Role.Button, onClick = onToggleExpanded)
            .semantics { contentDescription = actionDescription }
            .testTag(VideoFeedTestTags.MetadataSummary)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .background(FEED_ACCENT, RoundedCornerShape(2.dp)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = channelTitle,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (descriptionPreview.isNotBlank()) {
                Text(
                    text = descriptionPreview,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = actionLabel,
            color = FEED_ACCENT,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        FeedChevron(pointsUp = !expanded)
    }
}

@Composable
private fun FeedChevron(pointsUp: Boolean) {
    Canvas(
        modifier = Modifier
            .padding(start = 6.dp)
            .size(16.dp),
    ) {
        val strokeWidth = 1.8.dp.toPx()
        val tipY = if (pointsUp) size.height * 0.34f else size.height * 0.66f
        val wingY = if (pointsUp) size.height * 0.66f else size.height * 0.34f
        drawLine(
            color = Color.White.copy(alpha = 0.86f),
            start = Offset(size.width * 0.18f, wingY),
            end = Offset(size.width * 0.5f, tipY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White.copy(alpha = 0.86f),
            start = Offset(size.width * 0.5f, tipY),
            end = Offset(size.width * 0.82f, wingY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun FeedMetadata(
    item: FeedVideoItem,
    playbackState: VideoPlaybackState,
    linkState: OriginalMessageLinkUiState,
    onShowDetails: (VideoKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playingVideo = when (playbackState) {
        VideoPlaybackState.Idle -> null
        is VideoPlaybackState.Loading -> playbackState.video
        is VideoPlaybackState.Ready -> playbackState.video
        is VideoPlaybackState.Unsupported -> playbackState.video
        is VideoPlaybackState.Failed -> playbackState.video
    }?.takeIf { it.key == item.video.key }
    val caption = item.video.caption
    val tagsText = item.video.tags.joinToString(separator = "  ") { tag -> tag.displayName }
    val expandDescription = stringResource(R.string.video_details_expand_description)
    var captionOverflow by remember(item.video.key, caption) { mutableStateOf(false) }
    var tagsOverflow by remember(item.video.key, tagsText) { mutableStateOf(false) }
    val detailsAvailable = captionOverflow || tagsOverflow
    val openDetails = { onShowDetails(item.video.key) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .background(FEED_ACCENT, RoundedCornerShape(2.dp)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = item.channelTitle,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        playingVideo?.let { video ->
            val size = video.playbackFileSize?.let {
                com.qixuan.channelvideoflow.feature.settings.formatByteSize(it)
            } ?: "体积未知"
            val source = if (video.selectedAlternative == null) "原画" else "服务端版本"
            Text(
                text = "$source · ${video.playbackWidth}×${video.playbackHeight} · $size" +
                    if (video.selectedAlternative == null && minOf(video.width, video.height) > 720) {
                        "\n当前播放高清原画，加载和流量消耗可能较大；可暂停或滑动到下一条。"
                    } else "",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (detailsAvailable) {
                            Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    role = Role.Button,
                                    onClick = openDetails,
                                )
                                .semantics {
                                    contentDescription = expandDescription
                                }
                                .padding(vertical = 4.dp)
                        } else {
                            Modifier
                        },
                    ),
                color = Color.White.copy(alpha = 0.94f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = EXPANDED_CAPTION_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (captionOverflow != result.hasVisualOverflow) {
                        captionOverflow = result.hasVisualOverflow
                    }
                },
            )
        }
        if (item.video.tags.isNotEmpty()) {
            Text(
                text = tagsText,
                color = FEED_ACCENT,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (tagsOverflow != result.hasVisualOverflow) {
                        tagsOverflow = result.hasVisualOverflow
                    }
                },
            )
        }
        if (detailsAvailable) {
            TextButton(
                onClick = openDetails,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(VideoFeedTestTags.DetailsExpand)
                    .semantics { contentDescription = expandDescription },
            ) {
                Text(
                    text = stringResource(R.string.video_details_expand),
                    color = FEED_ACCENT,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = formatPublishTime(item.video.publishTime),
            color = Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
        )
        when (linkState) {
            OriginalMessageLinkUiState.Idle -> Unit
            OriginalMessageLinkUiState.Loading -> Text(
                stringResource(R.string.video_original_link_loading),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
            is OriginalMessageLinkUiState.Unavailable -> Text(
                linkState.message,
                color = Color(0xFFFFB4AB),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VideoDetailsBottomSheet(
    item: FeedVideoItem,
    onDismiss: () -> Unit,
) {
    val detailsTitle = stringResource(R.string.video_details_title)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val maxContentHeight = LocalConfiguration.current.screenHeightDp.dp * 0.82f
    var dismissInProgress by remember(item.video.key) { mutableStateOf(false) }
    val dismissAnimated = {
        if (!dismissInProgress) {
            dismissInProgress = true
            coroutineScope.launch {
                sheetState.hide()
                if (!sheetState.isVisible) onDismiss()
                dismissInProgress = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier
            .testTag(VideoFeedTestTags.DetailsSheet)
            .semantics { paneTitle = detailsTitle },
        containerColor = ChannelVideoFlowTokens.Feed.elevatedGraphite,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.78f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = Color.White.copy(alpha = 0.38f),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxContentHeight)
                .verticalScroll(scrollState)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                .padding(start = 24.dp, end = 16.dp, bottom = 24.dp)
                .testTag(VideoFeedTestTags.DetailsContent),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = detailsTitle,
                    modifier = Modifier.semantics { heading() },
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = dismissAnimated,
                    enabled = !dismissInProgress,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(VideoFeedTestTags.DetailsClose),
                ) {
                    FeedIcon(
                        type = FeedIconType.CLOSE,
                        description = stringResource(R.string.video_details_close),
                        tint = Color.White.copy(alpha = 0.88f),
                    )
                }
            }
            if (item.channelTitle.isNotBlank()) {
                VideoDetailsSection(label = stringResource(R.string.video_details_channel)) {
                    Text(
                        text = item.channelTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (item.video.caption.isNotBlank()) {
                VideoDetailsSection(label = stringResource(R.string.video_details_caption)) {
                    Text(
                        text = item.video.caption,
                        modifier = Modifier.testTag(VideoFeedTestTags.DetailsCaption),
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (item.video.tags.isNotEmpty()) {
                VideoDetailsSection(label = stringResource(R.string.video_details_tags)) {
                    Text(
                        text = item.video.tags.joinToString(separator = "  ") { tag ->
                            tag.displayName
                        },
                        modifier = Modifier.testTag(VideoFeedTestTags.DetailsTags),
                        color = FEED_ACCENT,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (item.video.publishTime > 0L) {
                VideoDetailsSection(label = stringResource(R.string.video_details_publish_time)) {
                    Text(
                        text = formatPublishTime(item.video.publishTime),
                        modifier = Modifier.testTag(VideoFeedTestTags.DetailsPublishTime),
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoDetailsSection(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.52f),
            style = MaterialTheme.typography.labelMedium,
        )
        content()
    }
}

@Composable
private fun BoxScope.LandscapeFullscreenPrompt(
    videoWidth: Int,
    videoHeight: Int,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val fullscreenLabel = stringResource(R.string.video_fullscreen)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fittedVideoHeight = minOf(maxHeight, maxWidth * videoHeight.toFloat() / videoWidth.toFloat())
        val videoBottom = (maxHeight + fittedVideoHeight) / 2
        // A height-constrained video fills a landscape window. Keep the action in the
        // viewport above the bottom metadata instead of placing it below the window.
        val maximumPromptTop = (maxHeight - 180.dp - ChannelVideoFlowTokens.Sizes.touchTarget)
            .coerceAtLeast(0.dp)
        val promptTop = minOf(videoBottom + 14.dp, maximumPromptTop)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = promptTop)
                .controlPressScale(interactionSource)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.28f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.42f),
                    shape = RoundedCornerShape(18.dp),
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
                .semantics {
                    role = Role.Button
                    contentDescription = fullscreenLabel
                }
                .testTag(VideoFeedTestTags.Fullscreen)
                .sizeIn(minHeight = ChannelVideoFlowTokens.Sizes.touchTarget)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FeedIcon(
                type = FeedIconType.FULLSCREEN,
                description = fullscreenLabel,
                tint = Color.White.copy(alpha = 0.92f),
                iconSize = 16.dp,
            )
            Text(
                text = fullscreenLabel,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ExitFullscreenButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val exitFullscreenLabel = stringResource(R.string.video_exit_fullscreen)
    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .controlPressScale(interactionSource)
            .windowInsetsPadding(
                WindowInsets.safeContent.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                ),
            )
            .size(ChannelVideoFlowTokens.Sizes.touchTarget)
            .background(Color.Black.copy(alpha = 0.38f), CircleShape)
            .testTag(VideoFeedTestTags.ExitFullscreen),
    ) {
        FeedIcon(
            type = FeedIconType.EXIT_FULLSCREEN,
            description = exitFullscreenLabel,
            tint = Color.White.copy(alpha = 0.92f),
            iconSize = 22.dp,
        )
    }
}

@Composable
private fun FeedActionRail(
    isMuted: Boolean,
    originalLinkLoading: Boolean,
    interactionEnabled: Boolean,
    onToggleMute: () -> Unit,
    onOriginalMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val muteLabel = if (isMuted) {
            stringResource(R.string.video_sound_on)
        } else {
            stringResource(R.string.video_mute)
        }
        FeedActionButton(
            label = muteLabel,
            icon = if (isMuted) FeedIconType.MUTED else FeedIconType.VOLUME,
            testTag = VideoFeedTestTags.Mute,
            enabled = interactionEnabled,
            onClick = onToggleMute,
        )
        FeedActionButton(
            label = stringResource(R.string.video_original_message),
            icon = FeedIconType.EXTERNAL_LINK,
            testTag = VideoFeedTestTags.OriginalLink,
            enabled = interactionEnabled && !originalLinkLoading,
            onClick = onOriginalMessage,
        )
    }
}

@Composable
private fun FeedActionButton(
    label: String,
    icon: FeedIconType,
    testTag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier
                .controlPressScale(
                    interactionSource = interactionSource,
                    enabled = enabled,
                )
                .size(50.dp)
                .background(
                    ChannelVideoFlowTokens.Feed.overlay.copy(
                        alpha = if (enabled) 0.82f else 0.42f,
                    ),
                    CircleShape,
                )
                .border(
                    width = 1.dp,
                    color = ChannelVideoFlowTokens.Feed.outline,
                    shape = CircleShape,
                )
                .testTag(testTag),
        ) {
            FeedIcon(
                type = icon,
                description = label,
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.44f),
            )
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 0.92f else 0.44f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun PausedPlaybackOverlay(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val resumeLabel = stringResource(R.string.video_resume)
    val interactionModifier = if (enabled) {
        Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = resumeLabel }
    } else {
        Modifier.clearAndSetSemantics { }
    }

    Box(
        modifier = Modifier
            .size(76.dp)
            .background(ChannelVideoFlowTokens.Feed.overlay.copy(alpha = 0.78f), CircleShape)
            .border(1.dp, ChannelVideoFlowTokens.Feed.outline, CircleShape)
            .testTag(VideoFeedTestTags.PausedOverlay)
            .then(interactionModifier),
        contentAlignment = Alignment.Center,
    ) {
        FeedIcon(
            type = FeedIconType.PLAY,
            description = resumeLabel,
            tint = Color.White.copy(alpha = 0.78f),
        )
    }
}

@Composable
private fun Modifier.controlPressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressed = enabled && isPressed
    val scale by animateFloatAsState(
        targetValue = if (pressed) CONTROL_PRESSED_SCALE else 1f,
        animationSpec = tween(
            durationMillis = if (pressed) {
                CONTROL_PRESS_IN_MILLIS
            } else {
                CONTROL_PRESS_OUT_MILLIS
            },
        ),
        label = "feed control press scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
private fun PlaybackProgressBar(
    positionMillis: Long,
    durationMillis: Long,
    isSeekable: Boolean,
    onSeek: (Long) -> Unit,
    onScrubbingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isSeekable || durationMillis <= 0L) return

    val safeDuration = durationMillis.coerceAtLeast(1L)
    var widthPx by remember { mutableIntStateOf(0) }
    var scrubPositionMillis by remember(durationMillis) {
        mutableLongStateOf(positionMillis.coerceIn(0L, safeDuration))
    }
    var isScrubbing by remember { mutableStateOf(false) }

    LaunchedEffect(positionMillis, durationMillis, isScrubbing) {
        if (!isScrubbing) {
            scrubPositionMillis = positionMillis.coerceIn(0L, safeDuration)
        }
    }

    fun positionForX(x: Float): Long {
        if (widthPx <= 0) return scrubPositionMillis
        return ((x / widthPx.toFloat()).coerceIn(0f, 1f) * safeDuration)
            .roundToLong()
            .coerceIn(0L, safeDuration)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ChannelVideoFlowTokens.Sizes.touchTarget)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(durationMillis, widthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val dragged = drag(down.id) { change ->
                        if (!isScrubbing) {
                            isScrubbing = true
                            onScrubbingChanged(true)
                        }
                        scrubPositionMillis = positionForX(change.position.x)
                        change.consume()
                    }
                    if (isScrubbing) {
                        isScrubbing = false
                        onScrubbingChanged(false)
                    }
                    if (dragged) {
                        onSeek(scrubPositionMillis.coerceIn(0L, safeDuration))
                    } else {
                        onSeek(positionForX(down.position.x))
                    }
                }
            }
            .testTag(VideoFeedTestTags.Progress),
    ) {
        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(if (isScrubbing) 5.dp else 2.dp),
        ) {
            val trackY = size.height / 2f
            val fraction = (scrubPositionMillis.toFloat() / safeDuration).coerceIn(0f, 1f)
            val activeX = size.width * fraction
            drawLine(
                color = Color.White.copy(alpha = if (isScrubbing) 0.5f else 0.32f),
                start = Offset(0f, trackY),
                end = Offset(size.width, trackY),
                strokeWidth = if (isScrubbing) 4.dp.toPx() else 1.5.dp.toPx(),
                cap = StrokeCap.Butt,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.96f),
                start = Offset(0f, trackY),
                end = Offset(activeX, trackY),
                strokeWidth = if (isScrubbing) 4.dp.toPx() else 1.5.dp.toPx(),
                cap = StrokeCap.Butt,
            )
            drawCircle(
                color = Color.White.copy(alpha = if (isScrubbing) 1f else 0.84f),
                radius = if (isScrubbing) 5.dp.toPx() else 2.5.dp.toPx(),
                center = Offset(activeX, trackY),
            )
        }
        if (isScrubbing) {
            Text(
                text = "${formatPlaybackTime(scrubPositionMillis)} / ${formatPlaybackTime(safeDuration)}",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                color = Color.White.copy(alpha = 0.94f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun formatPlaybackTime(timeMillis: Long): String {
    val totalSeconds = (timeMillis.coerceAtLeast(0L) / 1_000L).toInt()
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3_600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun ProtectedContentWindowEffect(isProtected: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val controller = remember(activity) {
        activity?.window?.let(::WindowSecurityController)
    }

    DisposableEffect(controller, isProtected) {
        controller?.setProtectedContent(isProtected)
        onDispose {
            if (isProtected) controller?.setProtectedContent(false)
        }
    }
}

@Composable
private fun KeepScreenOnEffect(keepScreenOn: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val controller = remember(activity) {
        activity?.window?.let(::WindowScreenOnController)
    }

    DisposableEffect(controller, keepScreenOn) {
        controller?.setKeepScreenOn(keepScreenOn)
        onDispose {
            // Leaving playback controls while still playing must not leave the flag behind.
            if (keepScreenOn) controller?.setKeepScreenOn(false)
        }
    }
}

@Composable
private fun FullscreenSystemUiEffect(isFullscreen: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(activity, lifecycleOwner, isFullscreen) {
        if (activity == null) {
            return@DisposableEffect onDispose {}
        }
        val insetsController = WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView,
        )
        val previousOrientation = activity.requestedOrientation
        val previousLightStatusBars = insetsController.isAppearanceLightStatusBars
        val previousLightNavigationBars = insetsController.isAppearanceLightNavigationBars

        fun applyForegroundState() {
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
            if (isFullscreen) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        fun restoreWindowState() {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            insetsController.isAppearanceLightStatusBars = previousLightStatusBars
            insetsController.isAppearanceLightNavigationBars = previousLightNavigationBars
            activity.requestedOrientation = previousOrientation
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> applyForegroundState()
                Lifecycle.Event.ON_STOP -> restoreWindowState()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            applyForegroundState()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            restoreWindowState()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun IndexedVideo.isLandscapeVideo(): Boolean = width > height && height > 0

/**
 * Single-line text for the collapsed description bar: the caption when it exists, otherwise the
 * tags, flattened so a multi-line caption cannot push the bar taller than one row.
 */
internal fun IndexedVideo.descriptionPreview(): String = caption
    .ifBlank { tags.joinToString(separator = "  ") { tag -> tag.displayName } }
    .replace('\n', ' ')
    .trim()

/**
 * True only while playback is actually running. Pausing, ending the feed or leaving the playback
 * phase must hand the screen back to the system display timeout.
 */
internal fun shouldKeepScreenOn(uiState: VideoPlaybackUiState): Boolean =
    uiState.phase == VideoFeedPhase.CONTENT &&
        uiState.player.isPlaying &&
        !uiState.player.isPaused

private fun VideoPlaybackState.videoKeyOrNull(): VideoKey? = when (this) {
    VideoPlaybackState.Idle -> null
    is VideoPlaybackState.Loading -> video.key
    is VideoPlaybackState.Ready -> video.key
    is VideoPlaybackState.Unsupported -> video.key
    is VideoPlaybackState.Failed -> video.key
}

@Composable
private fun ImmersiveLoadingState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(46.dp)
                .testTag(VideoFeedTestTags.Loading),
            color = Color.White.copy(alpha = 0.82f),
            strokeWidth = 3.dp,
        )
        Text(
            text = stringResource(R.string.video_loading_title),
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.video_loading_message),
            color = Color.White.copy(alpha = 0.46f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ImmersiveVideoLoadingState(
    video: IndexedVideo,
    visible: Boolean,
) {
    val loadingDescription = stringResource(R.string.video_preparing)
    val paletteIndex = videoPosterPaletteIndex(video.key)
    val palette = VIDEO_POSTER_PALETTES[paletteIndex]
    val ambientBrush = remember(paletteIndex) {
        Brush.linearGradient(
            colors = listOf(
                palette.upper,
                palette.base,
                palette.lower,
            ),
        )
    }
    val vignetteBrush = remember(paletteIndex) {
        Brush.radialGradient(
            0f to palette.accent.copy(alpha = 0.20f),
            0.52f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.74f),
        )
    }
    val cardBrush = remember(paletteIndex) {
        Brush.linearGradient(
            colors = listOf(
                palette.accent.copy(alpha = 0.28f),
                palette.base.copy(alpha = 0.98f),
                Color.Black.copy(alpha = 0.62f),
            ),
        )
    }
    val posterAlpha = remember(video.key) {
        Animatable(if (visible) 1f else 0f)
    }
    var showProgress by remember(video.key) { mutableStateOf(false) }

    LaunchedEffect(video.key, visible) {
        showProgress = false
        if (visible) {
            delay(ChannelVideoFlowTokens.Motion.loadingDisclosureMillis)
            showProgress = true
        }
    }

    LaunchedEffect(video.key, visible) {
        if (visible) {
            posterAlpha.snapTo(1f)
        } else if (posterAlpha.value > 0f) {
            posterAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = VIDEO_POSTER_FADE_OUT_MILLIS),
            )
        }
    }

    if (visible || posterAlpha.value > 0f) {
        val renderedAlpha = if (visible) 1f else posterAlpha.value
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = renderedAlpha }
                .background(palette.base)
                .background(ambientBrush)
                .background(vignetteBrush)
                .testTag(VideoFeedTestTags.LoadingPoster)
                .semantics {
                    contentDescription = loadingDescription
                    loadingPosterAlpha = renderedAlpha
                    loadingPosterPalette = paletteIndex
                    loadingPosterVideoIdentity = video.key.posterIdentity()
                },
            contentAlignment = Alignment.Center,
        ) {
            val aspectRatio = video.posterAspectRatio()
            val maximumCardWidth = if (aspectRatio >= 1f) maxWidth * 0.82f else maxWidth * 0.66f
            val maximumCardHeight = maxHeight * 0.56f
            val cardWidth = minOf(maximumCardWidth, maximumCardHeight * aspectRatio)
            val cardHeight = cardWidth / aspectRatio

            Box(
                modifier = Modifier
                    .width(cardWidth)
                    .height(cardHeight)
                    .clip(RoundedCornerShape(26.dp))
                    .background(cardBrush)
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(26.dp),
                    )
                    .clearAndSetSemantics { },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    palette.accent.copy(alpha = 0.58f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.24f),
                                shape = CircleShape,
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.10f),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (showProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(30.dp)
                                    .testTag(VideoFeedTestTags.Loading),
                                color = Color.White.copy(alpha = 0.76f),
                                strokeWidth = 2.5.dp,
                            )
                        } else {
                            Text(
                                text = "CVF",
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(
                        text = loadingDescription,
                        color = Color.White.copy(alpha = 0.94f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.video_preparing_message),
                        color = Color.White.copy(alpha = 0.48f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImmersiveEmptyState(onBack: () -> Unit) {
    ImmersiveStatusState(
        icon = FeedStateIcon.EMPTY,
        title = stringResource(R.string.video_empty_title),
        message = stringResource(R.string.video_empty_message),
        actionLabel = stringResource(R.string.video_back_channels),
        actionTestTag = VideoFeedTestTags.EmptyAction,
        titleTestTag = VideoFeedTestTags.Empty,
        onAction = onBack,
    )
}

@Composable
private fun ImmersiveFeedFailureState(onRetry: () -> Unit) {
    ImmersiveStatusState(
        icon = FeedStateIcon.ERROR,
        title = stringResource(R.string.video_feed_database_error_title),
        message = stringResource(R.string.video_feed_database_error_message),
        actionLabel = stringResource(R.string.video_retry),
        actionTestTag = VideoFeedTestTags.EmptyAction,
        titleTestTag = VideoFeedTestTags.FeedError,
        onAction = onRetry,
    )
}

@Composable
private fun ImmersiveMessageUnavailableState(onBack: () -> Unit) {
    ImmersiveStatusState(
        icon = FeedStateIcon.UNSUPPORTED,
        title = stringResource(R.string.video_message_unavailable_title),
        message = stringResource(R.string.video_message_unavailable_message),
        actionLabel = stringResource(R.string.video_back_channels),
        actionTestTag = VideoFeedTestTags.EmptyAction,
        onAction = onBack,
    )
}

@Composable
private fun ImmersivePlaybackFailure(
    failure: VideoPlaybackFailure,
    onRetry: () -> Unit,
) {
    val presentation = failure.presentation()
    ImmersiveStatusState(
        icon = presentation.icon,
        title = stringResource(presentation.titleRes),
        message = stringResource(presentation.messageRes),
        actionLabel = stringResource(R.string.video_retry),
        actionTestTag = VideoFeedTestTags.Retry,
        onAction = onRetry,
    )
}

@Composable
private fun ImmersiveUnsupportedState(
    onOriginalMessage: () -> Unit,
    linkLoading: Boolean,
) {
    ImmersiveStatusState(
        icon = FeedStateIcon.UNSUPPORTED,
        title = stringResource(R.string.video_streaming_unsupported_title),
        message = stringResource(R.string.video_streaming_unsupported_message),
        actionLabel = stringResource(
            if (linkLoading) R.string.video_opening else R.string.video_open_original,
        ),
        actionTestTag = VideoFeedTestTags.OriginalLink,
        actionEnabled = !linkLoading,
        onAction = onOriginalMessage,
    )
}

@Composable
private fun ImmersiveStatusState(
    icon: FeedStateIcon,
    title: String,
    message: String,
    actionLabel: String,
    actionTestTag: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
    titleTestTag: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeContent)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FeedStateGraphic(icon)
        Spacer(modifier = Modifier.height(30.dp))
        Text(
            text = title,
            modifier = if (titleTestTag == null) Modifier else Modifier.testTag(titleTestTag),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.46f),
            fontSize = 16.sp,
        )
        Spacer(modifier = Modifier.height(54.dp))
        TextButton(
            onClick = onAction,
            enabled = actionEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (actionEnabled) FEED_BUTTON else FEED_BUTTON.copy(alpha = 0.45f),
                )
                .testTag(actionTestTag),
        ) {
            Text(
                text = actionLabel,
                color = Color.White.copy(alpha = if (actionEnabled) 1f else 0.48f),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun FeedStateGraphic(icon: FeedStateIcon) {
    val description = stringResource(icon.descriptionRes)
    Canvas(
        modifier = Modifier
            .size(92.dp)
            .semantics { contentDescription = description },
    ) {
        val color = Color(0xFF3A3A3A)
        val strokeWidth = 8.dp.toPx()
        when (icon) {
            FeedStateIcon.NETWORK -> {
                drawArc(
                    color = color,
                    startAngle = 215f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.08f),
                    size = Size(size.width * 0.84f, size.height * 0.84f),
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color,
                    startAngle = 215f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.24f, size.height * 0.28f),
                    size = Size(size.width * 0.52f, size.height * 0.52f),
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color,
                    startAngle = 215f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.37f, size.height * 0.48f),
                    size = Size(size.width * 0.26f, size.height * 0.26f),
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
                drawCircle(
                    color = color,
                    radius = size.width * 0.075f,
                    center = Offset(size.width * 0.5f, size.height * 0.78f),
                )
                drawLine(
                    color = Color.Black,
                    start = Offset(size.width * 0.5f, size.height * 0.02f),
                    end = Offset(size.width * 0.5f, size.height * 0.67f),
                    strokeWidth = 5.dp.toPx(),
                )
            }
            FeedStateIcon.EMPTY -> {
                drawCircle(
                    color = color,
                    radius = size.width * 0.39f,
                    style = Stroke(strokeWidth),
                )
                val play = Path().apply {
                    moveTo(size.width * 0.42f, size.height * 0.33f)
                    lineTo(size.width * 0.70f, size.height * 0.50f)
                    lineTo(size.width * 0.42f, size.height * 0.67f)
                    close()
                }
                drawPath(play, color)
            }
            FeedStateIcon.UNSUPPORTED -> {
                drawCircle(
                    color = color,
                    radius = size.width * 0.39f,
                    style = Stroke(strokeWidth),
                )
                val play = Path().apply {
                    moveTo(size.width * 0.42f, size.height * 0.33f)
                    lineTo(size.width * 0.70f, size.height * 0.50f)
                    lineTo(size.width * 0.42f, size.height * 0.67f)
                    close()
                }
                drawPath(play, color)
                drawLine(
                    color = Color.Black,
                    start = Offset(size.width * 0.22f, size.height * 0.22f),
                    end = Offset(size.width * 0.78f, size.height * 0.78f),
                    strokeWidth = 9.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.20f, size.height * 0.20f),
                    end = Offset(size.width * 0.80f, size.height * 0.80f),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            FeedStateIcon.ERROR -> {
                drawCircle(
                    color = color,
                    radius = size.width * 0.39f,
                    style = Stroke(strokeWidth),
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.5f, size.height * 0.29f),
                    end = Offset(size.width * 0.5f, size.height * 0.58f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = color,
                    radius = size.width * 0.05f,
                    center = Offset(size.width * 0.5f, size.height * 0.72f),
                )
            }
        }
    }
}

@Composable
private fun FeedIcon(
    type: FeedIconType,
    description: String,
    tint: Color = Color.White,
    iconSize: Dp = 24.dp,
) {
    Canvas(
        modifier = Modifier
            .size(iconSize)
            .semantics { contentDescription = description },
    ) {
        val strokeWidth = 2.1.dp.toPx()
        when (type) {
            FeedIconType.BACK -> {
                drawLine(
                    tint,
                    Offset(size.width * 0.67f, size.height * 0.18f),
                    Offset(size.width * 0.31f, size.height * 0.50f),
                    strokeWidth,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(size.width * 0.31f, size.height * 0.50f),
                    Offset(size.width * 0.67f, size.height * 0.82f),
                    strokeWidth,
                    StrokeCap.Round,
                )
            }
            FeedIconType.PLAY -> {
                val path = Path().apply {
                    moveTo(size.width * 0.34f, size.height * 0.22f)
                    lineTo(size.width * 0.76f, size.height * 0.50f)
                    lineTo(size.width * 0.34f, size.height * 0.78f)
                    close()
                }
                drawPath(path, tint)
            }
            FeedIconType.PAUSE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.28f, size.height * 0.22f),
                    size = Size(size.width * 0.15f, size.height * 0.56f),
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.57f, size.height * 0.22f),
                    size = Size(size.width * 0.15f, size.height * 0.56f),
                )
            }
            FeedIconType.VOLUME,
            FeedIconType.MUTED,
            -> {
                val speaker = Path().apply {
                    moveTo(size.width * 0.20f, size.height * 0.42f)
                    lineTo(size.width * 0.37f, size.height * 0.42f)
                    lineTo(size.width * 0.54f, size.height * 0.26f)
                    lineTo(size.width * 0.54f, size.height * 0.74f)
                    lineTo(size.width * 0.37f, size.height * 0.58f)
                    lineTo(size.width * 0.20f, size.height * 0.58f)
                    close()
                }
                drawPath(speaker, tint)
                if (type == FeedIconType.VOLUME) {
                    drawArc(
                        color = tint,
                        startAngle = -46f,
                        sweepAngle = 92f,
                        useCenter = false,
                        topLeft = Offset(size.width * 0.46f, size.height * 0.26f),
                        size = Size(size.width * 0.34f, size.height * 0.48f),
                        style = Stroke(strokeWidth, cap = StrokeCap.Round),
                    )
                } else {
                    drawLine(
                        tint,
                        Offset(size.width * 0.64f, size.height * 0.37f),
                        Offset(size.width * 0.84f, size.height * 0.63f),
                        strokeWidth,
                        StrokeCap.Round,
                    )
                    drawLine(
                        tint,
                        Offset(size.width * 0.84f, size.height * 0.37f),
                        Offset(size.width * 0.64f, size.height * 0.63f),
                        strokeWidth,
                        StrokeCap.Round,
                    )
                }
            }
            FeedIconType.EXTERNAL_LINK -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.16f, size.height * 0.33f),
                    size = Size(size.width * 0.52f, size.height * 0.51f),
                    style = Stroke(strokeWidth),
                )
                drawLine(
                    tint,
                    Offset(size.width * 0.44f, size.height * 0.56f),
                    Offset(size.width * 0.82f, size.height * 0.18f),
                    strokeWidth,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(size.width * 0.58f, size.height * 0.18f),
                    Offset(size.width * 0.82f, size.height * 0.18f),
                    strokeWidth,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(size.width * 0.82f, size.height * 0.18f),
                    Offset(size.width * 0.82f, size.height * 0.42f),
                    strokeWidth,
                    StrokeCap.Round,
                )
            }
            FeedIconType.FULLSCREEN,
            FeedIconType.EXIT_FULLSCREEN,
            -> {
                val outer = if (type == FeedIconType.FULLSCREEN) 0.16f else 0.34f
                val inner = if (type == FeedIconType.FULLSCREEN) 0.38f else 0.16f
                listOf(
                    Triple(
                        Offset(size.width * outer, size.height * inner),
                        Offset(size.width * outer, size.height * outer),
                        Offset(size.width * inner, size.height * outer),
                    ),
                    Triple(
                        Offset(size.width * (1f - outer), size.height * inner),
                        Offset(size.width * (1f - outer), size.height * outer),
                        Offset(size.width * (1f - inner), size.height * outer),
                    ),
                    Triple(
                        Offset(size.width * outer, size.height * (1f - inner)),
                        Offset(size.width * outer, size.height * (1f - outer)),
                        Offset(size.width * inner, size.height * (1f - outer)),
                    ),
                    Triple(
                        Offset(size.width * (1f - outer), size.height * (1f - inner)),
                        Offset(size.width * (1f - outer), size.height * (1f - outer)),
                        Offset(size.width * (1f - inner), size.height * (1f - outer)),
                    ),
                ).forEach { (start, corner, end) ->
                    drawLine(tint, start, corner, strokeWidth, StrokeCap.Round)
                    drawLine(tint, corner, end, strokeWidth, StrokeCap.Round)
                }
            }
            FeedIconType.LOGOUT -> {
                drawLine(
                    tint,
                    Offset(size.width * 0.26f, size.height * 0.20f),
                    Offset(size.width * 0.26f, size.height * 0.80f),
                    strokeWidth,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(size.width * 0.26f, size.height * 0.20f),
                    Offset(size.width * 0.52f, size.height * 0.20f),
                    strokeWidth,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(size.width * 0.26f, size.height * 0.80f),
                    Offset(size.width * 0.52f, size.height * 0.80f),
                    strokeWidth,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(size.width * 0.46f, size.height * 0.50f),
                    Offset(size.width * 0.82f, size.height * 0.50f),
                    strokeWidth,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(size.width * 0.70f, size.height * 0.38f),
                    Offset(size.width * 0.82f, size.height * 0.50f),
                    strokeWidth,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(size.width * 0.82f, size.height * 0.50f),
                    Offset(size.width * 0.70f, size.height * 0.62f),
                    strokeWidth,
                    StrokeCap.Round,
                )
            }
            FeedIconType.CLOSE -> {
                drawLine(
                    tint,
                    Offset(size.width * 0.24f, size.height * 0.24f),
                    Offset(size.width * 0.76f, size.height * 0.76f),
                    strokeWidth,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(size.width * 0.76f, size.height * 0.24f),
                    Offset(size.width * 0.24f, size.height * 0.76f),
                    strokeWidth,
                    StrokeCap.Round,
                )
            }
        }
    }
}

private fun VideoPlaybackFailure.presentation(): FailurePresentation = when (this) {
    VideoPlaybackFailure.NETWORK -> FailurePresentation(
        icon = FeedStateIcon.NETWORK,
        titleRes = R.string.video_failure_network_title,
        messageRes = R.string.video_failure_network_message,
    )
    VideoPlaybackFailure.TIMEOUT -> FailurePresentation(
        icon = FeedStateIcon.NETWORK,
        titleRes = R.string.video_failure_timeout_title,
        messageRes = R.string.video_failure_timeout_message,
    )
    VideoPlaybackFailure.FILE_UNAVAILABLE -> FailurePresentation(
        icon = FeedStateIcon.ERROR,
        titleRes = R.string.video_failure_file_title,
        messageRes = R.string.video_failure_file_message,
    )
    VideoPlaybackFailure.MESSAGE_UNAVAILABLE -> FailurePresentation(
        icon = FeedStateIcon.UNSUPPORTED,
        titleRes = R.string.video_message_unavailable_title,
        messageRes = R.string.video_message_unavailable_message,
    )
    VideoPlaybackFailure.DECODER_UNSUPPORTED -> FailurePresentation(
        icon = FeedStateIcon.UNSUPPORTED,
        titleRes = R.string.video_failure_decoder_title,
        messageRes = R.string.video_failure_decoder_message,
    )
    VideoPlaybackFailure.PLAYER -> FailurePresentation(
        icon = FeedStateIcon.ERROR,
        titleRes = R.string.video_failure_player_title,
        messageRes = R.string.video_failure_player_message,
    )
    VideoPlaybackFailure.UNKNOWN -> FailurePresentation(
        icon = FeedStateIcon.ERROR,
        titleRes = R.string.video_failure_player_title,
        messageRes = R.string.video_failure_unknown_message,
    )
}

private data class FailurePresentation(
    val icon: FeedStateIcon,
    val titleRes: Int,
    val messageRes: Int,
)

private enum class FeedStateIcon(val descriptionRes: Int) {
    EMPTY(R.string.video_state_empty_description),
    NETWORK(R.string.video_state_network_description),
    UNSUPPORTED(R.string.video_state_unsupported_description),
    ERROR(R.string.video_state_error_description),
}

private enum class FeedIconType {
    BACK,
    PLAY,
    PAUSE,
    VOLUME,
    MUTED,
    EXTERNAL_LINK,
    FULLSCREEN,
    EXIT_FULLSCREEN,
    LOGOUT,
    CLOSE,
}

private fun formatPublishTime(epochSeconds: Long): String = PUBLISH_TIME_FORMATTER.format(
    Instant.ofEpochSecond(epochSeconds),
)

internal fun videoPosterPaletteIndex(videoKey: VideoKey): Int {
    val foldedChatId = videoKey.chatId xor (videoKey.chatId ushr Int.SIZE_BITS)
    val foldedMessageId = videoKey.messageId xor (videoKey.messageId ushr Int.SIZE_BITS)
    val combined = foldedChatId * 31L + foldedMessageId * 17L
    return Math.floorMod(combined, VIDEO_POSTER_PALETTES.size.toLong()).toInt()
}

private fun VideoKey.posterIdentity(): String = "$chatId:$messageId"

private fun IndexedVideo.posterAspectRatio(): Float = when {
    width <= 0 || height <= 0 -> 1f
    else -> (width.toFloat() / height.toFloat()).coerceIn(0.52f, 1.85f)
}

private data class VideoPosterPalette(
    val base: Color,
    val upper: Color,
    val lower: Color,
    val accent: Color,
)

private val VIDEO_POSTER_PALETTES = listOf(
    VideoPosterPalette(
        base = Color(0xFF121B20),
        upper = Color(0xFF1C2930),
        lower = Color(0xFF0C1114),
        accent = Color(0xFF78919A),
    ),
    VideoPosterPalette(
        base = Color(0xFF191920),
        upper = Color(0xFF292834),
        lower = Color(0xFF101015),
        accent = Color(0xFF8B8396),
    ),
    VideoPosterPalette(
        base = Color(0xFF151C19),
        upper = Color(0xFF23302A),
        lower = Color(0xFF0D120F),
        accent = Color(0xFF7F9588),
    ),
    VideoPosterPalette(
        base = Color(0xFF1E1917),
        upper = Color(0xFF302722),
        lower = Color(0xFF120F0D),
        accent = Color(0xFF99877C),
    ),
    VideoPosterPalette(
        base = Color(0xFF151A22),
        upper = Color(0xFF222C3B),
        lower = Color(0xFF0D1015),
        accent = Color(0xFF7F8EAA),
    ),
    VideoPosterPalette(
        base = Color(0xFF1B171A),
        upper = Color(0xFF2E252A),
        lower = Color(0xFF110E10),
        accent = Color(0xFF987F8B),
    ),
)

private val PUBLISH_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())

private val FEED_ACCENT = Color(0xFF68E3E0)
private val FEED_BUTTON = Color(0xFF292929)

/**
 * Bottom stack, measured from the safe bottom inset upwards:
 * progress bar touch area -> progress track (centred in that area) -> collapsed description bar.
 * The touch area starts further up than the previous 16dp so a drag cannot start on the system
 * gesture strip, and the description bar clears the progress touch area completely.
 */
private val PROGRESS_BOTTOM_OFFSET = 32.dp
private val METADATA_BOTTOM_OFFSET =
    PROGRESS_BOTTOM_OFFSET + ChannelVideoFlowTokens.Sizes.touchTarget + 8.dp
private val ACTION_RAIL_BOTTOM_OFFSET = 162.dp
private val VIDEO_METADATA_MAX_WIDTH = 600.dp
private val METADATA_SUMMARY_SHAPE = RoundedCornerShape(14.dp)
private const val BOTTOM_SCRIM_HEIGHT_FRACTION = 0.34f
private const val EXPANDED_CAPTION_MAX_LINES = 6
private const val METADATA_PANEL_ANIMATION_MILLIS = 220

private const val METADATA_INTERACTION_ALPHA = 0.30f
private const val METADATA_FADE_OUT_MILLIS = 90
private const val METADATA_FADE_IN_MILLIS = 220
private const val METADATA_RESTORE_DELAY_MILLIS = 320L

private const val CONTROL_PRESSED_SCALE = 0.985f
private const val CONTROL_PRESS_IN_MILLIS = 90
private const val CONTROL_PRESS_OUT_MILLIS = 120
private const val CONTROL_AUTO_HIDE_MILLIS = 3_000L
private const val CONTROL_VISIBILITY_ANIMATION_MILLIS = 160
private const val PAUSED_OVERLAY_INITIAL_SCALE = 0.92f
private const val PAUSED_OVERLAY_ANIMATION_MILLIS = 200
private const val SWIPE_HINT_ENTRANCE_MILLIS = 360
private val TEMPORARY_SPEED_TOP_PADDING = 68.dp
private const val TEMPORARY_SPEED_ANIMATION_MILLIS = 180
private const val TEMPORARY_SPEED_INITIAL_SCALE = 0.94f

internal const val VIDEO_POSTER_FADE_OUT_MILLIS = 190

private const val RANDOM_PAGER_PAGE_COUNT = 2_000_000
private const val RANDOM_PAGER_CENTER = RANDOM_PAGER_PAGE_COUNT / 2

private fun randomPagerStart(itemCount: Int): Int =
    RANDOM_PAGER_CENTER - (RANDOM_PAGER_CENTER % itemCount.coerceAtLeast(1))

private fun logicalPage(page: Int, itemCount: Int): Int = page % itemCount.coerceAtLeast(1)

private fun monotonicTimeMillis(): Long = System.nanoTime() / 1_000_000L
