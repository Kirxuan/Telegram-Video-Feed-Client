package com.qixuan.channelvideoflow.feature.channels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qixuan.channelvideoflow.model.channel.TelegramChatFailure
import com.qixuan.channelvideoflow.model.video.TelegramMessageFailure
import com.qixuan.channelvideoflow.model.video.VideoScanStatus
import com.qixuan.channelvideoflow.ui.theme.ChannelVideoFlowTheme
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelSelectionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun doubleFontShowsScanTextWithoutEllipsisAndExplainsDeduplication() {
        val progressText = "扫描中 · 已处理 25764 个视频"
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(androidx.compose.ui.unit.DpSize(320.dp, 800.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    ChannelVideoFlowTheme {
                        Box(androidx.compose.ui.Modifier.width(320.dp).fillMaxHeight()) {
                            ChannelSelectionScreen(
                                uiState = ChannelSelectionUiState(
                                    phase = ChannelListPhase.CONTENT,
                                    selectedCount = 1,
                                    channels = listOf(item(1, "测试频道", null).copy(
                                        isSelected = true,
                                        scanStatus = VideoScanStatus.SCANNING,
                                        processedVideoCandidateCount = 25764,
                                    )),
                                    scanSummary = ChannelScanSummary(
                                        processedVideoCandidateCount = 25764,
                                        indexedVideoCount = 14680,
                                        duplicateVideoEncounterCount = 10100,
                                    ),
                                ),
                                onSearchQueryChanged = {}, onToggleChannel = {}, onSave = {},
                                onRetry = {}, onLogout = {}, logoutEnabled = true,
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(androidx.compose.ui.test.hasText(progressText))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithText(progressText, useUnmergedTree = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult,
        ) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue(
            layouts.joinToString { "${it.layoutInput.text}: size=${it.size}, lines=${it.lineCount}" },
            layouts.none { it.hasVisualOverflow },
        )
        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(hasTestTag(ChannelSelectionTestTags.ScanDetails))
        composeRule.onNodeWithTag(ChannelSelectionTestTags.ScanDetails).performClick()
        composeRule.onNodeWithText("已折叠 10100 次重复视频", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun searchSelectTwoAndSaveEmitsTheExpectedSelection() {
        val allChannels = listOf(
            item(1, "频道一", "one"),
            item(2, "频道二", "two"),
            item(3, "频道三", null),
        )
        var searchQuery by mutableStateOf("")
        var selectedIds by mutableStateOf(emptySet<Long>())
        var savedIds = emptySet<Long>()
        composeRule.setContent {
            ChannelSelectionScreen(
                uiState = ChannelSelectionUiState(
                    phase = ChannelListPhase.CONTENT,
                    searchQuery = searchQuery,
                    channels = allChannels.filter { channel ->
                        searchQuery.isBlank() ||
                            channel.title.contains(searchQuery, ignoreCase = true) ||
                            channel.username?.contains(searchQuery, ignoreCase = true) == true
                    }.map { channel ->
                        channel.copy(isSelected = channel.chatId in selectedIds)
                    },
                    selectedCount = selectedIds.size,
                    canSave = selectedIds.isNotEmpty(),
                ),
                onSearchQueryChanged = { query ->
                    searchQuery = query
                },
                onToggleChannel = { chatId ->
                    selectedIds = if (chatId in selectedIds) {
                        selectedIds - chatId
                    } else {
                        selectedIds + chatId
                    }
                },
                onSave = {
                    savedIds = selectedIds
                },
                onRetry = {},
                onLogout = {},
                logoutEnabled = true,
            )
        }

        composeRule.onNodeWithTag(ChannelSelectionTestTags.Search).performTextInput("二")
        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(hasTestTag(ChannelSelectionTestTags.row(2)))
        composeRule.onNodeWithText("频道二").assertIsDisplayed()
        composeRule.onAllNodesWithText("频道一").assertCountEquals(0)
        composeRule.onNodeWithTag(ChannelSelectionTestTags.row(2)).performClick()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(hasTestTag(ChannelSelectionTestTags.Search))
        composeRule.onNodeWithTag(ChannelSelectionTestTags.Search).performTextReplacement("one")
        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(hasTestTag(ChannelSelectionTestTags.row(1)))
        composeRule.onNodeWithText("频道一").assertIsDisplayed()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.row(1)).performClick()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(hasTestTag(ChannelSelectionTestTags.SelectionSummary))
        composeRule.onNodeWithText("已选择 2 个频道").assertIsDisplayed()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.Save).assertIsEnabled().performClick()

        assertEquals(setOf(1L, 2L), savedIds)
    }

    @Test
    fun loadingEmptyAndErrorStatesExposeProgressAndRetry() {
        var retryCalls = 0
        var state by mutableStateOf(ChannelSelectionUiState())
        composeRule.setContent {
            ChannelSelectionScreen(
                uiState = state,
                onSearchQueryChanged = {},
                onToggleChannel = {},
                onSave = {},
                onRetry = { retryCalls += 1 },
                onLogout = {},
                logoutEnabled = true,
            )
        }

        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(androidx.compose.ui.test.hasText("正在加载频道"))
        composeRule.onNodeWithText("正在加载频道").assertIsDisplayed()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.Progress).assertIsDisplayed()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.Save).assertIsNotEnabled()

        state = ChannelSelectionUiState(phase = ChannelListPhase.EMPTY)
        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(androidx.compose.ui.test.hasText("没有可用频道。仅显示已加入且仍有访问权限的频道。"))
        composeRule.onNodeWithText("没有可用频道。仅显示已加入且仍有访问权限的频道。")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.Retry).performClick()

        state = ChannelSelectionUiState(
            phase = ChannelListPhase.ERROR,
            failure = TelegramChatFailure.NetworkUnavailable,
        )
        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(androidx.compose.ui.test.hasText("网络不可用，无法刷新频道"))
        composeRule.onNodeWithText("网络不可用，无法刷新频道").assertIsDisplayed()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.Retry).performClick()

        assertEquals(2, retryCalls)
    }

    @Test
    fun scanProgressShowsDatabaseCountsAndPauseIntent() {
        var pauseCalls = 0
        composeRule.setContent {
            ChannelSelectionScreen(
                uiState = ChannelSelectionUiState(
                    phase = ChannelListPhase.CONTENT,
                    selectedCount = 1,
                    channels = listOf(
                        item(1, "频道一", null).copy(
                            isSelected = true,
                            scanStatus = VideoScanStatus.SCANNING,
                            videoSearchPageCount = 2,
                            processedVideoCandidateCount = 200,
                            indexedVideoCount = 12,
                        ),
                    ),
                    scanSummary = ChannelScanSummary(
                        processedVideoCandidateCount = 200,
                        videoSearchPageCount = 2,
                        indexedVideoCount = 12,
                        approximateVideoCount = 13,
                        totalChannelCount = 1,
                        canControl = true,
                    ),
                ),
                onSearchQueryChanged = {},
                onToggleChannel = {},
                onSave = {},
                onRetry = {},
                onLogout = {},
                logoutEnabled = true,
                onPauseScan = { pauseCalls += 1 },
            )
        }

        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(hasTestTag(ChannelSelectionTestTags.ScanControl))

        val collapsedSelectionHeight = composeRule
            .onNodeWithTag(ChannelSelectionTestTags.SelectionSummary)
            .getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        val collapsedScanHeight = composeRule
            .onNodeWithTag(ChannelSelectionTestTags.ScanSummary)
            .getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        assertTrue(
            "collapsed scan summary should stay compact: scan=$collapsedScanHeight selection=$collapsedSelectionHeight",
            collapsedScanHeight <= collapsedSelectionHeight * 1.4f,
        )

        composeRule.onNodeWithTag(ChannelSelectionTestTags.ScanControl).performClick()
        composeRule.onNodeWithText("处理视频 200 个").assertIsDisplayed()
        composeRule.onNodeWithText("已索引 12 个").assertIsDisplayed()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(androidx.compose.ui.test.hasText("扫描中 · 已处理 200 个视频"))
        composeRule.onNodeWithText("扫描中 · 已处理 200 个视频").assertIsDisplayed()
        composeRule.onNodeWithText("搜索 2 页 · 已索引 12 个").assertIsDisplayed()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.PinDetails).performClick()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.ScanDetails).performClick()
        composeRule.onNodeWithText("已处理视频").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("200 个").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("搜索页数").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2 页").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("唯一索引").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("12 个").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("完整频道").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("0 / 1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Telegram 估计约 13 个视频，仅供参考")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(hasTestTag(ChannelSelectionTestTags.SelectionSummary))
        val expandedSelectionHeight = composeRule
            .onNodeWithTag(ChannelSelectionTestTags.SelectionSummary)
            .getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        val explanationHeight = composeRule
            .onNodeWithText("已保存的选中频道会自动置顶；长按频道可切换手动置顶")
            .getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(hasTestTag(ChannelSelectionTestTags.ScanSummary))
        val expandedScanHeight = composeRule
            .onNodeWithTag(ChannelSelectionTestTags.ScanSummary)
            .getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        assertTrue(
            "expanded summary should add readable explanation without excess blank space: " +
                "collapsed=$collapsedSelectionHeight expanded=$expandedSelectionHeight text=$explanationHeight",
            expandedSelectionHeight <= collapsedSelectionHeight + explanationHeight + 12f,
        )
        assertTrue(
            "expanded scan summary should expose more information than its compact row",
            expandedScanHeight > collapsedScanHeight,
        )
        val scanDetailsBounds = composeRule
            .onNodeWithTag(ChannelSelectionTestTags.ScanDetails)
            .fetchSemanticsNode().boundsInRoot
        val scanControlBounds = composeRule
            .onNodeWithTag(ChannelSelectionTestTags.ScanControl)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "expanded scan details and control should remain in the same action row",
            scanDetailsBounds.top < scanControlBounds.bottom &&
                scanControlBounds.top < scanDetailsBounds.bottom,
        )
        assertEquals(1, pauseCalls)
    }

    @Test
    fun paginationStallExplainsThatIndexIsPreservedAndCanBeResumed() {
        composeRule.setContent {
            ChannelSelectionScreen(
                uiState = ChannelSelectionUiState(
                    phase = ChannelListPhase.CONTENT,
                    selectedCount = 1,
                    channels = listOf(item(1, "频道一", null).copy(isSelected = true)),
                    scanSummary = ChannelScanSummary(
                        indexedVideoCount = 12,
                        totalChannelCount = 1,
                        isPaused = true,
                        canControl = true,
                        failure = TelegramMessageFailure.PaginationStalled,
                    ),
                ),
                onSearchQueryChanged = {},
                onToggleChannel = {},
                onSave = {},
                onRetry = {},
                onLogout = {},
                logoutEnabled = true,
            )
        }

        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(hasTestTag(ChannelSelectionTestTags.ScanSummary))
        composeRule.onNodeWithText("视频搜索分页游标停滞；已保留索引，可手动继续重试")
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.ScanControl).assertIsDisplayed()
    }

    @Test
    fun narrowLargeFontKeepsPrimaryBrowseAndMovesLogoutBehindOverflow() {
        val events = mutableListOf<String>()
        var state by mutableStateOf(
            ChannelSelectionUiState(
                phase = ChannelListPhase.CONTENT,
                channels = listOf(item(1, "频道一", "one")),
            ),
        )
        composeRule.setContent {
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(systemDensity.density, 2f),
            ) {
                ChannelVideoFlowTheme {
                    Box(modifier = androidx.compose.ui.Modifier.width(320.dp).fillMaxHeight()) {
                        ChannelSelectionScreen(
                            uiState = state,
                            onSearchQueryChanged = {},
                            onToggleChannel = {},
                            onSave = {},
                            onRetry = {},
                            onLogout = { events += "logout" },
                            onOpenCacheSettings = { events += "cache" },
                            onOpenPlayback = { events += "browse" },
                            logoutEnabled = true,
                        )
                    }
                }
            }
        }

        val cache = composeRule.onNodeWithTag(ChannelSelectionTestTags.QuickCache)
        val browse = composeRule.onNodeWithTag(ChannelSelectionTestTags.QuickBrowse)
        val cacheBounds = cache.fetchSemanticsNode().boundsInRoot
        val browseBounds = browse.fetchSemanticsNode().boundsInRoot

        assertTrue(cacheBounds.width >= 48f)
        assertTrue(cacheBounds.height >= 48f)
        assertTrue(browseBounds.height >= 48f)

        browse.assertIsDisplayed().assertIsNotEnabled().assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "尚未索引到视频，浏览视频暂不可用",
            ),
        )
        composeRule.onNodeWithContentDescription("缓存设置").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("浏览视频").assertIsDisplayed()
        cache.performClick()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.Overflow).performClick()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.QuickLogout)
            .assertIsDisplayed()
            .performClick()

        state = state.copy(scanSummary = ChannelScanSummary(indexedVideoCount = 1))
        browse.assertIsEnabled().performClick()
        assertEquals(listOf("cache", "logout", "browse"), events)
    }

    @Test
    fun oneMainScrollContainerReachesLateChannelsWhileSaveStaysVisible() {
        val channels = (1L..30L).map { id -> item(id, "频道$id", "channel$id") }
        composeRule.setContent {
            ChannelVideoFlowTheme {
                ChannelSelectionScreen(
                    uiState = ChannelSelectionUiState(
                        phase = ChannelListPhase.CONTENT,
                        channels = channels,
                        canSave = true,
                    ),
                    onSearchQueryChanged = {},
                    onToggleChannel = {},
                    onSave = {},
                    onRetry = {},
                    onLogout = {},
                    logoutEnabled = true,
                )
            }
        }

        val listHeight = composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue("main list must remain a usable scroll container", listHeight > 0f)
        composeRule.onNodeWithTag(ChannelSelectionTestTags.Save).assertIsDisplayed()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(hasTestTag(ChannelSelectionTestTags.row(30)))
        composeRule.onNodeWithTag(ChannelSelectionTestTags.row(30)).assertIsDisplayed()
        composeRule.onNodeWithTag(ChannelSelectionTestTags.Save).assertIsDisplayed()
    }

    @Test
    fun longPressTogglesManualPinWithoutTogglingSelection() {
        var pinnedIds by mutableStateOf(emptySet<Long>())
        var selectedIds by mutableStateOf(emptySet<Long>())
        composeRule.setContent {
            ChannelSelectionScreen(
                uiState = ChannelSelectionUiState(
                    phase = ChannelListPhase.CONTENT,
                    channels = listOf(
                        item(1, "频道一", null).copy(
                            isSelected = 1L in selectedIds,
                            isPinned = 1L in pinnedIds,
                        ),
                    ),
                    selectedCount = selectedIds.size,
                ),
                onSearchQueryChanged = {},
                onToggleChannel = { chatId ->
                    selectedIds = if (chatId in selectedIds) selectedIds - chatId else selectedIds + chatId
                },
                onToggleChannelPinned = { chatId ->
                    pinnedIds = if (chatId in pinnedIds) pinnedIds - chatId else pinnedIds + chatId
                },
                onSave = {},
                onRetry = {},
                onLogout = {},
                logoutEnabled = true,
            )
        }

        composeRule.onNodeWithTag(ChannelSelectionTestTags.MainList)
            .performScrollToNode(hasTestTag(ChannelSelectionTestTags.row(1)))
        composeRule.onNodeWithTag(ChannelSelectionTestTags.row(1)).performTouchInput {
            longClick()
        }
        composeRule.onNodeWithText("置顶").assertIsDisplayed()
        assertEquals(setOf(1L), pinnedIds)
        assertTrue(selectedIds.isEmpty())

        composeRule.onNodeWithTag(ChannelSelectionTestTags.row(1)).performTouchInput {
            longClick()
        }
        composeRule.onAllNodesWithText("置顶").assertCountEquals(0)
        assertTrue(pinnedIds.isEmpty())
        assertTrue(selectedIds.isEmpty())
    }

    private fun item(
        id: Long,
        title: String,
        username: String?,
    ) = ChannelSelectionItem(id, title, username, isSelected = false)
}
