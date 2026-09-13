package com.qixuan.channelvideoflow.player

import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.util.EGLSurfaceTexture
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicBoolean

/** One consumed offscreen output for the C2 experiment; never used for protected content. */
@UnstableApi
internal class StandbyVideoSurface(
    private val callbackHandler: Handler,
    private val onReady: (Surface) -> Unit,
    private val onFailure: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val thread = HandlerThread("velora-standby-surface").apply { start() }
    private val handler = Handler(thread.looper)
    private val texture = EGLSurfaceTexture(handler)
    private var output: Surface? = null
    private val timeout = Runnable {
        if (!closed.get()) {
            close()
            onFailure()
        }
    }

    init {
        callbackHandler.postDelayed(timeout, 5_000L)
        handler.post(::initialize)
    }

    private fun initialize() {
        if (closed.get()) {
            releaseOnSurfaceThread()
            thread.quitSafely()
            return
        }
        try {
            texture.init(EGLSurfaceTexture.SECURE_MODE_NONE)
            val surface = Surface(texture.surfaceTexture).also { output = it }
            if (closed.get()) {
                releaseOnSurfaceThread()
                thread.quitSafely()
                return
            }
            callbackHandler.post {
                callbackHandler.removeCallbacks(timeout)
                if (!closed.get()) onReady(surface)
            }
        } catch (_: Exception) {
            releaseOnSurfaceThread()
            thread.quitSafely()
            reportFailure()
        } catch (_: LinkageError) {
            // A device without a usable EGL implementation must keep the C1 path alive.
            releaseOnSurfaceThread()
            thread.quitSafely()
            reportFailure()
        }
    }

    private fun reportFailure() {
        callbackHandler.post {
            callbackHandler.removeCallbacks(timeout)
            if (!closed.get()) onFailure()
        }
    }

    private fun releaseOnSurfaceThread() {
        // Release both resources even if one vendor cleanup call fails.
        runCatching { output?.release() }
        output = null
        runCatching { texture.release() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        callbackHandler.removeCallbacks(timeout)
        if (!handler.post {
            releaseOnSurfaceThread()
            thread.quitSafely()
        }) {
            // initialize() already released resources before stopping the thread.
            thread.quitSafely()
        }
    }
}
