package com.github.gionnio.platea.playback

import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import androidx.annotation.OptIn
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import com.github.gionnio.platea.ui.formatBitrate
import com.github.gionnio.platea.ui.launchDefault
import com.github.gionnio.platea.ui.playback.CurrentPlayback
import com.github.gionnio.platea.ui.playback.PlaybackState
import com.github.gionnio.platea.PlaybackJournal
import com.github.gionnio.platea.engine.DeviceCapabilities
import com.github.gionnio.platea.engine.DolbyVisionCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt

/**
 * What the player is really doing with the current title: decoders, bitrate, dropped frames, time to the first
 * picture. Feeds the diagnostics panel and the playback journal, and reports a Dolby Vision file that ended up
 * on a decoder without Dolby Vision.
 */
@OptIn(UnstableApi::class)
class PlayerDiagnostics(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlaybackState>,
    private val journal: PlaybackJournal,
    private val videoMimeType: () -> String?,
    private val capabilities: suspend () -> DeviceCapabilities?,
    private val onDolbyVisionLost: suspend (DolbyVisionCheck.Shown) -> Unit,
) : AnalyticsListener {
    private var startedAt = 0L
    private val marks = linkedMapOf<String, Long>()
    private var dolbyVisionReportedFor: UUID? = null

    /** A title was asked for: the clock for the "time to first picture" starts here. */
    fun titleRequested() {
        startedAt = SystemClock.elapsedRealtime()
        marks.clear()
    }

    /** A step of the start is done (server answer, display switch...). */
    fun mark(step: String) {
        if (startedAt > 0) marks[step] = SystemClock.elapsedRealtime() - startedAt
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) {
        if (startedAt == 0L) return
        mark("picture")
        journal.start(state.value.currentPlayback?.item?.name, marks.mapValues { "${it.value} ms" })
        startedAt = 0
    }

    private fun updatePlayback(block: (CurrentPlayback) -> CurrentPlayback) {
        state.update { it.copy(currentPlayback = it.currentPlayback?.let(block)) }
    }

    private fun decoderLabel(decoderName: String): String {
        val codecInfo =
            MediaCodecList(MediaCodecList.ALL_CODECS)
                .codecInfos
                .firstOrNull { !it.isEncoder && it.name == decoderName }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codecInfo?.isHardwareAccelerated == true) {
            "$decoderName (HW)"
        } else {
            decoderName
        }
    }

    private fun videoDecoder(decoderName: String) {
        scope.launchDefault {
            val label = decoderLabel(decoderName)
            updatePlayback { it.copy(videoDecoder = label) }
        }
    }

    private fun audioDecoder(decoderName: String) {
        scope.launchDefault {
            val label = decoderLabel(decoderName)
            updatePlayback { it.copy(audioDecoder = label) }
        }
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Timber.v("onVideoDecoderInitialized: decoder=$decoderName")
        videoDecoder(decoderName)
        val playback = state.value.currentPlayback ?: return
        val video = playback.mediaSourceInfo.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
        val rangeType = video?.videoRangeType?.serialName
        journal.decoder(decoderName, videoMimeType(), rangeType)
        scope.launchDefault {
            val shown =
                DolbyVisionCheck.lost(
                    rangeType = rangeType,
                    hasDolbyVisionData = video?.videoDoViTitle != null,
                    converting = playback.playMethod == PlayMethod.TRANSCODE,
                    capabilities = capabilities(),
                    decoderName = decoderName,
                ) ?: return@launchDefault
            // Once per title: a seek or a track change starts the decoder again
            if (dolbyVisionReportedFor == playback.item.id) return@launchDefault
            dolbyVisionReportedFor = playback.item.id
            journal.fallback("DolbyVisionLost", "$decoderName, shown as $shown")
            onDolbyVisionLost(shown)
        }
    }

    override fun onVideoDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        updatePlayback { it.copy(videoDecoder = null) }
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        decoderReuseEvaluation?.let { decoder ->
            if (decoder.result != DecoderReuseEvaluation.REUSE_RESULT_NO) videoDecoder(decoder.decoderName)
        }
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audioDecoder(decoderName)
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        decoderReuseEvaluation?.let { decoder ->
            if (decoder.result != DecoderReuseEvaluation.REUSE_RESULT_NO) audioDecoder(decoder.decoderName)
        }
    }

    override fun onAudioDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        updatePlayback { it.copy(audioDecoder = null) }
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        if (totalLoadTimeMs > 0 && totalBytesLoaded > 0) {
            state.update {
                it.copy(
                    analyticsState =
                        it.analyticsState.copy(
                            bitrate = formatBitrate((totalBytesLoaded.toDouble() / (totalLoadTimeMs / 1000.0) * 8).roundToInt()),
                            bitrateEstimate = formatBitrate(bitrateEstimate.toInt()),
                        ),
                )
            }
        }
    }

    // Platea: playback trace. One line every few seconds in the log (tag PlateaNet), readable from every build,
    // and the anomalies in the journal: what came over the network, how much is buffered ahead, and whether
    // the player is asking for data. A stall with bytes coming in is a slow source; a stall with no bytes and
    // "loading" on is a stuck connection; no bytes and "loading" off is the player not asking.
    private var traceBytes = StreamMeter.bytes.get()
    private var traceOpens = StreamMeter.opens.get()
    private var traceAt = SystemClock.elapsedRealtime()
    private var loadErrors = 0

    fun sample(player: Player) {
        val now = SystemClock.elapsedRealtime()
        val bytes = StreamMeter.bytes.get()
        val opens = StreamMeter.opens.get()
        val seconds = ((now - traceAt).coerceAtLeast(1)) / 1000.0
        val mbps = (bytes - traceBytes) * 8 / seconds / 1_000_000
        val ahead = player.totalBufferedDuration
        val playerState =
            when (player.playbackState) {
                Player.STATE_BUFFERING -> "buffering"
                Player.STATE_READY -> if (player.isPlaying) "playing" else "ready"
                Player.STATE_ENDED -> "ended"
                else -> "idle"
            }
        val line =
            "state=$playerState loading=${player.isLoading} ahead=${ahead}ms in=${"%.1f".format(mbps)}Mbps " +
                "opens=${opens - traceOpens} errors=$loadErrors dropped=${state.value.analyticsState.droppedFrames} pos=${player.currentPosition}ms"
        Log.i(TRACE_TAG, line)
        // Little buffered while playing or waiting: worth keeping in the journal
        if (ahead < 10_000 && player.playWhenReady && player.playbackState != Player.STATE_ENDED) journal.trace(line)
        traceBytes = bytes
        traceOpens = opens
        traceAt = now
        loadErrors = 0
    }

    override fun onIsLoadingChanged(
        eventTime: AnalyticsListener.EventTime,
        isLoading: Boolean,
    ) {
        Log.i(TRACE_TAG, "loading=$isLoading ahead=${eventTime.totalBufferedDurationMs}ms")
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        // ExoPlayer retries these by itself: without this line a reconnection loop is invisible
        loadErrors++
        val cause = generateSequence(error as Throwable) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
        Log.w(TRACE_TAG, "load error after ${loadEventInfo.bytesLoaded} bytes in ${loadEventInfo.loadDurationMs} ms: $cause")
        journal.fallback("LoadError", "${loadEventInfo.bytesLoaded} bytes in ${loadEventInfo.loadDurationMs} ms, ahead ${eventTime.totalBufferedDurationMs} ms: $cause".take(400))
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        state.update {
            it.copy(analyticsState = it.analyticsState.copy(droppedFrames = it.analyticsState.droppedFrames + droppedFrames))
        }
    }

    companion object {
        const val TRACE_TAG = "PlateaNet"
    }
}
