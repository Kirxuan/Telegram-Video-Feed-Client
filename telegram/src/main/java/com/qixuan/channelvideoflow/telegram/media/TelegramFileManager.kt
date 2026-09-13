package com.qixuan.channelvideoflow.telegram.media

import android.util.Log
import com.qixuan.channelvideoflow.database.MediaCacheEntryDao
import com.qixuan.channelvideoflow.domain.media.TelegramFileGateway
import com.qixuan.channelvideoflow.domain.media.TelegramFileDeleteResult
import com.qixuan.channelvideoflow.domain.media.TelegramFileOwnerKind
import com.qixuan.channelvideoflow.domain.media.TelegramFileProtectionLease
import com.qixuan.channelvideoflow.domain.media.TelegramFileRangeLease
import com.qixuan.channelvideoflow.domain.media.TelegramFileRequestPriority
import com.qixuan.channelvideoflow.domain.media.TelegramFileSnapshot
import com.qixuan.channelvideoflow.domain.media.TelegramFileUnavailableException
import com.qixuan.channelvideoflow.domain.media.TelegramInternalResourceHandle
import com.qixuan.channelvideoflow.domain.media.TelegramInternalResourceKind
import com.qixuan.channelvideoflow.domain.media.TelegramInternalResourceResolution
import com.qixuan.channelvideoflow.domain.media.TelegramNetworkRequestSnapshot
import com.qixuan.channelvideoflow.domain.media.NoOpStreamingNetworkMetricsRepository
import com.qixuan.channelvideoflow.domain.media.StreamingNetworkMetricsRepository
import com.qixuan.channelvideoflow.domain.media.TdLibNetworkTransferSample
import com.qixuan.channelvideoflow.telegram.client.TelegramClientFileSnapshot
import com.qixuan.channelvideoflow.telegram.client.TelegramClientResult
import com.qixuan.channelvideoflow.telegram.client.TelegramFileClient
import com.qixuan.channelvideoflow.telegram.BuildConfig
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates TDLib's single per-file download cursor between DataSource owners.
 */
internal class TelegramFileManager(
    private val client: TelegramFileClient,
    private val scope: CoroutineScope,
    private val cacheEntryDao: MediaCacheEntryDao? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val reuseContainedActiveRequest: Boolean = PRODUCTION_REUSE_CONTAINED_ACTIVE_REQUEST,
    private val networkMetrics: StreamingNetworkMetricsRepository =
        NoOpStreamingNetworkMetricsRepository,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val prefixQueryTimeoutMillis: Long = PREFIX_QUERY_TIMEOUT_MILLIS,
    private val privateFileReadable: suspend (String?) -> Boolean = { localPath ->
        withContext(Dispatchers.IO) {
            localPath?.let(::File)?.let { file -> file.isFile && file.canRead() } == true
        }
    },
) : TelegramFileGateway {
    private val lock = Any()
    private val entries = mutableMapOf<Int, FileEntry>()
    private val internalResources = mutableMapOf<String, InternalResourceEntry>()
    private var accountGeneration = 0L
    private var nextOwnerSequence = 0L
    private val cacheMetadataWriter = cacheEntryDao?.let { dao ->
        MediaCacheMetadataWriter(dao, scope)
    }

    init {
        scope.launch {
            client.fileEvents.collect { event ->
                when (event) {
                    is com.qixuan.channelvideoflow.telegram.client.TelegramFileClientEvent.FileUpdated ->
                        onFileUpdated(event.file)
                    com.qixuan.channelvideoflow.telegram.client.TelegramFileClientEvent.Ready -> {
                        synchronized(lock) {
                            accountGeneration += 1L
                            internalResources.clear()
                        }
                        networkMetrics.resetSession()
                    }
                    com.qixuan.channelvideoflow.telegram.client.TelegramFileClientEvent.AccountLoggingOut ->
                        clearForLogout()
                }
            }
        }
    }

    override fun acquireRange(
        fileId: Int,
        offset: Long,
        length: Long,
        priority: TelegramFileRequestPriority,
        ownerToken: String,
        ownerKind: TelegramFileOwnerKind,
        readAheadBytes: Long,
    ): TelegramFileRangeLease {
        require(offset >= 0L) { "offset must be non-negative" }
        require(length > 0L) { "length must be positive" }
        require(readAheadBytes >= length) { "readAheadBytes must cover the requested range" }
        require(readAheadBytes <= MAX_FOREGROUND_REQUEST_BYTES) {
            "readAheadBytes exceeds the foreground request budget"
        }
        require(offset <= Long.MAX_VALUE - length) { "range end must not overflow" }
        val entry = synchronized(lock) {
            entries.getOrPut(fileId, ::FileEntry).also { current ->
                synchronized(current.monitor) {
                    current.failure = null
                    current.owners[ownerToken] = OwnerRange(
                        offset = offset,
                        length = length,
                        priority = priority,
                        ownerKind = ownerKind,
                        readAheadBytes = readAheadBytes,
                        sequence = ++nextOwnerSequence,
                    )
                }
                touchLocked(fileId, current)
                current.cancelGeneration += 1L
                if (priority.suspendsPreload) suspendPreloadsLocked(exceptFileId = fileId)
                if (!current.deletionReserved) {
                    val owner = current.owners.getValue(ownerToken)
                    if (shouldProbePrefix(current, owner)) {
                        startPrefixProbeLocked(fileId, current, owner)
                    } else {
                        ensureRequestLocked(fileId, current)
                    }
                }
                if (availableSnapshot(current, current.owners.getValue(ownerToken)) == null) {
                    trace(
                        "lease acquire fileId=$fileId owner=$ownerKind priority=$priority " +
                            "offset=$offset length=$length readAhead=$readAheadBytes result=WAIT",
                    )
                }
            }
        }
        return RangeLeaseImpl(
            manager = this,
            entry = entry,
            fileId = fileId,
            offset = offset,
            length = length,
            ownerToken = ownerToken,
        )
    }

    override fun pinFile(
        fileId: Int,
        ownerToken: String,
        ownerKind: TelegramFileOwnerKind,
    ): TelegramFileProtectionLease {
        synchronized(lock) {
            val entry = entries.getOrPut(fileId, ::FileEntry)
            entry.protections[ownerToken] = ownerKind
            touchLocked(fileId, entry)
            entry.cancelGeneration += 1L
        }
        return ProtectionLeaseImpl(this, fileId, ownerToken, ownerKind)
    }

    override fun observeFile(fileId: Int): Flow<TelegramFileSnapshot> =
        synchronized(lock) {
            entries.getOrPut(fileId, ::FileEntry).state.filterNotNull()
        }

    override fun currentSnapshot(fileId: Int): TelegramFileSnapshot? =
        synchronized(lock) { entries[fileId]?.snapshot }

    override fun invalidateLocalSnapshot(fileId: Int, expectedLocalPath: String) {
        synchronized(lock) {
            val entry = entries[fileId] ?: return
            val invalidated = synchronized(entry.monitor) {
                val snapshot = entry.snapshot
                if (snapshot?.localPath != expectedLocalPath) {
                    false
                } else {
                    entry.snapshot = null
                    entry.state.value = null
                    entry.owners.values.forEach { owner -> owner.verifiedSnapshot = null }
                    cancelPrefixQueriesLocked(entry)
                    entry.monitor.notifyAll()
                    true
                }
            }
            if (!invalidated) return
            persistEntryLocked(fileId, entry, cachedBytes = 0L)
            trace("local snapshot invalidated fileId=$fileId result=MISSING_PRIVATE_FILE")
            ensureRequestLocked(fileId, entry)
        }
    }

    override fun protectedFileIds(): Set<Int> = synchronized(lock) {
        removeExpiredInternalResourcesLocked()
        entries
            .filterValues { entry -> entry.owners.isNotEmpty() || entry.protections.isNotEmpty() }
            .keys
            .plus(internalResources.values.map(InternalResourceEntry::fileId))
            .toSet()
    }

    override suspend fun deleteCachedFile(fileId: Int): TelegramFileDeleteResult {
        val entry = synchronized(lock) {
            removeExpiredInternalResourcesLocked()
            val candidate = entries.getOrPut(fileId, ::FileEntry)
            if (
                candidate.deletionReserved ||
                candidate.owners.isNotEmpty() ||
                candidate.protections.isNotEmpty() ||
                internalResources.values.any { resource -> resource.fileId == fileId }
            ) {
                return TelegramFileDeleteResult.PROTECTED
            }
            candidate.deletionReserved = true
            candidate
        }
        val result = when (client.deleteFile(fileId)) {
            is TelegramClientResult.Success -> {
                synchronized(lock) {
                    entry.deletionReserved = false
                    entry.snapshot = null
                    entry.state.value = null
                    cancelPrefixQueriesLocked(entry)
                    if (entry.owners.isEmpty() && entry.protections.isEmpty()) {
                        entries.remove(fileId)
                    } else {
                        ensureRequestLocked(fileId, entry)
                    }
                }
                cacheMetadataWriter?.delete(fileId)
                TelegramFileDeleteResult.DELETED
            }
            is TelegramClientResult.Failure -> {
                synchronized(lock) {
                    entry.deletionReserved = false
                    ensureRequestLocked(fileId, entry)
                }
                TelegramFileDeleteResult.FAILED
            }
        }
        return result
    }

    override fun release(ownerToken: String) {
        synchronized(lock) {
            revokeInternalResourcesLocked(ownerToken)
            var releasedForegroundBlocker = false
            entries.forEach { (fileId, entry) ->
                val released = releaseFromEntryLocked(fileId, entry, ownerToken)
                releasedForegroundBlocker =
                    releasedForegroundBlocker || released.foregroundBlocker
            }
            if (releasedForegroundBlocker && !hasForegroundBlockerLocked()) {
                entries.forEach { (fileId, entry) -> ensureRequestLocked(fileId, entry) }
            }
        }
    }

    override fun currentAccountGeneration(): Long = synchronized(lock) { accountGeneration }

    override fun currentNetworkRequest(): TelegramNetworkRequestSnapshot? = synchronized(lock) {
        entries.entries
            .mapNotNull { (fileId, entry) ->
                entry.activeRequest
                    ?.takeIf { request -> request.started.get() && !request.cancelled.get() }
                    ?.let { request ->
                        val downloaded = entry.snapshot
                            ?.contiguousBytesFrom(request.offset, request.length)
                            ?.coerceIn(0L, request.length)
                            ?: 0L
                        TelegramNetworkRequestSnapshot(
                            fileId = fileId,
                            downloadedBytes = downloaded,
                            remainingBytes = (request.length - downloaded).coerceAtLeast(0L),
                            priority = request.priority,
                            ownerKind = request.ownerKind,
                        )
                    }
            }
            .maxByOrNull { request -> request.priority.tdLibPriority }
    }

    override fun registerInternalResource(
        fileId: Int,
        ownerToken: String,
        kind: TelegramInternalResourceKind,
        expectedSize: Long?,
        referencedResources: Map<Int, TelegramInternalResourceHandle>,
        timeToLiveMillis: Long,
    ): TelegramInternalResourceHandle {
        require(fileId > 0) { "fileId must be positive" }
        require(ownerToken.isNotBlank()) { "ownerToken must not be blank" }
        require(expectedSize == null || expectedSize > 0L) { "expectedSize must be positive" }
        require(timeToLiveMillis in 1L..MAX_INTERNAL_RESOURCE_TTL_MILLIS) {
            "internal resource TTL is outside the safe range"
        }
        return synchronized(lock) {
            removeExpiredInternalResourcesLocked()
            val generation = accountGeneration
            referencedResources.forEach { (referencedFileId, handle) ->
                require(referencedFileId > 0) { "referenced fileId must be positive" }
                val referenced = internalResources[handle.opaqueToken]
                require(
                    referenced != null &&
                        referenced.ownerToken == ownerToken &&
                        referenced.generation == generation &&
                        referenced.fileId == referencedFileId &&
                        referenced.kind == TelegramInternalResourceKind.HLS_MEDIA,
                ) { "referenced resource is stale or belongs to another owner" }
            }
            val opaqueToken = UUID.randomUUID().toString().replace("-", "")
            TelegramInternalResourceHandle(
                accountGeneration = generation,
                opaqueToken = opaqueToken,
                kind = kind,
            ).also { handle ->
                internalResources[opaqueToken] = InternalResourceEntry(
                    fileId = fileId,
                    ownerToken = ownerToken,
                    generation = generation,
                    kind = kind,
                    expectedSize = expectedSize,
                    referencedResources = referencedResources.toMap(),
                    expiresAtMillis = nowMillis().saturatedAdd(timeToLiveMillis),
                )
            }
        }
    }

    override fun resolveInternalResource(
        accountGeneration: Long,
        opaqueToken: String,
    ): TelegramInternalResourceResolution? = synchronized(lock) {
        removeExpiredInternalResourcesLocked()
        if (accountGeneration != this.accountGeneration) return@synchronized null
        val resource = internalResources[opaqueToken] ?: return@synchronized null
        if (resource.generation != accountGeneration) return@synchronized null
        TelegramInternalResourceResolution(
            fileId = resource.fileId,
            kind = resource.kind,
            expectedSize = resource.expectedSize,
            referencedResources = resource.referencedResources,
        )
    }

    override fun revokeInternalResources(ownerToken: String) {
        synchronized(lock) { revokeInternalResourcesLocked(ownerToken) }
    }

    private fun revokeInternalResourcesLocked(ownerToken: String) {
        internalResources.entries.removeAll { (_, resource) ->
            resource.ownerToken == ownerToken
        }
    }

    private fun removeExpiredInternalResourcesLocked() {
        val currentTime = nowMillis()
        internalResources.entries.removeAll { (_, resource) ->
            resource.expiresAtMillis <= currentTime || resource.generation != accountGeneration
        }
    }

    private fun release(fileId: Int, ownerToken: String) {
        synchronized(lock) {
            val entry = entries[fileId] ?: return
            val released = releaseFromEntryLocked(fileId, entry, ownerToken)
            if (released.foregroundBlocker && !hasForegroundBlockerLocked()) {
                entries.forEach { (candidateFileId, candidate) ->
                    ensureRequestLocked(candidateFileId, candidate)
                }
            }
        }
    }

    private fun releaseFromEntryLocked(
        fileId: Int,
        entry: FileEntry,
        ownerToken: String,
    ): ReleasedOwner {
        val removedOwner = synchronized(entry.monitor) {
            entry.owners.remove(ownerToken).also { removed ->
                if (removed != null) entry.monitor.notifyAll()
            }
        }
        val protectionRemoved = entry.protections.remove(ownerToken) != null
        if (removedOwner == null && !protectionRemoved) return ReleasedOwner.NONE

        if (removedOwner != null) {
            cancelUnusedPrefixQueryLocked(entry, removedOwner.offset)
            val noRangeOwners = synchronized(entry.monitor) { entry.owners.isEmpty() }
            if (noRangeOwners) {
                entry.activeRequest?.cancelled?.set(true)
                entry.activeRequest = null
                ++entry.cancelGeneration
                scheduleCancelLocked(fileId, entry, "UNUSED")
            } else {
                ensureRequestLocked(fileId, entry)
            }
        }
        return ReleasedOwner(
            foregroundBlocker = removedOwner?.priority?.suspendsPreload == true,
        )
    }

    private fun updatePriority(
        fileId: Int,
        ownerToken: String,
        priority: TelegramFileRequestPriority,
    ) {
        synchronized(lock) {
            val entry = entries[fileId] ?: return
            val owner = synchronized(entry.monitor) { entry.owners[ownerToken] } ?: return
            if (owner.priority == priority) return
            val previousPriority = owner.priority
            owner.priority = priority
            trace(
                "lease update fileId=$fileId owner=${owner.ownerKind} " +
                    "priority=$previousPriority->$priority result=UPDATED",
            )
            if (priority.suspendsPreload) suspendPreloadsLocked(exceptFileId = fileId)
            ensureRequestLocked(fileId, entry)
            if (!hasForegroundBlockerLocked()) {
                entries.forEach { (candidateFileId, candidate) ->
                    ensureRequestLocked(candidateFileId, candidate)
                }
            }
        }
    }

    private fun suspendPreloadsLocked(exceptFileId: Int) {
        entries.forEach { (fileId, entry) ->
            if (fileId == exceptFileId) return@forEach
            val active = entry.activeRequest ?: return@forEach
            if (active.priority != TelegramFileRequestPriority.NEXT_PRELOAD) return@forEach
            active.cancelled.set(true)
            entry.activeRequest = null
            entry.cancelGeneration += 1L
            scheduleCancelLocked(fileId, entry, "PREEMPTED_BY_CURRENT")
        }
    }

    private fun scheduleCancelLocked(
        fileId: Int,
        entry: FileEntry,
        result: String,
    ) {
        if (entry.cancelJob?.isActive == true) return
        lateinit var cancelJob: Job
        cancelJob = scope.launch(start = CoroutineStart.LAZY) {
            trace("cancel fileId=$fileId result=$result")
            client.cancelDownloadFile(fileId)
            synchronized(lock) {
                if (entry.cancelJob === cancelJob) {
                    entry.cancelJob = null
                    if (entries[fileId] === entry) ensureRequestLocked(fileId, entry)
                }
            }
        }
        entry.cancelJob = cancelJob
        cancelJob.start()
    }

    private fun hasForegroundBlockerLocked(): Boolean = entries.values.any { entry ->
        synchronized(entry.monitor) {
            entry.owners.values.any { owner -> owner.priority.suspendsPreload }
        }
    }

    private fun await(
        entry: FileEntry,
        fileId: Int,
        offset: Long,
        length: Long,
        ownerToken: String,
        timeoutMillis: Long,
    ): TelegramFileSnapshot {
        val startedAtMillis = monotonicMillis()
        val waitBudget = ProgressAwareRangeWaitBudget(
            stallTimeoutMillis = timeoutMillis,
            startedAtMillis = startedAtMillis,
            hardTimeoutMultiplier = MAX_PROGRESS_WAIT_WINDOWS,
        )
        synchronized(entry.monitor) {
            while (true) {
                if (entry.owners[ownerToken] == null) {
                    throw TelegramFileUnavailableException("文件请求已取消")
                }
                entry.failure?.let { throw TelegramFileUnavailableException(it) }
                val owner = entry.owners.getValue(ownerToken)
                val snapshot = availableSnapshot(entry, owner) ?: entry.snapshot
                val nowMillis = monotonicMillis()
                val progressBytes = snapshot?.contiguousBytesFrom(offset, length) ?: 0L
                waitBudget.observeProgress(progressBytes, nowMillis)
                if (snapshot != null && snapshot.covers(offset, length)) {
                    val waitedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
                    val kibPerSecond = if (waitedMillis > 0L) {
                        (progressBytes / 1024L) * 1_000L / waitedMillis
                    } else {
                        0L
                    }
                    if (waitedMillis > 0L) {
                        trace(
                            "range ready fileId=$fileId offset=$offset length=$length " +
                                "waitMs=$waitedMillis firstByteMs=" +
                                "${waitBudget.firstProgressWaitMillis()} " +
                                "progressBytes=$progressBytes " +
                                "effectiveKiBps=$kibPerSecond",
                        )
                    }
                    return snapshot
                }
                val remainingMillis = waitBudget.remainingWaitMillis(nowMillis)
                if (remainingMillis <= 0L) {
                    val waitedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
                    trace(
                        "range timeout fileId=$fileId offset=$offset length=$length " +
                            "waitMs=$waitedMillis firstByteMs=" +
                            "${waitBudget.firstProgressWaitMillis()} " +
                            "progressBytes=$progressBytes " +
                            "reason=${waitBudget.timeoutReason(nowMillis)}",
                    )
                    throw com.qixuan.channelvideoflow.domain.media.TelegramFileTimeoutException(
                        "等待 fileId=$fileId 的区间超时",
                    )
                }
                entry.monitor.wait(remainingMillis)
            }
        }
    }

    private fun onFileUpdated(file: TelegramClientFileSnapshot) {
        synchronized(lock) {
            val entry = entries[file.fileId] ?: return
            val snapshot = file.toDomain()
            recordNetworkProgressLocked(entry, snapshot)
            val wakesWaiters = shouldWakeWaiters(entry, snapshot)
            publishSnapshot(entry, snapshot, wakesWaiters)
            persistEntryLocked(file.fileId, entry, snapshot.downloadedSize)
            trace(
                "file update fileId=${snapshot.fileId} size=${snapshot.size} " +
                    "offset=${snapshot.downloadOffset} " +
                    "prefix=${snapshot.downloadedPrefixSize} downloaded=${snapshot.downloadedSize} " +
                    "downloadable=${snapshot.canBeDownloaded} active=${snapshot.isDownloadingActive} " +
                    "complete=${snapshot.isDownloadingCompleted} result=" +
                    if (wakesWaiters) "PROGRESS" else "STALE_NO_PROGRESS",
            )
            if (entry.activeRequest?.let { it.accepted && it.covers(snapshot) } == true) {
                entry.activeRequest = null
            }
            ensureRequestLocked(file.fileId, entry)
        }
    }

    private fun clearForLogout() {
        cacheMetadataWriter?.resetAndClear()
        synchronized(lock) {
            accountGeneration += 1L
            internalResources.clear()
            entries.values.forEach { entry ->
                synchronized(entry.monitor) {
                    entry.failure = "账号已退出"
                }
                entry.activeRequest?.cancelled?.set(true)
                entry.activeRequest = null
                cancelPrefixQueriesLocked(entry)
                entry.protections.clear()
                synchronized(entry.monitor) { entry.monitor.notifyAll() }
            }
            entries.clear()
            networkMetrics.resetSession()
        }
    }

    private fun shouldProbePrefix(entry: FileEntry, owner: OwnerRange): Boolean {
        if (availableSnapshot(entry, owner) != null) return false
        if (owner.prefixProbeState == PrefixProbeState.PENDING) return false
        val snapshot = entry.snapshot ?: return false
        return snapshot.downloadedSize > 0L
    }

    private fun startPrefixProbeLocked(
        fileId: Int,
        entry: FileEntry,
        owner: OwnerRange,
    ) {
        owner.prefixProbeState = PrefixProbeState.PENDING
        if (entry.prefixQueries.containsKey(owner.offset)) return
        val query = PrefixQuery(owner.offset)
        entry.prefixQueries[owner.offset] = query
        query.job = scope.launch(start = CoroutineStart.LAZY) {
            val result = withTimeoutOrNull(prefixQueryTimeoutMillis.coerceAtLeast(1L)) {
                queryDownloadedPrefix(fileId, entry, query)
            }
            synchronized(lock) {
                if (entries[fileId] !== entry || entry.prefixQueries[query.offset] !== query) {
                    return@synchronized
                }
                entry.prefixQueries.remove(query.offset)
                synchronized(entry.monitor) {
                    entry.owners.values
                        .filter { candidate ->
                            candidate.offset == query.offset &&
                                candidate.prefixProbeState == PrefixProbeState.PENDING
                        }
                        .forEach { candidate ->
                            candidate.verifiedSnapshot = result
                                ?.takeIf { probe -> probe.prefixSize >= candidate.length }
                                ?.snapshot
                                ?.copy(
                                    downloadOffset = candidate.offset,
                                    downloadedPrefixSize = result.prefixSize,
                                )
                            candidate.prefixProbeState = if (candidate.verifiedSnapshot != null) {
                                PrefixProbeState.HIT
                            } else {
                                PrefixProbeState.MISS
                            }
                        }
                    entry.monitor.notifyAll()
                }
                trace(
                    "prefix probe fileId=$fileId offset=${query.offset} " +
                        "prefix=${result?.prefixSize ?: 0L} result=" +
                        if (result == null) "MISS_OR_TIMEOUT" else "COMPLETE",
                )
                ensureRequestLocked(fileId, entry)
            }
        }
        query.job.start()
    }

    private suspend fun queryDownloadedPrefix(
        fileId: Int,
        entry: FileEntry,
        query: PrefixQuery,
    ): PrefixProbeResult? {
        var snapshot = synchronized(lock) {
            if (entries[fileId] !== entry || entry.prefixQueries[query.offset] !== query) null
            else entry.snapshot
        } ?: return null
        var prefixSize = when (
            val prefix = client.getFileDownloadedPrefixSize(fileId, query.offset)
        ) {
            is TelegramClientResult.Success -> prefix.value
            is TelegramClientResult.Failure -> return null
        }
        if (prefixSize <= 0L) return null
        if (!privateFileReadable(snapshot.localPath)) {
            snapshot = when (val refreshed = client.getFile(fileId)) {
                is TelegramClientResult.Success -> refreshed.value
                    .takeIf { file -> file.fileId == fileId }
                    ?.toDomain()
                    ?: return null
                is TelegramClientResult.Failure -> return null
            }
            if (!privateFileReadable(snapshot.localPath)) return null
            prefixSize = when (
                val refreshedPrefix = client.getFileDownloadedPrefixSize(fileId, query.offset)
            ) {
                is TelegramClientResult.Success -> refreshedPrefix.value
                is TelegramClientResult.Failure -> return null
            }
        }
        return PrefixProbeResult(snapshot, prefixSize.coerceAtLeast(0L))
    }

    private fun availableSnapshot(
        entry: FileEntry,
        owner: OwnerRange,
    ): TelegramFileSnapshot? = entry.snapshot
        ?.takeIf { snapshot -> snapshot.covers(owner.offset, owner.length) }
        ?: owner.verifiedSnapshot
            ?.takeIf { snapshot -> snapshot.covers(owner.offset, owner.length) }

    private fun cancelUnusedPrefixQueryLocked(entry: FileEntry, offset: Long) {
        val stillNeeded = synchronized(entry.monitor) {
            entry.owners.values.any { owner ->
                owner.offset == offset && owner.prefixProbeState == PrefixProbeState.PENDING
            }
        }
        if (stillNeeded) return
        entry.prefixQueries.remove(offset)?.job?.cancel()
    }

    private fun cancelPrefixQueriesLocked(entry: FileEntry) {
        entry.prefixQueries.values.forEach { query -> query.job.cancel() }
        entry.prefixQueries.clear()
        entry.owners.values.forEach { owner ->
            if (owner.prefixProbeState == PrefixProbeState.PENDING) {
                owner.prefixProbeState = PrefixProbeState.MISS
            }
        }
    }

    private fun recordNetworkProgressLocked(
        entry: FileEntry,
        snapshot: TelegramFileSnapshot,
    ) {
        val request = entry.activeRequest ?: return
        if (!request.started.get() || request.cancelled.get()) return
        val now = monotonicNanos()
        val contextRevision = networkMetrics.contextRevision
        val progress = snapshot.contiguousBytesFrom(request.offset, request.length)
        if (
            request.metricsContextRevision != contextRevision ||
            request.lastProgressNanos == 0L ||
            progress < request.lastProgressBytes
        ) {
            request.metricsContextRevision = contextRevision
            request.lastProgressNanos = now
            request.lastProgressBytes = progress
            return
        }
        val delta = progress - request.lastProgressBytes
        val duration = now - request.lastProgressNanos
        request.lastProgressNanos = now
        request.lastProgressBytes = progress
        if (delta <= 0L || duration <= 0L) return
        val firstProgress = !request.hasRecordedNetworkProgress
        request.hasRecordedNetworkProgress = true
        networkMetrics.recordTdLibTransfer(
            TdLibNetworkTransferSample(
                bytes = delta,
                durationNanos = duration,
                contextRevision = contextRevision,
                timeToFirstByteNanos = if (firstProgress) {
                    (now - request.startedAtNanos).takeIf { it > 0L }
                } else {
                    null
                },
                isCached = false,
                isActiveNetworkDownload = true,
            ),
        )
    }

    private fun ensureRequestLocked(fileId: Int, entry: FileEntry) {
        if (entry.owners.isEmpty()) return
        val unsatisfied = entry.owners.values.filterNot { owner ->
            availableSnapshot(entry, owner) != null || owner.prefixProbeState == PrefixProbeState.PENDING
        }
        if (unsatisfied.isEmpty()) return
        // Playback owners steer the download window. A residual preload owner for the same file
        // (e.g. the unfinished tail chunk of a "next" preparation that was promoted into
        // playback) must not pull the active request back to its older offset: that produced
        // cancel/SWITCH loops where the download window oscillated between the last preload
        // chunk and the playback read-ahead, and every oscillation could cancel in-flight
        // parts. Already-downloaded bytes stay in TDLib; unread parts are covered by the
        // playback request's own read-ahead as consumption advances.
        //
        // The ownership test looks at *all* owners, not just the unsatisfied ones: during
        // playback the consumption leases are satisfied most of the time (the player reads
        // data that is already local), and in those windows the leftover preload owner would
        // otherwise become the only unsatisfied owner and steer the window backwards again —
        // the exact oscillation this guard exists to remove. Files that are not playing are
        // unaffected: preload owners still steer their own downloads.
        val steering = selectSteeringOwners(entry, unsatisfied)
        if (steering.isEmpty()) return
        if (
            steering.all { owner ->
                owner.priority == TelegramFileRequestPriority.NEXT_PRELOAD
            } &&
            hasForegroundBlockerLocked()
        ) {
            return
        }
        val plan = planRequest(steering, entry.snapshot)
        var action = RequestAction.START
        var cancelFirst = false
        entry.activeRequest?.let { active ->
            if (
                active.offset <= plan.requiredStart &&
                active.end >= plan.requiredEnd &&
                active.priority == plan.priority
            ) {
                trace(
                    "request reuse fileId=$fileId owner=${plan.ownerKind} " +
                        "priority=${plan.priority} offset=${plan.requiredStart} " +
                        "limit=${plan.requiredEnd - plan.requiredStart} result=CONTAINED",
                )
                return
            }
            if (
                active.offset <= plan.requiredStart &&
                active.end >= plan.requiredEnd
            ) {
            if (reuseContainedActiveRequest) {
                val previousPriority = active.priority
                val previousKind = active.ownerKind
                // plan already reflects the highest-priority unsatisfied owner. Use it exactly
                // so first-frame STARTUP -> CONTINUATION still yields other-file preload.
                active.priority = plan.priority
                active.ownerKind = plan.ownerKind
                val changed = previousPriority != active.priority ||
                    previousKind != active.ownerKind
                if (!active.started.get()) {
                    if (changed) {
                        trace(
                            "request reprioritize fileId=$fileId owner=${active.ownerKind} " +
                                "priority=$previousPriority->${active.priority} " +
                                "offset=${active.offset} limit=${active.length} result=QUEUED",
                        )
                    }
                    return
                }
                // Only publish to TDLib when something actually changed. A sequential read
                // advances the lease every chunk while the priority and owner stay identical;
                // re-publishing the same request made every chunk boundary a fresh
                // downloadFile call whose streaming-offset update could disturb in-flight
                // parts, and device evidence showed hundreds of such calls per hundred
                // seconds. The active request still covers the new range, so silence is safe.
                if (!changed) return
                trace(
                    "request reprioritize fileId=$fileId owner=${active.ownerKind} " +
                        "priority=$previousPriority->${active.priority} " +
                        "offset=${active.offset} limit=${active.length} result=REUSED_ACTIVE",
                )
                updateActiveRequestPriority(fileId, entry, active)
                return
            }
            action = RequestAction.REPRIORITIZE
            active.cancelled.set(true)
            } else
            if (!active.started.get()) {
                active.offset = minOf(active.offset, plan.offset)
                active.end = maxOf(active.end, plan.end)
                    .coerceAtMost(active.offset.saturatedAdd(MAX_FOREGROUND_REQUEST_BYTES))
                active.priority = maxPriority(active.priority, plan.priority)
                active.ownerKind = higherOwnerKind(active.ownerKind, plan.ownerKind)
                trace(
                    "request merge fileId=$fileId owner=${active.ownerKind} " +
                        "priority=${active.priority} offset=${active.offset} " +
                        "limit=${active.length} result=QUEUED",
                )
                return
            }
            // Ranges are half-open. Merely touching at one endpoint must not merge budgets.
            val overlaps = active.offset < plan.end && plan.offset < active.end
            if (action == RequestAction.REPRIORITIZE) {
                Unit
            } else if (
                overlaps &&
                maxOf(active.end, plan.end) - minOf(active.offset, plan.offset) <=
                MAX_FOREGROUND_REQUEST_BYTES
            ) {
                action = RequestAction.MERGE
            } else if (
                plan.priority.tdLibPriority > active.priority.tdLibPriority ||
                !active.coversAny(unsatisfied)
            ) {
                action = RequestAction.SWITCH
                cancelFirst = !overlaps
            } else {
                trace(
                    "request defer fileId=$fileId owner=${plan.ownerKind} " +
                        "priority=${plan.priority} offset=${plan.offset} " +
                        "limit=${plan.length} result=LOWER_PRIORITY",
                )
                return
            }
            active.cancelled.set(true)
        }
        val active = entry.activeRequest
        val request = if (action == RequestAction.MERGE && active != null) {
            ActiveRequest(
                offset = minOf(active.offset, plan.offset),
                end = maxOf(active.end, plan.end),
                priority = maxPriority(active.priority, plan.priority),
                ownerKind = higherOwnerKind(active.ownerKind, plan.ownerKind),
            )
        } else {
            ActiveRequest(
                offset = plan.offset,
                end = plan.end,
                priority = plan.priority,
                ownerKind = plan.ownerKind,
            )
        }
        entry.activeRequest = request
        val pendingCancel = entry.cancelJob
        synchronized(entry.monitor) { entry.failure = null }
        scope.launch {
            pendingCancel?.join()
            val requestPriority = synchronized(lock) {
                if (request.cancelled.get() || entry.activeRequest !== request ||
                    entry.owners.isEmpty()
                ) {
                    return@launch
                }
                request.started.set(true)
                request.metricsContextRevision = networkMetrics.contextRevision
                request.lastProgressNanos = monotonicNanos()
                request.startedAtNanos = request.lastProgressNanos
                request.hasRecordedNetworkProgress = false
                request.lastProgressBytes = entry.snapshot
                    ?.contiguousBytesFrom(request.offset, request.length)
                    ?: 0L
                request.priority
            }
            if (cancelFirst) {
                trace("cancel fileId=$fileId result=DISJOINT_SWITCH")
                client.cancelDownloadFile(fileId)
                val stillCurrent = synchronized(lock) {
                    entry.activeRequest === request &&
                        !request.cancelled.get() &&
                        entry.owners.isNotEmpty()
                }
                if (!stillCurrent) return@launch
            }
            trace(
                "request begin fileId=$fileId owner=${request.ownerKind} " +
                    "priority=$requestPriority offset=${request.offset} " +
                    "limit=${request.length} result=$action",
            )
            when (
                val result = client.downloadFile(
                    fileId = fileId,
                    priority = requestPriority.tdLibPriority,
                    offset = request.offset,
                    limit = request.length,
                )
            ) {
                is TelegramClientResult.Success -> onDownloadRequestAccepted(
                    fileId = fileId,
                    entry = entry,
                    request = request,
                    snapshot = result.value.toDomain(),
                )
                is TelegramClientResult.Failure -> {
                    trace("request failed category=${result.failure.javaClass.simpleName}")
                    synchronized(lock) {
                        if (entry.activeRequest === request) {
                            synchronized(entry.monitor) {
                                entry.failure = result.failure.toString()
                            }
                            entry.activeRequest = null
                            synchronized(entry.monitor) { entry.monitor.notifyAll() }
                        }
                    }
                }
            }
        }
    }

    /**
     * TDLib treats a second downloadFile call for the same active range as an in-place priority
     * update. Keeping the same ActiveRequest means either response may still publish progress;
     * no cancelDownloadFile is sent and the existing NEXT_PRELOAD wait remains valid until the
     * CURRENT owner has acquired its lease.
     */
    private fun updateActiveRequestPriority(
        fileId: Int,
        entry: FileEntry,
        request: ActiveRequest,
    ) {
        val priority = request.priority
        val offset = request.offset
        val length = request.length
        scope.launch {
            when (
                val result = client.downloadFile(
                    fileId = fileId,
                    priority = priority.tdLibPriority,
                    offset = offset,
                    limit = length,
                )
            ) {
                is TelegramClientResult.Success -> onDownloadRequestAccepted(
                    fileId = fileId,
                    entry = entry,
                    request = request,
                    snapshot = result.value.toDomain(),
                )
                is TelegramClientResult.Failure -> trace(
                    "request reprioritize failed " +
                        "category=${result.failure.javaClass.simpleName}",
                )
            }
        }
    }

    private fun selectSteeringOwners(
        entry: FileEntry,
        unsatisfied: List<OwnerRange>,
    ): List<OwnerRange> {
        val playbackOwned = entry.owners.values.any { owner ->
            owner.ownerKind == TelegramFileOwnerKind.CURRENT_PLAYBACK
        }
        if (!playbackOwned) return unsatisfied
        return unsatisfied.filter { owner ->
            owner.ownerKind == TelegramFileOwnerKind.CURRENT_PLAYBACK
        }
    }

    private fun planRequest(
        unsatisfied: List<OwnerRange>,
        snapshot: TelegramFileSnapshot?,
    ): RangePlan {
        val primary = unsatisfied.maxWith(
            compareBy<OwnerRange> { owner -> owner.priority.tdLibPriority }
                .thenBy { owner -> -owner.sequence },
        )
        var requiredStart = primary.offset
        var requiredEnd = primary.end
        var changed: Boolean
        do {
            changed = false
            unsatisfied.forEach { candidate ->
                if (
                    candidate.offset <= requiredEnd &&
                    candidate.end >= requiredStart &&
                    maxOf(requiredEnd, candidate.end) - minOf(requiredStart, candidate.offset) <=
                    MAX_FOREGROUND_REQUEST_BYTES
                ) {
                    val mergedStart = minOf(requiredStart, candidate.offset)
                    val mergedEnd = maxOf(requiredEnd, candidate.end)
                    if (mergedStart != requiredStart || mergedEnd != requiredEnd) {
                        requiredStart = mergedStart
                        requiredEnd = mergedEnd
                        changed = true
                    }
                }
            }
        } while (changed)
        val compatible = unsatisfied.filter { candidate ->
            candidate.offset <= requiredEnd && candidate.end >= requiredStart
        }
        val priority = compatible
            .maxByOrNull { owner -> owner.priority.tdLibPriority }
            ?.priority
            ?: primary.priority
        val ownerKind = compatible
            .maxByOrNull { owner -> owner.priority.tdLibPriority }
            ?.ownerKind
            ?: primary.ownerKind
        val readAheadEnd = primary.offset.saturatedAdd(primary.readAheadBytes)
        val boundedEnd = maxOf(requiredEnd, readAheadEnd)
            .coerceAtMost(requiredStart.saturatedAdd(MAX_FOREGROUND_REQUEST_BYTES))
            .coerceAtMost(
                snapshot?.size?.takeIf { size -> size >= requiredEnd } ?: Long.MAX_VALUE,
            )
        return RangePlan(
            offset = requiredStart,
            end = boundedEnd,
            requiredStart = requiredStart,
            requiredEnd = requiredEnd,
            priority = priority,
            ownerKind = ownerKind,
        )
    }

    private fun maxPriority(
        first: TelegramFileRequestPriority,
        second: TelegramFileRequestPriority,
    ): TelegramFileRequestPriority =
        if (first.tdLibPriority >= second.tdLibPriority) first else second

    private fun higherOwnerKind(
        first: TelegramFileOwnerKind,
        second: TelegramFileOwnerKind,
    ): TelegramFileOwnerKind =
        if (
            first == TelegramFileOwnerKind.CURRENT_PLAYBACK ||
            second == TelegramFileOwnerKind.CURRENT_PLAYBACK
        ) {
            TelegramFileOwnerKind.CURRENT_PLAYBACK
        } else {
            TelegramFileOwnerKind.NEXT_PRELOAD
        }

    private fun onDownloadRequestAccepted(
        fileId: Int,
        entry: FileEntry,
        request: ActiveRequest,
        snapshot: TelegramFileSnapshot,
    ) {
        synchronized(lock) {
            if (entry.activeRequest !== request) return
            if (snapshot.fileId != fileId) {
                trace(
                    "request stale fileId=$fileId updateFileId=${snapshot.fileId} " +
                        "result=IGNORED_FILE_MISMATCH",
                )
                return
            }
            request.accepted = true
            publishSnapshot(entry, snapshot, shouldWakeWaiters(entry, snapshot))
            trace(
                "request accepted offset=${request.offset} limit=${request.length} " +
                    "covered=${request.covers(snapshot)} active=${snapshot.isDownloadingActive}",
            )
            if (request.covers(snapshot)) entry.activeRequest = null
            ensureRequestLocked(fileId, entry)
        }
    }

    private fun shouldWakeWaiters(
        entry: FileEntry,
        snapshot: TelegramFileSnapshot,
    ): Boolean = synchronized(entry.monitor) {
        val previous = entry.snapshot
        entry.owners.values.any { owner ->
            val previousProgress =
                previous?.contiguousBytesFrom(owner.offset, owner.length) ?: 0L
            val newProgress = snapshot.contiguousBytesFrom(owner.offset, owner.length)
            newProgress > previousProgress
        }
    }

    private fun publishSnapshot(
        entry: FileEntry,
        snapshot: TelegramFileSnapshot,
        notifyWaiters: Boolean,
    ) {
        synchronized(entry.monitor) {
            entry.snapshot = snapshot
            entry.state.value = snapshot
            if (notifyWaiters) entry.monitor.notifyAll()
        }
    }

    private fun touchLocked(fileId: Int, entry: FileEntry) {
        entry.lastAccessedAtMillis = maxOf(entry.lastAccessedAtMillis, nowMillis())
        persistEntryLocked(fileId, entry, entry.snapshot?.downloadedSize ?: 0L)
    }

    private fun persistEntryLocked(fileId: Int, entry: FileEntry, cachedBytes: Long) {
        val writer = cacheMetadataWriter ?: return
        val lastAccessedAt = entry.lastAccessedAtMillis.takeIf { it > 0L } ?: nowMillis()
        writer.record(
            fileId = fileId,
            cachedBytes = cachedBytes,
            lastAccessedAtMillis = lastAccessedAt,
        )
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) runCatching { Log.i(LOG_TAG, message) }
    }

    private fun ActiveRequest.covers(snapshot: TelegramFileSnapshot): Boolean =
        snapshot.covers(offset, length) ||
            (
                snapshot.isDownloadingCompleted &&
                    snapshot.size >= offset &&
                    snapshot.size <= end
                )

    private fun Long.saturatedAdd(increment: Long): Long =
        if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment

    private fun TelegramFileSnapshot.contiguousBytesFrom(
        start: Long,
        requestedLength: Long,
    ): Long {
        if (localPath == null || requestedLength <= 0L) return 0L
        if (isDownloadingCompleted && size > start) {
            return (size - start).coerceAtMost(requestedLength)
        }
        if (start < downloadOffset) return 0L
        val availableEnd = downloadOffset.saturatedAdd(downloadedPrefixSize)
        return (availableEnd - start)
            .coerceAtLeast(0L)
            .coerceAtMost(requestedLength)
    }

    private fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLISECOND

    private class FileEntry {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        val monitor = Object()
        val state = MutableStateFlow<TelegramFileSnapshot?>(null)
        val owners = mutableMapOf<String, OwnerRange>()
        val protections = mutableMapOf<String, TelegramFileOwnerKind>()
        var snapshot: TelegramFileSnapshot? = null
        var failure: String? = null
        var activeRequest: ActiveRequest? = null
        var cancelJob: Job? = null
        val prefixQueries = mutableMapOf<Long, PrefixQuery>()
        var cancelGeneration: Long = 0L
        var lastAccessedAtMillis: Long = 0L
        var deletionReserved: Boolean = false
    }

    private data class OwnerRange(
        val offset: Long,
        val length: Long,
        var priority: TelegramFileRequestPriority,
        val ownerKind: TelegramFileOwnerKind,
        val readAheadBytes: Long,
        val sequence: Long,
        var prefixProbeState: PrefixProbeState = PrefixProbeState.NOT_STARTED,
        var verifiedSnapshot: TelegramFileSnapshot? = null,
    ) {
        val end: Long get() = offset + length
    }

    private class ActiveRequest(
        var offset: Long,
        var end: Long,
        var priority: TelegramFileRequestPriority,
        var ownerKind: TelegramFileOwnerKind,
    ) {
        val cancelled = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        var accepted: Boolean = false
        var metricsContextRevision: Long = Long.MIN_VALUE
        var lastProgressNanos: Long = 0L
        var lastProgressBytes: Long = 0L
        var startedAtNanos: Long = 0L
        var hasRecordedNetworkProgress: Boolean = false
        val length: Long get() = end - offset
    }

    private class PrefixQuery(val offset: Long) {
        lateinit var job: Job
    }

    private data class PrefixProbeResult(
        val snapshot: TelegramFileSnapshot,
        val prefixSize: Long,
    )

    private enum class PrefixProbeState {
        NOT_STARTED,
        PENDING,
        HIT,
        MISS,
    }

    private data class RangePlan(
        val offset: Long,
        val end: Long,
        val requiredStart: Long,
        val requiredEnd: Long,
        val priority: TelegramFileRequestPriority,
        val ownerKind: TelegramFileOwnerKind,
    ) {
        val length: Long get() = end - offset
    }

    private data class ReleasedOwner(
        val foregroundBlocker: Boolean,
    ) {
        companion object {
            val NONE = ReleasedOwner(foregroundBlocker = false)
        }
    }

    private enum class RequestAction {
        START,
        MERGE,
        SWITCH,
        REPRIORITIZE,
    }

    private class RangeLeaseImpl(
        private val manager: TelegramFileManager,
        private val entry: FileEntry,
        override val fileId: Int,
        override val offset: Long,
        override val length: Long,
        private val ownerToken: String,
    ) : TelegramFileRangeLease {
        private var released = false

        override fun awaitAvailable(timeoutMillis: Long): TelegramFileSnapshot {
            check(!released) { "lease already closed" }
            return manager.await(entry, fileId, offset, length, ownerToken, timeoutMillis)
        }

        override fun updatePriority(priority: TelegramFileRequestPriority) {
            check(!released) { "lease already closed" }
            manager.updatePriority(fileId, ownerToken, priority)
        }

        override fun close() {
            if (released) return
            released = true
            manager.release(fileId, ownerToken)
        }
    }

    private class ProtectionLeaseImpl(
        private val manager: TelegramFileManager,
        override val fileId: Int,
        private val ownerToken: String,
        override val ownerKind: TelegramFileOwnerKind,
    ) : TelegramFileProtectionLease {
        private var released = false

        override fun close() {
            if (released) return
            released = true
            manager.release(fileId, ownerToken)
        }
    }

    private data class InternalResourceEntry(
        val fileId: Int,
        val ownerToken: String,
        val generation: Long,
        val kind: TelegramInternalResourceKind,
        val expectedSize: Long?,
        val referencedResources: Map<Int, TelegramInternalResourceHandle>,
        val expiresAtMillis: Long,
    )

    internal companion object {
        const val MAX_MERGED_RANGE_BYTES = 512L * 1024L
        const val MAX_FOREGROUND_REQUEST_BYTES = 4L * 1024L * 1024L
        const val FOREGROUND_READ_AHEAD_TRIGGER_BYTES = 256L * 1024L
        val FOREGROUND_PRIORITY = TelegramFileRequestPriority.CURRENT_STARTUP
        const val CANCEL_GRACE_MILLIS = 0L
        const val MAX_PROGRESS_WAIT_WINDOWS = 6
        const val LOG_TAG = "CVF-TdFile"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PREFIX_QUERY_TIMEOUT_MILLIS = 10L
        const val PRODUCTION_REUSE_CONTAINED_ACTIVE_REQUEST = false
        const val MAX_INTERNAL_RESOURCE_TTL_MILLIS = 15L * 60L * 1_000L
    }

    private val TelegramFileRequestPriority.suspendsPreload: Boolean
        get() = this == TelegramFileRequestPriority.CURRENT_STARTUP ||
            this == TelegramFileRequestPriority.CURRENT_SEEK

    private fun ActiveRequest.coversAny(owners: List<OwnerRange>): Boolean =
        owners.any { owner -> offset <= owner.offset && end >= owner.end }
}

internal class ProgressAwareRangeWaitBudget(
    stallTimeoutMillis: Long,
    startedAtMillis: Long,
    hardTimeoutMultiplier: Int,
) {
    private val stallTimeoutMillis = stallTimeoutMillis.coerceAtLeast(0L)
    private val hardTimeoutMillis = this.stallTimeoutMillis.saturatedMultiply(
        hardTimeoutMultiplier.coerceAtLeast(1),
    )
    private val startedAtMillis = startedAtMillis
    private var lastProgressAtMillis = startedAtMillis
    private var firstProgressAtMillis: Long? = null
    private var observedProgressBytes = 0L

    fun observeProgress(progressBytes: Long, nowMillis: Long) {
        val safeProgress = progressBytes.coerceAtLeast(0L)
        if (safeProgress <= observedProgressBytes) return
        if (firstProgressAtMillis == null) firstProgressAtMillis = nowMillis
        observedProgressBytes = safeProgress
        lastProgressAtMillis = nowMillis
    }

    fun remainingWaitMillis(nowMillis: Long): Long {
        val stallRemaining = remaining(
            durationMillis = stallTimeoutMillis,
            elapsedMillis = elapsed(lastProgressAtMillis, nowMillis),
        )
        val hardRemaining = remaining(
            durationMillis = hardTimeoutMillis,
            elapsedMillis = elapsed(startedAtMillis, nowMillis),
        )
        return minOf(stallRemaining, hardRemaining)
    }

    fun timeoutReason(nowMillis: Long): String {
        val hardExpired = elapsed(startedAtMillis, nowMillis) >= hardTimeoutMillis
        return if (hardExpired) "HARD_LIMIT" else "NO_PROGRESS"
    }

    fun firstProgressWaitMillis(): Long? = firstProgressAtMillis?.let { firstProgress ->
        elapsed(startedAtMillis, firstProgress)
    }

    private fun elapsed(startMillis: Long, nowMillis: Long): Long =
        (nowMillis - startMillis).coerceAtLeast(0L)

    private fun remaining(durationMillis: Long, elapsedMillis: Long): Long =
        (durationMillis - elapsedMillis).coerceAtLeast(0L)

    private fun Long.saturatedMultiply(multiplier: Int): Long =
        if (this > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else this * multiplier
}

private fun TelegramClientFileSnapshot.toDomain() = TelegramFileSnapshot(
    fileId = fileId,
    size = size,
    expectedSize = expectedSize,
    localPath = localPath,
    canBeDownloaded = canBeDownloaded,
    isDownloadingActive = isDownloadingActive,
    isDownloadingCompleted = isDownloadingCompleted,
    downloadOffset = downloadOffset,
    downloadedPrefixSize = downloadedPrefixSize,
    downloadedSize = downloadedSize,
)
