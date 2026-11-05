package com.github.gionnio.platea.util

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.github.gionnio.platea.ui.launchIO
import com.github.gionnio.platea.ui.playback.CurrentPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import timber.log.Timber
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Listens to playback and periodically saves playback activity to the server
 */
@OptIn(UnstableApi::class)
class TrackActivityPlaybackListener(
    private val api: ApiClient,
    private val player: Player,
    private val getState: () -> PlaybackItemState?,
    startPositionMs: Long = 0,
) : Player.Listener {
    /**
     * Platea: last position known to be real. A player that failed (or never started) reads 0, and reporting
     * that would wipe the resume point on the server.
     */
    @Volatile
    private var lastKnownMs = startPositionMs.coerceAtLeast(0)

    private val coroutineScope = CoroutineScope(PlateaDispatchers.Main)
    private val task: TimerTask =
        object : TimerTask() {
            override fun run() {
                try {
                    saveActivity(-1L)
                } catch (ex: Exception) {
                    Timber.w(ex, "Exception during track activity timer")
                }
            }
        }

    @Volatile
    private var initialized = false

    fun init() {
        launch("reportPlaybackStart") {
            getState.invoke()?.let { state ->
                Timber.v("reportPlaybackStart for ${state.itemId}")
                api.playStateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        canSeek = true,
                        itemId = state.itemId,
                        isPaused = withContext(PlateaDispatchers.Main) { !player.isPlaying },
                        playMethod = state.playMethod,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        isMuted = false,
                        audioStreamIndex = state.audioStreamIndex,
                        subtitleStreamIndex = state.subtitleStreamIndex,
                        playSessionId = state.playSessionId,
                        liveStreamId = state.liveStreamId,
                    ),
                )

                val delay = 5.seconds.inWholeMilliseconds
                // Every x seconds, check if the video is playing
                TIMER.schedule(task, delay, delay)
                initialized = true
            }
        }
    }

    fun release() {
//        player.removeListener(this)
        task.cancel()
        TIMER.purge()
        val current = player.currentPosition
        val position = (if (current > 0) current else lastKnownMs).milliseconds
        // Read now: by the time the report is sent the view model may already have cleared its state
        val stateAtStop = getState.invoke()
        launch("reportPlaybackStopped") {
            stateAtStop?.let { state ->
                Timber.v("reportPlaybackStopped for ${state.itemId} at $position")
                if (position < Duration.ZERO) {
                    Timber.w("Negative position when reporting playback stopped: %s", position)
                }
                try {
                    api.playStateApi.reportPlaybackStopped(
                        PlaybackStopInfo(
                            itemId = state.itemId,
                            positionTicks = position.inWholeTicks.takeIf { it >= 0 },
                            failed = false,
                            playSessionId = state.playSessionId,
                            liveStreamId = state.liveStreamId,
                        ),
                    )
                } finally {
                    // Platea: the pages reload progress and watched marks now, not after a guessed delay
                    com.github.gionnio.platea.PlaybackEvents.onStopReported()
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (initialized) {
            saveActivity(-1)
        } else if (isPlaying) {
            init()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            Timber.v("onPlaybackStateChanged STATE_ENDED")
            // Platea: an early end is a source that stopped (see EarlyEnd): its position, not the full duration,
            // otherwise the title is marked as watched after a few seconds
            val early = com.github.gionnio.platea.engine.EarlyEnd.isEarly(player.currentPosition, player.duration)
            saveActivity(if (early) player.currentPosition else player.duration)
        }
    }

    private fun saveActivity(position: Long) {
        launch("saveActivity") {
            getState.invoke()?.let { state ->
                val calcPosition =
                    withContext(PlateaDispatchers.Main) {
                        (if (position >= 0) position else player.currentPosition)
                    }
                if (calcPosition > 0) {
                    lastKnownMs = calcPosition
                    val isPaused = withContext(PlateaDispatchers.Main) { !player.isPlaying }
                    Timber.v("saveActivity: itemId=${state.itemId}, pos=$calcPosition")
                    api.playStateApi.reportPlaybackProgress(
                        PlaybackProgressInfo(
                            itemId = state.itemId,
                            positionTicks = calcPosition.milliseconds.inWholeTicks,
                            canSeek = true,
                            isPaused = isPaused,
                            isMuted = false,
                            playMethod = state.playMethod,
                            repeatMode = RepeatMode.REPEAT_NONE,
                            playbackOrder = PlaybackOrder.DEFAULT,
                            audioStreamIndex = state.audioStreamIndex,
                            subtitleStreamIndex = state.subtitleStreamIndex,
                            playSessionId = state.playSessionId,
                            liveStreamId = state.liveStreamId,
                        ),
                    )
                }
            }
        }
    }

    private fun launch(
        name: String,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        coroutineScope.launchIO {
            try {
                block.invoke(this)
            } catch (ex: Exception) {
                Timber.w(ex, "Exception during %s", name)
            }
        }
    }

    companion object {
        private const val TAG = "TrackActivityPlaybackListener"

        private val TIMER by lazy { Timer("$TAG-timer", true) }
    }
}

data class PlaybackItemState(
    val itemId: UUID,
    val playMethod: PlayMethod,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val playSessionId: String? = null,
    val liveStreamId: String? = null,
) {
    constructor(
        playback: CurrentPlayback,
    ) : this(
        itemId = playback.item.id,
        playMethod = playback.playMethod,
        audioStreamIndex = playback.audioIndex.takeIf { it >= 0 },
        subtitleStreamIndex = playback.subtitleIndex.takeIf { it >= 0 },
        playSessionId = playback.playSessionId,
        liveStreamId = playback.liveStreamId,
    )
}
