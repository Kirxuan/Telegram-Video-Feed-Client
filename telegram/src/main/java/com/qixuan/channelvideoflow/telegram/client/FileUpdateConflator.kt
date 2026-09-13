package com.qixuan.channelvideoflow.telegram.client

import android.util.Log
import com.qixuan.channelvideoflow.telegram.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Coalesces UpdateFile by fileId without ever suspending the TDLib control-event producer. */
internal class FileUpdateConflator(
    private val scope: CoroutineScope,
    private val publish: suspend (TelegramClientFileSnapshot) -> Unit,
) {
    private val lock = Any()
    private val pending = linkedMapOf<Int, PendingUpdate>()
    private var drainScheduled = false
    private var generation = 0L
    private var received = 0L
    private var applied = 0L
    private var coalesced = 0L

    fun offer(snapshot: TelegramClientFileSnapshot) {
        var schedule = false
        synchronized(lock) {
            received += 1L
            if (pending.put(snapshot.fileId, PendingUpdate(generation, snapshot)) != null) {
                coalesced += 1L
            }
            if (!drainScheduled) {
                drainScheduled = true
                schedule = true
            }
        }
        if (schedule) scope.launch { drain() }
    }

    fun advanceGeneration() {
        synchronized(lock) {
            generation += 1L
            pending.clear()
        }
    }

    internal fun counters(): FileUpdateCounters = synchronized(lock) {
        FileUpdateCounters(received, applied, coalesced, pending.size)
    }

    private suspend fun drain() {
        while (true) {
            val batch = synchronized(lock) {
                if (pending.isEmpty()) {
                    drainScheduled = false
                    return
                }
                pending.values.toList().also { pending.clear() }
            }
            for (update in batch) {
                val active = synchronized(lock) { update.generation == generation }
                if (!active) continue
                publish(update.snapshot)
                synchronized(lock) { applied += 1L }
            }
            val snapshot = counters()
            trace(
                "summary eventReceived=${snapshot.received} " +
                    "eventApplied=${snapshot.applied} eventCoalesced=${snapshot.coalesced}",
            )
        }
    }

    private fun trace(message: String) {
        if (BuildConfig.PERFORMANCE_DIAGNOSTICS_ENABLED) runCatching { Log.i(LOG_TAG, message) }
    }

    private data class PendingUpdate(
        val generation: Long,
        val snapshot: TelegramClientFileSnapshot,
    )

    private companion object {
        const val LOG_TAG = "CVF-CachePerf"
    }
}

internal data class FileUpdateCounters(
    val received: Long,
    val applied: Long,
    val coalesced: Long,
    val pending: Int,
)
