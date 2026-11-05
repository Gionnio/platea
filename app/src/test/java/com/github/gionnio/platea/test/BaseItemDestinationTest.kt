package com.github.gionnio.platea.test

import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.ui.nav.Destination
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class BaseItemDestinationTest {
    @Test
    fun audioWithAlbumId_navigatesToAlbumWithInitialSongId() {
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val item = BaseItem(song(id = songId, albumId = albumId), false)

        val dest = item.destination()

        assertEquals(Destination.MediaItem::class, dest::class)
        val media = dest as Destination.MediaItem
        assertEquals(albumId, media.itemId)
        assertEquals(BaseItemKind.MUSIC_ALBUM, media.type)
        assertEquals(songId, media.initialSongId)
    }

    @Test
    fun audioWithoutAlbumId_returnsSelfAsMediaItem() {
        val songId = UUID.randomUUID()
        val item = BaseItem(song(id = songId, albumId = null), false)

        val dest = item.destination()

        assertEquals(songId, (dest as Destination.MediaItem).itemId)
        assertEquals(BaseItemKind.AUDIO, dest.type)
        assertNull(dest.initialSongId)
    }

    @Test
    fun movie_usesStandardDestination() {
        val movieId = UUID.randomUUID()
        val item = BaseItem(movie(id = movieId), false)

        val dest = item.destination()

        assertEquals(movieId, (dest as Destination.MediaItem).itemId)
        assertEquals(BaseItemKind.MOVIE, dest.type)
    }

    @Test
    fun episodeWithSeasonButNoSeries_opensAsItIs() {
        val episodeId = UUID.randomUUID()
        val item =
            BaseItem(
                movie(id = episodeId).copy(type = BaseItemKind.EPISODE, seasonId = UUID.randomUUID(), seriesId = null),
                false,
            )

        val dest = item.destination()

        assertEquals(episodeId, (dest as Destination.MediaItem).itemId)
    }

    @Test
    fun seasonWithoutSeries_opensAsItIs() {
        val seasonId = UUID.randomUUID()
        val item = BaseItem(movie(id = seasonId).copy(type = BaseItemKind.SEASON, seriesId = null), false)

        val dest = item.destination()

        assertEquals(seasonId, (dest as Destination.MediaItem).itemId)
    }

    @Test
    fun episodeWithSeasonAndSeries_opensTheSeries() {
        val seriesId = UUID.randomUUID()
        val item =
            BaseItem(
                movie().copy(type = BaseItemKind.EPISODE, seasonId = UUID.randomUUID(), seriesId = seriesId),
                false,
            )

        val dest = item.destination()

        assertEquals(seriesId, (dest as Destination.SeriesOverview).itemId)
    }
}
