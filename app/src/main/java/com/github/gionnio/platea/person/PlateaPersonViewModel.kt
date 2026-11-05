package com.github.gionnio.platea.person

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.services.ImageUrlService
import com.github.gionnio.platea.services.NavigationManager
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
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

data class PlateaPersonState(
    val person: BaseItem? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** "Nascita: 4 gennaio 1970, Londra", "54 anni"… */
    val facts: List<String> = emptyList(),
    val movies: List<BaseItem> = emptyList(),
    val series: List<BaseItem> = emptyList(),
)

/**
 * A person and their titles in the library. Written for Platea (Wholphin's PersonViewModel reviewed 10/10/2026:
 * tied to Seerr and to paged rows, not reused).
 */
@HiltViewModel(assistedFactory = PlateaPersonViewModel.Factory::class)
class PlateaPersonViewModel
    @AssistedInject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val imageUrlService: ImageUrlService,
        private val navigationManager: NavigationManager,
        @Assisted val personId: UUID,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(personId: UUID): PlateaPersonViewModel
        }

        private val _state = MutableStateFlow(PlateaPersonState())
        val state: StateFlow<PlateaPersonState> = _state
        val focusMemory = FocusMemory()

        init {
            load()
            // Reload when the server has recorded where playback stopped
            viewModelScope.launch { com.github.gionnio.platea.PlaybackEvents.stopped.drop(1).collect { load() } }
        }

        /** Also on return from a title: watched marks may have changed. */
        fun load() {
            viewModelScope.launch(PlateaDispatchers.IO) {
                runCatching {
                    val person = async { BaseItem(api.userLibraryApi.getItem(personId).content) }
                    val movies = async { titles(BaseItemKind.MOVIE) }
                    val series = async { titles(BaseItemKind.SERIES) }
                    val p = person.await()
                    _state.update { it.copy(person = p, facts = facts(p), movies = movies.await(), series = series.await(), loading = false, error = null) }
                }.onFailure { e ->
                    Timber.w(e, "Person %s", personId)
                    _state.update { it.copy(loading = false, error = if (it.person == null) e.message ?: e.javaClass.simpleName else null) }
                }
            }
        }

        private suspend fun titles(kind: BaseItemKind): List<BaseItem> =
            api.itemsApi
                .getItems(
                    GetItemsRequest(
                        userId = serverRepository.currentUser?.id,
                        personIds = listOf(personId),
                        includeItemTypes = listOf(kind),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
                        sortOrder = listOf(SortOrder.DESCENDING, SortOrder.DESCENDING, SortOrder.ASCENDING),
                        fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
                        enableUserData = true,
                        limit = 100,
                        enableTotalRecordCount = false,
                    ),
                ).content.items
                .map { BaseItem(it) }

        private fun facts(person: BaseItem): List<String> {
            val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            val born = person.data.premiereDate?.toLocalDate()
            val died = person.data.endDate?.toLocalDate()
            val place = person.data.productionLocations?.firstOrNull()?.takeIf { it.isNotBlank() }
            return buildList {
                born?.let {
                    add(
                        if (place != null) {
                            context.getString(R.string.platea_person_born_in, it.format(format), place)
                        } else {
                            context.getString(R.string.platea_person_born, it.format(format))
                        },
                    )
                }
                died?.let { add(context.getString(R.string.platea_person_died, it.format(format))) }
                born?.let { add(context.getString(R.string.platea_person_age, Period.between(it, died ?: LocalDate.now()).years)) }
            }
        }

        fun photo(person: BaseItem) = imageUrlService.getOwnImageUrl(person, ImageType.PRIMARY, maxHeight = 500)

        fun poster(item: BaseItem) = imageUrlService.getItemImageUrl(item, ImageType.PRIMARY, fillHeight = 330)

        fun open(item: BaseItem) = navigationManager.navigateTo(Destination.MediaItem(item.id, item.type))    }
