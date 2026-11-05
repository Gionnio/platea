package com.github.gionnio.platea.ui.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.github.gionnio.platea.preferences.PlaybackPreferences
import com.github.gionnio.platea.preferences.skipBackOnResume
import com.github.gionnio.platea.ui.seekBack
import com.google.common.util.concurrent.ListenableFuture
import timber.log.Timber

@OptIn(UnstableApi::class)
class MediaSessionPlayer(
    player: Player,
    private val playbackPreferences: PlaybackPreferences,
) : ForwardingSimpleBasePlayer(player) {
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Timber.v("handleSetPlayWhenReady: playWhenReady=$playWhenReady")
        if (playWhenReady) {
            playbackPreferences.skipBackOnResume?.let {
                player.seekBack(it)
            }
        }
        return super.handleSetPlayWhenReady(playWhenReady)
    }
}
