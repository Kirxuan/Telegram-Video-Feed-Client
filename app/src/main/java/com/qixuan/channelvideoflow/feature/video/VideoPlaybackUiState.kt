package com.qixuan.channelvideoflow.feature.video

import com.qixuan.channelvideoflow.model.video.DEFAULT_VIDEO_FEED_ORDER
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoFeedOrder
import com.qixuan.channelvideoflow.model.video.VideoKey
import com.qixuan.channelvideoflow.player.VideoPlayerSnapshot
import com.qixuan.channelvideoflow.domain.message.RepositoryObservationFailure

enum class VideoFeedPhase {
    LOADING,
    EMPTY,
    CONTENT,
    ERROR,
}

data class FeedVideoItem(
    val video: IndexedVideo,
    val channelTitle: String,
)

sealed interface OriginalMessageLinkUiState {
    data object Idle : OriginalMessageLinkUiState
    data object Loading : OriginalMessageLinkUiState
    data class Unavailable(
        val message: String,
    ) : OriginalMessageLinkUiState
}

data class VideoPlaybackUiState(
    val phase: VideoFeedPhase = VideoFeedPhase.LOADING,
    /** Lightweight logical order. Full [items] are intentionally limited to the hydration window. */
    val feedKeys: List<VideoKey> = emptyList(),
    val items: List<FeedVideoItem> = emptyList(),
    val upcomingKeys: List<VideoKey> = emptyList(),
    val upcomingItems: List<FeedVideoItem> = emptyList(),
    val feedGeneration: Long = 0L,
    val hydrationGeneration: Long = 0L,
    val order: VideoFeedOrder = DEFAULT_VIDEO_FEED_ORDER,
    val queueGeneration: Long = 0L,
    val randomRoundStartPagerPage: Int? = null,
    val currentPage: Int = 0,
    val player: VideoPlayerSnapshot = VideoPlayerSnapshot(),
    val originalPlaybackAwaitingConfirmation: IndexedVideo? = null,
    val showSwipeHint: Boolean = false,
    val originalMessageLink: OriginalMessageLinkUiState = OriginalMessageLinkUiState.Idle,
    val feedFailure: RepositoryObservationFailure? = null,
)

/** High-frequency playback values collected only by the progress control. */
data class VideoPlaybackProgressUiState(
    val key: VideoKey? = null,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val bufferedPositionMillis: Long = 0L,
    val isSeekable: Boolean = false,
)
