package com.qixuan.channelvideoflow.player

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.qixuan.channelvideoflow.domain.media.TelegramFileGateway
import com.qixuan.channelvideoflow.domain.media.TelegramFileOwnerKind
import com.qixuan.channelvideoflow.domain.media.TelegramFileRequestPriority
import com.qixuan.channelvideoflow.domain.media.TelegramFileRangeLease
import com.qixuan.channelvideoflow.domain.media.TelegramFileSnapshot
import com.qixuan.channelvideoflow.domain.media.TelegramFileTimeoutException
import com.qixuan.channelvideoflow.domain.media.TelegramFileUnavailableException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Media3 DataSource backed by TDLib's private local file path.
 *
 * The URI is an app-internal identifier (`telegram-file://file/<id>`), never a
 * Telegram message link or a network URL.
 */
@UnstableApi
class TelegramMediaDataSource(
    private val gateway: TelegramFileGateway,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val chunkSizeBytes: Long = DEFAULT_CHUNK_SIZE_BYTES,
    private val isMainThread: () -> Boolean = {
        Looper.myLooper() == Looper.getMainLooper()
    },
    private val fileIdOverride: Int? = null,
    private val requestSession: PlaybackRangeRequestSession = PlaybackRangeRequestSession(),
    private val onCurrentRangeLeaseAcquired: ((Boolean) -> Unit)? = null,
    private val ownerKindOverride: TelegramFileOwnerKind? = null,
    private val maxReadAheadBytes: Long = MAX_CURRENT_READ_AHEAD_BYTES,
    /**
     * Live ceiling for the read-ahead window of the current stream. TDLib keeps serving a range
     * that was already acquired, so a bounded next item cannot get the link until the current
     * stream's in-flight window drains. Narrowing that window while the next item waits hands the
     * link over within roughly one window instead of one full 4 MiB window.
     */
    private val dynamicMaxReadAheadBytes: (() -> Long)? = null,
) : DataSource {
    private val closeLock = Any()
    private var currentUri: Uri? = null
    private var dataSpec: DataSpec? = null
    private var fileId: Int? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var rangeEnd = 0L
    private var lease: TelegramFileRangeLease? = null
    private var rangeRegistration: PlaybackRangeRegistration? = null
    private var pendingLease: TelegramFileRangeLease? = null
    private var pendingRegistration: PlaybackRangeRegistration? = null
    private var file: RandomAccessFile? = null
    @Volatile
    private var closed = false

    override fun addTransferListener(transferListener: TransferListener) = Unit

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        checkNotMainThread()
        close()
        closed = false
        this.dataSpec = dataSpec
        currentUri = dataSpec.uri
        fileId = fileIdOverride ?: parseFileId(dataSpec.uri)
        position = dataSpec.position
        remaining = dataSpec.length
        if (remaining == 0L) return 0L
        if (isAtKnownEndOfFile()) return 0L

        // Let the extractor inspect the header/index as soon as one small range is
        // available. The independent rolling read-ahead still keeps TDLib downloading.
        val initialLength = requestedLength(remaining).coerceAtMost(INITIAL_REQUIRED_BYTES)
        val currentFileId = fileId ?: throw TelegramMediaDataSourceException("缺少 TDLib fileId")
        requestSession.onDataSpecOpened(
            position = position,
            requestedLength = initialLength,
            snapshot = gateway.currentSnapshot(currentFileId),
        )
        val snapshot = acquireAndOpen(initialLength)
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            return dataSpec.length
        }
        return snapshot.size
            .takeIf { it > 0L }
            ?.let { size -> (size - position).coerceAtLeast(0L) }
            ?: C.LENGTH_UNSET.toLong()
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkNotMainThread()
        if (length == 0) return 0
        if (closed || file == null) return C.RESULT_END_OF_INPUT
        if (rangeRegistration?.isActive == false) {
            throw TelegramMediaUnavailableException("TDLib 文件区间已被新请求取代")
        }
        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        if (position >= rangeEnd) {
            if (remaining != C.LENGTH_UNSET.toLong() && remaining <= 0L) {
                return C.RESULT_END_OF_INPUT
            }
            if (isAtKnownEndOfFile()) return C.RESULT_END_OF_INPUT
            advanceRange()
        }

        val boundedByRange = (rangeEnd - position).coerceAtMost(Int.MAX_VALUE.toLong())
        val boundedByRequest = if (remaining == C.LENGTH_UNSET.toLong()) {
            boundedByRange
        } else {
            boundedByRange.coerceAtMost(remaining)
        }
        val toRead = minOf(length.toLong(), boundedByRequest).toInt()
        if (toRead <= 0) return C.RESULT_END_OF_INPUT
        val read = try {
            file?.seek(position)
            file?.read(buffer, offset, toRead) ?: -1
        } catch (error: Exception) {
            throw TelegramMediaReadException("读取 TDLib 私有文件失败", error)
        }
        if (read <= 0) {
            throw TelegramMediaReadException("TDLib 文件区间不可读")
        }
        position += read
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = EMPTY_HEADERS

    @Throws(IOException::class)
    override fun close() {
        val resources = synchronized(closeLock) {
            if (closed) return
            closed = true
            CloseResources(
                file = file,
                registration = rangeRegistration,
                lease = lease,
                pendingRegistration = pendingRegistration,
                pendingLease = pendingLease,
            ).also {
                file = null
                rangeRegistration = null
                lease = null
                pendingRegistration = null
                pendingLease = null
                dataSpec = null
                fileId = null
                rangeEnd = 0L
            }
        }
        resources.close()
    }

    private fun acquireAndOpen(
        length: Long,
        allowStalePathRetry: Boolean = true,
    ): TelegramFileSnapshot {
        val currentFileId = fileId ?: throw TelegramMediaDataSourceException("缺少 TDLib fileId")
        val ownerToken = OWNER_COUNTER.incrementAndGet().toString() + "-" + UUID.randomUUID()
        val requestPriority = requestSession.currentPriority()
        val readAheadBytes = requestReadAheadBytes(requestedLength = length)
        val newLease = try {
            gateway.acquireRange(
                fileId = currentFileId,
                offset = position,
                length = length,
                priority = requestPriority,
                ownerToken = ownerToken,
                ownerKind = ownerKindOverride ?: requestSession.currentOwnerKind(),
                readAheadBytes = readAheadBytes,
            )
        } catch (error: Exception) {
            notifyCurrentRangeLeaseAcquired(false)
            throw TelegramMediaUnavailableException("无法建立 TDLib 当前播放区间", error)
        }
        val newRegistration = try {
            requestSession.registerCancellation(
                cancel = newLease::close,
                updatePriority = newLease::updatePriority,
                acquiredPriority = requestPriority,
            )
        } catch (error: Exception) {
            newLease.close()
            notifyCurrentRangeLeaseAcquired(false)
            throw TelegramMediaUnavailableException("无法注册 TDLib 当前播放区间", error)
        }
        val acceptedAsPending = synchronized(closeLock) {
            if (closed) {
                false
            } else {
                pendingLease = newLease
                pendingRegistration = newRegistration
                true
            }
        }
        if (!acceptedAsPending) {
            newRegistration.close()
            newLease.close()
            notifyCurrentRangeLeaseAcquired(false)
            throw TelegramMediaUnavailableException("TDLib 文件区间已关闭")
        }
        notifyCurrentRangeLeaseAcquired(true)
        val snapshot = try {
            newLease.awaitAvailable(timeoutMillis)
        } catch (error: TelegramFileTimeoutException) {
            clearPending(newLease, newRegistration)
            newRegistration.close()
            newLease.close()
            throw TelegramMediaTimeoutException("等待 TDLib 文件区间超时", error)
        } catch (error: TelegramFileUnavailableException) {
            clearPending(newLease, newRegistration)
            newRegistration.close()
            newLease.close()
            throw TelegramMediaUnavailableException("TDLib 文件不可用", error)
        } catch (error: Exception) {
            clearPending(newLease, newRegistration)
            newRegistration.close()
            newLease.close()
            throw TelegramMediaUnavailableException("等待 TDLib 文件区间失败", error)
        }
        if (closed || !newRegistration.isActive) {
            clearPending(newLease, newRegistration)
            newRegistration.close()
            newLease.close()
            throw TelegramMediaUnavailableException("TDLib 文件区间已被新请求取代")
        }
        val path = snapshot.localPath
            ?: run {
                newRegistration.close()
                newLease.close()
                clearPending(newLease, newRegistration)
                throw TelegramMediaUnavailableException("TDLib 未提供私有文件路径")
            }
        if (snapshot.size > 0L && position >= snapshot.size) {
            newRegistration.close()
            newLease.close()
            clearPending(newLease, newRegistration)
            rangeEnd = position
            file = null
            lease = null
            return snapshot
        }
        if (!snapshot.covers(position, length)) {
            newRegistration.close()
            newLease.close()
            clearPending(newLease, newRegistration)
            throw TelegramMediaUnavailableException("TDLib 文件未覆盖请求区间")
        }
        val opened = try {
            RandomAccessFile(path, "r")
        } catch (error: Exception) {
            clearPending(newLease, newRegistration)
            newRegistration.close()
            newLease.close()
            if (allowStalePathRetry && error.isMissingLocalFile()) {
                gateway.invalidateLocalSnapshot(
                    fileId = currentFileId,
                    expectedLocalPath = path,
                )
                return acquireAndOpen(length = length, allowStalePathRetry = false)
            }
            throw TelegramMediaUnavailableException("无法打开 TDLib 私有文件", error)
        }
        val openedRangeEnd = reusableRangeEnd(
            snapshot = snapshot,
            requestedLength = length,
            readAheadBytes = readAheadBytes,
        )
        val previous = synchronized(closeLock) {
            if (closed || !newRegistration.isActive) {
                null
            } else {
                val old = CloseResources(
                    file = file,
                    registration = rangeRegistration,
                    lease = lease,
                )
                pendingLease = null
                pendingRegistration = null
                rangeRegistration = newRegistration
                lease = newLease
                file = opened
                rangeEnd = openedRangeEnd
                old
            }
        }
        if (previous == null) {
            runCatching { opened.close() }
            clearPending(newLease, newRegistration)
            newRegistration.close()
            newLease.close()
            throw TelegramMediaUnavailableException("TDLib 文件区间已关闭")
        }
        previous.close()
        requestSession.onRangeReady(
            fileId = currentFileId,
            priority = requestPriority,
        )
        return snapshot
    }

    private fun Throwable.isMissingLocalFile(): Boolean =
        this is FileNotFoundException || cause is java.nio.file.NoSuchFileException

    private fun clearPending(
        candidateLease: TelegramFileRangeLease,
        candidateRegistration: PlaybackRangeRegistration,
    ) {
        synchronized(closeLock) {
            if (pendingLease === candidateLease) pendingLease = null
            if (pendingRegistration === candidateRegistration) pendingRegistration = null
        }
    }

    private fun notifyCurrentRangeLeaseAcquired(acquired: Boolean) {
        runCatching { onCurrentRangeLeaseAcquired?.invoke(acquired) }
    }

    private fun advanceRange() {
        val nextLength = requestedLength(remaining)
        acquireAndOpen(nextLength)
    }

    private fun requestReadAheadBytes(requestedLength: Long): Long {
        val dataSpecRemaining = remaining
            .takeUnless { value -> value == C.LENGTH_UNSET.toLong() }
            ?.coerceAtLeast(requestedLength)
            ?: MAX_CURRENT_READ_AHEAD_BYTES
        val knownFileRemaining = fileId
            ?.let(gateway::currentSnapshot)
            ?.size
            ?.takeIf { size -> size > position }
            ?.minus(position)
            ?: MAX_CURRENT_READ_AHEAD_BYTES
        val effectiveReadAheadCeiling = dynamicMaxReadAheadBytes?.invoke() ?: maxReadAheadBytes
        return minOf(
            effectiveReadAheadCeiling,
            if (requestSession.isPreloadOnly()) NextPreloadBudgetControllerBridge.CHUNK_BYTES
                else MAX_CURRENT_READ_AHEAD_BYTES,
            dataSpecRemaining,
            knownFileRemaining,
        ).coerceAtLeast(requestedLength)
    }

    private fun reusableRangeEnd(
        snapshot: TelegramFileSnapshot,
        requestedLength: Long,
        readAheadBytes: Long,
    ): Long {
        val requestedEnd = position.saturatedAdd(requestedLength)
        val availableEnd = if (snapshot.isDownloadingCompleted && snapshot.size > 0L) {
            snapshot.size
        } else {
            snapshot.downloadOffset.saturatedAdd(snapshot.downloadedPrefixSize)
        }
        val dataSpecEnd = remaining
            .takeUnless { value -> value == C.LENGTH_UNSET.toLong() }
            ?.let { requestLength -> position.saturatedAdd(requestLength) }
            ?: Long.MAX_VALUE
        val knownFileEnd = snapshot.size.takeIf { size -> size > 0L } ?: Long.MAX_VALUE
        val boundedEnd = minOf(
            availableEnd,
            position.saturatedAdd(readAheadBytes),
            dataSpecEnd,
            knownFileEnd,
        )
        if (boundedEnd < requestedEnd) {
            throw TelegramMediaUnavailableException("TDLib 文件未覆盖请求区间")
        }
        return boundedEnd
    }

    private fun isAtKnownEndOfFile(): Boolean {
        val currentFileId = fileId ?: return false
        val size = gateway.currentSnapshot(currentFileId)?.size ?: return false
        return size > 0L && position >= size
    }

    private fun requestedLength(requestRemaining: Long): Long {
        val chunkLength = if (requestRemaining == C.LENGTH_UNSET.toLong()) {
            chunkSizeBytes
        } else {
            requestRemaining.coerceAtMost(chunkSizeBytes)
        }
        val knownRemaining = fileId
            ?.let(gateway::currentSnapshot)
            ?.size
            ?.takeIf { it > position }
            ?.minus(position)
        val fileBounded = knownRemaining?.let { minOf(chunkLength, it) } ?: chunkLength
        return fileBounded
    }

    private fun checkNotMainThread() {
        if (isMainThread()) {
            throw TelegramMediaDataSourceException("Media3 DataSource 不允许在主线程读取")
        }
    }

    private fun parseFileId(uri: Uri): Int {
        if (uri.scheme != SCHEME || uri.authority != "file" || uri.pathSegments.size != 1) {
            throw TelegramMediaDataSourceException("无效的 Telegram 文件标识")
        }
        return uri.pathSegments[0].toIntOrNull()
            ?: throw TelegramMediaDataSourceException("无效的 TDLib fileId")
    }

    private fun Long.saturatedAdd(increment: Long): Long =
        if (this > Long.MAX_VALUE - increment) Long.MAX_VALUE else this + increment

    @UnstableApi
    class Factory(
        private val gateway: TelegramFileGateway,
        private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        private val chunkSizeBytes: Long = DEFAULT_CHUNK_SIZE_BYTES,
        private val requestSession: PlaybackRangeRequestSession = PlaybackRangeRequestSession(),
        private val onCurrentRangeLeaseAcquired: ((Boolean) -> Unit)? = null,
        private val ownerKindOverride: TelegramFileOwnerKind? = null,
        private val maxReadAheadBytes: Long = MAX_CURRENT_READ_AHEAD_BYTES,
        private val dynamicMaxReadAheadBytes: (() -> Long)? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramMediaDataSource(
            gateway = gateway,
            timeoutMillis = timeoutMillis,
            chunkSizeBytes = chunkSizeBytes,
            requestSession = requestSession,
            onCurrentRangeLeaseAcquired = onCurrentRangeLeaseAcquired,
            ownerKindOverride = ownerKindOverride,
            maxReadAheadBytes = maxReadAheadBytes,
            dynamicMaxReadAheadBytes = dynamicMaxReadAheadBytes,
        )
    }

    companion object {
        const val SCHEME = "telegram-file"
        const val DEFAULT_CHUNK_SIZE_BYTES = 256L * 1024L
        const val INITIAL_REQUIRED_BYTES = 64L * 1024L
        const val MAX_CURRENT_READ_AHEAD_BYTES = 4L * 1024L * 1024L
        const val DEFAULT_TIMEOUT_MILLIS = 15_000L
        private val EMPTY_HEADERS: Map<String, List<String>> = emptyMap()
        private val OWNER_COUNTER = AtomicLong()

        fun uriForFile(fileId: Int): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority("file")
            .appendPath(fileId.toString())
            .build()
    }

    private data class CloseResources(
        val file: RandomAccessFile? = null,
        val registration: PlaybackRangeRegistration? = null,
        val lease: TelegramFileRangeLease? = null,
        val pendingRegistration: PlaybackRangeRegistration? = null,
        val pendingLease: TelegramFileRangeLease? = null,
    ) {
        fun close() {
            runCatching { file?.close() }
            registration?.close()
            lease?.close()
            pendingRegistration?.close()
            pendingLease?.close()
        }
    }
}

class PlaybackRangeRequestSession(
    private val nowNanos: () -> Long = System::nanoTime,
    preloadOnly: Boolean = false,
) {
    private val lock = Any()
    private val startupRangeObservation = StartupRangeObservation()
    private var phase = Phase.STARTUP
    private var preloadOnly = preloadOnly
    private var nextRegistrationId = 0L
    private val registrations = mutableMapOf<Long, SessionRegistration>()
    private var firstRange: FirstRangeReady? = null

    internal fun currentPriority(): TelegramFileRequestPriority = synchronized(lock) {
        currentPriorityLocked()
    }

    internal fun currentOwnerKind(): TelegramFileOwnerKind = synchronized(lock) {
        if (preloadOnly) TelegramFileOwnerKind.NEXT_PRELOAD
        else TelegramFileOwnerKind.CURRENT_PLAYBACK
    }

    internal fun isPreloadOnly(): Boolean = synchronized(lock) { preloadOnly }

    fun promoteToCurrent() {
        val updates = synchronized(lock) {
            preloadOnly = false
            phase = Phase.STARTUP
            registrations.values.map(SessionRegistration::updatePriority)
        }
        updates.forEach { update -> runCatching { update(TelegramFileRequestPriority.CURRENT_STARTUP) } }
    }

    internal fun registerCancellation(
        cancel: () -> Unit,
        updatePriority: (TelegramFileRequestPriority) -> Unit,
        acquiredPriority: TelegramFileRequestPriority,
    ): PlaybackRangeRegistration {
        val registrationId = synchronized(lock) {
            if (phase == Phase.CLOSED) null else ++nextRegistrationId
        }
        if (registrationId == null) {
            cancel()
            return PlaybackRangeRegistration.inactive()
        }
        val registration = PlaybackRangeRegistration(
            onClose = {
                synchronized(lock) { registrations.remove(registrationId) }
            },
            onPriorityChange = updatePriority,
        )
        synchronized(lock) {
            if (phase == Phase.CLOSED) {
                registration.cancel()
            } else {
                registrations[registrationId] = SessionRegistration(
                    cancel = {
                        registration.cancel()
                        cancel()
                    },
                    updatePriority = registration::updatePriority,
                )
                val currentPriority = currentPriorityLocked()
                if (currentPriority != acquiredPriority) {
                    registration.updatePriority(currentPriority)
                }
            }
        }
        return registration
    }

    fun onRangeReady(
        fileId: Int,
        priority: TelegramFileRequestPriority,
    ) {
        val updates = synchronized(lock) {
            if (firstRange == null) {
                firstRange = FirstRangeReady(
                    fileId = fileId,
                    priority = priority,
                    atNanos = nowNanos(),
                )
            }
            if (phase == Phase.SEEK && priority == TelegramFileRequestPriority.CURRENT_SEEK) {
                phase = Phase.CONTINUATION
                registrations.values.map(SessionRegistration::updatePriority)
            } else {
                emptyList()
            }
        }
        updates.forEach { update ->
            runCatching { update(TelegramFileRequestPriority.CURRENT_CONTINUATION) }
        }
    }

    internal fun firstRangeReady(): FirstRangeReady? = synchronized(lock) { firstRange }

    internal fun onDataSpecOpened(
        position: Long,
        requestedLength: Long,
        snapshot: TelegramFileSnapshot?,
    ) {
        startupRangeObservation.onDataSpecOpened(position, requestedLength, snapshot)
    }

    internal fun startupRangeObservation(): StartupRangeObservationSnapshot =
        startupRangeObservation.snapshot()

    fun onFirstFrame() {
        val updates = synchronized(lock) {
            if (phase == Phase.CLOSED) {
                emptyList()
            } else {
                phase = Phase.CONTINUATION
                registrations.values.map(SessionRegistration::updatePriority)
            }
        }
        updates.forEach { update ->
            runCatching { update(TelegramFileRequestPriority.CURRENT_CONTINUATION) }
        }
    }

    fun onUserSeek() {
        cancelActive(Phase.SEEK)
    }

    fun close() {
        cancelActive(Phase.CLOSED)
    }

    private fun cancelActive(nextPhase: Phase) {
        val callbacks = synchronized(lock) {
            phase = nextPhase
            registrations.values.map(SessionRegistration::cancel).also {
                registrations.clear()
            }
        }
        callbacks.forEach { cancel -> runCatching(cancel) }
    }

    private enum class Phase {
        STARTUP,
        SEEK,
        CONTINUATION,
        CLOSED,
    }

    private data class SessionRegistration(
        val cancel: () -> Unit,
        val updatePriority: (TelegramFileRequestPriority) -> Unit,
    )

    private fun currentPriorityLocked(): TelegramFileRequestPriority {
        if (phase == Phase.CLOSED) {
            throw TelegramMediaUnavailableException("播放文件请求已释放")
        }
        if (preloadOnly) return TelegramFileRequestPriority.NEXT_PRELOAD
        return when (phase) {
            Phase.STARTUP -> TelegramFileRequestPriority.CURRENT_STARTUP
            Phase.SEEK -> TelegramFileRequestPriority.CURRENT_SEEK
            Phase.CONTINUATION -> TelegramFileRequestPriority.CURRENT_CONTINUATION
            Phase.CLOSED -> error("closed phase handled above")
        }
    }
}

internal data class FirstRangeReady(
    val fileId: Int,
    val priority: TelegramFileRequestPriority,
    val atNanos: Long,
)

internal class PlaybackRangeRegistration private constructor(
    private val onClose: () -> Unit,
    private val onPriorityChange: (TelegramFileRequestPriority) -> Unit,
    active: Boolean,
) {
    @Volatile
    var isActive: Boolean = active
        private set

    constructor(
        onClose: () -> Unit,
        onPriorityChange: (TelegramFileRequestPriority) -> Unit,
    ) : this(onClose, onPriorityChange, true)

    fun updatePriority(priority: TelegramFileRequestPriority) {
        if (isActive) onPriorityChange(priority)
    }

    fun cancel() {
        isActive = false
        onClose()
    }

    fun close() {
        if (!isActive) return
        isActive = false
        onClose()
    }

    companion object {
        fun inactive() = PlaybackRangeRegistration(
            onClose = {},
            onPriorityChange = {},
            active = false,
        )
    }
}

class TelegramMediaDataSourceException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class TelegramMediaTimeoutException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class TelegramMediaUnavailableException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class TelegramMediaReadException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
