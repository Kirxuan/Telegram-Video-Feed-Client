package com.qixuan.channelvideoflow.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.qixuan.channelvideoflow.feature.auth.AuthViewModel
import com.qixuan.channelvideoflow.feature.auth.LoginScreen
import com.qixuan.channelvideoflow.feature.auth.LoginStep
import com.qixuan.channelvideoflow.feature.channels.ChannelSelectionRoute
import com.qixuan.channelvideoflow.feature.settings.CacheSettingsRoute
import com.qixuan.channelvideoflow.feature.tags.TagFilterRoute
import com.qixuan.channelvideoflow.feature.video.VideoPlaybackRoute

@Composable
@UnstableApi
internal fun ChannelVideoFlowNavHost(
    authViewModel: AuthViewModel = hiltViewModel(),
    navigationViewModel: AuthorizedNavigationViewModel = hiltViewModel(),
) {
    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
    val navigation by navigationViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(authUiState.step) {
        if (authUiState.step != LoginStep.AUTHORIZED) {
            navigationViewModel.reset()
        }
    }
    BackHandler(
        enabled = authUiState.step == LoginStep.AUTHORIZED &&
            navigation.destination != AuthorizedDestination.CHANNELS,
    ) {
        navigationViewModel.back()
    }
    if (authUiState.step == LoginStep.AUTHORIZED) {
        when (navigation.destination) {
            AuthorizedDestination.SETTINGS -> CacheSettingsRoute(
                onBack = navigationViewModel::back,
                onLogout = {
                    navigationViewModel.reset()
                    authViewModel.logout()
                },
            )
            AuthorizedDestination.TAGS -> TagFilterRoute(
                onBack = navigationViewModel::back,
                onContinue = navigationViewModel::openFeed,
            )
            AuthorizedDestination.FEED -> VideoPlaybackRoute(
                initialFilter = navigation.filter,
                initialOrder = navigation.order,
                onOrderPersisted = navigationViewModel::updateOrder,
                onBack = navigationViewModel::back,
                onLogout = {
                    navigationViewModel.reset()
                    authViewModel.logout()
                },
            )
            AuthorizedDestination.CHANNELS -> ChannelSelectionRoute(
                onLogout = authViewModel::logout,
                onOpenPlayback = navigationViewModel::openTags,
                onOpenCacheSettings = navigationViewModel::openSettings,
                logoutEnabled = authUiState.canLogout,
            )
        }
    } else {
        LoginScreen(
            uiState = authUiState,
            onInputChanged = authViewModel::onInputChanged,
            onCredentialApiIdChanged = authViewModel::onCredentialApiIdChanged,
            onCredentialApiHashChanged = authViewModel::onCredentialApiHashChanged,
            onConfigureCredentials = authViewModel::configureCredentials,
            onSubmit = authViewModel::submit,
            onResendCode = authViewModel::resendCode,
            onRetry = authViewModel::retryStart,
            onLogout = authViewModel::logout,
        )
    }
}
