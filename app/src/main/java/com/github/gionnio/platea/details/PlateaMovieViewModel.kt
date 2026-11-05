package com.github.gionnio.platea.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.gionnio.platea.data.ChosenStreams
import com.github.gionnio.platea.data.ItemPlaybackRepository
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.data.model.Person
import com.github.gionnio.platea.data.model.Trailer
import com.github.gionnio.platea.services.WatchedService
import com.github.gionnio.platea.services.NavigationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.github.gionnio.platea.services.StreamChoiceService
import com.github.gionnio.platea.services.TrailerService
import com.github.gionnio.platea.services.UserPreferencesService
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.PlaybackEvents
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.util.UUID

data class PlateaMovieState(
    val movie: BaseItem? = null,
    val loading: Boolean = true,
    /** Set when the movie cannot be loaded at all (server unreachable…). */
    val error: String? = null,
    val chosen: ChosenStreams? = null,
    val trailers: List<Trailer> = emptyList(),
    val people: List<Person> = emptyList(),
)

/**
 * Movie page: the movie, the tracks Platea will play, trailers and cast. Only what the page shows
 * (Wholphin's MovieViewModel also fetched similar titles, Seerr suggestions, extras and delete rights).
 */
@HiltViewModel(assistedFactory = PlateaMovieViewModel.Factory::class)
class PlateaMovieViewModel
    @AssistedInject
    constructor(
        private val api: ApiClient,
        private val navigationManager: NavigationManager,
        private val itemPlaybackRepository: ItemPlaybackRepository,
        private val streamChoiceService: StreamChoiceService,
        private val userPreferencesService: UserPreferencesService,
        private val watchedService: WatchedService,
        @param:ApplicationContext private val context: Context,
        private val trailerService: TrailerService,
        @Assisted val movieId: UUID,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(movieId: UUID): PlateaMovieViewModel
        }

        private val _state = MutableStateFlow(PlateaMovieState())
        val state: StateFlow<PlateaMovieState> = _state

        init {
            load(everything = true)
            // Progress and watched mark, once the server has recorded where playback stopped
            viewModelScope.launch { PlaybackEvents.stopped.drop(1).collect { load(everything = false) } }
        }

        /** @param everything false: only the movie and its tracks (trailers and cast do not change) */
        fun load(everything: Boolean) {
            viewModelScope.launch(PlateaDispatchers.IO) {
                runCatching {
                    val movie = BaseItem(api.userLibraryApi.getItem(movieId).content)
                    val chosen = itemPlaybackRepository.getSelectedTracks(movieId, movie, userPreferencesService.getCurrent())
                    _state.update { it.copy(movie = movie, chosen = chosen, loading = false, error = null) }
                    if (everything) {
                        // Cast from the data the movie already carries: no request per person
                        _state.update { it.copy(people = movie.data.people.orEmpty().map { p -> Person.fromDto(context, p, api) }) }
                        // Only trailers stored on the server: online ones would open another app
                        launch { runCatching { trailerService.getLocalTrailers(movie) }.onSuccess { local -> _state.update { it.copy(trailers = local) } } }
                    }
                }.onFailure { e ->
                    Timber.w(e, "Movie %s", movieId)
                    _state.update { it.copy(loading = false, error = if (it.movie == null) e.localizedMessage ?: e.javaClass.simpleName else null) }
                }
            }
        }

        fun setWatched(played: Boolean) =
            viewModelScope.launch(PlateaDispatchers.IO) {
                runCatching { watchedService.setWatched(movieId, played) }.onFailure { Timber.w(it) }
                load(everything = false)
            }

        fun saveTrackSelection(
            type: MediaStreamType,
            trackIndex: Int,
        ) {
            val movie = _state.value.movie ?: return
            val playback = _state.value.chosen?.itemPlayback
            viewModelScope.launch(PlateaDispatchers.IO) {
                runCatching {
                    val prefs = userPreferencesService.getCurrent()
                    val plc = streamChoiceService.getPlaybackLanguageChoice(movie.data)
                    val saved = itemPlaybackRepository.saveTrackSelection(item = movie, itemPlayback = playback, trackIndex = trackIndex, type = type)
                    _state.update { it.copy(chosen = itemPlaybackRepository.getChosenItemFromPlayback(movie, saved, plc, prefs)) }
                }.onFailure { Timber.w(it, "Track selection") }
            }
        }

        fun navigateTo(destination: Destination) = navigationManager.navigateTo(destination)
    }
