package com.qixuan.channelvideoflow.feature.tags

import com.qixuan.channelvideoflow.model.video.TagFilterMode
import com.qixuan.channelvideoflow.model.video.TagSummary
import com.qixuan.channelvideoflow.model.video.VideoFilter
import com.qixuan.channelvideoflow.domain.message.RepositoryObservationFailure

data class TagFilterItem(
    val summary: TagSummary,
    val isSelected: Boolean,
)

data class TagFilterUiState(
    val isLoading: Boolean = true,
    val channelIds: Set<Long> = emptySet(),
    val tags: List<TagFilterItem> = emptyList(),
    val totalTagCount: Int = 0,
    val selectedNames: Set<String> = emptySet(),
    val mode: TagFilterMode = TagFilterMode.OR,
    val searchQuery: String = "",
    val observationFailure: RepositoryObservationFailure? = null,
) {
    val canContinue: Boolean get() = channelIds.isNotEmpty()
    val hasActiveSearch: Boolean get() = normalizeTagSearchQuery(searchQuery).isNotEmpty()

    fun toFilter(): VideoFilter = VideoFilter(
        channelIds = channelIds,
        normalizedTags = selectedNames,
        tagMode = mode,
    )
}
