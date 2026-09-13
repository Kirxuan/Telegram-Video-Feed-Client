package com.qixuan.channelvideoflow.domain.video

import com.qixuan.channelvideoflow.domain.message.VideoFeedKeySnapshot
import com.qixuan.channelvideoflow.model.video.VideoFeedOrder
import com.qixuan.channelvideoflow.model.video.VideoKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFeedSessionTest {
    @Test
    fun latestOrderUsesPublishTimeThenCompositeKeyAsStableTieBreakers() {
        val session = PlaybackFeedSession(FixedRandom())

        val snapshot = session.replace(
            listOf(
                row(id = 1, chatId = 1, publishTime = 10),
                row(id = 1, chatId = 2, publishTime = 20),
                row(id = 2, chatId = 2, publishTime = 20),
            ),
            VideoFeedOrder.LATEST,
        )

        assertEquals(listOf(VideoKey(2, 2), VideoKey(2, 1), VideoKey(1, 1)), snapshot.keys)
    }

    @Test
    fun boundedWindowsStaySmallForAllRequiredCardinalities() {
        for (size in listOf(0, 1, 10, 1_000, 100_000)) {
            val session = PlaybackFeedSession(FixedRandom())
            session.replace(rows(size), VideoFeedOrder.LATEST)
            session.settle((size / 2).coerceAtLeast(0))

            val window = session.current().window(radius = 3)

            assertTrue("size=$size", window.keys.size <= 7)
            assertEquals(size, session.current().keys.size)
        }
    }

    @Test
    fun randomReconcilePreservesRetainedOrderAndAppendsNewKeysOnce() {
        val session = PlaybackFeedSession(FixedRandom())
        val first = session.replace(rows(5), VideoFeedOrder.RANDOM)
        session.settle(2)
        val currentKey = session.current().currentKey

        val replacement = rows(5).filterNot { it.key == first.keys[0] } + row(99)
        val reconciled = session.replace(replacement, VideoFeedOrder.RANDOM)

        assertEquals(currentKey, reconciled.currentKey)
        assertEquals(replacement.map { it.key }.toSet(), reconciled.keys.toSet())
        assertEquals(reconciled.keys.size, reconciled.keys.distinct().size)
        assertEquals(VideoKey(1L, 99L), reconciled.keys.last())
    }

    @Test
    fun randomRoundsContainEveryKeyExactlyOnce() {
        val snapshot = PlaybackFeedSession(FixedRandom(0, 0, 0, 0))
            .replace(rows(5), VideoFeedOrder.RANDOM)

        assertEquals(rows(5).map { it.key }.toSet(), snapshot.keys.toSet())
        assertEquals(snapshot.keys.size, snapshot.keys.distinct().size)
        assertEquals(snapshot.keys.toSet(), snapshot.upcoming?.keys?.toSet())
    }

    @Test
    fun deletingUpcomingKeyRevalidatesBothRoundsAndNextEntry() {
        val session = PlaybackFeedSession(FixedRandom(0, 0, 1, 0))
        val started = session.replace(rows(3), VideoFeedOrder.RANDOM)
        val removed = requireNotNull(started.upcoming).keys[1]

        val reconciled = session.replace(
            rows(3).filterNot { it.key == removed },
            VideoFeedOrder.RANDOM,
        )

        val expected = rows(3).map { it.key }.toSet() - removed
        assertEquals(expected, reconciled.keys.toSet())
        assertEquals(expected, reconciled.upcoming?.keys?.toSet())
        assertTrue(reconciled.nextRandomEntry()?.key in expected)
    }

    @Test
    fun newKeyJoinsRetainedCurrentAndUpcomingRoundsExactlyOnce() {
        val session = PlaybackFeedSession(FixedRandom(0, 1, 0, 0))
        session.replace(rows(2), VideoFeedOrder.RANDOM)

        val reconciled = session.replace(rows(2) + row(3), VideoFeedOrder.RANDOM)

        assertEquals(1, reconciled.keys.count { it == VideoKey(1, 3) })
        assertEquals(1, reconciled.upcoming?.keys?.count { it == VideoKey(1, 3) })
    }

    @Test
    fun emptyAndSingleSourcesDoNotCreateUpcomingRounds() {
        val empty = PlaybackFeedSession(FixedRandom()).replace(emptyList(), VideoFeedOrder.RANDOM)
        val single = PlaybackFeedSession(FixedRandom()).replace(rows(1), VideoFeedOrder.RANDOM)

        assertEquals(null, empty.upcoming)
        assertEquals(null, empty.nextRandomEntry())
        assertEquals(null, single.upcoming)
        assertEquals(null, single.nextRandomEntry())
    }

    @Test
    fun resetInvalidatesOldRandomRoundGenerations() {
        val session = PlaybackFeedSession(FixedRandom(0, 0, 0, 0))
        val old = session.replace(rows(3), VideoFeedOrder.RANDOM)

        session.reset()
        val rebuilt = session.replace((4L..6L).map { id -> row(id) }, VideoFeedOrder.RANDOM)

        assertNotEquals(old.roundGeneration, rebuilt.roundGeneration)
        assertNotEquals(old.upcoming?.generation, rebuilt.upcoming?.generation)
        assertEquals((4L..6L).toSet(), rebuilt.keys.map { it.messageId }.toSet())
    }

    @Test
    fun deletingCurrentChoosesDeterministicSameIndexNeighbor() {
        val session = PlaybackFeedSession(FixedRandom())
        session.replace(rows(5), VideoFeedOrder.LATEST)
        session.settle(2)
        val oldIndex = session.current().currentIndex
        val removed = session.current().currentKey

        val reconciled = session.replace(rows(5).filterNot { it.key == removed }, VideoFeedOrder.LATEST)

        assertEquals(oldIndex, reconciled.currentIndex)
        assertEquals(reconciled.keys[oldIndex], reconciled.currentKey)
    }

    @Test
    fun randomWindowAtBoundaryIncludesOnlyBoundedUpcomingKeys() {
        val session = PlaybackFeedSession(FixedRandom())
        val first = session.replace(rows(100_000), VideoFeedOrder.RANDOM)

        val window = first.windowAround(first.keys.lastIndex, radius = 3)

        assertTrue(window.keys.size <= 8)
        assertTrue(first.upcoming?.keys?.first() in window.keys)
        assertEquals(100_000, first.keys.distinct().size)
        assertEquals(100_000, first.upcoming?.keys?.distinct()?.size)
    }

    @Test
    fun promotingUpcomingRoundPreservesNoRepeatBoundaryAndCreatesOneFutureRound() {
        val session = PlaybackFeedSession(FixedRandom())
        val first = session.replace(rows(5), VideoFeedOrder.RANDOM)
        val upcoming = requireNotNull(first.upcoming)

        val promoted = session.settleRandom(upcoming.entry(0))

        assertEquals(upcoming.generation, promoted.roundGeneration)
        assertEquals(upcoming.keys, promoted.keys)
        assertTrue(promoted.upcoming != null)
        assertTrue(promoted.keys.last() != promoted.upcoming?.keys?.first())
    }

    private fun rows(size: Int): List<VideoFeedKeySnapshot> =
        (1..size).map { id -> row(id.toLong()) }

    private fun row(
        id: Long,
        chatId: Long = 1L,
        publishTime: Long = id,
    ) = VideoFeedKeySnapshot(VideoKey(chatId, id), publishTime, null)

    private class FixedRandom(vararg values: Int) : VideoQueueRandomSource {
        private val sequence = values.toList()
        private var index = 0

        override fun nextInt(until: Int): Int = sequence.getOrElse(index++) { 0 }.mod(until)
    }
}
