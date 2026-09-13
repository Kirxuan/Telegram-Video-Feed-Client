package com.qixuan.channelvideoflow.domain.media

import com.qixuan.channelvideoflow.model.video.IndexedVideo
import com.qixuan.channelvideoflow.model.video.VideoPlaybackVariant
import com.qixuan.channelvideoflow.model.video.VideoQualityPreference

/**
 * Chooses one direct Telegram file while preserving the message identity and original metadata.
 *
 * Only H.264 alternatives are eligible in this first stage because API 26+ devices are required to
 * decode AVC, while HEVC/AV1 support remains device-dependent. If no safe alternative exists, the
 * original Telegram file remains selected.
 */
object VideoQualitySelector {
    /**
     * Standby quality ceiling, expressed as the Telegram short edge so portrait and landscape
     * encodes are both covered. Five seconds of an original can cost ~10 MiB while five seconds of
     * 480p costs ~0.6 MiB, so the cheaper representation buys far more prepared wall-clock time
     * inside the same byte budget and is the main lever on promotion hit rate.
     */
    const val STANDBY_MAX_SHORT_EDGE = 480

    /** Freeze the next AUTO representation so bandwidth updates cannot undo prepared samples. */
    fun selectForStandby(
        video: IndexedVideo,
        preference: VideoQualityPreference,
        network: NetworkTransport,
    ): IndexedVideo {
        if (preference != VideoQualityPreference.AUTO || !video.supportsStreaming) {
            return select(video, preference, network)
        }
        val alternatives = video.alternativeVariants.filter {
            it.isEligible() && it.fileId != video.fileId && it.isLowerCostThan(video)
        }
        // Short edge supports both portrait and landscape Telegram encodes.
        val low = alternatives.filter { minOf(it.width, it.height) <= STANDBY_MAX_SHORT_EDGE }
        val bestResolution = low.maxOfOrNull { minOf(it.width, it.height) }
        val selected = low.filter { minOf(it.width, it.height) == bestResolution }.minByKnownSize()
            ?: selectDataSaver(video, alternatives)
        return video.copy(selectedAlternative = selected)
    }

    fun select(
        video: IndexedVideo,
        preference: VideoQualityPreference,
        network: NetworkTransport,
        availableBandwidthBitsPerSecond: Long? = null,
    ): IndexedVideo {
        if (!video.supportsStreaming || preference == VideoQualityPreference.ORIGINAL) {
            return video.copy(selectedAlternative = null)
        }

        val alternatives = video.alternativeVariants
            .asSequence()
            .filter { variant -> variant.isEligible() }
            .filterNot { variant -> variant.fileId == video.fileId }
            .distinctBy(VideoPlaybackVariant::fileId)
            .filter { variant -> variant.isLowerCostThan(video) }
            .toList()
        if (alternatives.isEmpty()) return video.copy(selectedAlternative = null)

        if (preference == VideoQualityPreference.AUTO && network == NetworkTransport.WIFI &&
            availableBandwidthBitsPerSecond != null
        ) {
            return video.copy(
                selectedAlternative = selectSustainable(
                    video = video,
                    alternatives = alternatives,
                    availableBandwidthBitsPerSecond = availableBandwidthBitsPerSecond,
                ),
            )
        }

        val effectivePreference = when (preference) {
            VideoQualityPreference.AUTO -> when (network) {
                NetworkTransport.WIFI -> VideoQualityPreference.HD_720
                NetworkTransport.MOBILE,
                NetworkTransport.OTHER,
                NetworkTransport.OFFLINE,
                -> VideoQualityPreference.DATA_SAVER
            }
            else -> preference
        }
        val selected = when (effectivePreference) {
            VideoQualityPreference.DATA_SAVER -> selectDataSaver(video, alternatives)
            VideoQualityPreference.HD_720 -> selectAtMost720p(video, alternatives)
            VideoQualityPreference.AUTO,
            VideoQualityPreference.ORIGINAL,
            -> null
        }
        return video.copy(selectedAlternative = selected)
    }

    private fun selectSustainable(
        video: IndexedVideo,
        alternatives: List<VideoPlaybackVariant>,
        availableBandwidthBitsPerSecond: Long,
    ): VideoPlaybackVariant? {
        val durationSeconds = video.durationSeconds.takeIf { duration -> duration > 0 }
            ?: return selectDataSaver(video, alternatives)
        val ranked = alternatives.mapNotNull { variant ->
            variant.estimatedBitrate(durationSeconds)?.let { bitrate -> variant to bitrate }
        }
        if (ranked.isEmpty()) return selectDataSaver(video, alternatives)
        val sustainable = ranked.filter { (_, bitrate) ->
            bitrate <= availableBandwidthBitsPerSecond.coerceAtLeast(0L)
        }
        return (sustainable.ifEmpty { ranked })
            .minWithOrNull(
                if (sustainable.isEmpty()) {
                    compareBy<Pair<VideoPlaybackVariant, Long>> { (_, bitrate) -> bitrate }
                        .thenBy { (variant, _) -> variant.pixelCount() }
                        .thenBy { (variant, _) -> variant.fileId }
                } else {
                    compareByDescending<Pair<VideoPlaybackVariant, Long>> { (_, bitrate) -> bitrate }
                        .thenByDescending { (variant, _) -> variant.pixelCount() }
                        .thenBy { (variant, _) -> variant.fileSize ?: Long.MAX_VALUE }
                        .thenBy { (variant, _) -> variant.fileId }
                },
            )
            ?.first
            ?.takeIf { candidate -> candidate.isLowerCostThan(video) }
    }

    private fun selectAtMost720p(
        video: IndexedVideo,
        alternatives: List<VideoPlaybackVariant>,
    ): VideoPlaybackVariant? {
        if (video.pixelCount() <= HD_720_PIXEL_COUNT) return null
        val withinBudget = alternatives
            .filter { variant -> variant.pixelCount() <= HD_720_PIXEL_COUNT }
        val highestPixelCount = withinBudget.maxOfOrNull { variant -> variant.pixelCount() }
        return highestPixelCount
            ?.let { target ->
                withinBudget
                    .filter { variant -> variant.pixelCount() == target }
                    .minByKnownSize()
            }
            ?: selectDataSaver(video, alternatives)
    }

    private fun selectDataSaver(
        video: IndexedVideo,
        alternatives: List<VideoPlaybackVariant>,
    ): VideoPlaybackVariant? {
        val candidate = alternatives.minByKnownSize() ?: return null
        val originalSize = video.fileSize?.takeIf { it > 0L }
        val candidateSize = candidate.fileSize?.takeIf { it > 0L }
        val isLowerCost = if (originalSize != null && candidateSize != null) {
            candidateSize < originalSize
        } else {
            candidate.pixelCount() < video.pixelCount()
        }
        return candidate.takeIf { isLowerCost }
    }

    private fun List<VideoPlaybackVariant>.minByKnownSize(): VideoPlaybackVariant? {
        val knownSizes = filter { variant -> variant.fileSize?.let { it > 0L } == true }
        return if (knownSizes.isNotEmpty()) {
            knownSizes.minWithOrNull(
                compareBy<VideoPlaybackVariant> { variant -> variant.fileSize }
                    .thenBy { variant -> variant.pixelCount() }
                    .thenBy(VideoPlaybackVariant::fileId),
            )
        } else {
            minWithOrNull(
                compareBy<VideoPlaybackVariant> { variant -> variant.pixelCount() }
                    .thenBy(VideoPlaybackVariant::fileId),
            )
        }
    }

    private fun VideoPlaybackVariant.isEligible(): Boolean =
        fileId > 0 &&
            width > 0 &&
            height > 0 &&
            codec.equals(H264_CODEC, ignoreCase = true)

    private fun IndexedVideo.pixelCount(): Long = width.toLong() * height.toLong()

    private fun VideoPlaybackVariant.pixelCount(): Long = width.toLong() * height.toLong()

    private fun VideoPlaybackVariant.estimatedBitrate(durationSeconds: Int): Long? {
        val size = fileSize?.takeIf { it > 0L } ?: return null
        val bits = if (size > Long.MAX_VALUE / 8L) Long.MAX_VALUE else size * 8L
        return bits / durationSeconds.coerceAtLeast(1)
    }

    private fun VideoPlaybackVariant.isLowerCostThan(video: IndexedVideo): Boolean {
        val originalSize = video.fileSize?.takeIf { it > 0L }
        val candidateSize = fileSize?.takeIf { it > 0L }
        return if (originalSize != null && candidateSize != null) {
            candidateSize < originalSize
        } else {
            pixelCount() < video.pixelCount()
        }
    }

    private const val H264_CODEC = "h264"
    private const val HD_720_PIXEL_COUNT = 1280L * 720L
}
