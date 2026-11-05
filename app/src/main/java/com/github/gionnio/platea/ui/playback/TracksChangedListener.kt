package com.github.gionnio.platea.ui.playback

import android.os.Handler
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import org.jellyfin.sdk.model.api.MediaSourceInfo
import timber.log.Timber

class TracksChangedListener(
    val player: Player,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    val source: MediaSourceInfo,
    /** Called with what could not be selected, after a second attempt. */
    val onFailure: (TrackSelectionResult) -> Unit,
) : Player.Listener {
    // Created only if a second look is needed
    private val handler by lazy { Handler(player.applicationLooper) }
    private var pending = false
    private var retried = false

    override fun onTracksChanged(tracks: Tracks) {
        Timber.v("onTracksChanged: $tracks")
        if (tracks.groups.isNotEmpty()) select()
    }

    private fun select() {
        val result =
            TrackSelectionUtils.createTrackSelections(
                player.trackSelectionParameters,
                player.currentTracks,
                audioIndex,
                subtitleIndex,
                source,
            )
        Timber.v("onTracksChanged: %s", result)
        when {
            result.bothSelected -> {
                player.removeListener(this)
                cancel()
                player.trackSelectionParameters = result.trackSelectionParameters
            }

            // Platea: the track list can arrive in more than one step. One more look before giving up on
            // direct play, which is the rule.
            !retried -> {
                retried = true
                pending = true
                handler.postDelayed({ select() }, RETRY_MS)
            }

            else -> {
                player.removeListener(this)
                Timber.w("Failed to select tracks: %s", result)
                onFailure.invoke(result)
            }
        }
    }

    fun cancel() {
        if (pending) handler.removeCallbacksAndMessages(null)
        pending = false
    }

    private companion object {
        const val RETRY_MS = 800L
    }
}
