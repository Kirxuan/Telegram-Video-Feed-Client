package com.qixuan.channelvideoflow.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.qixuan.channelvideoflow.R
import com.qixuan.channelvideoflow.domain.cache.MediaCacheLimits
import com.qixuan.channelvideoflow.domain.cache.MediaCacheOperation
import com.qixuan.channelvideoflow.model.video.VideoQualityPreference
import com.qixuan.channelvideoflow.ui.components.PremiumBackdrop
import com.qixuan.channelvideoflow.ui.components.PremiumTopBar
import com.qixuan.channelvideoflow.ui.components.SettingsGroup
import com.qixuan.channelvideoflow.ui.theme.ChannelVideoFlowTokens
import java.util.Locale

internal object CacheSettingsTestTags {
    const val Usage = "cache-settings-usage"
    const val MobilePreload = "cache-settings-mobile-preload"
    const val Clear = "cache-settings-clear"
    const val ConfirmClear = "cache-settings-confirm-clear"
    const val Logout = "cache-settings-logout"
    const val LimitSlider = "cache-settings-limit-slider"
    const val LimitMenu = "cache-settings-limit-menu"
    fun limit(bytes: Long) = "cache-settings-limit-$bytes"
    fun quality(preference: VideoQualityPreference) = "cache-settings-quality-${preference.name}"
}

@Composable
@UnstableApi
fun CacheSettingsRoute(
    onBack: () -> Unit,
    onLogout: () -> Unit = {},
    viewModel: CacheSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    CacheSettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onLimitSelected = viewModel::setLimit,
        onMobilePreloadChanged = viewModel::setMobilePreloadEnabled,
        onVideoQualitySelected = viewModel::setVideoQuality,
        onRefresh = viewModel::refresh,
        onClear = viewModel::clearCache,
        onLogout = onLogout,
    )
}

@Composable
internal fun CacheSettingsScreen(
    uiState: CacheSettingsUiState,
    onBack: () -> Unit,
    onLimitSelected: (Long) -> Unit,
    onMobilePreloadChanged: (Boolean) -> Unit,
    onVideoQualitySelected: (VideoQualityPreference) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onLogout: () -> Unit = {},
) {
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.cache_clear_confirm_title)) },
            text = { Text(stringResource(R.string.cache_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                    modifier = Modifier.testTag(CacheSettingsTestTags.ConfirmClear),
                ) {
                    Text(stringResource(R.string.cache_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cache_clear_cancel))
                }
            },
        )
    }

    PremiumBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                PremiumTopBar(
                    title = stringResource(R.string.cache_title),
                    subtitle = "播放质量、容量与网络策略",
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                        ),
                    ),
                    navigation = {
                        TextButton(onClick = onBack) {
                            Text(stringResource(R.string.cache_back))
                        }
                    },
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    )
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "当前缓存上限 ${formatByteSize(uiState.cache.limitBytes)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                SettingsGroup(
                    title = stringResource(R.string.video_quality_title),
                    subtitle = stringResource(R.string.video_quality_summary),
                ) {
                    VideoQualityPreference.entries.forEach { preference ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onVideoQualitySelected(preference) }
                                .testTag(CacheSettingsTestTags.quality(preference)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = uiState.cache.videoQualityPreference == preference,
                                onClick = { onVideoQualitySelected(preference) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(preference.titleResource()))
                                Text(
                                    stringResource(preference.summaryResource()),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                SettingsGroup(title = "缓存概览", subtitle = "媒体字节仅位于应用私有缓存") {
                    Text(
                        text = stringResource(
                            R.string.cache_usage,
                            formatByteSize(uiState.cache.usedBytes),
                            if (uiState.cache.isExactUsage) {
                                ""
                            } else {
                                stringResource(R.string.cache_usage_estimate)
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(CacheSettingsTestTags.Usage),
                    )
                    TextButton(onClick = onRefresh, enabled = !uiState.cache.isRefreshing) {
                        if (uiState.cache.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        } else {
                            Text(stringResource(R.string.cache_refresh))
                        }
                    }
                }

                SettingsGroup(
                    title = stringResource(R.string.cache_limit_title),
                    subtitle = "达到上限后按最近使用顺序清理，当前与下一条受保护",
                ) {
                    CacheCapacitySelector(
                        selectedBytes = uiState.cache.limitBytes,
                        onSelected = onLimitSelected,
                    )
                }

                SettingsGroup(title = "网络", subtitle = "移动数据默认不预加载下一条") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cache_mobile_preload))
                            Text(
                                stringResource(R.string.cache_mobile_preload_summary),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = uiState.cache.mobileDataPreloadEnabled,
                            onCheckedChange = onMobilePreloadChanged,
                            modifier = Modifier.testTag(CacheSettingsTestTags.MobilePreload),
                        )
                    }
                }

                SettingsGroup(title = "维护与账号", subtitle = "危险操作需要明确确认") {
                    CacheOperationText(uiState.cache.operation)
                    Button(
                        onClick = { confirmClear = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag(CacheSettingsTestTags.Clear),
                        shape = ChannelVideoFlowTokens.Shapes.control,
                    ) {
                        Text(stringResource(R.string.cache_clear))
                    }
                    Button(
                        onClick = onLogout,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag(CacheSettingsTestTags.Logout),
                        shape = ChannelVideoFlowTokens.Shapes.control,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(stringResource(R.string.login_logout))
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun CacheCapacitySelector(
    selectedBytes: Long,
    onSelected: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(CacheSettingsTestTags.LimitMenu),
        ) {
            Text("当前上限 ${formatByteSize(selectedBytes)} · 更改")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MediaCacheLimits.allowedBytes.forEach { bytes ->
                DropdownMenuItem(
                    text = { Text(formatByteSize(bytes) + if (bytes == selectedBytes) "（已选）" else "") },
                    onClick = {
                        expanded = false
                        onSelected(bytes)
                    },
                    modifier = Modifier.testTag(CacheSettingsTestTags.limit(bytes)),
                )
            }
        }
    }
}

private fun VideoQualityPreference.titleResource(): Int = when (this) {
    VideoQualityPreference.AUTO -> R.string.video_quality_auto
    VideoQualityPreference.DATA_SAVER -> R.string.video_quality_data_saver
    VideoQualityPreference.HD_720 -> R.string.video_quality_720p
    VideoQualityPreference.ORIGINAL -> R.string.video_quality_original
}

private fun VideoQualityPreference.summaryResource(): Int = when (this) {
    VideoQualityPreference.AUTO -> R.string.video_quality_auto_summary
    VideoQualityPreference.DATA_SAVER -> R.string.video_quality_data_saver_summary
    VideoQualityPreference.HD_720 -> R.string.video_quality_720p_summary
    VideoQualityPreference.ORIGINAL -> R.string.video_quality_original_summary
}

@Composable
private fun CacheOperationText(operation: MediaCacheOperation) {
    val text = when (operation) {
        MediaCacheOperation.Idle -> return
        is MediaCacheOperation.Trimmed ->
            stringResource(R.string.cache_trimmed, formatByteSize(operation.releasedBytes))
        is MediaCacheOperation.Cleared ->
            stringResource(R.string.cache_cleared, formatByteSize(operation.releasedBytes))
        is MediaCacheOperation.Partial ->
            stringResource(
                R.string.cache_partial,
                formatByteSize(operation.releasedBytes),
                formatByteSize(operation.remainingBytes),
            )
        MediaCacheOperation.Failed -> stringResource(R.string.cache_failed)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

internal fun formatByteSize(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return if (safe >= MediaCacheLimits.GIBIBYTE) {
        val gib = safe.toDouble() / MediaCacheLimits.GIBIBYTE
        if (gib % 1.0 == 0.0) {
            "${gib.toLong()} GB"
        } else {
            String.format(Locale.ROOT, "%.1f GB", gib)
        }
    } else {
        "${safe / MediaCacheLimits.MEBIBYTE} MB"
    }
}
