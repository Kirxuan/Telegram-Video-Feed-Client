package com.qixuan.channelvideoflow.telegram.media

import android.util.Log
import com.qixuan.channelvideoflow.database.MediaCacheEntryDao
import com.qixuan.channelvideoflow.database.MediaCacheEntryEntity
import com.qixuan.channelvideoflow.telegram.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One ordered writer for cache metadata. Old-account updates are rejected before they reach Room. */
internal class MediaCacheMetadataWriter(
    scope: CoroutineScope,
    private val writeBatch: suspend (List<MediaCacheEntryEntity>) -> Unit,
    private val clearAll: suspend () -> Unit,
    private val deleteOne: suspend (Int) -> Unit = {},
    private val batchDelayMillis: Long = DEFAULT_BATCH_DELAY_MILLIS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
) {
    constructor(
        dao: MediaCacheEntryDao,
        scope: CoroutineScope,
    ) : this(
        scope = scope,
        writeBatch = dao::upsertBatch,
        clearAll = dao::clear,
        deleteOne = dao::delete,
    )

    private val lock = Any()
    private var generation = 0L
    private var clearPending = false
    private var clearFailed = false
    private val pending = linkedMapOf<Int, MediaCacheEntryEntity>()
    private val deletions = linkedSetOf<Int>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    internal val pendingCount: Int get() = synchronized(lock) { pending.size + deletions.size }
    internal var committedRows: Long = 0L
        private set
    internal var committedBatches: Long = 0L
        private set

    private val writerJob: Job = scope.launch {
        for (ignored in wakeups) {
            if (batchDelayMillis > 0L) delay(batchDelayMillis)
            val batch = synchronized(lock) {
                Batch(generation, clearPending, deletions.toList(), pending.values.toList()).also {
                    clearPending = false
                    deletions.clear()
                    pending.clear()
                }
            }
            // One worker orders an in-flight old write before the reset barrier. A failed clear
            // blocks new-account writes until another explicit reset succeeds.
            if (batch.clear) clearFailed = !retry { clearAll() }
            if (clearFailed || batch.generation != synchronized(lock) { generation }) continue
            var deleted = true
            for (fileId in batch.deletions) {
                if (!retry { deleteOne(fileId) }) deleted = false
            }
            if (deleted && batch.entries.isNotEmpty()) {
                retry {
                    if (batch.generation == synchronized(lock) { generation }) {
                        writeBatch(batch.entries)
                        committedRows += batch.entries.size
                        committedBatches += 1
                        trace("summary touchWrites=$committedRows touchBatches=$committedBatches")
                    }
                }
            }
        }
    }

    fun record(fileId: Int, cachedBytes: Long, lastAccessedAtMillis: Long) {
        synchronized(lock) {
            val previous = pending[fileId]
            if (previous == null && pending.size >= MAX_PENDING_KEYS) {
                // LRU touches are approximate metadata, never media or control state. Keep a
                // bounded newest working set; TDLib storage statistics remain the capacity truth.
                pending.remove(pending.keys.first())
            }
            pending[fileId] = MediaCacheEntryEntity(
                fileId, cachedBytes.coerceAtLeast(0L),
                maxOf(previous?.lastAccessedAtMillis ?: 0L, lastAccessedAtMillis),
            )
        }
        wakeups.trySend(Unit)
    }

    fun resetAndClear() {
        synchronized(lock) {
            generation += 1
            pending.clear()
            deletions.clear()
            clearPending = true
        }
        wakeups.trySend(Unit)
    }

    fun delete(fileId: Int) {
        synchronized(lock) {
            pending.remove(fileId)
            deletions.add(fileId)
            if (deletions.size >= MAX_PENDING_KEYS) {
                // Equivalent conservative invalidation of metadata on a deletion flood.
                // This never deletes media bytes and cannot remove any protection lease.
                deletions.clear()
                clearPending = true
            }
        }
        wakeups.trySend(Unit)
    }

    fun close() {
        wakeups.close()
        writerJob.cancel()
        synchronized(lock) { pending.clear(); deletions.clear() }
    }

    private suspend fun retry(block: suspend () -> Unit): Boolean {
        repeat(MAX_WRITE_ATTEMPTS) { attempt ->
            try {
                block()
                return true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (attempt + 1 < MAX_WRITE_ATTEMPTS && retryDelayMillis > 0L) {
                    delay(retryDelayMillis * (attempt + 1))
                }
            }
        }
        trace("writerFailure=true attempts=$MAX_WRITE_ATTEMPTS")
        return false
    }

    private data class Batch(
        val generation: Long,
        val clear: Boolean,
        val deletions: List<Int>,
        val entries: List<MediaCacheEntryEntity>,
    )

    private fun trace(message: String) {
        if (BuildConfig.PERFORMANCE_DIAGNOSTICS_ENABLED) runCatching { Log.i(LOG_TAG, message) }
    }

    private companion object {
        const val MAX_PENDING_KEYS = 256
        const val DEFAULT_BATCH_DELAY_MILLIS = 100L
        const val DEFAULT_RETRY_DELAY_MILLIS = 250L
        const val MAX_WRITE_ATTEMPTS = 3
        const val LOG_TAG = "CVF-CachePerf"
    }
}
