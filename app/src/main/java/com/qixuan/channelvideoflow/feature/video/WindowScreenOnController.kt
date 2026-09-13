package com.qixuan.channelvideoflow.feature.video

import android.view.Window
import android.view.WindowManager

/**
 * App-layer owner for the playback screen-on window flag.
 *
 * `FLAG_KEEP_SCREEN_ON` is a window-level request: it suppresses the system display timeout only
 * while this window is visible, so backgrounding the app needs no extra handling. The flag is
 * cleared as soon as playback is not running, which hands the screen back to the user's configured
 * display timeout instead of permanently changing it.
 */
internal class WindowScreenOnController(
    private val window: Window,
) {
    fun setKeepScreenOn(keepScreenOn: Boolean) {
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
