package com.qixuan.channelvideoflow.database

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.qixuan.channelvideoflow.model.channel.ChannelAccessState
import com.qixuan.channelvideoflow.model.channel.ChannelScanState
import com.qixuan.channelvideoflow.model.video.TagFilterMode
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoIndexDaoTest {
    private lateinit var database: ChannelVideoFlowDatabase
    private lateinit var channelDao: ChannelDao
    private lateinit var videoIndexDao: VideoIndexDao
    private val clock = AtomicLong(1_000)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ChannelVideoFlowDatabase::class.java)
            .build()
        channelDao = database.channelDao()
        videoIndexDao = database.videoIndexDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun compositeKeyAllowsSameMessageIdAcrossChatsAndUpsertDoesNotDuplicate() = runBlocking {
        selectChannels(1, 2)
        videoIndexDao.commitPage(page(1, 10, videos = listOf(video(1, 10, "学习"))))
        videoIndexDao.commitPage(page(2, 10, videos = listOf(video(2, 10, "娱乐"))))
        videoIndexDao.commitPage(
            page(1, 10, videos = listOf(video(1, 10, "Kotlin", caption = "已编辑"))),
        )

        assertEquals(2, videoIndexDao.getVideoRecordCount())
        assertEquals("已编辑", videoIndexDao.getVideo(1, 10)?.caption)
        assertEquals(1, videoIndexDao.getDiagnostics().duplicateEncounterCount)
        assertEquals(0, videoIndexDao.getDiagnostics().duplicateRecordCount)
    }

    @Test
    fun eachPageCommitsCursorCountsAndBoundaryDuplicatesIdempotently() = runBlocking {
        selectChannels(1)
        videoIndexDao.commitPage(
            VideoPageWrite(
                chatId = 1,
                videos = listOf(video(1, 100, "近期"), video(1, 91, "边界")),
                candidateCount = 10,
                latestMessageId = 100,
                nextSearchCursor = 91,
                advanceSearchCursor = true,
                committedAt = clock.incrementAndGet(),
            ),
        )
        videoIndexDao.commitPage(
            VideoPageWrite(
                chatId = 1,
                videos = listOf(video(1, 91, "边界"), video(1, 82, "更早")),
                candidateCount = 10,
                latestMessageId = 91,
                nextSearchCursor = 82,
                advanceSearchCursor = true,
                committedAt = clock.incrementAndGet(),
            ),
        )

        val channel = videoIndexDao.getChannel(1)!!
        assertEquals(82L, channel.videoSearchCursor)
        assertEquals(100L, channel.lastNewMessageId)
        assertEquals(20L, channel.videoCandidateCount)
        assertEquals(2, channel.videoSearchPageCount)
        assertEquals(1, channel.duplicateVideoEncounterCount)
        assertEquals(3, videoIndexDao.getVideoRecordCount())
    }

    @Test
    fun roomQueryImplementsChannelOrTagOrAndChannelAndTagAnd() = runBlocking {
        selectChannels(1, 2)
        channelDao.reconcileAvailableChannels(
            listOf(channel(1), channel(2), channel(3)),
        )
        channelDao.replaceSelection(setOf(1, 2))
        listOf(
            video(1, 11, "学习"),
            video(1, 12, "娱乐"),
            video(2, 21, "学习", "单片机"),
            video(2, 22, "其他"),
            video(3, 31, "学习"),
        ).forEach { persisted -> videoIndexDao.replaceVideoAndTags(persisted) }

        assertMessages(
            setOf(1, 2), setOf("学习", "单片机"), TagFilterMode.OR, 21, 11,
        )
        assertMessages(
            setOf(1, 2), setOf("学习", "单片机"), TagFilterMode.AND, 21,
        )
        assertMessages(setOf(1, 2), emptySet(), TagFilterMode.OR, 22, 21, 12, 11)
        assertMessages(setOf(2), setOf("学习"), TagFilterMode.OR, 21)
        assertMessages(emptySet(), setOf("学习"), TagFilterMode.OR)

        videoIndexDao.deleteMessages(1, listOf(11), clock.incrementAndGet())
        assertMessages(setOf(1, 2), setOf("学习"), TagFilterMode.OR, 21)
        channelDao.markChannelUnavailable(2)
        assertMessages(setOf(1, 2), emptySet(), TagFilterMode.OR, 12)
    }

    @Test
    fun lightweightKeysPreserveFilterOrderAndCompositeWindowHydratesExactPairs() = runBlocking {
        selectChannels(1, 2)
        listOf(
            video(1, 10, "学习"),
            video(1, 20, "学习"),
            video(2, 10, "学习"),
            video(2, 30, "其他"),
        ).forEach { videoIndexDao.replaceVideoAndTags(it) }

        val keys = videoIndexDao.observeFilteredVideoKeys(
            setOf(1, 2),
            setOf("学习"),
            TagFilterMode.OR,
        ).first()
        // The fixture's publishTime equals messageId: timestamp 20 precedes 10.
        // chatId breaks only the equal-timestamp tie; message IDs are not global identities.
        assertEquals(listOf(1L to 20L, 2L to 10L, 1L to 10L), keys.map { it.chatId to it.messageId })

        val hydrated = videoIndexDao.getVideosForKeyWindow(listOf(1L, 2L), listOf(10L, 30L))
        assertEquals(
            setOf(1L to 10L, 2L to 10L, 2L to 30L),
            hydrated.map { it.chatId to it.messageId }.toSet(),
        )
    }

    @Test
    fun editAtomicallyReplacesCaptionAndSoftDeleteRetainsAssociationsUntilPurge() = runBlocking {
        selectChannels(1)
        videoIndexDao.replaceVideoAndTags(video(1, 10, "旧标签", caption = "旧说明"))

        videoIndexDao.replaceVideoAndTags(
            video(1, 10, "新标签", "中文", caption = "新说明"),
        )

        assertEquals("新说明", videoIndexDao.getVideo(1, 10)?.caption)
        assertEquals(2, videoIndexDao.getVideoTagRecordCount())
        assertEquals(
            setOf("新标签", "中文"),
            videoIndexDao.getVideoTagsForChannels(listOf(1)).map { it.normalizedTagName }.toSet(),
        )

        val invalidatedAt = clock.incrementAndGet()
        videoIndexDao.deleteMessages(1, listOf(10), invalidatedAt)
        assertEquals(2, videoIndexDao.getVideoTagRecordCount())
        assertFalse(videoIndexDao.observeFilteredVideos(setOf(1), emptySet(), TagFilterMode.OR)
            .first().any())

        assertEquals(0, videoIndexDao.purgeInvalidatedBefore(invalidatedAt))
        assertEquals(1, videoIndexDao.purgeInvalidatedBefore(invalidatedAt + 1))
        assertEquals(0, videoIndexDao.getVideoTagRecordCount())
        assertEquals(0, videoIndexDao.getTagRecordCount())
        assertEquals(0, videoIndexDao.getVideoRecordCount())
    }

    @Test
    fun rediscoveredSoftDeletedVideoClearsInvalidationAndSurvivesOldCutoff() = runBlocking {
        selectChannels(1)
        val persisted = video(1, 10, "标签")
        videoIndexDao.replaceVideoAndTags(persisted)
        videoIndexDao.deleteMessages(1, listOf(10), 2_000)

        videoIndexDao.replaceVideoAndTags(persisted.copy(video = persisted.video.copy(caption = "恢复")))

        val restored = videoIndexDao.getVideo(1, 10)!!
        assertFalse(restored.isDeleted)
        assertEquals(null, restored.invalidatedAt)
        assertEquals(0, videoIndexDao.purgeInvalidatedBefore(3_000))
        assertEquals(1, videoIndexDao.getVideoRecordCount())
    }

    @Test
    fun batchPageDeduplicatesKeysReplacesTagsAndCleansOrphansOnlyAtCompletion() = runBlocking {
        selectChannels(1)
        videoIndexDao.commitPage(
            VideoPageWrite(
                chatId = 1,
                videos = listOf(
                    video(1, 10, "旧标签"),
                    video(1, 10, "新标签", caption = "页内最终值"),
                    video(1, 11),
                ),
                candidateCount = 3,
                latestMessageId = 11,
                nextSearchCursor = 5,
                advanceSearchCursor = true,
                committedAt = clock.incrementAndGet(),
            ),
        )

        assertEquals(2, videoIndexDao.getVideoRecordCount())
        assertEquals("页内最终值", videoIndexDao.getVideo(1, 10)?.caption)
        assertEquals(1, videoIndexDao.getVideoTagRecordCount())
        assertEquals(1, videoIndexDao.getTagRecordCount())

        videoIndexDao.commitPage(
            VideoPageWrite(
                chatId = 1,
                videos = listOf(video(1, 10, "完成标签")),
                candidateCount = 1,
                latestMessageId = 10,
                nextSearchCursor = 0,
                advanceSearchCursor = true,
                searchCompleted = true,
                committedAt = clock.incrementAndGet(),
            ),
        )

        assertEquals(setOf("完成标签"), videoIndexDao.getVideoTagsForChannels(listOf(1))
            .map(VideoTagRecord::normalizedTagName).toSet())
        assertEquals(1, videoIndexDao.getTagRecordCount())
        assertEquals(2L, videoIndexDao.getDiagnostics().duplicateEncounterCount)
    }

    @Test
    fun pageExistingKeyLookupUsesCompositePrimaryKeyIndex() {
        database.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT message_id FROM videos " +
                "WHERE chat_id = ? AND message_id IN (?, ?)",
            arrayOf(1L, 10L, 11L),
        ).use { cursor ->
            val details = buildList {
                while (cursor.moveToNext()) add(cursor.getString(3))
            }.joinToString(" ")
            assertTrue(details.contains("sqlite_autoindex_videos_1"))
        }
    }

    @Test
    fun manualResumePreservesFloodWaitDeadlineButResetsAttemptBudget() = runBlocking {
        selectChannels(1)
        videoIndexDao.updateScanFailure(
            chatId = 1,
            state = ChannelScanState.ERROR,
            failureCode = "FLOOD_WAIT",
            failureDetail = null,
            retryAt = 90_000,
            retryCount = 3,
        )

        videoIndexDao.setUserPaused(false)

        val channel = videoIndexDao.getChannel(1)!!
        assertEquals(ChannelScanState.SCANNING, channel.scanState)
        assertEquals("FLOOD_WAIT", channel.scanFailureCode)
        assertEquals(90_000L, channel.scanRetryAt)
        assertEquals(0, channel.scanRetryCount)
    }

    private suspend fun selectChannels(vararg ids: Long) {
        channelDao.reconcileAvailableChannels(ids.map(::channel))
        channelDao.replaceSelection(ids.toSet())
    }

    private suspend fun assertMessages(
        channelIds: Set<Long>,
        tags: Set<String>,
        mode: TagFilterMode,
        vararg expected: Long,
    ) {
        val actual = videoIndexDao.observeFilteredVideos(channelIds, tags, mode)
            .first()
            .map(VideoEntity::messageId)
        assertEquals(expected.toList(), actual)
    }

    private fun page(
        chatId: Long,
        messageId: Long,
        videos: List<PersistedVideo>,
    ) = VideoPageWrite(
        chatId = chatId,
        videos = videos,
        candidateCount = 1,
        latestMessageId = messageId,
        nextSearchCursor = 0,
        advanceSearchCursor = true,
        searchCompleted = true,
        committedAt = clock.incrementAndGet(),
    )

    private fun video(
        chatId: Long,
        messageId: Long,
        vararg tags: String,
        caption: String = "说明",
    ) = PersistedVideo(
        video = VideoEntity(
            chatId = chatId,
            messageId = messageId,
            fileId = messageId.toInt(),
            remoteUniqueId = "remote-$chatId-$messageId",
            caption = caption,
            durationSeconds = 30,
            width = 1080,
            height = 1920,
            fileSize = 1024,
            supportsStreaming = true,
            publishTime = messageId,
            editTime = null,
            canBeSaved = true,
            indexedAt = clock.incrementAndGet(),
        ),
        tags = tags.map { tag -> PersistedVideoTag(tag.lowercase(), "#$tag") },
    )

    private fun channel(id: Long) = ChannelEntity(
        chatId = id,
        title = "频道 $id",
        username = null,
        accessState = ChannelAccessState.AVAILABLE,
    )
}
