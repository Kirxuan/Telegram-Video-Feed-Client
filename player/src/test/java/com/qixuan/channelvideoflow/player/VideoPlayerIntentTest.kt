package com.qixuan.channelvideoflow.player

import android.app.Application
import androidx.media3.ui.PlayerView
import com.qixuan.channelvideoflow.domain.media.*
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VideoPlayerIntentTest {
    @Test fun enginePauseDuringBufferingIsReflectedAndOneResumeRestartsIntent() = withManager { manager, view ->
        val player = requireNotNull(view.player)
        assertTrue(player.playWhenReady)
        // Same public Player event used by system/remote playback controls, outside manager.pause().
        player.pause()
        assertTrue("Engine pause must be visible to the UI", manager.snapshot.value.isPaused)
        manager.resume()
        assertTrue(player.playWhenReady)
        assertFalse(manager.snapshot.value.isPaused)
    }

    @Test fun engineResumeSynchronizesSnapshotWithoutAWatchdog() = withManager { manager, view ->
        manager.pause()
        requireNotNull(view.player).play()
        assertFalse("Engine resume left a stale pause overlay", manager.snapshot.value.isPaused)
    }

    @Test fun pageTransitionStaysTransientAndExplicitUserPauseStaysPaused() = withManager { manager, view ->
        manager.pauseForPageTransition()
        assertFalse(requireNotNull(view.player).playWhenReady)
        assertFalse(manager.snapshot.value.isPaused)
        manager.resume()
        assertTrue(requireNotNull(view.player).playWhenReady)
        manager.pause()
        assertTrue(manager.snapshot.value.isPaused)
        assertFalse(requireNotNull(view.player).playWhenReady)
    }

    private fun withManager(block: (VideoPlayerManager, PlayerView) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val gateway = NoBytesGateway()
        val manager = VideoPlayerManager(context, gateway, IntentAdaptiveController,
            IntentVideoPreloadController,
            Media3SamplePreloadController(context, gateway, NoOpStreamingNetworkMetricsRepository))
        val view = PlayerView(context)
        try {
            manager.attach(view)
            manager.bind(IndexedVideo(
                key = VideoKey(1, 1), fileId = 1, remoteUniqueId = "fixture", caption = "",
                supportsStreaming = true, fileSize = 1024, durationSeconds = 30,
                width = 320, height = 180, publishTime = 0, editTime = null,
                canBeSaved = true, tags = emptyList(),
            ))
            block(manager, view)
        } finally {
            manager.release()
        }
    }
}

private class NoBytesGateway : TelegramFileGateway {
    override fun acquireRange(fileId: Int, offset: Long, length: Long,
        priority: TelegramFileRequestPriority, ownerToken: String,
        ownerKind: TelegramFileOwnerKind, readAheadBytes: Long): TelegramFileRangeLease =
        throw java.io.IOException("No network in intent fixture")
    override fun pinFile(fileId: Int, ownerToken: String, ownerKind: TelegramFileOwnerKind) =
        object : TelegramFileProtectionLease {
            override val fileId = fileId
            override val ownerKind = ownerKind
            override fun close() = Unit
        }
    override fun currentSnapshot(fileId: Int): TelegramFileSnapshot? = null
    override fun observeFile(fileId: Int) = flowOf<TelegramFileSnapshot>()
    override fun protectedFileIds() = emptySet<Int>()
    override suspend fun deleteCachedFile(fileId: Int) = TelegramFileDeleteResult.PROTECTED
    override fun release(ownerToken: String) = Unit
}

private object IntentAdaptiveController : AdaptivePreloadController {
    override val decision = MutableStateFlow(AdaptivePreloadDecision(
        AdaptivePreloadState.OFF, AdaptivePreloadReason.OFFLINE, 0L, 0, null,
    ))
    override fun onCurrentBind(cacheHit: Boolean) = Unit
    override fun onFirstFrame(bindToFirstFrameMillis: Long) = Unit
    override fun onPlaybackFailure() = Unit
    override fun onRebufferStarted() = Unit
    override fun onRebufferRecovered() = Unit
    override fun onCurrentReleased() = Unit
}

private object IntentVideoPreloadController : VideoPreloadController {
    override val ownerHandoff = MutableStateFlow(PreloadOwnerHandoffSnapshot())
    override fun setNextVideo(video: IndexedVideo?) = Unit
    override fun beginTargetPromotion() = Unit
    override fun commitTargetPromotion(video: IndexedVideo) = Unit
    override fun abandonTargetPromotion() = Unit
    override fun onCurrentPlaybackStarting(video: IndexedVideo) = Unit
    override fun onCurrentPlaybackRangeAcquired(video: IndexedVideo) = Unit
    override fun onCurrentPlaybackRangeAcquireFailed(video: IndexedVideo) = Unit
    override fun stop() = Unit
}
