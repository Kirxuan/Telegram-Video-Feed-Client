package com.qixuan.channelvideoflow.feature.auth

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qixuan.channelvideoflow.R
import com.qixuan.channelvideoflow.model.auth.TelegramAuthFailure
import com.qixuan.channelvideoflow.model.auth.TelegramCodeDeliveryType
import com.qixuan.channelvideoflow.model.auth.TelegramCodeInfo
import com.qixuan.channelvideoflow.model.auth.TelegramUnsupportedAuthStep
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun englishLocaleUsesEnglishNameAndSlogan() {
        val localizedContext = localizedContext("en-US")
        val state = mutableStateOf(LoginUiState(LoginStep.INITIALIZING))
        render(state, localizedContext = localizedContext)

        assertEquals("VELORA", localizedContext.getString(R.string.app_name))
        assertEquals("Kirxuan", localizedContext.getString(R.string.creator_name))
        composeRule.onNodeWithText("VELORA").assertIsDisplayed()
        composeRule.onNodeWithText("VELORA — Let Content Flow.").assertIsDisplayed()
        composeRule.onNodeWithText("Created by Kirxuan").assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTestTags.Creator).assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTestTags.BrandName).assertHeightIsAtLeast(28.dp)
        assertNoText("曜流")
        assertNoText("曜流，让精彩自然流动")
        assertNoText("创造者：麒轩")
    }

    @Test
    fun chineseLocaleUsesChineseNameAndSlogan() {
        val localizedContext = localizedContext("zh-CN")
        val state = mutableStateOf(LoginUiState(LoginStep.INITIALIZING))
        render(state, localizedContext = localizedContext)

        assertEquals("曜流", localizedContext.getString(R.string.app_name))
        assertEquals("麒轩", localizedContext.getString(R.string.creator_name))
        composeRule.onNodeWithText("曜流").assertIsDisplayed()
        composeRule.onNodeWithText("曜流，让精彩自然流动").assertIsDisplayed()
        composeRule.onNodeWithText("创造者：麒轩").assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTestTags.Creator).assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTestTags.BrandName).assertHeightIsAtLeast(28.dp)
        assertNoText("VELORA")
        assertNoText("VELORA — Let Content Flow.")
        assertNoText("Created by Kirxuan")
    }

    @Test
    fun traditionalChineseLocaleUsesChineseNameAndSlogan() {
        val localizedContext = localizedContext("zh-HK")
        val state = mutableStateOf(LoginUiState(LoginStep.INITIALIZING))
        render(state, localizedContext = localizedContext)

        assertEquals("曜流", localizedContext.getString(R.string.app_name))
        assertEquals("麒轩", localizedContext.getString(R.string.creator_name))
        composeRule.onNodeWithText("曜流").assertIsDisplayed()
        composeRule.onNodeWithText("曜流，让精彩自然流动").assertIsDisplayed()
        composeRule.onNodeWithText("创造者：麒轩").assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTestTags.Creator).assertIsDisplayed()
        composeRule.onNodeWithTag(LoginTestTags.BrandName).assertHeightIsAtLeast(28.dp)
        assertNoText("VELORA")
        assertNoText("VELORA — Let Content Flow.")
        assertNoText("Created by Kirxuan")
    }

    @Test
    fun phoneStepShowsOnlyPhoneInputAndSubmit() {
        val state = mutableStateOf(LoginUiState(LoginStep.PHONE_NUMBER, input = "synthetic-phone"))
        render(state)

        composeRule.onNodeWithText("手机号").assertIsDisplayed()
        composeRule.onNodeWithTag("login-input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("login-submit").assertIsDisplayed()
        assertNoText("验证码")
        assertNoText("两步验证密码")
    }

    @Test
    fun codeStepShowsOnlyCodeInputAndSubmit() {
        val state = mutableStateOf(
            LoginUiState(
                LoginStep.CODE,
                input = "synthetic-code",
                codeInfo = TelegramCodeInfo(
                    deliveryType = TelegramCodeDeliveryType.TELEGRAM_MESSAGE,
                    nextDeliveryType = TelegramCodeDeliveryType.SMS,
                    resendTimeoutSeconds = 60,
                ),
                resendSecondsRemaining = 60,
            ),
        )
        render(state)

        composeRule.onNodeWithText("验证码").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("验证码已发送至：另一台已登录设备的 Telegram 服务消息").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("60 秒后可改用：短信").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("login-resend-code").assertIsNotEnabled()
        composeRule.onNodeWithTag("login-input").performScrollTo().assertIsDisplayed()
        assertNoText("手机号")
        assertNoText("两步验证密码")
    }

    @Test
    fun passwordStepExposesOnlyTheTaggedPasswordField() {
        val state = mutableStateOf(LoginUiState(LoginStep.PASSWORD, input = "synthetic-password"))
        render(state)

        composeRule.onNodeWithText("两步验证密码").assertIsDisplayed()
        composeRule.onNodeWithTag("login-password-input").assertIsDisplayed()
        assertNoText("手机号")
        assertNoText("验证码")
    }

    @Test
    fun nonEditableTransitionsShowProgressAndNoInputTags() {
        val state = mutableStateOf(LoginUiState(LoginStep.INITIALIZING))
        render(state)

        listOf(
            LoginUiState(LoginStep.INITIALIZING) to "初始化中",
            LoginUiState(LoginStep.LOGGING_OUT) to "退出中",
            LoginUiState(LoginStep.CLOSING) to "关闭中",
        ).forEach { (nextState, status) ->
            state.value = nextState

            composeRule.onNodeWithText(status).assertIsDisplayed()
            composeRule.onNodeWithTag("login-progress").performScrollTo().assertIsDisplayed()
            assertNoTag("login-input")
            assertNoTag("login-password-input")
        }
    }

    @Test
    fun submittingInputShowsProgressAndDisablesSubmit() {
        val state = mutableStateOf(LoginUiState(LoginStep.CODE, input = "synthetic-code", isSubmitting = true))
        render(state)

        composeRule.onNodeWithTag("login-progress").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("login-submit").assertIsNotEnabled()
    }

    @Test
    fun authorizedShowsOnlyAuthorizedStatusAndLogout() {
        val state = mutableStateOf(LoginUiState(LoginStep.AUTHORIZED))
        render(state)

        composeRule.onNodeWithText("已登录").assertIsDisplayed()
        composeRule.onNodeWithTag("login-logout").assertIsDisplayed()
        assertNoTag("login-input")
        assertNoTag("login-password-input")
        assertNoText("频道")
        assertNoText("信息流")
    }

    @Test
    fun unconfiguredShowsCredentialInputsAndSanitizedValidation() {
        val state = mutableStateOf(
            LoginUiState(
                LoginStep.UNCONFIGURED,
                invalidKeys = setOf("TELEGRAM_API_HASH", "TELEGRAM_API_ID"),
            ),
        )
        render(state)

        composeRule.onNodeWithText("配置你自己的 Telegram API").assertIsDisplayed()
        composeRule.onNodeWithTag("login-credential-api-id").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("login-credential-api-hash").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "API ID 应为正整数；API Hash 应为 32 位十六进制字符。",
        ).performScrollTo().assertIsDisplayed()
        assertNoTag("login-input")
        assertNoTag("login-password-input")
    }

    @Test
    fun configuredCredentialInputsEmitOneIntentWithoutDisplayingHash() {
        var apiId = ""
        var apiHash = ""
        var configureCalls = 0
        val state = mutableStateOf(
            LoginUiState(
                step = LoginStep.UNCONFIGURED,
                credentialApiId = "12345",
                credentialApiHash = "0123456789abcdef0123456789abcdef",
            ),
        )
        render(
            state = state,
            onCredentialApiIdChanged = {
                apiId = it
                state.value = state.value.copy(credentialApiId = it)
            },
            onCredentialApiHashChanged = {
                apiHash = it
                state.value = state.value.copy(credentialApiHash = it)
            },
            onConfigureCredentials = { configureCalls += 1 },
        )

        composeRule.onNodeWithTag("login-credential-api-id").performTextReplacement("54321")
        composeRule.onNodeWithTag("login-credential-api-hash").performTextReplacement(
            "fedcba9876543210fedcba9876543210",
        )
        composeRule.onNodeWithTag("login-configure-credentials").performScrollTo().performClick()

        assertEquals("54321", apiId)
        assertEquals("fedcba9876543210fedcba9876543210", apiHash)
        assertEquals(1, configureCalls)
        assertNoText("0123456789abcdef0123456789abcdef")
    }

    @Test
    fun unsupportedAndFatalShowSanitizedMessagesWithoutRetry() {
        val state = mutableStateOf(LoginUiState(LoginStep.UNSUPPORTED, unsupportedStep = TelegramUnsupportedAuthStep.EMAIL_CODE))
        render(state)

        composeRule.onNodeWithText("不支持当前授权步骤").assertIsDisplayed()
        assertNoTag("login-retry")

        state.value = LoginUiState(LoginStep.UNSUPPORTED, unsupportedStep = TelegramUnsupportedAuthStep.REGISTRATION)
        composeRule.onNodeWithText("新账号注册不支持").assertIsDisplayed()
        assertNoTag("login-retry")

        state.value = LoginUiState(LoginStep.FATAL_ERROR, failure = TelegramAuthFailure.RequestRejected(429))
        composeRule.onNodeWithText("请求被拒绝：429").assertIsDisplayed()
        assertNoTag("login-retry")
    }

    @Test
    fun initialStartFailureExposesTheOnlyRetryCallback() {
        var retryCalls = 0
        val state = mutableStateOf(LoginUiState(LoginStep.INITIALIZING, failure = TelegramAuthFailure.Unknown))
        render(state, onRetry = { retryCalls += 1 })

        composeRule.onNodeWithText("未知错误").assertIsDisplayed()
        composeRule.onNodeWithTag("login-retry").performClick()

        assertEquals(1, retryCalls)
    }

    @Test
    fun retryingInitialStartShowsProgressAndDisablesRetry() {
        val state = mutableStateOf(
            LoginUiState(
                LoginStep.INITIALIZING,
                failure = TelegramAuthFailure.Unknown,
                isSubmitting = true,
            ),
        )
        render(state)

        composeRule.onNodeWithTag("login-progress").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("login-retry").assertIsNotEnabled()
    }

    @Test
    fun failuresUseFixedMessagesWithOnlyPermittedNumbers() {
        val state = mutableStateOf(LoginUiState(LoginStep.CODE, input = "synthetic-code"))
        render(state)
        val cases = listOf(
            FailureCase(TelegramAuthFailure.InvalidPhoneNumber, "手机号错误"),
            FailureCase(TelegramAuthFailure.InvalidCode, "验证码错误"),
            FailureCase(TelegramAuthFailure.InvalidPassword, "密码错误"),
            FailureCase(TelegramAuthFailure.NetworkUnavailable, "网络错误"),
            FailureCase(TelegramAuthFailure.FloodWait(7), "请在 7 秒后重试", 7),
            FailureCase(TelegramAuthFailure.RequestRejected(429), "请求被拒绝：429"),
            FailureCase(TelegramAuthFailure.NativeLibraryLoadFailed, "原生库加载失败"),
            FailureCase(TelegramAuthFailure.TdLibInitializationFailed, "TDLib 初始化失败"),
            FailureCase(TelegramAuthFailure.DatabaseFailed, "数据库错误"),
            FailureCase(TelegramAuthFailure.Unknown, "未知错误"),
        )

        cases.forEach { case ->
            state.value = LoginUiState(
                LoginStep.CODE,
                input = "synthetic-code",
                failure = case.failure,
                retrySecondsRemaining = case.retrySecondsRemaining,
            )
            composeRule.onNodeWithText(case.expectedText).assertIsDisplayed()
        }
    }

    @Test
    fun inputAndEnabledSubmitEmitExactlyOneIntent() {
        var changedInput = ""
        var submitCalls = 0
        val state = mutableStateOf(LoginUiState(LoginStep.PHONE_NUMBER, input = "synthetic"))
        render(
            state,
            onInputChanged = { changedInput = it },
            onSubmit = { submitCalls += 1 },
        )

        composeRule.onNodeWithTag("login-input").performTextReplacement("synthetic-field")
        composeRule.onNodeWithTag("login-submit").performClick()

        assertEquals("synthetic-field", changedInput)
        assertEquals(1, submitCalls)
    }

    @Test
    fun authorizedLogoutCallbackFiresOnceWhileEnabled() {
        var logoutCalls = 0
        val state = mutableStateOf(LoginUiState(LoginStep.AUTHORIZED))
        render(state, onLogout = { logoutCalls += 1 })

        composeRule.onNodeWithTag("login-logout").performClick()

        assertEquals(1, logoutCalls)
    }

    private fun render(
        state: MutableState<LoginUiState>,
        onInputChanged: (String) -> Unit = {},
        onCredentialApiIdChanged: (String) -> Unit = {},
        onCredentialApiHashChanged: (String) -> Unit = {},
        onConfigureCredentials: () -> Unit = {},
        onSubmit: () -> Unit = {},
        onResendCode: () -> Unit = {},
        onRetry: () -> Unit = {},
        onLogout: () -> Unit = {},
        localizedContext: Context? = null,
    ) {
        composeRule.setContent {
            val content: @Composable () -> Unit = {
                LoginScreen(
                    uiState = state.value,
                    onInputChanged = onInputChanged,
                    onCredentialApiIdChanged = onCredentialApiIdChanged,
                    onCredentialApiHashChanged = onCredentialApiHashChanged,
                    onConfigureCredentials = onConfigureCredentials,
                    onSubmit = onSubmit,
                    onResendCode = onResendCode,
                    onRetry = onRetry,
                    onLogout = onLogout,
                )
            }
            if (localizedContext == null) {
                content()
            } else {
                CompositionLocalProvider(
                    LocalContext provides localizedContext,
                    LocalConfiguration provides localizedContext.resources.configuration,
                    content = content,
                )
            }
        }
    }

    private fun localizedContext(languageTag: String): Context {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        return baseContext.createConfigurationContext(configuration)
    }

    private fun assertNoText(text: String) {
        composeRule.onAllNodesWithText(text).assertCountEquals(0)
    }

    private fun assertNoTag(tag: String) {
        composeRule.onAllNodesWithTag(tag).assertCountEquals(0)
    }

    private data class FailureCase(
        val failure: TelegramAuthFailure,
        val expectedText: String,
        val retrySecondsRemaining: Int = 0,
    )
}
