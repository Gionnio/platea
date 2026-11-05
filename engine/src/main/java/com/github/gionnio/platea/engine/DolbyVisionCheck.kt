package com.github.gionnio.platea.engine

/**
 * Tells when a Dolby Vision file ends up on a decoder that ignores the Dolby Vision data, on a device and TV
 * that were expected to show it. The player does this silently (decoder fallback); Platea says it.
 */
object DolbyVisionCheck {
    /**
     * @param rangeType Jellyfin `VideoRangeType` of the file, e.g. `DOVIWithHDR10`
     * @param hasDolbyVisionData some files carry the Dolby Vision data without the range type saying so
     * @param converting the server is converting the video, so what reaches the decoder is not the file
     * @return `null` when there is nothing to report, otherwise what is shown instead
     */
    fun lost(
        rangeType: String?,
        hasDolbyVisionData: Boolean,
        converting: Boolean,
        capabilities: DeviceCapabilities?,
        decoderName: String,
    ): Shown? {
        val dolbyVisionFile = rangeType?.startsWith("DOVI") == true || hasDolbyVisionData
        if (!dolbyVisionFile || converting || capabilities == null) return null
        val decoders = capabilities.video.dolbyVisionDecoders
        // A TV or a device without Dolby Vision plays the base layer by design: nothing was lost
        if (!capabilities.display.dolbyVision || decoders.isEmpty()) return null
        if (decoders.any { it.equals(decoderName, ignoreCase = true) }) return null
        return when {
            rangeType == null -> Shown.HDR10
            "HLG" in rangeType -> Shown.HLG
            "SDR" in rangeType -> Shown.SDR
            "HDR10" in rangeType || "EL" in rangeType -> Shown.HDR10
            // Profile 5: there is no standard picture under the Dolby Vision data
            else -> Shown.WRONG_COLOURS
        }
    }

    enum class Shown { HDR10, HLG, SDR, WRONG_COLOURS }
}
