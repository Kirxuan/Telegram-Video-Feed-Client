package com.qixuan.channelvideoflow.feature.video

import android.app.Activity
import android.view.WindowManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WindowScreenOnControllerTest {
    @Test
    fun playingSetsFlagAndPausingClearsIt() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val controller = WindowScreenOnController(activity.window)
        assertFalse(activity.window.hasKeepScreenOnFlag())

        controller.setKeepScreenOn(true)
        assertTrue(activity.window.hasKeepScreenOnFlag())

        controller.setKeepScreenOn(false)
        assertFalse(activity.window.hasKeepScreenOnFlag())
    }

    private fun android.view.Window.hasKeepScreenOnFlag(): Boolean =
        attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
}
