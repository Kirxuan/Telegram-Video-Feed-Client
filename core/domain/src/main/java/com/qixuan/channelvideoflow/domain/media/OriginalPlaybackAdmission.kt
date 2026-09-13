package com.qixuan.channelvideoflow.domain.media

import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoQualityPreference

/** User-authorized mobile-data guard, shared by playback and speculative preparation. */
object OriginalPlaybackAdmission {
    const val CONFIRM_ABOVE_BYTES = 200L * 1024L * 1024L

    fun requiresConfirmation(
        video: IndexedVideo,
        preference: VideoQualityPreference,
        network: NetworkTransport,
    ): Boolean = video.supportsStreaming &&
        network == NetworkTransport.MOBILE &&
        preference in setOf(VideoQualityPreference.AUTO, VideoQualityPreference.DATA_SAVER) &&
        video.selectedAlternative == null &&
        (video.playbackFileSize ?: 0L) > CONFIRM_ABOVE_BYTES
}
