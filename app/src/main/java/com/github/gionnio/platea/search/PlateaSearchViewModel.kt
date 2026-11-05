package com.github.gionnio.platea.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.services.ImageUrlService
import com.github.gionnio.platea.services.NavigationManager
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.util.SearchRelevance
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.home.NavLibrary
import com.github.gionnio.platea.ui.FocusMemory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.personsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import javax.inject.Inject

enum class SearchGroup { MOVIES, SERIES, EPISODES, PEOPLE, COLLECTIONS }

data class PlateaSearchState(
    val query: String = "",
    val searching: Boolean = false,
    /** Null until the first search; groups without results are left out. */
    val results: Map<SearchGroup, List<BaseItem>>? = null,
    /** Every request of the last search failed: the server is not answering. */
    val error: String? = null,
    val libraries: List<NavLibrary> = emptyList(),
    val userInitial: String = "",
)

/** Search as you type: movies, series, episodes, people and collections, each in its own row. */
@HiltViewModel
class PlateaSearchViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val plateaLibraries: com.github.gionnio.platea.PlateaLibraries,
        private val imageUrlService: ImageUrlService,
        val navigationManager: NavigationManager,
        /** Wholphin's speech recognition wrapper (reviewed 10/10/2026: kept as is). */
        val voice: com.github.gionnio.platea.ui.components.VoiceInputManager,
    ) : ViewModel() {
        override fun onCleared() {
            voice.stopListening()
        }

        private val _state = MutableStateFlow(PlateaSearchState())
        val state: StateFlow<PlateaSearchState> = _state
        val focusMemory = FocusMemory()
        var initialFocusDone = false
        private var job: Job? = null
        private val failures = java.util.concurrent.CopyOnWriteArrayList<String>()

        fun retry() {
            val query = _state.value.query
            _state.update { it.copy(query = "") }
            setQuery(query)
        }

        init {
            viewModelScope.launch(PlateaDispatchers.IO) {
                val user = serverRepository.currentUserDto ?: return@launch
                runCatching {
                    val libraries =
                        plateaLibraries.get(user.id)
                    _state.update { it.copy(libraries = libraries, userInitial = user.name?.take(1)?.uppercase().orEmpty()) }
                }
            }
        }

        fun setQuery(query: String) {
            if (query == _state.value.query) return
            _state.update { it.copy(query = query) }
            job?.cancel()
            if (query.isBlank()) {
                _state.update { it.copy(results = null, searching = false) }
                return
            }
            job =
                viewModelScope.launch(PlateaDispatchers.IO) {
                    // Wait for a pause in typing
                    delay(350)
                    _state.update { it.copy(searching = true) }
                    val q = query.trim()
                    val movies = async { items(q, BaseItemKind.MOVIE) }
                    val series = async { items(q, BaseItemKind.SERIES) }
                    val episodes = async { items(q, BaseItemKind.EPISODE) }
                    val collections = async { items(q, BaseItemKind.BOX_SET) }
                    val people =
                        async {
                            runCatching {
                                api.personsApi
                                    .getPersons(searchTerm = q, limit = LIMIT, userId = serverRepository.currentUser?.id)
                                    .content.items
                                    .map { BaseItem(it) }
                            }.onFailure {
                                Timber.w(it, "People search")
                                failures += it.localizedMessage ?: it.javaClass.simpleName
                            }.getOrDefault(emptyList())
                        }
                    val results =
                        linkedMapOf(
                            SearchGroup.MOVIES to movies.await(),
                            SearchGroup.SERIES to series.await(),
                            SearchGroup.EPISODES to episodes.await(),
                            SearchGroup.PEOPLE to people.await(),
                            SearchGroup.COLLECTIONS to collections.await(),
                        ).filterValues { it.isNotEmpty() }
                            .mapValues { (_, list) -> list.sortedBy { SearchRelevance.score(it, q) } }
                    val failure = failures.takeIf { it.size == 5 }?.firstOrNull()
                    failures.clear()
                    _state.update { if (it.query == query) it.copy(results = results, searching = false, error = failure) else it }
                }
        }

        private suspend fun items(
            query: String,
            kind: BaseItemKind,
        ): List<BaseItem> =
            runCatching {
                api.itemsApi
                    .getItems(
                        GetItemsRequest(
                            userId = serverRepository.currentUser?.id,
                            searchTerm = query,
                            recursive = true,
                            includeItemTypes = listOf(kind),
                            fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
                            enableUserData = true,
                            limit = LIMIT,
                            enableTotalRecordCount = false,
                        ),
                    ).content.items
                    .map { BaseItem(it) }
            }.onFailure {
                Timber.w(it, "Search %s", kind)
                failures += it.localizedMessage ?: it.javaClass.simpleName
            }.getOrDefault(emptyList())

        fun poster(item: BaseItem) = imageUrlService.getItemImageUrl(item, ImageType.PRIMARY, fillHeight = 330)

        fun still(item: BaseItem) = imageUrlService.getItemImageUrl(item, ImageType.PRIMARY, fillWidth = 480, useSeriesForPrimary = false)

        /** Episodes open their series page; everything else its own page. */
        fun open(item: BaseItem) {
            val seriesId = item.data.seriesId
            if (item.type == BaseItemKind.EPISODE && seriesId != null) {
                navigationManager.navigateTo(Destination.MediaItem(seriesId, BaseItemKind.SERIES))
            } else {
                navigationManager.navigateTo(Destination.MediaItem(item))
            }
        }

        fun navigate(destination: Destination) = navigationManager.navigateToFromDrawer(destination)

        fun home() = navigationManager.goToHome()

        companion object {
            private const val LIMIT = 24
        }
    }
