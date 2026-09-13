package com.qixuan.channelvideoflow.telegram.client

import android.content.Context
import android.os.Build
import com.qixuan.channelvideoflow.telegram.auth.TdLibAuthorizationStateMapper
import com.qixuan.channelvideoflow.telegram.config.TelegramCredentials
import com.qixuan.channelvideoflow.telegram.config.TelegramCredentialsProvider
import com.qixuan.channelvideoflow.telegram.config.TelegramCredentialsResult
import com.qixuan.channelvideoflow.telegram.logging.AuthEventLogger
import com.qixuan.channelvideoflow.telegram.storage.TdLibDirectories
import com.qixuan.channelvideoflow.telegram.storage.SecureTdLibDatabaseKeyProvider
import com.qixuan.channelvideoflow.telegram.storage.TdLibDatabaseKeyProvider
import com.qixuan.channelvideoflow.telegram.storage.TdLibDatabaseKeyResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

@Singleton
internal class TelegramClientManager private constructor(
    private val credentialsProvider: TelegramCredentialsProvider,
    private val directories: TdLibDirectories,
    private val bridge: TdLibBridge,
    private val logger: AuthEventLogger,
    private val applicationInfo: TdLibApplicationInfo,
    private val dispatcher: CoroutineDispatcher,
    private val databaseKeyProvider: TdLibDatabaseKeyProvider,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) : TelegramAuthClient, TelegramChatClient, TelegramMessageClient, TelegramFileClient {
    constructor(
        @ApplicationContext context: Context,
        credentialsProvider: TelegramCredentialsProvider,
        bridge: TdLibBridge,
        logger: AuthEventLogger,
    ) : this(
        credentialsProvider = credentialsProvider,
        directories = TdLibDirectories(context),
        bridge = bridge,
        logger = logger,
        applicationInfo = AndroidTdLibApplicationInfo(context),
        dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cvf-tdlib-events")
        }.asCoroutineDispatcher(),
        databaseKeyProvider = SecureTdLibDatabaseKeyProvider(context),
        constructorMarker = Unit,
    )

    internal constructor(
        credentialsProvider: TelegramCredentialsProvider,
        directories: TdLibDirectories,
        bridge: TdLibBridge,
        logger: AuthEventLogger,
        applicationInfo: TdLibApplicationInfo,
        dispatcher: CoroutineDispatcher,
        databaseKeyProvider: TdLibDatabaseKeyProvider = TdLibDatabaseKeyProvider {
            TdLibDatabaseKeyResult.LegacyUnencrypted
        },
    ) : this(
        credentialsProvider,
        directories,
        bridge,
        logger,
        applicationInfo,
        dispatcher,
        databaseKeyProvider,
        Unit,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableEvents = MutableSharedFlow<TelegramClientEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<TelegramClientEvent> = mutableEvents.asSharedFlow()
    private val mutableChatEvents =
        MutableSharedFlow<TelegramChatClientEvent>(extraBufferCapacity = 64)
    override val chatEvents: SharedFlow<TelegramChatClientEvent> = mutableChatEvents.asSharedFlow()
    private val mutableMessageEvents =
        MutableSharedFlow<TelegramMessageClientEvent>(extraBufferCapacity = 256)
    override val messageEvents: SharedFlow<TelegramMessageClientEvent> =
        mutableMessageEvents.asSharedFlow()
    private val mutableFileControlEvents =
        MutableSharedFlow<TelegramFileClientEvent>(extraBufferCapacity = 16)
    private val mutableFileUpdateEvents =
        MutableSharedFlow<TelegramFileClientEvent>(extraBufferCapacity = 64)
    override val fileEvents = merge(mutableFileControlEvents, mutableFileUpdateEvents)
    private val fileUpdateConflator = FileUpdateConflator(scope) { snapshot ->
        mutableFileUpdateEvents.emit(TelegramFileClientEvent.FileUpdated(snapshot))
    }

    private var session: TdLibSession? = null
    private var sessionToken: SessionToken? = null
    private var generation = 0L
    private var lastParameterState: TdApi.AuthorizationStateWaitTdlibParameters? = null
    private var restartAfterClose = false

    override suspend fun start() = withContext(dispatcher) {
        startInternal()
    }

    override suspend fun restartAfterCredentialsChanged() = withContext(dispatcher) {
        val active = session
        val token = sessionToken
        if (active == null || token == null) {
            startInternal()
            return@withContext
        }
        if (restartAfterClose) return@withContext

        restartAfterClose = true
        logger.request(TelegramAuthRequest.CLOSE.name)
        try {
            active.send(TdApi.Close()) { result ->
                scope.launch { handleResult(token, TelegramAuthRequest.CLOSE, result) }
            }
        } catch (throwable: Throwable) {
            rethrowCancellation(throwable)
            restartAfterClose = false
            emitFatal(FatalCategory.INITIALIZATION)
        }
    }

    private suspend fun startInternal() {
        if (session != null) return
        val credentials = when (val result = credentialsProvider.get()) {
            is TelegramCredentialsResult.Available -> result.credentials
            is TelegramCredentialsResult.Unavailable -> {
                mutableEvents.emit(
                    TelegramClientEvent.CredentialsUnavailable(
                        invalidKeys = result.invalidKeys.toSet(),
                        reason = result.reason,
                    ),
                )
                return
            }
        }
        try {
            bridge.load()
        } catch (throwable: Throwable) {
            rethrowCancellation(throwable)
            emitFatal(FatalCategory.NATIVE_LIBRARY)
            return
        }
        try {
            bridge.configureLogHandler(logger)
            val nextGeneration = generation + 1
            val token = SessionToken(nextGeneration)
            val created = bridge.create(
                onUpdate = { update -> scope.launch { handleUpdate(token, update, credentials) } },
                onException = { scope.launch { handleException(token) } },
            )
            token.session = created
            generation = nextGeneration
            session = created
            sessionToken = token
            lastParameterState = null
        } catch (throwable: Throwable) {
            rethrowCancellation(throwable)
            emitFatal(FatalCategory.INITIALIZATION)
        }
    }

    override suspend fun submitPhoneNumber(phoneNumber: String) =
        send(TelegramAuthRequest.PHONE_NUMBER, TdApi.SetAuthenticationPhoneNumber(phoneNumber, null))

    override suspend fun submitCode(code: String) =
        send(TelegramAuthRequest.CODE, TdApi.CheckAuthenticationCode(code))

    override suspend fun resendCode() = send(
        TelegramAuthRequest.RESEND_CODE,
        TdApi.ResendAuthenticationCode(TdApi.ResendCodeReasonUserRequest()),
    )

    override suspend fun submitPassword(password: String) =
        send(TelegramAuthRequest.PASSWORD, TdApi.CheckAuthenticationPassword(password))

    override suspend fun logout() = send(TelegramAuthRequest.LOG_OUT, TdApi.LogOut())

    override suspend fun loadChats(
        chatList: TelegramClientChatList,
        limit: Int,
    ): TelegramLoadChatsResult = when (
        val result = execute(TdApi.LoadChats(chatList.toTdApi(), limit))
    ) {
        is TdLibQueryResult.Response -> when (val response = result.value) {
            is TdApi.Ok -> TelegramLoadChatsResult.Loaded
            is TdApi.Error -> if (response.code == 404) {
                TelegramLoadChatsResult.EndReached
            } else {
                TelegramLoadChatsResult.Failed(response.toClientFailure())
            }
            else -> TelegramLoadChatsResult.Failed(TelegramClientFailure.Unknown)
        }
        TdLibQueryResult.SessionUnavailable ->
            TelegramLoadChatsResult.Failed(TelegramClientFailure.SessionUnavailable)
        TdLibQueryResult.SendFailed ->
            TelegramLoadChatsResult.Failed(TelegramClientFailure.Unknown)
    }

    override suspend fun getChats(
        chatList: TelegramClientChatList,
        limit: Int,
    ): TelegramClientResult<TelegramClientChats> =
        execute(TdApi.GetChats(chatList.toTdApi(), limit)).mapResponse { response ->
            (response as? TdApi.Chats)?.let { chats ->
                TelegramClientChats(
                    totalCount = chats.totalCount,
                    chatIds = chats.chatIds.toList(),
                )
            }
        }

    override suspend fun getChat(chatId: Long): TelegramClientResult<TelegramClientChat> =
        execute(TdApi.GetChat(chatId)).mapResponse { response ->
            (response as? TdApi.Chat)?.let(TdLibChatObjectMapper::mapChat)
        }

    override suspend fun getSupergroup(
        supergroupId: Long,
    ): TelegramClientResult<TelegramClientSupergroup> =
        execute(TdApi.GetSupergroup(supergroupId)).mapResponse { response ->
            (response as? TdApi.Supergroup)?.let(TdLibChatObjectMapper::mapSupergroup)
        }

    override suspend fun searchChatVideos(
        chatId: Long,
        fromMessageId: Long,
        limit: Int,
    ): TelegramClientResult<TelegramClientVideoSearchPage> = execute(
        TdApi.SearchChatMessages(
            chatId,
            null,
            "",
            null,
            fromMessageId,
            0,
            limit,
            TdApi.SearchMessagesFilterVideo(),
        ),
    ).mapResponse { response ->
        (response as? TdApi.FoundChatMessages)?.let { messages ->
            TelegramClientVideoSearchPage(
                messages.messages.orEmpty().mapNotNull { message ->
                    message?.let(TdLibMessageObjectMapper::mapMessage)
                },
                approximateTotalCount = messages.totalCount.takeIf { it >= 0 },
                nextFromMessageId = messages.nextFromMessageId,
            )
        }
    }

    override suspend fun getMessage(
        chatId: Long,
        messageId: Long,
    ): TelegramClientResult<TelegramClientMessage> =
        execute(TdApi.GetMessage(chatId, messageId)).mapResponse { response ->
            (response as? TdApi.Message)?.let(TdLibMessageObjectMapper::mapMessage)
        }

    override suspend fun getMessageProperties(
        chatId: Long,
        messageId: Long,
    ): TelegramClientResult<TelegramClientMessageProperties> =
        execute(TdApi.GetMessageProperties(chatId, messageId)).mapResponse { response ->
            (response as? TdApi.MessageProperties)?.let { properties ->
                TelegramClientMessageProperties(canGetLink = properties.canGetLink)
            }
        }

    override suspend fun getMessageLink(
        chatId: Long,
        messageId: Long,
    ): TelegramClientResult<TelegramClientMessageLink> =
        execute(
            TdApi.GetMessageLink(
                chatId,
                messageId,
                0,
                0,
                "",
                false,
                false,
            ),
        ).mapResponse { response ->
            (response as? TdApi.MessageLink)
                ?.link
                ?.takeIf { link -> link.startsWith("https://", ignoreCase = true) }
                ?.let(::TelegramClientMessageLink)
        }

    override suspend fun downloadFile(
        fileId: Int,
        priority: Int,
        offset: Long,
        limit: Long,
    ): TelegramClientResult<TelegramClientFileSnapshot> =
        execute(TdApi.DownloadFile(fileId, priority, offset, limit, false)).mapResponse { response ->
            (response as? TdApi.File)?.let(TdLibFileObjectMapper::map)
        }

    override suspend fun cancelDownloadFile(fileId: Int): TelegramClientResult<Unit> =
        execute(TdApi.CancelDownloadFile(fileId, false)).mapResponse { response ->
            if (response is TdApi.Ok) Unit else null
        }

    override suspend fun getFile(
        fileId: Int,
    ): TelegramClientResult<TelegramClientFileSnapshot> =
        execute(TdApi.GetFile(fileId)).mapResponse { response ->
            (response as? TdApi.File)?.let(TdLibFileObjectMapper::map)
        }

    override suspend fun getFileDownloadedPrefixSize(
        fileId: Int,
        offset: Long,
    ): TelegramClientResult<Long> =
        execute(TdApi.GetFileDownloadedPrefixSize(fileId, offset)).mapResponse { response ->
            (response as? TdApi.FileDownloadedPrefixSize)?.size?.coerceAtLeast(0L)
        }

    override suspend fun deleteFile(fileId: Int): TelegramClientResult<Unit> =
        execute(TdApi.DeleteFile(fileId)).mapResponse { response ->
            if (response is TdApi.Ok) Unit else null
        }

    override suspend fun getStorageStatistics(): TelegramClientResult<TelegramClientStorageStatistics> =
        execute(TdApi.GetStorageStatistics(0)).mapResponse { response ->
            (response as? TdApi.StorageStatistics)?.toVideoStatistics()
        }

    override suspend fun optimizeVideoStorage(
        maxBytes: Long,
    ): TelegramClientResult<TelegramClientStorageStatistics> =
        execute(
            TdApi.OptimizeStorage(
                maxBytes,
                -1,
                -1,
                0,
                arrayOf(TdApi.FileTypeVideo()),
                longArrayOf(),
                longArrayOf(),
                false,
                0,
            ),
        ).mapResponse { response ->
            (response as? TdApi.StorageStatistics)?.toVideoStatistics()
        }

    private suspend fun send(request: TelegramAuthRequest, function: TdApi.Function<*>) =
        withContext(dispatcher) {
            val active = session ?: return@withContext
            val token = sessionToken ?: return@withContext
            logger.request(request.name)
            try {
                active.send(function) { result -> scope.launch { handleResult(token, request, result) } }
            } catch (throwable: Throwable) {
                rethrowCancellation(throwable)
                emitFatal(FatalCategory.INITIALIZATION)
            }
        }

    private suspend fun handleUpdate(
        token: SessionToken,
        update: TdApi.Object,
        credentials: TelegramCredentials,
    ) {
        if (!isCurrent(token)) return
        when (update) {
            is TdApi.UpdateAuthorizationState ->
                handleAuthorizationUpdate(token, update.authorizationState, credentials)
            is TdApi.UpdateNewChat -> mutableChatEvents.emit(
                TelegramChatClientEvent.ChatChanged(TdLibChatObjectMapper.mapChat(update.chat)),
            )
            is TdApi.UpdateChatTitle -> mutableChatEvents.emit(
                TelegramChatClientEvent.ChatTitleChanged(update.chatId, update.title),
            )
            is TdApi.UpdateSupergroup -> mutableChatEvents.emit(
                TelegramChatClientEvent.SupergroupChanged(
                    TdLibChatObjectMapper.mapSupergroup(update.supergroup),
                ),
            )
            is TdApi.UpdateNewMessage -> mutableMessageEvents.emit(
                TelegramMessageClientEvent.NewMessage(
                    TdLibMessageObjectMapper.mapMessage(update.message),
                ),
            )
            is TdApi.UpdateMessageContent -> mutableMessageEvents.emit(
                TelegramMessageClientEvent.MessageContentChanged(
                    chatId = update.chatId,
                    messageId = update.messageId,
                    video = TdLibMessageObjectMapper.mapVideoContent(update.newContent),
                ),
            )
            is TdApi.UpdateMessageEdited -> mutableMessageEvents.emit(
                TelegramMessageClientEvent.MessageEdited(
                    chatId = update.chatId,
                    messageId = update.messageId,
                    editTime = update.editDate.toLong().takeIf { it > 0 },
                ),
            )
            is TdApi.UpdateDeleteMessages -> mutableMessageEvents.emit(
                TelegramMessageClientEvent.MessagesDeleted(
                    chatId = update.chatId,
                    messageIds = update.messageIds.toList(),
                    fromCache = update.fromCache,
                ),
            )
            is TdApi.UpdateFile -> fileUpdateConflator.offer(TdLibFileObjectMapper.map(update.file))
        }
    }

    private suspend fun handleAuthorizationUpdate(
        token: SessionToken,
        state: TdApi.AuthorizationState,
        credentials: TelegramCredentials,
    ) {
        logger.state(stateName(state))
        mutableEvents.emit(TelegramClientEvent.AuthorizationStateChanged(TdLibAuthorizationStateMapper.map(state)))
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                if (lastParameterState === state) return
                sendParameters(token, state, credentials)
            }
            is TdApi.AuthorizationStateClosed -> {
                session = null
                sessionToken = null
                lastParameterState = null
                if (restartAfterClose) {
                    restartAfterClose = false
                    startInternal()
                }
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                mutableChatEvents.emit(TelegramChatClientEvent.AccountLoggingOut)
                mutableMessageEvents.emit(TelegramMessageClientEvent.AccountLoggingOut)
                fileUpdateConflator.advanceGeneration()
                mutableFileControlEvents.emit(TelegramFileClientEvent.AccountLoggingOut)
            }
            is TdApi.AuthorizationStateReady -> {
                fileUpdateConflator.advanceGeneration()
                mutableFileControlEvents.emit(TelegramFileClientEvent.Ready)
            }
        }
    }

    private suspend fun execute(function: TdApi.Function<*>): TdLibQueryResult =
        withContext(dispatcher) {
            val active = session ?: return@withContext TdLibQueryResult.SessionUnavailable
            val token = sessionToken ?: return@withContext TdLibQueryResult.SessionUnavailable

            suspendCancellableCoroutine<TdLibQueryResult> { continuation ->
                try {
                    active.send(function) { response ->
                        scope.launch {
                            val result = if (isCurrent(token)) {
                                TdLibQueryResult.Response(response)
                            } else {
                                TdLibQueryResult.SessionUnavailable
                            }
                            if (continuation.isActive) continuation.resume(result)
                        }
                    }
                } catch (throwable: Throwable) {
                    rethrowCancellation(throwable)
                    if (continuation.isActive) {
                        continuation.resume(TdLibQueryResult.SendFailed)
                    }
                }
            }
        }

    private fun <T> TdLibQueryResult.mapResponse(
        mapper: (TdApi.Object) -> T?,
    ): TelegramClientResult<T> = when (this) {
        is TdLibQueryResult.Response -> when (val response = value) {
            is TdApi.Error -> TelegramClientResult.Failure(response.toClientFailure())
            else -> mapper(response)
                ?.let(TelegramClientResult<T>::Success)
                ?: TelegramClientResult.Failure(TelegramClientFailure.Unknown)
        }
        TdLibQueryResult.SessionUnavailable ->
            TelegramClientResult.Failure(TelegramClientFailure.SessionUnavailable)
        TdLibQueryResult.SendFailed ->
            TelegramClientResult.Failure(TelegramClientFailure.Unknown)
    }

    private fun TdApi.Error.toClientFailure(): TelegramClientFailure {
        val floodWaitSeconds = FLOOD_WAIT_PATTERN.find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return when {
            floodWaitSeconds != null -> TelegramClientFailure.FloodWait(floodWaitSeconds)
            code == 403 || message.contains("CHAT_ACCESS", ignoreCase = true) ||
                message.contains("CHANNEL_PRIVATE", ignoreCase = true) ->
                TelegramClientFailure.AccessLost
            code == 404 -> TelegramClientFailure.NotFound
            code >= 500 -> TelegramClientFailure.NetworkUnavailable
            code > 0 -> TelegramClientFailure.RequestRejected(code)
            else -> TelegramClientFailure.Unknown
        }
    }

    private fun TelegramClientChatList.toTdApi(): TdApi.ChatList = when (this) {
        TelegramClientChatList.MAIN -> TdApi.ChatListMain()
        TelegramClientChatList.ARCHIVE -> TdApi.ChatListArchive()
    }

    private suspend fun sendParameters(
        token: SessionToken,
        state: TdApi.AuthorizationStateWaitTdlibParameters,
        credentials: TelegramCredentials,
    ) {
        if (!isCurrent(token)) return
        val active = session ?: return
        try {
            directories.ensureCreated()
        } catch (throwable: Throwable) {
            rethrowCancellation(throwable)
            emitFatal(FatalCategory.DATABASE)
            return
        }
        val tdLibDatabaseEncryptionKey = when (
            val keyResult = databaseKeyProvider.loadOrCreate(directories.databaseDirectory)
        ) {
            TdLibDatabaseKeyResult.LegacyUnencrypted -> byteArrayOf()
            is TdLibDatabaseKeyResult.Available -> keyResult.key
            TdLibDatabaseKeyResult.MigrationRequired,
            TdLibDatabaseKeyResult.Unavailable,
            -> {
                lastParameterState = state
                closeAfterDatabaseFailure(token)
                return
            }
        }
        val parameters = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = directories.databaseDirectory.absolutePath
            filesDirectory = directories.filesDirectory.absolutePath
            databaseEncryptionKey = tdLibDatabaseEncryptionKey
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = credentials.apiId
            apiHash = credentials.apiHash
            systemLanguageCode = applicationInfo.systemLanguageCode
            deviceModel = applicationInfo.deviceModel
            systemVersion = applicationInfo.systemVersion
            applicationVersion = applicationInfo.applicationVersion
        }
        logger.request(TelegramAuthRequest.PARAMETERS.name)
        try {
            active.send(parameters) { result ->
                scope.launch { handleResult(token, TelegramAuthRequest.PARAMETERS, result) }
            }
            lastParameterState = state
        } catch (throwable: Throwable) {
            rethrowCancellation(throwable)
            emitFatal(FatalCategory.INITIALIZATION)
        }
    }

    private suspend fun closeAfterDatabaseFailure(token: SessionToken) {
        emitFatal(FatalCategory.DATABASE)
        val active = session ?: return
        try {
            active.send(TdApi.Close()) { result ->
                if (result is TdApi.Error) {
                    scope.launch {
                        if (isCurrent(token)) logger.failure("DATABASE_CLOSE_FAILED", result.code)
                    }
                }
            }
        } catch (throwable: Throwable) {
            rethrowCancellation(throwable)
            logger.failure("DATABASE_CLOSE_FAILED", 0)
        }
    }

    private suspend fun handleResult(
        token: SessionToken,
        request: TelegramAuthRequest,
        result: TdApi.Object,
    ) {
        if (!isCurrent(token)) return
        if (result !is TdApi.Error) return
        logger.failure("REQUEST_FAILED", result.code)
        if (request == TelegramAuthRequest.PARAMETERS && result.code == INVALID_DATABASE_KEY_CODE) {
            closeAfterDatabaseFailure(token)
            return
        }
        if (request == TelegramAuthRequest.CLOSE) {
            restartAfterClose = false
            emitFatal(FatalCategory.INITIALIZATION)
            return
        }
        mutableEvents.emit(TelegramClientEvent.RequestFailed(request, result.code, result.message))
    }

    private suspend fun emitFatal(category: FatalCategory) {
        logger.failure(category.name, 0)
        mutableEvents.emit(TelegramClientEvent.FatalFailure(category))
    }

    private suspend fun handleException(token: SessionToken) {
        if (isCurrent(token)) emitFatal(FatalCategory.INITIALIZATION)
    }

    private fun isCurrent(token: SessionToken): Boolean =
        token === sessionToken && token.session === session && token.generation == generation

    private fun rethrowCancellation(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
    }

    private fun stateName(state: TdApi.AuthorizationState): String = when (state) {
        is TdApi.AuthorizationStateWaitTdlibParameters -> "WAIT_TDLIB_PARAMETERS"
        is TdApi.AuthorizationStateWaitPhoneNumber -> "WAIT_PHONE_NUMBER"
        is TdApi.AuthorizationStateWaitCode -> "WAIT_CODE"
        is TdApi.AuthorizationStateWaitPassword -> "WAIT_PASSWORD"
        is TdApi.AuthorizationStateReady -> "READY"
        is TdApi.AuthorizationStateLoggingOut -> "LOGGING_OUT"
        is TdApi.AuthorizationStateClosing -> "CLOSING"
        is TdApi.AuthorizationStateClosed -> "CLOSED"
        is TdApi.AuthorizationStateWaitPremiumPurchase -> "WAIT_PREMIUM_PURCHASE"
        is TdApi.AuthorizationStateWaitEmailAddress -> "WAIT_EMAIL_ADDRESS"
        is TdApi.AuthorizationStateWaitEmailCode -> "WAIT_EMAIL_CODE"
        is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> "WAIT_OTHER_DEVICE_CONFIRMATION"
        is TdApi.AuthorizationStateWaitRegistration -> "WAIT_REGISTRATION"
        else -> "UNSUPPORTED"
    }

    private class SessionToken(
        val generation: Long,
        var session: TdLibSession? = null,
    )

    private sealed interface TdLibQueryResult {
        data class Response(val value: TdApi.Object) : TdLibQueryResult
        data object SessionUnavailable : TdLibQueryResult
        data object SendFailed : TdLibQueryResult
    }

    private companion object {
        const val INVALID_DATABASE_KEY_CODE = 401
        val FLOOD_WAIT_PATTERN = Regex("FLOOD_WAIT_?(\\d+)", RegexOption.IGNORE_CASE)
    }
}

private fun TdApi.StorageStatistics.toVideoStatistics(): TelegramClientStorageStatistics {
    var bytes = 0L
    var count = 0
    byChat.orEmpty().forEach { chat ->
        chat.byFileType.orEmpty().forEach { byType ->
            if (byType.fileType is TdApi.FileTypeVideo) {
                bytes = bytes.saturatedAdd(byType.size.coerceAtLeast(0L))
                count += byType.count.coerceAtLeast(0)
            }
        }
    }
    return TelegramClientStorageStatistics(videoBytes = bytes, videoFileCount = count)
}

private fun Long.saturatedAdd(other: Long): Long =
    if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

internal interface TdLibApplicationInfo {
    val systemLanguageCode: String
    val deviceModel: String
    val systemVersion: String
    val applicationVersion: String
}

private class AndroidTdLibApplicationInfo(context: Context) : TdLibApplicationInfo {
    override val systemLanguageCode: String = Locale.getDefault().toLanguageTag()
    override val deviceModel: String = Build.MODEL
    override val systemVersion: String = Build.VERSION.RELEASE
    override val applicationVersion: String = context.packageManager
        .getPackageInfo(context.packageName, 0)
        .versionName
        ?: "unknown"
}
