package com.github.gionnio.platea.playback

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.github.gionnio.platea.engine.RebufferPolicy

/**
 * ExoPlayer's [DefaultLoadControl], except after running dry: see [RebufferPolicy]. When the buffer cannot grow
 * any further (byte limit reached) playback resumes anyway, so a long wait never turns into a stuck player.
 */
@OptIn(UnstableApi::class)
class PatientLoadControl(
    private val delegate: DefaultLoadControl = DefaultLoadControl(),
    private val policy: RebufferPolicy = RebufferPolicy(),
) : LoadControl {
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!delegate.shouldStartPlayback(parameters)) return false
        if (!parameters.rebuffering) return true
        val now = SystemClock.elapsedRealtime()
        val requiredUs = policy.requiredMs(now) * 1000
        val start = parameters.bufferedDurationUs >= requiredUs || !delegate.shouldContinueLoading(parameters)
        if (start) {
            policy.resumed(now)
            Log.i(PlayerDiagnostics.TRACE_TAG, "resume after rebuffer with ${parameters.bufferedDurationUs / 1000} ms (asked ${requiredUs / 1000} ms)")
        }
        return start
    }

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
}
