package com.qixuan.channelvideoflow.navigation

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.qixuan.channelvideoflow.domain.channel.TelegramChatRepository
import com.qixuan.channelvideoflow.model.channel.TelegramChannel
import com.qixuan.channelvideoflow.model.channel.TelegramChatSyncState
import com.qixuan.channelvideoflow.model.video.TagFilterMode
import com.qixuan.channelvideoflow.model.video.VideoFeedOrder
import com.qixuan.channelvideoflow.model.video.VideoFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthorizedNavigationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun savedStateRecreatesDestinationFilterAndOrderTogether() = runTest(dispatcher) {
        val repository = FakeChatRepository(selectedIds = setOf(1L, 2L))
        val handle = SavedStateHandle()
        val first = AuthorizedNavigationViewModel(handle, repository)
        val filter = VideoFilter(setOf(1L, 2L), setOf("音乐"), TagFilterMode.AND)

        first.openFeed(filter)
        first.updateOrder(VideoFeedOrder.LATEST)
        val restoredBundle = requireNotNull(handle.get<Bundle>("authorized_navigation"))
        val recreated = AuthorizedNavigationViewModel(
            SavedStateHandle(mapOf("authorized_navigation" to restoredBundle)),
            repository,
        )

        assertEquals(
            AuthorizedNavigationState(AuthorizedDestination.FEED, filter, VideoFeedOrder.LATEST),
            recreated.state.value,
        )
    }

    @Test
    fun readyChannelSnapshotInvalidatesRemovedFilter() = runTest(dispatcher) {
        val repository = FakeChatRepository(selectedIds = setOf(1L))
        val handle = SavedStateHandle(
            mapOf(
                "authorized_navigation" to AuthorizedNavigationStateCodec.encode(
                    AuthorizedNavigationState(
                        destination = AuthorizedDestination.FEED,
                        filter = VideoFilter(setOf(1L, 2L)),
                    ),
                ),
            ),
        )

        val viewModel = AuthorizedNavigationViewModel(handle, repository)
        testScheduler.runCurrent()

        assertEquals(AuthorizedNavigationState(), viewModel.state.value)
    }

    private class FakeChatRepository(selectedIds: Set<Long>) : TelegramChatRepository {
        private val channelState = MutableStateFlow(
            selectedIds.map { id -> TelegramChannel(id, "channel-$id", null, isSelected = true) },
        )
        override val channels: Flow<List<TelegramChannel>> = channelState
        override val syncState = MutableStateFlow<TelegramChatSyncState>(TelegramChatSyncState.Ready)
        override suspend fun refresh() = Unit
        override suspend fun saveSelectedChannelIds(chatIds: Set<Long>) = Unit
        override suspend fun setChannelPinned(chatId: Long, isPinned: Boolean) = Unit
    }
}
