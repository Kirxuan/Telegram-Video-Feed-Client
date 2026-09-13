package com.qixuan.channelvideoflow.feature.tags

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qixuan.channelvideoflow.model.video.TagFilterMode
import com.qixuan.channelvideoflow.model.video.TagSummary
import com.qixuan.channelvideoflow.ui.theme.ChannelVideoFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagFilterScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun modeTagClearAndContinueExposeExactlyOneIntentEach() {
        val events = mutableListOf<String>()
        val state = TagFilterUiState(
            isLoading = false,
            channelIds = setOf(10L, 11L),
            tags = listOf(
                TagFilterItem(TagSummary("news", "#新闻", 12), isSelected = false),
                TagFilterItem(TagSummary("long", "#很长的中英文标签MixedContent", 3), isSelected = true),
            ),
            totalTagCount = 2,
            selectedNames = setOf("long"),
        )
        composeRule.setContent {
            TagFilterScreen(
                uiState = state,
                onBack = { events += "back" },
                onTagToggle = { events += "tag:$it" },
                onModeChanged = { events += "mode:${it.name}" },
                onClearSelection = { events += "clear-selection" },
                onContinue = { events += "continue" },
            )
        }

        composeRule.onNodeWithTag(TagFilterTestTags.List)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TagFilterTestTags.tag("news")))
        composeRule.onNodeWithTag(TagFilterTestTags.tag("news")).performClick()
        composeRule.onNodeWithTag(TagFilterTestTags.List)
            .performScrollToNode(androidx.compose.ui.test.hasText("全部标签"))
        composeRule.onNodeWithText("全部标签").performClick()
        composeRule.onNodeWithTag(TagFilterTestTags.List)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TagFilterTestTags.ClearSelection))
        composeRule.onNodeWithTag(TagFilterTestTags.ClearSelection).performClick()
        composeRule.onNodeWithTag(TagFilterTestTags.Continue).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(TagFilterTestTags.List).performScrollToNode(
            androidx.compose.ui.test.hasTestTag(TagFilterTestTags.tag("long")),
        )
        composeRule.onNodeWithText("#很长的中英文标签MixedContent").assertIsDisplayed()

        assertEquals(
            listOf("tag:news", "mode:AND", "clear-selection", "continue"),
            events,
        )
    }

    @Test
    fun searchClearAndNoResultsAreDiscoverableAndKeepContinueAvailable() {
        val allTags = listOf(
            TagSummary("新闻", "#新闻", 12),
            TagSummary("kotlin", "#Kotlin", 8),
        )
        var query by mutableStateOf("")
        composeRule.setContent {
            val normalizedQuery = normalizeTagSearchQuery(query)
            val visible = allTags.filter { summary -> SearchableTag(summary).matches(normalizedQuery) }
            TagFilterScreen(
                uiState = TagFilterUiState(
                    isLoading = false,
                    channelIds = setOf(10L),
                    tags = visible.map { summary -> TagFilterItem(summary, isSelected = false) },
                    totalTagCount = allTags.size,
                    searchQuery = query,
                ),
                onBack = {},
                onTagToggle = {},
                onModeChanged = {},
                onClearSelection = {},
                onContinue = {},
                onSearchQueryChanged = { query = it },
                onClearSearch = { query = "" },
            )
        }

        composeRule.onNodeWithContentDescription("搜索标签").assertIsDisplayed()
        composeRule.onNodeWithTag(TagFilterTestTags.Search).performTextInput("KOTLIN")
        composeRule.onNodeWithTag(TagFilterTestTags.List).performScrollToNode(
            androidx.compose.ui.test.hasTestTag(TagFilterTestTags.tag("kotlin")),
        )
        composeRule.onNodeWithText("#Kotlin").assertIsDisplayed()
        composeRule.onNodeWithTag(TagFilterTestTags.Search).performTextReplacement("missing")
        composeRule.onNodeWithTag(TagFilterTestTags.NoResults).assertIsDisplayed()
        composeRule.onNodeWithText("没有匹配的标签").assertIsDisplayed()
        composeRule.onNodeWithTag(TagFilterTestTags.Continue).assertIsEnabled()
        composeRule.onNodeWithTag(TagFilterTestTags.List)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TagFilterTestTags.ClearSearch))
        composeRule.onNodeWithTag(TagFilterTestTags.ClearSearch).performClick()
        composeRule.onNodeWithTag(TagFilterTestTags.List)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TagFilterTestTags.tag("新闻")))
        composeRule.onNodeWithText("#新闻").assertIsDisplayed()
        composeRule.onNodeWithTag(TagFilterTestTags.List).performScrollToNode(
            androidx.compose.ui.test.hasTestTag(TagFilterTestTags.tag("kotlin")),
        )
        composeRule.onNodeWithText("#Kotlin").assertIsDisplayed()
    }

    @Test
    fun clearSearchAndClearSelectionRemainSeparateActions() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            TagFilterScreen(
                uiState = TagFilterUiState(
                    isLoading = false,
                    channelIds = setOf(10L),
                    tags = listOf(TagFilterItem(TagSummary("news", "#新闻", 12), true)),
                    totalTagCount = 2,
                    selectedNames = setOf("news"),
                    searchQuery = "新闻",
                ),
                onBack = {},
                onTagToggle = {},
                onModeChanged = {},
                onClearSelection = { events += "selection" },
                onContinue = {},
                onSearchQueryChanged = { query -> if (query.isEmpty()) events += "search" },
                onClearSearch = { events += "search-recovery" },
            )
        }

        composeRule.onNodeWithTag(TagFilterTestTags.List)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TagFilterTestTags.ClearSearch))
        composeRule.onNodeWithTag(TagFilterTestTags.ClearSearch).performClick()
        composeRule.onNodeWithTag(TagFilterTestTags.List)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TagFilterTestTags.ClearSelection))
        composeRule.onNodeWithTag(TagFilterTestTags.ClearSelection).performClick()
        assertEquals(listOf("search", "selection"), events)
    }

    @Test
    fun missingChannelLoadingEmptyAndSearchEmptyStatesRemainExplicit() {
        var state by mutableStateOf(TagFilterUiState())
        composeRule.setContent {
            TagFilterScreen(
                uiState = state,
                onBack = {},
                onTagToggle = {},
                onModeChanged = {},
                onClearSelection = {},
                onContinue = {},
            )
        }

        composeRule.onNodeWithTag(TagFilterTestTags.List).performScrollToNode(
            androidx.compose.ui.test.hasTestTag(TagFilterTestTags.Loading),
        )
        composeRule.onNodeWithTag(TagFilterTestTags.Loading).assertIsDisplayed()
        composeRule.onNodeWithTag(TagFilterTestTags.Continue).assertIsNotEnabled()

        state = TagFilterUiState(isLoading = false)
        composeRule.onNodeWithTag(TagFilterTestTags.List).performScrollToNode(
            androidx.compose.ui.test.hasText("尚未选择频道"),
        )
        composeRule.onNodeWithText("尚未选择频道").assertIsDisplayed()
        composeRule.onNodeWithText("请返回频道页，至少选择一个频道后再筛选。")
            .performScrollTo()
            .assertIsDisplayed()

        state = TagFilterUiState(isLoading = false, channelIds = setOf(10L))
        composeRule.onNodeWithTag(TagFilterTestTags.List).performScrollToNode(
            androidx.compose.ui.test.hasText("暂无标签"),
        )
        composeRule.onNodeWithText("暂无标签").assertIsDisplayed()
        composeRule.onNodeWithText("浏览全部视频").assertIsDisplayed()
        composeRule.onNodeWithTag(TagFilterTestTags.Continue).assertIsEnabled()

        state = TagFilterUiState(
            isLoading = false,
            channelIds = setOf(10L),
            totalTagCount = 2,
            selectedNames = setOf("hidden"),
            searchQuery = "missing",
        )
        composeRule.onNodeWithTag(TagFilterTestTags.List).performScrollToNode(
            androidx.compose.ui.test.hasText("没有匹配的标签"),
        )
        composeRule.onNodeWithText("没有匹配的标签").assertIsDisplayed()
        composeRule.onNodeWithText("应用筛选并浏览").assertIsDisplayed()
    }

    @Test
    fun backModeAndTagRowsExposeButtonTabAndCheckboxSemantics() {
        composeRule.setContent {
            TagFilterScreen(
                uiState = TagFilterUiState(
                    isLoading = false,
                    channelIds = setOf(10L),
                    tags = listOf(
                        TagFilterItem(TagSummary("news", "#新闻", 12), true),
                        TagFilterItem(TagSummary("music", "#音乐", 8), false),
                    ),
                    totalTagCount = 2,
                    selectedNames = setOf("news"),
                ),
                onBack = {},
                onTagToggle = {},
                onModeChanged = {},
                onClearSelection = {},
                onContinue = {},
            )
        }

        composeRule.onNodeWithContentDescription("返回频道选择")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeRule.onNodeWithText("任一标签").assertIsSelected()
        composeRule.onNodeWithTag(TagFilterTestTags.List)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(TagFilterTestTags.tag("news")))
        composeRule.onNodeWithTag(TagFilterTestTags.tag("news"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertIsOn()
        composeRule.onNodeWithTag(TagFilterTestTags.List).performScrollToNode(
            androidx.compose.ui.test.hasTestTag(TagFilterTestTags.tag("music")),
        )
        composeRule.onNodeWithTag(TagFilterTestTags.tag("music")).assertIsOff()
    }

    @Test
    fun narrowDarkLargeFontKeepsSearchModesAndBottomActionReadable() {
        val longLabel = "#" + "很长的中英文标签MixedContent需要继续展开才能完整阅读".repeat(8)
        composeRule.setContent {
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(systemDensity.density, 2f),
            ) {
                ChannelVideoFlowTheme(darkTheme = true) {
                    Box(modifier = Modifier.width(320.dp).fillMaxHeight()) {
                        TagFilterScreen(
                            uiState = TagFilterUiState(
                                isLoading = false,
                                channelIds = setOf(10L),
                                tags = listOf(
                                    TagFilterItem(
                                        TagSummary("long", longLabel, 3),
                                        true,
                                    ),
                                ),
                                totalTagCount = 1,
                                selectedNames = setOf("long"),
                            ),
                            onBack = {},
                            onTagToggle = {},
                            onModeChanged = {},
                            onClearSelection = {},
                            onContinue = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(TagFilterTestTags.Search).assertIsDisplayed()
        composeRule.onNodeWithTag(TagFilterTestTags.List).performScrollToNode(
            androidx.compose.ui.test.hasTestTag(TagFilterTestTags.Mode),
        )
        composeRule.onNodeWithText("任一标签").assertIsDisplayed()
        composeRule.onNodeWithText("全部标签").assertIsDisplayed()
        composeRule.onNodeWithTag(TagFilterTestTags.Continue).assertIsDisplayed()
        composeRule.onNodeWithTag(TagFilterTestTags.List).performScrollToNode(
            androidx.compose.ui.test.hasTestTag(TagFilterTestTags.tag("long")),
        )
        composeRule.waitUntil(1_000L) {
            composeRule.onAllNodesWithTag(TagFilterTestTags.expand("long"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        val labelBounds = composeRule.onNodeWithText(longLabel, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val countBounds = composeRule.onNodeWithText("3 个视频", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("video count should stay in the trailing column", countBounds.left > labelBounds.left)
        composeRule.onNodeWithTag(TagFilterTestTags.expand("long")).performScrollTo().performClick()
        composeRule.onNodeWithText("收起标签").fetchSemanticsNode()
    }
}
