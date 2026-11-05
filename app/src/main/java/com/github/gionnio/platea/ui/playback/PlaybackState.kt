package com.github.gionnio.platea.ui.playback

import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.data.model.ItemPlayback
import com.github.gionnio.platea.data.model.Playlist
import com.github.gionnio.platea.data.model.PlaylistItem
import com.github.gionnio.platea.preferences.PlayerBackend
import com.github.gionnio.platea.ui.formatBitrate
import com.github.gionnio.platea.util.LoadingState
import io.github.peerless2012.ass.media.AssHandler
import org.jellyfin.sdk.model.api.MediaSegmentDto

data class PlaybackState(
    val loading: LoadingState = LoadingState.Loading,
    val currentMediaInfo: CurrentMediaInfo = CurrentMediaInfo.EMPTY,
    val currentPlayback: CurrentPlayback? = null,
    val currentItemPlayback: ItemPlayback? = null,
    val currentSegment: MediaSegmentState? = null,
    val analyticsState: AnalyticsState = AnalyticsState(),
    val subtitleCues: List<Cue> = emptyList(),
    val nextUp: BaseItem? = null,
    val playlistIndex: Int = 0,
    val playlist: Playlist = Playlist(emptyList()),
    /** Platea: the player says it is playing but is waiting for data (see StallWatchdog) */
    val stalled: Boolean = false,
) {
    val hasNext: Boolean get() = (playlistIndex + 1) < playlist.items.size
    val hasPrevious: Boolean get() = playlistIndex > 0
    val upcomingItems: List<PlaylistItem>
        get() =
            playlist.items.subList(
                (playlistIndex + 1).coerceAtMost(playlist.items.size),
                playlist.items.size,
            )

    fun nextItem(): PlaylistItem? = playlist.items.getOrNull(playlistIndex + 1)
}

data class PlayerInstance(
    val player: Player,
    val backend: PlayerBackend,
    val assHandler: AssHandler?,
)

data class MediaSegmentState(
    val segment: MediaSegmentDto,
    val interacted: Boolean,
)

data class AnalyticsState(
    val bitrate: String = formatBitrate(0),
    val bitrateEstimate: String = formatBitrate(0),
    val droppedFrames: Int = 0,
)
