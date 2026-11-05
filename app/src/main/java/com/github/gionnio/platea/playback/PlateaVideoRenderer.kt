package com.github.gionnio.platea.playback

import android.media.MediaFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/** Frame rate of the title being played, as the server knows it (Matroska files often do not declare it). */
object ContentFrameRate {
    @Volatile
    var fps: Float = 0f
}

/**
 * ExoPlayer's video renderer that always tells the decoder the frame rate of the film. Without it the decoder of
 * some TVs (MediaTek on Sony) gets "frame rate 0" and the TV keeps 60 Hz with 3:2 judder on 24p films, while in
 * tunneled playback its compositor can switch the panel to the film's rate.
 */
@OptIn(UnstableApi::class)
class PlateaVideoRenderer(
    builder: Builder,
) : MediaCodecVideoRenderer(builder) {
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat {
        val mediaFormat =
            super.getMediaFormat(format, codecMimeType, codecMaxValues, codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId)
        if (!mediaFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            val fps = format.frameRate.takeIf { it > 0 } ?: ContentFrameRate.fps
            if (fps > 0) {
                mediaFormat.setFloat(MediaFormat.KEY_FRAME_RATE, fps)
                Log.i("PlateaNet", "video decoder told frame rate $fps")
            }
        }
        return mediaFormat
    }
}
