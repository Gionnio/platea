package com.github.gionnio.platea.library

import android.content.Context
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.services.WatchedService
import com.github.gionnio.platea.services.ImageUrlService
import com.github.gionnio.platea.services.NavigationManager
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.home.NavLibrary
import com.github.gionnio.platea.ui.FocusMemory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID

enum class LibraryFilter(
    val label: Int,
) {
    ALL(R.string.platea_filter_all),
    UNWATCHED(R.string.platea_filter_unwatched),
    IN_PROGRESS(R.string.platea_filter_in_progress),

    /** Movie library only: the sagas instead of the single movies. */
    COLLECTIONS(R.string.platea_filter_collections),
}

enum class LibrarySort(
    val label: Int,
    val by: ItemSortBy,
    val order: SortOrder,
) {
    ADDED(R.string.platea_sort_added, ItemSortBy.DATE_CREATED, SortOrder.DESCENDING),
    TITLE(R.string.platea_sort_title, ItemSortBy.SORT_NAME, SortOrder.ASCENDING),
    YEAR(R.string.platea_sort_year, ItemSortBy.PRODUCTION_YEAR, SortOrder.DESCENDING),
    RATING(R.string.platea_sort_rating, ItemSortBy.COMMUNITY_RATING, SortOrder.DESCENDING),
}

data class PlateaLibraryState(
    val title: String = "",
    val libraries: List<NavLibrary> = emptyList(),
    val userInitial: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.ADDED,
    val genre: String? = null,
    val genres: List<String> = emptyList(),
    val items: List<BaseItem> = emptyList(),
    val total: Int? = null,
    val loading: Boolean = true,
    /** The server did not answer and there is nothing to show. */
    val error: String? = null,
)

/** A movie or TV library: filters, sort, genre and the poster grid, loaded a page at a time. */
@HiltViewModel(assistedFactory = PlateaLibraryViewModel.Factory::class)
class PlateaLibraryViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val plateaLibraries: com.github.gionnio.platea.PlateaLibraries,
        private val imageUrlService: ImageUrlService,
        private val watchedService: WatchedService,
        val navigationManager: NavigationManager,
        @Assisted val libraryId: UUID,
        @Assisted val collectionType: CollectionType?,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(
                libraryId: UUID,
                collectionType: CollectionType?,
            ): PlateaLibraryViewModel
        }

        private val _state = MutableStateFlow(PlateaLibraryState())
        val state: StateFlow<PlateaLibraryState> = _state
        val focusMemory = FocusMemory()

        /**
         * Grid scroll position kept here, not in the composition: coming back from a details page the grid must
         * be where it was, so the poster that was opened is laid out and gets the focus back.
         */
        val gridState = LazyGridState()

        /** The first poster takes the focus once, when the page opens (not after a filter change). */
        var initialFocusDone = false

        val isSeries = collectionType == CollectionType.TVSHOWS

        /** Collections library: every saga, as posters. */
        val isCollections = collectionType == CollectionType.BOXSETS

        /** Folder library (home videos, test clips): all its videos in one grid, no filters. */
        val isFolders = !isSeries && !isCollections && collectionType != CollectionType.MOVIES
        private val kind =
            when {
                isSeries -> BaseItemKind.SERIES
                isCollections -> BaseItemKind.BOX_SET
                else -> BaseItemKind.MOVIE
            }
        private var pageJob: Job? = null

        init {
            viewModelScope.launch(PlateaDispatchers.IO) {
                val user = serverRepository.currentUserDto ?: return@launch
                runCatching {
                    val title = api.userLibraryApi.getItem(libraryId).content.name.orEmpty()
                    val libraries =
                        plateaLibraries.get(user.id)
                    _state.update { it.copy(title = title, libraries = libraries, userInitial = user.name?.take(1)?.uppercase().orEmpty()) }
                }.onFailure { Timber.w(it, "Library header") }
                if (!isFolders && !isCollections) runCatching {
                    val genres =
                        api.genresApi
                            .getGenres(parentId = libraryId, userId = user.id, includeItemTypes = listOf(kind))
                            .content.items
                            .mapNotNull { it.name }
                    _state.update { it.copy(genres = genres) }
                }.onFailure { Timber.w(it, "Genres") }
            }
            reload()
            // Reload when the server has recorded where playback stopped
            viewModelScope.launch { com.github.gionnio.platea.PlaybackEvents.stopped.drop(1).collect { refreshVisible() } }
        }

        fun setFilter(filter: LibraryFilter) {
            if (filter == _state.value.filter) return
            _state.update { it.copy(filter = filter) }
            reload()
        }

        fun nextSort() {
            val all = LibrarySort.entries
            _state.update { it.copy(sort = all[(all.indexOf(it.sort) + 1) % all.size]) }
            reload()
        }

        fun setGenre(genre: String?) {
            if (genre == _state.value.genre) return
            _state.update { it.copy(genre = genre) }
            reload()
        }

        /** Called while the grid scrolls: fetches the next page when the end is near. */
        fun onVisible(lastIndex: Int) {
            val s = _state.value
            val total = s.total ?: return
            if (!s.loading && s.items.size < total && lastIndex >= s.items.size - PAGE / 3) loadPage(s.items.size)
        }

        /** Back from a details page or the player: watched marks and progress may have changed. */
        fun refreshVisible() {
            val count = _state.value.items.size.coerceAtLeast(PAGE)
            pageJob?.cancel()
            pageJob =
                viewModelScope.launch(PlateaDispatchers.IO) {
                    runCatching { fetch(0, count) }
                        .onSuccess { (items, total) -> _state.update { it.copy(items = items, total = total) } }
                }
        }

        fun retry() = reload()

        private fun reload() {
            _state.update { it.copy(items = emptyList(), total = null) }
            viewModelScope.launch { gridState.scrollToItem(0) }
            loadPage(0)
        }

        private fun loadPage(start: Int) {
            pageJob?.cancel()
            _state.update { it.copy(loading = true) }
            pageJob =
                viewModelScope.launch(PlateaDispatchers.IO) {
                    runCatching { fetch(start, PAGE) }
                        .onSuccess { (items, total) ->
                            _state.update { it.copy(items = (if (start == 0) items else it.items + items).distinctBy { item -> item.id }, total = total, loading = false, error = null) }
                        }.onFailure { e ->
                            Timber.w(e, "Library page %d", start)
                            _state.update { s -> s.copy(loading = false, error = if (s.items.isEmpty()) e.localizedMessage ?: e.javaClass.simpleName else null) }
                        }
                }
        }

        private suspend fun fetch(
            start: Int,
            limit: Int,
        ): Pair<List<BaseItem>, Int> {
            val s = _state.value
            val collectionsView = s.filter == LibraryFilter.COLLECTIONS
            val response =
                api.itemsApi
                    .getItems(
                        GetItemsRequest(
                            userId = serverRepository.currentUser?.id,
                            // Collections live in their own library on the server: no parent
                            parentId = if (collectionsView) null else libraryId,
                            // Folder libraries: every video inside, whatever folder it is in (one click to play)
                            includeItemTypes =
                                when {
                                    isFolders -> listOf(BaseItemKind.VIDEO, BaseItemKind.MOVIE)
                                    collectionsView -> listOf(BaseItemKind.BOX_SET)
                                    else -> listOf(kind)
                                },
                            recursive = true,
                            sortBy = if (isFolders) listOf(ItemSortBy.SORT_NAME) else listOf(s.sort.by, ItemSortBy.SORT_NAME),
                            sortOrder = if (isFolders) listOf(SortOrder.ASCENDING) else listOf(s.sort.order, SortOrder.ASCENDING),
                            filters =
                                when (s.filter) {
                                    LibraryFilter.ALL -> null
                                    LibraryFilter.UNWATCHED -> listOf(ItemFilter.IS_UNPLAYED)
                                    LibraryFilter.IN_PROGRESS -> listOf(ItemFilter.IS_RESUMABLE)
                                    LibraryFilter.COLLECTIONS -> null
                                },
                            genres = s.genre?.takeIf { !collectionsView }?.let { listOf(it) },
                            fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
                            enableUserData = true,
                            startIndex = start,
                            limit = limit,
                            enableTotalRecordCount = true,
                        ),
                    ).content
            return response.items.map { BaseItem(it) } to response.totalRecordCount
        }

        fun poster(item: BaseItem) = imageUrlService.getItemImageUrl(item, ImageType.PRIMARY, fillHeight = 330)

        /** A page for titles and folders; a loose video (test clip, home video) plays straight away. */
        fun open(item: BaseItem) =
            when {
                item.data.isFolder == true && isFolders -> navigationManager.navigateTo(Destination.MediaItem(item.id, BaseItemKind.FOLDER, collectionType))
                isFolders -> navigationManager.navigateTo(Destination.Playback(item.id, if (item.played) 0 else item.resumeMs))
                else -> navigationManager.navigateTo(Destination.MediaItem(item.id, item.type))
            }

        fun still(item: BaseItem) = imageUrlService.getItemImageUrl(item, ImageType.PRIMARY, fillWidth = 480)

        fun play(
            item: BaseItem,
            fromStart: Boolean,
        ) = navigationManager.navigateTo(Destination.Playback(item.id, if (fromStart) 0 else item.resumeMs))

        fun navigate(destination: Destination) = navigationManager.navigateToFromDrawer(destination)

        fun home() = navigationManager.goToHome()

        fun setWatched(
            item: BaseItem,
            played: Boolean,
        ) = viewModelScope.launch(PlateaDispatchers.IO) {
            runCatching { watchedService.setWatched(item.id, played) }.onFailure { Timber.w(it) }
            refreshVisible()
        }

        fun countLabel(total: Int): String =
            if (isSeries) {
                context.resources.getQuantityString(R.plurals.platea_series_count, total, total)
            } else if (isCollections || _state.value.filter == LibraryFilter.COLLECTIONS) {
                context.resources.getQuantityString(R.plurals.platea_collections_count, total, total)
            } else if (isFolders) {
                context.resources.getQuantityString(R.plurals.platea_items_count, total, total)
            } else {
                context.resources.getQuantityString(R.plurals.platea_movies, total, total)
            }

        companion object {
            private const val PAGE = 120
        }
    }
