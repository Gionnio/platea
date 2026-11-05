package com.github.gionnio.platea.engine

/**
 * What the device can really decode and output, computed once on a stable display and cached.
 *
 * It is a snapshot: it changes only on real events (HDMI output change, firmware update, manual override),
 * never because the display is switching mode while a title starts.
 */
data class DeviceCapabilities(
    /** `Build.MODEL`, e.g. `AFTKRT` for the Fire TV Stick 4K Max (2nd gen). */
    val model: String,
    /** `Build.FINGERPRINT`, used to detect firmware updates and invalidate the cache. */
    val firmware: String,
    val video: VideoCapabilities,
    val display: DisplayCapabilities,
    val audio: AudioCapabilities,
    /** Model exceptions and user overrides already applied to the values above. */
    val appliedOverrides: List<String>,
    val computedAtMillis: Long,
)

/** Decoder capabilities read from `MediaCodecList`. */
data class VideoCapabilities(
    val hevcMain10: Boolean,
    val hevcHdr10: Boolean,
    val hevcHdr10Plus: Boolean,
    /** Dolby Vision decoder for profile 5 (`dvhe.05`). */
    val dolbyVisionProfile5: Boolean,
    /** Dolby Vision decoder for profile 8 (`dvhe.08`, HDR10/SDR/HLG compatible base layer). */
    val dolbyVisionProfile8: Boolean,
    /** Dual-layer profile 7 with enhancement layer (FEL/MEL). */
    val dolbyVisionProfile7: Boolean,
    val av1Main10: Boolean,
    val av1DolbyVision: Boolean,
    /** Names of the Dolby Vision decoders found, e.g. `c2.dolby.decoder.hevc`. */
    val dolbyVisionDecoders: List<String>,
    val maxWidth: Int,
    val maxHeight: Int,
)

/** HDR formats and modes of the connected display (`Display.HdrCapabilities`, `Display.Mode`). */
data class DisplayCapabilities(
    val dolbyVision: Boolean,
    val hdr10: Boolean,
    val hdr10Plus: Boolean,
    val hlg: Boolean,
    val modes: List<DisplayModeInfo>,
)

data class DisplayModeInfo(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

/**
 * Audio the device can play: passthrough to the HDMI sink (as seen by Media3 `AudioCapabilities`,
 * the same check ExoPlayer uses) and codecs decoded on the device.
 */
data class AudioCapabilities(
    val ac3: Boolean,
    val eac3: Boolean,
    /** E-AC3 with Atmos (JOC). */
    val eac3Joc: Boolean,
    val trueHd: Boolean,
    val dts: Boolean,
    val dtsHd: Boolean,
    val maxChannels: Int,
    /** Compressed audio MIME types with a platform decoder (e.g. `audio/eac3` via `c2.dolby.eac3.decoder`). */
    val platformDecoders: Set<String> = emptySet(),
)

/**
 * Manual capability override, from the per-model exception table or from the user settings
 * (e.g. "force Dolby Vision supported"). Every override can be switched off.
 */
data class CapabilityOverride(
    val key: String,
    val value: Boolean,
    /** Why it exists: a known firmware bug, a user choice... Shown in the diagnostic overlay. */
    val reason: String,
)
