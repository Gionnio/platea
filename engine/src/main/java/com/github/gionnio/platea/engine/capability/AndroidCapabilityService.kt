package com.github.gionnio.platea.engine.capability

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.exoplayer.audio.AudioCapabilities as Media3AudioCapabilities
import androidx.annotation.RequiresApi
import com.github.gionnio.platea.engine.AudioCapabilities
import com.github.gionnio.platea.engine.CapabilityOverride
import com.github.gionnio.platea.engine.CapabilityService
import com.github.gionnio.platea.engine.DeviceCapabilities
import com.github.gionnio.platea.engine.DisplayCapabilities
import com.github.gionnio.platea.engine.DisplayModeInfo
import com.github.gionnio.platea.engine.VideoCapabilities

/**
 * [CapabilityService] reading the platform APIs: `MediaCodecList`, `Display.HdrCapabilities`
 * and direct (passthrough) audio support.
 *
 * Capabilities are computed once and kept in memory. The HDR types come from the connected display (sink),
 * which does not change with refresh rate switches, so the result is stable between playbacks.
 *
 * Requires Android 11 (both target devices run it); older devices keep the upstream profile.
 */
@RequiresApi(Build.VERSION_CODES.R)
class AndroidCapabilityService(
    context: Context,
    private val overridesProvider: () -> List<CapabilityOverride> = { emptyList() },
) : CapabilityService {
    private val appContext = context.applicationContext

    @Volatile
    override var cached: DeviceCapabilities? = null
        private set

    override suspend fun get(): DeviceCapabilities = cached ?: synchronized(this) { cached ?: compute().also { cached = it } }

    override fun invalidate(reason: String) {
        Log.i(TAG, "Capabilities invalidated: $reason")
        cached = null
    }

    override fun overrides(): List<CapabilityOverride> = overridesProvider()

    private fun compute(): DeviceCapabilities {
        val overrides = overrides()
        val caps =
            DeviceCapabilities(
                model = Build.MODEL,
                firmware = Build.FINGERPRINT,
                video = readVideo(),
                display = readDisplay(),
                audio = readAudio(),
                appliedOverrides = overrides.map { "${it.key}=${it.value} (${it.reason})" },
                computedAtMillis = System.currentTimeMillis(),
            )
        log(caps)
        return caps
    }

    private fun readVideo(): VideoCapabilities {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }

        fun profiles(mime: String) =
            decoders.flatMap { info ->
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) {
                    emptyList()
                } else {
                    runCatching { info.getCapabilitiesForType(mime).profileLevels.map { it.profile } }.getOrDefault(emptyList())
                }
            }.toSet()

        val hevc = profiles(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val dv = profiles(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION)
        val av1 = profiles(MediaFormat.MIMETYPE_VIDEO_AV1)
        val dvDecoders =
            decoders
                .filter { info -> info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION, ignoreCase = true) } }
                .map { it.name }

        var maxWidth = 0
        var maxHeight = 0
        decoders.forEach { info ->
            if (info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }) {
                runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities }.getOrNull()?.let {
                    maxWidth = maxOf(maxWidth, it.supportedWidths.upper)
                    maxHeight = maxOf(maxHeight, it.supportedHeights.upper)
                }
            }
        }

        return VideoCapabilities(
            hevcMain10 = CodecProfileLevel.HEVCProfileMain10 in hevc,
            hevcHdr10 = CodecProfileLevel.HEVCProfileMain10HDR10 in hevc,
            hevcHdr10Plus = CodecProfileLevel.HEVCProfileMain10HDR10Plus in hevc,
            dolbyVisionProfile5 = CodecProfileLevel.DolbyVisionProfileDvheStn in dv,
            dolbyVisionProfile8 = CodecProfileLevel.DolbyVisionProfileDvheSt in dv,
            dolbyVisionProfile7 = CodecProfileLevel.DolbyVisionProfileDvheDtb in dv,
            av1Main10 = av1.any { it == CodecProfileLevel.AV1ProfileMain10 || it == CodecProfileLevel.AV1ProfileMain10HDR10 },
            av1DolbyVision = CodecProfileLevel.DolbyVisionProfileDvav110 in dv,
            dolbyVisionDecoders = dvDecoders,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
        )
    }

    @Suppress("DEPRECATION")
    private fun readDisplay(): DisplayCapabilities {
        val display = appContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val types = display?.hdrCapabilities?.supportedHdrTypes?.toSet().orEmpty()
        return DisplayCapabilities(
            dolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types,
            hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in types,
            hdr10Plus = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in types,
            hlg = Display.HdrCapabilities.HDR_TYPE_HLG in types,
            modes =
                display?.supportedModes.orEmpty().map {
                    DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
                },
        )
    }

    /**
     * Passthrough from Media3 [Media3AudioCapabilities]: it reads the HDMI sink (and the Fire OS surround setting),
     * unlike `AudioTrack.isDirectPlaybackSupported`, which on Fire OS answers yes even when the TV accepts only PCM.
     */
    private fun readAudio(): AudioCapabilities {
        val sink = Media3AudioCapabilities.getCapabilities(appContext, Media3AudioAttributes.DEFAULT, null, emptyList())
        val compressed =
            listOf(
                MimeTypes.AUDIO_AC3,
                MimeTypes.AUDIO_E_AC3,
                MimeTypes.AUDIO_E_AC3_JOC,
                MimeTypes.AUDIO_TRUEHD,
                MimeTypes.AUDIO_DTS,
                MimeTypes.AUDIO_DTS_HD,
            )
        val decoders =
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.toList() }
                .map { it.lowercase() }
                .filter { it in compressed }
                .toSet()
        return AudioCapabilities(
            ac3 = sink.supportsEncoding(C.ENCODING_AC3),
            eac3 = sink.supportsEncoding(C.ENCODING_E_AC3),
            eac3Joc = sink.supportsEncoding(C.ENCODING_E_AC3_JOC),
            trueHd = sink.supportsEncoding(C.ENCODING_DOLBY_TRUEHD),
            dts = sink.supportsEncoding(C.ENCODING_DTS),
            dtsHd = sink.supportsEncoding(C.ENCODING_DTS_HD),
            maxChannels = sink.maxChannelCount,
            platformDecoders = decoders,
        )
    }

    private fun log(caps: DeviceCapabilities) {
        Log.i(TAG, "Device ${caps.model} (${caps.firmware})")
        Log.i(TAG, "Video ${caps.video}")
        Log.i(TAG, "Display ${caps.display.copy(modes = emptyList())}, ${caps.display.modes.size} modes")
        Log.i(TAG, "Audio ${caps.audio}")
        ModelExceptions.notesFor(caps.model).forEach { Log.i(TAG, "Model note: $it") }
        caps.appliedOverrides.forEach { Log.i(TAG, "Override: $it") }
    }

    companion object {
        const val TAG = "Platea"
    }
}
