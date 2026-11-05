package com.github.gionnio.platea.ui.playback

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.github.gionnio.platea.data.ItemPlaybackDao
import com.github.gionnio.platea.data.ItemPlaybackRepository
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.data.model.Chapter
import com.github.gionnio.platea.data.model.Playlist
import com.github.gionnio.platea.data.model.PlaylistItem
import com.github.gionnio.platea.preferences.AppPreference
import com.github.gionnio.platea.preferences.PlayerBackend
import com.github.gionnio.platea.preferences.ShowNextUpWhen
import com.github.gionnio.platea.preferences.UserPreferences
import com.github.gionnio.platea.preferences.enabled
import com.github.gionnio.platea.services.DatePlayedService
import com.github.gionnio.platea.services.DeviceProfileService
import com.github.gionnio.platea.services.ImageUrlService
import com.github.gionnio.platea.services.NavigationManager
import com.github.gionnio.platea.services.PlayerFactory
import com.github.gionnio.platea.services.PlaylistCreationResult
import com.github.gionnio.platea.services.PlaylistCreator
import com.github.gionnio.platea.services.RefreshRateService
import com.github.gionnio.platea.services.ScreensaverService
import com.github.gionnio.platea.services.StreamChoiceService
import com.github.gionnio.platea.services.UserPreferencesService
import com.github.gionnio.platea.ui.gt
import com.github.gionnio.platea.ui.launchDefault
import com.github.gionnio.platea.ui.launchIO
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.ui.onMain
import com.github.gionnio.platea.ui.showToast
import com.github.gionnio.platea.R
import com.github.gionnio.platea.engine.DolbyVisionCheck
import com.github.gionnio.platea.engine.FallbackPolicy
import com.github.gionnio.platea.playback.PlayerDiagnostics
import com.github.gionnio.platea.playback.RemoteCommands
import com.github.gionnio.platea.playback.ResolvedStream
import com.github.gionnio.platea.playback.SegmentTracker
import com.github.gionnio.platea.playback.StreamResolver
import com.github.gionnio.platea.engine.RetryBudget
import com.github.gionnio.platea.engine.StallWatchdog
import com.github.gionnio.platea.util.ExceptionHandler
import com.github.gionnio.platea.util.LoadingState
import com.github.gionnio.platea.util.PlaybackItemState
import com.github.gionnio.platea.util.TrackActivityPlaybackListener
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.util.checkForSupport
import com.github.gionnio.platea.util.profile.Codec
import com.github.gionnio.platea.util.supportItemKinds
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import timber.log.Timber
import java.util.Date
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * This [ViewModel] is responsible for playing media including moving through playlists (including next up episodes)
 */
@HiltViewModel(assistedFactory = PlaybackViewModel.Factory::class)
@OptIn(markerClass = [UnstableApi::class])
class PlaybackViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext internal val context: Context,
        internal val api: ApiClient,
        val navigationManager: NavigationManager,
        private val playlistCreator: PlaylistCreator,
        private val itemPlaybackDao: ItemPlaybackDao,
        internal val serverRepository: ServerRepository,
        internal val itemPlaybackRepository: ItemPlaybackRepository,
        private val playerFactory: PlayerFactory,
        private val datePlayedService: DatePlayedService,
        private val deviceInfo: DeviceInfo,
        private val deviceProfileService: DeviceProfileService,
        private val refreshRateService: RefreshRateService,
        val streamChoiceService: StreamChoiceService,
        private val userPreferencesService: UserPreferencesService,
        private val imageUrlService: ImageUrlService,
        private val screensaverService: ScreensaverService,
        private val playbackJournal: com.github.gionnio.platea.PlaybackJournal,
        private val plateaProfiles: com.github.gionnio.platea.PlateaProfileProvider,
        private val nasSettings: com.github.gionnio.platea.nas.NasSettings,
        @Assisted private val destination: Destination,
    ) : ViewModel(),
        Player.Listener {
        /** Retries spent on the current title after read errors (Platea fallback policy). */
        private val retryBudget = RetryBudget { SystemClock.elapsedRealtime() }

        // One stream change at a time: two quick track changes must not build two streams in parallel
        private val streamMutex = Mutex()
        private var controlsObserved = false

        @AssistedFactory
        interface Factory {
            fun create(destination: Destination): PlaybackViewModel
        }

        val currentPlayer = MutableStateFlow<PlayerInstance?>(null)

        private val _state = MutableStateFlow(PlaybackState())
        val state: StateFlow<PlaybackState> = _state

        private val diagnostics =
            PlayerDiagnostics(
                scope = viewModelScope,
                state = _state,
                journal = playbackJournal,
                videoMimeType = { (player as? ExoPlayer)?.videoFormat?.sampleMimeType },
                capabilities = { plateaProfiles.deviceCapabilities() },
                onDolbyVisionLost = { shown ->
                    val text =
                        when (shown) {
                            DolbyVisionCheck.Shown.WRONG_COLOURS -> context.getString(R.string.platea_dv_lost_colours)
                            else -> context.getString(R.string.platea_dv_lost, shown.name)
                        }
                    tell(text)
                },
            )

        internal lateinit var player: Player

        private var mediaSession: MediaSession? = null

        // Mutex & Job for changing playlist index
        private val playlistMutex = Mutex()
        private var playlistJob: Job? = null

        val controllerViewState =
            ControllerViewState(
                AppPreference.ControllerTimeout.defaultValue,
                true,
            )

        internal lateinit var preferences: UserPreferences
        internal lateinit var itemId: UUID
        internal lateinit var currentItem: PlaylistItem
        internal var forceTranscoding: Boolean = false
        private var activityListener: TrackActivityPlaybackListener? = null

        /** What the progress reports say about the current title; read by [activityListener] at every report. */
        @Volatile
        private var reportingState: PlaybackItemState? = null
        private var trackChangeListener: TracksChangedListener? = null
        private val jobs = mutableListOf<Job>()

        private val isPlaylist = destination is Destination.PlaybackList

        // Exposed for testing
        val initJob: Job

        init {
            initJob =
                viewModelScope.launchIO {
                    addCloseable {
                        screensaverService.keepScreenOn(false)
                        disconnectPlayer()
                    }
                    guarded { init() }
                }
        }

        /**
         * Platea: nothing that goes wrong while starting a title may leave the loading screen spinning. The
         * error is shown, with a way to try again.
         */
        private suspend fun guarded(block: suspend () -> Unit) {
            try {
                block()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.e(ex, "Playback could not start")
                _state.update { it.copy(loading = LoadingState.Error(context.getString(R.string.platea_msg_start_failed), ex)) }
            }
        }

        /** Tries again after an error: from where the title was, or from the beginning if it never started. */
        fun retry() {
            viewModelScope.launchIO {
                _state.update { it.copy(loading = LoadingState.Loading) }
                guarded {
                    if (this@PlaybackViewModel::currentItem.isInitialized && this@PlaybackViewModel::player.isInitialized) {
                        retryBudget.reset()
                        val position = onMain { player.currentPosition }
                        if (!play(currentItem, position)) navigationManager.goBack()
                    } else {
                        init()
                    }
                }
            }
        }

        private fun disconnectPlayer() {
            if (this@PlaybackViewModel::player.isInitialized) {
                player.removeListener(this@PlaybackViewModel)
                (player as? ExoPlayer)?.removeAnalyticsListener(diagnostics)

                this@PlaybackViewModel.activityListener?.let {
                    it.release()
                    player.removeListener(it)
                }
                reportingState = null
                this@PlaybackViewModel.trackChangeListener?.let {
                    player.removeListener(it)
                }
                player.release()
                mediaSession?.release()
            }
            jobs.forEach { it.cancel() }
        }

        private suspend fun createPlayer(
            isHdr: Boolean,
            is4k: Boolean,
        ) {
            val requestBackend =
                (destination as? Destination.Playback)?.backend
                    ?: preferences.appPreferences.playbackPreferences.playerBackend
            val playerBackend =
                when (requestBackend) {
                    PlayerBackend.UNRECOGNIZED,
                    PlayerBackend.EXO_PLAYER,
                    -> PlayerBackend.EXO_PLAYER

                    // No libmpv in Platea
                    PlayerBackend.MPV, PlayerBackend.PREFER_MPV -> PlayerBackend.EXO_PLAYER

                    PlayerBackend.EXTERNAL_PLAYER -> throw IllegalStateException("Cannot use this for external playback")
                }

            Timber.d("Selected backend: %s", playerBackend)
            if (currentPlayer.value?.backend != playerBackend) {
                Timber.i("Switching player backend to %s", playerBackend)
                withContext(PlateaDispatchers.Main) {
                    disconnectPlayer()
                }

                val playerCreation =
                    playerFactory.createVideoPlayer(
                        playerBackend,
                        preferences.appPreferences,
                    )
                this.player = playerCreation.player
                currentPlayer.update {
                    PlayerInstance(playerCreation.player, playerBackend, playerCreation.assHandler)
                }
                configurePlayer()
            }
        }

        private fun configurePlayer() {
            player.addListener(this)
            (player as? ExoPlayer)?.addAnalyticsListener(diagnostics)
            jobs.add(remoteCommands.listen(viewModelScope))
            jobs.add(listenForTranscodeReason())
            val sessionPlayer =
                MediaSessionPlayer(
                    player,
                    preferences.appPreferences.playbackPreferences,
                )
            mediaSession = playerFactory.createMediaSession(sessionPlayer)
        }

        /**
         * Initialize from the UI to start playback
         */
        private suspend fun init() {
            _state.update { it.copy(nextUp = null) }
            this.preferences = userPreferencesService.getCurrent()
            if (preferences.appPreferences.playbackPreferences.refreshRateSwitching) {
                addCloseable { refreshRateService.resetRefreshRate() }
            }
            controllerViewState.hideMilliseconds =
                preferences.appPreferences.playbackPreferences.controllerTimeoutMs
            this.forceTranscoding =
                (destination as? Destination.Playback)?.forceTranscoding ?: false
            val positionMs: Long
            val forceTranscoding: Boolean

            val itemId =
                when (val d = destination) {
                    is Destination.Playback -> {
                        positionMs = d.positionMs
                        forceTranscoding = d.forceTranscoding
                        d.itemId
                    }

                    is Destination.PlaybackList -> {
                        positionMs = 0
                        forceTranscoding = false
                        d.itemId
                    }

                    else -> {
                        throw IllegalArgumentException("Destination not supported: $destination")
                    }
                }
            this.itemId = itemId
            val plateaQueueStart =
                (destination as? Destination.PlaybackList)?.startIndex?.takeIf { it in 1 until Playlist.MAX_SIZE && !destination.shuffle } ?: 0
            var queueResumeMs = 0L
            val queriedItem = api.userLibraryApi.getItem(itemId).content
            val playlistItem =
                if (queriedItem.type.playable) {
                    PlaylistItem.Media(BaseItem(queriedItem, false))
                } else {
                    val playlistResult =
                        if (destination is Destination.PlaybackList) {
                            playlistCreator.createFrom(
                                item = queriedItem,
                                // Platea: the whole queue is loaded and playback starts at startIndex, so the
                                // titles before it stay reachable with "previous" (see plateaQueueStart below)
                                startIndex = if (plateaQueueStart > 0) 0 else destination.startIndex ?: 0,
                                sortAndDirection = destination.sortAndDirection,
                                shuffled = destination.shuffle,
                                recursive = destination.recursive,
                                filter = destination.filter,
                            )
                        } else {
                            val shuffled =
                                if (destination is Destination.Playback) {
                                    destination.shuffle
                                } else {
                                    false
                                }
                            // Try to create a playlist
                            playlistCreator.createFrom(
                                item = queriedItem,
                                recursive = true,
                                shuffled = shuffled,
                            )
                        }
                    when (val r = playlistResult) {
                        is PlaylistCreationResult.Error -> {
                            _state.update { it.copy(loading = LoadingState.Error(r.message, r.ex)) }
                            return
                        }

                        is PlaylistCreationResult.Success -> {
                            if (r.playlist.items.isEmpty()) {
                                tell(context.getString(R.string.platea_msg_queue_empty), Toast.LENGTH_SHORT)
                                navigationManager.goBack()
                                return
                            }
                            val start = plateaQueueStart.takeIf { it in r.playlist.items.indices } ?: 0
                            _state.update {
                                it.copy(playlist = r.playlist, playlistIndex = start)
                            }
                            r.playlist.items[start].also {
                                // A queue started on a title in progress resumes it instead of restarting it
                                if (destination is Destination.PlaybackList && !it.item.played) queueResumeMs = it.item.resumeMs
                            }
                        }
                    }
                }

            if (!controlsObserved) {
                controlsObserved = true
                viewModelScope.launch(ExceptionHandler()) { controllerViewState.observe() }
            }

            val intros =
                // If not resuming playback & cinema mode is enabled, get potential intros
                if (positionMs == 0L && preferences.appPreferences.playbackPreferences.cinemaMode) {
                    api.userLibraryApi
                        .getIntros(
                            itemId = playlistItem.id,
                            userId = serverRepository.currentUser?.id,
                        ).content.items
                        .map {
                            PlaylistItem.Intro(BaseItem(it))
                        }
                } else {
                    emptyList()
                }
            val firstItem =
                if (intros.isNotEmpty()) {
                    Timber.v("Got %s intros", intros.size)
                    _state.update {
                        it.copy(playlist = Playlist(intros + it.playlist.items))
                    }
                    intros.first()
                } else {
                    playlistItem
                }

            val played =
                play(
                    firstItem,
                    if (positionMs == 0L && firstItem === playlistItem) queueResumeMs else positionMs,
                    forceTranscoding,
                )
            if (!played) {
                playNextUp()
            }

            if (!isPlaylist) {
                // The title is already playing: a failure here only means there is no queue after it
                val result =
                    try {
                        playlistCreator.createFrom(queriedItem)
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        Timber.w(ex, "Could not build the queue after the title")
                        null
                    }
                if (result is PlaylistCreationResult.Success && result.playlist.items.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            playlist = Playlist(it.playlist.items + result.playlist.items),
                        )
                    }
                }
            }
        }

        /** A message for the user. Never interrupts what playback is doing if it cannot be shown. */
        private suspend fun tell(
            text: String,
            duration: Int = Toast.LENGTH_LONG,
        ) {
            try {
                showToast(context, text, duration)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "Cannot show a playback message")
            }
        }

        internal fun updateCurrentPlayback(block: (CurrentPlayback?) -> CurrentPlayback?) {
            _state.update {
                it.copy(currentPlayback = block.invoke(it.currentPlayback))
            }
        }

        /**
         * Play an item
         *
         * @param currentItem the item to play
         * @param positionMs the starting playback position in milliseconds
         * @param itemPlayback the parameters for playback such chosen subtitle or audio streams
         * @param forceTranscoding whether the user has requested to force playback via transcoding
         */
        private suspend fun play(
            playlistItem: PlaylistItem,
            positionMs: Long,
            forceTranscoding: Boolean = this.forceTranscoding,
        ): Boolean =
            withContext(PlateaDispatchers.IO) {
                val item =
                    when (playlistItem) {
                        is PlaylistItem.Intro -> playlistItem.item
                        is PlaylistItem.Media -> playlistItem.item
                    }

                Timber.i("Playing ${item.id}")
                diagnostics.titleRequested()

                // New item, so we can clear the media segment tracker & subtitle cues
                segments.reset()
                _state.update { it.copy(subtitleCues = emptyList()) }

                viewModelScope.launchIO {
                    // Starting playback, so want to invalidate the last played timestamp for this item
                    datePlayedService.invalidate(item)
                }

                if (item.type !in supportItemKinds) {
                    tell(context.getString(R.string.platea_msg_unsupported, item.type.toString()), Toast.LENGTH_SHORT)
                    return@withContext false
                }
                this@PlaybackViewModel.currentItem = playlistItem
                this@PlaybackViewModel.itemId = item.id

                val isLiveTv = item.type == BaseItemKind.TV_CHANNEL
                val base = item.data

                // Use the provided playback parameters or else check if the database has some
                val itemPlayback =
                    serverRepository.currentUser?.let { user ->
                        itemPlaybackDao.getItem(user, base.id)?.let {
                            Timber.v("Fetched itemPlayback from DB: %s", it)
                            if (it.sourceId != null) {
                                it
                            } else {
                                null
                            }
                        }
                    }
                val mediaSource =
                    if (!isLiveTv) {
                        streamChoiceService.chooseSource(base, itemPlayback)
                    } else {
                        null
                    }

                val plc = streamChoiceService.getPlaybackLanguageChoice(base)

                if (mediaSource == null && !isLiveTv) {
                    tell(context.getString(R.string.platea_msg_no_source), Toast.LENGTH_SHORT)
                    return@withContext false
                }

                val videoStream =
                    mediaSource
                        ?.mediaStreams
                        ?.firstOrNull { it.type == MediaStreamType.VIDEO }
                        ?.let {
                            val isHdr =
                                it.videoRange == VideoRange.HDR ||
                                    (it.videoRangeType != VideoRangeType.SDR && it.videoRangeType != VideoRangeType.UNKNOWN)
                            // Often times 4k movies have a wider aspect ratio so the height is lower even though the width is still 3840
                            val is4k = (it.width ?: 0) > 2560 || (it.height ?: 0) > 1440
                            SimpleVideoStream(it.index, isHdr, is4k)
                        }

                // Create the correct player for the media
                createPlayer(videoStream?.hdr == true, videoStream?.is4k == true)

                val subtitleStreams = mediaSource?.let { getSubtitleStreams(mediaSource) }.orEmpty()
                val audioStreams = mediaSource?.let { getAudioStreams(mediaSource) }.orEmpty()
                val audioStream =
                    mediaSource?.let {
                        streamChoiceService
                            .chooseAudioStream(
                                source = mediaSource,
                                seriesId = base.seriesId,
                                itemPlayback = itemPlayback,
                                plc = plc,
                                prefs = preferences,
                            )
                    }
                val audioIndex = audioStream?.index

                val subtitleIndex =
                    mediaSource?.let {
                        streamChoiceService
                            .chooseSubtitleStream(
                                source = mediaSource,
                                audioStream = audioStream,
                                seriesId = base.seriesId,
                                itemPlayback = itemPlayback,
                                plc = plc,
                                prefs = preferences,
                            )?.index
                    }
                Timber.d(
                    "Selected mediaSource=%s, audioIndex=%s, subtitleIndex=%s",
                    mediaSource?.id,
                    audioIndex,
                    subtitleIndex,
                )

                val chapters = Chapter.fromDto(base)
                _state.update {
                    it.copy(
                        currentItemPlayback = itemPlayback,
                        currentPlayback = null,
                    )
                }
                updateCurrentMedia {
                    CurrentMediaInfo(
                        sourceId = mediaSource?.id,
                        videoStream = videoStream,
                        audioStreams = audioStreams,
                        subtitleStreams = subtitleStreams,
                        chapters = chapters,
                    )
                }
                withContext(PlateaDispatchers.Main) {
                    changeStreams(
                        item = item,
                        audioIndex = audioIndex,
                        subtitleIndex = subtitleIndex,
                        positionMs = if (positionMs > 0) positionMs else C.TIME_UNSET,
                        sourceId = mediaSource?.id,
                        enableDirectPlay = !forceTranscoding,
                        enableDirectStream = !forceTranscoding,
                    )
                    player.prepare()
                    player.play()
                }
                segments.follow(item.id)
                return@withContext true
            }

        /**
         * Change which streams (ie audio or subtitle) are active
         */
        @OptIn(UnstableApi::class)
        internal suspend fun changeStreams(
            item: BaseItem,
            sourceId: String?,
            audioIndex: Int?,
            subtitleIndex: Int?,
            positionMs: Long = 0,
            enableDirectPlay: Boolean = !this.forceTranscoding,
            enableDirectStream: Boolean = !this.forceTranscoding,
        ): Unit =
            streamMutex.withLock {
                changeStreamsLocked(item, sourceId, audioIndex, subtitleIndex, positionMs, enableDirectPlay, enableDirectStream)
            }

        private suspend fun changeStreamsLocked(
            item: BaseItem,
            sourceId: String?,
            audioIndex: Int?,
            subtitleIndex: Int?,
            positionMs: Long,
            enableDirectPlay: Boolean,
            enableDirectStream: Boolean,
        ): Unit =
            withContext(PlateaDispatchers.IO) {
                val itemId = item.id

                trackChangeListener?.let {
                    it.cancel()
                    onMain { player.removeListener(it) }
                }
                trackChangeListener = null

                state.value.currentPlayback?.let { currentPlayback ->
                    if (currentPlayback.item.id == item.id &&
                        currentPlayback.playMethod == PlayMethod.DIRECT_PLAY &&
                        enableDirectPlay
                    ) {
                        val wasSuccessful =
                            changeStreamsDirectPlay(
                                currentPlayback = currentPlayback,
                                audioIndex = audioIndex,
                                subtitleIndex = subtitleIndex,
                            )
                        if (wasSuccessful) return@withContext
                    }
                }

                Timber.i(
                    "changeStreams (%s): audioIndex=%s, subtitleIndex=%s, enableDirectPlay=%s, enableDirectStream=%s, positionMs=%s",
                    itemId,
                    audioIndex,
                    subtitleIndex,
                    enableDirectPlay,
                    enableDirectStream,
                    positionMs,
                )

                val resolved =
                    streamResolver.resolve(
                        item = item,
                        sourceId = sourceId,
                        audioIndex = audioIndex,
                        subtitleIndex = subtitleIndex,
                        enableDirectPlay = enableDirectPlay,
                        enableDirectStream = enableDirectStream,
                        appPreferences = preferences.appPreferences,
                        backend = currentPlayer.value!!.backend,
                        allowNas = item.id !in nasFailed,
                    )
                diagnostics.mark("server")
                when (resolved) {
                    is ResolvedStream.Failed -> {
                        _state.update { it.copy(loading = LoadingState.Error(resolved.message)) }
                    }

                    is ResolvedStream.Ready -> {
                        if (enableDirectPlay && state.value.currentPlayback?.item?.id != item.id) retryBudget.reset()
                        startStream(item, resolved, sourceId, audioIndex, subtitleIndex, positionMs)
                    }
                }
            }

        /** Hands a resolved stream to the player and keeps reporting, display mode and track selection in step. */
        private suspend fun startStream(
            item: BaseItem,
            resolved: ResolvedStream.Ready,
            sourceId: String?,
            audioIndex: Int?,
            subtitleIndex: Int?,
            positionMs: Long,
        ) {
            val source = resolved.source
            val mediaItem = resolved.mediaItem
            val playback = resolved.playback
            com.github.gionnio.platea.playback.ContentFrameRate.fps =
                source.mediaStreams
                    ?.firstOrNull { it.type == MediaStreamType.VIDEO }
                    ?.let { it.realFrameRate ?: it.averageFrameRate } ?: 0f
            val transcodeType = playback.playMethod
            val externalSubtitle = resolved.externalSubtitle
            preferences.appPreferences.playbackPreferences.let { prefs ->
                source.mediaStreams
                    ?.firstOrNull { it.type == MediaStreamType.VIDEO }
                    ?.let { stream ->
                        refreshRateService.changeRefreshRate(
                            stream = stream,
                            switchRefreshRate = prefs.refreshRateSwitching,
                            switchResolution = prefs.resolutionSwitching,
                        )
                        // Platea: what the TV offered and what was chosen, to explain a display stuck at 60 Hz
                        refreshRateService.lastSwitch?.let { playbackJournal.display(it) }
                    }
            }
            diagnostics.mark("display")
            withContext(PlateaDispatchers.Main) {
                // Platea: changing audio or subtitles of the same title in direct play keeps the same
                // reporting session (no fake "stopped" + "started" on the server). A new session only for
                // a new title, or when the server is or was converting (its job must be closed).
                val previous = reportingState
                val sameSession =
                    this@PlaybackViewModel.activityListener != null &&
                        previous != null &&
                        previous.itemId == item.id &&
                        previous.playMethod == PlayMethod.DIRECT_PLAY &&
                        transcodeType == PlayMethod.DIRECT_PLAY
                reportingState = PlaybackItemState(playback)
                if (!sameSession) {
                    this@PlaybackViewModel.activityListener?.let {
                        it.release()
                        player.removeListener(it)
                    }
                    val activityListener =
                        TrackActivityPlaybackListener(
                            api = api,
                            player = player,
                            getState = { reportingState },
                            startPositionMs = positionMs,
                        )
                    player.addListener(activityListener)
                    this@PlaybackViewModel.activityListener = activityListener
                }

                _state.update {
                    it.copy(
                        loading = LoadingState.Success,
                        currentPlayback = playback,
                    )
                }
                player.setMediaItem(
                    mediaItem,
                    positionMs,
                )
                val onFailure: (TrackSelectionResult) -> Unit = { result ->
                    // Platea: leaving direct play is never silent. It is written in the journal, told
                    // to the user, and only done if "server conversion as fallback" is on.
                    val what =
                        listOfNotNull(
                            "audio $audioIndex ${result.audio}".takeIf { !result.audioSelected },
                            "subtitle $subtitleIndex ${result.subtitle}".takeIf { !result.subtitleSelected },
                        ).joinToString(", ")
                    val allowed = !preferences.appPreferences.playbackPreferences.overrides.plateaNoAutoTranscode
                    playbackJournal.fallback(if (allowed) "TrackToServer" else "TrackKeptDirect", what)
                    val audio = !result.audioSelected
                    if (allowed) {
                        viewModelScope.launchIO {
                            tell(context.getString(if (audio) R.string.platea_audio_to_server else R.string.platea_subtitles_to_server), Toast.LENGTH_LONG)
                            changeStreams(
                                item = item,
                                sourceId = sourceId,
                                audioIndex = audioIndex,
                                subtitleIndex = subtitleIndex,
                                positionMs = positionMs,
                                enableDirectPlay = false,
                                enableDirectStream = true,
                            )
                        }
                    } else {
                        viewModelScope.launchIO {
                            tell(context.getString(if (audio) R.string.platea_audio_unavailable else R.string.platea_subtitles_unavailable), Toast.LENGTH_LONG)
                        }
                        // What could be selected (e.g. the audio) is still applied
                        player.trackSelectionParameters = result.trackSelectionParameters
                    }
                }
                val onTracksChangedListener =
                    if (transcodeType == PlayMethod.DIRECT_PLAY && (audioIndex != null || subtitleIndex != null)) {
                        TracksChangedListener(
                            player = player,
                            audioIndex = audioIndex,
                            subtitleIndex = subtitleIndex,
                            source = source,
                            onFailure = onFailure,
                        )
                    } else if (externalSubtitle) {
                        TracksChangedListener(
                            player = player,
                            audioIndex = null, // Do not manually activate audio
                            subtitleIndex = subtitleIndex,
                            source = source,
                            onFailure = onFailure,
                        )
                    } else {
                        null
                    }
                onTracksChangedListener?.let {
                    player.addListener(it)
                    this@PlaybackViewModel.trackChangeListener = it
                }
            }
        }

        /**
         * If direct playing, can try to switch tracks without playback restarting
         * Except for external subtitles
         */
        @OptIn(UnstableApi::class)
        private suspend fun changeStreamsDirectPlay(
            currentPlayback: CurrentPlayback,
            audioIndex: Int?,
            subtitleIndex: Int?,
        ): Boolean =
            withContext(PlateaDispatchers.IO) {
                Timber.v("changeStreams direct play")

                // TODO Better way to handle unsupported types in general is needed
                // This is a workaround for switching to a non AC3 track when the user wants audio transcoded to AC3
                if (preferences.appPreferences.experimentalPreferences.enabled { preferAc3Surround } && audioIndex != null) {
                    currentPlayback.mediaSourceInfo.mediaStreams
                        .orEmpty()
                        .firstOrNull { it.index == audioIndex }
                        ?.let {
                            if (it.channels.gt(2) && it.codec != Codec.Audio.AC3) {
                                // User wants to transcode audio into AC3
                                return@withContext false
                            }
                        }
                }

                val source = currentPlayback.mediaSourceInfo
                val externalSubtitle = source.findExternalSubtitle(subtitleIndex)

                if (externalSubtitle == null) {
                    val result =
                        withContext(PlateaDispatchers.Main) {
                            TrackSelectionUtils.createTrackSelections(
                                onMain { player.trackSelectionParameters },
                                onMain { player.currentTracks },
                                audioIndex,
                                subtitleIndex,
                                source,
                            )
                        }
                    if (result.bothSelected) {
                        onMain { player.trackSelectionParameters = result.trackSelectionParameters }
                        Timber.d("Changes tracks audio=$audioIndex, subtitle=$subtitleIndex")
                        _state.update {
                            it.copy(
                                currentPlayback =
                                    (it.currentPlayback ?: currentPlayback).copy(
                                        tracks = checkForSupport(onMain { player.currentTracks }),
                                    ),
                            )
                        }
                        return@withContext true
                    }
                } else {
                    Timber.v("changeStreams direct play, external subtitle was requested")
                }
                return@withContext false
            }

        fun changeAudioStream(index: Int) {
            viewModelScope.launchIO {
                val currentPlayback = state.value.currentPlayback
                if (currentPlayback != null) {
                    Timber.d("Changing audio track to %s", index)
                    saveTrackSelection(index, MediaStreamType.AUDIO)
                    _state.update {
                        it.copy(
                            currentPlayback = it.currentPlayback?.copy(audioIndex = index),
                        )
                    }

                    // Resolve ONLY_FORCED to actual track based on new audio language
                    val resolvedSubtitleIndex =
                        streamChoiceService.resolveSubtitleIndex(
                            source = currentPlayback.mediaSourceInfo,
                            audioStreamIndex = index,
                            seriesId = currentItem.item.data.seriesId,
                            subtitleIndex = currentPlayback.subtitleIndex,
                            prefs = preferences,
                        )

                    changeStreams(
                        item = currentItem.item,
                        sourceId = currentPlayback.mediaSourceInfo.id,
                        audioIndex = index,
                        subtitleIndex = resolvedSubtitleIndex,
                        positionMs = onMain { player.currentPosition },
                        enableDirectPlay = true,
                    )
                } else {
                    Timber.w("Trying to change audio, but currentPlayback is null")
                }
            }
        }

        fun changeSubtitleStream(index: Int): Job =
            viewModelScope.launchIO {
                val currentPlayback = state.value.currentPlayback
                if (currentPlayback != null) {
                    Timber.d("Changing subtitle track to %s", index)
                    saveTrackSelection(index, MediaStreamType.SUBTITLE)
                    _state.update {
                        it.copy(
                            currentPlayback = it.currentPlayback?.copy(subtitleIndex = index),
                        )
                    }

                    // Resolve ONLY_FORCED to actual track index for playback
                    val resolvedIndex =
                        streamChoiceService.resolveSubtitleIndex(
                            source = currentPlayback.mediaSourceInfo,
                            audioStreamIndex = currentPlayback.audioIndex,
                            seriesId = currentItem.item.data.seriesId,
                            subtitleIndex = index,
                            prefs = preferences,
                        )

                    changeStreams(
                        item = currentItem.item,
                        sourceId = currentPlayback.mediaSourceInfo.id,
                        audioIndex = currentPlayback.audioIndex,
                        subtitleIndex = resolvedIndex,
                        positionMs = onMain { player.currentPosition },
                        enableDirectPlay = true,
                    )
                } else {
                    Timber.w("Trying to change subtitle, but currentPlayback is null")
                }
            }

        internal suspend fun saveTrackSelection(
            trackIndex: Int,
            type: MediaStreamType,
        ) {
            val itemPlayback =
                itemPlaybackRepository.saveTrackSelection(
                    item = currentItem.item,
                    itemPlayback = state.value.currentItemPlayback,
                    trackIndex = trackIndex,
                    type = type,
                )
            _state.update {
                it.copy(
                    currentItemPlayback = itemPlayback,
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) traceLoading()
            if (playbackState == Player.STATE_ENDED && recoverEarlyEnd()) return
            if (playbackState == Player.STATE_ENDED) {
                Timber.v("Playback state is STATE_ENDED")
                viewModelScope.launchDefault {
                    when (val nextItem = state.value.nextItem()) {
                        is PlaylistItem.Intro -> {
                            Timber.v("Next item is intro, so playing immediately")
                            playNextUp()
                        }

                        is PlaylistItem.Media -> {
                            val prefs = preferences.appPreferences.playbackPreferences
                            when {
                                currentItem is PlaylistItem.Intro -> {
                                    Timber.v("Current item is intro, so playing next up immediately")
                                    playNextUp()
                                }

                                prefs.showNextUpWhen == ShowNextUpWhen.NEXT_UP_NEVER && !prefs.autoPlayNext -> {
                                    Timber.v("Never show or auto play next up, returning")
                                    navigationManager.goBack()
                                }

                                prefs.showNextUpWhen != ShowNextUpWhen.NEXT_UP_NEVER -> {
                                    Timber.v("Setting next up to ${nextItem.id}")
                                    _state.update { it.copy(nextUp = nextItem.item) }
                                }

                                else -> {
                                    controllerViewState.showControls()
                                }
                            }
                        }

                        null -> {
                            Timber.v("No next up")
                            navigationManager.goBack()
                        }
                    }
                }
            }
        }

        private val streamResolver =
            StreamResolver(context, api, serverRepository, deviceProfileService, imageUrlService, playbackJournal) { nasSettings.current }

        /** Titles whose direct reading from the NAS failed in this session: they use the server's stream. */
        private val nasFailed = mutableSetOf<java.util.UUID>()

        private val segments =
            SegmentTracker(
                scope = viewModelScope,
                api = api,
                state = _state,
                preferences = { preferences.appPreferences.playbackPreferences },
                currentItemId = { itemId },
                position = { onMain { player.currentPosition } },
                seekTo = { onMain { player.seekTo(it) } },
            )

        // Exposed for testing
        internal val segmentJob: Job? get() = segments.job

        fun updateSegment(
            segmentId: UUID?,
            dismissed: Boolean,
        ) = segments.answer(segmentId, dismissed)

        private fun listenForTranscodeReason(): Job =
            viewModelScope.launchIO {
                state.map { it.currentPlayback }.collectLatest {
                    if (it != null && it.playMethod == PlayMethod.TRANSCODE && it.transcodeInfo == null) {
                        try {
                            var transcodeInfo = it.transcodeInfo
                            var attempts = 0
                            // Only feeds the diagnostics panel: no point asking for the whole film
                            while (isActive && transcodeInfo == null && attempts++ < 12) {
                                delay(2.seconds)
                                transcodeInfo =
                                    api.sessionApi
                                        .getSessions(deviceId = deviceInfo.id)
                                        .content
                                        .firstOrNull()
                                        ?.transcodingInfo
                                if (transcodeInfo == null) delay(3.seconds)
                            }
                            Timber.v("transcodeInfo=$transcodeInfo")
                            updateCurrentPlayback { current ->
                                current?.copy(transcodeInfo = transcodeInfo)
                            }
                        } catch (ex: Exception) {
                            if (ex !is CancellationException) {
                                Timber.w(ex, "Exception trying to get session info")
                                updateCurrentPlayback { current ->
                                    current?.copy(transcodeInfo = null)
                                }
                            }
                        }
                    }
                }
            }

        private var lastInteractionDate: Date = Date()

        /**
         * Tracks interactions with the UI for passout protection
         */
        fun reportInteraction() {
//            Timber.v("reportInteraction")
            lastInteractionDate = Date()
        }

        fun shouldAutoPlayNextUp(): Boolean =
            preferences.appPreferences.playbackPreferences.let {
                it.autoPlayNext &&
                    if (it.passOutProtectionMs > 0) {
                        (Date().time - lastInteractionDate.time) < it.passOutProtectionMs
                    } else {
                        true
                    }
            }

        fun playNextUp() {
            viewModelScope.launchDefault {
                playlistMutex.withLock {
                    val state = state.value
                    if (state.hasNext) {
                        cancelUpNextEpisode()
                        val nextIndex = state.playlistIndex + 1
                        val item = state.playlist.items[nextIndex]
                        _state.update { it.copy(playlistIndex = nextIndex) }
                        playlistJob?.cancel()
                        playlistJob =
                            viewModelScope.launchDefault {
                                val played = play(item, 0)
                                if (!played) {
                                    playNextUp()
                                }
                            }
                    } else {
                        Timber.w("Attempting to play next, but there are no more items")
                        return@launchDefault
                    }
                }
            }
        }

        fun playPrevious() {
            viewModelScope.launchDefault {
                playlistMutex.withLock {
                    val state = state.value
                    if (state.hasPrevious) {
                        cancelUpNextEpisode()
                        val previousIndex = state.playlistIndex - 1
                        val item = state.playlist.items[previousIndex]
                        _state.update { it.copy(playlistIndex = previousIndex) }
                        playlistJob?.cancel()
                        playlistJob =
                            viewModelScope.launchDefault {
                                val played = play(item, 0)
                                if (!played) {
                                    playPrevious()
                                }
                            }
                    } else {
                        Timber.w("Attempting to play previous, but there is none")
                        return@launchDefault
                    }
                }
            }
        }

        /**
         * Platea: previous item also for the episode playback started from. The queue only holds what follows
         * the first episode, so the one before it is looked up on the server and put in front of the queue.
         */
        fun playPreviousOrFetch() {
            if (state.value.hasPrevious) {
                playPrevious()
                return
            }
            viewModelScope.launchIO {
                val current = currentItem.item
                val seriesId = current.data.seriesId ?: return@launchIO
                val previous =
                    runCatching {
                        val around =
                            api.tvShowsApi
                                .getEpisodes(seriesId, adjacentTo = current.id, userId = serverRepository.currentUser?.id, fields = com.github.gionnio.platea.ui.DetailItemFields)
                                .content.items
                        around.getOrNull(around.indexOfFirst { it.id == current.id } - 1)
                    }.onFailure { Timber.w(it, "Platea previous episode lookup") }.getOrNull()
                Timber.i("Platea previous episode: %s", previous?.name)
                if (previous == null) return@launchIO
                _state.update {
                    it.copy(
                        playlist = Playlist(listOf(PlaylistItem.Media(BaseItem(previous))) + it.playlist.items),
                        playlistIndex = it.playlistIndex + 1,
                    )
                }
                playPrevious()
            }
        }

        suspend fun cancelUpNextEpisode() {
            _state.update { it.copy(nextUp = null) }
        }

        fun playItemInPlaylist(item: BaseItem) {
            viewModelScope.launchDefault {
                playlistMutex.withLock {
                    val state = state.value
                    val index = state.playlist.items.indexOfFirst { it.id == item.id }
                    if (index in state.playlist.items.indices) {
                        val toPlay = state.playlist.items[index]
                        _state.update { it.copy(playlistIndex = index) }
                        playlistJob?.cancel()
                        playlistJob =
                            viewModelScope.launchDefault {
                                val played = play(toPlay, 0)
                                if (!played) {
                                    playNextUp()
                                }
                            }
                    } else {
                        Timber.w("Item not found in playlist %s", item.id)
                        return@launchDefault
                    }
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateCurrentPlayback {
                it?.copy(
                    tracks = checkForSupport(tracks),
                )
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            _state.update { it.copy(subtitleCues = cueGroup.cues) }
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "Playback error")
            viewModelScope.launch(PlateaDispatchers.Main + ExceptionHandler()) {
                // Platea: some TVs (Sony) accept tunneled playback but cannot open the audio output it needs for
                // decoded multichannel audio. The title goes on without tunneling, with sound, at once.
                if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED && disableTunneling()) {
                    playbackJournal.fallback("TunnelingOff", "audio output not available in tunneled mode")
                    player.prepare()
                    player.play()
                    return@launch
                }
                state.value.currentPlayback?.let {
                    when (it.playMethod) {
                        PlayMethod.TRANSCODE -> {
                            val action = FallbackPolicy.onConversionError(error.errorCode, retryBudget.used)
                            playbackJournal.fallback(action::class.simpleName.orEmpty(), action.reason)
                            if (action is FallbackPolicy.Action.Retry) {
                                retryBudget.spend()
                                delay(action.delayMs)
                                if (state.value.currentPlayback?.item?.id != it.item.id) return@launch
                                player.prepare()
                                player.play()
                                return@launch
                            }
                            _state.update {
                                it.copy(
                                    loading =
                                        LoadingState.Error(
                                            context.getString(R.string.platea_msg_playback_error),
                                            error,
                                        ),
                                )
                            }
                        }

                        PlayMethod.DIRECT_STREAM, PlayMethod.DIRECT_PLAY -> {
                            // Platea: the NAS cannot be read (address, password, NAS off): the same title from
                            // the server's stream, at once, without spending the retries on the NAS
                            if (it.fromNas) {
                                nasFailed += it.item.id
                                playbackJournal.fallback("NasToServer", PlaybackException.getErrorCodeName(error.errorCode))
                                tell(context.getString(R.string.platea_nas_fallback), Toast.LENGTH_LONG)
                                changeStreams(
                                    item = currentItem.item,
                                    sourceId = it.mediaSourceInfo.id,
                                    audioIndex = it.audioIndex.takeIf { i -> i >= 0 },
                                    subtitleIndex = it.subtitleIndex.takeIf { i -> i >= 0 },
                                    positionMs = player.currentPosition,
                                )
                                player.prepare()
                                player.play()
                                return@launch
                            }
                            // Platea: direct play is the rule, see FallbackPolicy
                            val action =
                                FallbackPolicy.onDirectPlayError(
                                    errorCode = error.errorCode,
                                    retriesSoFar = retryBudget.used,
                                    autoTranscode = !preferences.appPreferences.playbackPreferences.overrides.plateaNoAutoTranscode,
                                )
                            playbackJournal.fallback(action::class.simpleName.orEmpty(), action.reason)
                            when (action) {
                                is FallbackPolicy.Action.Retry -> {
                                    retryBudget.spend()
                                    Timber.w("Playback error during ${it.playMethod}: ${action.reason}")
                                    delay(action.delayMs)
                                    if (state.value.currentPlayback?.item?.id != it.item.id) return@launch
                                    player.prepare()
                                    player.play()
                                    return@launch
                                }

                                is FallbackPolicy.Action.ShowError -> {
                                    _state.update { state ->
                                        state.copy(loading = LoadingState.Error(context.getString(R.string.platea_direct_play_failed, action.reason), error))
                                    }
                                    return@launch
                                }

                                is FallbackPolicy.Action.ServerTranscode -> {
                                    tell(context.getString(R.string.platea_server_transcode, action.reason), Toast.LENGTH_LONG)
                                }
                            }
                            Timber.w("Playback error during ${it.playMethod}, falling back to transcoding")
                            val currentPlayback = state.value.currentPlayback
                            if (currentPlayback == null) {
                                Timber.w("Playback error, currentPlayback is null")
                            }
                            changeStreams(
                                item = currentItem.item,
                                sourceId = currentPlayback?.mediaSourceInfo?.id,
                                audioIndex = currentPlayback?.audioIndex,
                                subtitleIndex = currentPlayback?.subtitleIndex,
                                positionMs = player.currentPosition,
                                enableDirectPlay = false,
                                enableDirectStream = false,
                            )
                            withContext(PlateaDispatchers.Main) {
                                player.prepare()
                                player.play()
                            }
                        }
                    }
                }
            }
        }

        private var traceJob: Job? = null

        /** Platea: a sample of the playback trace every 5 s while the title is meant to play (see PlayerDiagnostics). */
        private fun traceLoading() {
            if (traceJob?.isActive == true) return
            traceJob =
                viewModelScope.launch(PlateaDispatchers.Main) {
                    while (isActive && player.playWhenReady &&
                        (player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY)
                    ) {
                        delay(5_000)
                        diagnostics.sample(player)
                    }
                }
        }

        /**
         * Platea: Jellyfin's trickplay thumbnails of [playback] (the resolution closest to 320 px wide), or null when
         * the server has not generated them.
         */
        fun trickplaySource(playback: CurrentPlayback?): com.github.gionnio.platea.player.TrickplaySource? {
            val p = playback ?: return null
            val all = p.item.data.trickplay ?: return null
            val sourceId = p.mediaSourceInfo.id
            val byWidth = (sourceId?.let { all[it] } ?: all.values.firstOrNull()) ?: return null
            val info = byWidth.values.minByOrNull { kotlin.math.abs(it.width - 320) } ?: return null
            val itemId = p.item.id
            val mediaSourceId = sourceId?.toUUIDOrNull()
            return com.github.gionnio.platea.player.TrickplaySource(info) { sheet ->
                api.trickplayApi.getTrickplayTileImageUrl(itemId, info.width, sheet, mediaSourceId)
            }
        }

        /** Turns tunneled playback off for this player. @return true if it was on */
        private fun disableTunneling(): Boolean {
            val selector = (player as? ExoPlayer)?.trackSelector as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector ?: return false
            if (!selector.parameters.tunnelingEnabled) return false
            Timber.w("Audio output failed in tunneled mode: tunneling off")
            selector.setParameters(selector.buildUponParameters().setTunnelingEnabled(false))
            return true
        }

        private var earlyEnds = 0
        private var earlyEndItem: java.util.UUID? = null

        /**
         * Platea: an "end" well before the duration is a source that stopped, not the end (see EarlyEnd). The title
         * goes on from the same point (from the server when it was read from the NAS) instead of being marked as
         * watched and followed by the next episode. @return true when handled
         */
        private fun recoverEarlyEnd(): Boolean {
            val playback = state.value.currentPlayback ?: return false
            val position = player.currentPosition
            val duration = player.duration
            if (!com.github.gionnio.platea.engine.EarlyEnd.isEarly(position, duration)) return false
            if (earlyEndItem != playback.item.id) {
                earlyEndItem = playback.item.id
                earlyEnds = 0
            }
            if (earlyEnds >= com.github.gionnio.platea.engine.EarlyEnd.MAX_RECOVERIES) return false
            earlyEnds++
            Timber.w("Ended at %d ms of %d: the source stopped, playback goes on", position, duration)
            playbackJournal.fallback("EarlyEnd", "at $position of $duration ms" + if (playback.fromNas) " (NAS)" else "")
            viewModelScope.launch(PlateaDispatchers.Main + ExceptionHandler()) {
                if (playback.fromNas) {
                    nasFailed += playback.item.id
                    tell(context.getString(R.string.platea_nas_fallback), Toast.LENGTH_LONG)
                    changeStreams(
                        item = currentItem.item,
                        sourceId = playback.mediaSourceInfo.id,
                        audioIndex = playback.audioIndex.takeIf { it >= 0 },
                        subtitleIndex = playback.subtitleIndex.takeIf { it >= 0 },
                        positionMs = position,
                    )
                } else {
                    player.seekTo(position)
                }
                player.prepare()
                player.play()
            }
            return true
        }

        private val stallWatchdog = StallWatchdog()
        private var stallJob: Job? = null

        /**
         * Platea: see [StallWatchdog], a starving passthrough track must become a rebuffer, not an error. Runs only
         * while the player says it is playing, which is exactly when a stall can hide.
         */
        private fun watchForStall() {
            if (stallJob?.isActive == true) return
            stallJob =
                viewModelScope.launch(PlateaDispatchers.Main) {
                    stallWatchdog.reset()
                    while (isActive && player.isPlaying) {
                        delay(500)
                        val position = player.currentPosition
                        if (stallWatchdog.check(SystemClock.elapsedRealtime(), player.isPlaying, position, player.bufferedPosition)) {
                            Timber.w("Playing with no progress at %d ms and nothing buffered: flushing to rebuffer", position)
                            playbackJournal.fallback("Unstick", "no progress at $position ms, buffered ${player.bufferedPosition - position} ms")
                            _state.update { it.copy(stalled = true) }
                            player.seekTo(position)
                        } else if (state.value.stalled && !stallWatchdog.standing) {
                            _state.update { it.copy(stalled = false) }
                        }
                    }
                    if (state.value.stalled) _state.update { it.copy(stalled = false) }
                }
        }

        fun release() {
            Timber.v("release")
            disconnectPlayer()
            activityListener = null
        }

        private val remoteCommands =
            RemoteCommands(
                api = api,
                player = { player },
                preferences = { preferences.appPreferences.playbackPreferences },
                onStop = {
                    release()
                    navigationManager.goBack()
                },
                onNext = ::playNextUp,
                onPrevious = ::playPrevious,
            )

        /**
         * Atomically update [currentMediaInfo]
         */
        internal suspend fun updateCurrentMedia(block: (CurrentMediaInfo) -> CurrentMediaInfo) =
            withContext(PlateaDispatchers.Default) {
                _state.update {
                    it.copy(currentMediaInfo = block.invoke(it.currentMediaInfo))
                }
            }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            screensaverService.keepScreenOn(isPlaying)
            if (isPlaying) {
                watchForStall()
                traceLoading()
            }
        }
    }
