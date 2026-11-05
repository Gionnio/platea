@file:OptIn(markerClass = [UnstableApi::class])

package com.github.gionnio.platea.services

import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import com.github.gionnio.platea.BuildConfig
import com.github.gionnio.platea.nas.NasRoutingDataSource
import com.github.gionnio.platea.nas.NasSettings
import com.github.gionnio.platea.playback.PatientLoadControl
import com.github.gionnio.platea.playback.SlowSourceSimulator
import com.github.gionnio.platea.playback.StreamMeter
import java.io.File
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.AssPlaybackMode
import com.github.gionnio.platea.preferences.MediaExtensionStatus
import com.github.gionnio.platea.preferences.PlayerBackend
import com.github.gionnio.platea.services.hilt.AuthOkHttpClient
import com.github.gionnio.platea.util.PlateaDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.lang.reflect.Constructor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Constructs a [Player] instance for video playback
 */
@Singleton
class PlayerFactory
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:AuthOkHttpClient private val authOkHttpClient: OkHttpClient,
        private val nasSettings: NasSettings,
    ) {
        @Volatile
        var currentPlayer: Player? = null
            private set

        suspend fun createVideoPlayer(
            backend: PlayerBackend,
            appPreferences: AppPreferences,
        ): PlayerCreation {
            val prefs = appPreferences.playbackPreferences
            withContext(PlateaDispatchers.Main) {
                if (currentPlayer?.isReleased == false) {
                    Timber.w("Player was not released before trying to create a new one!")
                    currentPlayer?.release()
                }
            }
            var assHandler: AssHandler? = null
            val newPlayer =
                when (backend) {
                    // Platea plays everything with ExoPlayer: there is no libmpv in the app
                    PlayerBackend.PREFER_MPV,
                    PlayerBackend.MPV,
                    PlayerBackend.EXO_PLAYER,
                    PlayerBackend.UNRECOGNIZED,
                    -> {
                        val extensions = prefs.overrides.mediaExtensionsEnabled
                        val useLibAss =
                            prefs.overrides.assPlaybackMode == AssPlaybackMode.ASS_LIBASS
                        val decodeAv1 = prefs.overrides.decodeAv1
                        Timber.v(
                            "extensions=%s, assPlaybackMode=%s",
                            extensions,
                            prefs.overrides.assPlaybackMode,
                        )
                        val rendererMode =
                            when (extensions) {
                                MediaExtensionStatus.MES_FALLBACK -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                                MediaExtensionStatus.MES_PREFERRED -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                                MediaExtensionStatus.MES_DISABLED -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                                else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                            }
                        val dataSourceFactory =
                            // Platea: smb:// addresses (direct reading from the NAS) go to the SMB client
                            NasRoutingDataSource.Factory(DefaultDataSource.Factory(context).setTransferListener(StreamMeter)) {
                                nasSettings.current
                            }.let {
                                // Debug only: a slow server or NAS on demand, see SlowSourceSimulator
                                if (BuildConfig.DEBUG) {
                                    SlowSourceSimulator.Factory(it, File(context.filesDir, "platea/slow-source"))
                                } else {
                                    it
                                }
                            }
                        val extractorsFactory = createExtractorsFactory()
                        var renderersFactory: RenderersFactory =
                            PlateaRenderersFactory(context, decodeAv1)
                                .setEnableDecoderFallback(true)
                                .setExtensionRendererMode(rendererMode)

                        val mediaSourceFactory =
                            if (useLibAss) {
                                val renderType =
                                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                        AssRenderType.OVERLAY_CANVAS
                                    } else {
                                        AssRenderType.OVERLAY_OPEN_GL
                                    }
                                assHandler = AssHandler(renderType)
                                val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)
                                renderersFactory = AssRenderersFactory(assHandler, renderersFactory)
                                DefaultMediaSourceFactory(
                                    dataSourceFactory,
                                    extractorsFactory.withAssMkvSupport(
                                        assSubtitleParserFactory,
                                        assHandler,
                                    ),
                                ).setSubtitleParserFactory(assSubtitleParserFactory)
                            } else {
                                DefaultMediaSourceFactory(
                                    dataSourceFactory,
                                    extractorsFactory,
                                )
                            }
                        // Platea: no audio offload for video. It is a battery saver made for music; with films
                        // it only adds a way for passthrough and lip sync to go wrong on some TVs.
                        val disableAudioOffload = true
                        val tunneling =
                            // Platea: its own switch in the advanced settings, not behind the experimental ones. On TVs that
                            // expose only 60 Hz to Android (Sony) the video goes straight to the TV's video path, which
                            // can show 24p without the 3:2 judder of the 60 Hz composition.
                            appPreferences.experimentalPreferences.videoTunnelingEnabled || onlyOneRefreshRate()
                        val trackSelector =
                            createTrackSelector(
                                tunneling = tunneling,
                                disableAudioOffload = disableAudioOffload,
                            )

                        ExoPlayer
                            .Builder(context)
                            .setMediaSourceFactory(mediaSourceFactory)
                            .setRenderersFactory(renderersFactory)
                            .setTrackSelector(trackSelector)
                            // Platea: few clean pauses instead of stutter when the source is slow
                            .setLoadControl(PatientLoadControl())
                            .build()
                            .apply {
                                assHandler?.init(this)
                                withContext(PlateaDispatchers.Main) {
                                    // Audio focus: other apps' music pauses while a video plays
                                    setAudioAttributes(
                                        AudioAttributes
                                            .Builder()
                                            .setUsage(C.USAGE_MEDIA)
                                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                            .build(),
                                        true,
                                    )
                                }
                            }
                    }

                    PlayerBackend.EXTERNAL_PLAYER -> {
                        throw IllegalArgumentException("Cannot create a player for external playback")
                    }
                }
            currentPlayer = newPlayer
            return PlayerCreation(newPlayer, assHandler)
        }

        /**
         * Platea: the TV offers Android a single refresh rate (Sony: 3840x2160 at 60 Hz only), so no display mode switch
         * can follow a 24p film. Tunneled playback is then on by default: the video goes to the TV's own video path,
         * which shows 24p without the 3:2 judder (verified on the Sony).
         */
        private fun onlyOneRefreshRate(): Boolean =
            runCatching {
                val display =
                    (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                        .getDisplay(android.view.Display.DEFAULT_DISPLAY)
                display.supportedModes.map { Math.round(it.refreshRate * 100) }.distinct().size == 1 &&
                    (Build.VERSION.SDK_INT < 31 || display.mode.alternativeRefreshRates.isEmpty())
            }.getOrDefault(false)

        fun createAudioPlayer(
            disableAudioOffload: Boolean,
            extensions: MediaExtensionStatus = MediaExtensionStatus.MES_FALLBACK,
        ): ExoPlayer {
            val rendererMode =
                when (extensions) {
                    MediaExtensionStatus.MES_FALLBACK -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    MediaExtensionStatus.MES_PREFERRED -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    MediaExtensionStatus.MES_DISABLED -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
            val extractorsFactory = createExtractorsFactory()
            val renderersFactory: RenderersFactory =
                PlateaRenderersFactory(context, false)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(rendererMode)
            val mediaSourceFactory =
                DefaultMediaSourceFactory(
                    OkHttpDataSource.Factory(authOkHttpClient),
                    extractorsFactory,
                )
            val trackSelector = createTrackSelector(disableAudioOffload = disableAudioOffload)
            return ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(renderersFactory)
                .setTrackSelector(trackSelector)
                .build()
                .also {
                    it.setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        true,
                    )
                }
        }

        private fun createExtractorsFactory() =
            DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)

        private fun createTrackSelector(
            tunneling: Boolean? = null,
            disableAudioOffload: Boolean = false,
        ) = DefaultTrackSelector(context).apply {
            val offloadMode =
                if (disableAudioOffload) {
                    AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                } else {
                    AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                }
            setParameters(
                buildUponParameters()
                    .apply {
                        tunneling?.let { setTunnelingEnabled(tunneling) }
                    }.setAudioOffloadPreferences(
                        AudioOffloadPreferences
                            .Builder()
                            .setAudioOffloadMode(offloadMode)
                            .build(),
                    ),
            )
        }

        fun createMediaSession(player: Player) =
            MediaSession
                .Builder(context, player)
                .setCallback(TrustAllControllersCallback)
                .build()
    }

/**
 * Since Media3 1.11 controllers that are not trusted (e.g. the Google Home cast bridge or a phone remote) only
 * get read-only commands, so their play/pause is dropped: give every controller the default player commands.
 */
object TrustAllControllersCallback : MediaSession.Callback {
    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.ConnectionResult> =
        Futures.immediateFuture(
            MediaSession.ConnectionResult
                .AcceptedResultBuilder(session, controller)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build(),
        )
}

val Player.isReleased: Boolean
    get() = (this as? ExoPlayer)?.isReleased ?: false

data class PlayerCreation(
    val player: Player,
    val assHandler: AssHandler? = null,
)

// Code is adapted from https://github.com/androidx/media/blob/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java#L436
class PlateaRenderersFactory(
    context: Context,
    private val av1Enabled: Boolean,
) : DefaultRenderersFactory(context) {
    @OptIn(ExperimentalApi::class)
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        var videoRendererBuilder =
            MediaCodecVideoRenderer
                .Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                .experimentalSetParseAv1SampleDependencies(false)
                .experimentalSetLateThresholdToDropDecoderInputUs(C.TIME_UNSET)
        if (Build.VERSION.SDK_INT >= 34) {
            videoRendererBuilder =
                videoRendererBuilder.experimentalSetEnableMediaCodecBufferDecodeOnlyFlag(
                    false,
                )
        }
        // Platea: tells the decoder the film's frame rate (24p on TVs that only expose 60 Hz), see PlateaVideoRenderer
        out.add(com.github.gionnio.platea.playback.PlateaVideoRenderer(videoRendererBuilder))

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) {
            return
        }
        var extensionRendererIndex = out.size
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--
        }

        if (av1Enabled) {
            try {
                val clazz = Class.forName("androidx.media3.decoder.av1.Libdav1dVideoRenderer")
                val constructor: Constructor<*> =
                    clazz.getConstructor(
                        Long::class.javaPrimitiveType,
                        Handler::class.java,
                        VideoRendererEventListener::class.java,
                        Int::class.javaPrimitiveType,
                    )
                val renderer =
                    constructor.newInstance(
                        allowedVideoJoiningTimeMs,
                        eventHandler,
                        eventListener,
                        MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
                    ) as Renderer
                out.add(extensionRendererIndex++, renderer)
                Timber.i("Loaded Libdav1dVideoRenderer.")
            } catch (e: Exception) {
                // The extension is present, but instantiation failed.
                throw java.lang.IllegalStateException("Error instantiating AV1 extension", e)
            }
        }
    }
}
