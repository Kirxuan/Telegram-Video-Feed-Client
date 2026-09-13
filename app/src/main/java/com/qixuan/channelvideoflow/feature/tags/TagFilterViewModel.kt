package com.qixuan.channelvideoflow.feature.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qixuan.channelvideoflow.domain.channel.TelegramChatRepository
import com.qixuan.channelvideoflow.domain.message.TelegramMessageRepository
import com.qixuan.channelvideoflow.model.video.TagFilterMode
import com.qixuan.channelvideoflow.model.video.VideoFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TagFilterViewModel @Inject constructor(
    chatRepository: TelegramChatRepository,
    messageRepository: TelegramMessageRepository,
) : ViewModel() {
    private val selection = MutableStateFlow(TagSelection())
    private val observationRetry = MutableStateFlow(0L)

    private val availableTags = chatRepository.channels
        .map { channels ->
            channels.asSequence()
                .filter { channel -> channel.isSelected }
                .map { channel -> channel.chatId }
                .toSet()
        }
        .distinctUntilChanged()
        .combine(observationRetry) { channelIds, retry -> channelIds to retry }
        .flatMapLatest { (channelIds, _) ->
            if (channelIds.isEmpty()) {
                flowOf(TagSource(channelIds = emptySet(), tags = emptyList()))
            } else {
                messageRepository.observeTagObservation(channelIds).map { observation ->
                    TagSource(
                        channelIds = channelIds,
                        tags = observation.value.map(::SearchableTag),
                        failure = observation.failure,
                    )
                }
            }
        }

    val uiState = combine(availableTags, selection) { source, selected ->
        val availableNames = source.tags.mapTo(mutableSetOf()) { tag -> tag.item.normalizedName }
        val validSelection = selected.normalizedNames.intersect(availableNames)
        val normalizedQuery = normalizeTagSearchQuery(selected.searchQuery)
        TagFilterUiState(
            isLoading = false,
            channelIds = source.channelIds,
            tags = source.tags
                .asSequence()
                .filter { tag -> tag.matches(normalizedQuery) }
                .map { tag ->
                    TagFilterItem(
                        summary = tag.item,
                        isSelected = tag.item.normalizedName in validSelection,
                    )
                }
                .toList(),
            totalTagCount = source.tags.size,
            selectedNames = validSelection,
            mode = selected.mode,
            searchQuery = selected.searchQuery,
            observationFailure = source.failure,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TagFilterUiState(),
    )

    fun toggleTag(normalizedName: String) {
        selection.update { current ->
            val next = if (normalizedName in current.normalizedNames) {
                current.normalizedNames - normalizedName
            } else {
                current.normalizedNames + normalizedName
            }
            current.copy(normalizedNames = next)
        }
    }

    fun setMode(mode: TagFilterMode) {
        selection.update { current -> current.copy(mode = mode) }
    }

    fun onSearchQueryChanged(query: String) {
        selection.update { current -> current.copy(searchQuery = query) }
    }

    fun clearSearch() {
        selection.update { current -> current.copy(searchQuery = "") }
    }

    fun clearSelection() {
        selection.update { current -> current.copy(normalizedNames = emptySet()) }
    }

    fun retryObservation() {
        observationRetry.value += 1L
    }

    fun currentFilter(): VideoFilter = uiState.value.toFilter()

    private data class TagSelection(
        val normalizedNames: Set<String> = emptySet(),
        val mode: TagFilterMode = TagFilterMode.OR,
        val searchQuery: String = "",
    )

    private data class TagSource(
        val channelIds: Set<Long>,
        val tags: List<SearchableTag>,
        val failure: com.qixuan.channelvideoflow.domain.message.RepositoryObservationFailure? = null,
    )
}
