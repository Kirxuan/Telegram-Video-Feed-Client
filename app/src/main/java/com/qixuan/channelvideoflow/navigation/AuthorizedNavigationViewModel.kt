package com.qixuan.channelvideoflow.navigation

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qixuan.channelvideoflow.domain.channel.TelegramChatRepository
import com.qixuan.channelvideoflow.model.channel.TelegramChatSyncState
import com.qixuan.channelvideoflow.model.video.DEFAULT_VIDEO_FEED_ORDER
import com.qixuan.channelvideoflow.model.video.TagFilterMode
import com.qixuan.channelvideoflow.model.video.VideoFeedOrder
import com.qixuan.channelvideoflow.model.video.VideoFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal enum class AuthorizedDestination {
    CHANNELS,
    TAGS,
    FEED,
    SETTINGS,
}

internal data class AuthorizedNavigationState(
    val destination: AuthorizedDestination = AuthorizedDestination.CHANNELS,
    val filter: VideoFilter? = null,
    val order: VideoFeedOrder = DEFAULT_VIDEO_FEED_ORDER,
) {
    fun sanitized(): AuthorizedNavigationState = when {
        destination == AuthorizedDestination.FEED &&
            (filter == null || filter.channelIds.isEmpty()) -> AuthorizedNavigationState()
        destination != AuthorizedDestination.FEED && filter != null -> copy(filter = null)
        else -> this
    }

    fun validatedAgainst(selectedChannelIds: Set<Long>): AuthorizedNavigationState =
        if (filter?.channelIds.orEmpty().let { ids -> ids.isNotEmpty() && !selectedChannelIds.containsAll(ids) }) {
            AuthorizedNavigationState()
        } else {
            this
        }
}

internal object AuthorizedNavigationStateCodec {
    fun encode(state: AuthorizedNavigationState): Bundle = Bundle().apply {
        putInt(KEY_VERSION, VERSION)
        putString(KEY_DESTINATION, state.destination.name)
        putString(KEY_ORDER, state.order.name)
        state.filter?.let { filter ->
            putLongArray(KEY_CHANNEL_IDS, filter.channelIds.sorted().toLongArray())
            putStringArrayList(KEY_TAGS, ArrayList(filter.normalizedTags.sorted()))
            putString(KEY_TAG_MODE, filter.tagMode.name)
        }
    }

    fun decode(bundle: Bundle?): AuthorizedNavigationState? {
        if (bundle == null || bundle.getInt(KEY_VERSION, -1) != VERSION) return null
        val destination = bundle.getString(KEY_DESTINATION)
            ?.let { runCatching { AuthorizedDestination.valueOf(it) }.getOrNull() }
            ?: return null
        val order = bundle.getString(KEY_ORDER)
            ?.let { runCatching { VideoFeedOrder.valueOf(it) }.getOrNull() }
            ?: return null
        val channelIds = bundle.getLongArray(KEY_CHANNEL_IDS)?.toSet().orEmpty()
        val filter = if (channelIds.isEmpty()) {
            null
        } else {
            val tagMode = bundle.getString(KEY_TAG_MODE)
                ?.let { runCatching { TagFilterMode.valueOf(it) }.getOrNull() }
                ?: return null
            VideoFilter(
                channelIds = channelIds,
                normalizedTags = bundle.getStringArrayList(KEY_TAGS)?.toSet().orEmpty(),
                tagMode = tagMode,
            )
        }
        return AuthorizedNavigationState(destination, filter, order).sanitized()
    }

    private const val VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_DESTINATION = "destination"
    private const val KEY_ORDER = "order"
    private const val KEY_CHANNEL_IDS = "channel_ids"
    private const val KEY_TAGS = "tags"
    private const val KEY_TAG_MODE = "tag_mode"
}

@HiltViewModel
internal class AuthorizedNavigationViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    chatRepository: TelegramChatRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        AuthorizedNavigationStateCodec.decode(savedStateHandle[STATE_KEY])
            ?: AuthorizedNavigationState(),
    )
    val state: StateFlow<AuthorizedNavigationState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(chatRepository.channels, chatRepository.syncState) { channels, sync ->
                channels to sync
            }.collect { (channels, sync) ->
                if (sync != TelegramChatSyncState.Ready) return@collect
                val selectedIds = channels.asSequence()
                    .filter { channel -> channel.isSelected }
                    .map { channel -> channel.chatId }
                    .toSet()
                val validated = mutableState.value.validatedAgainst(selectedIds)
                if (validated != mutableState.value) update(validated)
            }
        }
    }

    fun openTags() = update(AuthorizedNavigationState(AuthorizedDestination.TAGS))

    fun openSettings() = update(AuthorizedNavigationState(AuthorizedDestination.SETTINGS))

    fun openFeed(filter: VideoFilter) = update(
        AuthorizedNavigationState(
            destination = AuthorizedDestination.FEED,
            filter = filter,
            order = DEFAULT_VIDEO_FEED_ORDER,
        ).sanitized(),
    )

    fun updateOrder(order: VideoFeedOrder) = update(mutableState.value.copy(order = order))

    fun back() {
        update(
            when (mutableState.value.destination) {
                AuthorizedDestination.FEED -> AuthorizedNavigationState(AuthorizedDestination.TAGS)
                AuthorizedDestination.TAGS,
                AuthorizedDestination.SETTINGS,
                AuthorizedDestination.CHANNELS,
                -> AuthorizedNavigationState()
            },
        )
    }

    fun reset() = update(AuthorizedNavigationState())

    private fun update(next: AuthorizedNavigationState) {
        mutableState.value = next
        savedStateHandle[STATE_KEY] = AuthorizedNavigationStateCodec.encode(next)
    }

    private companion object {
        const val STATE_KEY = "authorized_navigation"
    }
}
