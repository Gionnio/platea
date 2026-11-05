package com.github.gionnio.platea.collection

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.services.WatchedService
import com.github.gionnio.platea.services.ImageUrlService
import com.github.gionnio.platea.services.NavigationManager
import com.github.gionnio.platea.ui.data.SortAndDirection
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.ui.FocusMemory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID

data class PlateaCollectionState(
    val collection: BaseItem? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val meta: List<String> = emptyList(),
    /** In release order. */
    val items: List<BaseItem> = emptyList(),
    /** Title the main button plays: the one in progress, else the first not watched, else the first. */
    val next: BaseItem? = null,
)

/**
 * A collection / saga: its titles in release order and playback as a queue, so the player's previous and
 * next buttons move through the saga. Written for Platea (Wholphin's CollectionViewModel reviewed 10/10/2026:
 * filters, view options and paging not needed here).
 */
@HiltViewModel(assistedFactory = PlateaCollectionViewModel.Factory::class)
class PlateaCollectionViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val imageUrlService: ImageUrlService,
        private val watchedService: WatchedService,
        private val navigationManager: NavigationManager,
        @Assisted val collectionId: UUID,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(collectionId: UUID): PlateaCollectionViewModel
        }

        private val _state = MutableStateFlow(PlateaCollectionState())
        val state: StateFlow<PlateaCollectionState> = _state
        val focusMemory = FocusMemory()

        init {
            load()
            // Reload when the server has recorded where playback stopped
            viewModelScope.launch { com.github.gionnio.platea.PlaybackEvents.stopped.drop(1).collect { load() } }
        }

        fun load() {
            viewModelScope.launch(PlateaDispatchers.IO) {
                runCatching {
                    val collection = async { BaseItem(api.userLibraryApi.getItem(collectionId).content) }
                    val items = items()
                    val c = collection.await()
                    val next = items.firstOrNull { it.resumeMs > 0 && !it.played } ?: items.firstOrNull { !it.played } ?: items.firstOrNull()
                    _state.update { it.copy(collection = c, items = items, next = next, meta = meta(items), loading = false, error = null) }
                }.onFailure { e ->
                    Timber.w(e, "Collection %s", collectionId)
                    _state.update { it.copy(loading = false, error = if (it.collection == null) e.message ?: e.javaClass.simpleName else null) }
                }
            }
        }

        // Same parent, sort and order as the player's queue (PlaylistCreator), so list index = queue index
        private suspend fun items(): List<BaseItem> =
            api.itemsApi
                .getItems(
                    GetItemsRequest(
                        userId = serverRepository.currentUser?.id,
                        parentId = collectionId,
                        sortBy = listOf(QUEUE_ORDER.sort),
                        sortOrder = listOf(QUEUE_ORDER.direction),
                        fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
                        enableUserData = true,
                    ),
                ).content.items
                .map { BaseItem(it) }

        private fun meta(items: List<BaseItem>): List<String> {
            val years = items.mapNotNull { it.data.productionYear }
            val movies = items.count { it.type == BaseItemKind.MOVIE }
            val series = items.count { it.type == BaseItemKind.SERIES }
            return listOfNotNull(
                movies.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.platea_movies, it, it) },
                series.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.platea_series_count, it, it) },
                years.takeIf { it.isNotEmpty() }?.let {
                    if (it.min() == it.max()) it.min().toString() else context.getString(R.string.platea_collection_years, it.min().toString(), it.max().toString())
                },
            )
        }

        /** Plays a title; when the collection holds only movies, as a queue starting from it. */
        fun play(item: BaseItem) {
            val items = _state.value.items
            val index = items.indexOfFirst { it.id == item.id }
            if (index >= 0 && items.all { it.type == BaseItemKind.MOVIE }) {
                navigationManager.navigateTo(Destination.PlaybackList(itemId = collectionId, startIndex = index, sortAndDirection = QUEUE_ORDER))
            } else if (item.type == BaseItemKind.MOVIE) {
                navigationManager.navigateTo(Destination.Playback(item.id, if (item.played) 0 else item.resumeMs))
            } else {
                open(item)
            }
        }

        fun open(item: BaseItem) = navigationManager.navigateTo(Destination.MediaItem(item.id, item.type))

        fun setWatched(
            item: BaseItem,
            played: Boolean,
        ) = viewModelScope.launch(PlateaDispatchers.IO) {
            runCatching { watchedService.setWatched(item.id, played) }.onFailure { Timber.w(it) }
            load()
        }

        /** The collection's own backdrop, or the one of the title to watch next when it has none. */
        fun backdrop(collection: BaseItem): String? {
            val source = if (collection.data.backdropImageTags.isNullOrEmpty()) _state.value.next ?: collection else collection
            return imageUrlService.getOwnImageUrl(source, ImageType.BACKDROP, maxWidth = 1920)
        }

        fun poster(item: BaseItem) = imageUrlService.getItemImageUrl(item, ImageType.PRIMARY, fillHeight = 330)

        companion object {
            private val QUEUE_ORDER = SortAndDirection(ItemSortBy.PREMIERE_DATE, SortOrder.ASCENDING)
        }
    }
