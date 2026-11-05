package com.github.gionnio.platea.playback

import com.github.gionnio.platea.util.isForcedTrack
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.data.model.TrackIndex
import com.github.gionnio.platea.preferences.AppPreference
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.PlayerBackend
import com.github.gionnio.platea.services.DeviceProfileService
import com.github.gionnio.platea.services.ImageUrlService
import com.github.gionnio.platea.ui.isNotNullOrBlank
import com.github.gionnio.platea.ui.playback.CurrentPlayback
import com.github.gionnio.platea.ui.playback.findExternalSubtitle
import com.github.gionnio.platea.ui.playback.toMediaMetadata
import com.github.gionnio.platea.util.profile.Codec
import com.github.gionnio.platea.util.subtitleMimeTypes
import com.github.gionnio.platea.PlaybackJournal
import com.github.gionnio.platea.engine.PlayMethods
import com.github.gionnio.platea.nas.NasConfig
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import timber.log.Timber
import com.github.gionnio.platea.engine.PlayMethod as EnginePlayMethod

/** A title ready to be handed to the player, or why it cannot be played. */
sealed interface ResolvedStream {
    data class Ready(
        val mediaItem: MediaItem,
        val playback: CurrentPlayback,
        val source: MediaSourceInfo,
        /** The chosen subtitles come as a separate file next to the stream. */
        val externalSubtitle: Boolean,
    ) : ResolvedStream

    data class Failed(
        val message: String?,
    ) : ResolvedStream
}

/**
 * Asks the server how to play a title with the chosen tracks (PlaybackInfo, with Platea's device profile) and
 * turns the answer into what the player needs: address, play method, subtitles delivered apart.
 */
class StreamResolver(
    private val context: Context,
    private val api: ApiClient,
    private val serverRepository: ServerRepository,
    private val deviceProfileService: DeviceProfileService,
    private val imageUrlService: ImageUrlService,
    private val journal: PlaybackJournal,
    /** Direct reading from the NAS, when set (see NasSettings). */
    private val nas: () -> NasConfig = { NasConfig() },
) {
    suspend fun resolve(
        item: BaseItem,
        sourceId: String?,
        audioIndex: Int?,
        subtitleIndex: Int?,
        enableDirectPlay: Boolean,
        enableDirectStream: Boolean,
        appPreferences: AppPreferences,
        backend: PlayerBackend,
        /** false after the NAS failed for this title: the server's stream instead. */
        allowNas: Boolean = true,
    ): ResolvedStream {
        val itemId = item.id
        val maxBitrate =
            appPreferences.playbackPreferences.maxBitrate
                .takeIf { it > 0 } ?: AppPreference.DEFAULT_BITRATE
        val response by
            api.mediaInfoApi.getPostedPlaybackInfo(
                itemId,
                PlaybackInfoDto(
                    startTimeTicks = null,
                    deviceProfile =
                        deviceProfileService.getOrCreateDeviceProfile(
                            appPreferences,
                            serverRepository.currentServer?.serverVersion,
                        ),
                    maxAudioChannels = null,
                    audioStreamIndex = audioIndex,
                    subtitleStreamIndex = subtitleIndex,
                    mediaSourceId = sourceId,
                    alwaysBurnInSubtitleWhenTranscoding = false,
                    maxStreamingBitrate = maxBitrate.toInt(),
                    enableDirectPlay = enableDirectPlay,
                    enableDirectStream = enableDirectStream,
                    allowVideoStreamCopy = enableDirectStream,
                    allowAudioStreamCopy = enableDirectStream,
                    enableTranscoding = true,
                    autoOpenLiveStream = true,
                ),
            )
        if (response.errorCode != null) {
            Timber.e("Error in PostedPlaybackInfo: %s", response.errorCode)
            return ResolvedStream.Failed(response.errorCode?.serialName)
        }
        val source = response.mediaSources.firstOrNull()
        if (source == null) {
            Timber.e("PostedPlaybackInfo has no media source for %s", itemId)
            return ResolvedStream.Failed(context.getString(R.string.platea_msg_no_source))
        }
        val method =
            when (PlayMethods.of(source)) {
                EnginePlayMethod.DIRECT_PLAY -> PlayMethod.DIRECT_PLAY
                EnginePlayMethod.REMUX -> PlayMethod.DIRECT_STREAM
                EnginePlayMethod.TRANSCODE -> PlayMethod.TRANSCODE
                null -> return ResolvedStream.Failed(context.getString(R.string.platea_msg_no_method))
            }
        // Platea: in direct play the file can be read straight from the NAS, like Infuse or VLC
        val nasUrl =
            if (method == PlayMethod.DIRECT_PLAY && allowNas && !source.isRemote) {
                nas().takeIf { it.usable }?.paths?.toSmb(source.path)
            } else {
                null
            }
        val mediaUrl =
            if (nasUrl != null) {
                nasUrl
            } else if (method == PlayMethod.DIRECT_PLAY) {
                if (source.isRemote && source.path.isNotNullOrBlank()) {
                    Timber.i("Playback is remote for source: %s", source.id)
                    source.path
                } else {
                    api.videosApi.getVideoStreamUrl(
                        itemId = itemId,
                        mediaSourceId = source.id,
                        static = true,
                        tag = source.eTag,
                        playSessionId = response.playSessionId,
                    )
                }
            } else {
                source.transcodingUrl?.let { api.createUrl(forceAudioConversion(it)) }
            }
        if (mediaUrl.isNullOrBlank()) return ResolvedStream.Failed(context.getString(R.string.platea_msg_no_stream))
        if (method != PlayMethod.DIRECT_PLAY) {
            Timber.i("Conversion address: %s", mediaUrl.replace(Regex("(?i)(api_?key)=[^&]*"), "$1=…"))
        }

        Timber.i("Playback decision for $itemId: $method")
        journal.decision(item.name, source, if (nasUrl != null) "${method.name} (NAS)" else method.name)

        val externalSubtitle =
            source.findExternalSubtitle(subtitleIndex)?.let { stream ->
                stream.deliveryUrl?.let { deliveryUrl ->
                    var flags = 0
                    if (stream.isForcedTrack) flags = flags.or(C.SELECTION_FLAG_FORCED)
                    if (stream.isDefault) flags = flags.or(C.SELECTION_FLAG_DEFAULT)
                    MediaItem.SubtitleConfiguration
                        .Builder(api.createUrl(deliveryUrl).toUri())
                        .setId("e:${stream.index}")
                        .setMimeType(subtitleMimeTypes[stream.codec])
                        .setLanguage(stream.language)
                        .setLabel(stream.title)
                        .setSelectionFlags(flags)
                        .build()
                }
            }
        Timber.v("subtitleIndex=$subtitleIndex, externalSubtitle=$externalSubtitle")

        val mediaItem =
            MediaItem
                .Builder()
                .setMediaId(itemId.toString())
                .setMediaMetadata(
                    item.toMediaMetadata(
                        imageUrlService.getItemImageUrl(item, ImageType.PRIMARY, useSeriesForPrimary = true),
                    ),
                ).setUri(mediaUrl.toUri())
                .setSubtitleConfigurations(listOfNotNull(externalSubtitle))
                .apply {
                    when (source.container) {
                        Codec.Container.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                        Codec.Container.DASH -> setMimeType(MimeTypes.APPLICATION_MPD)
                    }
                }.build()

        val playback =
            CurrentPlayback(
                item = item,
                tracks = listOf(),
                backend = backend,
                playMethod = method,
                playSessionId = response.playSessionId,
                liveStreamId = source.liveStreamId,
                mediaSourceInfo = source,
                audioIndex = audioIndex ?: TrackIndex.DISABLED,
                subtitleIndex = subtitleIndex ?: TrackIndex.DISABLED,
                fromNas = nasUrl != null,
            )
        return ResolvedStream.Ready(mediaItem, playback, source, externalSubtitle != null)
    }

    /**
     * When the server converts because the device cannot play the audio codec, it can still decide to copy that
     * audio into the HLS stream (seen with TrueHD on the Sony: an MPEG-TS stream the player cannot read, so the
     * title plays without sound). The audio must be converted to what the transcoding profile asks for.
     */
    private fun forceAudioConversion(transcodingUrl: String): String {
        val reasons = Regex("[?&]TranscodeReasons=([^&]*)").find(transcodingUrl)?.groupValues?.get(1).orEmpty()
        if (!reasons.contains("AudioCodecNotSupported")) return transcodingUrl
        val copy = Regex("([?&])AllowAudioStreamCopy=[^&]*")
        return if (copy.containsMatchIn(transcodingUrl)) {
            transcodingUrl.replace(copy, "$1AllowAudioStreamCopy=false")
        } else {
            transcodingUrl + (if ('?' in transcodingUrl) "&" else "?") + "AllowAudioStreamCopy=false"
        }
    }
}
