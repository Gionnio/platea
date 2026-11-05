package com.github.gionnio.platea.playback

import androidx.media3.common.Player
import androidx.media3.common.util.Util
import com.github.gionnio.platea.preferences.PlaybackPreferences
import com.github.gionnio.platea.services.keepSubscribed
import com.github.gionnio.platea.ui.seekBack
import com.github.gionnio.platea.ui.seekForward
import com.github.gionnio.platea.util.PlateaDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.extensions.ticks
import kotlin.time.Duration.Companion.milliseconds

/**
 * Playback commands sent by the server to this device (pause, seek, next...), e.g. from the Jellyfin web
 * interface or another app controlling this session.
 */
class RemoteCommands(
    private val api: ApiClient,
    private val player: () -> Player,
    private val preferences: () -> PlaybackPreferences,
    private val onStop: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
) {
    fun listen(scope: CoroutineScope): Job =
        scope.keepSubscribed("Playback commands", subscribe = { api.webSocket.subscribe<PlaystateMessage>() }) { message ->
            val request = message.data ?: return@keepSubscribed
            withContext(PlateaDispatchers.Main) {
                val player = player()
                when (request.command) {
                    PlaystateCommand.STOP -> onStop()
                    PlaystateCommand.PAUSE -> player.pause()
                    PlaystateCommand.UNPAUSE -> player.play()
                    PlaystateCommand.NEXT_TRACK -> onNext()
                    PlaystateCommand.PREVIOUS_TRACK -> onPrevious()
                    PlaystateCommand.SEEK -> request.seekPositionTicks?.ticks?.let { player.seekTo(it.inWholeMilliseconds) }
                    PlaystateCommand.REWIND -> player.seekBack(preferences().skipBackMs.milliseconds)
                    PlaystateCommand.FAST_FORWARD -> player.seekForward(preferences().skipForwardMs.milliseconds)
                    PlaystateCommand.PLAY_PAUSE -> Util.handlePlayPauseButtonAction(player)
                }
            }
        }
}
