package com.qixuan.channelvideoflow.feature.video

import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoFeedOrder
import com.qixuan.channelvideoflow.model.video.VideoKey
import com.qixuan.channelvideoflow.model.video.VideoTag
import com.qixuan.channelvideoflow.player.VideoPlaybackState
import com.qixuan.channelvideoflow.player.VideoPlayerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataSummaryContractTest {
    @Test
    fun descriptionPreviewPrefersTheCaptionAndFlattensLineBreaks() {
        val withCaption = video(
            caption = "第一行\n第二行",
            tags = listOf(VideoTag("tag", "#标签")),
        )
        assertEquals("第一行 第二行", withCaption.descriptionPreview())

        val withoutCaption = video(caption = "", tags = listOf(VideoTag("tag", "#标签")))
        assertEquals("#标签", withoutCaption.descriptionPreview())

        val empty = video(caption = "", tags = emptyList())
        assertEquals("", empty.descriptionPreview())
    }

    @Test
    fun keepScreenOnFollowsPlaybackIntentOnly() {
        assertTrue(shouldKeepScreenOn(uiState(isPlaying = true, isPaused = false)))
        assertFalse(shouldKeepScreenOn(uiState(isPlaying = false, isPaused = true)))
        assertFalse(shouldKeepScreenOn(uiState(isPlaying = false, isPaused = false)))
        assertFalse(shouldKeepScreenOn(uiState(isPlaying = true, isPaused = true)))
        assertFalse(
            shouldKeepScreenOn(
                uiState(isPlaying = true, isPaused = false).copy(phase = VideoFeedPhase.LOADING),
            ),
        )
    }

    private fun uiState(
        isPlaying: Boolean,
        isPaused: Boolean,
    ) = VideoPlaybackUiState(
        phase = VideoFeedPhase.CONTENT,
        items = listOf(FeedVideoItem(video(caption = "视频说明"), "测试频道")),
        order = VideoFeedOrder.LATEST,
        player = VideoPlayerSnapshot(
            playbackState = VideoPlaybackState.Ready(
                video = video(caption = "视频说明"),
                firstReadyWaitMillis = null,
                observedLocalBytes = null,
            ),
            isPlaying = isPlaying,
            isPaused = isPaused,
        ),
    )

    private fun video(
        caption: String,
        tags: List<VideoTag> = emptyList(),
    ) = IndexedVideo(
        key = VideoKey(1, 1),
        fileId = 1,
        remoteUniqueId = "remote-1",
        caption = caption,
        supportsStreaming = true,
        fileSize = 1,
        durationSeconds = 1,
        width = 1_080,
        height = 1_920,
        publishTime = 1L,
        editTime = null,
        canBeSaved = true,
        tags = tags,
    )
}
