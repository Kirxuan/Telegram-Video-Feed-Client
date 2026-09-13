package com.qixuan.channelvideoflow.feature.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qixuan.channelvideoflow.R
import com.qixuan.channelvideoflow.model.video.TagFilterMode
import com.qixuan.channelvideoflow.model.video.VideoFilter
import com.qixuan.channelvideoflow.ui.components.BottomPrimaryAction
import com.qixuan.channelvideoflow.ui.components.GlossCard
import com.qixuan.channelvideoflow.ui.components.GlossSearchField
import com.qixuan.channelvideoflow.ui.components.PremiumBackdrop
import com.qixuan.channelvideoflow.ui.components.PremiumTopBar
import com.qixuan.channelvideoflow.ui.components.SegmentedControl
import com.qixuan.channelvideoflow.ui.components.StatePanel
import com.qixuan.channelvideoflow.ui.components.StatusPill
import com.qixuan.channelvideoflow.ui.theme.ChannelVideoFlowTokens
import com.qixuan.channelvideoflow.ui.theme.glossColors

internal object TagFilterTestTags {
    const val Back = "tag-filter-back"
    const val Search = "tag-filter-search"
    const val ClearSearch = "tag-filter-clear-search"
    const val Mode = "tag-filter-mode"
    const val ClearSelection = "tag-filter-clear-selection"
    const val Continue = "tag-filter-continue"
    const val Loading = "tag-filter-loading"
    const val NoResults = "tag-filter-no-results"
    const val Error = "tag-filter-error"
    const val List = "tag-filter-list"
    fun tag(name: String) = "tag-filter-$name"
    fun expand(name: String) = "tag-filter-expand-$name"
}

@Composable
fun TagFilterRoute(
    onBack: () -> Unit,
    onContinue: (VideoFilter) -> Unit,
    viewModel: TagFilterViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TagFilterScreen(
        uiState = uiState,
        onBack = onBack,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onClearSearch = viewModel::clearSearch,
        onTagToggle = viewModel::toggleTag,
        onModeChanged = viewModel::setMode,
        onClearSelection = viewModel::clearSelection,
        onRetryObservation = viewModel::retryObservation,
        onContinue = { onContinue(viewModel.currentFilter()) },
    )
}

@Composable
internal fun TagFilterScreen(
    uiState: TagFilterUiState,
    onBack: () -> Unit,
    onTagToggle: (String) -> Unit,
    onModeChanged: (TagFilterMode) -> Unit,
    onClearSelection: () -> Unit,
    onContinue: () -> Unit,
    onSearchQueryChanged: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onRetryObservation: () -> Unit = {},
) {
    val totalTagCount = uiState.totalTagCount.takeIf { it > 0 } ?: uiState.tags.size
    val bodyState = when {
        uiState.isLoading -> TagBodyState.Loading
        uiState.channelIds.isEmpty() -> TagBodyState.MissingChannels
        uiState.observationFailure != null && uiState.tags.isEmpty() -> TagBodyState.Error
        uiState.hasActiveSearch && uiState.tags.isEmpty() && totalTagCount > 0 ->
            TagBodyState.NoResults
        totalTagCount == 0 -> TagBodyState.Empty
        else -> TagBodyState.Content
    }
    PremiumBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                            ),
                        ),
                ) {
                    PremiumTopBar(
                        title = stringResource(R.string.tags_title),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .widthIn(max = RESPONSIVE_CONTENT_MAX_WIDTH),
                        navigation = {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag(TagFilterTestTags.Back),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_back_outlined),
                                    contentDescription = stringResource(R.string.tags_back),
                                    modifier = Modifier.size(ChannelVideoFlowTokens.Sizes.icon),
                                )
                            }
                        },
                    )
                }
            },
            bottomBar = {
                BottomPrimaryAction(
                    text = stringResource(
                        if (uiState.selectedNames.isEmpty()) {
                            R.string.tags_browse_all
                        } else {
                            R.string.tags_apply_and_browse
                        },
                    ),
                    onClick = onContinue,
                    buttonModifier = Modifier.testTag(TagFilterTestTags.Continue),
                    enabled = uiState.canContinue,
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxSize()
                        .widthIn(max = RESPONSIVE_CONTENT_MAX_WIDTH)
                        .testTag(TagFilterTestTags.List),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = ChannelVideoFlowTokens.Spacing.large,
                        end = ChannelVideoFlowTokens.Spacing.large,
                        bottom = ChannelVideoFlowTokens.Spacing.small,
                    ),
                    verticalArrangement = Arrangement.spacedBy(
                        ChannelVideoFlowTokens.Spacing.small,
                    ),
                ) {
                    item(key = "search") {
                        GlossSearchField(
                            value = uiState.searchQuery,
                            onValueChange = onSearchQueryChanged,
                            label = stringResource(R.string.tags_search),
                            searchIcon = painterResource(R.drawable.ic_search_outlined),
                            searchIconContentDescription = stringResource(R.string.tags_search_icon),
                            clearIcon = painterResource(R.drawable.ic_clear_outlined),
                            clearIconContentDescription = stringResource(R.string.tags_search_clear),
                            modifier = Modifier.testTag(TagFilterTestTags.Search),
                            clearButtonModifier = Modifier.testTag(TagFilterTestTags.ClearSearch),
                        )
                    }
                    item(key = "match-summary") {
                        Text(
                            text = stringResource(R.string.tags_match_summary),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item(key = "mode") {
                        SegmentedControl(
                            options = listOf(
                                stringResource(R.string.tags_mode_any),
                                stringResource(R.string.tags_mode_all),
                            ),
                            selectedIndex = if (uiState.mode == TagFilterMode.OR) 0 else 1,
                            onSelected = { index ->
                                onModeChanged(if (index == 0) TagFilterMode.OR else TagFilterMode.AND)
                            },
                            modifier = Modifier.testTag(TagFilterTestTags.Mode),
                        )
                    }
                    item(key = "selection-summary") {
                        TagSelectionSummary(
                            selectedCount = uiState.selectedNames.size,
                            visibleCount = uiState.tags.size,
                            totalCount = totalTagCount,
                            hasActiveSearch = uiState.hasActiveSearch,
                            hasObservationFailure = uiState.observationFailure != null,
                            onClearSelection = onClearSelection,
                            onRetryObservation = onRetryObservation,
                        )
                    }
                    when (bodyState) {
                        TagBodyState.Loading -> item(key = "loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 200.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                            CircularProgressIndicator(
                                    modifier = Modifier.testTag(TagFilterTestTags.Loading),
                            )
                        }
                        }
                        TagBodyState.MissingChannels -> item(key = "missing-channels") {
                            StatePanel(
                                title = stringResource(R.string.tags_missing_channels_title),
                                message = stringResource(R.string.tags_missing_channels_message),
                                action = {
                                    TextButton(onClick = onBack) {
                                        Text(stringResource(R.string.tags_return_channels))
                                    }
                                },
                            )
                        }
                        TagBodyState.NoResults -> item(key = "no-results") {
                            StatePanel(
                                title = stringResource(R.string.tags_no_results_title),
                                message = stringResource(R.string.tags_no_results_message),
                                modifier = Modifier.testTag(TagFilterTestTags.NoResults),
                                action = {
                                    TextButton(onClick = onClearSearch) {
                                        Text(stringResource(R.string.tags_search_clear))
                                    }
                                },
                            )
                        }
                        TagBodyState.Empty -> item(key = "empty") {
                            StatePanel(
                                title = stringResource(R.string.tags_empty_title),
                                message = stringResource(R.string.tags_empty_message),
                            )
                        }
                        TagBodyState.Error -> item(key = "error") {
                            StatePanel(
                                title = stringResource(R.string.tags_database_error_title),
                                message = stringResource(R.string.tags_database_error_message),
                                modifier = Modifier.testTag(TagFilterTestTags.Error),
                                action = {
                                    TextButton(onClick = onRetryObservation) {
                                        Text(stringResource(R.string.tags_retry))
                                    }
                                },
                            )
                        }
                        TagBodyState.Content -> items(
                            items = uiState.tags,
                            key = { item -> item.summary.normalizedName },
                        ) { item ->
                            TagRow(item = item, onTagToggle = onTagToggle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagSelectionSummary(
    selectedCount: Int,
    visibleCount: Int,
    totalCount: Int,
    hasActiveSearch: Boolean,
    hasObservationFailure: Boolean,
    onClearSelection: () -> Unit,
    onRetryObservation: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(text = stringResource(R.string.tags_selected_count, selectedCount))
            if (hasActiveSearch) {
                Text(
                    text = stringResource(R.string.tags_visible_count, visibleCount, totalCount),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onClearSelection,
                enabled = selectedCount > 0,
                modifier = Modifier.testTag(TagFilterTestTags.ClearSelection),
            ) {
                Text(stringResource(R.string.tags_clear_selection))
            }
            if (hasObservationFailure) {
                TextButton(onClick = onRetryObservation) {
                    Text(stringResource(R.string.tags_retry))
                }
            }
        }
    }
}

@Composable
private fun TagRow(
    item: TagFilterItem,
    onTagToggle: (String) -> Unit,
) {
            var expanded by rememberSaveable(item.summary.normalizedName) { mutableStateOf(false) }
            var canExpand by rememberSaveable(item.summary.normalizedName) { mutableStateOf(false) }
            val shouldOfferExpansion = canExpand || item.summary.displayName.length > 28
            val selectedState = stringResource(
                if (item.isSelected) R.string.tags_selected else R.string.tags_not_selected,
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                GlossCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        toggleableState = if (item.isSelected) {
                            ToggleableState.On
                        } else {
                            ToggleableState.Off
                        }
                    }
                    .testTag(TagFilterTestTags.tag(item.summary.normalizedName))
                    .sizeIn(minHeight = 64.dp),
                shape = ChannelVideoFlowTokens.Shapes.medium,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 8.dp,
                    vertical = 6.dp,
                ),
                selected = item.isSelected,
                onClick = { onTagToggle(item.summary.normalizedName) },
                role = Role.Checkbox,
                stateDescription = selectedState,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = item.isSelected, onCheckedChange = null)
                        Text(
                            text = item.summary.displayName,
                            modifier = Modifier.weight(1f),
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            onTextLayout = { result ->
                                if (result.hasVisualOverflow) canExpand = true
                            },
                        )
                        Text(
                            text = stringResource(
                                R.string.tags_video_count,
                                item.summary.videoCount,
                            ),
                            modifier = Modifier.widthIn(min = 64.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                        )
                    }
                }
                if (shouldOfferExpansion) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier
                                .sizeIn(minHeight = ChannelVideoFlowTokens.Sizes.touchTarget)
                                .testTag(
                                    TagFilterTestTags.expand(item.summary.normalizedName),
                                ),
                        ) {
                            Text(
                                stringResource(
                                    if (expanded) R.string.tags_collapse else R.string.tags_expand,
                                ),
                            )
                        }
                    }
                }
            }
}

private enum class TagBodyState {
    Loading,
    MissingChannels,
    NoResults,
    Empty,
    Error,
    Content,
}

private val RESPONSIVE_CONTENT_MAX_WIDTH = 720.dp
