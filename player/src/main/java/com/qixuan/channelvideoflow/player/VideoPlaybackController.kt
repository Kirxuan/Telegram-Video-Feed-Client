package com.qixuan.channelvideoflow.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoFeedOrder
import com.qixuan.channelvideoflow.model.video.VideoKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

data class VideoPlayerSnapshot(
    val playbackState: VideoPlaybackState = VideoPlaybackState.Idle,
    val isPaused: Boolean = false,
    val isMuted: Boolean = false,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val bufferedPositionMillis: Long = 0L,
    val isSeekable: Boolean = false,
    val hasRenderedFirstFrame: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = VideoPlaybackSpeeds.NORMAL,
    /** True only after the active binding reports Media3's actual end of media. */
    val hasEnded: Boolean = false,
)

object VideoPlaybackSpeeds {
    const val NORMAL = 1.0f
    const val TEMPORARY_FAST_FORWARD = 2.0f
}

enum class PlaybackTransitionDirection {
    INITIAL,
    FORWARD,
    REVERSE,
    UNCHANGED,
}

sealed interface PlaybackTransitionEvent {
    data class GestureStarted(
        val observedAtMillis: Long,
    ) : PlaybackTransitionEvent

    data class GestureReleased(
        val observedAtMillis: Long,
    ) : PlaybackTransitionEvent

    data class TargetKnown(
        val key: VideoKey,
        val order: VideoFeedOrder? = null,
        val direction: PlaybackTransitionDirection? = null,
        val randomRoundBoundary: Boolean? = null,
    ) : PlaybackTransitionEvent

    data object TargetAbandoned : PlaybackTransitionEvent

    data class PlanPreparationStarted(
        val key: VideoKey,
    ) : PlaybackTransitionEvent

    data class PlanPrepared(
        val key: VideoKey,
    ) : PlaybackTransitionEvent

    /** Legacy boundary retained for initial-page and non-pointer callers. */
    data object PageUnstable : PlaybackTransitionEvent

    data class PageSettled(
        val key: VideoKey,
        val order: VideoFeedOrder? = null,
        val direction: PlaybackTransitionDirection? = null,
        val randomRoundBoundary: Boolean? = null,
    ) : PlaybackTransitionEvent

    data class PlanStarted(
        val key: VideoKey,
        val promoted: Boolean = false,
        val planAgeMillis: Long? = null,
        val preparedRefreshOutcome: PlaybackPlanRefreshOutcome? = null,
        val preparedRefreshMillis: Long? = null,
    ) : PlaybackTransitionEvent

    data class RefreshStarted(
        val key: VideoKey,
    ) : PlaybackTransitionEvent

    data class RefreshFinished(
        val key: VideoKey,
        val outcome: PlaybackPlanRefreshOutcome,
    ) : PlaybackTransitionEvent

    data class TransparentRecoveryStarted(
        val key: VideoKey,
    ) : PlaybackTransitionEvent

    data class TransparentRecoveryFinished(
        val key: VideoKey,
        val outcome: TransparentRecoveryOutcome,
    ) : PlaybackTransitionEvent
}

enum class PlaybackPlanRefreshOutcome {
    SUCCESS,
    FALLBACK,
    SKIPPED,
}

enum class TransparentRecoveryOutcome {
    REBOUND,
    SOFT_TIMEOUT,
    UNAVAILABLE,
    MESSAGE_UNAVAILABLE,
    STALE_REFERENCE,
    REFRESHED_FILE_UNAVAILABLE,
}

/** Exact in-memory context shared by plan preparation and the final stable bind. */
data class PlaybackPreparationContext(
    val qualityGeneration: Long,
    val accountGeneration: Long,
    val networkGeneration: Long,
    val queueGeneration: Long,
    val roundGeneration: Long?,
)

/** ViewModel-facing contract for application-owned playback resources. */
@UnstableApi
interface VideoPlaybackController {
    val snapshot: StateFlow<VideoPlayerSnapshot>

    /** True only for the explicitly selected, physical-device playback-pool experiment. */
    val supportsStandbyPreparation: Boolean
        get() = false

    /**
     * Records a sanitized, in-memory transition boundary for Debug performance diagnostics.
     *
     * Implementations that do not collect diagnostics may keep the default no-op behavior.
     */
    fun recordTransition(event: PlaybackTransitionEvent) = Unit

    fun attach(playerView: PlayerView)

    fun detach(playerView: PlayerView)

    fun bind(video: IndexedVideo)

    /** Prepares exactly one bounded next item when the pool experiment is enabled. */
    fun bind(video: IndexedVideo, context: PlaybackPreparationContext) = bind(video)

    fun prepareStandby(video: IndexedVideo, context: PlaybackPreparationContext): Boolean = false

    fun discardStandby() = Unit

    /** Publishes an app-resolved terminal failure without creating a media binding. */
    fun showFailure(video: IndexedVideo, failure: VideoPlaybackFailure) = Unit

    /** Completes a FILE_UNAVAILABLE transition after its one recovery opportunity fails. */
    fun finishFileRecoveryFailure(key: VideoKey) = Unit

    fun retry()

    fun pause()

    fun resume()

    fun seekTo(positionMillis: Long)

    fun pauseForPageTransition()

    fun setMuted(muted: Boolean)

    /** Applies or safely clears the press-and-hold speed on the current shared player. */
    fun setTemporaryPlaybackSpeed(active: Boolean) = Unit

    fun onAppBackgrounded()

    fun releaseBinding()

    /** Releases the application-owned ExoPlayer and all of its current binding resources. */
    fun release()
}

@Module
@InstallIn(SingletonComponent::class)
@UnstableApi
internal abstract class VideoPlaybackModule {
    @Binds
    @Singleton
    abstract fun bindVideoPlaybackController(
        implementation: VideoPlayerManager,
    ): VideoPlaybackController
}
