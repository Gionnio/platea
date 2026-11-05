package com.github.gionnio.platea.engine

/**
 * Where the preview frame of a position is in Jellyfin's trickplay images: thumbnails every [interval] ms,
 * packed in sheets of [tileWidth] x [tileHeight].
 */
object TrickplayTiles {
    data class Tile(
        /** Sheet to load (the index in the trickplay image URL). */
        val sheet: Int,
        val column: Int,
        val row: Int,
    )

    fun at(
        positionMs: Long,
        interval: Int,
        tileWidth: Int,
        tileHeight: Int,
        thumbnailCount: Int,
    ): Tile? {
        if (interval <= 0 || tileWidth <= 0 || tileHeight <= 0 || thumbnailCount <= 0) return null
        val index = (positionMs.coerceAtLeast(0) / interval).toInt().coerceAtMost(thumbnailCount - 1)
        val perSheet = tileWidth * tileHeight
        val inSheet = index % perSheet
        return Tile(sheet = index / perSheet, column = inSheet % tileWidth, row = inSheet / tileWidth)
    }
}
