package com.qixuan.channelvideoflow.telegram.message

import com.qixuan.channelvideoflow.database.ChannelEntity
import com.qixuan.channelvideoflow.database.ChannelScanRecord
import com.qixuan.channelvideoflow.database.PersistedVideo
import com.qixuan.channelvideoflow.database.TagSummaryRecord
import com.qixuan.channelvideoflow.database.VideoEntity
import com.qixuan.channelvideoflow.database.VideoFeedKeyRow
import com.qixuan.channelvideoflow.database.VideoIndexDao
import com.qixuan.channelvideoflow.database.VideoPageWrite
import com.qixuan.channelvideoflow.database.VideoTagRecord
import com.qixuan.channelvideoflow.model.channel.ChannelScanState
import com.qixuan.channelvideoflow.model.video.TagFilterMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal interface MessageIndexStore {
    fun observeSelectedChannelScans(): Flow<List<ChannelScanRecord>>

    suspend fun getSelectedScanChannels(): List<ChannelEntity>

    suspend fun getChannel(chatId: Long): ChannelEntity?

    suspend fun commitPage(page: VideoPageWrite)

    suspend fun upsertIncremental(persisted: PersistedVideo, committedAt: Long)

    suspend fun recordIncrementalPosition(chatId: Long, messageId: Long, committedAt: Long)

    suspend fun getVideo(chatId: Long, messageId: Long): VideoEntity?

    suspend fun replaceVideoAndTags(persisted: PersistedVideo)

    suspend fun markUnsupportedEdit(chatId: Long, messageId: Long, invalidatedAt: Long)

    suspend fun updateEditTime(chatId: Long, messageId: Long, editTime: Long?)

    suspend fun deleteMessages(chatId: Long, messageIds: List<Long>, invalidatedAt: Long)

    suspend fun purgeInvalidatedBefore(cutoffExclusive: Long): Int

    suspend fun updateScanFailure(
        chatId: Long,
        state: ChannelScanState,
        failureCode: String,
        failureDetail: Int?,
        retryAt: Long?,
        retryCount: Int,
    )

    suspend fun markAccessLost(chatId: Long)

    suspend fun setForegroundScanning(isForeground: Boolean)

    suspend fun setUserPaused(paused: Boolean)

    suspend fun clearAllIndex()

    fun observeFilteredVideos(
        channelIds: Set<Long>,
        normalizedTags: Set<String>,
        tagMode: TagFilterMode,
    ): Flow<List<VideoEntity>>

    fun observeFilteredVideoKeys(
        channelIds: Set<Long>,
        normalizedTags: Set<String>,
        tagMode: TagFilterMode,
    ): Flow<List<VideoFeedKeyRow>> = observeFilteredVideos(channelIds, normalizedTags, tagMode)
        .map { entities ->
            entities.map { entity ->
                VideoFeedKeyRow(
                    chatId = entity.chatId,
                    messageId = entity.messageId,
                    publishTime = entity.publishTime,
                    editTime = entity.editTime,
                )
            }
        }

    suspend fun getVideosForKeyWindow(keys: List<Pair<Long, Long>>): List<VideoEntity> =
        keys.mapNotNull { (chatId, messageId) -> getVideo(chatId, messageId) }

    suspend fun getVideoTagsForKeyWindow(keys: List<Pair<Long, Long>>): List<VideoTagRecord> {
        val exactKeys = keys.toSet()
        return getVideoTagsForChannels(keys.map { it.first }.distinct())
            .filter { record -> record.chatId to record.messageId in exactKeys }
    }

    suspend fun getVideoTagsForChannels(channelIds: List<Long>): List<VideoTagRecord>

    fun observeTagSummaries(channelIds: Set<Long>): Flow<List<TagSummaryRecord>>
}

internal class RoomMessageIndexStore(
    private val dao: VideoIndexDao,
) : MessageIndexStore {
    override fun observeSelectedChannelScans(): Flow<List<ChannelScanRecord>> =
        dao.observeSelectedChannelScans()

    override suspend fun getSelectedScanChannels(): List<ChannelEntity> =
        dao.getSelectedScanChannels()

    override suspend fun getChannel(chatId: Long): ChannelEntity? = dao.getChannel(chatId)

    override suspend fun commitPage(page: VideoPageWrite) = dao.commitPage(page)

    override suspend fun upsertIncremental(persisted: PersistedVideo, committedAt: Long) =
        dao.upsertIncremental(persisted, committedAt)

    override suspend fun recordIncrementalPosition(
        chatId: Long,
        messageId: Long,
        committedAt: Long,
    ) = dao.recordIncrementalPosition(chatId, messageId, committedAt)

    override suspend fun getVideo(chatId: Long, messageId: Long): VideoEntity? =
        dao.getVideo(chatId, messageId)

    override suspend fun replaceVideoAndTags(persisted: PersistedVideo) =
        dao.replaceVideoAndTags(persisted)

    override suspend fun markUnsupportedEdit(chatId: Long, messageId: Long, invalidatedAt: Long) =
        dao.markUnsupportedEdit(chatId, messageId, invalidatedAt)

    override suspend fun updateEditTime(chatId: Long, messageId: Long, editTime: Long?) =
        dao.updateEditTime(chatId, messageId, editTime)

    override suspend fun deleteMessages(
        chatId: Long,
        messageIds: List<Long>,
        invalidatedAt: Long,
    ) = dao.deleteMessages(chatId, messageIds, invalidatedAt)

    override suspend fun purgeInvalidatedBefore(cutoffExclusive: Long): Int =
        dao.purgeInvalidatedBefore(cutoffExclusive)

    override suspend fun updateScanFailure(
        chatId: Long,
        state: ChannelScanState,
        failureCode: String,
        failureDetail: Int?,
        retryAt: Long?,
        retryCount: Int,
    ) = dao.updateScanFailure(
        chatId,
        state,
        failureCode,
        failureDetail,
        retryAt,
        retryCount,
    )

    override suspend fun markAccessLost(chatId: Long) = dao.markAccessLost(chatId)

    override suspend fun setForegroundScanning(isForeground: Boolean) =
        dao.setForegroundScanning(isForeground)

    override suspend fun setUserPaused(paused: Boolean) = dao.setUserPaused(paused)

    override suspend fun clearAllIndex() = dao.clearAllIndex()

    override fun observeFilteredVideos(
        channelIds: Set<Long>,
        normalizedTags: Set<String>,
        tagMode: TagFilterMode,
    ): Flow<List<VideoEntity>> = dao.observeFilteredVideos(channelIds, normalizedTags, tagMode)

    override fun observeFilteredVideoKeys(
        channelIds: Set<Long>,
        normalizedTags: Set<String>,
        tagMode: TagFilterMode,
    ): Flow<List<VideoFeedKeyRow>> = dao.observeFilteredVideoKeys(channelIds, normalizedTags, tagMode)

    override suspend fun getVideosForKeyWindow(keys: List<Pair<Long, Long>>): List<VideoEntity> =
        keys.distinct().groupBy { it.first }.flatMap { (chatId, rows) ->
            rows.chunked(400).flatMap { chunk ->
                dao.getVideosForKeyWindow(listOf(chatId), chunk.map { it.second })
            }
        }

    override suspend fun getVideoTagsForKeyWindow(keys: List<Pair<Long, Long>>): List<VideoTagRecord> =
        keys.distinct().groupBy { it.first }.flatMap { (chatId, rows) ->
            rows.chunked(400).flatMap { chunk ->
                dao.getVideoTagsForKeyWindow(listOf(chatId), chunk.map { it.second })
            }
        }

    override suspend fun getVideoTagsForChannels(channelIds: List<Long>): List<VideoTagRecord> =
        dao.getVideoTagsForChannels(channelIds)

    override fun observeTagSummaries(channelIds: Set<Long>): Flow<List<TagSummaryRecord>> =
        dao.observeTagSummaries(channelIds)
}
