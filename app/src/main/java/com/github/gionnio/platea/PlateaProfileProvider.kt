package com.github.gionnio.platea

import android.content.Context
import android.os.Build
import android.util.Log
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.AssPlaybackMode
import com.github.gionnio.platea.preferences.MediaExtensionStatus
import com.github.gionnio.platea.engine.ProfilePolicy
import com.github.gionnio.platea.engine.capability.AndroidCapabilityService
import com.github.gionnio.platea.engine.profile.PlateaProfileBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jellyfin.sdk.model.ServerVersion
import org.jellyfin.sdk.model.api.DeviceProfile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge between Wholphin's [com.github.gionnio.platea.services.DeviceProfileService] and the Platea engine.
 * Returns `null` when the upstream profile must be used (setting off or Android < 11).
 */
@Singleton
class PlateaProfileProvider
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) {
        private val capabilities =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) AndroidCapabilityService(context) else null
        private val builder = PlateaProfileBuilder()

        suspend fun build(
            appPrefs: AppPreferences,
            serverVersion: ServerVersion?,
        ): DeviceProfile? {
            val overrides = appPrefs.playbackPreferences.overrides
            if (overrides.plateaUseUpstreamProfile) {
                Log.i(TAG, "Using the upstream device profile (Platea profile disabled)")
                return null
            }
            val service = capabilities ?: return null
            val policy =
                ProfilePolicy(
                    maxBitrate = appPrefs.playbackPreferences.maxBitrate.toInt().takeIf { it > 0 },
                    clientRendersImageSubtitles = overrides.directPlayPgs,
                    clientRendersAss = overrides.assPlaybackMode != AssPlaybackMode.ASS_TRANSCODE,
                    softwareAudioDecoder =
                        overrides.mediaExtensionsEnabled != MediaExtensionStatus.MES_DISABLED && ffmpegAvailable(),
                    allowDolbyVisionEnhancementLayer = overrides.directPlayDolbyVisionEL,
                    excludeDolbyVisionWithHdr10Plus = overrides.plateaExcludeDoviHdr10Plus,
                    serverVersion = serverVersion?.toString(),
                )
            return builder.build(service.get(), policy)
        }

        /** What this device and the connected TV can do, or `null` on Android < 11. */
        suspend fun deviceCapabilities() = capabilities?.get()

        private fun ffmpegAvailable(): Boolean =
            runCatching {
                Class
                    .forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
                    .getMethod("isAvailable")
                    .invoke(null) as Boolean
            }.getOrDefault(false)

        companion object {
            private const val TAG = "Platea"
        }
    }
