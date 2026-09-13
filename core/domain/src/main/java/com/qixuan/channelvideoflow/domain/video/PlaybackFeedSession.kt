package com.qixuan.channelvideoflow.domain.video

import com.qixuan.channelvideoflow.domain.message.VideoFeedKeySnapshot
import com.qixuan.channelvideoflow.model.video.VideoFeedOrder
import com.qixuan.channelvideoflow.model.video.VideoKey

data class PlaybackFeedWindow(
    val generation: Long,
    val centerIndex: Int,
    val keys: List<VideoKey>,
)

data class PlaybackFeedRound(
    val keys: List<VideoKey>,
    val generation: Long,
) {
    fun entry(index: Int): PlaybackFeedRoundEntry {
        val key = keys.getOrNull(index) ?: error("Feed round index is out of bounds")
        return PlaybackFeedRoundEntry(key, generation, index)
    }
}

data class PlaybackFeedRoundEntry(
    val key: VideoKey,
    val roundGeneration: Long,
    val index: Int,
)

data class PlaybackFeedSnapshot(
    val generation: Long,
    val order: VideoFeedOrder,
    val keys: List<VideoKey>,
    val currentIndex: Int,
    val roundGeneration: Long? = null,
    val upcoming: PlaybackFeedRound? = null,
) {
    val currentKey: VideoKey? get() = keys.getOrNull(currentIndex)

    fun currentEntry(): PlaybackFeedRoundEntry? = roundGeneration?.let { generation ->
        currentKey?.let { key -> PlaybackFeedRoundEntry(key, generation, currentIndex) }
    }

    fun nextRandomEntry(): PlaybackFeedRoundEntry? = when {
        order != VideoFeedOrder.RANDOM || keys.size <= 1 -> null
        currentIndex < keys.lastIndex -> PlaybackFeedRoundEntry(
            key = keys[currentIndex + 1],
            roundGeneration = requireNotNull(roundGeneration),
            index = currentIndex + 1,
        )
        else -> upcoming?.takeIf { it.keys.isNotEmpty() }?.entry(0)
    }

    fun window(radius: Int = DEFAULT_HYDRATION_RADIUS): PlaybackFeedWindow {
        require(radius >= 0)
        if (keys.isEmpty()) return PlaybackFeedWindow(generation, 0, emptyList())
        val start = (currentIndex - radius).coerceAtLeast(0)
        val endExclusive = (currentIndex + radius + 1).coerceAtMost(keys.size)
        return PlaybackFeedWindow(generation, currentIndex, keys.subList(start, endExclusive))
    }

    fun windowAround(
        centerIndex: Int,
        radius: Int = DEFAULT_HYDRATION_RADIUS,
        includeUpcomingCount: Int = radius + 1,
    ): PlaybackFeedWindow {
        require(radius >= 0)
        require(includeUpcomingCount >= 0)
        if (keys.isEmpty()) return PlaybackFeedWindow(generation, 0, emptyList())
        val boundedCenter = centerIndex.coerceIn(keys.indices)
        val start = (boundedCenter - radius).coerceAtLeast(0)
        val endExclusive = (boundedCenter + radius + 1).coerceAtMost(keys.size)
        val windowKeys = LinkedHashSet<VideoKey>(endExclusive - start + includeUpcomingCount)
        windowKeys += keys.subList(start, endExclusive)
        if (order == VideoFeedOrder.RANDOM && endExclusive == keys.size) {
            windowKeys += upcoming?.keys.orEmpty().take(includeUpcomingCount)
        }
        return PlaybackFeedWindow(generation, boundedCenter, windowKeys.toList())
    }

    private companion object {
        const val DEFAULT_HYDRATION_RADIUS = 3
    }
}

/**
 * Owns only lightweight Room keys. Full captions, variants and tags are hydrated for a bounded
 * window by the caller. Reconciliation preserves the current key and existing RANDOM order.
 */
class PlaybackFeedSession(
    private val random: VideoQueueRandomSource = VideoQueueRandomSource(kotlin.random.Random.Default::nextInt),
) {
    private var nextGeneration = 0L
    private var nextRoundGeneration = 0L
    private var lastPlayedRandomKey: VideoKey? = null
    private var snapshot = PlaybackFeedSnapshot(0L, VideoFeedOrder.LATEST, emptyList(), 0)

    fun replace(
        rows: List<VideoFeedKeySnapshot>,
        order: VideoFeedOrder,
    ): PlaybackFeedSnapshot {
        // Room already emits this order. Validate linearly before paying for another sort.
        val comparator = compareByDescending<VideoFeedKeySnapshot> { it.publishTime }
            .thenByDescending { it.key.chatId }.thenByDescending { it.key.messageId }
        val sorted = (1 until rows.size).all { comparator.compare(rows[it - 1], rows[it]) <= 0 }
        val latestKeys = (if (sorted) rows else rows.sortedWith(comparator)).map(VideoFeedKeySnapshot::key)
        val oldCurrent = snapshot.currentKey
        val previousSnapshot = snapshot
        val nextKeys: List<VideoKey>
        val nextRoundGeneration: Long?
        val nextUpcoming: PlaybackFeedRound?
        if (order == VideoFeedOrder.LATEST) {
            nextKeys = latestKeys
            nextRoundGeneration = null
            nextUpcoming = null
        } else if (previousSnapshot.order != VideoFeedOrder.RANDOM) {
            val current = newRound(latestKeys, lastPlayedRandomKey)
            nextKeys = current.keys
            nextRoundGeneration = current.generation
            nextUpcoming = upcomingRound(latestKeys, current.keys.lastOrNull())
        } else {
            nextKeys = reconcileRandom(previousSnapshot.keys, latestKeys)
            nextRoundGeneration = previousSnapshot.roundGeneration
            nextUpcoming = when {
                nextKeys.size <= 1 -> null
                previousSnapshot.upcoming == null -> upcomingRound(latestKeys, nextKeys.lastOrNull())
                else -> previousSnapshot.upcoming.copy(
                    keys = avoidBoundaryRepeat(
                        reconcileRandom(previousSnapshot.upcoming.keys, latestKeys),
                        nextKeys.lastOrNull(),
                    ),
                )
            }
        }
        val nextIndex = oldCurrent
            ?.let(nextKeys::indexOf)
            ?.takeIf { index -> index >= 0 }
            ?: snapshot.currentIndex.coerceIn(0, nextKeys.lastIndex.coerceAtLeast(0))
        snapshot = PlaybackFeedSnapshot(
            generation = ++nextGeneration,
            order = order,
            keys = nextKeys,
            currentIndex = nextIndex,
            roundGeneration = nextRoundGeneration,
            upcoming = nextUpcoming,
        )
        return snapshot
    }

    fun settle(index: Int): PlaybackFeedSnapshot {
        if (snapshot.keys.isEmpty()) return snapshot
        snapshot = snapshot.copy(currentIndex = index.coerceIn(snapshot.keys.indices))
        return snapshot
    }

    fun settleRandom(entry: PlaybackFeedRoundEntry): PlaybackFeedSnapshot {
        check(snapshot.order == VideoFeedOrder.RANDOM) { "Random feed session has not started" }
        snapshot = when (entry.roundGeneration) {
            snapshot.roundGeneration -> {
                require(snapshot.keys.getOrNull(entry.index) == entry.key) {
                    "Random current entry does not match its round"
                }
                snapshot.copy(currentIndex = entry.index)
            }
            snapshot.upcoming?.generation -> {
                val promoted = requireNotNull(snapshot.upcoming)
                require(promoted.keys.getOrNull(entry.index) == entry.key) {
                    "Random upcoming entry does not match its round"
                }
                snapshot.copy(
                    keys = promoted.keys,
                    currentIndex = entry.index,
                    roundGeneration = promoted.generation,
                    upcoming = upcomingRound(promoted.keys, promoted.keys.lastOrNull()),
                )
            }
            else -> error("Stale random round generation")
        }
        return snapshot
    }

    fun recordPlayed(key: VideoKey, order: VideoFeedOrder) {
        if (order == VideoFeedOrder.RANDOM) lastPlayedRandomKey = key
    }

    fun reset() {
        snapshot = PlaybackFeedSnapshot(++nextGeneration, VideoFeedOrder.LATEST, emptyList(), 0)
    }

    fun current(): PlaybackFeedSnapshot = snapshot

    private fun reconcileRandom(existing: List<VideoKey>, latest: List<VideoKey>): List<VideoKey> {
        val remaining = latest.toMutableSet()
        val retained = existing.filter(remaining::remove)
        return retained + shuffled(remaining.toList())
    }

    private fun newRound(
        keys: List<VideoKey>,
        previousBoundaryKey: VideoKey?,
    ): PlaybackFeedRound = PlaybackFeedRound(
        keys = avoidBoundaryRepeat(shuffled(keys), previousBoundaryKey),
        generation = ++nextRoundGeneration,
    )

    private fun upcomingRound(
        keys: List<VideoKey>,
        previousBoundaryKey: VideoKey?,
    ): PlaybackFeedRound? = keys
        .takeIf { it.size > 1 }
        ?.let { newRound(it, previousBoundaryKey) }

    private fun avoidBoundaryRepeat(
        keys: List<VideoKey>,
        previousBoundaryKey: VideoKey?,
    ): List<VideoKey> {
        if (keys.size <= 1 || keys.first() != previousBoundaryKey) return keys
        val replacementIndex = keys.indexOfFirst { it != previousBoundaryKey }
        if (replacementIndex <= 0) return keys
        return keys.toMutableList().apply {
            val first = this[0]
            this[0] = this[replacementIndex]
            this[replacementIndex] = first
        }
    }

    private fun shuffled(source: List<VideoKey>): List<VideoKey> = source.toMutableList().apply {
        for (index in lastIndex downTo 1) {
            val target = random.nextInt(index + 1)
            val value = this[index]
            this[index] = this[target]
            this[target] = value
        }
    }
}
