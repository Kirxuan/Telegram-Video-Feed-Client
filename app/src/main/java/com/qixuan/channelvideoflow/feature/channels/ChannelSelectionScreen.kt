package com.qixuan.channelvideoflow.feature.channels

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qixuan.channelvideoflow.R
import com.qixuan.channelvideoflow.model.channel.TelegramChatFailure
import com.qixuan.channelvideoflow.model.video.TelegramMessageFailure
import com.qixuan.channelvideoflow.model.video.VideoScanStatus
import com.qixuan.channelvideoflow.ui.components.BottomPrimaryAction
import com.qixuan.channelvideoflow.ui.components.GlossCard
import com.qixuan.channelvideoflow.ui.components.GlossSearchField
import com.qixuan.channelvideoflow.ui.components.PremiumBackdrop
import com.qixuan.channelvideoflow.ui.components.PremiumTopBar
import com.qixuan.channelvideoflow.ui.components.PrimaryActionState
import com.qixuan.channelvideoflow.ui.components.StatusPill
import com.qixuan.channelvideoflow.ui.theme.ChannelVideoFlowTokens
import com.qixuan.channelvideoflow.ui.theme.glossColors

internal object ChannelSelectionTestTags {
    const val Search = "channel-search"
    const val Save = "channel-save"
    const val Retry = "channel-retry"
    const val Progress = "channel-progress"
    const val ScanControl = "channel-scan-control"
    const val ScanDetails = "channel-scan-details"
    const val ScanSummary = "channel-scan-summary"
    const val PinDetails = "channel-pin-details"
    const val SelectionSummary = "channel-selection-summary"
    const val MainList = "channel-main-list"
    const val QuickLogout = "channel-quick-logout"
    const val QuickCache = "channel-quick-cache"
    const val QuickBrowse = "channel-quick-browse"
    const val Overflow = "channel-overflow"
    fun row(chatId: Long) = "channel-row-$chatId"
}

@Composable
fun ChannelSelectionRoute(
    onLogout: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenCacheSettings: () -> Unit,
    logoutEnabled: Boolean,
    viewModel: ChannelSelectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForegroundChanged(true)
                Lifecycle.Event.ON_STOP -> viewModel.onForegroundChanged(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.onForegroundChanged(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onForegroundChanged(false)
        }
    }
    ChannelSelectionScreen(
        uiState = uiState,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onToggleChannel = viewModel::toggleChannel,
        onToggleChannelPinned = viewModel::toggleChannelPinned,
        onSave = viewModel::saveSelection,
        onRetry = viewModel::refresh,
        onPauseScan = viewModel::pauseScanning,
        onResumeScan = viewModel::resumeScanning,
        onLogout = onLogout,
        onOpenPlayback = onOpenPlayback,
        onOpenCacheSettings = onOpenCacheSettings,
        logoutEnabled = logoutEnabled,
    )
}

@Composable
fun ChannelSelectionScreen(
    uiState: ChannelSelectionUiState,
    onSearchQueryChanged: (String) -> Unit,
    onToggleChannel: (Long) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
    onOpenPlayback: () -> Unit = {},
    onOpenCacheSettings: () -> Unit = {},
    logoutEnabled: Boolean,
    onPauseScan: () -> Unit = {},
    onResumeScan: () -> Unit = {},
    onToggleChannelPinned: (Long) -> Unit = {},
) {
    val saveStatus = saveStatusText(uiState.saveStatus)
    var overflowExpanded by rememberSaveable { mutableStateOf(false) }
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
                        title = stringResource(R.string.channels_title),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .widthIn(max = RESPONSIVE_CONTENT_MAX_WIDTH),
                        actions = {
                            IconButton(
                                onClick = onOpenCacheSettings,
                                modifier = Modifier
                                    .size(ChannelVideoFlowTokens.Sizes.touchTarget)
                                    .testTag(ChannelSelectionTestTags.QuickCache),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings_outlined),
                                    contentDescription = stringResource(
                                        R.string.channels_action_cache_icon,
                                    ),
                                )
                            }
                            Box {
                                IconButton(
                                    onClick = { overflowExpanded = true },
                                    modifier = Modifier
                                        .size(ChannelVideoFlowTokens.Sizes.touchTarget)
                                        .testTag(ChannelSelectionTestTags.Overflow),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_more_vert_outlined),
                                        contentDescription = stringResource(
                                            R.string.channels_more_actions,
                                        ),
                                    )
                                }
                                DropdownMenu(
                                    expanded = overflowExpanded,
                                    onDismissRequest = { overflowExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.channels_action_logout)) },
                                        onClick = {
                                            overflowExpanded = false
                                            onLogout()
                                        },
                                        enabled = logoutEnabled,
                                        modifier = Modifier.testTag(
                                            ChannelSelectionTestTags.QuickLogout,
                                        ),
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(
                                                    R.drawable.ic_logout_outlined,
                                                ),
                                                contentDescription = null,
                                                tint = glossColors.danger,
                                            )
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            },
            bottomBar = {
                BottomPrimaryAction(
                    text = stringResource(R.string.channels_save),
                    onClick = onSave,
                    buttonModifier = Modifier.testTag(ChannelSelectionTestTags.Save),
                    enabled = uiState.canSave,
                    statusText = saveStatus,
                    state = when (uiState.saveStatus) {
                        ChannelSaveStatus.Idle -> PrimaryActionState.Idle
                        ChannelSaveStatus.Saving -> PrimaryActionState.Loading
                        is ChannelSaveStatus.Saved -> PrimaryActionState.Success
                        ChannelSaveStatus.Failed -> PrimaryActionState.Error
                    },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                ChannelMainList(
                    uiState = uiState,
                    onSearchQueryChanged = onSearchQueryChanged,
                    onToggleChannel = onToggleChannel,
                    onToggleChannelPinned = onToggleChannelPinned,
                    onRetry = onRetry,
                    onPauseScan = onPauseScan,
                    onResumeScan = onResumeScan,
                    onOpenPlayback = onOpenPlayback,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxHeight()
                        .widthIn(max = RESPONSIVE_CONTENT_MAX_WIDTH),
                )
            }
        }
    }
}

@Composable
private fun ChannelMainList(
    uiState: ChannelSelectionUiState,
    onSearchQueryChanged: (String) -> Unit,
    onToggleChannel: (Long) -> Unit,
    onToggleChannelPinned: (Long) -> Unit,
    onRetry: () -> Unit,
    onPauseScan: () -> Unit,
    onResumeScan: () -> Unit,
    onOpenPlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag(ChannelSelectionTestTags.MainList),
        contentPadding = PaddingValues(
            start = ChannelVideoFlowTokens.Spacing.large,
            top = ChannelVideoFlowTokens.Spacing.xSmall,
            end = ChannelVideoFlowTokens.Spacing.large,
            bottom = ChannelVideoFlowTokens.Spacing.medium,
        ),
        verticalArrangement = Arrangement.spacedBy(ChannelVideoFlowTokens.Spacing.small),
    ) {
        item(key = "quick-actions") {
            ChannelBrowseAction(
                hasIndexedVideos = uiState.scanSummary.indexedVideoCount > 0,
                onOpenPlayback = onOpenPlayback,
            )
        }
        item(key = "search") {
            GlossSearchField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChanged,
                label = stringResource(R.string.channels_search),
                searchIcon = painterResource(R.drawable.ic_search_outlined),
                searchIconContentDescription = stringResource(R.string.channels_search),
                clearIcon = painterResource(R.drawable.ic_clear_outlined),
                clearIconContentDescription = stringResource(R.string.channels_search_clear),
                modifier = Modifier.testTag(ChannelSelectionTestTags.Search),
            )
        }
        item(key = "selection-summary") {
            SelectionSummary(selectedCount = uiState.selectedCount)
            if (uiState.canSave) {
                Text(
                    text = "选择尚未保存：保存后才会切换扫描频道，取消的频道将停止扫描。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (uiState.selectedCount > 0) {
            item(key = "scan-summary") {
                ScanProgressPanel(
                    selectedCount = uiState.selectedCount,
                    summary = uiState.scanSummary,
                    onPause = onPauseScan,
                    onResume = onResumeScan,
                )
            }
        }
        if (uiState.isRefreshing) {
            item(key = "refreshing") {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .testTag(ChannelSelectionTestTags.Progress),
                    )
                    Text(stringResource(R.string.channels_refreshing))
                }
            }
        }
        if (uiState.failure != null && uiState.phase == ChannelListPhase.CONTENT) {
            item(key = "inline-failure") {
                InlineFailure(
                    failure = uiState.failure,
                    retrySecondsRemaining = uiState.retrySecondsRemaining,
                    onRetry = onRetry,
                )
            }
        }

        when (uiState.phase) {
            ChannelListPhase.LOADING -> item(key = "loading") { LoadingContent() }
            ChannelListPhase.EMPTY -> item(key = "empty") { EmptyContent(onRetry) }
            ChannelListPhase.ERROR -> item(key = "error") {
                ErrorContent(
                    failure = uiState.failure ?: TelegramChatFailure.Unknown,
                    retrySecondsRemaining = uiState.retrySecondsRemaining,
                    onRetry = onRetry,
                )
            }
            ChannelListPhase.CONTENT -> if (uiState.channels.isEmpty()) {
                item(key = "search-empty") { SearchEmptyContent() }
            } else {
                items(uiState.channels, key = ChannelSelectionItem::chatId) { channel ->
                    ChannelRow(
                        channel = channel,
                        onToggleChannel = onToggleChannel,
                        onToggleChannelPinned = onToggleChannelPinned,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(ChannelVideoFlowTokens.Motion.contentEnterMillis),
                            placementSpec = tween(ChannelVideoFlowTokens.Motion.surfaceMillis),
                            fadeOutSpec = tween(ChannelVideoFlowTokens.Motion.stateChangeMillis),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelBrowseAction(
    hasIndexedVideos: Boolean,
    onOpenPlayback: () -> Unit,
) {
    val label = stringResource(R.string.channels_action_browse)
    val disabledDescription = stringResource(R.string.channels_action_browse_disabled)
    Button(
        onClick = onOpenPlayback,
        enabled = hasIndexedVideos,
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = ChannelVideoFlowTokens.Sizes.touchTarget)
            .semantics {
                role = Role.Button
                contentDescription = label
                if (!hasIndexedVideos) {
                    disabled()
                    stateDescription = disabledDescription
                }
            }
            .testTag(ChannelSelectionTestTags.QuickBrowse),
        shape = ChannelVideoFlowTokens.Shapes.control,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_play_circle_outlined),
            contentDescription = null,
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = ChannelVideoFlowTokens.Spacing.small),
        )
    }
}

@Composable
private fun SelectionSummary(selectedCount: Int) {
    var detailsVisible by rememberSaveable { mutableStateOf(false) }
    GlossCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChannelSelectionTestTags.SelectionSummary),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        showGlossHighlight = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val showCompactHint = this@BoxWithConstraints.maxWidth >= 280.dp &&
                    LocalDensity.current.fontScale < 1.5f
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPill(
                        text = stringResource(R.string.channels_selected_count, selectedCount),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (showCompactHint) {
                        Text(
                            text = stringResource(R.string.channels_pin_hint_compact),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f))
                    }
                    TextButton(
                        onClick = { detailsVisible = !detailsVisible },
                        modifier = Modifier.testTag(ChannelSelectionTestTags.PinDetails),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            stringResource(
                                if (detailsVisible) {
                                    R.string.channels_pin_details_hide
                                } else {
                                    R.string.channels_pin_details_show
                                },
                            ),
                            maxLines = Int.MAX_VALUE,
                        )
                    }
                }
            }
            if (detailsVisible || LocalDensity.current.fontScale >= 1.5f) {
                Text(
                    text = stringResource(R.string.channels_pin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelSelectionItem,
    onToggleChannel: (Long) -> Unit,
    onToggleChannelPinned: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = glossColors.surface
    val outline = glossColors.border
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) ChannelVideoFlowTokens.Motion.pressedScale else 1f,
        animationSpec = tween(
            if (pressed) {
                ChannelVideoFlowTokens.Motion.pressInMillis
            } else {
                ChannelVideoFlowTokens.Motion.pressOutMillis
            },
        ),
        label = "channel row press",
    )
    val containerColor by animateColorAsState(
        targetValue = if (channel.isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            surface
        },
        animationSpec = tween(ChannelVideoFlowTokens.Motion.stateChangeMillis),
        label = "channel row surface",
    )
    val borderColor by animateColorAsState(
        targetValue = if (channel.isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.54f)
        } else {
            outline
        },
        animationSpec = tween(ChannelVideoFlowTokens.Motion.stateChangeMillis),
        label = "channel row border",
    )
    val pinnedLabel = stringResource(R.string.channels_pinned_compact)
    val metadata = buildList {
        channel.username?.let { add("@$it") }
        if (channel.isPinned) add(pinnedLabel)
    }.joinToString(" · ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(ChannelVideoFlowTokens.Shapes.medium)
            .background(containerColor)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = ChannelVideoFlowTokens.Shapes.medium,
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = indication,
                onClick = { onToggleChannel(channel.chatId) },
                onLongClick = { onToggleChannelPinned(channel.chatId) },
                onLongClickLabel = stringResource(
                    if (channel.isPinned) R.string.channels_unpin else R.string.channels_pin,
                ),
                role = Role.Checkbox,
            )
            .semantics {
                role = Role.Checkbox
                toggleableState = if (channel.isSelected) ToggleableState.On else ToggleableState.Off
            }
            .testTag(ChannelSelectionTestTags.row(channel.chatId))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = channel.isSelected, onCheckedChange = null)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = channel.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (channel.isSelected && channel.scanStatus != null) {
                ChannelScanProgress(channel)
            }
        }
    }
}

@Composable
private fun ScanProgressPanel(
    selectedCount: Int,
    summary: ChannelScanSummary,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    if (selectedCount == 0) {
        Text(
            text = stringResource(R.string.channels_scan_waiting_selection),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    var detailsVisible by rememberSaveable { mutableStateOf(false) }
    GlossCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChannelSelectionTestTags.ScanSummary),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        showGlossHighlight = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(
                            R.string.channels_scan_summary_processed,
                            summary.processedVideoCandidateCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.channels_scan_summary_indexed,
                            summary.indexedVideoCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = { detailsVisible = !detailsVisible },
                    modifier = Modifier
                        .sizeIn(minWidth = ChannelVideoFlowTokens.Sizes.touchTarget)
                        .testTag(ChannelSelectionTestTags.ScanDetails),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(
                        stringResource(
                            if (detailsVisible) {
                                R.string.channels_scan_details_hide
                            } else {
                                R.string.channels_scan_details_show
                            },
                        ),
                        maxLines = Int.MAX_VALUE,
                    )
                }
                if (summary.canControl) {
                    TextButton(
                        onClick = if (summary.isPaused) onResume else onPause,
                        modifier = Modifier
                            .sizeIn(
                                minWidth = ChannelVideoFlowTokens.Sizes.touchTarget,
                                minHeight = ChannelVideoFlowTokens.Sizes.touchTarget,
                            )
                            .testTag(ChannelSelectionTestTags.ScanControl),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            stringResource(
                                if (summary.isPaused) {
                                    R.string.channels_scan_resume
                                } else {
                                    R.string.channels_scan_pause
                                },
                            ),
                            maxLines = Int.MAX_VALUE,
                        )
                    }
                }
            }
            summary.failure?.let { failure ->
                Text(
                    text = scanFailureText(failure, summary.retrySecondsRemaining),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (detailsVisible) {
                Text(
                    text = "已折叠 ${summary.duplicateVideoEncounterCount} 次重复视频；已索引为去重后的数量，处理量包含重复及未纳入索引的候选。",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ScanStatistic(
                            label = stringResource(R.string.channels_scan_stat_processed),
                            value = stringResource(
                                R.string.channels_scan_stat_count_value,
                                summary.processedVideoCandidateCount,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        ScanStatistic(
                            label = stringResource(R.string.channels_scan_stat_pages),
                            value = stringResource(
                                R.string.channels_scan_stat_page_value,
                                summary.videoSearchPageCount,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ScanStatistic(
                            label = stringResource(R.string.channels_scan_stat_indexed),
                            value = stringResource(
                                R.string.channels_scan_stat_count_value,
                                summary.indexedVideoCount,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        ScanStatistic(
                            label = stringResource(R.string.channels_scan_stat_completed),
                            value = stringResource(
                                R.string.channels_scan_stat_completed_value,
                                summary.completedChannelCount,
                                summary.totalChannelCount,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    summary.approximateVideoCount?.let { approximateCount ->
                        Text(
                            text = stringResource(
                                R.string.channels_scan_approximate_hint,
                                approximateCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelScanProgress(channel: ChannelSelectionItem) {
    val status = when (channel.scanStatus) {
        VideoScanStatus.NOT_STARTED -> stringResource(R.string.channels_scan_not_started)
        VideoScanStatus.SCANNING -> stringResource(R.string.channels_scan_scanning)
        VideoScanStatus.PAUSED -> stringResource(R.string.channels_scan_paused)
        VideoScanStatus.COMPLETED -> stringResource(R.string.channels_scan_completed)
        VideoScanStatus.ERROR -> stringResource(R.string.channels_scan_error)
        null -> return
    }
    Text(
        text = stringResource(
            R.string.channels_scan_channel_progress_primary,
            status,
            channel.processedVideoCandidateCount,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = stringResource(
            R.string.channels_scan_channel_progress_secondary,
            channel.videoSearchPageCount,
            channel.indexedVideoCount,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ScanStatistic(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(ChannelVideoFlowTokens.Shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = Int.MAX_VALUE,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun scanFailureText(failure: TelegramMessageFailure, retrySeconds: Int): String =
    when (failure) {
        TelegramMessageFailure.NetworkUnavailable ->
            stringResource(R.string.channels_scan_network_error)
        is TelegramMessageFailure.FloodWait ->
            stringResource(R.string.channels_scan_flood_wait, retrySeconds)
        is TelegramMessageFailure.RequestRejected ->
            stringResource(R.string.channels_scan_telegram_error, failure.code)
        TelegramMessageFailure.AccessLost -> stringResource(R.string.channels_scan_access_lost)
        TelegramMessageFailure.Timeout -> stringResource(R.string.channels_scan_timeout)
        TelegramMessageFailure.Database -> stringResource(R.string.channels_scan_database_error)
        TelegramMessageFailure.PaginationStalled ->
            stringResource(R.string.channels_scan_pagination_stalled)
        TelegramMessageFailure.Unknown -> stringResource(R.string.channels_scan_unknown_error)
    }

@Composable
private fun LoadingContent() {
    CenteredState {
        CircularProgressIndicator(modifier = Modifier.testTag(ChannelSelectionTestTags.Progress))
        Text(stringResource(R.string.channels_loading))
    }
}

@Composable
private fun EmptyContent(onRetry: () -> Unit) {
    CenteredState {
        Text(stringResource(R.string.channels_empty))
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.testTag(ChannelSelectionTestTags.Retry),
        ) {
            Text(stringResource(R.string.channels_retry))
        }
    }
}

@Composable
private fun SearchEmptyContent() {
    CenteredState { Text(stringResource(R.string.channels_search_empty)) }
}

@Composable
private fun ErrorContent(
    failure: TelegramChatFailure,
    retrySecondsRemaining: Int,
    onRetry: () -> Unit,
) {
    CenteredState(isError = true) {
        FailureText(failure, retrySecondsRemaining)
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.testTag(ChannelSelectionTestTags.Retry),
            enabled = retrySecondsRemaining == 0,
        ) {
            Text(stringResource(R.string.channels_retry))
        }
    }
}

@Composable
private fun InlineFailure(
    failure: TelegramChatFailure,
    retrySecondsRemaining: Int,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            FailureText(failure, retrySecondsRemaining)
        }
        TextButton(onClick = onRetry, enabled = retrySecondsRemaining == 0) {
            Text(stringResource(R.string.channels_retry))
        }
    }
}

@Composable
private fun saveStatusText(status: ChannelSaveStatus): String? = when (status) {
    ChannelSaveStatus.Idle -> null
    ChannelSaveStatus.Saving -> stringResource(R.string.channels_saving)
    is ChannelSaveStatus.Saved -> stringResource(R.string.channels_saved, status.count)
    ChannelSaveStatus.Failed -> stringResource(R.string.channels_save_failed)
}

@Composable
private fun CenteredState(
    isError: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 260.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlossCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(24.dp),
            isError = isError,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

private fun TelegramChatFailure.toMessageRes(): Int = when (this) {
    TelegramChatFailure.NetworkUnavailable -> R.string.channels_network_error
    is TelegramChatFailure.FloodWait -> R.string.channels_flood_wait
    is TelegramChatFailure.RequestRejected -> R.string.channels_telegram_error
    TelegramChatFailure.Timeout -> R.string.channels_timeout
    TelegramChatFailure.Database -> R.string.channels_database_error
    TelegramChatFailure.Unknown -> R.string.channels_unknown_error
}

@Composable
private fun FailureText(
    failure: TelegramChatFailure,
    retrySecondsRemaining: Int,
) {
    val text = if (failure is TelegramChatFailure.FloodWait) {
        stringResource(R.string.channels_flood_wait, retrySecondsRemaining)
    } else if (failure is TelegramChatFailure.RequestRejected) {
        stringResource(R.string.channels_telegram_error, failure.code)
    } else {
        stringResource(failure.toMessageRes())
    }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private val RESPONSIVE_CONTENT_MAX_WIDTH = 720.dp
