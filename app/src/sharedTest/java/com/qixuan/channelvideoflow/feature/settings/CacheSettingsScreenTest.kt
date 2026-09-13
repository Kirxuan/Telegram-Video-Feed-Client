package com.qixuan.channelvideoflow.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qixuan.channelvideoflow.domain.cache.MediaCacheLimits
import com.qixuan.channelvideoflow.domain.cache.MediaCacheState
import com.qixuan.channelvideoflow.model.video.VideoQualityPreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun twentyGiBLimitIsVisibleOnEntryWithoutScrolling() {
        composeRule.setContent {
            CacheSettingsScreen(
                uiState = CacheSettingsUiState(MediaCacheState(limitBytes = 20L * MediaCacheLimits.GIBIBYTE)),
                onBack = {}, onLimitSelected = {}, onMobilePreloadChanged = {},
                onVideoQualitySelected = {}, onRefresh = {}, onClear = {},
            )
        }
        composeRule.onNodeWithText("当前缓存上限 20 GB").assertIsDisplayed()
    }

    @Test
    fun showsExactUsageDefaultLimitAndAllRequestedLimitChoices() {
        var selected: Long? = null
        composeRule.setContent {
            CacheSettingsScreen(
                uiState = CacheSettingsUiState(
                    MediaCacheState(
                        usedBytes = 300L * MediaCacheLimits.MEBIBYTE,
                        isExactUsage = true,
                    ),
                ),
                onBack = {},
                onLimitSelected = { selected = it },
                onMobilePreloadChanged = {},
                onVideoQualitySelected = {},
                onRefresh = {},
                onClear = {},
            )
        }

        composeRule
            .onNodeWithText("当前视频缓存：300 MB")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CacheSettingsTestTags.LimitMenu).performScrollTo().performClick()
        composeRule.onNodeWithTag(
            CacheSettingsTestTags.limit(MediaCacheLimits.DEFAULT_BYTES),
        ).assertExists()
        MediaCacheLimits.allowedBytes.forEach { bytes ->
            composeRule.onNodeWithTag(CacheSettingsTestTags.limit(bytes)).assertExists()
        }
        val oneGiB = MediaCacheLimits.GIBIBYTE
        composeRule.onNodeWithTag(CacheSettingsTestTags.limit(oneGiB)).performScrollTo().performClick()
        assertEquals(oneGiB, selected)
    }

    @Test
    fun manualClearRequiresConfirmationBeforeCallingController() {
        var clears = 0
        composeRule.setContent {
            CacheSettingsScreen(
                uiState = CacheSettingsUiState(),
                onBack = {},
                onLimitSelected = {},
                onMobilePreloadChanged = {},
                onVideoQualitySelected = {},
                onRefresh = {},
                onClear = { clears += 1 },
            )
        }

        composeRule.onNodeWithTag(CacheSettingsTestTags.Clear).performScrollTo().performClick()
        assertEquals(0, clears)
        composeRule.onNodeWithTag(CacheSettingsTestTags.ConfirmClear).performClick()
        assertEquals(1, clears)
    }

    @Test
    fun showsAndSelectsAllServerQualityChoices() {
        var selected: VideoQualityPreference? = null
        composeRule.setContent {
            CacheSettingsScreen(
                uiState = CacheSettingsUiState(),
                onBack = {},
                onLimitSelected = {},
                onMobilePreloadChanged = {},
                onVideoQualitySelected = { selected = it },
                onRefresh = {},
                onClear = {},
            )
        }

        VideoQualityPreference.entries.forEach { preference ->
            composeRule.onNodeWithTag(CacheSettingsTestTags.quality(preference)).assertExists()
        }
        composeRule
            .onNodeWithTag(CacheSettingsTestTags.quality(VideoQualityPreference.DATA_SAVER))
            .performScrollTo().performClick()
        assertEquals(VideoQualityPreference.DATA_SAVER, selected)
    }
}
