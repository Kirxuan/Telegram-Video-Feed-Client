package com.qixuan.channelvideoflow.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.qixuan.channelvideoflow.model.channel.ChannelAccessState
import com.qixuan.channelvideoflow.model.channel.ChannelScanState
import com.qixuan.channelvideoflow.model.video.TagFilterMode
import kotlinx.coroutines.flow.Flow

data class PersistedVideoTag(
    val normalizedName: String,
    val displayName: String,
)

data class PersistedVideo(
    val video: VideoEntity,
    val tags: List<PersistedVideoTag>,
)

data class VideoPageWrite(
    val chatId: Long,
    val videos: List<PersistedVideo>,
    val candidateCount: Int,
    val latestMessageId: Long?,
    val nextSearchCursor: Long,
    val advanceSearchCursor: Boolean,
    val searchCompleted: Boolean = false,
    val approximateTotalCount: Int? = null,
    val paginationStalled: Boolean = false,
    val committedAt: Long,
)

data class ChannelScanRecord(
    @Embedded
    val channel: ChannelEntity,
    @ColumnInfo(name = "indexed_video_count")
    val indexedVideoCount: Int,
)

data class VideoTagRecord(
    @ColumnInfo(name = "chat_id")
    val chatId: Long,
    @ColumnInfo(name = "message_id")
    val messageId: Long,
    @ColumnInfo(name = "normalized_tag_name")
    val normalizedTagName: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
)

data class VideoFeedKeyRow(
    @ColumnInfo(name = "chat_id")
    val chatId: Long,
    @ColumnInfo(name = "message_id")
    val messageId: Long,
    @ColumnInfo(name = "publish_time")
    val publishTime: Long,
    @ColumnInfo(name = "edit_time")
    val editTime: Long?,
)

data class TagSummaryRecord(
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "video_count")
    val videoCount: Int,
)

data class VideoIndexDiagnostics(
    @ColumnInfo(name = "video_candidate_count")
    val videoCandidateCount: Long,
    @ColumnInfo(name = "video_search_page_count")
    val videoSearchPageCount: Int,
    @ColumnInfo(name = "video_record_count")
    val videoRecordCount: Int,
    @ColumnInfo(name = "active_video_record_count")
    val activeVideoRecordCount: Int,
    @ColumnInfo(name = "duplicate_record_count")
    val duplicateRecordCount: Int,
    @ColumnInfo(name = "duplicate_encounter_count")
    val duplicateEncounterCount: Long,
    @ColumnInfo(name = "exception_count")
    val exceptionCount: Int,
)

@Dao
abstract class VideoIndexDao {
    @Query(
        """
        SELECT c.*,
            (SELECT COUNT(*) FROM videos v
             WHERE v.chat_id = c.chat_id AND v.is_deleted = 0) AS indexed_video_count
        FROM channels c
        WHERE c.is_selected = 1 AND c.access_state = :accessState
        ORDER BY c.title COLLATE NOCASE ASC, c.chat_id ASC
        """,
    )
    abstract fun observeSelectedChannelScans(
        accessState: ChannelAccessState = ChannelAccessState.AVAILABLE,
    ): Flow<List<ChannelScanRecord>>

    @Query(
        """
        SELECT * FROM channels
        WHERE is_selected = 1 AND access_state = :accessState
        ORDER BY chat_id ASC
        """,
    )
    abstract suspend fun getSelectedScanChannels(
        accessState: ChannelAccessState = ChannelAccessState.AVAILABLE,
    ): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE chat_id = :chatId LIMIT 1")
    abstract suspend fun getChannel(chatId: Long): ChannelEntity?

    @Query(
        """
        SELECT * FROM videos v
        WHERE :channelCount > 0
          AND v.chat_id IN (:channelIds)
          AND v.is_deleted = 0
          AND EXISTS (
              SELECT 1 FROM channels c
              WHERE c.chat_id = v.chat_id
                AND c.is_selected = 1
                AND c.access_state = :accessState
          )
          AND (
              :tagCount = 0
              OR (:tagMode = 'OR' AND EXISTS (
                  SELECT 1 FROM video_tags vt
                  WHERE vt.chat_id = v.chat_id
                    AND vt.message_id = v.message_id
                    AND vt.normalized_tag_name IN (:normalizedTags)
              ))
              OR (:tagMode = 'AND' AND (
                  SELECT COUNT(DISTINCT vt.normalized_tag_name)
                  FROM video_tags vt
                  WHERE vt.chat_id = v.chat_id
                    AND vt.message_id = v.message_id
                    AND vt.normalized_tag_name IN (:normalizedTags)
              ) = :tagCount)
          )
        ORDER BY v.publish_time DESC, v.chat_id DESC, v.message_id DESC
        """,
    )
    protected abstract fun observeFilteredVideoEntities(
        channelIds: List<Long>,
        channelCount: Int,
        normalizedTags: List<String>,
        tagCount: Int,
        tagMode: String,
        accessState: ChannelAccessState = ChannelAccessState.AVAILABLE,
    ): Flow<List<VideoEntity>>

    fun observeFilteredVideos(
        channelIds: Set<Long>,
        normalizedTags: Set<String>,
        tagMode: TagFilterMode,
    ): Flow<List<VideoEntity>> = observeFilteredVideoEntities(
        channelIds = channelIds.sorted(),
        channelCount = channelIds.size,
        normalizedTags = normalizedTags.sorted(),
        tagCount = normalizedTags.size,
        tagMode = tagMode.name,
    )

    @Query(
        """
        SELECT v.chat_id, v.message_id, v.publish_time, v.edit_time
        FROM videos v
        WHERE :channelCount > 0
          AND v.chat_id IN (:channelIds)
          AND v.is_deleted = 0
          AND EXISTS (
              SELECT 1 FROM channels c
              WHERE c.chat_id = v.chat_id
                AND c.is_selected = 1
                AND c.access_state = :accessState
          )
          AND (
              :tagCount = 0
              OR (:tagMode = 'OR' AND EXISTS (
                  SELECT 1 FROM video_tags vt
                  WHERE vt.chat_id = v.chat_id
                    AND vt.message_id = v.message_id
                    AND vt.normalized_tag_name IN (:normalizedTags)
              ))
              OR (:tagMode = 'AND' AND (
                  SELECT COUNT(DISTINCT vt.normalized_tag_name)
                  FROM video_tags vt
                  WHERE vt.chat_id = v.chat_id
                    AND vt.message_id = v.message_id
                    AND vt.normalized_tag_name IN (:normalizedTags)
              ) = :tagCount)
          )
        ORDER BY v.publish_time DESC, v.chat_id DESC, v.message_id DESC
        """,
    )
    protected abstract fun observeFilteredVideoKeyRows(
        channelIds: List<Long>,
        channelCount: Int,
        normalizedTags: List<String>,
        tagCount: Int,
        tagMode: String,
        accessState: ChannelAccessState = ChannelAccessState.AVAILABLE,
    ): Flow<List<VideoFeedKeyRow>>

    fun observeFilteredVideoKeys(
        channelIds: Set<Long>,
        normalizedTags: Set<String>,
        tagMode: TagFilterMode,
    ): Flow<List<VideoFeedKeyRow>> = observeFilteredVideoKeyRows(
        channelIds = channelIds.sorted(),
        channelCount = channelIds.size,
        normalizedTags = normalizedTags.sorted(),
        tagCount = normalizedTags.size,
        tagMode = tagMode.name,
    )

    @Query(
        """
        SELECT * FROM videos
        WHERE chat_id IN (:chatIds)
          AND message_id IN (:messageIds)
          AND is_deleted = 0
        """,
    )
    abstract suspend fun getVideosForKeyWindow(
        chatIds: List<Long>,
        messageIds: List<Long>,
    ): List<VideoEntity>

    @Query(
        """
        SELECT vt.chat_id, vt.message_id, vt.normalized_tag_name, vt.display_name
        FROM video_tags vt
        JOIN videos v ON v.chat_id = vt.chat_id AND v.message_id = vt.message_id
        WHERE vt.chat_id IN (:chatIds)
          AND vt.message_id IN (:messageIds)
          AND v.is_deleted = 0
        """,
    )
    abstract suspend fun getVideoTagsForKeyWindow(
        chatIds: List<Long>,
        messageIds: List<Long>,
    ): List<VideoTagRecord>

    @Query(
        """
        SELECT vt.chat_id, vt.message_id, vt.normalized_tag_name, vt.display_name
        FROM video_tags vt
        JOIN videos v
          ON v.chat_id = vt.chat_id AND v.message_id = vt.message_id
        WHERE :channelCount > 0
          AND vt.chat_id IN (:channelIds)
          AND v.is_deleted = 0
        """,
    )
    abstract suspend fun getVideoTagsForChannels(
        channelIds: List<Long>,
        channelCount: Int = channelIds.size,
    ): List<VideoTagRecord>

    @Query(
        """
        SELECT t.normalized_name,
               t.canonical_display_name AS display_name,
               COUNT(DISTINCT CAST(vt.chat_id AS TEXT) || ':' || CAST(vt.message_id AS TEXT))
                   AS video_count
        FROM tags t
        JOIN video_tags vt ON vt.normalized_tag_name = t.normalized_name
        JOIN videos v ON v.chat_id = vt.chat_id AND v.message_id = vt.message_id
        JOIN channels c ON c.chat_id = v.chat_id
        WHERE :channelCount > 0
          AND v.chat_id IN (:channelIds)
          AND v.is_deleted = 0
          AND c.is_selected = 1
          AND c.access_state = :accessState
        GROUP BY t.normalized_name, t.canonical_display_name
        ORDER BY video_count DESC, t.normalized_name ASC
        """,
    )
    protected abstract fun observeTagSummaryRecords(
        channelIds: List<Long>,
        channelCount: Int,
        accessState: ChannelAccessState = ChannelAccessState.AVAILABLE,
    ): Flow<List<TagSummaryRecord>>

    fun observeTagSummaries(channelIds: Set<Long>): Flow<List<TagSummaryRecord>> =
        observeTagSummaryRecords(channelIds.sorted(), channelIds.size)

    @Query("SELECT * FROM videos WHERE chat_id = :chatId AND message_id = :messageId LIMIT 1")
    abstract suspend fun getVideo(chatId: Long, messageId: Long): VideoEntity?

    @Query("SELECT COUNT(*) FROM videos")
    abstract suspend fun getVideoRecordCount(): Int

    @Query("SELECT DISTINCT file_id FROM videos ORDER BY file_id ASC")
    abstract suspend fun getAllIndexedVideoFileIds(): List<Int>

    @Query("SELECT COUNT(*) FROM video_tags")
    abstract suspend fun getVideoTagRecordCount(): Int

    @Query("SELECT COUNT(*) FROM tags")
    abstract suspend fun getTagRecordCount(): Int

    @Query(
        """
        SELECT
          COALESCE((SELECT SUM(video_candidate_count) FROM channels), 0)
              AS video_candidate_count,
          COALESCE((SELECT SUM(video_search_page_count) FROM channels), 0)
              AS video_search_page_count,
          (SELECT COUNT(*) FROM videos) AS video_record_count,
          (SELECT COUNT(*) FROM videos WHERE is_deleted = 0) AS active_video_record_count,
          COALESCE((
              SELECT SUM(duplicate_count) FROM (
                  SELECT COUNT(*) - 1 AS duplicate_count
                  FROM videos GROUP BY chat_id, message_id HAVING COUNT(*) > 1
              )
          ), 0) AS duplicate_record_count,
          COALESCE((SELECT SUM(duplicate_video_encounter_count) FROM channels), 0)
              AS duplicate_encounter_count,
          COALESCE((SELECT SUM(scan_exception_count) FROM channels), 0)
              AS exception_count
        """,
    )
    abstract suspend fun getDiagnostics(): VideoIndexDiagnostics

    @Query(
        "SELECT message_id FROM videos WHERE chat_id = :chatId AND message_id IN (:messageIds)",
    )
    protected abstract suspend fun getExistingMessageIds(
        chatId: Long,
        messageIds: List<Long>,
    ): List<Long>

    @Upsert
    protected abstract suspend fun upsertVideos(entities: List<VideoEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTags(entities: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertVideoTags(entities: List<VideoTagCrossRef>)

    @Query("DELETE FROM video_tags WHERE chat_id = :chatId AND message_id IN (:messageIds)")
    protected abstract suspend fun deleteVideoTags(chatId: Long, messageIds: List<Long>)

    @Query(
        """
        DELETE FROM tags
        WHERE normalized_name NOT IN (SELECT DISTINCT normalized_tag_name FROM video_tags)
        """,
    )
    protected abstract suspend fun deleteOrphanTags()

    @Query(
        """
        UPDATE videos SET is_deleted = 1, invalidated_at = :invalidatedAt
        WHERE chat_id = :chatId AND message_id IN (:messageIds)
        """,
    )
    protected abstract suspend fun markVideosDeleted(
        chatId: Long,
        messageIds: List<Long>,
        invalidatedAt: Long,
    )

    @Query(
        """
        DELETE FROM videos
        WHERE is_deleted = 1
          AND invalidated_at IS NOT NULL
          AND invalidated_at < :cutoffExclusive
        """,
    )
    protected abstract suspend fun deleteInvalidatedVideosBefore(cutoffExclusive: Long): Int

    @Query(
        """
        UPDATE videos SET edit_time = :editTime
        WHERE chat_id = :chatId AND message_id = :messageId
        """,
    )
    abstract suspend fun updateEditTime(chatId: Long, messageId: Long, editTime: Long?)

    @Update
    protected abstract suspend fun updateChannel(entity: ChannelEntity)

    @Query("DELETE FROM videos")
    protected abstract suspend fun deleteAllVideos()

    @Query("DELETE FROM tags")
    protected abstract suspend fun deleteAllTags()

    @Transaction
    open suspend fun commitPage(page: VideoPageWrite) {
        val channel = getChannel(page.chatId) ?: return
        val uniqueVideos = LinkedHashMap<Long, PersistedVideo>()
        var duplicateEncounterCount = 0L
        page.videos.forEach { persisted ->
            require(persisted.video.chatId == page.chatId)
            if (uniqueVideos.put(persisted.video.messageId, persisted) != null) {
                duplicateEncounterCount += 1
            }
        }
        val messageIds = uniqueVideos.keys.toList()
        if (messageIds.isNotEmpty()) {
            duplicateEncounterCount += getExistingMessageIds(page.chatId, messageIds).size
            val persistedVideos = uniqueVideos.values.toList()
            upsertVideos(
                persistedVideos.map { it.video.copy(isDeleted = false, invalidatedAt = null) },
            )
            insertTags(
                persistedVideos
                    .flatMap(PersistedVideo::tags)
                    .distinctBy(PersistedVideoTag::normalizedName)
                    .map { tag -> TagEntity(tag.normalizedName, tag.displayName) },
            )
            deleteVideoTags(page.chatId, messageIds)
            insertVideoTags(
                persistedVideos.flatMap { persisted ->
                    persisted.tags.distinctBy(PersistedVideoTag::normalizedName).map { tag ->
                        VideoTagCrossRef(
                            chatId = page.chatId,
                            messageId = persisted.video.messageId,
                            normalizedTagName = tag.normalizedName,
                            displayName = tag.displayName,
                        )
                    }
                },
            )
        }

        val newLatest = maxOfNullable(channel.lastNewMessageId, page.latestMessageId)
        val completed = channel.videoSearchCompleted || page.searchCompleted
        updateChannel(
            channel.copy(
                lastNewMessageId = newLatest,
                initialScanCompleted = channel.initialScanCompleted || page.searchCompleted,
                scanStrategyVersion = CURRENT_SCAN_STRATEGY_VERSION,
                videoSearchCursor = if (page.advanceSearchCursor && !page.paginationStalled) {
                    page.nextSearchCursor
                } else {
                    channel.videoSearchCursor
                },
                videoSearchCompleted = completed,
                videoCandidateCount = channel.videoCandidateCount + page.candidateCount,
                videoSearchPageCount = channel.videoSearchPageCount + 1,
                approximateVideoCount = page.approximateTotalCount
                    ?: channel.approximateVideoCount,
                lastSyncTime = page.committedAt,
                scanState = when {
                    page.paginationStalled -> ChannelScanState.ERROR
                    completed -> ChannelScanState.COMPLETED
                    else -> ChannelScanState.SCANNING
                },
                scanRetryAt = null,
                scanRetryCount = 0,
                scanFailureCode = if (page.paginationStalled) {
                    FAILURE_PAGINATION_STALLED
                } else {
                    null
                },
                scanFailureDetail = null,
                duplicateVideoEncounterCount =
                    channel.duplicateVideoEncounterCount + duplicateEncounterCount,
                scanExceptionCount = channel.scanExceptionCount +
                    if (page.paginationStalled) 1 else 0,
            ),
        )
        if (completed || page.paginationStalled) deleteOrphanTags()
    }

    @Transaction
    open suspend fun upsertIncremental(
        persisted: PersistedVideo,
        committedAt: Long,
    ) {
        val channel = getChannel(persisted.video.chatId) ?: return
        val duplicate = getVideo(persisted.video.chatId, persisted.video.messageId) != null
        replaceVideoAndTags(persisted)
        updateChannel(
            channel.copy(
                lastNewMessageId = maxOfNullable(
                    channel.lastNewMessageId,
                    persisted.video.messageId,
                ),
                lastSyncTime = committedAt,
                duplicateVideoEncounterCount = channel.duplicateVideoEncounterCount +
                    if (duplicate) 1 else 0,
            ),
        )
        deleteOrphanTags()
    }

    @Transaction
    open suspend fun recordIncrementalPosition(
        chatId: Long,
        messageId: Long,
        committedAt: Long,
    ) {
        val channel = getChannel(chatId) ?: return
        updateChannel(
            channel.copy(
                lastNewMessageId = maxOfNullable(channel.lastNewMessageId, messageId),
                lastSyncTime = committedAt,
            ),
        )
    }

    @Transaction
    open suspend fun replaceVideoAndTags(persisted: PersistedVideo) {
        upsertVideos(listOf(persisted.video.copy(isDeleted = false, invalidatedAt = null)))
        insertTags(
            persisted.tags.map { tag ->
                TagEntity(tag.normalizedName, tag.displayName)
            },
        )
        deleteVideoTags(persisted.video.chatId, listOf(persisted.video.messageId))
        insertVideoTags(
            persisted.tags.map { tag ->
                VideoTagCrossRef(
                    chatId = persisted.video.chatId,
                    messageId = persisted.video.messageId,
                    normalizedTagName = tag.normalizedName,
                    displayName = tag.displayName,
                )
            },
        )
    }

    @Transaction
    open suspend fun deleteMessages(
        chatId: Long,
        messageIds: List<Long>,
        invalidatedAt: Long,
    ) {
        if (messageIds.isEmpty()) return
        require(invalidatedAt >= 0)
        markVideosDeleted(chatId, messageIds, invalidatedAt)
    }

    @Transaction
    open suspend fun markUnsupportedEdit(chatId: Long, messageId: Long, invalidatedAt: Long) {
        require(invalidatedAt >= 0)
        markVideosDeleted(chatId, listOf(messageId), invalidatedAt)
    }

    @Transaction
    open suspend fun purgeInvalidatedBefore(cutoffExclusive: Long): Int {
        require(cutoffExclusive >= 0)
        val deletedCount = deleteInvalidatedVideosBefore(cutoffExclusive)
        deleteOrphanTags()
        return deletedCount
    }

    @Transaction
    open suspend fun updateScanFailure(
        chatId: Long,
        state: ChannelScanState,
        failureCode: String,
        failureDetail: Int?,
        retryAt: Long?,
        retryCount: Int,
    ) {
        val channel = getChannel(chatId) ?: return
        updateChannel(
            channel.copy(
                scanState = state,
                scanFailureCode = failureCode,
                scanFailureDetail = failureDetail,
                scanRetryAt = retryAt,
                scanRetryCount = retryCount,
                scanExceptionCount = channel.scanExceptionCount + 1,
            ),
        )
    }

    @Transaction
    open suspend fun markAccessLost(chatId: Long) {
        val channel = getChannel(chatId) ?: return
        updateChannel(
            channel.copy(
                isSelected = false,
                accessState = ChannelAccessState.UNAVAILABLE,
                scanState = ChannelScanState.ERROR,
                scanFailureCode = "ACCESS_LOST",
                scanFailureDetail = null,
                scanRetryAt = null,
                scanExceptionCount = channel.scanExceptionCount + 1,
            ),
        )
    }

    @Transaction
    open suspend fun setForegroundScanning(isForeground: Boolean) {
        getSelectedScanChannels().forEach { channel ->
            if (channel.videoSearchCompleted || channel.scanPausedByUser) return@forEach
            val nextState = when {
                !isForeground && channel.scanState == ChannelScanState.SCANNING ->
                    ChannelScanState.PAUSED
                isForeground && channel.scanState in setOf(
                    ChannelScanState.NOT_STARTED,
                    ChannelScanState.PAUSED,
                ) -> ChannelScanState.SCANNING
                else -> channel.scanState
            }
            if (nextState != channel.scanState) updateChannel(channel.copy(scanState = nextState))
        }
    }

    @Transaction
    open suspend fun setUserPaused(paused: Boolean) {
        getSelectedScanChannels().forEach { channel ->
            if (channel.videoSearchCompleted && channel.scanState != ChannelScanState.ERROR) {
                return@forEach
            }
            val mustHonorFloodWait = !paused && channel.scanFailureCode == "FLOOD_WAIT"
            updateChannel(
                channel.copy(
                    scanPausedByUser = paused,
                    scanState = if (paused) ChannelScanState.PAUSED else ChannelScanState.SCANNING,
                    scanRetryAt = if (paused || mustHonorFloodWait) channel.scanRetryAt else null,
                    scanRetryCount = if (paused) channel.scanRetryCount else 0,
                    scanFailureCode = if (paused || mustHonorFloodWait) {
                        channel.scanFailureCode
                    } else {
                        null
                    },
                    scanFailureDetail = if (paused || mustHonorFloodWait) {
                        channel.scanFailureDetail
                    } else {
                        null
                    },
                ),
            )
        }
    }

    @Transaction
    open suspend fun clearAllIndex() {
        deleteAllVideos()
        deleteAllTags()
    }

    private fun maxOfNullable(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> maxOf(first, second)
    }

    private companion object {
        const val CURRENT_SCAN_STRATEGY_VERSION = 2
        const val FAILURE_PAGINATION_STALLED = "PAGINATION_STALLED"
    }
}
