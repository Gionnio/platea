package com.github.gionnio.platea.engine.profile

import android.util.Log
import com.github.gionnio.platea.engine.DeviceCapabilities
import com.github.gionnio.platea.engine.ProfileBuilder
import com.github.gionnio.platea.engine.ProfilePolicy
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.deviceprofile.DeviceProfileBuilder
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile

/**
 * Direct-play-first profile for a curated library.
 *
 * Differences from the upstream (jellyfin-androidtv) profile:
 * - every Jellyfin `VideoRangeType` is listed explicitly as supported (`EqualsAny`), decided from the real
 *   decoders and the connected display; Dolby Vision with an HDR10/HLG/SDR base layer is always playable
 *   when its base layer is, because the device falls back to the base layer by itself;
 * - no per-model exclusion unless the user enables the override;
 * - no codec level conditions (they cause needless transcodes when decoders under-report levels);
 * - audio codecs follow the real passthrough capabilities, plus what Android decodes natively.
 */
class PlateaProfileBuilder : ProfileBuilder {
    override fun build(
        capabilities: DeviceCapabilities,
        policy: ProfilePolicy,
    ): DeviceProfile {
        val video = capabilities.video
        val display = capabilities.display
        val audio = capabilities.audio

        val hevcRanges = hevcRangeTypes(capabilities, policy)
        val av1Ranges =
            buildSet {
                add(SDR)
                if (video.av1Main10) addAll(listOf(HDR10, HDR10_PLUS, HLG))
                if (video.av1DolbyVision) addAll(listOf(DOVI_WITH_HDR10, DOVI_WITH_HDR10_PLUS, DOVI_WITH_HLG, DOVI_WITH_SDR))
                if (video.av1DolbyVision && display.dolbyVision) add(DOVI)
            }

        // An audio codec is declared only if the device can really play it: passthrough to the sink,
        // a platform decoder, or the Media3 ffmpeg extension. Otherwise the server transcodes the audio only.
        fun playable(
            passthrough: Boolean,
            vararg mimes: String,
        ) = passthrough || policy.softwareAudioDecoder || mimes.any { it in audio.platformDecoders }
        val audioCodecs =
            buildList {
                // Decoded by Android/Media3 on every target device
                addAll(listOf("aac", "mp3", "mp2", "flac", "alac", "opus", "vorbis", "pcm_s16le", "pcm_s24le", "pcm_mulaw", "pcm_alaw"))
                if (playable(audio.ac3, "audio/ac3")) add("ac3")
                if (playable(audio.eac3, "audio/eac3")) add("eac3")
                if (playable(audio.trueHd, "audio/true-hd")) addAll(listOf("truehd", "mlp"))
                if (playable(audio.dts || audio.dtsHd, "audio/vnd.dts", "audio/vnd.dts.hd")) addAll(listOf("dts", "dca"))
            }

        val profile =
            buildDeviceProfile {
                name = "Platea"
                policy.maxBitrate?.let {
                    maxStaticBitrate = it
                    maxStreamingBitrate = it
                }

                directPlayProfile {
                    type = DlnaProfileType.VIDEO
                    container("mkv", "webm", "mp4", "m4v", "mov", "ts", "mpegts", "m2ts", "mpeg", "mpg", "vob", "ogg")
                    videoCodec("hevc", "h264", "av1", "vp9", "vp8", "mpeg2video", "mpeg4", "vc1")
                    audioCodec(*audioCodecs.toTypedArray())
                }
                directPlayProfile {
                    type = DlnaProfileType.AUDIO
                    audioCodec(*audioCodecs.toTypedArray())
                }

                // Emergency fallback only
                transcodingProfile {
                    type = DlnaProfileType.VIDEO
                    context = EncodingContext.STREAMING
                    container = "ts"
                    protocol = MediaStreamProtocol.HLS
                    videoCodec("hevc", "h264")
                    audioCodec("eac3", "ac3", "aac")
                    copyTimestamps = false
                    enableSubtitlesInManifest = true
                }
                transcodingProfile {
                    type = DlnaProfileType.AUDIO
                    context = EncodingContext.STREAMING
                    container = "ts"
                    protocol = MediaStreamProtocol.HLS
                    audioCodec("aac")
                }

                codecProfile {
                    type = CodecType.VIDEO
                    codec = "hevc"
                    conditions {
                        ProfileConditionValue.VIDEO_PROFILE inCollection listOfNotNull("main", if (video.hevcMain10) "main 10" else null)
                        ProfileConditionValue.VIDEO_RANGE_TYPE inCollection hevcRanges.toList()
                        if (video.maxWidth > 0) ProfileConditionValue.WIDTH lowerThanOrEquals video.maxWidth
                        if (video.maxHeight > 0) ProfileConditionValue.HEIGHT lowerThanOrEquals video.maxHeight
                    }
                }
                codecProfile {
                    type = CodecType.VIDEO
                    codec = "av1"
                    conditions {
                        ProfileConditionValue.VIDEO_RANGE_TYPE inCollection av1Ranges.toList()
                    }
                }
                codecProfile {
                    type = CodecType.VIDEO_AUDIO
                    conditions {
                        ProfileConditionValue.AUDIO_CHANNELS lowerThanOrEquals 8
                    }
                }

                subtitles(policy)
            }

        Log.i(TAG, "Profile: HEVC ranges=$hevcRanges, AV1 ranges=$av1Ranges, audio=$audioCodecs, policy=$policy")
        return profile
    }

    /** Range types playable for HEVC, see the class documentation. */
    fun hevcRangeTypes(
        capabilities: DeviceCapabilities,
        policy: ProfilePolicy,
    ): Set<String> {
        val video = capabilities.video
        val hdr = video.hevcMain10
        val dvSingleLayer = video.dolbyVisionProfile8 || video.dolbyVisionProfile5
        return buildSet {
            add(SDR)
            if (hdr) {
                addAll(listOf(HDR10, HDR10_PLUS, HLG))
                // DV with a compatible base layer: DV decoder if present, otherwise the base layer
                addAll(listOf(DOVI_WITH_HDR10, DOVI_WITH_HLG, DOVI_WITH_SDR))
                if (!policy.excludeDolbyVisionWithHdr10Plus) add(DOVI_WITH_HDR10_PLUS)
                // Profile 7 (enhancement layer) only on request: stock Media3 cannot decode the dual layer
                if (policy.allowDolbyVisionEnhancementLayer) {
                    add(DOVI_WITH_EL)
                    if (!policy.excludeDolbyVisionWithHdr10Plus) add(DOVI_WITH_EL_HDR10_PLUS)
                }
            }
            // Profile 5 has no fallback layer: only with a DV decoder and a DV display
            if (video.dolbyVisionProfile5 && capabilities.display.dolbyVision) add(DOVI)
            if (!dvSingleLayer) Log.i(TAG, "No Dolby Vision decoder: DV files play on their base layer")
        }
    }

    private fun DeviceProfileBuilder.subtitles(policy: ProfilePolicy) {
        listOf("srt", "subrip", "vtt", "webvtt", "ttml").forEach {
            subtitleProfile(it, SubtitleDeliveryMethod.EMBED)
            subtitleProfile(it, SubtitleDeliveryMethod.EXTERNAL)
        }
        listOf("ass", "ssa").forEach {
            if (policy.clientRendersAss) {
                subtitleProfile(it, SubtitleDeliveryMethod.EMBED)
                subtitleProfile(it, SubtitleDeliveryMethod.EXTERNAL)
            }
            subtitleProfile(it, SubtitleDeliveryMethod.ENCODE)
        }
        listOf("pgs", "pgssub", "dvdsub", "dvbsub").forEach {
            if (policy.clientRendersImageSubtitles) subtitleProfile(it, SubtitleDeliveryMethod.EMBED)
            subtitleProfile(it, SubtitleDeliveryMethod.ENCODE)
        }
    }

    companion object {
        private const val TAG = "Platea"

        // Jellyfin VideoRangeType names (server 12.2, /api-docs/openapi.json)
        const val SDR = "SDR"
        const val HDR10 = "HDR10"
        const val HDR10_PLUS = "HDR10Plus"
        const val HLG = "HLG"
        const val DOVI = "DOVI"
        const val DOVI_WITH_HDR10 = "DOVIWithHDR10"
        const val DOVI_WITH_HDR10_PLUS = "DOVIWithHDR10Plus"
        const val DOVI_WITH_HLG = "DOVIWithHLG"
        const val DOVI_WITH_SDR = "DOVIWithSDR"
        const val DOVI_WITH_EL = "DOVIWithEL"
        const val DOVI_WITH_EL_HDR10_PLUS = "DOVIWithELHDR10Plus"
    }
}
