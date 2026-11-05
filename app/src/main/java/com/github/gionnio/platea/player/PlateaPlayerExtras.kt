package com.github.gionnio.platea.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.ui.formatTime
import com.github.gionnio.platea.ui.skipStringRes
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType
import java.time.LocalTime
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.MediaSegmentType

/**
 * End of an episode: the next one, with its frame, title, plot and length; OK plays it, Back cancels the
 * automatic start. The countdown drains along the bottom of the frame.
 *
 * @param secondsLeft seconds before the automatic start, null when it is off or was cancelled
 */
@Composable
fun PlateaNextUp(
    episode: BaseItem,
    imageUrl: String?,
    secondsLeft: Long?,
    totalSeconds: Long,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val context = LocalContext.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = PlateaDimens.SafeHorizontal, vertical = 16.dp),
    ) {
        Surface(
            onClick = onPlay,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.CardRadius)),
            colors = ClickableSurfaceDefaults.colors(containerColor = PlateaColors.Placeholder, focusedContainerColor = PlateaColors.Placeholder),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(PlateaDimens.FocusBorder, PlateaColors.Text), shape = RoundedCornerShape(PlateaDimens.CardRadius))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
            modifier = Modifier.focusRequester(focus).width(240.dp).height(135.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = imageUrl, contentDescription = episode.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                // Play mark on the frame
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(PlateaColors.Ground.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = PlateaColors.Text, modifier = Modifier.size(22.dp))
                }
                if (secondsLeft != null && totalSeconds > 0) {
                    val fraction by animateFloatAsState((secondsLeft.toFloat() / totalSeconds).coerceIn(0f, 1f), tween(1000, easing = LinearEasing), label = "countdown")
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(PlateaColors.Gold))
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
            Text(
                if (secondsLeft != null) stringResource(R.string.platea_next_starts_in, secondsLeft) else stringResource(R.string.platea_featured_next_episode),
                style = PlateaType.Kicker,
                color = PlateaColors.Gold,
            )
            Text(
                "S${episode.data.parentIndexNumber ?: 0}:E${episode.data.indexNumber ?: 0} · ${episode.name.orEmpty()}",
                style = PlateaType.RowTitle.copy(fontSize = 16.sp),
                color = PlateaColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.data.overview?.let {
                Text(it, style = PlateaType.Body, color = PlateaColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            episode.data.runTimeTicks?.let { ticks ->
                val minutes = ticks / 600_000_000L
                val ends = formatTime(context, LocalTime.now().plusMinutes(minutes))
                Text(
                    "$minutes min  •  " + stringResource(R.string.ends_at, ends),
                    style = PlateaType.Meta,
                    color = PlateaColors.TextMeta,
                )
            }
            if (secondsLeft != null) {
                Text(stringResource(R.string.platea_next_cancel_hint), style = PlateaType.CardSub, color = PlateaColors.TextTertiary, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** "Skip intro" while the controls are hidden: a pill with a slow gold glow, white when focused. */
@Composable
fun PlateaSkipSegmentButton(
    type: MediaSegmentType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glow by rememberInfiniteTransition(label = "skip").animateColor(
        initialValue = PlateaColors.Gold,
        targetValue = PlateaColors.Gold.copy(alpha = 0.15f),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "glow",
    )
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = PlateaColors.Ground.copy(alpha = 0.8f),
                contentColor = PlateaColors.Text,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.5.dp, glow), shape = RoundedCornerShape(50)),
                focusedBorder = Border(BorderStroke(2.dp, glow), inset = (-3).dp, shape = RoundedCornerShape(50)),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
        modifier = modifier.height(32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp)) {
            Text(stringResource(type.skipStringRes), style = PlateaType.Button.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold))
            Spacer(Modifier.width(6.dp))
            Text("›", style = PlateaType.Button.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold))
        }
    }
}

/**
 * Brief mark in the middle of the screen when the film is paused or resumed with the controls hidden:
 * pause or play symbol on a velvet disc.
 */
@Composable
fun PlateaPauseIndicator(
    player: Player,
    modifier: Modifier = Modifier,
) {
    // playWhenReady changes: false = paused by the user, true = resumed
    var event by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    val active = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING
                    if (active) event = playWhenReady to System.nanoTime()
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(event) {
        if (event != null) {
            visible = true
            delay(700)
            visible = false
        }
    }
    AnimatedVisibility(visible, enter = scaleIn(tween(220), initialScale = 0.7f) + fadeIn(tween(220)), exit = fadeOut(tween(400)), modifier = modifier) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(PlateaColors.Ground.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            if (event?.first == true) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = PlateaColors.Text, modifier = Modifier.size(38.dp))
            } else {
                Icon(painterResource(R.drawable.baseline_pause_24), contentDescription = null, tint = PlateaColors.Text, modifier = Modifier.size(34.dp))
            }
        }
    }
}
