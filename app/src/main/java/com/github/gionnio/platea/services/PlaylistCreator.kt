package com.github.gionnio.platea.services

import android.content.Context
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.data.model.GetItemsFilter
import com.github.gionnio.platea.data.model.Playlist
import com.github.gionnio.platea.data.model.PlaylistItem
import com.github.gionnio.platea.ui.DetailItemFields
import com.github.gionnio.platea.ui.data.SortAndDirection
import com.github.gionnio.platea.ui.gt
import com.github.gionnio.platea.ui.indexOfFirstOrNull
import com.github.gionnio.platea.ui.playback.playable
import com.github.gionnio.platea.util.GetEpisodesRequestHandler
import com.github.gionnio.platea.util.GetItemsRequestHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the playback queue (not a Jellyfin server playlist)
 *
 * Used to create a queue of episodes or from Play All button
 */
@Singleton
class PlaylistCreator
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
    ) {
        /**
         * Creates a playlist of next up episodes for the given series starting with the given episode
         */
        private suspend fun createFromEpisode(
            seriesId: UUID,
            episodeId: UUID?,
            seasonId: UUID? = null,
            shuffled: Boolean = false,
        ): Playlist {
            val request =
                GetEpisodesRequest(
                    seriesId = seriesId,
                    seasonId = seasonId,
                    fields = DetailItemFields,
                    startItemId = episodeId,
                    sortBy = if (shuffled) ItemSortBy.RANDOM else null,
                    limit = Playlist.MAX_SIZE,
                )
            val episodes =
                GetEpisodesRequestHandler
                    .execute(api, request)
                    .content.items
                    .convertAndAddParts(false)
            val startIndex =
                episodeId?.let { episodes.indexOfFirstOrNull { it.id == episodeId } } ?: 0
            return Playlist(episodes.subList(startIndex, episodes.size))
        }

        private suspend fun createFromCollection(
            item: BaseItemDto,
            startIndex: Int = 0,
            sortAndDirection: SortAndDirection?,
            filter: GetItemsFilter,
        ): Playlist {
            val includeItemTypes =
                item.collectionType
                    ?.baseItemKinds
                    ?.filter { it.playable }
                    ?.takeIf { it.isNotEmpty() }
                    ?: BaseItemKind.entries.filter { it.playable }
            val request =
                filter.applyTo(
                    overwriteIncludeTypes = false,
                    req =
                        GetItemsRequest(
                            parentId = item.id,
                            enableImageTypes =
                                listOf(
                                    ImageType.PRIMARY,
                                    ImageType.THUMB,
                                    ImageType.LOGO,
                                ),
                            includeItemTypes = includeItemTypes,
                            recursive = true,
                            excludeItemIds = listOf(item.id),
                            sortBy = sortAndDirection?.let { listOf(sortAndDirection.sort) },
                            sortOrder = sortAndDirection?.let { listOf(sortAndDirection.direction) },
                            fields = DetailItemFields,
                            startIndex = startIndex,
                            limit = Playlist.MAX_SIZE,
                            excludeLocationTypes = listOf(LocationType.VIRTUAL),
                        ),
                )
            val items =
                GetItemsRequestHandler
                    .execute(api, request)
                    .content.items
                    .convertAndAddParts()
            return Playlist(items)
        }

        /**
         * Create a [Playlist] contextually based on the given item.
         *
         * For example, an episode creates a queue of next up episodes in the series, or a movie creates one for its parts if needed
         */
        suspend fun createFrom(
            item: BaseItemDto,
            startIndex: Int = 0,
            sortAndDirection: SortAndDirection? = SortAndDirection.DEFAULT,
            shuffled: Boolean = false,
            recursive: Boolean = false,
            filter: GetItemsFilter = GetItemsFilter(),
        ): PlaylistCreationResult =
            when (item.type) {
                BaseItemKind.BOX_SET,
                BaseItemKind.COLLECTION_FOLDER,
                BaseItemKind.USER_VIEW,
                -> {
                    PlaylistCreationResult.Success(
                        createFromCollection(
                            item = item,
                            startIndex = startIndex,
                            sortAndDirection =
                                if (shuffled) {
                                    SortAndDirection(ItemSortBy.RANDOM, SortOrder.ASCENDING)
                                } else {
                                    sortAndDirection
                                },
                            filter = filter,
                        ),
                    )
                }

                BaseItemKind.EPISODE -> {
                    val seriesId = item.seriesId
                    if (seriesId != null) {
                        PlaylistCreationResult.Success(
                            createFromEpisode(
                                seriesId = seriesId,
                                seasonId = null,
                                episodeId = item.id,
                                shuffled = shuffled,
                            ),
                        )
                    } else {
                        PlaylistCreationResult.Error(null, "Episode has no seriesId")
                    }
                }

                BaseItemKind.SEASON -> {
                    val seriesId = item.seriesId
                    if (seriesId != null) {
                        if (shuffled) {
                            api.tvShowsApi
                                .getEpisodes(
                                    seriesId = seriesId,
                                    seasonId = item.id,
                                    limit = Playlist.MAX_SIZE,
                                    sortBy = ItemSortBy.RANDOM,
                                    fields = DetailItemFields,
                                ).content.items
                                .convertAndAddParts()
                                .let {
                                    PlaylistCreationResult.Success(Playlist(it))
                                }
                        } else {
                            PlaylistCreationResult.Success(
                                createFromEpisode(
                                    seriesId = seriesId,
                                    seasonId = item.id,
                                    episodeId = null,
                                    shuffled = shuffled,
                                ),
                            )
                        }
                    } else {
                        PlaylistCreationResult.Error(null, "Episode has no seriesId")
                    }
                }

                BaseItemKind.SERIES -> {
                    if (shuffled) {
                        api.tvShowsApi
                            .getEpisodes(
                                seriesId = item.id,
                                limit = Playlist.MAX_SIZE,
                                sortBy = ItemSortBy.RANDOM,
                                fields = DetailItemFields,
                            ).content.items
                            .convertAndAddParts()
                            .let {
                                PlaylistCreationResult.Success(Playlist(it))
                            }
                    } else {
                        val result by api.tvShowsApi.getNextUp(seriesId = item.id)
                        val nextUp =
                            result.items.firstOrNull() ?: api.tvShowsApi
                                .getEpisodes(
                                    item.id,
                                    limit = 1,
                                ).content.items
                                .firstOrNull()
                        if (nextUp != null) {
                            PlaylistCreationResult.Success(
                                createFromEpisode(
                                    seriesId = item.id,
                                    seasonId = null,
                                    episodeId = nextUp.id,
                                    shuffled = shuffled,
                                ),
                            )
                        } else {
                            PlaylistCreationResult.Error(
                                null,
                                "Could not determine next up episode for series: " + item.id,
                            )
                        }
                    }
                }

                BaseItemKind.MOVIE,
                BaseItemKind.VIDEO,
                BaseItemKind.MUSIC_VIDEO,
                BaseItemKind.TRAILER,
                -> {
                    val list =
                        buildList {
                            add(PlaylistItem.Media(BaseItem(item, false)))

                            if (item.partCount.gt(1)) {
                                api.videosApi
                                    .getAdditionalPart(item.id)
                                    .content.items
                                    .map {
                                        PlaylistItem.Media(BaseItem(it, false))
                                    }.let(::addAll)
                            }
                        }
                    PlaylistCreationResult.Success(Playlist(list))
                }

                // Not support yet
//                BaseItemKind.AGGREGATE_FOLDER -> TODO()
//                BaseItemKind.FOLDER -> TODO()
//                BaseItemKind.GENRE -> TODO()
//                BaseItemKind.MANUAL_PLAYLISTS_FOLDER -> TODO()
//                BaseItemKind.MUSIC_ALBUM -> TODO()
//                BaseItemKind.MUSIC_ARTIST -> TODO()

                else -> {
                    PlaylistCreationResult.Error(null, "Unsupported type: ${item.type}")
                }
            }

        private suspend fun List<BaseItemDto>.convertAndAddParts(useSeriesForPrimary: Boolean = false): List<PlaylistItem> =
            buildList {
                this@convertAndAddParts
                    .filter { it.locationType != LocationType.VIRTUAL }
                    .forEach { ep ->
                        add(PlaylistItem.Media(BaseItem(ep, useSeriesForPrimary)))
                        if (ep.partCount.gt(1)) {
                            val parts =
                                api.videosApi.getAdditionalPart(ep.id).content.items.map { part ->
                                    PlaylistItem.Media(BaseItem(part, useSeriesForPrimary))
                                }
                            addAll(parts)
                        }
                    }
            }
    }

sealed interface PlaylistCreationResult {
    data class Success(
        val playlist: Playlist,
    ) : PlaylistCreationResult

    data class Error(
        val ex: Exception?,
        val message: String?,
    ) : PlaylistCreationResult
}

/** The kinds of titles a library of this type holds. */
private val CollectionType.baseItemKinds: List<BaseItemKind>
    get() =
        when (this) {
            CollectionType.MOVIES -> {
                listOf(BaseItemKind.MOVIE)
            }

            CollectionType.TVSHOWS -> {
                listOf(BaseItemKind.SERIES, BaseItemKind.SEASON, BaseItemKind.EPISODE)
            }

            CollectionType.HOMEVIDEOS -> {
                listOf(BaseItemKind.VIDEO)
            }

            CollectionType.MUSIC -> {
                listOf(
                    BaseItemKind.AUDIO,
                    BaseItemKind.MUSIC_ARTIST,
                    BaseItemKind.MUSIC_ALBUM,
                )
            }

            CollectionType.MUSICVIDEOS -> {
                listOf(BaseItemKind.MUSIC_VIDEO)
            }

            CollectionType.BOXSETS -> {
                listOf(BaseItemKind.BOX_SET)
            }

            CollectionType.PLAYLISTS -> {
                listOf(BaseItemKind.PLAYLIST)
            }

            CollectionType.PHOTOS -> {
                listOf(BaseItemKind.PHOTO, BaseItemKind.PHOTO_ALBUM)
            }

            CollectionType.LIVETV -> {
                listOf(
                    BaseItemKind.TV_CHANNEL,
                    BaseItemKind.LIVE_TV_CHANNEL,
                    BaseItemKind.PROGRAM,
                    BaseItemKind.TV_PROGRAM,
                    BaseItemKind.LIVE_TV_PROGRAM,
                )
            }

            CollectionType.TRAILERS -> {
                listOf(BaseItemKind.TRAILER)
            }

            CollectionType.BOOKS -> {
                listOf(BaseItemKind.BOOK)
            }

            CollectionType.FOLDERS -> {
                listOf(BaseItemKind.FOLDER)
            }

            CollectionType.UNKNOWN -> {
                listOf()
            }
        }
