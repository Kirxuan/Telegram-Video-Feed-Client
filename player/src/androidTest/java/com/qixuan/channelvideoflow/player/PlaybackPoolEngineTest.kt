package com.qixuan.channelvideoflow.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Media3 + EGL proof with a synthetic clip; no TDLib, account or network. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackPoolEngineTest {
    @Test fun fastStartupNeverLowersRebufferThresholdAndStandbyFillsFiveSeconds() {
        val delegate = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 60_000, 2_500, 12_000)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        val role = AtomicBoolean(true)
        val control = PoolLoadControl(delegate, role) { 800L }
        val id = androidx.media3.exoplayer.analytics.PlayerId("duration-startup-proof")
        val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(
            60_000_000L, true, false, false, null, MediaItem.fromUri("tg://fixture"),
        )
        fun parameters(bufferedUs: Long, rebuffer: Boolean = false) = androidx.media3.exoplayer.LoadControl.Parameters(
            id, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0L, bufferedUs, 1f, false, rebuffer,
            androidx.media3.common.C.TIME_UNSET, androidx.media3.common.C.TIME_UNSET,
        )
        control.onPrepared(id)
        val allocator = control.getAllocator(id)
        val allocations = mutableListOf<androidx.media3.exoplayer.upstream.Allocation>()
        try {
            assertFalse(control.shouldStartPlayback(parameters(799_000)))
            assertTrue(control.shouldStartPlayback(parameters(800_000)))
            assertFalse(control.shouldStartPlayback(parameters(800_000, rebuffer = true)))
            role.set(false)
            while (allocator.totalBytesAllocated < 2 * 1024 * 1024) allocations += allocator.allocate()
            assertTrue("A high bitrate NEXT must continue beyond the old 1 MiB cutoff",
                control.shouldContinueLoading(parameters(2_000_000)))
            assertTrue("STANDBY fills to the five second reserve",
                control.shouldContinueLoading(parameters(4_999_000)))
            assertFalse(control.shouldContinueLoading(parameters(5_000_000)))
            while (allocator.totalBytesAllocated < 20 * 1024 * 1024) allocations += allocator.allocate()
            assertFalse(control.shouldContinueLoading(parameters(2_000_000)))
        } finally {
            allocations.forEach(allocator::release)
            control.onReleased(id)
        }
    }

    @Test fun meteredActiveCapYieldsTheLinkInsteadOfFillingToTheFiftySecondMinimum() {
        val delegate = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 60_000, 2_500, 12_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        var nextAdmitted = false
        val control = PoolLoadControl(
            delegate,
            AtomicBoolean(true),
            activeBufferCapMicros = {
                if (nextAdmitted) PoolLoadControl.METERED_ACTIVE_FORWARD_BUFFER_CAP_MICROS else Long.MAX_VALUE
            },
        )
        val id = androidx.media3.exoplayer.analytics.PlayerId("metered-cap-proof")
        val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(
            600_000_000L, true, false, false, null, MediaItem.fromUri("tg://fixture"),
        )
        fun parameters(bufferedUs: Long) = androidx.media3.exoplayer.LoadControl.Parameters(
            id, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0L, bufferedUs, 1f, false, false,
            androidx.media3.common.C.TIME_UNSET, androidx.media3.common.C.TIME_UNSET,
        )
        control.onPrepared(id)
        val allocator = control.getAllocator(id)
        val allocations = mutableListOf<androidx.media3.exoplayer.upstream.Allocation>()
        try {
            // No next item admitted: the current stream keeps filling towards its own minimum.
            assertTrue(control.shouldContinueLoading(parameters(20_000_000)))
            nextAdmitted = true
            assertTrue(control.shouldContinueLoading(parameters(19_000_000)))
            assertFalse("The current stream must stop at the reserve while a NEXT wants the link",
                control.shouldContinueLoading(parameters(20_000_000)))
            nextAdmitted = false
            assertTrue("Releasing the claim must restore the full current budget",
                control.shouldContinueLoading(parameters(20_000_000)))
        } finally {
            allocations.forEach(allocator::release)
            control.onReleased(id)
        }
    }

    @Test fun activeLoadingStopsAtSampleMemoryCeilingEvenWhenTimeBufferIsStillShort() {
        val delegate = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 60_000, 2_500, 12_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val control = PoolLoadControl(delegate, AtomicBoolean(true))
        val id = androidx.media3.exoplayer.analytics.PlayerId("sample-memory-proof")
        val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(
            60_000_000L, true, false, false, null, MediaItem.fromUri("tg://fixture"),
        )
        val parameters = androidx.media3.exoplayer.LoadControl.Parameters(
            id, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0L, 500_000L, 1f, false, false,
            androidx.media3.common.C.TIME_UNSET, androidx.media3.common.C.TIME_UNSET,
        )
        control.onPrepared(id)
        val allocator = control.getAllocator(id)
        val allocations = mutableListOf<androidx.media3.exoplayer.upstream.Allocation>()
        try {
            while (allocator.totalBytesAllocated < 32 * 1024 * 1024) {
                allocations += allocator.allocate()
            }
            assertFalse("A high-bitrate or paused video must not fill the Java heap",
                control.shouldContinueLoading(parameters))
            assertTrue("The memory ceiling must not deadlock foreground buffering",
                control.shouldStartPlayback(parameters))
        } finally {
            allocations.forEach(allocator::release)
            control.onReleased(id)
        }
    }

    @Test fun standbyCannotDeclarePlaybackReadyWithOnlyHalfASecondBuffered() {
        val delegate = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 60_000, 2_500, 12_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val role = AtomicBoolean(false)
        val control = PoolLoadControl(delegate, role)
        val id = androidx.media3.exoplayer.analytics.PlayerId("startup-reservoir-proof")
        val timeline = androidx.media3.exoplayer.source.SinglePeriodTimeline(
            60_000_000L, true, false, false, null, MediaItem.fromUri("tg://fixture"),
        )
        fun parameters(bufferedUs: Long) = androidx.media3.exoplayer.LoadControl.Parameters(
            id, timeline,
            MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)), 0L, bufferedUs, 1f, false, false,
            androidx.media3.common.C.TIME_UNSET, androidx.media3.common.C.TIME_UNSET,
        )
        control.onPrepared(id)
        try {
            assertFalse("READY must mean the foreground startup reservoir is available",
                control.shouldStartPlayback(parameters(500_000L)))
            assertTrue("Standby must keep filling beyond its old one-second ceiling",
                control.shouldContinueLoading(parameters(1_100_000L)))
            assertTrue(control.shouldStartPlayback(parameters(2_500_000L)))
            role.set(true)
            assertFalse(control.shouldStartPlayback(parameters(500_000L)))
            assertTrue(control.shouldStartPlayback(parameters(2_500_000L)))
        } finally {
            control.onReleased(id)
        }
    }

    @Test fun canceledAndRepeatedSurfaceCloseSuppressCallbacksAndStopThreads() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        repeat(12) {
            instrumentation.runOnMainSync {
                val surface = StandbyVideoSurface(Handler(Looper.getMainLooper()),
                    { callbacks.incrementAndGet() }, { callbacks.incrementAndGet() })
                surface.close()
                surface.close()
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && Thread.getAllStackTraces().keys.any {
                it.name == "velora-standby-surface" && it.isAlive
            }) {
            Thread.sleep(20)
        }
        instrumentation.waitForIdleSync()
        assertEquals(0, callbacks.get())
        assertFalse("Canceled Surface worker leaked", Thread.getAllStackTraces().keys.any {
            it.name == "velora-standby-surface" && it.isAlive
        })
    }

    @Test fun javaDefaultMethodsAreForwardedBeforeFirstPlayerConstruction() {
        val delegate = DefaultLoadControl.Builder().setBackBuffer(1234, true).build()
        val playerId = androidx.media3.exoplayer.analytics.PlayerId("load-control-proof")
        // This is the exact Kotlin delegation pattern shipped in the original C2 APK.
        val broken = object : androidx.media3.exoplayer.LoadControl by delegate {}
        val failure = assertThrows(IllegalStateException::class.java) {
            broken.getBackBufferDurationUs(playerId)
        }
        assertEquals("getBackBufferDurationUs not implemented", failure.message)
        val fixed = PoolLoadControl(delegate, AtomicBoolean(true))
        assertEquals(1_234_000L, fixed.getBackBufferDurationUs(playerId))
        assertTrue(fixed.retainBackBufferFromKeyframe(playerId))
        repeat(3) {
            fixed.onPrepared(playerId)
            assertNotNull(fixed.getAllocator(playerId))
            fixed.onStopped(playerId)
            fixed.onReleased(playerId)
        }
    }

    @Test fun c1PreparesWithoutPersistentOutputAndKeepsExactlyTwoEngines() = runPool(false)
    @Test fun c2RendersPausedOffscreenFrameAndReusesExactlyTwoEngines() = runPool(true)

    private fun runPool(withSurface: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val clip = File(context.cacheDir, "pool-proof.mp4")
        instrumentation.context.assets.open("pool-proof.mp4").use { input ->
            clip.outputStream().use(input::copyTo)
        }
        val engines = mutableListOf<ExoPlayerReusableEngine>()
        val lifecycle = ReusablePlayerLifecycle<MediaSource>(
            factory = {
                val active = AtomicBoolean(true)
                ExoPlayerReusableEngine(
                    ExoPlayer.Builder(context)
                        .setAudioAttributes(VideoAudioPolicy.attributes, false)
                        .setLoadControl(PoolLoadControl(DefaultLoadControl.Builder().build(), active))
                        .build(),
                    active,
                ).also(engines::add)
            },
            startOrder = PlaybackStartOrder.PREPARE_THEN_PLAY,
        )
        var output: android.view.Surface? = null
        var surface: StandbyVideoSurface? = null
        try {
            if (withSurface) {
                val ready = CountDownLatch(1)
                surface = StandbyVideoSurface(Handler(Looper.getMainLooper()), {
                    output = it
                    ready.countDown()
                }, { ready.countDown() })
                assertTrue("EGL setup timed out", ready.await(10, TimeUnit.SECONDS))
                assertNotNull("C2 requires a real consumed Surface", output)
            }
            val source = {
                ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
                    .createMediaSource(MediaItem.fromUri(android.net.Uri.fromFile(clip)))
            }
            instrumentation.runOnMainSync { lifecycle.bind(source()) }
            repeat(4) { index ->
                val prepared = CountDownLatch(1)
                val frame = CountDownLatch(1)
                var error: PlaybackException? = null
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) prepared.countDown()
                    }
                    override fun onRenderedFirstFrame() { frame.countDown() }
                    override fun onPlayerError(failure: PlaybackException) {
                        error = failure
                        prepared.countDown()
                        frame.countDown()
                    }
                }
                instrumentation.runOnMainSync {
                    lifecycle.prepareStandby(source(), "target-$index", onPrepare = {
                        val standby = (lifecycle.preparedEngine as ExoPlayerReusableEngine).player
                        standby.addListener(listener)
                        if (output != null) standby.setVideoSurface(output)
                    })
                }
                assertTrue("STANDBY never became ready", prepared.await(15, TimeUnit.SECONDS))
                if (withSurface) assertTrue("No decoded offscreen frame", frame.await(15, TimeUnit.SECONDS))
                assertNull(error?.errorCodeName, error)
                instrumentation.runOnMainSync {
                    val standby = (lifecycle.preparedEngine as ExoPlayerReusableEngine).player
                    assertFalse(standby.playWhenReady)
                    assertFalse(standby.isPlaying)
                    assertEquals(0f, standby.volume, 0f)
                    standby.removeListener(listener)
                    standby.clearVideoSurface()
                    val retired = (lifecycle.currentEngine as ExoPlayerReusableEngine).player
                    val promoted = lifecycle.promotePrepared("target-$index", index + 2L)
                    assertNotNull(promoted)
                    assertFalse(retired.playWhenReady)
                    assertEquals(0f, retired.volume, 0f)
                    assertEquals(0, retired.mediaItemCount)
                    promoted!!.setActive(true)
                    promoted.setPlayWhenReady(true)
                    assertEquals(2, lifecycle.instanceCount)
                }
            }
            assertEquals(2, engines.size)
        } finally {
            instrumentation.runOnMainSync { lifecycle.release() }
            surface?.close()
            clip.delete()
        }
        assertEquals(0, lifecycle.instanceCount)
    }
}
