package com.github.gionnio.platea.engine

import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream

/**
 * Playback decisions for one title: play method, decoder, audio/subtitle tracks and display mode.
 *
 * Direct play is the default policy; server transcoding is only an emergency fallback, with a clear reason.
 * The engine knows nothing about the UI: the GUI asks for a [PlaybackPlan] and shows it.
 */
interface PlaybackEngine {
    /**
     * Decides how to play [source] with the given capabilities and preferences.
     * Pure decision: no player is created and the display is not touched.
     */
    suspend fun plan(
        source: MediaSourceInfo,
        capabilities: DeviceCapabilities,
        preferences: TrackPreferences,
    ): PlaybackPlan

    /**
     * Chooses the decoder for a video track: the Dolby Vision decoder for `dvhe`/`dvh1`/`dvav` when available,
     * otherwise an explicit and logged fallback to the HEVC base layer.
     */
    fun selectDecoder(
        video: MediaStream,
        capabilities: DeviceCapabilities,
    ): DecoderChoice

    /**
     * Switches refresh rate and dynamic range before the player starts, waiting for the display confirmation
     * (mode change event) with a safety timeout.
     */
    suspend fun prepareDisplay(plan: PlaybackPlan): DisplaySwitchResult

    /** Restores the display mode that was active before playback. */
    suspend fun restoreDisplay()
}

/** Track preferences. Every option can be switched off. */
data class TrackPreferences(
    val audioLanguages: List<String>,
    val subtitleLanguages: List<String>,
    val subtitleMode: SubtitleMode = SubtitleMode.FORCED_ONLY,
    val preferPassthrough: Boolean = true,
    val switchRefreshRate: Boolean = true,
    val switchDynamicRange: Boolean = true,
)

enum class SubtitleMode { NONE, FORCED_ONLY, DEFAULT_FLAG, ALWAYS }

enum class PlayMethod { DIRECT_PLAY, REMUX, TRANSCODE }

/** Result of [PlaybackEngine.plan]. */
data class PlaybackPlan(
    val method: PlayMethod,
    /** Why it is not direct play (`null` when it is), shown to the user and in the logs. */
    val reason: String?,
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
    val decoder: DecoderChoice,
    val targetDisplayMode: DisplayModeInfo?,
)

data class DecoderChoice(
    /** MediaCodec name, e.g. `c2.dolby.decoder.hevc` or `OMX.MTK.VIDEO.DECODER.HEVC`. */
    val codecName: String?,
    val mimeType: String,
    val isDolbyVision: Boolean,
    /** Set when a fallback was needed (e.g. P7 FEL not supported → HDR10 base layer). */
    val fallbackReason: String?,
)

sealed interface DisplaySwitchResult {
    data object NotNeeded : DisplaySwitchResult

    data class Switched(
        val mode: DisplayModeInfo,
        val waitedMillis: Long,
    ) : DisplaySwitchResult

    data class TimedOut(
        val requested: DisplayModeInfo,
    ) : DisplaySwitchResult

    data class Failed(
        val message: String,
    ) : DisplaySwitchResult
}
