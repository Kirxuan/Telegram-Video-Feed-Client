package com.qixuan.channelvideoflow.telegram.message

import android.os.SystemClock
import android.util.Log
import com.qixuan.channelvideoflow.database.ChannelEntity
import com.qixuan.channelvideoflow.database.ChannelScanRecord
import com.qixuan.channelvideoflow.database.PersistedVideo
import com.qixuan.channelvideoflow.database.PersistedVideoTag
import com.qixuan.channelvideoflow.database.VideoEntity
import com.qixuan.channelvideoflow.database.VideoPageWrite
import com.qixuan.channelvideoflow.telegram.BuildConfig
import com.qixuan.channelvideoflow.domain.message.TelegramMessageRepository
import com.qixuan.channelvideoflow.domain.message.RepositoryObservation
import com.qixuan.channelvideoflow.domain.message.RepositoryObservationFailure
import com.qixuan.channelvideoflow.domain.message.VideoReferenceFailure
import com.qixuan.channelvideoflow.domain.message.VideoReferenceResolution
import com.qixuan.channelvideoflow.domain.message.VideoFeedKeySnapshot
import com.qixuan.channelvideoflow.domain.video.HashtagParser
import com.qixuan.channelvideoflow.domain.video.Utf16TextRange
import com.qixuan.channelvideoflow.model.channel.ChannelAccessState
import com.qixuan.channelvideoflow.model.channel.ChannelScanState
import com.qixuan.channelvideoflow.model.video.ChannelVideoScanProgress
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.OriginalMessageLinkResult
import com.qixuan.channelvideoflow.model.video.TagSummary
import com.qixuan.channelvideoflow.model.video.TelegramMessageFailure
import com.qixuan.channelvideoflow.model.video.VideoFilter
import com.qixuan.channelvideoflow.model.video.VideoKey
import com.qixuan.channelvideoflow.model.video.TelegramMediaFileReference
import com.qixuan.channelvideoflow.model.video.VideoPlaybackVariant
import com.qixuan.channelvideoflow.model.video.VideoScanStatus
import com.qixuan.channelvideoflow.model.video.VideoTag
import com.qixuan.channelvideoflow.telegram.client.TelegramClientFailure
import com.qixuan.channelvideoflow.telegram.client.TelegramClientMessage
import com.qixuan.channelvideoflow.telegram.client.TelegramClientResult
import com.qixuan.channelvideoflow.telegram.client.TelegramClientVideoContent
import com.qixuan.channelvideoflow.telegram.client.TelegramClientVideoSearchPage
import com.qixuan.channelvideoflow.telegram.client.TelegramMessageClient
import com.qixuan.channelvideoflow.telegram.client.TelegramMessageClientEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

internal fun interface MessageScanClock {
    fun nowMillis(): Long
}

internal fun interface MessageScanDelay {
    suspend fun await(millis: Long)
}

internal fun interface MessageScanJitter {
    fun nextMillis(upperBoundInclusive: Long): Long
}

private fun <T> Flow<T>.withDatabaseFailure(initialValue: T): Flow<RepositoryObservation<T>> = flow {
    var latestValue = initialValue
    var failures = 0
    while (currentCoroutineContext().isActive) {
        try {
            collect { value ->
                latestValue = value
                emit(RepositoryObservation(value))
            }
            return@flow
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            emit(
                RepositoryObservation(
                    value = latestValue,
                    failure = RepositoryObservationFailure.DATABASE,
                ),
            )
            failures += 1
            if (failures >= 3) return@flow
            delay(OBSERVATION_RETRY_DELAY_MILLIS * failures)
        }
    }
}

private const val OBSERVATION_RETRY_DELAY_MILLIS = 1_000L

@OptIn(ExperimentalCoroutinesApi::class)
internal class TdLibTelegramMessageRepository(
    private val client: TelegramMessageClient,
    private val store: MessageIndexStore,
    private val scope: CoroutineScope,
    private val clock: MessageScanClock = MessageScanClock(System::currentTimeMillis),
    private val delayStrategy: MessageScanDelay = MessageScanDelay { millis -> delay(millis) },
    private val jitter: MessageScanJitter = MessageScanJitter { upperBoundInclusive ->
        Random.Default.nextLong(upperBoundInclusive + 1)
    },
) : TelegramMessageRepository {
    override val scanProgressObservation: Flow<RepositoryObservation<List<ChannelVideoScanProgress>>> = store
        .observeSelectedChannelScans()
        .map { records -> records.map(::mapProgress) }
        .withDatabaseFailure(emptyList())

    override val scanProgress: Flow<List<ChannelVideoScanProgress>> =
        scanProgressObservation.map { observation -> observation.value }

    private val lifecycleMutex = Mutex()
    private val refreshSingleFlight = VideoRefreshSingleFlight(scope)
    private val refreshFloodWaitMutex = Mutex()
    private var refreshFloodWaitUntilMillis = 0L
    private val scanFloodWaitMutex = Mutex()
    private var scanFloodWaitUntilMillis = 0L
    private val foreground = AtomicBoolean(false)
    private val retentionCleanupPending = AtomicBoolean(true)
    private var coordinatorJob: Job? = null

    init {
        scope.launch {
            client.messageEvents.collect { event ->
                try {
                    handleEvent(event)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // The next foreground reconciliation is the bounded recovery path.
                }
            }
        }
    }

    override fun observeVideoObservation(
        filter: VideoFilter,
    ): Flow<RepositoryObservation<List<IndexedVideo>>> = observeVideoKeyObservation(filter)
        .mapLatest { keyObservation ->
            if (keyObservation.failure != null) {
                return@mapLatest RepositoryObservation(emptyList(), keyObservation.failure)
            }
            val hydrated = ArrayList<IndexedVideo>(keyObservation.value.size)
            for (window in keyObservation.value.map(VideoFeedKeySnapshot::key)
                .chunked(HYDRATION_QUERY_WINDOW_SIZE)) {
                val observation = hydrateVideos(window)
                if (observation.failure != null) {
                    return@mapLatest RepositoryObservation(emptyList(), observation.failure)
                }
                hydrated += observation.value
            }
            RepositoryObservation(hydrated)
        }

    override fun observeVideos(filter: VideoFilter): Flow<List<IndexedVideo>> =
        observeVideoObservation(filter).map { observation -> observation.value }

    override fun observeVideoKeyObservation(
        filter: VideoFilter,
    ): Flow<RepositoryObservation<List<VideoFeedKeySnapshot>>> = flow {
        var previousEmissionAt = SystemClock.elapsedRealtime()
        var firstEmission = true
        store.observeFilteredVideoKeys(filter.channelIds, filter.normalizedTags, filter.tagMode)
            .collect { rows ->
                val now = SystemClock.elapsedRealtime()
                val observationGapMillis = (now - previousEmissionAt).coerceAtLeast(0L)
                // A Room Flow's subsequent emission includes the time the database stayed idle
                // between invalidations. Initial subscription latency includes scheduling;
                // it is not a measurement of SQLite execution time.
                val firstEmissionMillis = if (firstEmission) observationGapMillis else null
                traceFeedPerformance(
                    firstEmissionMillis = firstEmissionMillis,
                    observationGapMillis = if (firstEmission) null else observationGapMillis,
                    snapshotKeys = rows.size,
                )
                emit(
                    rows.map { row ->
                        VideoFeedKeySnapshot(
                            key = VideoKey(row.chatId, row.messageId),
                            publishTime = row.publishTime,
                            editTime = row.editTime,
                        )
                    },
                )
                previousEmissionAt = now
                firstEmission = false
            }
    }
        .withDatabaseFailure(emptyList())

    override suspend fun hydrateVideos(
        keys: List<VideoKey>,
    ): RepositoryObservation<List<IndexedVideo>> {
        if (keys.isEmpty()) return RepositoryObservation(emptyList())
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val keyPairs = keys.map { key -> key.chatId to key.messageId }
            val tagsByKey = store.getVideoTagsForKeyWindow(keyPairs)
                .groupBy { record -> VideoKey(record.chatId, record.messageId) }
            val entitiesByKey = store.getVideosForKeyWindow(keyPairs)
                .associateBy { entity -> VideoKey(entity.chatId, entity.messageId) }
            val observation = RepositoryObservation(
                keys.mapNotNull { key ->
                    entitiesByKey[key]?.toModel(
                        tagsByKey[key].orEmpty().map { record ->
                            VideoTag(record.normalizedTagName, record.displayName)
                        },
                    )
                },
            )
            traceFeedPerformance(
                hydrateMillis = SystemClock.elapsedRealtime() - startedAt,
                hydratedItems = observation.value.size,
            )
            observation
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            RepositoryObservation(emptyList(), RepositoryObservationFailure.DATABASE)
        }
    }

    private fun traceFeedPerformance(
        firstEmissionMillis: Long? = null,
        observationGapMillis: Long? = null,
        hydrateMillis: Long? = null,
        snapshotKeys: Int? = null,
        hydratedItems: Int? = null,
    ) {
        if (!BuildConfig.PERFORMANCE_DIAGNOSTICS_ENABLED) return
        Log.i(
            FEED_PERF_TAG,
            "summary firstEmissionMs=$firstEmissionMillis hydrateMs=$hydrateMillis " +
                "observationGapMs=$observationGapMillis " +
                "snapshotKeys=$snapshotKeys hydratedItems=$hydratedItems",
        )
    }

    override fun observeTagObservation(
        channelIds: Set<Long>,
    ): Flow<RepositoryObservation<List<TagSummary>>> = store
        .observeTagSummaries(channelIds)
        .map { records ->
            records.map { record ->
                TagSummary(record.normalizedName, record.displayName, record.videoCount)
            }
        }
        .withDatabaseFailure(emptyList())

    override fun observeTags(channelIds: Set<Long>): Flow<List<TagSummary>> =
        observeTagObservation(channelIds).map { observation -> observation.value }

    override suspend fun refreshVideo(videoKey: VideoKey): VideoReferenceResolution {
        currentRefreshFloodWait()?.let { failure ->
            return VideoReferenceResolution.Unavailable(failure)
        }
        return refreshSingleFlight.await(videoKey) { refreshVideoOnce(videoKey) }
    }

    private suspend fun refreshVideoOnce(videoKey: VideoKey): VideoReferenceResolution {
        val refreshed = try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) {
                client.getMessage(videoKey.chatId, videoKey.messageId)
            }
        } catch (_: TimeoutCancellationException) {
            return VideoReferenceResolution.Unavailable(VideoReferenceFailure.Timeout)
        }
        val message = when (refreshed) {
            is TelegramClientResult.Success -> refreshed.value
            is TelegramClientResult.Failure -> {
                if (refreshed.failure == TelegramClientFailure.NotFound) {
                    store.deleteMessages(
                        videoKey.chatId,
                        listOf(videoKey.messageId),
                        clock.nowMillis(),
                    )
                    return VideoReferenceResolution.MessageMissing
                }
                val failure = refreshed.failure.toReferenceFailure()
                if (failure is VideoReferenceFailure.FloodWait) {
                    recordRefreshFloodWait(failure.retryAfterSeconds)
                }
                return VideoReferenceResolution.Unavailable(failure)
            }
        }
        if (message.chatId != videoKey.chatId || message.messageId != videoKey.messageId) {
            return VideoReferenceResolution.Unavailable(VideoReferenceFailure.Unknown)
        }
        val refreshedVideo = message.video
            ?: run {
                store.markUnsupportedEdit(videoKey.chatId, videoKey.messageId, clock.nowMillis())
                return VideoReferenceResolution.UnsupportedMessage
            }
        val indexedAt = clock.nowMillis()
        val persisted = message.toPersistedVideo(indexedAt)
            ?: run {
                store.markUnsupportedEdit(videoKey.chatId, videoKey.messageId, clock.nowMillis())
                return VideoReferenceResolution.UnsupportedMessage
            }
        store.replaceVideoAndTags(persisted)
        return VideoReferenceResolution.Resolved(persisted.video.toModel(
            persisted.tags.map { tag -> VideoTag(tag.normalizedName, tag.displayName) },
        ).copy(
            alternativeVariants = refreshedVideo.alternativeVariants.map { alternative ->
                VideoPlaybackVariant(
                    alternativeId = alternative.alternativeId,
                    fileId = alternative.fileId,
                    remoteUniqueId = alternative.remoteUniqueId,
                    fileSize = alternative.fileSize,
                    width = alternative.width,
                    height = alternative.height,
                    codec = alternative.codec,
                    hlsManifestFile = alternative.hlsManifestFile?.let { manifest ->
                        TelegramMediaFileReference(
                            fileId = manifest.fileId,
                            remoteUniqueId = manifest.remoteUniqueId,
                            fileSize = manifest.fileSize,
                        )
                    },
                )
            },
        ))
    }

    private suspend fun currentRefreshFloodWait(): VideoReferenceFailure.FloodWait? =
        refreshFloodWaitMutex.withLock {
            val remainingMillis = refreshFloodWaitUntilMillis - clock.nowMillis()
            if (remainingMillis <= 0L) {
                refreshFloodWaitUntilMillis = 0L
                null
            } else {
                val remainingSeconds = ((remainingMillis - 1L) / 1_000L + 1L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                VideoReferenceFailure.FloodWait(remainingSeconds)
            }
        }

    private suspend fun recordRefreshFloodWait(retryAfterSeconds: Int) {
        val waitMillis = retryAfterSeconds.coerceAtLeast(0).toLong() * 1_000L
        if (waitMillis <= 0L) return
        refreshFloodWaitMutex.withLock {
            val now = clock.nowMillis()
            val deadline = if (waitMillis > Long.MAX_VALUE - now) {
                Long.MAX_VALUE
            } else {
                now + waitMillis
            }
            refreshFloodWaitUntilMillis = maxOf(refreshFloodWaitUntilMillis, deadline)
        }
    }

    private suspend fun clearRefreshFloodWait() {
        refreshFloodWaitMutex.withLock {
            refreshFloodWaitUntilMillis = 0L
        }
    }

    override suspend fun getOriginalMessageLink(videoKey: VideoKey): OriginalMessageLinkResult {
        val properties = client.getMessageProperties(videoKey.chatId, videoKey.messageId)
        val canGetLink = (properties as? TelegramClientResult.Success)?.value?.canGetLink
            ?: return properties.toOriginalMessageLinkResult()
        if (!canGetLink) return OriginalMessageLinkResult.Unavailable

        return when (val result = client.getMessageLink(videoKey.chatId, videoKey.messageId)) {
            is TelegramClientResult.Success -> OriginalMessageLinkResult.Available(result.value.httpsUrl)
            is TelegramClientResult.Failure -> result.toOriginalMessageLinkResult()
        }
    }

    override suspend fun setForeground(isForeground: Boolean) {
        if (isForeground && retentionCleanupPending.compareAndSet(true, false)) {
            try {
                store.purgeInvalidatedBefore(retentionCutoff(clock.nowMillis()))
            } catch (failure: Throwable) {
                retentionCleanupPending.set(true)
                throw failure
            }
        }
        lifecycleMutex.withLock {
            foreground.set(isForeground)
            coordinatorJob?.cancel()
            coordinatorJob = null
            store.setForegroundScanning(isForeground)
            if (isForeground) startCoordinatorLocked()
        }
    }

    override suspend fun refreshSelection() {
        lifecycleMutex.withLock {
            coordinatorJob?.cancel()
            coordinatorJob = null
            if (foreground.get()) startCoordinatorLocked()
        }
    }

    override suspend fun pauseScanning() {
        lifecycleMutex.withLock {
            coordinatorJob?.cancel()
            coordinatorJob = null
            store.setUserPaused(true)
        }
    }

    override suspend fun resumeScanning() {
        lifecycleMutex.withLock {
            store.setUserPaused(false)
            coordinatorJob?.cancel()
            coordinatorJob = null
            if (foreground.get()) startCoordinatorLocked()
        }
    }

    private fun startCoordinatorLocked() {
        coordinatorJob = scope.launch {
            try {
                scanLoop()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                val selected = runCatching { store.getSelectedScanChannels() }
                    .getOrDefault(emptyList())
                selected.forEach { channel ->
                    runCatching {
                        store.updateScanFailure(
                            chatId = channel.chatId,
                            state = ChannelScanState.ERROR,
                            failureCode = FAILURE_DATABASE,
                            failureDetail = null,
                            retryAt = null,
                            retryCount = channel.scanRetryCount,
                        )
                    }
                }
            }
        }
    }

    private suspend fun scanLoop() {
        val recentSessions = mutableMapOf<Long, RecentSyncSession>()
        var roundOffset = 0
        while (currentCoroutineContext().isActive && foreground.get()) {
            val selected = store.getSelectedScanChannels()
                .filter { channel ->
                    !channel.scanPausedByUser && channel.scanState != ChannelScanState.ERROR
                }
            if (selected.isEmpty()) return

            recentSessions.keys.retainAll(selected.mapTo(hashSetOf(), ChannelEntity::chatId))
            selected.forEach { channel ->
                recentSessions.putIfAbsent(
                    channel.chatId,
                    RecentSyncSession(boundaryMessageId = channel.lastNewMessageId),
                )
            }

            val recentWork = rotate(selected, roundOffset).filter { selectedChannel ->
                !recentSessions.getValue(selectedChannel.chatId).completed
            }
            processChannelsBounded(recentWork) { selectedChannel ->
                val session = recentSessions.getValue(selectedChannel.chatId)
                val current = store.getChannel(selectedChannel.chatId)
                if (current == null || !current.isScannable()) {
                    session.completed = true
                } else {
                    scanRecentPage(current, session)
                }
            }

            if (recentSessions.values.all(RecentSyncSession::completed)) {
                val historicalWork = mutableListOf<ChannelEntity>()
                rotate(selected, roundOffset).forEach { selectedChannel ->
                    val current = store.getChannel(selectedChannel.chatId)
                    if (current != null && current.isScannable() && !current.videoSearchCompleted) {
                        historicalWork += selectedChannel
                    }
                }
                processChannelsBounded(historicalWork) { selectedChannel ->
                    val current = store.getChannel(selectedChannel.chatId) ?: return@processChannelsBounded
                    if (!current.isScannable() || current.videoSearchCompleted) {
                        return@processChannelsBounded
                    }
                    scanHistoricalPage(current)
                }
                if (historicalWork.isEmpty()) return
            }

            if (recentWork.isEmpty() && !recentSessions.values.all(RecentSyncSession::completed)) return
            roundOffset = (roundOffset + 1) % selected.size
            yield()
        }
    }

    private suspend fun scanRecentPage(
        channel: ChannelEntity,
        session: RecentSyncSession,
    ) {
        val requestCursor = session.fromMessageId
        val page = requestVideoSearchWithRetry(channel, requestCursor)
        if (page == null) {
            session.completed = true
            return
        }
        val messages = page.messages
        val latestInPage = messages.maxOfOrNull(TelegramClientMessage::messageId)
        val reachedBoundary = session.boundaryMessageId == null ||
            messages.any { message -> message.messageId <= session.boundaryMessageId }
        val paginationStalled = isPaginationStalled(requestCursor, page.nextFromMessageId)
        val advancesHistoricalSearch = !channel.videoSearchCompleted &&
            channel.videoSearchCursor == requestCursor

        store.commitPage(
            VideoPageWrite(
                chatId = channel.chatId,
                videos = messages.mapNotNull { message -> message.toPersistedVideo(clock.nowMillis()) },
                candidateCount = messages.size,
                latestMessageId = latestInPage,
                nextSearchCursor = page.nextFromMessageId,
                advanceSearchCursor = advancesHistoricalSearch,
                searchCompleted = advancesHistoricalSearch && page.nextFromMessageId == 0L,
                approximateTotalCount = page.approximateTotalCount,
                paginationStalled = paginationStalled,
                committedAt = clock.nowMillis(),
            ),
        )

        session.completed = reachedBoundary || page.nextFromMessageId == 0L || paginationStalled
        if (!session.completed) session.fromMessageId = page.nextFromMessageId
    }

    private suspend fun scanHistoricalPage(channel: ChannelEntity) {
        val fromMessageId = channel.videoSearchCursor
        val page = requestVideoSearchWithRetry(channel, fromMessageId) ?: return
        val messages = page.messages
        val paginationStalled = isPaginationStalled(fromMessageId, page.nextFromMessageId)
        store.commitPage(
            VideoPageWrite(
                chatId = channel.chatId,
                videos = messages.mapNotNull { message -> message.toPersistedVideo(clock.nowMillis()) },
                candidateCount = messages.size,
                latestMessageId = if (channel.lastNewMessageId == null) {
                    messages.maxOfOrNull(TelegramClientMessage::messageId)
                } else {
                    null
                },
                nextSearchCursor = page.nextFromMessageId,
                advanceSearchCursor = true,
                searchCompleted = page.nextFromMessageId == 0L,
                approximateTotalCount = page.approximateTotalCount,
                paginationStalled = paginationStalled,
                committedAt = clock.nowMillis(),
            ),
        )
    }

    private suspend fun requestVideoSearchWithRetry(
        channel: ChannelEntity,
        fromMessageId: Long,
    ): TelegramClientVideoSearchPage? {
        var attempts = channel.scanRetryCount
        channel.scanRetryAt?.let { retryAt ->
            if (channel.scanFailureCode == FAILURE_FLOOD_WAIT) {
                recordScanFloodWaitDeadline(retryAt)
            } else {
                val remaining = retryAt - clock.nowMillis()
                if (remaining > 0) delayStrategy.await(remaining)
            }
        }

        while (currentCoroutineContext().isActive && foreground.get()) {
            awaitScanFloodWaitGate()
            val result = try {
                withTimeout(REQUEST_TIMEOUT_MILLIS) {
                    client.searchChatVideos(channel.chatId, fromMessageId, PAGE_SIZE)
                }
            } catch (_: TimeoutCancellationException) {
                TelegramClientResult.Failure(TelegramClientFailure.Timeout)
            }
            when (result) {
                is TelegramClientResult.Success -> return result.value
                is TelegramClientResult.Failure -> {
                    val failure = result.failure
                    if (failure == TelegramClientFailure.AccessLost ||
                        failure == TelegramClientFailure.NotFound
                    ) {
                        store.markAccessLost(channel.chatId)
                        return null
                    }

                    attempts += 1
                    val isFloodWait = failure is TelegramClientFailure.FloodWait
                    val retryAt = when (failure) {
                        is TelegramClientFailure.FloodWait -> clock.nowMillis() +
                            failure.retryAfterSeconds.coerceAtLeast(0) * 1_000L
                        else -> if (attempts < MAX_REQUEST_ATTEMPTS) {
                            clock.nowMillis() + retryDelayMillis(attempts)
                        } else {
                            null
                        }
                    }
                    if (isFloodWait && retryAt != null) recordScanFloodWaitDeadline(retryAt)
                    val finalFailure = attempts >= MAX_REQUEST_ATTEMPTS
                    store.updateScanFailure(
                        chatId = channel.chatId,
                        state = if (finalFailure) ChannelScanState.ERROR else ChannelScanState.PAUSED,
                        failureCode = failure.code,
                        failureDetail = (failure as? TelegramClientFailure.RequestRejected)?.code,
                        retryAt = if (isFloodWait || !finalFailure) retryAt else null,
                        retryCount = attempts,
                    )
                    if (finalFailure) return null
                    val remaining = (retryAt ?: clock.nowMillis()) - clock.nowMillis()
                    if (remaining > 0) delayStrategy.await(remaining)
                }
            }
        }
        return null
    }

    private suspend fun awaitScanFloodWaitGate() {
        while (currentCoroutineContext().isActive && foreground.get()) {
            val remaining = scanFloodWaitMutex.withLock {
                (scanFloodWaitUntilMillis - clock.nowMillis()).also { wait ->
                    if (wait <= 0L) scanFloodWaitUntilMillis = 0L
                }
            }
            if (remaining <= 0L) return
            delayStrategy.await(remaining)
        }
    }

    private suspend fun recordScanFloodWaitDeadline(deadline: Long) {
        scanFloodWaitMutex.withLock {
            scanFloodWaitUntilMillis = maxOf(scanFloodWaitUntilMillis, deadline)
        }
    }

    private suspend fun clearScanFloodWait() {
        scanFloodWaitMutex.withLock {
            scanFloodWaitUntilMillis = 0L
        }
    }

    private suspend fun processChannelsBounded(
        channels: List<ChannelEntity>,
        block: suspend (ChannelEntity) -> Unit,
    ) {
        if (channels.isEmpty()) return
        val queueMutex = Mutex()
        var nextIndex = 0
        coroutineScope {
            List(minOf(MAX_CONCURRENT_CHANNELS, channels.size)) {
                async {
                    while (currentCoroutineContext().isActive) {
                        val channel = queueMutex.withLock {
                            channels.getOrNull(nextIndex)?.also { nextIndex += 1 }
                        } ?: return@async
                        block(channel)
                    }
                }
            }.awaitAll()
        }
    }

    private fun rotate(channels: List<ChannelEntity>, offset: Int): List<ChannelEntity> {
        if (channels.size < 2) return channels
        val normalized = offset.mod(channels.size)
        return channels.drop(normalized) + channels.take(normalized)
    }

    private fun isPaginationStalled(currentCursor: Long, nextCursor: Long): Boolean =
        nextCursor != 0L && currentCursor != 0L && nextCursor >= currentCursor

    private suspend fun handleEvent(event: TelegramMessageClientEvent) {
        when (event) {
            is TelegramMessageClientEvent.NewMessage -> {
                val channel = store.getChannel(event.message.chatId) ?: return
                if (!channel.isSelectedAvailable()) return
                val committedAt = clock.nowMillis()
                val persisted = event.message.toPersistedVideo(committedAt)
                if (persisted == null) {
                    store.recordIncrementalPosition(
                        event.message.chatId,
                        event.message.messageId,
                        committedAt,
                    )
                } else {
                    store.upsertIncremental(persisted, committedAt)
                }
            }
            is TelegramMessageClientEvent.MessageContentChanged -> {
                val existing = store.getVideo(event.chatId, event.messageId)
                val content = event.video
                if (existing != null && content == null) {
                    store.markUnsupportedEdit(event.chatId, event.messageId, clock.nowMillis())
                } else if (existing != null && content != null) {
                    store.replaceVideoAndTags(content.toPersistedVideo(existing, clock.nowMillis()))
                } else if (content != null) {
                    val channel = store.getChannel(event.chatId) ?: return
                    if (!channel.isSelectedAvailable()) return
                    val refreshed = try {
                        withTimeout(REQUEST_TIMEOUT_MILLIS) {
                            client.getMessage(event.chatId, event.messageId)
                        }
                    } catch (_: TimeoutCancellationException) {
                        null
                    }
                    val message = (refreshed as? TelegramClientResult.Success)?.value ?: return
                    message.toPersistedVideo(clock.nowMillis())?.let { persisted ->
                        store.upsertIncremental(persisted, clock.nowMillis())
                    }
                }
            }
            is TelegramMessageClientEvent.MessageEdited -> store.updateEditTime(
                event.chatId,
                event.messageId,
                event.editTime,
            )
            is TelegramMessageClientEvent.MessagesDeleted -> if (!event.fromCache) {
                store.deleteMessages(event.chatId, event.messageIds, clock.nowMillis())
            }
            TelegramMessageClientEvent.AccountLoggingOut -> {
                refreshSingleFlight.cancelAll()
                clearRefreshFloodWait()
                clearScanFloodWait()
                lifecycleMutex.withLock {
                    foreground.set(false)
                    coordinatorJob?.cancel()
                    coordinatorJob = null
                }
                store.clearAllIndex()
            }
        }
    }

    /**
     * Shares one official getMessage request per VideoKey while retaining caller cancellation.
     * The final waiter owns cancellation, so a timed-out speculative caller cannot cancel a
     * refresh that the visible current item is still awaiting.
     */
    private class VideoRefreshSingleFlight(
        private val scope: CoroutineScope,
    ) {
        private val lock = Any()
        private val flights = mutableMapOf<VideoKey, Flight>()

        suspend fun await(
            key: VideoKey,
            request: suspend () -> VideoReferenceResolution,
        ): VideoReferenceResolution {
            val flight = synchronized(lock) {
                flights[key]?.also { existing ->
                    existing.waiterCount += 1
                } ?: Flight(
                    deferred = scope.async(start = CoroutineStart.LAZY) { request() },
                    waiterCount = 1,
                ).also { created -> flights[key] = created }
            }
            flight.deferred.start()
            return try {
                flight.deferred.await()
            } finally {
                val cancel = synchronized(lock) {
                    flight.waiterCount -= 1
                    if (flight.waiterCount == 0) {
                        flights.remove(key, flight)
                        !flight.deferred.isCompleted
                    } else {
                        false
                    }
                }
                if (cancel) flight.deferred.cancel()
            }
        }

        fun cancelAll() {
            val active = synchronized(lock) {
                flights.values.map(Flight::deferred).also { flights.clear() }
            }
            active.forEach(Deferred<VideoReferenceResolution>::cancel)
        }

        private data class Flight(
            val deferred: Deferred<VideoReferenceResolution>,
            var waiterCount: Int,
        )
    }

    private fun TelegramClientFailure.toReferenceFailure(): VideoReferenceFailure = when (this) {
        TelegramClientFailure.SessionUnavailable -> VideoReferenceFailure.SessionUnavailable
        TelegramClientFailure.NetworkUnavailable -> VideoReferenceFailure.Network
        is TelegramClientFailure.FloodWait -> VideoReferenceFailure.FloodWait(
            retryAfterSeconds.coerceAtLeast(0),
        )
        TelegramClientFailure.AccessLost -> VideoReferenceFailure.AccessLost
        TelegramClientFailure.Timeout -> VideoReferenceFailure.Timeout
        TelegramClientFailure.NotFound -> VideoReferenceFailure.Unknown
        is TelegramClientFailure.RequestRejected -> VideoReferenceFailure.RequestRejected(code)
        TelegramClientFailure.Unknown -> VideoReferenceFailure.Unknown
    }

    private fun TelegramClientMessage.toPersistedVideo(indexedAt: Long): PersistedVideo? =
        video?.toPersistedVideo(
            existing = VideoEntity(
                chatId = chatId,
                messageId = messageId,
                fileId = video.fileId,
                remoteUniqueId = video.remoteUniqueId,
                caption = video.caption,
                durationSeconds = video.durationSeconds,
                width = video.width,
                height = video.height,
                fileSize = video.fileSize,
                supportsStreaming = video.supportsStreaming,
                publishTime = publishTime,
                editTime = editTime,
                canBeSaved = canBeSaved,
                indexedAt = indexedAt,
            ),
            indexedAt = indexedAt,
        )

    private fun TelegramClientVideoContent.toPersistedVideo(
        existing: VideoEntity,
        indexedAt: Long,
    ): PersistedVideo {
        val parsedTags = HashtagParser.parse(
            text = caption,
            hashtagEntityRanges = hashtagEntityRanges.map { range ->
                Utf16TextRange(range.offset, range.length)
            },
        ).tags
        return PersistedVideo(
            video = existing.copy(
                fileId = fileId,
                remoteUniqueId = remoteUniqueId,
                caption = caption,
                durationSeconds = durationSeconds,
                width = width,
                height = height,
                fileSize = fileSize,
                supportsStreaming = supportsStreaming,
                isDeleted = false,
                indexedAt = indexedAt,
            ),
            tags = parsedTags.map { tag ->
                PersistedVideoTag(tag.normalizedName, tag.displayName)
            },
        )
    }

    private fun VideoEntity.toModel(tags: List<VideoTag>): IndexedVideo = IndexedVideo(
        key = VideoKey(chatId, messageId),
        fileId = fileId,
        remoteUniqueId = remoteUniqueId,
        caption = caption,
        supportsStreaming = supportsStreaming,
        fileSize = fileSize,
        durationSeconds = durationSeconds,
        width = width,
        height = height,
        publishTime = publishTime,
        editTime = editTime,
        canBeSaved = canBeSaved,
        tags = tags,
    )

    private fun mapProgress(record: ChannelScanRecord): ChannelVideoScanProgress {
        val channel = record.channel
        return ChannelVideoScanProgress(
            chatId = channel.chatId,
            channelTitle = channel.title,
            status = when (channel.scanState) {
                ChannelScanState.NOT_STARTED -> VideoScanStatus.NOT_STARTED
                ChannelScanState.SCANNING -> VideoScanStatus.SCANNING
                ChannelScanState.PAUSED -> VideoScanStatus.PAUSED
                ChannelScanState.COMPLETED -> VideoScanStatus.COMPLETED
                ChannelScanState.ERROR -> VideoScanStatus.ERROR
            },
            processedVideoCandidateCount = channel.videoCandidateCount,
            videoSearchPageCount = channel.videoSearchPageCount,
            indexedVideoCount = record.indexedVideoCount,
            approximateVideoCount = channel.approximateVideoCount,
            duplicateVideoEncounterCount = channel.duplicateVideoEncounterCount,
            exceptionCount = channel.scanExceptionCount,
            nextVideoSearchCursor = channel.videoSearchCursor,
            latestSyncedMessageId = channel.lastNewMessageId,
            isPausedByUser = channel.scanPausedByUser,
            failure = channel.toFailure(),
        )
    }

    private fun ChannelEntity.toFailure(): TelegramMessageFailure? = when (scanFailureCode) {
        null -> null
        FAILURE_NETWORK -> TelegramMessageFailure.NetworkUnavailable
        FAILURE_FLOOD_WAIT -> scanRetryAt?.let(TelegramMessageFailure::FloodWait)
            ?: TelegramMessageFailure.Unknown
        FAILURE_ACCESS_LOST -> TelegramMessageFailure.AccessLost
        FAILURE_TIMEOUT -> TelegramMessageFailure.Timeout
        FAILURE_REQUEST_REJECTED -> TelegramMessageFailure.RequestRejected(scanFailureDetail ?: 0)
        FAILURE_DATABASE -> TelegramMessageFailure.Database
        FAILURE_PAGINATION_STALLED -> TelegramMessageFailure.PaginationStalled
        else -> TelegramMessageFailure.Unknown
    }

    private val TelegramClientFailure.code: String
        get() = when (this) {
            TelegramClientFailure.SessionUnavailable,
            TelegramClientFailure.Unknown,
            -> FAILURE_UNKNOWN
            TelegramClientFailure.NetworkUnavailable -> FAILURE_NETWORK
            is TelegramClientFailure.FloodWait -> FAILURE_FLOOD_WAIT
            TelegramClientFailure.AccessLost,
            TelegramClientFailure.NotFound,
            -> FAILURE_ACCESS_LOST
            TelegramClientFailure.Timeout -> FAILURE_TIMEOUT
            is TelegramClientFailure.RequestRejected -> FAILURE_REQUEST_REJECTED
        }

    private fun TelegramClientResult<*>.toOriginalMessageLinkResult(): OriginalMessageLinkResult =
        when ((this as? TelegramClientResult.Failure)?.failure) {
            TelegramClientFailure.NetworkUnavailable -> OriginalMessageLinkResult.NetworkUnavailable
            TelegramClientFailure.AccessLost,
            TelegramClientFailure.NotFound,
            -> OriginalMessageLinkResult.Unavailable
            else -> OriginalMessageLinkResult.Unknown
        }

    private fun ChannelEntity.isSelectedAvailable(): Boolean =
        isSelected && accessState == ChannelAccessState.AVAILABLE

    private fun ChannelEntity.isScannable(): Boolean =
        isSelectedAvailable() && !scanPausedByUser && scanState != ChannelScanState.ERROR

    private fun retryDelayMillis(attempt: Int): Long =
        (BASE_RETRY_DELAY_MILLIS shl (attempt - 1).coerceAtLeast(0))
            .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
            .let { base -> base + jitter.nextMillis(base / 4) }

    private fun retentionCutoff(nowMillis: Long): Long =
        (nowMillis - DELETED_VIDEO_RETENTION_MILLIS).coerceAtLeast(0L)

    private data class RecentSyncSession(
        val boundaryMessageId: Long?,
        var fromMessageId: Long = 0,
        var completed: Boolean = false,
    )

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_CONCURRENT_CHANNELS = 2
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
        const val MAX_REQUEST_ATTEMPTS = 3
        const val HYDRATION_QUERY_WINDOW_SIZE = 64
        const val DELETED_VIDEO_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        const val FEED_PERF_TAG = "CVF-FeedPerf"
        const val BASE_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 8_000L
        const val FAILURE_NETWORK = "NETWORK"
        const val FAILURE_FLOOD_WAIT = "FLOOD_WAIT"
        const val FAILURE_ACCESS_LOST = "ACCESS_LOST"
        const val FAILURE_TIMEOUT = "TIMEOUT"
        const val FAILURE_REQUEST_REJECTED = "REQUEST_REJECTED"
        const val FAILURE_DATABASE = "DATABASE"
        const val FAILURE_PAGINATION_STALLED = "PAGINATION_STALLED"
        const val FAILURE_UNKNOWN = "UNKNOWN"
    }
}
