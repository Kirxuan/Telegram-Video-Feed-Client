package com.qixuan.channelvideoflow.domain.message

import com.qixuan.channelvideoflow.model.video.ChannelVideoScanProgress
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.OriginalMessageLinkResult
import com.qixuan.channelvideoflow.model.video.TagSummary
import com.qixuan.channelvideoflow.model.video.VideoFilter
import com.qixuan.channelvideoflow.model.video.VideoKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class RepositoryObservationFailure {
    DATABASE,
    UNKNOWN,
}

data class RepositoryObservation<T>(
    val value: T,
    val failure: RepositoryObservationFailure? = null,
) {
    val isFailure: Boolean get() = failure != null
}

data class VideoFeedKeySnapshot(
    val key: VideoKey,
    val publishTime: Long,
    val editTime: Long?,
)

interface TelegramMessageRepository {
    val scanProgress: Flow<List<ChannelVideoScanProgress>>

    val scanProgressObservation: Flow<RepositoryObservation<List<ChannelVideoScanProgress>>>
        get() = scanProgress.map { value -> RepositoryObservation(value) }

    fun observeVideos(filter: VideoFilter): Flow<List<IndexedVideo>>

    fun observeVideoObservation(
        filter: VideoFilter,
    ): Flow<RepositoryObservation<List<IndexedVideo>>> =
        observeVideos(filter).map { value -> RepositoryObservation(value) }

    fun observeVideoKeyObservation(
        filter: VideoFilter,
    ): Flow<RepositoryObservation<List<VideoFeedKeySnapshot>>> =
        observeVideoObservation(filter).map { observation ->
            RepositoryObservation(
                value = observation.value.map { video ->
                    VideoFeedKeySnapshot(video.key, video.publishTime, video.editTime)
                },
                failure = observation.failure,
            )
        }

    suspend fun hydrateVideos(keys: List<VideoKey>): RepositoryObservation<List<IndexedVideo>> =
        RepositoryObservation(emptyList(), RepositoryObservationFailure.UNKNOWN)

    fun observeTags(channelIds: Set<Long>): Flow<List<TagSummary>>

    fun observeTagObservation(
        channelIds: Set<Long>,
    ): Flow<RepositoryObservation<List<TagSummary>>> =
        observeTags(channelIds).map { value -> RepositoryObservation(value) }

    /**
     * Re-resolves the current Telegram message into an app-owned result that
     * distinguishes a fresh reference, a terminal message change, and a
     * transient request failure.
     */
    suspend fun refreshVideo(videoKey: VideoKey): VideoReferenceResolution

    suspend fun getOriginalMessageLink(videoKey: VideoKey): OriginalMessageLinkResult

    suspend fun setForeground(isForeground: Boolean)

    suspend fun refreshSelection()

    suspend fun pauseScanning()

    suspend fun resumeScanning()
}

/** App-owned result of resolving a Telegram message into its current video reference. */
sealed interface VideoReferenceResolution {
    data class Resolved(val video: IndexedVideo) : VideoReferenceResolution

    /** The message no longer exists and its Room row has been marked deleted. */
    data object MessageMissing : VideoReferenceResolution

    /** The message exists but is no longer an ordinary messageVideo. */
    data object UnsupportedMessage : VideoReferenceResolution

    /** A transient/request failure for which the indexed reference remains a safe fallback. */
    data class Unavailable(
        val failure: VideoReferenceFailure,
    ) : VideoReferenceResolution
}

sealed interface VideoReferenceFailure {
    data object Network : VideoReferenceFailure
    data class FloodWait(val retryAfterSeconds: Int) : VideoReferenceFailure
    data object Timeout : VideoReferenceFailure
    data object SessionUnavailable : VideoReferenceFailure
    data object AccessLost : VideoReferenceFailure
    data class RequestRejected(val code: Int) : VideoReferenceFailure
    data object Unknown : VideoReferenceFailure
}
