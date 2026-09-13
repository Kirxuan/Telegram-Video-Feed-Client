package com.qixuan.channelvideoflow.telegram.media

import android.util.Log
import com.qixuan.channelvideoflow.database.MediaCacheEntryDao
import com.qixuan.channelvideoflow.domain.cache.MediaCacheController
import com.qixuan.channelvideoflow.domain.cache.MediaCacheEntry
import com.qixuan.channelvideoflow.domain.cache.MediaCacheEvictionPlanner
import com.qixuan.channelvideoflow.domain.cache.MediaCacheLimits
import com.qixuan.channelvideoflow.domain.cache.MediaCacheOperation
import com.qixuan.channelvideoflow.domain.cache.MediaCachePreferences
import com.qixuan.channelvideoflow.domain.cache.MediaCacheState
import com.qixuan.channelvideoflow.domain.media.DevicePreloadPolicySource
import com.qixuan.channelvideoflow.domain.media.TelegramFileDeleteResult
import com.qixuan.channelvideoflow.domain.media.TelegramFileGateway
import com.qixuan.channelvideoflow.model.video.VideoQualityPreference
import com.qixuan.channelvideoflow.telegram.BuildConfig
import com.qixuan.channelvideoflow.telegram.client.TelegramClientResult
import com.qixuan.channelvideoflow.telegram.client.TelegramFileClient
import com.qixuan.channelvideoflow.telegram.client.TelegramFileClientEvent
import com.qixuan.channelvideoflow.telegram.di.TelegramApplicationScope
import com.qixuan.channelvideoflow.telegram.di.TelegramIoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
internal class TdLibMediaCacheManager @Inject constructor(
    private val client: TelegramFileClient,
    private val gateway: TelegramFileGateway,
    private val cacheEntryDao: MediaCacheEntryDao,
    private val preferences: MediaCachePreferences,
    private val policySource: DevicePreloadPolicySource,
    private val privateSizer: PrivateMediaCacheSizer,
    @param:TelegramApplicationScope private val scope: CoroutineScope,
    @param:TelegramIoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MediaCacheController {
    private val mutableState = MutableStateFlow(MediaCacheState())
    override val state: StateFlow<MediaCacheState> = mutableState.asStateFlow()
    private val operationMutex = Mutex()
    private var started = false
    private var refreshJob: Job? = null

    override fun start() {
        if (started) return
        started = true
        scope.launch(ioDispatcher) {
            val startupBytes = privateSizer.allocatedBytes()
            if (!mutableState.value.isExactUsage) {
                mutableState.value = mutableState.value.copy(
                    usedBytes = startupBytes,
                    isExactUsage = false,
                )
            }
        }
        scope.launch {
            preferences.preferences.collect { saved ->
                val previousLimit = mutableState.value.limitBytes
                mutableState.value = mutableState.value.copy(
                    limitBytes = saved.limitBytes,
                    mobileDataPreloadEnabled = saved.mobileDataPreloadEnabled,
                    videoQualityPreference = saved.videoQualityPreference,
                )
                if (saved.limitBytes < previousLimit) trimToLimit()
            }
        }
        scope.launch {
            client.fileEvents.collect { event ->
                when (event) {
                    TelegramFileClientEvent.Ready -> {
                        refresh()
                        trimToLimit()
                    }
                    is TelegramFileClientEvent.FileUpdated -> scheduleRefreshAndTrim()
                    TelegramFileClientEvent.AccountLoggingOut -> {
                        refreshJob?.cancel()
                        cacheEntryDao.clear()
                        mutableState.value = mutableState.value.copy(
                            usedBytes = 0L,
                            isExactUsage = false,
                            operation = MediaCacheOperation.Idle,
                        )
                    }
                }
            }
        }
        scope.launch {
            policySource.signals
                .map { signals -> signals.isStorageLow }
                .distinctUntilChanged()
                .collect { storageLow ->
                    if (storageLow) trimForLowStorage()
                }
        }
    }

    override suspend fun refresh() {
        mutableState.value = mutableState.value.copy(isRefreshing = true)
        when (val result = client.getStorageStatistics()) {
            is TelegramClientResult.Success -> {
                mutableState.value = mutableState.value.copy(
                    usedBytes = result.value.videoBytes.coerceAtLeast(0L),
                    isExactUsage = true,
                    isRefreshing = false,
                )
            }
            is TelegramClientResult.Failure -> {
                mutableState.value = mutableState.value.copy(isRefreshing = false)
            }
        }
    }

    override suspend fun setLimitBytes(bytes: Long) {
        preferences.setLimitBytes(MediaCacheLimits.requireAllowed(bytes))
        mutableState.value = mutableState.value.copy(limitBytes = bytes)
        trimToLimit()
    }

    override suspend fun setMobileDataPreloadEnabled(enabled: Boolean) {
        preferences.setMobileDataPreloadEnabled(enabled)
        mutableState.value = mutableState.value.copy(mobileDataPreloadEnabled = enabled)
    }

    override suspend fun setVideoQualityPreference(preference: VideoQualityPreference) {
        preferences.setVideoQualityPreference(preference)
        mutableState.value = mutableState.value.copy(videoQualityPreference = preference)
    }

    override suspend fun trimToLimit() {
        trimTo(mutableState.value.limitBytes, clear = false)
    }

    override suspend fun clearMediaCache() {
        trimTo(targetBytes = 0L, clear = true)
    }

    private suspend fun trimForLowStorage() {
        refresh()
        val target = (mutableState.value.usedBytes - LOW_STORAGE_RELEASE_BYTES).coerceAtLeast(0L)
            .coerceAtMost(mutableState.value.limitBytes)
        trimTo(target, clear = false)
    }

    private suspend fun trimTo(targetBytes: Long, clear: Boolean) {
        operationMutex.withLock {
            refresh()
            val before = mutableState.value.usedBytes
            if (before <= targetBytes) {
                mutableState.value = mutableState.value.copy(
                    operation = if (clear) {
                        MediaCacheOperation.Cleared(0L)
                    } else {
                        MediaCacheOperation.Trimmed(0L)
                    },
                )
                return
            }

            val lru = cacheEntryDao.getLruEntries().map { entry ->
                MediaCacheEntry(
                    fileId = entry.fileId,
                    cachedBytes = entry.cachedBytes,
                    lastAccessedAtMillis = entry.lastAccessedAtMillis,
                )
            }
            val plan = MediaCacheEvictionPlanner.plan(
                currentBytes = before,
                targetBytes = targetBytes,
                entries = lru,
                protectedFileIds = gateway.protectedFileIds(),
            )
            var deletionFailed = false
            plan.fileIds.forEach { fileId ->
                when (gateway.deleteCachedFile(fileId)) {
                    TelegramFileDeleteResult.DELETED -> Unit
                    TelegramFileDeleteResult.PROTECTED,
                    TelegramFileDeleteResult.FAILED,
                    -> deletionFailed = true
                }
            }
            refresh()

            if (
                mutableState.value.usedBytes > targetBytes &&
                gateway.protectedFileIds().isEmpty()
            ) {
                when (val optimized = client.optimizeVideoStorage(targetBytes)) {
                    is TelegramClientResult.Success -> {
                        mutableState.value = mutableState.value.copy(
                            usedBytes = optimized.value.videoBytes.coerceAtLeast(0L),
                            isExactUsage = true,
                        )
                        removeMissingLruEntries()
                    }
                    is TelegramClientResult.Failure -> deletionFailed = true
                }
            }
            refresh()
            val remaining = mutableState.value.usedBytes
            val released = (before - remaining).coerceAtLeast(0L)
            mutableState.value = mutableState.value.copy(
                operation = when {
                    remaining > targetBytes -> MediaCacheOperation.Partial(released, remaining)
                    clear -> MediaCacheOperation.Cleared(released)
                    deletionFailed -> MediaCacheOperation.Partial(released, remaining)
                    else -> MediaCacheOperation.Trimmed(released)
                },
            )
            trace(
                "cache ${if (clear) "clear" else "trim"} before=$before " +
                    "target=$targetBytes remaining=$remaining protected=" +
                    gateway.protectedFileIds().size,
            )
        }
    }

    private fun scheduleRefreshAndTrim() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            delay(REFRESH_DEBOUNCE_MILLIS)
            refresh()
            if (mutableState.value.usedBytes > mutableState.value.limitBytes) trimToLimit()
        }
    }

    private suspend fun removeMissingLruEntries() {
        cacheEntryDao.getLruEntries().forEach { entry ->
            when (val file = client.getFile(entry.fileId)) {
                is TelegramClientResult.Success -> if (
                    file.value.localPath == null || file.value.downloadedSize <= 0L
                ) {
                    cacheEntryDao.delete(entry.fileId)
                }
                is TelegramClientResult.Failure -> Unit
            }
        }
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) runCatching { Log.i(LOG_TAG, message) }
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MILLIS = 2_000L
        const val LOW_STORAGE_RELEASE_BYTES = 128L * MediaCacheLimits.MEBIBYTE
        const val LOG_TAG = "CVF-Cache"
    }
}
