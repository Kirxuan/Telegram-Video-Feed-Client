package com.qixuan.channelvideoflow.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qixuan.channelvideoflow.domain.channel.TelegramChatRepository
import com.qixuan.channelvideoflow.domain.message.TelegramMessageRepository
import com.qixuan.channelvideoflow.domain.message.RepositoryObservationFailure
import com.qixuan.channelvideoflow.model.channel.TelegramChannel
import com.qixuan.channelvideoflow.model.channel.TelegramChatFailure
import com.qixuan.channelvideoflow.model.channel.TelegramChatSyncState
import com.qixuan.channelvideoflow.model.video.ChannelVideoScanProgress
import com.qixuan.channelvideoflow.model.video.TelegramMessageFailure
import com.qixuan.channelvideoflow.model.video.VideoScanStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@HiltViewModel
class ChannelSelectionViewModel @Inject constructor(
    private val repository: TelegramChatRepository,
    private val messageRepository: TelegramMessageRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ChannelSelectionUiState())
    val uiState: StateFlow<ChannelSelectionUiState> = mutableUiState.asStateFlow()

    private var allChannels = emptyList<TelegramChannel>()
    private var syncState: TelegramChatSyncState = TelegramChatSyncState.Loading
    private var draftSelectedIds = emptySet<Long>()
    private var selectionInitialized = false
    private var selectionDirty = false
    private var searchQuery = ""
    private var saveStatus: ChannelSaveStatus = ChannelSaveStatus.Idle
    private var refreshJob: Job? = null
    private var floodWaitJob: Job? = null
    private var retrySecondsRemaining = 0
    private var scanProgress = emptyList<ChannelVideoScanProgress>()
    private var scanObservationFailure: RepositoryObservationFailure? = null
    private var scanCountdownJob: Job? = null
    private var foregroundJob: Job? = null

    init {
        viewModelScope.launch {
            repository.channels.collect { channels ->
                allChannels = channels
                val availableIds = channels.mapTo(mutableSetOf(), TelegramChannel::chatId)
                if (!selectionInitialized || !selectionDirty) {
                    draftSelectedIds = channels
                        .asSequence()
                        .filter(TelegramChannel::isSelected)
                        .map(TelegramChannel::chatId)
                        .toSet()
                    selectionInitialized = true
                } else {
                    draftSelectedIds = draftSelectedIds.intersect(availableIds)
                }
                rebuildUiState()
            }
        }
        viewModelScope.launch {
            repository.syncState.collect { state ->
                syncState = state
                applyFloodWait(state)
                rebuildUiState()
            }
        }
        viewModelScope.launch {
            messageRepository.scanProgressObservation.collect { observation ->
                scanProgress = observation.value
                scanObservationFailure = observation.failure
                restartScanCountdown()
                rebuildUiState()
            }
        }
        refresh()
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery = query
        rebuildUiState()
    }

    fun toggleChannel(chatId: Long) {
        if (allChannels.none { it.chatId == chatId }) return
        draftSelectedIds = if (chatId in draftSelectedIds) {
            draftSelectedIds - chatId
        } else {
            draftSelectedIds + chatId
        }
        selectionDirty = true
        saveStatus = ChannelSaveStatus.Idle
        rebuildUiState()
    }

    fun toggleChannelPinned(chatId: Long) {
        val channel = allChannels.firstOrNull { it.chatId == chatId } ?: return
        viewModelScope.launch {
            try {
                repository.setChannelPinned(chatId, !channel.isPinned)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The repository exposes a sanitized database failure through syncState.
            }
        }
    }

    fun saveSelection() {
        if (saveStatus == ChannelSaveStatus.Saving || !mutableUiState.value.canSave) return
        val selection = draftSelectedIds
        saveStatus = ChannelSaveStatus.Saving
        rebuildUiState()

        viewModelScope.launch {
            try {
                repository.saveSelectedChannelIds(selection)
                messageRepository.refreshSelection()
                allChannels = allChannels.map { channel ->
                    channel.copy(isSelected = channel.chatId in selection)
                }
                selectionDirty = false
                saveStatus = ChannelSaveStatus.Saved(selection.size)
                rebuildUiState()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                saveStatus = ChannelSaveStatus.Failed
                rebuildUiState()
            }
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true || retrySecondsRemaining > 0) return
        refreshJob = viewModelScope.launch {
            try {
                repository.refresh()
                messageRepository.refreshSelection()
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
    }

    fun onForegroundChanged(isForeground: Boolean) {
        foregroundJob?.cancel()
        foregroundJob = viewModelScope.launch {
            messageRepository.setForeground(isForeground)
        }
    }

    fun pauseScanning() {
        viewModelScope.launch { messageRepository.pauseScanning() }
    }

    fun resumeScanning() {
        viewModelScope.launch { messageRepository.resumeScanning() }
    }

    private fun rebuildUiState() {
        val filtered = allChannels.filter { channel ->
            searchQuery.isBlank() ||
                channel.title.contains(searchQuery, ignoreCase = true) ||
                channel.username?.contains(searchQuery, ignoreCase = true) == true
        }
        val failure = (syncState as? TelegramChatSyncState.Failed)?.failure
        val phase = when {
            allChannels.isNotEmpty() -> ChannelListPhase.CONTENT
            syncState == TelegramChatSyncState.Loading -> ChannelListPhase.LOADING
            syncState is TelegramChatSyncState.Failed -> ChannelListPhase.ERROR
            else -> ChannelListPhase.EMPTY
        }
        val persistedSelection = allChannels
            .asSequence()
            .filter(TelegramChannel::isSelected)
            .map(TelegramChannel::chatId)
            .toSet()
        val progressById = scanProgress.associateBy(ChannelVideoScanProgress::chatId)
        val scanRetrySeconds = scanProgress
            .mapNotNull { progress ->
                (progress.failure as? TelegramMessageFailure.FloodWait)?.retryAtEpochMillis
            }
            .maxOfOrNull { retryAt ->
                ((retryAt - System.currentTimeMillis() + 999L) / 1_000L)
                    .coerceAtLeast(0)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
            } ?: 0
        val unfinished = scanProgress.filter { progress ->
            progress.status != VideoScanStatus.COMPLETED
        }

        mutableUiState.value = ChannelSelectionUiState(
            phase = phase,
            searchQuery = searchQuery,
            channels = filtered.map { channel ->
                val progress = progressById[channel.chatId]
                ChannelSelectionItem(
                    chatId = channel.chatId,
                    title = channel.title,
                    username = channel.username,
                    isSelected = channel.chatId in draftSelectedIds,
                    isPinned = channel.isPinned,
                    scanStatus = progress?.status,
                    videoSearchPageCount = progress?.videoSearchPageCount ?: 0,
                    processedVideoCandidateCount =
                        progress?.processedVideoCandidateCount ?: 0,
                    indexedVideoCount = progress?.indexedVideoCount ?: 0,
                )
            },
            selectedCount = draftSelectedIds.size,
            canSave = selectionInitialized &&
                draftSelectedIds != persistedSelection &&
                saveStatus != ChannelSaveStatus.Saving,
            isRefreshing = allChannels.isNotEmpty() && syncState == TelegramChatSyncState.Loading,
            failure = failure,
            retrySecondsRemaining = retrySecondsRemaining,
            saveStatus = saveStatus,
            scanSummary = ChannelScanSummary(
                duplicateVideoEncounterCount = scanProgress.sumOf(
                    ChannelVideoScanProgress::duplicateVideoEncounterCount,
                ),
                processedVideoCandidateCount = scanProgress.sumOf(
                    ChannelVideoScanProgress::processedVideoCandidateCount,
                ),
                videoSearchPageCount = scanProgress.sumOf(
                    ChannelVideoScanProgress::videoSearchPageCount,
                ),
                indexedVideoCount = scanProgress.sumOf(ChannelVideoScanProgress::indexedVideoCount),
                approximateVideoCount = scanProgress.mapNotNull(
                    ChannelVideoScanProgress::approximateVideoCount,
                ).let { counts ->
                    counts.takeIf { it.isNotEmpty() && it.size == scanProgress.size }?.sum()
                },
                completedChannelCount = scanProgress.count { it.status == VideoScanStatus.COMPLETED },
                totalChannelCount = scanProgress.size,
                isPaused = unfinished.any { progress ->
                    progress.isPausedByUser || progress.status == VideoScanStatus.ERROR
                },
                canControl = unfinished.isNotEmpty(),
                retrySecondsRemaining = scanRetrySeconds,
                failure = scanObservationFailure.toTelegramMessageFailure()
                    ?: unfinished.firstNotNullOfOrNull(ChannelVideoScanProgress::failure),
            ),
        )
    }

    private fun RepositoryObservationFailure?.toTelegramMessageFailure(): TelegramMessageFailure? =
        when (this) {
            RepositoryObservationFailure.DATABASE -> TelegramMessageFailure.Database
            RepositoryObservationFailure.UNKNOWN -> TelegramMessageFailure.Unknown
            null -> null
        }

    private fun restartScanCountdown() {
        scanCountdownJob?.cancel()
        scanCountdownJob = null
        val hasFutureFloodWait = scanProgress.any { progress ->
            val retryAt = (progress.failure as? TelegramMessageFailure.FloodWait)
                ?.retryAtEpochMillis ?: return@any false
            retryAt > System.currentTimeMillis()
        }
        if (!hasFutureFloodWait) return

        scanCountdownJob = viewModelScope.launch {
            while (true) {
                rebuildUiState()
                val stillWaiting = scanProgress.any { progress ->
                    val retryAt = (progress.failure as? TelegramMessageFailure.FloodWait)
                        ?.retryAtEpochMillis ?: return@any false
                    retryAt > System.currentTimeMillis()
                }
                if (!stillWaiting) break
                delay(1_000)
            }
        }
    }

    private fun applyFloodWait(state: TelegramChatSyncState) {
        floodWaitJob?.cancel()
        floodWaitJob = null
        retrySecondsRemaining = (
            (state as? TelegramChatSyncState.Failed)?.failure as? TelegramChatFailure.FloodWait
        )?.retryAfterSeconds?.coerceAtLeast(0) ?: 0
        if (retrySecondsRemaining == 0) return

        floodWaitJob = viewModelScope.launch {
            repeat(retrySecondsRemaining) {
                delay(1_000)
                retrySecondsRemaining = (retrySecondsRemaining - 1).coerceAtLeast(0)
                rebuildUiState()
            }
        }
    }
}
