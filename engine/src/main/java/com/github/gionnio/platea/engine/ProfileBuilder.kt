package com.github.gionnio.platea.engine

import org.jellyfin.sdk.model.api.DeviceProfile

/**
 * Builds the Jellyfin [DeviceProfile] sent with `PlaybackInfo`, starting only from [DeviceCapabilities].
 *
 * The same capabilities must always produce the same profile, so the server decision is repeatable.
 * Every Jellyfin `VideoRangeType` (DOVI, DOVIWithHDR10, DOVIWithHDR10Plus, DOVIWithSDR, DOVIWithHLG,
 * DOVIWithEL, HDR10, HDR10Plus, HLG...) is declared explicitly as supported or not.
 */
interface ProfileBuilder {
    fun build(
        capabilities: DeviceCapabilities,
        policy: ProfilePolicy,
    ): DeviceProfile
}

/** User choices that shape the profile. */
data class ProfilePolicy(
    /** Maximum bitrate in bit/s, `null` = no limit (local network, curated library). */
    val maxBitrate: Int?,
    /** Image subtitles (PGS, DVD, DVB) are drawn by the client and never burned in by the server. */
    val clientRendersImageSubtitles: Boolean = true,
    /** ASS/SSA rendered by the client (libass). */
    val clientRendersAss: Boolean = true,
    /** A software audio decoder (Media3 ffmpeg extension) is available for codecs without passthrough. */
    val softwareAudioDecoder: Boolean = false,
    /** Allow Dolby Vision with enhancement layer (profile 7) in direct play. */
    val allowDolbyVisionEnhancementLayer: Boolean = false,
    /** Exclude Dolby Vision + HDR10+ files from direct play (known firmware issue on some devices). */
    val excludeDolbyVisionWithHdr10Plus: Boolean = false,
    /** Server version, for logging. */
    val serverVersion: String?,
)
