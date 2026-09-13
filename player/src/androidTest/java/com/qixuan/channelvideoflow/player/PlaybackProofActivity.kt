package com.qixuan.channelvideoflow.player

import android.app.Activity
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/** Only packaged in the account-free player test APK. */
@UnstableApi
class PlaybackProofActivity : Activity() {
    lateinit var playerView: PlayerView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerView = PlayerView(this)
        setContentView(playerView)
    }
}
