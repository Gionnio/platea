package com.github.gionnio.platea

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Tells the pages when the server has received "playback stopped" (position, watched mark), so they reload
 * at the right moment instead of guessing with a delay.
 */
object PlaybackEvents {
    private val _stopped = MutableStateFlow(0L)

    /** Increases every time a stop report has been answered by the server (or has failed). */
    val stopped: StateFlow<Long> = _stopped

    fun onStopReported() = _stopped.update { it + 1 }
}
