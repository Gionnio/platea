package com.github.gionnio.platea.details

import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.services.DeviceProfileService
import com.github.gionnio.platea.services.UserPreferencesService
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What will happen when this title is played, as decided by the server with Platea's device profile. */
data class PlaybackPrediction(
    val method: Method,
    val dolbyVision: Boolean,
    /** Jellyfin TranscodeReasons when the server does not direct play. */
    val reasons: List<String>,
) {
    enum class Method { DIRECT_PLAY, REMUX, TRANSCODE }
}

/**
 * Asks the server (PlaybackInfo) how it would serve a title, with the same device profile the player sends.
 * No stream is started: it only reads the decision, so the details page can show it before Play.
 */
@Singleton
class PlaybackPredictor
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val deviceProfileService: DeviceProfileService,
        private val userPreferencesService: UserPreferencesService,
    ) {
        suspend fun predict(
            itemId: UUID,
            mediaSourceId: String?,
            audioIndex: Int?,
            subtitleIndex: Int?,
        ): PlaybackPrediction? =
            runCatching {
                val prefs = userPreferencesService.getCurrent()
                val profile = deviceProfileService.getOrCreateDeviceProfile(prefs.appPreferences, serverRepository.currentServer?.serverVersion)
                val response =
                    api.mediaInfoApi
                        .getPostedPlaybackInfo(
                            itemId,
                            PlaybackInfoDto(
                                userId = serverRepository.currentUser?.id,
                                deviceProfile = profile,
                                mediaSourceId = mediaSourceId,
                                audioStreamIndex = audioIndex,
                                subtitleStreamIndex = subtitleIndex,
                                enableDirectPlay = true,
                                enableDirectStream = true,
                                enableTranscoding = true,
                                allowVideoStreamCopy = true,
                                allowAudioStreamCopy = true,
                                autoOpenLiveStream = false,
                            ),
                        ).content
                val source = response.mediaSources.firstOrNull { mediaSourceId == null || it.id == mediaSourceId } ?: response.mediaSources.first()
                val video = source.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
                // Some files carry the Dolby Vision data without the range type saying so
                val isDovi = video?.videoRangeType?.serialName?.startsWith("DOVI") == true || video?.videoDoViTitle != null
                val method =
                    when {
                        source.supportsDirectPlay -> PlaybackPrediction.Method.DIRECT_PLAY
                        source.supportsDirectStream -> PlaybackPrediction.Method.REMUX
                        else -> PlaybackPrediction.Method.TRANSCODE
                    }
                val reasons =
                    source.transcodingUrl
                        ?.substringAfter("TranscodeReasons=", "")
                        ?.substringBefore('&')
                        ?.replace("%2C", ",")
                        ?.split(',')
                        ?.filter { it.isNotBlank() }
                        .orEmpty()
                PlaybackPrediction(method, dolbyVision = isDovi && method == PlaybackPrediction.Method.DIRECT_PLAY, reasons = reasons)
            }.onFailure { Timber.w(it, "Playback prediction for %s", itemId) }.getOrNull()
    }
