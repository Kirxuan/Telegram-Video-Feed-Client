package com.qixuan.channelvideoflow.telegram.client

import com.qixuan.channelvideoflow.telegram.config.TelegramCredentials
import com.qixuan.channelvideoflow.telegram.config.TelegramCredentialsProvider
import com.qixuan.channelvideoflow.telegram.config.TelegramCredentialsResult
import com.qixuan.channelvideoflow.telegram.logging.AuthEventLogger
import com.qixuan.channelvideoflow.telegram.storage.TdLibDirectories
import com.qixuan.channelvideoflow.telegram.storage.TdLibDatabaseKeyProvider
import com.qixuan.channelvideoflow.telegram.storage.TdLibDatabaseKeyResult
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramClientManagerTest {
    @Test
    fun missingCredentialsEmitsUnavailableWithoutLoadingNative() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = manager(bridge, unavailableCredentials())
        val events = async(start = CoroutineStart.UNDISPATCHED) { manager.events.take(1).toList() }

        manager.start()

        assertEquals(
            listOf(TelegramClientEvent.CredentialsUnavailable(setOf("TELEGRAM_API_HASH"))),
            events.await(),
        )
        assertEquals(0, bridge.loadCalls)
        assertEquals(0, bridge.createCalls)
    }

    @Test
    fun repeatedStartCreatesOneSession() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = manager(bridge, availableCredentials())

        manager.start()
        manager.start()

        assertEquals(1, bridge.loadCalls)
        assertEquals(1, bridge.createCalls)
    }

    @Test
    fun credentialsChangeClosesCurrentSessionThenStartsWithFreshCredentials() = runTest {
        val bridge = FakeTdLibBridge()
        var activeCredentials = TelegramCredentials(12345, "first-synthetic-hash")
        val manager = manager(
            bridge = bridge,
            credentials = TelegramCredentialsProvider {
                TelegramCredentialsResult.Available(activeCredentials)
            },
        )
        manager.start()
        activeCredentials = TelegramCredentials(54321, "second-synthetic-hash")

        manager.restartAfterCredentialsChanged()

        assertTrue(bridge.session(0).sentFunctions.single() is TdApi.Close)
        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()))
        runCurrent()
        assertEquals(2, bridge.createCalls)

        bridge.emitUpdate(
            1,
            TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters()),
        )
        runCurrent()
        val parameters = bridge.session(1).sentFunctions.single() as TdApi.SetTdlibParameters
        assertEquals(54321, parameters.apiId)
        assertEquals("second-synthetic-hash", parameters.apiHash)
    }

    @Test
    fun waitParametersSendsExactlyOneParametersRequestForTheStateObject() = runTest {
        val bridge = FakeTdLibBridge()
        val directories = temporaryDirectories()
        val manager = manager(bridge, availableCredentials(), directories = directories)
        manager.start()
        val state = TdApi.AuthorizationStateWaitTdlibParameters()

        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(state))
        runCurrent()
        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(state))
        runCurrent()

        val parameters = bridge.session(0).sentFunctions.single() as TdApi.SetTdlibParameters
        assertFalse(parameters.useTestDc)
        assertEquals(directories.databaseDirectory.absolutePath, parameters.databaseDirectory)
        assertEquals(directories.filesDirectory.absolutePath, parameters.filesDirectory)
        assertArrayEquals(byteArrayOf(), parameters.databaseEncryptionKey)
        assertTrue(parameters.useFileDatabase)
        assertTrue(parameters.useChatInfoDatabase)
        assertTrue(parameters.useMessageDatabase)
        assertFalse(parameters.useSecretChats)
        assertEquals(12345, parameters.apiId)
        assertEquals("synthetic-hash", parameters.apiHash)
        assertEquals("en-US", parameters.systemLanguageCode)
        assertEquals("synthetic-device", parameters.deviceModel)
        assertEquals("synthetic-system", parameters.systemVersion)
        assertEquals("1.0-test", parameters.applicationVersion)
        assertEquals(1, bridge.sentFunctions.filterIsInstance<TdApi.SetTdlibParameters>().size)
    }

    @Test
    fun encryptionCandidatePassesTheWrappedDatabaseKeyToTdLibParameters() = runTest {
        val bridge = FakeTdLibBridge()
        val expectedKey = ByteArray(32) { index -> (index + 1).toByte() }
        val manager = manager(
            bridge = bridge,
            credentials = availableCredentials(),
            databaseKeyProvider = TdLibDatabaseKeyProvider {
                TdLibDatabaseKeyResult.Available(expectedKey)
            },
        )
        manager.start()

        bridge.emitUpdate(
            0,
            TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters()),
        )
        runCurrent()

        val parameters = bridge.sentFunctions.single() as TdApi.SetTdlibParameters
        assertArrayEquals(expectedKey, parameters.databaseEncryptionKey)
    }

    @Test
    fun migrationRequiredFailsClosedWithoutSendingParametersOrRetryingTheState() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = manager(
            bridge = bridge,
            credentials = availableCredentials(),
            databaseKeyProvider = TdLibDatabaseKeyProvider {
                TdLibDatabaseKeyResult.MigrationRequired
            },
        )
        val events = recordEvents(manager)
        manager.start()
        val state = TdApi.AuthorizationStateWaitTdlibParameters()

        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(state))
        runCurrent()
        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(state))
        runCurrent()

        assertTrue(events.contains(TelegramClientEvent.FatalFailure(FatalCategory.DATABASE)))
        assertTrue(bridge.sentFunctions.none { it is TdApi.SetTdlibParameters })
        assertEquals(1, bridge.sentFunctions.count { it is TdApi.Close })
    }

    @Test
    fun rejectedDatabaseKeyIsClassifiedAsDatabaseFailureAndClosesTheSession() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = manager(
            bridge = bridge,
            credentials = availableCredentials(),
            databaseKeyProvider = TdLibDatabaseKeyProvider {
                TdLibDatabaseKeyResult.Available(ByteArray(32) { 7 })
            },
        )
        val events = recordEvents(manager)
        manager.start()
        bridge.emitUpdate(
            0,
            TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateWaitTdlibParameters()),
        )
        runCurrent()
        val parameters = bridge.sentFunctions.single() as TdApi.SetTdlibParameters

        bridge.complete(0, parameters, TdApi.Error(401, "synthetic invalid database key"))
        runCurrent()

        assertTrue(events.contains(TelegramClientEvent.FatalFailure(FatalCategory.DATABASE)))
        assertTrue(events.none { it is TelegramClientEvent.RequestFailed })
        assertEquals(1, bridge.sentFunctions.count { it is TdApi.Close })
    }

    @Test
    fun aNewSessionSendsParametersAgainForTheSameStateObject() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = manager(bridge, availableCredentials())
        val state = TdApi.AuthorizationStateWaitTdlibParameters()
        manager.start()
        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(state))
        runCurrent()
        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()))
        runCurrent()

        manager.start()
        bridge.emitUpdate(1, TdApi.UpdateAuthorizationState(state))
        runCurrent()

        assertEquals(2, bridge.createCalls)
        assertEquals(2, bridge.sentFunctions.filterIsInstance<TdApi.SetTdlibParameters>().size)
    }

    @Test
    fun submitPhoneNumberSendsExactTdLibFunction() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)

        manager.submitPhoneNumber("synthetic-phone")

        val function = bridge.sentFunctions.single() as TdApi.SetAuthenticationPhoneNumber
        assertEquals("synthetic-phone", function.phoneNumber)
        assertEquals(null, function.settings)
    }

    @Test
    fun submitCodeSendsTdLibCodeFunction() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)

        manager.submitCode("synthetic-code")

        assertEquals("synthetic-code", (bridge.sentFunctions.single() as TdApi.CheckAuthenticationCode).code)
    }

    @Test
    fun resendCodeSendsUserRequestedTdLibFunction() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)

        manager.resendCode()

        val function = bridge.sentFunctions.single() as TdApi.ResendAuthenticationCode
        assertTrue(function.reason is TdApi.ResendCodeReasonUserRequest)
    }

    @Test
    fun submitPasswordSendsTdLibPasswordFunction() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)

        manager.submitPassword("synthetic-password")

        assertEquals(
            "synthetic-password",
            (bridge.sentFunctions.single() as TdApi.CheckAuthenticationPassword).password,
        )
    }

    @Test
    fun logoutSendsTdLibLogOutFunction() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)

        manager.logout()

        assertTrue(bridge.sentFunctions.single() is TdApi.LogOut)
    }

    @Test
    fun tdLibErrorEmitsRequestFailureWithoutRawMessageLogging() = runTest {
        val bridge = FakeTdLibBridge()
        val logger = RecordingLogger()
        val manager = manager(bridge, availableCredentials(), logger)
        val events = async(start = CoroutineStart.UNDISPATCHED) { manager.events.take(1).toList() }
        manager.start()
        manager.submitCode("synthetic-code")
        val request = bridge.sentFunctions.single()

        bridge.complete(0, request, TdApi.Error(400, "synthetic sensitive detail"))
        val event = events.await().single() as TelegramClientEvent.RequestFailed

        assertEquals(TelegramAuthRequest.CODE, event.request)
        assertEquals(400, event.code)
        assertFalse(logger.values.any { it.contains("synthetic sensitive detail") })
        assertFalse(event.toString().contains("synthetic sensitive detail"))
    }

    @Test
    fun closedSessionIsClearedBeforeTheNextStart() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = manager(bridge, availableCredentials())
        manager.start()

        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()))
        runCurrent()
        manager.start()

        assertEquals(2, bridge.createCalls)
    }

    @Test
    fun staleRequestResultFromClosedSessionDoesNotEmitIntoNewSession() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = manager(bridge, availableCredentials())
        manager.start()
        manager.submitCode("synthetic-code")
        val oldRequest = bridge.session(0).sentFunctions.single()
        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()))
        runCurrent()
        manager.start()
        val events = recordEvents(manager)

        bridge.complete(0, oldRequest, TdApi.Error(400, "synthetic stale detail"))
        runCurrent()

        assertTrue(events.isEmpty())
    }

    @Test
    fun staleExceptionFromClosedSessionDoesNotEmitIntoNewSession() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = manager(bridge, availableCredentials())
        manager.start()
        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateClosed()))
        runCurrent()
        manager.start()
        val events = recordEvents(manager)

        bridge.failCallback(0)
        runCurrent()

        assertTrue(events.isEmpty())
    }

    @Test
    fun sameParametersStateRetriesAfterDirectoryFailureThenDeduplicatesAfterSuccess() = runTest {
        val root = Files.createTempDirectory("cvf-tdlib-retry").toFile()
        val blockingRoot = root.resolve("blocking").apply { writeText("not a directory") }
        val directories = TdLibDirectories.forTest(blockingRoot, root.resolve("cache"))
        val bridge = FakeTdLibBridge()
        val manager = manager(bridge, availableCredentials(), directories = directories)
        val events = recordEvents(manager)
        val state = TdApi.AuthorizationStateWaitTdlibParameters()
        manager.start()

        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(state))
        runCurrent()
        assertTrue(events.any { it == TelegramClientEvent.FatalFailure(FatalCategory.DATABASE) })
        assertTrue(blockingRoot.delete())

        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(state))
        runCurrent()
        bridge.emitUpdate(0, TdApi.UpdateAuthorizationState(state))
        runCurrent()

        assertEquals(1, bridge.session(0).sentFunctions.filterIsInstance<TdApi.SetTdlibParameters>().size)
    }

    @Test
    fun loadChatsMapsTdLibEndOfListWithoutTreatingItAsAnError() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val result = async { manager.loadChats(TelegramClientChatList.ARCHIVE, 100) }
        runCurrent()
        val request = bridge.sentFunctions.single()

        bridge.complete(0, request, TdApi.Error(404, "synthetic end of list"))
        runCurrent()

        assertTrue(request is TdApi.LoadChats)
        assertTrue((request as TdApi.LoadChats).chatList is TdApi.ChatListArchive)
        assertEquals(TelegramLoadChatsResult.EndReached, result.await())
    }

    @Test
    fun getChatMapsRawTdLibTypeBeforeLeavingTheClientBoundary() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val result = async { manager.getChat(77) }
        runCurrent()
        val request = bridge.sentFunctions.single()
        val chat = TdApi.Chat().apply {
            id = 77
            title = "真实频道"
            type = TdApi.ChatTypeSupergroup(700, true)
        }

        bridge.complete(0, request, chat)
        runCurrent()

        assertEquals(
            TelegramClientResult.Success(
                TelegramClientChat(
                    chatId = 77,
                    title = "真实频道",
                    type = TelegramClientChatType.Supergroup(700, true),
                ),
            ),
            result.await(),
        )
    }

    @Test
    fun chatTitleAndMembershipUpdatesAreMappedToSanitizedEvents() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            manager.chatEvents.take(2).toList()
        }
        val supergroup = TdApi.Supergroup().apply {
            id = 700
            isChannel = true
            usernames = null
            status = TdApi.ChatMemberStatusLeft()
        }

        bridge.emitUpdate(0, TdApi.UpdateChatTitle(77, "更新后标题"))
        bridge.emitUpdate(0, TdApi.UpdateSupergroup(supergroup))
        runCurrent()

        assertEquals(
            listOf(
                TelegramChatClientEvent.ChatTitleChanged(77, "更新后标题"),
                TelegramChatClientEvent.SupergroupChanged(
                    TelegramClientSupergroup(
                        supergroupId = 700,
                        isChannel = true,
                        username = null,
                        memberStatus = TelegramClientMemberStatus.Left,
                    ),
                ),
            ),
            events.await(),
        )
    }

    @Test
    fun searchChatVideosUsesExactFilterParametersAndMapsNextCursorWithoutDownload() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val result = async { manager.searchChatVideos(77, 900, 100) }
        runCurrent()
        val request = bridge.sentFunctions.single()
        val message = TdApi.Message().apply {
            id = 899
            chatId = 77
            date = 123
            canBeSaved = true
            content = TdApi.MessageText(TdApi.FormattedText("非视频", emptyArray()), null, null)
        }

        bridge.complete(0, request, TdApi.FoundChatMessages(12, arrayOf(message), 840))
        runCurrent()

        assertTrue(request is TdApi.SearchChatMessages)
        request as TdApi.SearchChatMessages
        assertEquals(77, request.chatId)
        assertEquals(null, request.topicId)
        assertEquals("", request.query)
        assertEquals(null, request.senderId)
        assertEquals(900, request.fromMessageId)
        assertEquals(0, request.offset)
        assertEquals(100, request.limit)
        assertTrue(request.filter is TdApi.SearchMessagesFilterVideo)
        val page = (result.await() as TelegramClientResult.Success).value
        assertEquals(899, page.messages.single().messageId)
        assertEquals(12, page.approximateTotalCount)
        assertEquals(840, page.nextFromMessageId)
        assertTrue(bridge.sentFunctions.none { it is TdApi.DownloadFile })
    }

    @Test
    fun searchChatVideosMapsUnknownApproximateCountToNullAndFinalCursorToZero() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val result = async { manager.searchChatVideos(77, 0, 100) }
        runCurrent()
        val request = bridge.sentFunctions.single()

        bridge.complete(0, request, TdApi.FoundChatMessages(-1, emptyArray(), 0))
        runCurrent()

        val page = (result.await() as TelegramClientResult.Success).value
        assertEquals(null, page.approximateTotalCount)
        assertEquals(0, page.nextFromMessageId)
    }

    @Test
    fun messageLinkUsesOfficialPropertiesThenHttpsLinkFunctions() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val propertiesResult = async { manager.getMessageProperties(77, 900) }
        runCurrent()
        val propertiesRequest = bridge.sentFunctions.single() as TdApi.GetMessageProperties
        assertEquals(77, propertiesRequest.chatId)
        assertEquals(900, propertiesRequest.messageId)
        bridge.complete(
            0,
            propertiesRequest,
            TdApi.MessageProperties().apply { canGetLink = true },
        )
        runCurrent()
        assertEquals(
            TelegramClientResult.Success(TelegramClientMessageProperties(true)),
            propertiesResult.await(),
        )

        val linkResult = async { manager.getMessageLink(77, 900) }
        runCurrent()
        val linkRequest = bridge.sentFunctions.last() as TdApi.GetMessageLink
        assertEquals(77, linkRequest.chatId)
        assertEquals(900, linkRequest.messageId)
        assertEquals(0, linkRequest.mediaTimestamp)
        assertFalse(linkRequest.forAlbum)
        bridge.complete(0, linkRequest, TdApi.MessageLink("https://t.me/c/77/900", true))
        runCurrent()
        assertEquals(
            TelegramClientResult.Success(TelegramClientMessageLink("https://t.me/c/77/900")),
            linkResult.await(),
        )
    }

    @Test
    fun messageUpdatesAreMappedBeforeLeavingTheClientBoundary() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            manager.messageEvents.take(4).toList()
        }
        val message = TdApi.Message().apply {
            id = 10
            chatId = 1
            date = 100
            content = TdApi.MessageText(TdApi.FormattedText("文本", emptyArray()), null, null)
        }

        bridge.emitUpdate(0, TdApi.UpdateNewMessage(message))
        bridge.emitUpdate(0, TdApi.UpdateMessageContent(1, 10, message.content))
        bridge.emitUpdate(0, TdApi.UpdateMessageEdited(1, 10, 101, null))
        bridge.emitUpdate(0, TdApi.UpdateDeleteMessages(1, longArrayOf(10), true, false))
        runCurrent()

        assertEquals(
            listOf(
                TelegramMessageClientEvent.NewMessage(
                    TelegramClientMessage(1, 10, 100, null, false, null),
                ),
                TelegramMessageClientEvent.MessageContentChanged(1, 10, null),
                TelegramMessageClientEvent.MessageEdited(1, 10, 101),
                TelegramMessageClientEvent.MessagesDeleted(1, listOf(10), false),
            ),
            events.await(),
        )
    }

    @Test
    fun downloadFileUsesOfficialOffsetLimitAndMapsPrivateFileState() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val result = async { manager.downloadFile(501, 32, 1_024, 262_144) }
        runCurrent()
        val request = bridge.sentFunctions.single() as TdApi.DownloadFile

        assertEquals(501, request.fileId)
        assertEquals(32, request.priority)
        assertEquals(1_024, request.offset)
        assertEquals(262_144, request.limit)
        assertFalse(request.synchronous)

        bridge.complete(
            0,
            request,
            TdApi.File().apply {
                id = 501
                size = 1_000_000
                expectedSize = 1_000_000
                local = TdApi.LocalFile().apply {
                    path = "private-file"
                    canBeDownloaded = true
                    isDownloadingActive = true
                    downloadOffset = 1_024
                    downloadedPrefixSize = 4_096
                    downloadedSize = 4_096
                }
            },
        )
        runCurrent()

        val snapshot = (result.await() as TelegramClientResult.Success).value
        assertEquals("private-file", snapshot.localPath)
        assertEquals(1_024, snapshot.downloadOffset)
        assertEquals(4_096, snapshot.downloadedPrefixSize)
    }

    @Test
    fun downloadedPrefixQueryUsesOfficialOffsetAndMapsSize() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val result = async { manager.getFileDownloadedPrefixSize(501, 9_000L) }
        runCurrent()
        val request = bridge.sentFunctions.single() as TdApi.GetFileDownloadedPrefixSize

        assertEquals(501, request.fileId)
        assertEquals(9_000L, request.offset)
        bridge.complete(0, request, TdApi.FileDownloadedPrefixSize(262_144L))
        runCurrent()

        assertEquals(TelegramClientResult.Success(262_144L), result.await())
    }

    @Test
    fun cancelDownloadFileUsesOfficialCancellationFunction() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val result = async { manager.cancelDownloadFile(501) }
        runCurrent()
        val request = bridge.sentFunctions.single() as TdApi.CancelDownloadFile

        assertEquals(501, request.fileId)
        assertFalse(request.onlyIfPending)
        bridge.complete(0, request, TdApi.Ok())
        runCurrent()
        assertEquals(TelegramClientResult.Success(Unit), result.await())
    }

    @Test
    fun exactStorageStatisticsSumOnlyOfficialVideoFileTypes() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val result = async { manager.getStorageStatistics() }
        runCurrent()
        val request = bridge.sentFunctions.single() as TdApi.GetStorageStatistics

        assertEquals(0, request.chatLimit)
        bridge.complete(
            0,
            request,
            TdApi.StorageStatistics(
                999,
                3,
                arrayOf(
                    TdApi.StorageStatisticsByChat(
                        1,
                        999,
                        3,
                        arrayOf(
                            TdApi.StorageStatisticsByFileType(TdApi.FileTypeVideo(), 700, 2),
                            TdApi.StorageStatisticsByFileType(TdApi.FileTypePhoto(), 299, 1),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()

        assertEquals(
            TelegramClientResult.Success(TelegramClientStorageStatistics(700, 2)),
            result.await(),
        )
    }

    @Test
    fun deleteAndOptimizeUseOfficialVideoOnlyFunctions() = runTest {
        val bridge = FakeTdLibBridge()
        val manager = startedManager(bridge)
        val deleteResult = async { manager.deleteFile(42) }
        runCurrent()
        val delete = bridge.sentFunctions.single() as TdApi.DeleteFile
        assertEquals(42, delete.fileId)
        bridge.complete(0, delete, TdApi.Ok())
        runCurrent()
        assertEquals(TelegramClientResult.Success(Unit), deleteResult.await())

        val optimizeResult = async { manager.optimizeVideoStorage(500) }
        runCurrent()
        val optimize = bridge.sentFunctions.last() as TdApi.OptimizeStorage
        assertEquals(500, optimize.size)
        assertEquals(0, optimize.immunityDelay)
        assertEquals(1, optimize.fileTypes.size)
        assertTrue(optimize.fileTypes.single() is TdApi.FileTypeVideo)
        assertTrue(optimize.chatIds.isEmpty())
        assertTrue(optimize.excludeChatIds.isEmpty())
        bridge.complete(
            0,
            optimize,
            TdApi.StorageStatistics(0, 0, emptyArray<TdApi.StorageStatisticsByChat>()),
        )
        runCurrent()
        assertEquals(
            TelegramClientResult.Success(TelegramClientStorageStatistics(0, 0)),
            optimizeResult.await(),
        )
    }

    private suspend fun TestScope.startedManager(bridge: FakeTdLibBridge): TelegramClientManager =
        manager(bridge, availableCredentials()).also { it.start() }

    private fun TestScope.manager(
        bridge: FakeTdLibBridge,
        credentials: TelegramCredentialsProvider,
        logger: AuthEventLogger = RecordingLogger(),
        directories: TdLibDirectories = temporaryDirectories(),
        databaseKeyProvider: TdLibDatabaseKeyProvider = TdLibDatabaseKeyProvider {
            TdLibDatabaseKeyResult.LegacyUnencrypted
        },
    ): TelegramClientManager = TelegramClientManager(
        credentialsProvider = credentials,
        directories = directories,
        bridge = bridge,
        logger = logger,
        applicationInfo = FakeApplicationInfo,
        dispatcher = UnconfinedTestDispatcher(testScheduler),
        databaseKeyProvider = databaseKeyProvider,
    )

    private fun TestScope.recordEvents(
        manager: TelegramClientManager,
    ): MutableList<TelegramClientEvent> = mutableListOf<TelegramClientEvent>().also { events ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.events.collect(events::add)
        }
    }

    private fun temporaryDirectories(): TdLibDirectories {
        val root = Files.createTempDirectory("cvf-tdlib-test").toFile()
        return TdLibDirectories.forTest(root.resolve("no-backup"), root.resolve("cache"))
    }

    private fun availableCredentials(): TelegramCredentialsProvider = TelegramCredentialsProvider {
        TelegramCredentialsResult.Available(TelegramCredentials(12345, "synthetic-hash"))
    }

    private fun unavailableCredentials(): TelegramCredentialsProvider = TelegramCredentialsProvider {
        TelegramCredentialsResult.Unavailable(setOf("TELEGRAM_API_HASH"))
    }

    private object FakeApplicationInfo : TdLibApplicationInfo {
        override val systemLanguageCode = "en-US"
        override val deviceModel = "synthetic-device"
        override val systemVersion = "synthetic-system"
        override val applicationVersion = "1.0-test"
    }

    private class RecordingLogger : AuthEventLogger {
        val values = mutableListOf<String>()

        override fun state(name: String) {
            values += name
        }

        override fun request(name: String) {
            values += name
        }

        override fun failure(category: String, code: Int) {
            values += "$category:$code"
        }

        override fun nativeLevel(level: Int) {
            values += level.toString()
        }
    }
}
