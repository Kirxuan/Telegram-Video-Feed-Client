package com.qixuan.channelvideoflow.feature.channels

import com.qixuan.channelvideoflow.model.channel.TelegramChatFailure
import com.qixuan.channelvideoflow.model.video.TelegramMessageFailure
import com.qixuan.channelvideoflow.model.video.VideoScanStatus

enum class ChannelListPhase {
    LOADING,
    CONTENT,
    EMPTY,
    ERROR,
}

data class ChannelSelectionItem(
    val chatId: Long,
    val title: String,
    val username: String?,
    val isSelected: Boolean,
    val isPinned: Boolean = false,
    val scanStatus: VideoScanStatus? = null,
    val processedVideoCandidateCount: Long = 0,
    val indexedVideoCount: Int = 0,
    val videoSearchPageCount: Int = 0,
)

data class ChannelScanSummary(
    val duplicateVideoEncounterCount: Long = 0,
    val processedVideoCandidateCount: Long = 0,
    val videoSearchPageCount: Int = 0,
    val indexedVideoCount: Int = 0,
    val approximateVideoCount: Int? = null,
    val completedChannelCount: Int = 0,
    val totalChannelCount: Int = 0,
    val isPaused: Boolean = false,
    val canControl: Boolean = false,
    val retrySecondsRemaining: Int = 0,
    val failure: TelegramMessageFailure? = null,
)

sealed interface ChannelSaveStatus {
    data object Idle : ChannelSaveStatus
    data object Saving : ChannelSaveStatus
    data class Saved(val count: Int) : ChannelSaveStatus
    data object Failed : ChannelSaveStatus
}

data class ChannelSelectionUiState(
    val phase: ChannelListPhase = ChannelListPhase.LOADING,
    val searchQuery: String = "",
    val channels: List<ChannelSelectionItem> = emptyList(),
    val selectedCount: Int = 0,
    val canSave: Boolean = false,
    val isRefreshing: Boolean = false,
    val failure: TelegramChatFailure? = null,
    val retrySecondsRemaining: Int = 0,
    val saveStatus: ChannelSaveStatus = ChannelSaveStatus.Idle,
    val scanSummary: ChannelScanSummary = ChannelScanSummary(),
)
