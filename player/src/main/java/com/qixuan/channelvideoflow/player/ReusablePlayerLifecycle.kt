package com.qixuan.channelvideoflow.player

internal enum class PlaybackStartOrder {
    PREPARE_THEN_PLAY,
    PLAY_THEN_PREPARE,
}

internal enum class ReusablePlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
}

internal enum class TemporaryPlaybackSpeedTermination {
    USER_RELEASE,
    PAUSE,
    SEEK,
    PAGE_UNSTABLE,
    BACKGROUND,
    ENDED,
    FAILURE,
    SUPPRESSION,
    NEW_BINDING,
    UNBIND,
    RELEASE,
    INVALID_PLAYBACK_CONTEXT,
}

/** Small boundary around the Media3 player operations that matter during a media replacement. */
internal interface ReusablePlayerEngine<Media> {
    val playbackSpeed: Float

    fun setActive(active: Boolean) = Unit

    fun setMedia(media: Media)

    fun prepare()

    fun setPlayWhenReady(playWhenReady: Boolean)

    fun pause()

    fun setPlaybackSpeed(speed: Float)

    fun clearMedia()

    fun release()
}

/**
 * Owns one reusable playback engine independently of the number of pager items.
 *
 * A normal media replacement pauses the old audio and replaces the media directly. Clearing the
 * playlist is reserved for an explicit binding release, while releasing the engine remains a page
 * lifecycle operation.
 */
internal class ReusablePlayerLifecycle<Media>(
    private val factory: () -> ReusablePlayerEngine<Media>,
    private val startOrder: PlaybackStartOrder,
) {
    private var engine: ReusablePlayerEngine<Media>? = null
    private var standbyEngine: ReusablePlayerEngine<Media>? = null
    private var standbyToken: String? = null
    private var standbyHasBinding = false
    private var hasBinding = false
    private var generatedBindingGeneration = 0L
    private var activeBindingGeneration: Long? = null
    private var temporaryPlaybackSpeedRequested = false

    var instanceCount: Int = 0
        private set

    val currentEngine: ReusablePlayerEngine<Media>?
        get() = engine

    val preparedEngine: ReusablePlayerEngine<Media>?
        get() = standbyEngine

    fun ensureEngine(): ReusablePlayerEngine<Media> = engine ?: factory().also { created ->
        engine = created
        instanceCount += 1
    }

    fun bind(
        media: Media,
        bindingGeneration: Long = nextBindingGeneration(),
        onPrepare: () -> Unit = {},
    ): ReusablePlayerEngine<Media> {
        val target = ensureEngine()
        terminateTemporaryPlaybackSpeed(TemporaryPlaybackSpeedTermination.NEW_BINDING)
        activeBindingGeneration = bindingGeneration
        applyPlaybackSpeed(target, VideoPlaybackSpeeds.NORMAL)
        if (hasBinding) target.pause()
        target.setActive(true)
        target.setMedia(media)
        when (startOrder) {
            PlaybackStartOrder.PREPARE_THEN_PLAY -> {
                onPrepare()
                target.prepare()
                target.setPlayWhenReady(true)
            }

            PlaybackStartOrder.PLAY_THEN_PREPARE -> {
                target.setPlayWhenReady(true)
                onPrepare()
                target.prepare()
            }
        }
        hasBinding = true
        return target
    }

    fun releaseBinding() {
        terminateTemporaryPlaybackSpeed(TemporaryPlaybackSpeedTermination.UNBIND)
        activeBindingGeneration = null
        if (!hasBinding) return
        engine?.pause()
        engine?.clearMedia()
        hasBinding = false
    }

    /**
     * Prepares one bounded, silent candidate without changing the active engine. The caller owns
     * the byte budget and must use a token that changes whenever the target or queue generation
     * changes.
     */
    fun prepareStandby(
        media: Media,
        token: String,
        onPrepare: () -> Unit = {},
    ): Boolean {
        if (token.isBlank()) return false
        val target = standbyEngine ?: factory().also {
            standbyEngine = it
            instanceCount += 1
        }
        if (standbyHasBinding && standbyToken == token) return true
        if (standbyHasBinding) {
            target.pause()
            target.clearMedia()
        }
        standbyToken = null
        target.setActive(false)
        target.setPlayWhenReady(false)
        target.setPlaybackSpeed(VideoPlaybackSpeeds.NORMAL)
        // Track a partial binding so cancellation also clears a failed setMedia/prepare call.
        standbyHasBinding = true
        target.setMedia(media)
        onPrepare()
        target.prepare()
        // A standby engine is never allowed to start playback or acquire audio focus.
        target.setPlayWhenReady(false)
        standbyToken = token
        return true
    }

    fun hasPreparedStandby(token: String): Boolean =
        standbyHasBinding && standbyToken == token

    /** Promotes the exact prepared candidate and clears the retired active engine. */
    fun promotePrepared(
        token: String,
        bindingGeneration: Long,
    ): ReusablePlayerEngine<Media>? {
        if (!hasPreparedStandby(token)) return null
        val previousActive = engine
        val promoted = standbyEngine ?: return null
        previousActive?.pause()
        previousActive?.setActive(false)
        previousActive?.clearMedia()
        terminateTemporaryPlaybackSpeed(TemporaryPlaybackSpeedTermination.NEW_BINDING)
        promoted.setPlaybackSpeed(VideoPlaybackSpeeds.NORMAL)
        engine = promoted
        standbyEngine = previousActive
        standbyToken = null
        standbyHasBinding = false
        hasBinding = true
        activeBindingGeneration = bindingGeneration
        generatedBindingGeneration = maxOf(generatedBindingGeneration, bindingGeneration)
        return promoted
    }

    fun clearStandby() {
        val target = standbyEngine ?: return
        val hadBinding = standbyHasBinding
        standbyToken = null
        standbyHasBinding = false
        if (hadBinding) {
            try {
                target.pause()
            } finally {
                target.clearMedia()
            }
        }
    }

    /** A gesture pauses only ACTIVE; the exact next binding must survive until settlement. */
    fun pauseForPageTransition() {
        terminateTemporaryPlaybackSpeed(TemporaryPlaybackSpeedTermination.PAGE_UNSTABLE)
        engine?.pause()
    }

    fun releaseStandby() {
        val target = standbyEngine ?: return
        try {
            clearStandby()
        } finally {
            standbyEngine = null
            instanceCount -= 1
            target.release()
        }
    }

    fun release() {
        terminateTemporaryPlaybackSpeed(TemporaryPlaybackSpeedTermination.RELEASE)
        releaseBinding()
        engine?.release()
        engine = null
        standbyEngine?.release()
        standbyEngine = null
        standbyToken = null
        standbyHasBinding = false
        instanceCount = 0
    }

    /** Never creates an engine solely to change speed and reports only a verified applied value. */
    fun setTemporaryPlaybackSpeed(active: Boolean): Float {
        val target = engine ?: return VideoPlaybackSpeeds.NORMAL
        temporaryPlaybackSpeedRequested = active && hasBinding && activeBindingGeneration != null
        val requested = if (temporaryPlaybackSpeedRequested) {
            VideoPlaybackSpeeds.TEMPORARY_FAST_FORWARD
        } else {
            VideoPlaybackSpeeds.NORMAL
        }
        return applyPlaybackSpeed(target, requested)
    }

    /**
     * Keeps a valid press-and-hold intent across transient rebuffering, while rejecting stale
     * callbacks and clearing the intent for every non-playing terminal context.
     */
    fun reconcileTemporaryPlaybackSpeed(
        bindingGeneration: Long,
        playbackState: ReusablePlaybackState,
        isPlaying: Boolean,
        playWhenReady: Boolean,
        isSuppressed: Boolean,
    ): Float {
        val target = engine ?: return VideoPlaybackSpeeds.NORMAL
        if (!hasBinding || bindingGeneration != activeBindingGeneration) {
            return currentPlaybackSpeed(target)
        }
        val transientRebuffer = playbackState == ReusablePlaybackState.BUFFERING &&
            playWhenReady &&
            !isSuppressed
        val activelyPlaying = playbackState == ReusablePlaybackState.READY &&
            isPlaying &&
            playWhenReady &&
            !isSuppressed
        if (temporaryPlaybackSpeedRequested && !transientRebuffer && !activelyPlaying) {
            terminateTemporaryPlaybackSpeed(
                when {
                    isSuppressed -> TemporaryPlaybackSpeedTermination.SUPPRESSION
                    playbackState == ReusablePlaybackState.ENDED ->
                        TemporaryPlaybackSpeedTermination.ENDED
                    !playWhenReady -> TemporaryPlaybackSpeedTermination.PAUSE
                    else -> TemporaryPlaybackSpeedTermination.INVALID_PLAYBACK_CONTEXT
                },
            )
        }
        val requested = if (
            temporaryPlaybackSpeedRequested && (transientRebuffer || activelyPlaying)
        ) {
            VideoPlaybackSpeeds.TEMPORARY_FAST_FORWARD
        } else {
            VideoPlaybackSpeeds.NORMAL
        }
        return applyPlaybackSpeed(target, requested)
    }

    fun terminateTemporaryPlaybackSpeed(reason: TemporaryPlaybackSpeedTermination): Float {
        when (reason) {
            TemporaryPlaybackSpeedTermination.USER_RELEASE,
            TemporaryPlaybackSpeedTermination.PAUSE,
            TemporaryPlaybackSpeedTermination.SEEK,
            TemporaryPlaybackSpeedTermination.PAGE_UNSTABLE,
            TemporaryPlaybackSpeedTermination.BACKGROUND,
            TemporaryPlaybackSpeedTermination.ENDED,
            TemporaryPlaybackSpeedTermination.FAILURE,
            TemporaryPlaybackSpeedTermination.SUPPRESSION,
            TemporaryPlaybackSpeedTermination.NEW_BINDING,
            TemporaryPlaybackSpeedTermination.UNBIND,
            TemporaryPlaybackSpeedTermination.RELEASE,
            TemporaryPlaybackSpeedTermination.INVALID_PLAYBACK_CONTEXT,
            -> temporaryPlaybackSpeedRequested = false
        }
        val target = engine ?: return VideoPlaybackSpeeds.NORMAL
        return applyPlaybackSpeed(target, VideoPlaybackSpeeds.NORMAL)
    }

    private fun nextBindingGeneration(): Long {
        generatedBindingGeneration += 1L
        return generatedBindingGeneration
    }

    private fun currentPlaybackSpeed(target: ReusablePlayerEngine<Media>): Float = try {
        target.playbackSpeed
    } catch (_: Exception) {
        VideoPlaybackSpeeds.NORMAL
    }

    private fun applyPlaybackSpeed(
        target: ReusablePlayerEngine<Media>,
        requested: Float,
    ): Float {
        val current = try {
            target.playbackSpeed
        } catch (_: Exception) {
            VideoPlaybackSpeeds.NORMAL
        }
        if (current == requested) return current
        return try {
            target.setPlaybackSpeed(requested)
            target.playbackSpeed.takeIf { applied -> applied == requested }
                ?: restoreNormalPlaybackSpeed(target)
        } catch (_: Exception) {
            restoreNormalPlaybackSpeed(target)
        }
    }

    private fun restoreNormalPlaybackSpeed(target: ReusablePlayerEngine<Media>): Float {
        try {
            if (target.playbackSpeed != VideoPlaybackSpeeds.NORMAL) {
                target.setPlaybackSpeed(VideoPlaybackSpeeds.NORMAL)
            }
        } catch (_: Exception) {
            return VideoPlaybackSpeeds.NORMAL
        }
        return try {
            target.playbackSpeed.takeIf { speed -> speed == VideoPlaybackSpeeds.NORMAL }
                ?: VideoPlaybackSpeeds.NORMAL
        } catch (_: Exception) {
            VideoPlaybackSpeeds.NORMAL
        }
    }
}

internal data class ViewBindingChange(
    val attached: Boolean,
    val detached: Boolean,
)

/** Keeps one player attached to at most one stable platform view. */
internal class StablePlayerViewBinding<View : Any, Player : Any>(
    private val currentPlayer: (View) -> Player?,
    private val setPlayer: (View, Player?) -> Unit,
) {
    private var activeView: View? = null

    fun attach(view: View, player: Player): ViewBindingChange {
        if (activeView === view && currentPlayer(view) === player) {
            return ViewBindingChange(attached = false, detached = false)
        }
        val detached = activeView?.let { previous ->
            setPlayer(previous, null)
            true
        } ?: false
        setPlayer(view, player)
        activeView = view
        return ViewBindingChange(attached = true, detached = detached)
    }

    fun detach(view: View): Boolean {
        if (activeView !== view) return false
        setPlayer(view, null)
        activeView = null
        return true
    }

    fun detachActive(): Boolean {
        val view = activeView ?: return false
        return detach(view)
    }

    fun forActiveView(action: (View) -> Unit) { activeView?.let(action) }

    fun isAttachedTo(player: Player): Boolean = activeView?.let { currentPlayer(it) === player } == true

    fun replacePlayer(player: Player): Boolean {
        val view = activeView ?: return false
        setPlayer(view, player)
        return true
    }
}
