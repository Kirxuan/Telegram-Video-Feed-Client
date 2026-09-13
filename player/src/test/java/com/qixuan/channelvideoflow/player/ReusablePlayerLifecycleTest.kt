package com.qixuan.channelvideoflow.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReusablePlayerLifecycleTest {
    @Test
    fun standbyPreparesSilentlyAndHundredsOfPromotionsReuseExactlyTwoEngines() {
        val engines = mutableListOf<FakeReusablePlayerEngine>()
        val lifecycle = ReusablePlayerLifecycle(
            factory = { FakeReusablePlayerEngine().also(engines::add) },
            startOrder = PlaybackStartOrder.PREPARE_THEN_PLAY,
        )
        lifecycle.bind("first", 1)
        repeat(100) { index ->
            lifecycle.prepareStandby("next-$index", "token-$index")
            val standby = lifecycle.preparedEngine as FakeReusablePlayerEngine
            assertFalse(standby.events.takeLast(5).contains("playWhenReady:true"))
            assertEquals(null, lifecycle.promotePrepared("stale", index + 2L))
            val old = lifecycle.currentEngine as FakeReusablePlayerEngine
            val promoted = lifecycle.promotePrepared("token-$index", index + 2L)
            assertEquals(standby, promoted)
            assertEquals(listOf("pause", "clear"), old.events.takeLast(2))
        }
        assertEquals(2, engines.size)
        assertEquals(2, lifecycle.instanceCount)
        lifecycle.release()
        assertEquals(0, lifecycle.instanceCount)
        assertEquals(2, engines.count { it.events.last() == "release" })
    }

    @Test
    fun stableViewBindingNeverLeavesTwoViewsAttachedAndIgnoresRecomposition() {
        val binding = StablePlayerViewBinding<FakePlayerView, String>(
            currentPlayer = { view -> view.player },
            setPlayer = { view, player -> view.player = player },
        )
        val first = FakePlayerView()
        val second = FakePlayerView()

        val initial = binding.attach(first, "one-player")
        val recomposed = binding.attach(first, "one-player")
        val replaced = binding.attach(second, "one-player")

        assertEquals(ViewBindingChange(attached = true, detached = false), initial)
        assertEquals(ViewBindingChange(attached = false, detached = false), recomposed)
        assertEquals(ViewBindingChange(attached = true, detached = true), replaced)
        assertEquals(null, first.player)
        assertEquals("one-player", second.player)
        assertFalse(binding.detach(first))
        assertEquals("one-player", second.player)
        assertEquals(true, binding.detach(second))
        assertEquals(null, second.player)
    }

    @Test
    fun repeatedPageBindingsReuseOneEngineAndStopOldAudioBeforeReplacement() {
        var creationCount = 0
        val engine = FakeReusablePlayerEngine()
        val lifecycle = ReusablePlayerLifecycle(
            factory = {
                creationCount += 1
                engine
            },
            startOrder = PlaybackStartOrder.PREPARE_THEN_PLAY,
        )

        lifecycle.bind("video-0")
        repeat(99) { page -> lifecycle.bind("video-${page + 1}") }

        assertEquals(1, creationCount)
        assertEquals(1, lifecycle.instanceCount)
        assertEquals(
            listOf("set:video-0", "prepare", "playWhenReady:true"),
            engine.events.take(3),
        )
        assertEquals(
            listOf("pause", "set:video-1", "prepare", "playWhenReady:true"),
            engine.events.drop(3).take(4),
        )
        assertFalse(engine.events.contains("stop"))
        assertFalse(engine.events.contains("clear"))
    }

    @Test
    fun releaseBindingClearsMediaButKeepsEngineUntilFullRelease() {
        var creationCount = 0
        val engines = mutableListOf<FakeReusablePlayerEngine>()
        val lifecycle = ReusablePlayerLifecycle(
            factory = {
                creationCount += 1
                FakeReusablePlayerEngine().also(engines::add)
            },
            startOrder = PlaybackStartOrder.PREPARE_THEN_PLAY,
        )

        lifecycle.bind("first")
        lifecycle.releaseBinding()
        lifecycle.bind("second")

        assertEquals(1, creationCount)
        assertEquals(
            listOf(
                "set:first",
                "prepare",
                "playWhenReady:true",
                "pause",
                "clear",
                "set:second",
                "prepare",
                "playWhenReady:true",
            ),
            engines.single().events,
        )

        lifecycle.release()
        assertEquals(listOf("pause", "clear", "release"), engines.single().events.takeLast(3))
        lifecycle.bind("third")
        assertEquals(2, creationCount)
    }

    @Test
    fun temporarySpeedUsesOnlyTheExistingEngineAndRepeatedChangesAreIdempotent() {
        var creationCount = 0
        val engine = FakeReusablePlayerEngine()
        val lifecycle = ReusablePlayerLifecycle(
            factory = {
                creationCount += 1
                engine
            },
            startOrder = PlaybackStartOrder.PREPARE_THEN_PLAY,
        )

        assertEquals(
            VideoPlaybackSpeeds.NORMAL,
            lifecycle.setTemporaryPlaybackSpeed(active = true),
        )
        assertEquals(0, creationCount)

        lifecycle.bind("first")
        assertEquals(
            VideoPlaybackSpeeds.TEMPORARY_FAST_FORWARD,
            lifecycle.setTemporaryPlaybackSpeed(active = true),
        )
        assertEquals(
            VideoPlaybackSpeeds.TEMPORARY_FAST_FORWARD,
            lifecycle.setTemporaryPlaybackSpeed(active = true),
        )
        assertEquals(1, engine.events.count { event -> event == "speed:2.0" })
        assertEquals(1, creationCount)

        assertEquals(
            VideoPlaybackSpeeds.NORMAL,
            lifecycle.setTemporaryPlaybackSpeed(active = false),
        )
        assertEquals(
            VideoPlaybackSpeeds.NORMAL,
            lifecycle.setTemporaryPlaybackSpeed(active = false),
        )
        assertEquals(1, engine.events.count { event -> event == "speed:1.0" })
        assertEquals(1, creationCount)
    }

    @Test
    fun bindingTheNextVideoAndReleasingAlwaysRestoreNormalSpeedWithoutAnotherEngine() {
        var creationCount = 0
        val engine = FakeReusablePlayerEngine()
        val lifecycle = ReusablePlayerLifecycle(
            factory = {
                creationCount += 1
                engine
            },
            startOrder = PlaybackStartOrder.PREPARE_THEN_PLAY,
        )

        lifecycle.bind("first")
        lifecycle.setTemporaryPlaybackSpeed(active = true)
        lifecycle.bind("second")

        assertEquals(VideoPlaybackSpeeds.NORMAL, engine.playbackSpeed)
        assertEquals(1, creationCount)
        assertEquals(
            listOf("speed:1.0", "pause", "set:second"),
            engine.events.windowed(size = 3).first { events -> events.last() == "set:second" },
        )

        lifecycle.setTemporaryPlaybackSpeed(active = true)
        lifecycle.releaseBinding()
        assertEquals(VideoPlaybackSpeeds.NORMAL, engine.playbackSpeed)

        lifecycle.bind("third")
        lifecycle.setTemporaryPlaybackSpeed(active = true)
        lifecycle.release()
        assertEquals(VideoPlaybackSpeeds.NORMAL, engine.playbackSpeed)
        assertEquals(1, creationCount)
    }

    @Test
    fun rejectedSpeedChangeFallsBackToNormalWithoutCrashing() {
        val engine = FakeReusablePlayerEngine().apply {
            rejectedSpeed = VideoPlaybackSpeeds.TEMPORARY_FAST_FORWARD
        }
        val lifecycle = ReusablePlayerLifecycle(
            factory = { engine },
            startOrder = PlaybackStartOrder.PREPARE_THEN_PLAY,
        )
        lifecycle.bind("first")

        assertEquals(
            VideoPlaybackSpeeds.NORMAL,
            lifecycle.setTemporaryPlaybackSpeed(active = true),
        )
        assertEquals(VideoPlaybackSpeeds.NORMAL, engine.playbackSpeed)
    }

    @Test
    fun temporarySpeedIntentSurvivesRebufferAndIsIdempotentlyReappliedOnRecovery() {
        val engine = FakeReusablePlayerEngine()
        val lifecycle = lifecycle(engine)

        lifecycle.bind("first", bindingGeneration = 1L)
        lifecycle.setTemporaryPlaybackSpeed(active = true)
        engine.forcePlaybackSpeed(VideoPlaybackSpeeds.NORMAL)
        engine.events.clear()

        assertEquals(
            VideoPlaybackSpeeds.TEMPORARY_FAST_FORWARD,
            lifecycle.reconcileTemporaryPlaybackSpeed(
                bindingGeneration = 1L,
                playbackState = ReusablePlaybackState.BUFFERING,
                isPlaying = false,
                playWhenReady = true,
                isSuppressed = false,
            ),
        )
        assertEquals(listOf("speed:2.0"), engine.events)

        assertEquals(
            VideoPlaybackSpeeds.TEMPORARY_FAST_FORWARD,
            lifecycle.reconcileTemporaryPlaybackSpeed(
                bindingGeneration = 1L,
                playbackState = ReusablePlaybackState.READY,
                isPlaying = true,
                playWhenReady = true,
                isSuppressed = false,
            ),
        )
        assertEquals(listOf("speed:2.0"), engine.events)
    }

    @Test
    fun releasingHoldDuringBufferingKeepsRecoveryAtNormalSpeed() {
        val engine = FakeReusablePlayerEngine()
        val lifecycle = lifecycle(engine)
        lifecycle.bind("first", bindingGeneration = 1L)
        lifecycle.setTemporaryPlaybackSpeed(active = true)

        lifecycle.terminateTemporaryPlaybackSpeed(
            TemporaryPlaybackSpeedTermination.USER_RELEASE,
        )
        lifecycle.reconcileTemporaryPlaybackSpeed(
            bindingGeneration = 1L,
            playbackState = ReusablePlaybackState.BUFFERING,
            isPlaying = false,
            playWhenReady = true,
            isSuppressed = false,
        )
        lifecycle.reconcileTemporaryPlaybackSpeed(
            bindingGeneration = 1L,
            playbackState = ReusablePlaybackState.READY,
            isPlaying = true,
            playWhenReady = true,
            isSuppressed = false,
        )

        assertEquals(VideoPlaybackSpeeds.NORMAL, engine.playbackSpeed)
        assertEquals(1, engine.events.count { event -> event == "speed:1.0" })
    }

    @Test
    fun terminalPlaybackContextsClearIntentAndCannotReapplyTemporarySpeed() {
        val endedEngine = FakeReusablePlayerEngine()
        val endedLifecycle = lifecycle(endedEngine)
        endedLifecycle.bind("ended", bindingGeneration = 1L)
        endedLifecycle.setTemporaryPlaybackSpeed(active = true)
        endedLifecycle.reconcileTemporaryPlaybackSpeed(
            bindingGeneration = 1L,
            playbackState = ReusablePlaybackState.ENDED,
            isPlaying = false,
            playWhenReady = true,
            isSuppressed = false,
        )
        assertRecoveryRemainsNormal(endedLifecycle, endedEngine, 1L)

        val suppressedEngine = FakeReusablePlayerEngine()
        val suppressedLifecycle = lifecycle(suppressedEngine)
        suppressedLifecycle.bind("suppressed", bindingGeneration = 2L)
        suppressedLifecycle.setTemporaryPlaybackSpeed(active = true)
        suppressedLifecycle.reconcileTemporaryPlaybackSpeed(
            bindingGeneration = 2L,
            playbackState = ReusablePlaybackState.READY,
            isPlaying = false,
            playWhenReady = true,
            isSuppressed = true,
        )
        assertRecoveryRemainsNormal(suppressedLifecycle, suppressedEngine, 2L)
    }

    @Test
    fun everyExplicitLifecycleBoundaryClearsIntentAndRestoresNormalSpeed() {
        val terminations = listOf(
            TemporaryPlaybackSpeedTermination.PAUSE,
            TemporaryPlaybackSpeedTermination.SEEK,
            TemporaryPlaybackSpeedTermination.PAGE_UNSTABLE,
            TemporaryPlaybackSpeedTermination.BACKGROUND,
            TemporaryPlaybackSpeedTermination.FAILURE,
        )
        terminations.forEachIndexed { index, termination ->
            val engine = FakeReusablePlayerEngine()
            val lifecycle = lifecycle(engine)
            val bindingGeneration = index + 1L
            lifecycle.bind("video-$index", bindingGeneration = bindingGeneration)
            lifecycle.setTemporaryPlaybackSpeed(active = true)

            lifecycle.terminateTemporaryPlaybackSpeed(termination)

            assertRecoveryRemainsNormal(lifecycle, engine, bindingGeneration)
        }

        val reboundEngine = FakeReusablePlayerEngine()
        val reboundLifecycle = lifecycle(reboundEngine)
        reboundLifecycle.bind("old", bindingGeneration = 10L)
        reboundLifecycle.setTemporaryPlaybackSpeed(active = true)
        reboundLifecycle.bind("new", bindingGeneration = 11L)
        assertEquals(VideoPlaybackSpeeds.NORMAL, reboundEngine.playbackSpeed)

        val unboundEngine = FakeReusablePlayerEngine()
        val unboundLifecycle = lifecycle(unboundEngine)
        unboundLifecycle.bind("unbound", bindingGeneration = 20L)
        unboundLifecycle.setTemporaryPlaybackSpeed(active = true)
        unboundLifecycle.releaseBinding()
        assertEquals(VideoPlaybackSpeeds.NORMAL, unboundEngine.playbackSpeed)

        val releasedEngine = FakeReusablePlayerEngine()
        val releasedLifecycle = lifecycle(releasedEngine)
        releasedLifecycle.bind("released", bindingGeneration = 30L)
        releasedLifecycle.setTemporaryPlaybackSpeed(active = true)
        releasedLifecycle.release()
        assertEquals(VideoPlaybackSpeeds.NORMAL, releasedEngine.playbackSpeed)
    }

    @Test
    fun latePlaybackCallbackFromOldBindingCannotChangeNewBindingSpeed() {
        val engine = FakeReusablePlayerEngine()
        val lifecycle = lifecycle(engine)
        lifecycle.bind("old", bindingGeneration = 1L)
        lifecycle.setTemporaryPlaybackSpeed(active = true)
        lifecycle.bind("new", bindingGeneration = 2L)
        engine.events.clear()

        lifecycle.reconcileTemporaryPlaybackSpeed(
            bindingGeneration = 1L,
            playbackState = ReusablePlaybackState.BUFFERING,
            isPlaying = false,
            playWhenReady = true,
            isSuppressed = false,
        )

        assertEquals(VideoPlaybackSpeeds.NORMAL, engine.playbackSpeed)
        assertEquals(emptyList<String>(), engine.events)
    }

    @Test
    fun failedStandbyPreparationCannotPromoteAndReleasesPartialMedia() {
        val engine = FakeReusablePlayerEngine().apply { failPrepare = true }
        val lifecycle = lifecycle(engine)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            lifecycle.prepareStandby("partial", "next")
        }
        assertEquals(false, lifecycle.hasPreparedStandby("next"))
        assertEquals(null, lifecycle.promotePrepared("next", 1L))
        lifecycle.releaseStandby()
        lifecycle.releaseStandby()
        assertEquals(1, engine.events.count { it == "clear" })
        assertEquals(1, engine.events.count { it == "release" })
        assertEquals(0, lifecycle.instanceCount)
    }

    @Test
    fun standbyTeardownFailureStillReleasesEngineAndInvalidatesOwnership() {
        val engine = FakeReusablePlayerEngine()
        val lifecycle = lifecycle(engine)
        lifecycle.prepareStandby("next", "next")
        engine.failClear = true
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            lifecycle.releaseStandby()
        }
        assertEquals(null, lifecycle.preparedEngine)
        assertEquals(0, lifecycle.instanceCount)
        assertEquals(false, lifecycle.hasPreparedStandby("next"))
        assertEquals(1, engine.events.count { it == "release" })
        lifecycle.releaseStandby()
    }

    @Test
    fun actualGesturePausePreservesPreparedTokenUntilSettlement() {
        val engines = mutableListOf<FakeReusablePlayerEngine>()
        val lifecycle = ReusablePlayerLifecycle(
            factory = { FakeReusablePlayerEngine().also(engines::add) },
            startOrder = PlaybackStartOrder.PREPARE_THEN_PLAY,
        )
        lifecycle.bind("current")
        repeat(100) { index ->
            lifecycle.prepareStandby("next-$index", "token-$index")
            val prepared = lifecycle.preparedEngine
            lifecycle.pauseForPageTransition()
            assertTrue(lifecycle.hasPreparedStandby("token-$index"))
            assertEquals(prepared, lifecycle.promotePrepared("token-$index", index + 2L))
            lifecycle.clearStandby()
        }
        assertEquals(2, engines.size)
        lifecycle.release()
        assertEquals(0, lifecycle.instanceCount)
    }

    private fun lifecycle(
        engine: FakeReusablePlayerEngine,
    ): ReusablePlayerLifecycle<String> = ReusablePlayerLifecycle(
        factory = { engine },
        startOrder = PlaybackStartOrder.PREPARE_THEN_PLAY,
    )

    private fun assertRecoveryRemainsNormal(
        lifecycle: ReusablePlayerLifecycle<String>,
        engine: FakeReusablePlayerEngine,
        bindingGeneration: Long,
    ) {
        lifecycle.reconcileTemporaryPlaybackSpeed(
            bindingGeneration = bindingGeneration,
            playbackState = ReusablePlaybackState.READY,
            isPlaying = true,
            playWhenReady = true,
            isSuppressed = false,
        )
        assertEquals(VideoPlaybackSpeeds.NORMAL, engine.playbackSpeed)
    }

    private class FakeReusablePlayerEngine : ReusablePlayerEngine<String> {
        val events = mutableListOf<String>()
        override var playbackSpeed: Float = VideoPlaybackSpeeds.NORMAL
            private set
        var rejectedSpeed: Float? = null
        var failPrepare = false
        var failClear = false

        fun forcePlaybackSpeed(speed: Float) {
            playbackSpeed = speed
        }

        override fun setMedia(media: String) {
            events += "set:$media"
        }

        override fun prepare() {
            events += "prepare"
            if (failPrepare) error("fixture prepare failed")
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            events += "playWhenReady:$playWhenReady"
        }

        override fun pause() {
            events += "pause"
        }

        override fun setPlaybackSpeed(speed: Float) {
            events += "speed:$speed"
            if (speed == rejectedSpeed) throw IllegalStateException("speed rejected")
            playbackSpeed = speed
        }

        override fun clearMedia() {
            events += "clear"
            if (failClear) error("fixture clear failed")
        }

        override fun release() {
            events += "release"
        }
    }

    private class FakePlayerView {
        var player: String? = null
    }
}
