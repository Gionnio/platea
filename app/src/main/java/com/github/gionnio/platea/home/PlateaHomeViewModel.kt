@file:UseSerializers(UUIDSerializer::class)

package com.github.gionnio.platea.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.services.ImageUrlService
import com.github.gionnio.platea.services.LatestNextUpService
import com.github.gionnio.platea.services.NavigationManager
import androidx.datastore.core.DataStore
import com.github.gionnio.platea.preferences.AppPreferences
import kotlinx.coroutines.flow.first
import com.github.gionnio.platea.ui.SlimItemFields
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.util.PlateaDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import timber.log.Timber
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject

/** A library shown in the top bar (Film, Serie TV, Filmini...). */
@Serializable
data class NavLibrary(
    val name: String,
    val destination: Destination,
)

/** One title of the featured rotation, ready to draw. */
@Serializable
data class FeaturedUi(
    val id: UUID,
    val kicker: String,
    val title: String,
    val logoUrl: String?,
    val backdropUrl: String?,
    val meta: List<String>,
    val highlight: String?,
    val badges: List<String>,
    val overview: String?,
    val primaryLabel: String,
    val primary: Destination?,
    val secondaryLabel: String,
    val secondary: Destination,
    /** Theme song played while this title is featured (series only). */
    val theme: ThemeRef?,
)

enum class RowStyle { LANDSCAPE, POSTER }

@Serializable
data class CardUi(
    val id: UUID,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val progress: Float?,
    val tag: String?,
    val badge: String?,
    val rating: String?,
    val destination: Destination,
    /** Long press menu (continue watching): resume label, start over, details page. */
    val actions: CardActions? = null,
)

@Serializable
data class CardActions(
    /** "Riprendi · 52 min rimasti", null when not started (next episode). */
    val resumeLabel: String?,
    val startOver: Destination,
    val detailsLabel: String,
    val details: Destination,
    /** Episodes: the series page opened on this episode. */
    val episode: Destination? = null,
)

@Serializable
data class RowUi(
    val key: String,
    val title: String,
    val note: String?,
    val style: RowStyle,
    val cards: List<CardUi>,
)

/** What the home shows; also saved on the device as the instant-start snapshot. */
@Serializable
data class HomeSnapshot(
    val userId: UUID,
    val savedAtMillis: Long,
    val libraries: List<NavLibrary>,
    val featured: List<FeaturedUi>,
    val rows: List<RowUi>,
    val userInitial: String,
)

data class PlateaHomeState(
    val loading: Boolean = true,
    /** Showing the saved snapshot while the server answers. */
    val fromCache: Boolean = false,
    val error: String? = null,
    val libraries: List<NavLibrary> = emptyList(),
    val featured: List<FeaturedUi> = emptyList(),
    val rows: List<RowUi> = emptyList(),
    val userInitial: String = "",
)

@HiltViewModel
class PlateaHomeViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
        private val latestNextUpService: LatestNextUpService,
        private val plateaLibraries: com.github.gionnio.platea.PlateaLibraries,
        private val imageUrlService: ImageUrlService,
        private val themeSongs: PlateaThemeSongs,
        private val appPreferences: DataStore<AppPreferences>,
        private val homeCache: HomeCache,
        val navigationManager: NavigationManager,
        val updater: com.github.gionnio.platea.update.PlateaUpdater,
    ) : ViewModel() {
        private val _state = MutableStateFlow(PlateaHomeState())
        val state: StateFlow<PlateaHomeState> = _state

        init {
            // "Continue watching" follows what was just played
            viewModelScope.launch { com.github.gionnio.platea.PlaybackEvents.stopped.drop(1).collect { load() } }
        }

        /** A new release on the NAS: checked from the home only, never during playback. */
        fun checkUpdate() {
            viewModelScope.launch { updater.check() }
        }

        fun installUpdate(state: com.github.gionnio.platea.update.UpdateState.Available) {
            viewModelScope.launch { updater.install(state.release, state.apk) }
        }

        fun load() {
            viewModelScope.launch(PlateaDispatchers.IO) {
                val user = serverRepository.currentUserDto ?: return@launch
                val started = System.currentTimeMillis()
                val useCache = !appPreferences.data.first().interfacePreferences.plateaNoHomeCache
                if (!useCache) {
                    homeCache.clear(user.id)
                } else if (_state.value.rows.isEmpty()) {
                    homeCache.read(user.id)?.let { snap ->
                        _state.update {
                            it.copy(
                                loading = false,
                                fromCache = true,
                                libraries = snap.libraries,
                                featured = snap.featured,
                                rows = snap.rows,
                                userInitial = snap.userInitial,
                            )
                        }
                        Timber.i("Platea home from cache in %d ms", System.currentTimeMillis() - started)
                    }
                }
                try {
                    // Each request on its own: one that fails leaves its row out, not the whole home
                    var failed = 0
                    var lastError: Throwable? = null

                    fun <T> tryAsync(
                        fallback: T,
                        block: suspend () -> T,
                    ) = async {
                        runCatching { block() }
                            .onFailure {
                                Timber.w(it, "Platea home request")
                                failed++
                                lastError = it
                            }.getOrDefault(fallback)
                    }
                    val libraries = tryAsync(_state.value.libraries) { plateaLibraries.get(user.id, refresh = true) }
                    val resume = tryAsync(emptyList()) { latestNextUpService.getResume(user.id, limit = 12, includeEpisodes = true) }
                    val nextUp =
                        tryAsync(emptyList()) {
                            latestNextUpService.getNextUp(
                                user.id,
                                limit = 12,
                                enableRewatching = false,
                                enableResumable = false,
                                maxDays = 0,
                            )
                        }
                    val latestMovies = tryAsync(emptyList()) { latest(BaseItemKind.MOVIE, group = false) }
                    val latestSeries = tryAsync(emptyList()) { latest(BaseItemKind.EPISODE, group = true) }
                    val topRated = tryAsync(emptyList()) { topRatedUnseen(user.id) }
                    val sagas = tryAsync(emptyList()) { collections(user.id) }

                    val continueItems = latestNextUpService.buildCombined(resume.await(), nextUp.await())
                    val movies = latestMovies.await()
                    val series = latestSeries.await()
                    val top = topRated.await()
                    sagas.await()
                    libraries.await()
                    // Nothing answered: keep what is on screen (the saved home) and say so
                    if (failed == 7) throw lastError ?: IllegalStateException("No answer from the server")

                    val featured = featured(user.id, nextUp.await(), resume.await(), movies, series, top)
                    val rows =
                        listOfNotNull(
                            row("continue", R.string.platea_row_continue, null, RowStyle.LANDSCAPE, continueItems.map(::continueCard)),
                            row("movies", R.string.platea_row_recent_movies, null, RowStyle.POSTER, movies.map(::posterCard)),
                            row("series", R.string.platea_row_recent_series, null, RowStyle.POSTER, series.map(::seriesCard)),
                            row("top", R.string.platea_row_top_rated, null, RowStyle.POSTER, top.map(::ratedCard)),
                            row("sagas", R.string.platea_row_sagas, null, RowStyle.LANDSCAPE, sagas.await().map(::sagaCard)),
                        )
                    _state.update {
                        it.copy(
                            loading = false,
                            fromCache = false,
                            error = null,
                            libraries = libraries.await(),
                            featured = featured,
                            rows = rows,
                            userInitial = user.name?.take(1)?.uppercase().orEmpty(),
                        )
                    }
                    Timber.i("Platea home loaded in %d ms", System.currentTimeMillis() - started)
                    if (useCache) {
                        val now = _state.value
                        homeCache.write(HomeSnapshot(user.id, System.currentTimeMillis(), now.libraries, now.featured, now.rows, now.userInitial))
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Platea home")
                    _state.update { it.copy(loading = false, error = ex.localizedMessage ?: ex.toString()) }
                }
            }
        }

        fun navigate(destination: Destination) {
            // Playback starts: the theme must not play over it
            if (destination is Destination.Playback || destination is Destination.PlaybackList) themeSongs.stopNow()
            navigationManager.navigateTo(destination)
        }

        /** Element that opened another screen: focus returns there on back. */
        val focusMemory = com.github.gionnio.platea.ui.FocusMemory()

        fun playTheme(theme: ThemeRef?) = themeSongs.play(theme)

        val themePlaying = themeSongs.playing

        fun stopTheme() = themeSongs.stop()

        // --- Data ---

        private suspend fun latest(
            kind: BaseItemKind,
            group: Boolean,
        ): List<BaseItem> =
            api.userLibraryApi
                .getLatestMedia(
                    GetLatestMediaRequest(
                        fields = SlimItemFields + listOf(ItemFields.DATE_CREATED, ItemFields.MEDIA_STREAMS, ItemFields.CHILD_COUNT),
                        includeItemTypes = listOf(kind),
                        groupItems = group,
                        limit = 16,
                    ),
                ).content
                .map { BaseItem(it) }

        private suspend fun topRatedUnseen(userId: UUID): List<BaseItem> =
            api.itemsApi
                .getItems(
                    GetItemsRequest(
                        userId = userId,
                        includeItemTypes = listOf(BaseItemKind.MOVIE),
                        recursive = true,
                        isPlayed = false,
                        sortBy = listOf(ItemSortBy.COMMUNITY_RATING),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        limit = 16,
                        fields = SlimItemFields,
                        enableTotalRecordCount = false,
                    ),
                ).content.items
                .map { BaseItem(it) }

        private suspend fun collections(userId: UUID): List<BaseItem> =
            api.itemsApi
                .getItems(
                    GetItemsRequest(
                        userId = userId,
                        includeItemTypes = listOf(BaseItemKind.BOX_SET),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.DATE_LAST_CONTENT_ADDED),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        limit = 16,
                        fields = SlimItemFields + ItemFields.CHILD_COUNT,
                        enableTotalRecordCount = false,
                    ),
                ).content.items
                .filter { (it.childCount ?: 0) > 1 }
                .map { BaseItem(it) }

        // --- Featured ---

        private suspend fun featured(
            userId: UUID,
            nextUp: List<BaseItem>,
            resume: List<BaseItem>,
            movies: List<BaseItem>,
            series: List<BaseItem>,
            top: List<BaseItem>,
        ): List<FeaturedUi> {
            val started = resume.map { it.id }.toSet()
            // First episode of each new series: tells whether it is 4K and what "Inizia" plays
            val firstEpisodes =
                series
                    .take(8)
                    .map { s -> viewModelScope.async(PlateaDispatchers.IO) { s.id to firstEpisode(userId, s.id) } }
                    .awaitAll()
                    .toMap()

            fun candidate(
                item: BaseItem,
                kind: FeaturedCandidate.Kind,
                video: BaseItemDto? = item.data,
            ) = FeaturedCandidate(
                id = item.id,
                groupId = item.data.seriesId ?: item.id,
                kind = kind,
                dateAdded = item.data.dateCreated?.toInstant(ZoneOffset.UTC),
                is4k = (videoWidth(video) ?: 0) >= 3000,
                played = item.played,
                inProgress = item.id in started || item.resumeMs > 0,
            )

            val picked =
                FeaturedRule.pick(
                    nextUp = nextUp.map { candidate(it, FeaturedCandidate.Kind.NEXT_EPISODE) },
                    novelties =
                        movies.map { candidate(it, FeaturedCandidate.Kind.MOVIE) } +
                            series.map { candidate(it, FeaturedCandidate.Kind.SERIES, firstEpisodes[it.id]) },
                    topRatedUnseen = top.map { candidate(it, FeaturedCandidate.Kind.MOVIE) },
                )
            val all = (nextUp + movies + series + top).associateBy { it.id }
            return picked
                .map { c -> viewModelScope.async(PlateaDispatchers.IO) { featuredUi(c, all.getValue(c.id), firstEpisodes[c.id], isTop = movies.none { it.id == c.id } && series.none { it.id == c.id }) } }
                .awaitAll()
                .filterNotNull()
        }

        /** Jellyfin's theme song, if the server has one. */
        private suspend fun themeFor(
            seriesId: UUID,
            @Suppress("UNUSED_PARAMETER") series: BaseItemDto?,
        ): ThemeRef? = themeSongs.resolve(seriesId)

        private suspend fun firstEpisode(
            userId: UUID,
            seriesId: UUID,
        ): BaseItemDto? =
            runCatching {
                api.tvShowsApi
                    .getEpisodes(seriesId, userId = userId, limit = 1, fields = listOf(ItemFields.MEDIA_STREAMS))
                    .content.items
                    .firstOrNull()
            }.getOrNull()

        private suspend fun featuredUi(
            c: FeaturedCandidate,
            item: BaseItem,
            firstEpisode: BaseItemDto?,
            isTop: Boolean,
        ): FeaturedUi? =
            runCatching {
                // Full item: genres, rating, seasons, streams
                val full = api.userLibraryApi.getItem(itemId = item.id).content
                val showId = full.seriesId ?: if (full.type == BaseItemKind.SERIES) full.id else null
                val show = showId?.let { api.userLibraryApi.getItem(itemId = it).content }
                val streamsOf = if (c.kind == FeaturedCandidate.Kind.SERIES) firstEpisode else full
                val theme = showId?.let { themeFor(it, show) }
                val genre = (show ?: full).genres?.firstOrNull()
                val rating = (show ?: full).officialRating?.substringAfter('-')
                when (c.kind) {
                    FeaturedCandidate.Kind.NEXT_EPISODE -> {
                        val se = "S${full.parentIndexNumber}:E${full.indexNumber}"
                        FeaturedUi(
                            id = full.id,
                            kicker = context.getString(R.string.platea_featured_next_episode),
                            title = full.seriesName ?: full.name.orEmpty(),
                            logoUrl = logo(showId),
                            backdropUrl = backdrop(showId ?: full.id),
                            highlight = "$se · ${full.name}",
                            meta = listOfNotNull(minutes(full.runTimeTicks), genre),
                            badges = badges(full),
                            overview = full.overview ?: show?.overview,
                            primaryLabel = context.getString(R.string.platea_play_episode, se),
                            primary = Destination.Playback(full.id, 0),
                            secondaryLabel = context.getString(R.string.platea_series),
                            secondary = Destination.MediaItem(showId ?: full.id, BaseItemKind.SERIES),
                            theme = theme,
                        )
                    }

                    FeaturedCandidate.Kind.SERIES -> {
                        FeaturedUi(
                            id = full.id,
                            kicker = context.getString(R.string.platea_featured_new_series, item.data.childCount ?: 0),
                            title = full.name.orEmpty(),
                            logoUrl = logo(full.id),
                            backdropUrl = backdrop(full.id),
                            highlight = null,
                            meta = listOfNotNull(years(full), full.childCount?.let { context.resources.getQuantityString(R.plurals.platea_seasons, it, it) }, genre, rating),
                            badges = badges(streamsOf),
                            overview = full.overview,
                            primaryLabel = context.getString(R.string.platea_start),
                            primary = firstEpisode?.let { Destination.Playback(it.id, 0) },
                            secondaryLabel = context.getString(R.string.platea_details),
                            secondary = Destination.MediaItem(full.id, BaseItemKind.SERIES),
                            theme = theme,
                        )
                    }

                    FeaturedCandidate.Kind.MOVIE -> {
                        FeaturedUi(
                            id = full.id,
                            kicker = context.getString(if (isTop) R.string.platea_featured_top else R.string.platea_featured_new_movie),
                            title = full.name.orEmpty(),
                            logoUrl = logo(full.id),
                            backdropUrl = backdrop(full.id),
                            highlight = null,
                            meta = listOfNotNull(full.productionYear?.toString(), minutes(full.runTimeTicks), genre, rating),
                            badges = badges(full),
                            overview = full.overview,
                            primaryLabel = context.getString(R.string.platea_play),
                            primary = Destination.Playback(full.id, 0),
                            secondaryLabel = context.getString(R.string.platea_details),
                            secondary = Destination.MediaItem(full.id, BaseItemKind.MOVIE),
                            theme = null,
                        )
                    }
                }
            }.onFailure { Timber.w(it, "Featured %s", item.id) }.getOrNull()

        // --- Cards ---

        private fun continueCard(item: BaseItem): CardUi {
            val episode = item.type == BaseItemKind.EPISODE
            val image =
                if (episode) {
                    imageUrlService.getItemImageUrl(item, ImageType.PRIMARY, fillWidth = 640, useSeriesForPrimary = false)
                } else {
                    imageUrlService.getItemImageUrl(item, if (item.data.imageTags?.containsKey(ImageType.THUMB) == true) ImageType.THUMB else ImageType.BACKDROP, fillWidth = 640)
                }
            val runtime = item.data.runTimeTicks ?: 0
            val position = item.data.userData?.playbackPositionTicks ?: 0
            val left = (runtime - position).coerceAtLeast(0)
            val se = if (episode) "S${item.data.parentIndexNumber}:E${item.data.indexNumber}" else null
            val isNext = episode && position == 0L
            val sub =
                when {
                    isNext -> listOfNotNull(se, minutes(runtime)).joinToString(" · ")
                    else -> listOfNotNull(se, context.getString(R.string.platea_time_left, duration(left))).joinToString(" · ")
                }
            return CardUi(
                id = item.id,
                title = item.title.orEmpty(),
                subtitle = sub,
                imageUrl = image,
                progress = if (runtime > 0 && position > 0) position.toFloat() / runtime else null,
                tag = if (isNext) context.getString(R.string.platea_tag_next) else null,
                badge = null,
                rating = null,
                destination = Destination.Playback(item.id, item.resumeMs),
                actions =
                    CardActions(
                        resumeLabel = if (position > 0) context.getString(R.string.platea_resume_left, duration(left)) else null,
                        startOver = Destination.Playback(item.id, 0),
                        detailsLabel = context.getString(if (episode) R.string.platea_go_to_series else R.string.platea_go_to_details),
                        details =
                            if (episode) {
                                Destination.MediaItem(item.data.seriesId ?: item.id, if (item.data.seriesId != null) BaseItemKind.SERIES else BaseItemKind.EPISODE)
                            } else {
                                Destination.MediaItem(item.id, item.type)
                            },
                        episode =
                            item.data.seriesId?.takeIf { episode }?.let { seriesId ->
                                Destination.MediaItem(seriesId, BaseItemKind.SERIES, initialEpisodeId = item.id)
                            },
                    ),
            )
        }

        private fun posterCard(item: BaseItem) =
            CardUi(
                id = item.id,
                title = item.name.orEmpty(),
                subtitle = null,
                imageUrl = imageUrlService.getItemImageUrl(item, ImageType.PRIMARY, fillHeight = 495),
                progress = null,
                tag = null,
                badge = null,
                rating = null,
                destination = Destination.MediaItem(item.id, item.type),
            )

        private fun seriesCard(item: BaseItem) =
            posterCard(item).copy(
                badge = item.data.childCount?.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.platea_new_episodes, it, it) },
            )

        private fun ratedCard(item: BaseItem) =
            posterCard(item).copy(rating = item.data.communityRating?.let { String.format(java.util.Locale.ITALY, "%.1f", it) })

        private fun sagaCard(item: BaseItem) =
            CardUi(
                id = item.id,
                title = item.name.orEmpty().removeSuffix(" - Collezione").removeSuffix(" - Collection"),
                subtitle = item.data.childCount?.let { context.resources.getQuantityString(R.plurals.platea_movies, it, it) },
                imageUrl = imageUrlService.getItemImageUrl(item, if (item.data.imageTags?.containsKey(ImageType.THUMB) == true) ImageType.THUMB else ImageType.BACKDROP, fillWidth = 640),
                progress = null,
                tag = null,
                badge = null,
                rating = null,
                destination = Destination.MediaItem(item.id, item.type),
            )

        private fun row(
            key: String,
            title: Int,
            note: String?,
            style: RowStyle,
            cards: List<CardUi>,
        ) = cards.takeIf { it.isNotEmpty() }?.let { RowUi(key, context.getString(title), note, style, it) }

        // --- Formatting ---

        private fun logo(id: UUID?) = id?.let { imageUrlService.getItemImageUrl(itemId = it, imageType = ImageType.LOGO, maxHeight = 260) }

        private fun backdrop(id: UUID) = imageUrlService.getItemImageUrl(itemId = id, imageType = ImageType.BACKDROP, maxWidth = 1920)

        private fun minutes(ticks: Long?) = ticks?.takeIf { it > 0 }?.let { duration(it) }

        private fun duration(ticks: Long): String {
            val totalMin = (ticks / 600_000_000L).toInt()
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "$h h $m min" else "$m min"
        }

        private fun years(item: BaseItemDto): String? {
            val start = item.productionYear ?: return null
            val end = item.endDate?.year
            return if (end != null && end != start) "$start–$end" else start.toString()
        }

        private fun videoWidth(item: BaseItemDto?) = item?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }?.width

        private fun badges(item: BaseItemDto?) = com.github.gionnio.platea.details.MediaBadges.of(context, item)
    }
