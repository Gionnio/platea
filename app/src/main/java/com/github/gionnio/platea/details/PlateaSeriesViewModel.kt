package com.github.gionnio.platea.details

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.ChosenStreams
import com.github.gionnio.platea.data.ItemPlaybackRepository
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.services.WatchedService
import com.github.gionnio.platea.services.ImageUrlService
import com.github.gionnio.platea.services.NavigationManager
import com.github.gionnio.platea.services.StreamChoiceService
import com.github.gionnio.platea.services.UserPreferencesService
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.home.PlateaThemeSongs
import com.github.gionnio.platea.home.ThemeRef
import com.github.gionnio.platea.ui.FocusMemory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.util.UUID

data class SeasonUi(
    val id: UUID,
    val number: Int?,
    val label: String,
    /** Every episode of the season watched. */
    val played: Boolean = false,
)

data class PlateaSeriesState(
    val series: BaseItem? = null,
    val error: String? = null,
    val meta: List<String> = emptyList(),
    val seasons: List<SeasonUi> = emptyList(),
    /** Episode the main button plays: the one in progress or the next one, else the first. */
    val next: BaseItem? = null,
    val nextChosen: ChosenStreams? = null,
    val badges: List<String> = emptyList(),
    val selectedSeason: Int = 0,
    /** Episodes of the selected season; null while loading. */
    val episodes: List<BaseItem>? = null,
    val theme: ThemeRef? = null,
    /** Episode whose audio and subtitles panel is open. */
    val tracksItem: BaseItem? = null,
    val tracksChosen: ChosenStreams? = null,
)

/** Series page: series, seasons, the episode to resume, the episodes of one season, theme song. */
@HiltViewModel(assistedFactory = PlateaSeriesViewModel.Factory::class)
class PlateaSeriesViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val imageUrlService: ImageUrlService,
        private val navigationManager: NavigationManager,
        private val watchedService: WatchedService,
        private val itemPlaybackRepository: ItemPlaybackRepository,
        private val streamChoiceService: StreamChoiceService,
        private val userPreferencesService: UserPreferencesService,
        private val themeSongs: PlateaThemeSongs,
        @Assisted("series") val seriesId: UUID,
        @Assisted("episode") private val focusEpisodeId: UUID?,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(
                @Assisted("series") seriesId: UUID,
                @Assisted("episode") focusEpisodeId: UUID?,
            ): PlateaSeriesViewModel
        }

        private val _state = MutableStateFlow(PlateaSeriesState())
        val state: StateFlow<PlateaSeriesState> = _state

        val focusMemory = FocusMemory()
        val themePlaying = themeSongs.playing
        private var episodesJob: Job? = null

        init {
            // Opened on one episode ("Vai all'episodio"): the cursor goes on it, as when coming back from it
            focusEpisodeId?.let { focusMemory.remember("ep:$it") }
            load(firstTime = true)
            // Reload when the server has recorded where playback stopped
            viewModelScope.launch { com.github.gionnio.platea.PlaybackEvents.stopped.drop(1).collect { load() } }
        }

        /** First load, and again when coming back from the player (progress and watched marks change). */
        fun load(firstTime: Boolean = false) {
            viewModelScope.launch(PlateaDispatchers.IO) {
                runCatching {
                    val seriesDeferred = async { BaseItem(api.userLibraryApi.getItem(seriesId).content) }
                    val seasonsDeferred = async { api.tvShowsApi.getSeasons(seriesId, enableUserData = true).content.items }
                    val next = nextEpisode()
                    val series = seriesDeferred.await()
                    val seasons =
                        seasonsDeferred.await().map { s ->
                            SeasonUi(
                                id = s.id,
                                number = s.indexNumber,
                                label =
                                    when (s.indexNumber) {
                                        0 -> context.getString(R.string.platea_specials)
                                        null -> s.name.orEmpty()
                                        else -> context.getString(R.string.platea_season, s.indexNumber)
                                    },
                                played = s.userData?.played == true,
                            )
                        }
                    val focusSeason =
                        if (firstTime && focusEpisodeId != null) {
                            runCatching { api.userLibraryApi.getItem(focusEpisodeId).content.seasonId }.getOrNull()
                        } else {
                            null
                        }
                    val selected =
                        if (firstTime) {
                            seasons.indexOfFirst { it.id == focusSeason }.takeIf { it >= 0 }
                                ?: seasons.indexOfFirst { it.id == next?.data?.seasonId }.takeIf { it >= 0 }
                                ?: seasons.indexOfFirst { (it.number ?: 0) > 0 }.coerceAtLeast(0)
                        } else {
                            _state.value.selectedSeason.coerceAtMost((seasons.size - 1).coerceAtLeast(0))
                        }
                    _state.update {
                        it.copy(
                            series = series,
                            error = null,
                            meta = meta(series, seasons.count { s -> (s.number ?: 0) > 0 }),
                            seasons = seasons,
                            next = next,
                            badges = MediaBadges.of(context, next?.data),
                            selectedSeason = selected,
                        )
                    }
                    next?.let { n -> _state.update { it.copy(nextChosen = chosenStreams(n)) } }
                    seasons.getOrNull(selected)?.let { loadEpisodes(it, keep = !firstTime) }
                    if (firstTime) {
                        val theme = themeSongs.resolve(seriesId)
                        _state.update { it.copy(theme = theme) }
                    }
                }.onFailure { e ->
                    Timber.w(e, "Series %s", seriesId)
                    if (_state.value.series == null) _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
                }
            }
        }

        /** In progress or next up (Jellyfin's Next Up includes resumable episodes), else the first episode. */
        private suspend fun nextEpisode(): BaseItem? {
            val id =
                api.tvShowsApi
                    .getNextUp(seriesId = seriesId, limit = 1, enableResumable = true)
                    .content.items
                    .firstOrNull()
                    ?.id
                    ?: api.tvShowsApi
                        .getEpisodes(seriesId, limit = 1)
                        .content.items
                        .firstOrNull()
                        ?.id
                    ?: return null
            // Full item: media sources and streams for badges, tracks and the playback prediction
            return BaseItem(api.userLibraryApi.getItem(id).content)
        }

        fun selectSeason(index: Int) {
            val season = _state.value.seasons.getOrNull(index) ?: return
            if (index == _state.value.selectedSeason && _state.value.episodes != null) return
            _state.update { it.copy(selectedSeason = index, episodes = null) }
            loadEpisodes(season, keep = false)
        }

        private fun loadEpisodes(
            season: SeasonUi,
            keep: Boolean,
        ) {
            episodesJob?.cancel()
            if (!keep) _state.update { it.copy(episodes = null) }
            episodesJob =
                viewModelScope.launch(PlateaDispatchers.IO) {
                    runCatching {
                        api.tvShowsApi
                            .getEpisodes(
                                seriesId,
                                seasonId = season.id,
                                fields = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
                                enableUserData = true,
                            ).content.items
                            .map { BaseItem(it) }
                    }.onSuccess { list ->
                        if (_state.value.seasons.getOrNull(_state.value.selectedSeason)?.id == season.id) {
                            _state.update { it.copy(episodes = list) }
                        }
                    }.onFailure { Timber.w(it, "Episodes of %s", season.id) }
                }
        }

        fun play(
            item: BaseItem,
            fromStart: Boolean = false,
        ) {
            themeSongs.stopNow()
            navigationManager.navigateTo(Destination.Playback(item.id, if (fromStart) 0 else item.resumeMs))
        }

        fun setEpisodeWatched(
            item: BaseItem,
            played: Boolean,
        ) = viewModelScope.launch(PlateaDispatchers.IO) {
            runCatching { watchedService.setWatched(item.id, played) }.onFailure { Timber.w(it) }
            load()
        }

        /** Marks all the episodes of a season as watched or not (Jellyfin applies it to the children). */
        fun setSeasonWatched(
            season: SeasonUi,
            played: Boolean,
        ) = viewModelScope.launch(PlateaDispatchers.IO) {
            runCatching { watchedService.setWatched(season.id, played) }.onFailure { Timber.w(it) }
            load()
        }

        // --- Audio and subtitles ---

        private suspend fun chosenStreams(item: BaseItem): ChosenStreams? =
            runCatching { itemPlaybackRepository.getSelectedTracks(item.id, item, userPreferencesService.getCurrent()) }
                .onFailure { Timber.w(it, "Tracks of %s", item.id) }
                .getOrNull()

        /** Opens the panel for an episode (the list has no media streams: the full item is fetched). */
        fun openTracks(item: BaseItem) {
            viewModelScope.launch(PlateaDispatchers.IO) {
                val full =
                    if (item.id == _state.value.next?.id) {
                        _state.value.next!!
                    } else {
                        runCatching { BaseItem(api.userLibraryApi.getItem(item.id).content) }.getOrNull() ?: return@launch
                    }
                val chosen = if (full.id == _state.value.next?.id) _state.value.nextChosen ?: chosenStreams(full) else chosenStreams(full)
                _state.update { it.copy(tracksItem = full, tracksChosen = chosen) }
            }
        }

        fun closeTracks() = _state.update { it.copy(tracksItem = null, tracksChosen = null) }

        fun saveTrackSelection(
            type: MediaStreamType,
            trackIndex: Int,
        ) {
            val item = _state.value.tracksItem ?: return
            val playback = _state.value.tracksChosen?.itemPlayback
            viewModelScope.launch(PlateaDispatchers.IO) {
                val prefs = userPreferencesService.getCurrent()
                val plc = streamChoiceService.getPlaybackLanguageChoice(item.data)
                val result = itemPlaybackRepository.saveTrackSelection(item = item, itemPlayback = playback, trackIndex = trackIndex, type = type)
                val chosen = itemPlaybackRepository.getChosenItemFromPlayback(item, result, plc, prefs)
                _state.update {
                    it.copy(
                        tracksChosen = chosen,
                        nextChosen = if (item.id == it.next?.id) chosen else it.nextChosen,
                    )
                }
            }
        }

        // --- Theme song ---

        fun playTheme() = themeSongs.play(_state.value.theme)

        fun stopTheme() = themeSongs.stop()

        // --- Formatting ---

        fun backdrop(item: BaseItem) = imageUrlService.getOwnImageUrl(item, ImageType.BACKDROP, maxWidth = 1920)

        fun logo(item: BaseItem) = imageUrlService.getOwnImageUrl(item, ImageType.LOGO, maxHeight = 260)

        fun still(episode: BaseItem) = imageUrlService.getItemImageUrl(episode, ImageType.PRIMARY, fillWidth = 480, useSeriesForPrimary = false)

        /** "S2:E5" */
        fun code(episode: BaseItem) = "S${episode.data.parentIndexNumber ?: 0}:E${episode.data.indexNumber ?: 0}"

        fun timeLeft(item: BaseItem): String {
            val runtime = item.data.runTimeTicks ?: 0
            val position = item.data.userData?.playbackPositionTicks ?: 0
            return duration((runtime - position).coerceAtLeast(0))
        }

        fun episodeMeta(episode: BaseItem): String {
            val runtime = episode.data.runTimeTicks?.takeIf { it > 0 }
            val position = episode.data.userData?.playbackPositionTicks ?: 0
            return listOfNotNull(
                runtime?.let { duration(it) },
                if (runtime != null && position > 0 && episode.played.not()) context.getString(R.string.platea_time_left, timeLeft(episode)) else null,
            ).joinToString(" · ")
        }

        fun progress(episode: BaseItem): Float? {
            if (episode.played) return 1f
            val runtime = episode.data.runTimeTicks ?: return null
            val position = episode.data.userData?.playbackPositionTicks ?: 0
            return if (runtime > 0 && position > 0) position.toFloat() / runtime else null
        }

        private fun meta(
            series: BaseItem,
            seasonCount: Int,
        ): List<String> {
            val start = series.data.productionYear
            val end = series.data.endDate?.year
            return listOfNotNull(
                start?.let { if (end != null && end != it) "$it–$end" else it.toString() },
                seasonCount.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.platea_seasons, it, it) },
                series.data.genres?.take(2)?.joinToString(", ")?.takeIf { it.isNotBlank() },
                series.data.officialRating?.substringAfter('-'),
            )
        }

        private fun duration(ticks: Long): String {
            val totalMin = (ticks / 600_000_000L).toInt()
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "$h h $m min" else "$m min"
        }
    }
