package com.qixuan.channelvideoflow.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qixuan.channelvideoflow.domain.media.*
import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoKey
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the application's real factory, LoadControl and DataSource with account-free bytes. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoPlayerManagerIntegrationTest {
    @Test fun visiblePoolPromotesCancelsAndReleasesWithBoundedOwnership() = verifyVisiblePool(false)

    @Test fun surfaceFailureRetainsC1UntilFeedSessionEnds() {
        org.junit.Assume.assumeTrue(BuildConfig.PLAYBACK_POOL_CANDIDATE == "C2")
        verifyVisiblePool(true)
    }

    private fun verifyVisiblePool(failSurface: Boolean) {
        org.junit.Assume.assumeTrue(BuildConfig.PLAYBACK_POOL_CANDIDATE != "DISABLED")
        val offscreen = BuildConfig.PLAYBACK_POOL_CANDIDATE == "C2"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val clip = File(context.cacheDir, "manager-visible-proof.mp4")
        instrumentation.context.assets.open("pool-proof.mp4").use { input ->
            clip.outputStream().use(input::copyTo)
        }
        val gateway = LocalClipGateway(clip)
        val networkMetrics = StreamingNetworkMetricsEstimator().apply {
            resetNetworkContext(NetworkTransport.MOBILE, 77L)
        }
        val adaptive = MutableWifiAdaptiveController()
        // Cellular authorization must reach the real factory and pool, including a slow
        // first-frame history. Local fixture bytes isolate lifecycle from network variance.
        adaptive.decision.value = adaptive.wifiDecision.copy(
            state = AdaptivePreloadState.CONSERVATIVE,
            reason = AdaptivePreloadReason.RECENT_FIRST_FRAME_TAIL,
            isUnmeteredWifi = false,
            mobileDataPreloadEnabled = true,
        )
        val activity = instrumentation.startActivitySync(
            android.content.Intent(instrumentation.context, PlaybackProofActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PlaybackProofActivity
        lateinit var manager: VideoPlayerManager
        var managerCreated = false
        val preparation = PlaybackPreparationContext(1, 0, 0, 1, null)
        fun video(id: Int, protected: Boolean = false) = IndexedVideo(
            key = VideoKey(1L, id.toLong()), fileId = id, remoteUniqueId = "fixture-$id",
            caption = "", supportsStreaming = true, fileSize = clip.length(),
            durationSeconds = 3, width = 160, height = 96, publishTime = 0L,
            editTime = null, canBeSaved = !protected, tags = emptyList(),
        )
        fun lifecycle(): ReusablePlayerLifecycle<*> =
            manager.javaClass.getDeclaredField("playerLifecycle").apply { isAccessible = true }
                .get(manager) as ReusablePlayerLifecycle<*>
        fun standby() = (lifecycle().preparedEngine as? ExoPlayerReusableEngine)?.player
        fun decodedFrame(): Boolean {
            val record = manager.javaClass.getDeclaredField("standbyRecord")
                .apply { isAccessible = true }.get(manager) ?: return false
            return record.javaClass.getDeclaredField("decodedFrame")
                .apply { isAccessible = true }.getBoolean(record)
        }
        fun awaitMain(label: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                var done = false
                onMain {
                    assertFalse("Playback failed: ${manager.state.value}", manager.state.value is VideoPlaybackState.Failed)
                    done = condition()
                }
                if (done) return
                Thread.sleep(25)
            }
            fail(label)
        }
        try {
            onMain {
                manager = VideoPlayerManager(context, gateway, adaptive,
                    IdleVideoPreloadController,
                    Media3SamplePreloadController(context, gateway, networkMetrics), networkMetrics)
                managerCreated = true
                manager.attach(activity.playerView)
                manager.bind(video(1), preparation)
            }
            awaitMain("Current visible frame missing") { manager.snapshot.value.hasRenderedFirstFrame }
            awaitMain("Actual media completion did not reach the snapshot") {
                manager.snapshot.value.hasEnded
            }
            onMain {
                assertEquals(Player.STATE_ENDED, activity.playerView.player!!.playbackState)
                manager.seekTo(0)
                assertFalse("Seek retained a stale completion", manager.snapshot.value.hasEnded)
            }
            awaitMain("Playback/progress did not restart after seeking from ENDED") {
                manager.snapshot.value.isPlaying && manager.snapshot.value.positionMillis > 0L
            }
            awaitMain("Second completion missing") { manager.snapshot.value.hasEnded }
            onMain {
                manager.resume()
                assertFalse("Resume could not replay a one-item random round", manager.snapshot.value.hasEnded)
            }
            awaitMain("Ended binding did not resume playback") { manager.snapshot.value.isPlaying }
            repeat(4) { index ->
                val next = video(index + 2)
                onMain { assertTrue(manager.prepareStandby(next, preparation)) }
                awaitMain("Standby preparation missing") {
                    if (offscreen) decodedFrame() else standby()?.playbackState == Player.STATE_READY
                }
                if (index == 0) {
                    onMain {
                        // The single next target can be warmed first by the decoder-free byte stage
                        // and then prepared by the pool. The pool must inherit the bytes that stage
                        // already asked TDLib for, so one target never gets two ceilings.
                        IdleVideoPreloadController.handoverBytes = 256L * 1024L
                        manager.prepareStandby(video(63), preparation)
                        val handedOver = manager.javaClass.getDeclaredField("standbyBudget")
                            .apply { isAccessible = true }.get(manager) as SampleRequestBudget
                        assertEquals(
                            "The pool did not inherit the lightweight stage's charged bytes",
                            256L * 1024L,
                            handedOver.reservedBytes,
                        )
                        IdleVideoPreloadController.handoverBytes = 0L
                        manager.prepareStandby(next, preparation)
                        val afterReset = manager.javaClass.getDeclaredField("standbyBudget")
                            .apply { isAccessible = true }.get(manager) as SampleRequestBudget
                        assertEquals(
                            "Re-targeting must not keep another target's hand-over bytes",
                            0L,
                            afterReset.reservedBytes,
                        )
                    }
                    var preparedBeforeEstimate: Any? = null
                    onMain {
                        preparedBeforeEstimate = lifecycle().preparedEngine
                        repeat(3) {
                            networkMetrics.recordNetworkProgress(512L * 1024L, 2_000_000_000L,
                                networkMetrics.contextRevision)
                        }
                    }
                    Thread.sleep(350)
                    onMain { assertSame("First bandwidth estimate was treated as a network switch",
                        preparedBeforeEstimate, lifecycle().preparedEngine) }
                }
                onMain {
                    val previous = requireNotNull(activity.playerView.player)
                    val prepared = requireNotNull(standby())
                    assertFalse(prepared.playWhenReady)
                    assertEquals(0f, prepared.volume, 0f)
                    assertEquals(2, lifecycle().instanceCount)
                    manager.pauseForPageTransition()
                    assertSame("Gesture start discarded the prepared player", prepared, standby())
                    manager.bind(next, preparation)
                    assertFalse("Promotion inherited the previous completion", manager.snapshot.value.hasEnded)
                    assertSame(prepared, activity.playerView.player)
                    assertFalse(previous.playWhenReady)
                    assertEquals(0f, previous.volume, 0f)
                    assertEquals(0, previous.mediaItemCount)
                    assertEquals(0, gateway.nextPinCount())
                }
                awaitMain("Promoted visible frame missing") { manager.snapshot.value.hasRenderedFirstFrame }
            }
            onMain {
                manager.pause()
                manager.seekTo(0L)
                assertTrue("Paused seek hid the existing picture and resume control",
                    manager.snapshot.value.hasRenderedFirstFrame)
                assertTrue(manager.snapshot.value.isPaused)
                assertFalse(requireNotNull(activity.playerView.player).playWhenReady)
            }
            awaitMain("Paused seek never completed") {
                activity.playerView.player?.playbackState == Player.STATE_READY
            }
            onMain {
                assertFalse("Seek priority was left active after READY",
                    manager.javaClass.getDeclaredField("seekRiskActive")
                        .apply { isAccessible = true }.getBoolean(manager))
                manager.resume()
                assertTrue(requireNotNull(activity.playerView.player).playWhenReady)
            }
            for (reason in listOf(
                AdaptivePreloadReason.MEMORY_LOW, AdaptivePreloadReason.STORAGE_LOW,
                AdaptivePreloadReason.POWER_SAVE, AdaptivePreloadReason.THERMAL,
                AdaptivePreloadReason.OFFLINE, AdaptivePreloadReason.NETWORK_CHANGED,
                AdaptivePreloadReason.REBUFFER, AdaptivePreloadReason.METERED_NETWORK,
            )) {
                val next = video(19)
                onMain { manager.prepareStandby(next, preparation) }
                awaitMain("Safety fixture did not prepare") {
                    if (offscreen) decodedFrame() else standby()?.playbackState == Player.STATE_READY
                }
                val budget = manager.javaClass.getDeclaredField("standbyBudget")
                    .apply { isAccessible = true }.get(manager)
                onMain {
                    manager.pauseForPageTransition()
                    adaptive.decision.value = AdaptivePreloadDecision(
                        state = if (reason == AdaptivePreloadReason.METERED_NETWORK) {
                            AdaptivePreloadState.CONSERVATIVE
                        } else AdaptivePreloadState.OFF,
                        reason = reason, maxPreloadBytes = 0, recentSampleCount = 0,
                        recentP90Millis = null, isUnmeteredWifi = false,
                    )
                }
                awaitMain("Safety transition retained a standby engine: $reason") {
                    lifecycle().instanceCount == 1
                }
                onMain {
                    assertEquals(0, gateway.nextPinCount())
                    assertNull("Safety transition retained EGL: $reason",
                        manager.javaClass.getDeclaredField("standbySurface")
                            .apply { isAccessible = true }.get(manager))
                    adaptive.decision.value = adaptive.wifiDecision.copy(
                        isUnmeteredWifi = false,
                        mobileDataPreloadEnabled = true,
                    )
                    manager.resume()
                    manager.prepareStandby(next, preparation)
                    assertSame("Safety retry reset the byte ledger", budget,
                        manager.javaClass.getDeclaredField("standbyBudget")
                            .apply { isAccessible = true }.get(manager))
                    manager.prepareStandby(next, preparation.copy(qualityGeneration = 2))
                    assertSame("Quality change refunded the same target's byte budget", budget,
                        manager.javaClass.getDeclaredField("standbyBudget")
                            .apply { isAccessible = true }.get(manager))
                }
            }
            onMain {
                gateway.failNextRange = true
                manager.prepareStandby(video(60), preparation)
            }
            awaitMain("Source failure did not retire the unavailable NEXT") {
                manager.javaClass.getDeclaredField("unavailableStandbyKey")
                    .apply { isAccessible = true }.get(manager) == video(60).key
            }
            onMain {
                assertTrue("One source failure disabled the entire pool", manager.supportsStandbyPreparation)
                assertEquals(0, gateway.nextPinCount())
                gateway.failNextRange = false
                manager.prepareStandby(video(61), preparation)
            }
            awaitMain("A later target could not prepare after a source failure") {
                standby()?.playbackState == Player.STATE_READY
            }
            onMain {
                repeat(12) { manager.prepareStandby(video(it + 20), preparation); manager.discardStandby() }
                assertEquals(0, gateway.nextPinCount())
                manager.prepareStandby(video(50, protected = true), preparation)
            }
            awaitMain("Protected standby not ready") { standby()?.playbackState == Player.STATE_READY }
            onMain {
                assertFalse(decodedFrame())
                manager.bind(video(50, protected = true), preparation)
                assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            awaitMain("Protected visible frame missing") { manager.snapshot.value.hasRenderedFirstFrame }
            onMain {
                gateway.failNextPin = !failSurface
                manager.prepareStandby(video(70), preparation)
                if (failSurface) {
                    manager.javaClass.getDeclaredMethod("disableStandbySurface")
                        .apply { isAccessible = true }.invoke(manager)
                }
            }
            awaitMain("Preparation failure did not apply the expected fallback") {
                val coordinator = manager.javaClass.getDeclaredField("playbackPoolCoordinator")
                    .apply { isAccessible = true }.get(manager) as PlaybackPoolCoordinator
                if (failSurface) {
                    manager.supportsStandbyPreparation && coordinator.state.candidate == PlaybackPoolCandidate.C1
                } else !manager.supportsStandbyPreparation
            }
            if (failSurface) {
                onMain { manager.prepareStandby(video(72), preparation) }
                awaitMain("Surface failure prevented a later C1 preparation") {
                    standby()?.playbackState == Player.STATE_READY
                }
                onMain {
                    assertNull(manager.javaClass.getDeclaredField("standbySurface")
                        .apply { isAccessible = true }.get(manager))
                    manager.discardStandby()
                }
            }
            onMain {
                assertEquals(1, lifecycle().instanceCount)
                assertEquals(0, gateway.nextPinCount())
                assertTrue(activity.playerView.player!!.playWhenReady)
                manager.releaseBinding()
                assertEquals(failSurface, manager.supportsStandbyPreparation)
                manager.bind(video(71), preparation)
                assertEquals(failSurface, manager.supportsStandbyPreparation)
                manager.pause()
                assertFalse(activity.playerView.player!!.playWhenReady)
                manager.resume()
                manager.seekTo(500)
                manager.onAppBackgrounded()
                assertEquals(1, lifecycle().instanceCount)
                assertFalse(activity.playerView.player!!.playWhenReady)
                assertEquals(0, gateway.nextPinCount())
                manager.release()
                manager.release()
                assertFalse(manager.snapshot.value.hasEnded)
                assertEquals(0, lifecycle().instanceCount)
                assertEquals(0, gateway.pins.size)
                assertNull(activity.playerView.player)
            }
        } finally {
            onMain { if (managerCreated) manager.release(); activity.finish() }
            clip.delete()
        }
    }

    @Test fun feedEntryAttachesPlaysAndReleasesUsingProductionFactory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val clip = File(context.cacheDir, "manager-proof.mp4")
        instrumentation.context.assets.open("pool-proof.mp4").use { input ->
            clip.outputStream().use(input::copyTo)
        }
        val gateway = LocalClipGateway(clip)
        var manager: VideoPlayerManager? = null
        try {
            // Returning to the filter screen and entering Feed again must also work.
            repeat(2) {
                val ready = CountDownLatch(1)
                val error = AtomicReference<PlaybackException?>()
                onMain {
                    val sampleController = Media3SamplePreloadController(
                        context, gateway, NoOpStreamingNetworkMetricsRepository,
                    )
                    val owner = VideoPlayerManager(
                        context, gateway, IdleAdaptiveController, IdleVideoPreloadController,
                        sampleController,
                    )
                    manager = owner
                    val view = PlayerView(context)
                    // Feed's AndroidView calls this before media binding. C2 used to throw here.
                    owner.attach(view)
                    val player = requireNotNull(view.player)
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) ready.countDown()
                        }
                        override fun onPlayerError(failure: PlaybackException) {
                            error.set(failure)
                            ready.countDown()
                        }
                    })
                    owner.bind(IndexedVideo(
                        key = VideoKey(1L, 1L), fileId = 1, remoteUniqueId = "fixture",
                        caption = "", supportsStreaming = true, fileSize = clip.length(),
                        durationSeconds = 2, width = 320, height = 180, publishTime = 0L,
                        editTime = null, canBeSaved = true, tags = emptyList(),
                    ))
                }
                assertTrue("Production player did not become READY", ready.await(15, TimeUnit.SECONDS))
                error.get()?.let { throw AssertionError("Production player failed", it) }
                onMain {
                    assertTrue(manager!!.state.value is VideoPlaybackState.Ready)
                    manager!!.release()
                    assertEquals(VideoPlaybackState.Idle, manager!!.state.value)
                    manager = null
                }
            }
        } finally {
            onMain { manager?.release() }
            clip.delete()
        }
    }

    private fun onMain(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
    }
}

private class LocalClipGateway(private val clip: File) : TelegramFileGateway {
    val pins = ConcurrentHashMap<String, TelegramFileOwnerKind>()
    var failNextPin = false
    @Volatile var failNextRange = false
    fun nextPinCount() = pins.values.count { it == TelegramFileOwnerKind.NEXT_PRELOAD }
    private fun snapshot(fileId: Int) = TelegramFileSnapshot(
        fileId, clip.length(), clip.length(), clip.absolutePath,
        true, false, true, 0L, clip.length(), clip.length(),
    )
    override fun currentSnapshot(fileId: Int) = snapshot(fileId)
    override fun observeFile(fileId: Int) = flowOf(snapshot(fileId))
    override fun acquireRange(
        fileId: Int, offset: Long, length: Long, priority: TelegramFileRequestPriority,
        ownerToken: String, ownerKind: TelegramFileOwnerKind, readAheadBytes: Long,
    ): TelegramFileRangeLease {
        if (failNextRange && ownerKind == TelegramFileOwnerKind.NEXT_PRELOAD) {
            throw TelegramFileUnavailableException("fixture next range unavailable")
        }
        return object : TelegramFileRangeLease {
        override val fileId = fileId
        override val offset = offset
        override val length = length
        override fun awaitAvailable(timeoutMillis: Long) = snapshot(fileId)
        override fun updatePriority(priority: TelegramFileRequestPriority) = Unit
        override fun close() = Unit
        }
    }
    override fun pinFile(fileId: Int, ownerToken: String, ownerKind: TelegramFileOwnerKind) =
        object : TelegramFileProtectionLease {
            init {
                if (failNextPin && ownerKind == TelegramFileOwnerKind.NEXT_PRELOAD) {
                    error("fixture next owner unavailable")
                }
                pins[ownerToken] = ownerKind
            }
            override val fileId = fileId
            override val ownerKind = ownerKind
            override fun close() { pins.remove(ownerToken) }
        }
    override fun protectedFileIds() = setOf(1)
    override suspend fun deleteCachedFile(fileId: Int) = TelegramFileDeleteResult.PROTECTED
    override fun release(ownerToken: String) = Unit
}

private class MutableWifiAdaptiveController : AdaptivePreloadController by IdleAdaptiveController {
    val wifiDecision = AdaptivePreloadDecision(
        AdaptivePreloadState.NORMAL, AdaptivePreloadReason.STABLE, 2L * 1024L * 1024L,
        10, 10L, isUnmeteredWifi = true,
    )
    override val decision = MutableStateFlow(wifiDecision)
}

private object IdleAdaptiveController : AdaptivePreloadController {
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

private object IdleVideoPreloadController : VideoPreloadController {
    /**
     * Bytes the lightweight byte stage claims to have already charged for the target under test.
     * The hand-over assertion drives this value to prove the pool inherits the target's ledger
     * instead of opening a fresh ceiling.
     */
    @Volatile
    var handoverBytes: Long = 0L

    override val ownerHandoff = MutableStateFlow(PreloadOwnerHandoffSnapshot())
    override fun setNextVideo(video: IndexedVideo?) = Unit
    override fun requestedBytesFor(video: IndexedVideo): Long = handoverBytes
    override fun beginTargetPromotion() = Unit
    override fun commitTargetPromotion(video: IndexedVideo) = Unit
    override fun abandonTargetPromotion() = Unit
    override fun onCurrentPlaybackStarting(video: IndexedVideo) = Unit
    override fun onCurrentPlaybackRangeAcquired(video: IndexedVideo) = Unit
    override fun onCurrentPlaybackRangeAcquireFailed(video: IndexedVideo) = Unit
    override fun stop() = Unit
}
