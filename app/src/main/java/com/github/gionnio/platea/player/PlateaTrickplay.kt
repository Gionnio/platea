package com.github.gionnio.platea.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.github.gionnio.platea.engine.TrickplayTiles
import com.github.gionnio.platea.ui.theme.PlateaColors
import org.jellyfin.sdk.model.api.TrickplayInfo

/** Jellyfin's trickplay thumbnails of the title being played, and the address of each sheet. */
class TrickplaySource(
    val info: TrickplayInfo,
    val sheetUrl: (Int) -> String,
)

/** Height of the preview frame above the seek bar. */
val TrickplayHeight: Dp = 150.dp

fun TrickplaySource.previewWidth(): Dp = TrickplayHeight * (info.width.toFloat() / info.height.coerceAtLeast(1))

/**
 * The frame at [positionMs], cut out of its trickplay sheet: shown above the seek bar while the user moves along
 * it, so the place to jump to is visible before the jump.
 */
@Composable
fun TrickplayFrame(
    source: TrickplaySource,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val info = source.info
    val tile = TrickplayTiles.at(positionMs, info.interval, info.tileWidth, info.tileHeight, info.thumbnailCount) ?: return
    val context = LocalContext.current
    val url = remember(tile.sheet) { source.sheetUrl(tile.sheet) }
    val request =
        remember(url) {
            ImageRequest
                .Builder(context)
                .data(url)
                .size(coil3.size.Size.ORIGINAL)
                .build()
        }
    val painter = rememberAsyncImagePainter(model = request, contentScale = ContentScale.None)
    val width = source.previewWidth()
    val scale = with(LocalDensity.current) { width.toPx() / info.width }
    Box(
        modifier
            .size(width, TrickplayHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(BorderStroke(1.5.dp, PlateaColors.Gold.copy(alpha = 0.85f)), RoundedCornerShape(8.dp)),
    ) {
        Canvas(Modifier.size(width, TrickplayHeight)) {
            scale(scale, scale, pivot = Offset.Zero) {
                translate(left = -tile.column.toFloat() * info.width, top = -tile.row.toFloat() * info.height) {
                    with(painter) {
                        draw(Size(info.width * info.tileWidth.toFloat(), info.height * info.tileHeight.toFloat()))
                    }
                }
            }
        }
    }
}
