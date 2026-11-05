package com.github.gionnio.platea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrickplayTilesTest {
    // Jellyfin's defaults: a thumbnail every 10 s, sheets of 10 x 10
    private fun at(positionMs: Long) = TrickplayTiles.at(positionMs, interval = 10_000, tileWidth = 10, tileHeight = 10, thumbnailCount = 720)

    @Test
    fun positionsMapToSheetColumnAndRow() {
        assertEquals(TrickplayTiles.Tile(0, 0, 0), at(0))
        assertEquals(TrickplayTiles.Tile(0, 3, 0), at(35_000))
        assertEquals(TrickplayTiles.Tile(0, 2, 1), at(125_000))
        // Thumbnail 100 opens the second sheet
        assertEquals(TrickplayTiles.Tile(1, 0, 0), at(1_000_000))
    }

    @Test
    fun theEndStaysOnTheLastThumbnail() {
        assertEquals(TrickplayTiles.Tile(7, 9, 1), at(10_000_000))
    }

    @Test
    fun brokenInfoGivesNoPreview() {
        assertNull(TrickplayTiles.at(5_000, interval = 0, tileWidth = 10, tileHeight = 10, thumbnailCount = 10))
        assertNull(TrickplayTiles.at(5_000, interval = 10_000, tileWidth = 10, tileHeight = 10, thumbnailCount = 0))
    }
}
