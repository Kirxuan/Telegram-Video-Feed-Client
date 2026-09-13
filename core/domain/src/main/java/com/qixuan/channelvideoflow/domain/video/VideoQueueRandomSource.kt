package com.qixuan.channelvideoflow.domain.video

/** Injectable entropy boundary shared by the lightweight playback feed session and tests. */
fun interface VideoQueueRandomSource {
    fun nextInt(until: Int): Int
}
