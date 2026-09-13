package com.qixuan.channelvideoflow.telegram.message

import com.qixuan.channelvideoflow.database.ChannelEntity
import com.qixuan.channelvideoflow.database.ChannelScanRecord
import com.qixuan.channelvideoflow.database.PersistedVideo
import com.qixuan.channelvideoflow.database.TagSummaryRecord
import com.qixuan.channelvideoflow.database.VideoEntity
import com.qixuan.channelvideoflow.database.VideoPageWrite
import com.qixuan.channelvideoflow.database.VideoTagRecord
import com.qixuan.channelvideoflow.model.channel.ChannelAccessState
import com.qixuan.channelvideoflow.model.channel.ChannelScanState
import com.qixuan.channelvideoflow.model.video.TagFilterMode
import com.qixuan.channelvideoflow.model.video.OriginalMessageLinkResult
import com.qixuan.channelvideoflow.model.video.VideoKey
import com.qixuan.channelvideoflow.domain.message.VideoReferenceResolution
import com.qixuan.channelvideoflow.domain.message.VideoReferenceFailure
import com.qixuan.channelvideoflow.domain.message.RepositoryObservationFailure
import com.qixuan.channelvideoflow.telegram.client.TelegramClientFailure
import com.qixuan.channelvideoflow.telegram.client.TelegramClientMessageLink
import com.qixuan.channelvideoflow.telegram.client.TelegramClientMessageProperties
import com.qixuan.channelvideoflow.telegram.client.TelegramClientMessage
import com.qixuan.channelvideoflow.telegram.client.TelegramClientVideoSearchPage
import com.qixuan.channelvideoflow.telegram.client.TelegramClientMediaFile
import com.qixuan.channelvideoflow.telegram.client.TelegramClientResult
import com.qixuan.channelvideoflow.telegram.client.TelegramClientUtf16Range
import com.qixuan.channelvideoflow.telegram.client.TelegramClientVideoContent
import com.qixuan.channelvideoflow.telegram.client.TelegramClientVideoVariant
import com.qixuan.channelvideoflow.telegram.client.TelegramMessageClient
import com.qixuan.channelvideoflow.telegram.client.TelegramMessageClientEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TdLibTelegramMessageRepositoryTest {
    @Test
    fun databaseObservationFailureIsNotDisguisedAsEmptyContent() = runTest {
        val store = FakeMessageIndexStore(channel()).apply {
            failVideoObservation = true
            failTagObservation = true
            failScanObservation = true
        }
        val repository = repository(FakeMessageClient(FakeClock()), store, FakeClock())

        val video = repository.observeVideoObservation(
            com.qixuan.channelvideoflow.model.video.VideoFilter(setOf(1L)),
        ).first()
        val tags = repository.observeTagObservation(setOf(1L)).first()
        val scan = repository.scanProgressObservation.first()

        assertEquals(RepositoryObservationFailure.DATABASE, video.failure)
        assertEquals(RepositoryObservationFailure.DATABASE, tags.failure)
        assertEquals(RepositoryObservationFailure.DATABASE, scan.failure)
        assertTrue(video.value.isEmpty())
        assertTrue(tags.value.isEmpty())
        assertTrue(scan.value.isEmpty())
    }

    @Test
    fun firstForegroundEntryPurgesOnlyTheThirtyDayCutoffOncePerRepository() = runTest {
        val retentionMillis = 30L * 24L * 60L * 60L * 1_000L
        val clock = FakeClock(now = retentionMillis + 123L)
        val store = FakeMessageIndexStore(channel(videoSearchCompleted = true))
        val repository = repository(FakeMessageClient(clock), store, clock)

        repository.setForeground(true)
        repository.setForeground(false)
        clock.now += 10_000L
        repository.setForeground(true)

        assertEquals(listOf(123L), store.purgeCutoffs)
    }

    @Test
    fun failedRetentionCleanupDoesNotEnterForegroundAndRetriesNextTime() = runTest {
        val clock = FakeClock(now = 30L * 24L * 60L * 60L * 1_000L + 9L)
        val store = FakeMessageIndexStore(channel(videoSearchCompleted = true)).apply {
            failNextPurge = true
        }
        val repository = repository(FakeMessageClient(clock), store, clock)

        val failure = runCatching { repository.setForeground(true) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(store.foregroundScanning)
        repository.setForeground(true)
        assertEquals(listOf(9L, 9L), store.purgeCutoffs)
        assertTrue(store.foregroundScanning)
    }

    @Test
    fun scansRecentPageFirstThenPaginatesFilteredVideosWithBoundaryDeduplication() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            results += success(
                message(100, video = video("#近期")),
                message(91, video = video("#边界")),
                nextCursor = 91,
            )
            results += success(
                message(91, video = video("#边界")),
                message(82, video = video("#更早")),
                nextCursor = 82,
            )
            results += success(message(82, video = video("#更早")), nextCursor = 0)
        }
        val store = FakeMessageIndexStore(channel())
        val repository = repository(client, store, clock)

        repository.setForeground(true)
        runCurrent()

        assertEquals(listOf(0L, 91L, 82L), client.calls.map(SearchCall::fromMessageId))
        assertEquals(3, store.pages.size)
        assertEquals(setOf(100L, 91L, 82L), store.videos.keys.map { it.second }.toSet())
        assertEquals(0L, store.channel().videoSearchCursor)
        assertEquals(100L, store.channel().lastNewMessageId)
        assertTrue(store.channel().videoSearchCompleted)
        assertEquals(5L, store.channel().videoCandidateCount)
    }

    @Test
    fun restartReconcilesNewerMessagesUntilItCrossesSavedLatestBoundary() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            results += success(
                message(105, video = video("#新")),
                message(104, video = video("#新")),
                message(103, video = video("#新")),
                message(102, video = video("#新")),
                message(101, video = video("#新")),
                message(90, video = video("#边界")),
                nextCursor = 80,
            )
            results += success(message(80, video = video("#旧")), nextCursor = 0)
        }
        val store = FakeMessageIndexStore(
            channel(lastNewMessageId = 100, videoSearchCursor = 80),
        )
        val repository = repository(client, store, clock)

        repository.setForeground(true)
        runCurrent()

        assertEquals(listOf(0L, 80L), client.calls.map(SearchCall::fromMessageId))
        assertEquals(setOf(80L, 90L, 101L, 102L, 103L, 104L, 105L), store.videos.keys.map { it.second }.toSet())
        assertEquals(105L, store.channel().lastNewMessageId)
        assertTrue(store.channel().videoSearchCompleted)
    }

    @Test
    fun alignedRecentPagesAdvanceHistoricalCursorWithoutReplayingThem() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            searchHandler = { _, cursor ->
                when (cursor) {
                    0L -> success(message(100, video = video("#近期")), nextCursor = 90)
                    90L -> success(message(90, video = video("#近期")), nextCursor = 70)
                    70L -> success(message(70, video = video("#边界")), nextCursor = 50)
                    50L -> success(message(50, video = video("#历史")), nextCursor = 0)
                    else -> error("unexpected cursor $cursor")
                }
            }
        }
        val store = FakeMessageIndexStore(channel(lastNewMessageId = 80))

        repository(client, store, clock).setForeground(true)
        runCurrent()

        assertEquals(listOf(0L, 90L, 70L, 50L), client.calls.map(SearchCall::fromMessageId))
        assertEquals(setOf(50L, 70L, 90L, 100L), store.videos.keys.map { it.second }.toSet())
        assertEquals(4, store.channel().videoSearchPageCount)
        assertEquals(4L, store.channel().videoCandidateCount)
        assertTrue(store.channel().videoSearchCompleted)
    }

    @Test
    fun shortAndEmptyPagesWithAdvancingCursorDoNotCompleteEarly() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            results += success(message(100, video = video("#一")), nextCursor = 70)
            results += success(nextCursor = 40)
            results += success(message(40, video = video("#二")), nextCursor = 0)
        }
        val store = FakeMessageIndexStore(channel())

        repository(client, store, clock).setForeground(true)
        runCurrent()

        assertEquals(listOf(0L, 70L, 40L), client.calls.map(SearchCall::fromMessageId))
        assertEquals(setOf(40L, 100L), store.videos.keys.map { it.second }.toSet())
        assertEquals(3, store.channel().videoSearchPageCount)
        assertTrue(store.channel().videoSearchCompleted)
    }

    @Test
    fun nonEmptyFinalPageIsCommittedBeforeCursorZeroMarksComplete() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            results += success(message(44, video = video("#最终")), nextCursor = 0)
        }
        val store = FakeMessageIndexStore(channel())

        repository(client, store, clock).setForeground(true)
        runCurrent()

        assertTrue(1L to 44L in store.videos)
        assertTrue(store.channel().videoSearchCompleted)
        assertEquals(0L, store.channel().videoSearchCursor)
    }

    @Test
    fun repeatedNonZeroCursorCommitsPageThenStopsAsRecoverablePaginationError() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            results += success(message(100, video = video("#一")), nextCursor = 100)
            results += success(message(100, video = video("#重复")), nextCursor = 100)
        }
        val store = FakeMessageIndexStore(channel())

        repository(client, store, clock).setForeground(true)
        runCurrent()

        assertEquals(2, client.calls.size)
        assertEquals(ChannelScanState.ERROR, store.channel().scanState)
        assertEquals("PAGINATION_STALLED", store.channel().scanFailureCode)
        assertFalse(store.channel().videoSearchCompleted)
    }

    @Test
    fun cancellationBeforeSearchResultDoesNotCommitAndCommittedCursorResumesAfterRestart() = runTest {
        val clock = FakeClock()
        val firstGate = CompletableDeferred<Unit>()
        val firstClient = FakeMessageClient(clock).apply {
            searchHandler = { _, cursor ->
                if (cursor == 0L) {
                    TelegramClientResult.Success(
                        TelegramClientVideoSearchPage(
                            listOf(message(100, video = video("#首"))),
                            2,
                            50,
                        ),
                    )
                } else {
                    firstGate.await()
                    success(nextCursor = 0)
                }
            }
        }
        val store = FakeMessageIndexStore(channel())
        val firstRepository = repository(firstClient, store, clock)
        firstRepository.setForeground(true)
        runCurrent()
        assertEquals(50L, store.channel().videoSearchCursor)

        firstRepository.setForeground(false)
        runCurrent()
        assertEquals(1, store.pages.size)

        val resumedClient = FakeMessageClient(clock).apply {
            results += success(message(100, video = video("#首")), nextCursor = 50)
            results += success(message(50, video = video("#续")), nextCursor = 0)
        }
        repository(resumedClient, store, clock).setForeground(true)
        runCurrent()

        assertEquals(listOf(0L, 50L), resumedClient.calls.map(SearchCall::fromMessageId))
        assertEquals(setOf(50L, 100L), store.videos.keys.map { it.second }.toSet())
        assertTrue(store.channel().videoSearchCompleted)
    }

    @Test
    fun selectedChannelsUseAtMostTwoConcurrentSearchesAndAllReceiveARecentTurn() = runTest {
        val clock = FakeClock()
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        val client = FakeMessageClient(clock).apply {
            searchHandler = { _, _ ->
                active += 1
                maxActive = maxOf(maxActive, active)
                release.await()
                active -= 1
                success(nextCursor = 0)
            }
        }
        val store = FakeMessageIndexStore(channel(1), channel(2), channel(3))
        val repository = repository(client, store, clock)

        repository.setForeground(true)
        runCurrent()
        assertEquals(2, client.calls.size)
        release.complete(Unit)
        runCurrent()

        assertEquals(setOf(1L, 2L, 3L), client.calls.map(SearchCall::chatId).toSet())
        assertEquals(2, maxActive)
        assertTrue((1L..3L).all { store.channel(it).videoSearchCompleted })
    }

    @Test
    fun floodWaitFromOneChannelGatesSubsequentRequestsAcrossTheScan() = runTest {
        val clock = FakeClock()
        val delay = FakeDelay(clock)
        val attempts = mutableMapOf<Long, Int>()
        val client = FakeMessageClient(clock).apply {
            searchHandler = { chatId, cursor ->
                val attempt = attempts.getOrDefault(chatId, 0) + 1
                attempts[chatId] = attempt
                when {
                    chatId == 1L && attempt == 1 ->
                        TelegramClientResult.Failure(TelegramClientFailure.FloodWait(30))
                    cursor == 0L && chatId == 2L -> success(nextCursor = 20)
                    else -> success(nextCursor = 0)
                }
            }
        }
        val store = FakeMessageIndexStore(channel(1), channel(2))

        repository(client, store, clock, delay).setForeground(true)
        runCurrent()

        val followUpCalls = client.calls.drop(2)
        assertTrue(followUpCalls.isNotEmpty())
        assertTrue(followUpCalls.all { it.calledAt >= 30_000L })
        assertTrue(store.channel(1).videoSearchCompleted)
        assertTrue(store.channel(2).videoSearchCompleted)
    }

    @Test
    fun accessLossDuringFilteredScanStopsThatChannelWithoutBlockingHealthyChannels() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            searchHandler = { chatId, _ ->
                if (chatId == 1L) {
                    TelegramClientResult.Failure(TelegramClientFailure.AccessLost)
                } else {
                    success(nextCursor = 0)
                }
            }
        }
        val store = FakeMessageIndexStore(channel(1), channel(2))

        repository(client, store, clock).setForeground(true)
        runCurrent()

        assertFalse(store.channel(1).isSelected)
        assertEquals(ChannelAccessState.UNAVAILABLE, store.channel(1).accessState)
        assertEquals(ChannelScanState.ERROR, store.channel(1).scanState)
        assertTrue(store.channel(2).videoSearchCompleted)
        assertEquals(setOf(1L, 2L), client.calls.map(SearchCall::chatId).toSet())
    }

    @Test
    fun duplicateIncrementalEditAndDeleteRemainIdempotentAndReplaceTags() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock)
        val store = FakeMessageIndexStore(channel(videoSearchCompleted = true))
        repository(client, store, clock)
        runCurrent()
        val original = message(110, video = video("#Kotlin"))

        client.emit(TelegramMessageClientEvent.NewMessage(original))
        client.emit(TelegramMessageClientEvent.NewMessage(original))
        runCurrent()
        assertEquals(1, store.videos.size)
        assertEquals(listOf("kotlin"), store.videos.getValue(1L to 110L).tags.map { it.normalizedName })

        client.emit(
            TelegramMessageClientEvent.MessageContentChanged(
                1,
                110,
                video("#中文 #English"),
            ),
        )
        client.emit(TelegramMessageClientEvent.MessageEdited(1, 110, 200))
        runCurrent()
        assertEquals(
            setOf("中文", "english"),
            store.videos.getValue(1L to 110L).tags.map { it.normalizedName }.toSet(),
        )
        assertEquals(200L, store.videos.getValue(1L to 110L).video.editTime)

        client.emit(TelegramMessageClientEvent.MessagesDeleted(1, listOf(110), fromCache = false))
        runCurrent()
        assertTrue(1L to 110L in store.deleted)
    }

    @Test
    fun floodWaitStopsRequestsUntilServerDelayAndThenRetries() = runTest {
        val clock = FakeClock()
        val delay = FakeDelay(clock)
        val client = FakeMessageClient(clock).apply {
            results += TelegramClientResult.Failure(TelegramClientFailure.FloodWait(30))
            results += success()
        }
        val store = FakeMessageIndexStore(channel())
        val repository = repository(client, store, clock, delay)

        repository.setForeground(true)
        runCurrent()

        assertEquals(listOf(0L, 30_000L), client.calls.map(SearchCall::calledAt))
        assertEquals(listOf(30_000L), delay.waits)
        assertEquals(2, client.calls.size)
        assertTrue(store.channel().videoSearchCompleted)
    }

    @Test
    fun repeatedFailureStopsAfterThreeAttemptsWithoutInfiniteRetry() = runTest {
        val clock = FakeClock()
        val delay = FakeDelay(clock)
        val client = FakeMessageClient(clock).apply {
            repeat(3) {
                results += TelegramClientResult.Failure(TelegramClientFailure.NetworkUnavailable)
            }
        }
        val store = FakeMessageIndexStore(channel())
        val repository = repository(client, store, clock, delay)

        repository.setForeground(true)
        runCurrent()

        assertEquals(3, client.calls.size)
        assertEquals(listOf(1_000L, 2_000L), delay.waits)
        assertEquals(ChannelScanState.ERROR, store.channel().scanState)
        assertEquals(3, store.channel().scanRetryCount)
    }

    @Test
    fun retryLimitPreservesFloodWaitAndManualResumeStillHonorsServerDeadline() = runTest {
        val clock = FakeClock()
        val delay = FakeDelay(clock)
        val client = FakeMessageClient(clock).apply {
            repeat(3) {
                results += TelegramClientResult.Failure(TelegramClientFailure.FloodWait(30))
            }
            results += success()
        }
        val store = FakeMessageIndexStore(channel())
        val repository = repository(client, store, clock, delay)

        repository.setForeground(true)
        runCurrent()

        assertEquals(listOf(0L, 30_000L, 60_000L), client.calls.map(SearchCall::calledAt))
        assertEquals(ChannelScanState.ERROR, store.channel().scanState)
        assertEquals(90_000L, store.channel().scanRetryAt)

        repository.resumeScanning()
        runCurrent()

        assertEquals(90_000L, client.calls.last().calledAt)
        assertEquals(listOf(30_000L, 30_000L, 30_000L), delay.waits)
        assertTrue(store.channel().videoSearchCompleted)
    }

    @Test
    fun cacheOnlyDeleteIsIgnoredAndBackgroundStopsTheCoordinator() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock)
        val store = FakeMessageIndexStore(channel(videoSearchCompleted = true)).apply {
            videos[1L to 10L] = persisted(message(10, video = video("#保留")), 0)!!
        }
        val repository = repository(client, store, clock)
        runCurrent()

        client.emit(TelegramMessageClientEvent.MessagesDeleted(1, listOf(10), fromCache = true))
        runCurrent()
        repository.setForeground(false)

        assertFalse(1L to 10L in store.deleted)
        assertFalse(store.foregroundScanning)
    }

    @Test
    fun originalMessageLinkQueriesPropertiesBeforeUsingOfficialLinkCapability() = runTest {
        val client = FakeMessageClient(FakeClock()).apply {
            propertiesResult = TelegramClientResult.Success(TelegramClientMessageProperties(true))
            linkResult = TelegramClientResult.Success(
                TelegramClientMessageLink("https://t.me/c/1/10"),
            )
        }
        val repository = repository(client, FakeMessageIndexStore(channel()), FakeClock())

        val result = repository.getOriginalMessageLink(VideoKey(1, 10))

        assertEquals(
            OriginalMessageLinkResult.Available("https://t.me/c/1/10"),
            result,
        )
        assertEquals(listOf("properties:1:10", "link:1:10"), client.linkCalls)
    }

    @Test
    fun refreshVideoReplacesStaleFileReferenceFromOfficialMessage() = runTest {
        val clock = FakeClock(now = 123)
        val client = FakeMessageClient(clock).apply {
            messageResult = TelegramClientResult.Success(
                message(
                    10,
                    video = video("#刷新", fileId = 202).copy(
                        alternativeVariants = listOf(
                            TelegramClientVideoVariant(
                                alternativeId = 3,
                                fileId = 303,
                                remoteUniqueId = "alternative-303",
                                fileSize = 2048,
                                width = 640,
                                height = 360,
                                codec = "h264",
                                hlsManifestFile = TelegramClientMediaFile(
                                    fileId = 304,
                                    remoteUniqueId = "manifest-304",
                                    fileSize = 512,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        val store = FakeMessageIndexStore(channel(videoSearchCompleted = true)).apply {
            videos[1L to 10L] = persisted(
                message(10, video = video("#旧", fileId = 101)),
                indexedAt = 0,
            )!!
        }
        val repository = repository(client, store, clock)

        val refreshed = repository.refreshVideo(VideoKey(1, 10))

        val resolved = (refreshed as VideoReferenceResolution.Resolved).video
        assertEquals(202, resolved.fileId)
        assertEquals(listOf(303), resolved.alternativeVariants.map { it.fileId })
        assertEquals(listOf(304), resolved.hlsCapableVariants.map { it.hlsManifestFile?.fileId })
        assertEquals(202, store.videos.getValue(1L to 10L).video.fileId)
        assertEquals(listOf("message:1:10"), client.messageCalls)
    }

    @Test
    fun concurrentRefreshesForTheSameVideoUseOneOfficialGetMessage() = runTest {
        val clock = FakeClock(now = 123)
        val gate = CompletableDeferred<Unit>()
        val client = FakeMessageClient(clock).apply {
            messageGate = gate
            messageResult = TelegramClientResult.Success(
                message(10, video = video("#刷新", fileId = 202)),
            )
        }
        val repository = repository(
            client,
            FakeMessageIndexStore(channel(videoSearchCompleted = true)),
            clock,
        )

        val refreshes = listOf(
            async { repository.refreshVideo(VideoKey(1, 10)) },
            async { repository.refreshVideo(VideoKey(1, 10)) },
        )
        runCurrent()

        assertEquals(listOf("message:1:10"), client.messageCalls)
        gate.complete(Unit)
        refreshes.awaitAll()
    }

    @Test
    fun differentVideosStillStartConcurrentRefreshesWithoutAFloodWaitGate() = runTest {
        val clock = FakeClock(now = 123)
        val gate = CompletableDeferred<Unit>()
        val client = FakeMessageClient(clock).apply {
            messageGate = gate
            messageResult = TelegramClientResult.Success(
                message(10, video = video("#刷新", fileId = 202)),
            )
        }
        val repository = repository(
            client,
            FakeMessageIndexStore(channel(videoSearchCompleted = true)),
            clock,
        )

        val refreshes = listOf(
            async { repository.refreshVideo(VideoKey(1, 10)) },
            async { repository.refreshVideo(VideoKey(1, 20)) },
        )
        runCurrent()

        assertEquals(
            setOf("message:1:10", "message:1:20"),
            client.messageCalls.toSet(),
        )
        gate.complete(Unit)
        refreshes.awaitAll()
    }

    @Test
    fun missingMessageMarksTheIndexedRowDeleted() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            messageResult = TelegramClientResult.Failure(TelegramClientFailure.NotFound)
        }
        val store = FakeMessageIndexStore(channel(videoSearchCompleted = true))
        val repository = repository(client, store, clock)

        val result = repository.refreshVideo(VideoKey(1, 10))

        assertEquals(VideoReferenceResolution.MessageMissing, result)
        assertTrue(1L to 10L in store.deleted)
        assertEquals(listOf("message:1:10"), client.messageCalls)
    }

    @Test
    fun nonVideoMessageMarksTheIndexedRowUnsupported() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            messageResult = TelegramClientResult.Success(message(10, video = null))
        }
        val store = FakeMessageIndexStore(channel(videoSearchCompleted = true))
        val repository = repository(client, store, clock)

        val result = repository.refreshVideo(VideoKey(1, 10))

        assertEquals(VideoReferenceResolution.UnsupportedMessage, result)
        assertTrue(1L to 10L in store.deleted)
    }

    @Test
    fun floodWaitIsReturnedWithoutAnAutomaticRetryStorm() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            messageResult = TelegramClientResult.Failure(TelegramClientFailure.FloodWait(30))
        }
        val repository = repository(client, FakeMessageIndexStore(channel()), clock)

        val result = repository.refreshVideo(VideoKey(1, 10))

        assertEquals(
            VideoReferenceResolution.Unavailable(VideoReferenceFailure.FloodWait(30)),
            result,
        )
        assertEquals(listOf("message:1:10"), client.messageCalls)
    }

    @Test
    fun floodWaitFromOneVideoBlocksDifferentVideoBeforeTheServerDeadline() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            messageResult = TelegramClientResult.Failure(TelegramClientFailure.FloodWait(30))
        }
        val repository = repository(client, FakeMessageIndexStore(channel()), clock)

        val first = repository.refreshVideo(VideoKey(1, 10))
        client.messageResult = TelegramClientResult.Success(
            message(20, video = video("#刷新", fileId = 202)),
        )
        val blocked = repository.refreshVideo(VideoKey(1, 20))

        assertEquals(
            VideoReferenceResolution.Unavailable(VideoReferenceFailure.FloodWait(30)),
            first,
        )
        assertEquals(
            VideoReferenceResolution.Unavailable(VideoReferenceFailure.FloodWait(30)),
            blocked,
        )
        assertEquals(listOf("message:1:10"), client.messageCalls)
    }

    @Test
    fun differentVideoCanRefreshAfterTheFloodWaitDeadline() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            messageResult = TelegramClientResult.Failure(TelegramClientFailure.FloodWait(30))
        }
        val repository = repository(client, FakeMessageIndexStore(channel()), clock)

        repository.refreshVideo(VideoKey(1, 10))
        clock.now = 30_000L
        client.messageResult = TelegramClientResult.Success(
            message(20, video = video("#恢复", fileId = 202)),
        )

        val recovered = repository.refreshVideo(VideoKey(1, 20))

        assertEquals(202, (recovered as VideoReferenceResolution.Resolved).video.fileId)
        assertEquals(listOf("message:1:10", "message:1:20"), client.messageCalls)
    }

    @Test
    fun floodWaitGateReturnsCeilingRemainingSecondsWithoutGoingNegative() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            messageResult = TelegramClientResult.Failure(TelegramClientFailure.FloodWait(30))
        }
        val repository = repository(client, FakeMessageIndexStore(channel()), clock)

        repository.refreshVideo(VideoKey(1, 10))
        clock.now = 1_001L
        val middle = repository.refreshVideo(VideoKey(1, 20))
        clock.now = 29_999L
        val finalSecond = repository.refreshVideo(VideoKey(1, 30))

        assertEquals(
            VideoReferenceResolution.Unavailable(VideoReferenceFailure.FloodWait(29)),
            middle,
        )
        assertEquals(
            VideoReferenceResolution.Unavailable(VideoReferenceFailure.FloodWait(1)),
            finalSecond,
        )
        assertEquals(listOf("message:1:10"), client.messageCalls)
    }

    @Test
    fun cancellingOneSharedWaiterDoesNotClearTheFloodWaitGate() = runTest {
        val clock = FakeClock()
        val gate = CompletableDeferred<Unit>()
        val client = FakeMessageClient(clock).apply {
            messageGate = gate
            messageResult = TelegramClientResult.Failure(TelegramClientFailure.FloodWait(30))
        }
        val repository = repository(client, FakeMessageIndexStore(channel()), clock)
        val cancelled = async { repository.refreshVideo(VideoKey(1, 10)) }
        val surviving = async { repository.refreshVideo(VideoKey(1, 10)) }
        runCurrent()

        cancelled.cancelAndJoin()
        gate.complete(Unit)
        assertEquals(
            VideoReferenceResolution.Unavailable(VideoReferenceFailure.FloodWait(30)),
            surviving.await(),
        )
        client.messageGate = null
        client.messageResult = TelegramClientResult.Success(
            message(20, video = video("#不应请求", fileId = 202)),
        )

        val blocked = repository.refreshVideo(VideoKey(1, 20))

        assertEquals(
            VideoReferenceResolution.Unavailable(VideoReferenceFailure.FloodWait(30)),
            blocked,
        )
        assertEquals(listOf("message:1:10"), client.messageCalls)
    }

    @Test
    fun accountLogoutClearsThePreviousSessionFloodWaitGate() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            messageResult = TelegramClientResult.Failure(TelegramClientFailure.FloodWait(30))
        }
        val repository = repository(client, FakeMessageIndexStore(channel()), clock)
        runCurrent()
        repository.refreshVideo(VideoKey(1, 10))

        client.emit(TelegramMessageClientEvent.AccountLoggingOut)
        runCurrent()
        client.messageResult = TelegramClientResult.Success(
            message(20, video = video("#新会话", fileId = 202)),
        )

        val refreshed = repository.refreshVideo(VideoKey(1, 20))

        assertEquals(202, (refreshed as VideoReferenceResolution.Resolved).video.fileId)
        assertEquals(listOf("message:1:10", "message:1:20"), client.messageCalls)
    }

    @Test
    fun nonFloodWaitFailureDoesNotBlockAnotherVideoRefresh() = runTest {
        val clock = FakeClock()
        val client = FakeMessageClient(clock).apply {
            messageResult = TelegramClientResult.Failure(TelegramClientFailure.NetworkUnavailable)
        }
        val repository = repository(client, FakeMessageIndexStore(channel()), clock)

        val first = repository.refreshVideo(VideoKey(1, 10))
        client.messageResult = TelegramClientResult.Success(
            message(20, video = video("#继续", fileId = 202)),
        )
        val second = repository.refreshVideo(VideoKey(1, 20))

        assertEquals(
            VideoReferenceResolution.Unavailable(VideoReferenceFailure.Network),
            first,
        )
        assertEquals(202, (second as VideoReferenceResolution.Resolved).video.fileId)
        assertEquals(listOf("message:1:10", "message:1:20"), client.messageCalls)
    }

    @Test
    fun cancellingTheFinalRefreshWaiterPropagatesAndCancelsTheSharedRequest() = runTest {
        val clock = FakeClock()
        val gate = CompletableDeferred<Unit>()
        val client = FakeMessageClient(clock).apply {
            messageGate = gate
            messageResult = TelegramClientResult.Success(
                message(10, video = video("#刷新", fileId = 202)),
            )
        }
        val repository = repository(client, FakeMessageIndexStore(channel()), clock)
        val refresh = async { repository.refreshVideo(VideoKey(1, 10)) }
        runCurrent()

        refresh.cancelAndJoin()

        assertTrue(refresh.isCancelled)
        assertEquals(listOf("message:1:10"), client.messageCalls)
    }

    private fun kotlinx.coroutines.test.TestScope.repository(
        client: FakeMessageClient,
        store: FakeMessageIndexStore,
        clock: FakeClock,
        delay: FakeDelay = FakeDelay(clock),
    ) = TdLibTelegramMessageRepository(
        client = client,
        store = store,
        scope = backgroundScope,
        clock = clock,
        delayStrategy = delay,
        jitter = MessageScanJitter { 0 },
    )

    private fun success(
        vararg messages: TelegramClientMessage,
        nextCursor: Long = 0L,
        approximateTotalCount: Int? = messages.size,
    ) = TelegramClientResult.Success(
        TelegramClientVideoSearchPage(
            messages = messages.toList(),
            approximateTotalCount = approximateTotalCount,
            nextFromMessageId = nextCursor,
        ),
    )

    private fun message(
        id: Long,
        video: TelegramClientVideoContent? = null,
        chatId: Long = 1,
    ) = TelegramClientMessage(
        chatId = chatId,
        messageId = id,
        publishTime = id,
        editTime = null,
        canBeSaved = true,
        video = video,
    )

    private fun video(
        caption: String,
        fileId: Int = caption.hashCode(),
    ) = TelegramClientVideoContent(
        fileId = fileId,
        remoteUniqueId = "remote-${caption.hashCode()}",
        caption = caption,
        hashtagEntityRanges = caption.split(' ')
            .runningFold(0) { offset, part -> offset + part.length + 1 }
            .dropLast(1)
            .zip(caption.split(' ')) { offset, part ->
                TelegramClientUtf16Range(offset, part.length)
            },
        durationSeconds = 30,
        width = 1080,
        height = 1920,
        fileSize = 4096,
        supportsStreaming = true,
    )

    private fun channel(
        chatId: Long = 1,
        lastNewMessageId: Long? = null,
        videoSearchCursor: Long = 0L,
        videoSearchCompleted: Boolean = false,
    ) = ChannelEntity(
        chatId = chatId,
        title = "测试频道$chatId",
        username = null,
        isSelected = true,
        lastNewMessageId = lastNewMessageId,
        initialScanCompleted = videoSearchCompleted,
        videoSearchCursor = videoSearchCursor,
        videoSearchCompleted = videoSearchCompleted,
        accessState = ChannelAccessState.AVAILABLE,
        scanState = if (videoSearchCompleted) ChannelScanState.COMPLETED else ChannelScanState.NOT_STARTED,
    )

    private fun persisted(message: TelegramClientMessage, indexedAt: Long): PersistedVideo? {
        val content = message.video ?: return null
        val tags = com.qixuan.channelvideoflow.domain.video.HashtagParser.parse(
            content.caption,
            content.hashtagEntityRanges.map {
                com.qixuan.channelvideoflow.domain.video.Utf16TextRange(it.offset, it.length)
            },
        ).tags
        return PersistedVideo(
            VideoEntity(
                chatId = message.chatId,
                messageId = message.messageId,
                fileId = content.fileId,
                remoteUniqueId = content.remoteUniqueId,
                caption = content.caption,
                durationSeconds = content.durationSeconds,
                width = content.width,
                height = content.height,
                fileSize = content.fileSize,
                supportsStreaming = content.supportsStreaming,
                publishTime = message.publishTime,
                editTime = message.editTime,
                canBeSaved = message.canBeSaved,
                indexedAt = indexedAt,
            ),
            tags.map { com.qixuan.channelvideoflow.database.PersistedVideoTag(it.normalizedName, it.displayName) },
        )
    }

    private class FakeClock(var now: Long = 0) : MessageScanClock {
        override fun nowMillis(): Long = now
    }

    private class FakeDelay(private val clock: FakeClock) : MessageScanDelay {
        val waits = mutableListOf<Long>()
        override suspend fun await(millis: Long) {
            waits += millis
            clock.now += millis
        }
    }

    private data class SearchCall(
        val chatId: Long,
        val fromMessageId: Long,
        val calledAt: Long,
    )

    private class FakeMessageClient(private val clock: FakeClock) : TelegramMessageClient {
        private val events = MutableSharedFlow<TelegramMessageClientEvent>(extraBufferCapacity = 32)
        override val messageEvents: Flow<TelegramMessageClientEvent> = events
        val results = ArrayDeque<TelegramClientResult<TelegramClientVideoSearchPage>>()
        val calls = mutableListOf<SearchCall>()
        var searchHandler: (suspend (Long, Long) -> TelegramClientResult<TelegramClientVideoSearchPage>)? = null
        var propertiesResult: TelegramClientResult<TelegramClientMessageProperties> =
            TelegramClientResult.Failure(TelegramClientFailure.NotFound)
        var linkResult: TelegramClientResult<TelegramClientMessageLink> =
            TelegramClientResult.Failure(TelegramClientFailure.NotFound)
        var messageResult: TelegramClientResult<TelegramClientMessage> =
            TelegramClientResult.Failure(TelegramClientFailure.NotFound)
        var messageGate: CompletableDeferred<Unit>? = null
        val messageCalls = mutableListOf<String>()
        val linkCalls = mutableListOf<String>()

        override suspend fun searchChatVideos(
            chatId: Long,
            fromMessageId: Long,
            limit: Int,
        ): TelegramClientResult<TelegramClientVideoSearchPage> {
            calls += SearchCall(chatId, fromMessageId, clock.now)
            searchHandler?.let { return it(chatId, fromMessageId) }
            return results.removeFirstOrNull()
                ?: TelegramClientResult.Success(
                    TelegramClientVideoSearchPage(emptyList(), 0, 0),
                )
        }

        override suspend fun getMessage(
            chatId: Long,
            messageId: Long,
        ): TelegramClientResult<TelegramClientMessage> {
            messageCalls += "message:$chatId:$messageId"
            messageGate?.await()
            return messageResult
        }

        override suspend fun getMessageProperties(
            chatId: Long,
            messageId: Long,
        ): TelegramClientResult<TelegramClientMessageProperties> {
            linkCalls += "properties:$chatId:$messageId"
            return propertiesResult
        }

        override suspend fun getMessageLink(
            chatId: Long,
            messageId: Long,
        ): TelegramClientResult<TelegramClientMessageLink> {
            linkCalls += "link:$chatId:$messageId"
            return linkResult
        }

        fun emit(event: TelegramMessageClientEvent) {
            assertTrue(events.tryEmit(event))
        }
    }

    private class FakeMessageIndexStore(
        initial: ChannelEntity,
        vararg additional: ChannelEntity,
    ) : MessageIndexStore {
        private val channels = (listOf(initial) + additional).associateByTo(mutableMapOf()) { it.chatId }
        private val scanRecords = MutableStateFlow(emptyList<ChannelScanRecord>())
        val pages = mutableListOf<VideoPageWrite>()
        val videos = mutableMapOf<Pair<Long, Long>, PersistedVideo>()
        val deleted = mutableSetOf<Pair<Long, Long>>()
        val invalidatedAt = mutableMapOf<Pair<Long, Long>, Long>()
        val purgeCutoffs = mutableListOf<Long>()
        var foregroundScanning = false
        var failVideoObservation = false
        var failTagObservation = false
        var failScanObservation = false
        var failNextPurge = false

        fun channel(chatId: Long = 1) = channels.getValue(chatId)

        override fun observeSelectedChannelScans(): Flow<List<ChannelScanRecord>> = if (failScanObservation) {
            flow { throw IllegalStateException("database unavailable") }
        } else {
            scanRecords
        }
        override suspend fun getSelectedScanChannels() = channels.values.filter {
            it.isSelected && it.accessState == ChannelAccessState.AVAILABLE
        }
        override suspend fun getChannel(chatId: Long) = channels[chatId]

        override suspend fun commitPage(page: VideoPageWrite) {
            pages += page
            page.videos.forEach { videos[it.video.chatId to it.video.messageId] = it }
            val current = channels.getValue(page.chatId)
            val completed = current.videoSearchCompleted || page.searchCompleted
            channels[page.chatId] = current.copy(
                lastNewMessageId = listOfNotNull(current.lastNewMessageId, page.latestMessageId).maxOrNull(),
                initialScanCompleted = current.initialScanCompleted || page.searchCompleted,
                videoSearchCursor = if (page.advanceSearchCursor && !page.paginationStalled) {
                    page.nextSearchCursor
                } else {
                    current.videoSearchCursor
                },
                videoSearchCompleted = completed,
                videoCandidateCount = current.videoCandidateCount + page.candidateCount,
                videoSearchPageCount = current.videoSearchPageCount + 1,
                approximateVideoCount = page.approximateTotalCount ?: current.approximateVideoCount,
                scanState = when {
                    page.paginationStalled -> ChannelScanState.ERROR
                    completed -> ChannelScanState.COMPLETED
                    else -> ChannelScanState.SCANNING
                },
                scanRetryAt = null,
                scanRetryCount = 0,
                scanFailureCode = if (page.paginationStalled) "PAGINATION_STALLED" else null,
            )
        }

        override suspend fun upsertIncremental(persisted: PersistedVideo, committedAt: Long) {
            videos[persisted.video.chatId to persisted.video.messageId] = persisted
            recordIncrementalPosition(persisted.video.chatId, persisted.video.messageId, committedAt)
        }
        override suspend fun recordIncrementalPosition(chatId: Long, messageId: Long, committedAt: Long) {
            val current = channels.getValue(chatId)
            channels[chatId] = current.copy(
                lastNewMessageId = maxOf(current.lastNewMessageId ?: messageId, messageId),
                lastSyncTime = committedAt,
            )
        }
        override suspend fun getVideo(chatId: Long, messageId: Long) = videos[chatId to messageId]?.video
        override suspend fun replaceVideoAndTags(persisted: PersistedVideo) {
            videos[persisted.video.chatId to persisted.video.messageId] = persisted
            deleted -= persisted.video.chatId to persisted.video.messageId
            invalidatedAt -= persisted.video.chatId to persisted.video.messageId
        }
        override suspend fun markUnsupportedEdit(chatId: Long, messageId: Long, invalidatedAt: Long) {
            val key = chatId to messageId
            deleted += key
            this.invalidatedAt[key] = invalidatedAt
        }
        override suspend fun updateEditTime(chatId: Long, messageId: Long, editTime: Long?) {
            val key = chatId to messageId
            val current = videos[key] ?: return
            videos[key] = current.copy(video = current.video.copy(editTime = editTime))
        }
        override suspend fun deleteMessages(
            chatId: Long,
            messageIds: List<Long>,
            invalidatedAt: Long,
        ) {
            messageIds.forEach { messageId ->
                val key = chatId to messageId
                deleted += key
                this.invalidatedAt[key] = invalidatedAt
            }
        }
        override suspend fun purgeInvalidatedBefore(cutoffExclusive: Long): Int {
            purgeCutoffs += cutoffExclusive
            if (failNextPurge) {
                failNextPurge = false
                error("synthetic retention cleanup failure")
            }
            val expired = invalidatedAt.filterValues { it < cutoffExclusive }.keys
            expired.forEach { key ->
                invalidatedAt -= key
                deleted -= key
                videos -= key
            }
            return expired.size
        }
        override suspend fun updateScanFailure(
            chatId: Long,
            state: ChannelScanState,
            failureCode: String,
            failureDetail: Int?,
            retryAt: Long?,
            retryCount: Int,
        ) {
            val current = channels.getValue(chatId)
            channels[chatId] = current.copy(
                scanState = state,
                scanFailureCode = failureCode,
                scanFailureDetail = failureDetail,
                scanRetryAt = retryAt,
                scanRetryCount = retryCount,
            )
        }
        override suspend fun markAccessLost(chatId: Long) {
            val current = channels.getValue(chatId)
            channels[chatId] = current.copy(
                isSelected = false,
                accessState = ChannelAccessState.UNAVAILABLE,
                scanState = ChannelScanState.ERROR,
            )
        }
        override suspend fun setForegroundScanning(isForeground: Boolean) {
            foregroundScanning = isForeground
            channels.replaceAll { _, channel ->
                if (channel.videoSearchCompleted || channel.scanPausedByUser) channel else channel.copy(
                    scanState = if (isForeground) ChannelScanState.SCANNING else ChannelScanState.PAUSED,
                )
            }
        }
        override suspend fun setUserPaused(paused: Boolean) {
            channels.replaceAll { _, channel ->
                if (channel.videoSearchCompleted && channel.scanState != ChannelScanState.ERROR) {
                    channel
                } else {
                    val mustHonorFloodWait = !paused && channel.scanFailureCode == "FLOOD_WAIT"
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
                    )
                }
            }
        }
        override suspend fun clearAllIndex() {
            videos.clear()
            deleted.clear()
            invalidatedAt.clear()
        }
        override fun observeFilteredVideos(
            channelIds: Set<Long>,
            normalizedTags: Set<String>,
            tagMode: TagFilterMode,
        ): Flow<List<VideoEntity>> = if (failVideoObservation) {
            flow { throw IllegalStateException("database unavailable") }
        } else {
            flowOf(emptyList())
        }
        override suspend fun getVideoTagsForChannels(channelIds: List<Long>): List<VideoTagRecord> = emptyList()
        override fun observeTagSummaries(channelIds: Set<Long>): Flow<List<TagSummaryRecord>> =
            if (failTagObservation) {
                flow { throw IllegalStateException("database unavailable") }
            } else {
                flowOf(emptyList())
            }
    }
}
