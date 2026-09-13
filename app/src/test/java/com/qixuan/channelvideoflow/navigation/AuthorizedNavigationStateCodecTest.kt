package com.qixuan.channelvideoflow.navigation

import android.os.Bundle
import com.qixuan.channelvideoflow.model.video.TagFilterMode
import com.qixuan.channelvideoflow.model.video.VideoFeedOrder
import com.qixuan.channelvideoflow.model.video.VideoFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthorizedNavigationStateCodecTest {
    @Test
    fun feedDestinationFilterAndOrderRoundTripAtomically() {
        val expected = AuthorizedNavigationState(
            destination = AuthorizedDestination.FEED,
            filter = VideoFilter(
                channelIds = setOf(22L, 11L),
                normalizedTags = setOf("音乐", "kotlin"),
                tagMode = TagFilterMode.AND,
            ),
            order = VideoFeedOrder.LATEST,
        )

        assertEquals(expected, AuthorizedNavigationStateCodec.decode(AuthorizedNavigationStateCodec.encode(expected)))
    }

    @Test
    fun incompatibleOrIncompleteSnapshotFailsClosed() {
        assertNull(AuthorizedNavigationStateCodec.decode(Bundle().apply { putInt("version", 99) }))

        val incompleteFeed = Bundle().apply {
            putInt("version", 1)
            putString("destination", AuthorizedDestination.FEED.name)
            putString("order", VideoFeedOrder.RANDOM.name)
        }
        assertEquals(AuthorizedNavigationState(), AuthorizedNavigationStateCodec.decode(incompleteFeed))
    }

    @Test
    fun invalidSelectedChannelSnapshotReturnsSafeEntry() {
        val state = AuthorizedNavigationState(
            destination = AuthorizedDestination.FEED,
            filter = VideoFilter(setOf(1L, 2L)),
        )

        assertEquals(AuthorizedNavigationState(), state.validatedAgainst(setOf(1L)))
        assertEquals(state, state.validatedAgainst(setOf(1L, 2L, 3L)))
    }
}
