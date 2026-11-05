@file:UseSerializers(UUIDSerializer::class)

package com.github.gionnio.platea.ui.nav

import androidx.navigation3.runtime.NavKey
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.data.model.GetItemsFilter
import com.github.gionnio.platea.preferences.PlayerBackend
import com.github.gionnio.platea.ui.data.SortAndDirection
import com.github.gionnio.platea.ui.preferences.PreferenceScreenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import java.util.UUID

/**
 * Represents a page in the app
 *
 * @param fullScreen kept for the pages that hide the system clock; every page is drawn full screen
 */
@Serializable
sealed class Destination(
    val fullScreen: Boolean = false,
) : NavKey {
    @Serializable
    data class Home(
        val id: Long = 0L,
    ) : Destination()


    @Serializable
    data class Settings(
        val screen: PreferenceScreenOption,
    ) : Destination(true)

    @Serializable
    data class SubtitleSettings(
        val hdr: Boolean,
    ) : Destination(true)

    @Serializable
    data class Search(
        val query: String = "",
    ) : Destination()


    @Serializable
    data class SeriesOverview(
        val itemId: UUID,
        val type: BaseItemKind,
        val seasonEpisode: SeasonEpisodeIds? = null,
    ) : Destination() {
        override fun toString(): String = "SeriesOverview(itemId=$itemId, type=$type, seasonEpisode=$seasonEpisode)"
    }

    @Serializable
    data class MediaItem(
        val itemId: UUID,
        val type: BaseItemKind,
        val collectionType: CollectionType? = null,
        val initialSongId: UUID? = null,
        /** Platea: on a series, the episode to open the page on (its season, cursor on it). */
        val initialEpisodeId: UUID? = null,
    ) : Destination() {
        constructor(item: BaseItem) : this(item.id, item.type, item.data.collectionType)
    }


    @Serializable
    data class Playback(
        val itemId: UUID,
        val positionMs: Long,
        val forceTranscoding: Boolean = false,
        val backend: PlayerBackend? = null,
        val shuffle: Boolean = false,
    ) : Destination(true) {
        constructor(item: BaseItem) : this(item.id, item.resumeMs)
    }

    @Serializable
    data class PlaybackList(
        val itemId: UUID,
        val filter: GetItemsFilter = GetItemsFilter(),
        val startIndex: Int? = null,
        val shuffle: Boolean = false,
        val recursive: Boolean = false,
        val sortAndDirection: SortAndDirection? = null,
    ) : Destination(true) {
        override fun toString(): String = "PlaybackList(itemId=$itemId)"
    }











    @Serializable
    data object License : Destination(true)

}

/** The season and episode a link to a series points at. */
@Serializable
data class SeasonEpisodeIds(
    val seasonId: UUID,
    val seasonNumber: Int?,
    val episodeId: UUID?,
    val episodeNumber: Int?,
)
