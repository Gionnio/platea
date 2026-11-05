package com.github.gionnio.platea.player

import com.github.gionnio.platea.util.isForcedTrack
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.data.model.Chapter
import com.github.gionnio.platea.data.model.TrackIndex
import com.github.gionnio.platea.ui.LocalImageUrlService
import com.github.gionnio.platea.ui.formatTime
import com.github.gionnio.platea.ui.playback.AnalyticsState
import com.github.gionnio.platea.ui.playback.ControllerViewState
import com.github.gionnio.platea.ui.playback.CurrentPlayback
import com.github.gionnio.platea.ui.playback.calculateSeekAccelerationMultiplier
import com.github.gionnio.platea.ui.seekBack
import com.github.gionnio.platea.ui.seekForward
import com.github.gionnio.platea.ui.skipStringRes
import com.github.gionnio.platea.details.PlaybackPrediction
import com.github.gionnio.platea.details.TrackFormat
import com.github.gionnio.platea.details.TrackPanel
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType
import java.time.LocalTime
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.model.extensions.ticks

/** After "back to the start", a second press within this position goes to the previous episode. */
private const val RESTART_WINDOW_MS = 4000L

/**
 * Platea playback controls, as in the concept: title and end time, chips with how the file is really played,
 * seek bar with chapters, Audio and Subtitles (Platea panel), back/play/forward, Chapters and Diagnostics.
 * Replaces Wholphin's PlaybackOverlay when the Platea interface is on; playback logic stays in PlaybackViewModel.
 */
@Composable
fun PlateaPlayerOverlay(
    item: BaseItem?,
    player: Player,
    controllerViewState: ControllerViewState,
    showPlay: Boolean,
    currentPlayback: CurrentPlayback?,
    chapters: List<Chapter>,
    analyticsState: AnalyticsState,
    nextEnabled: Boolean,
    /** There is a title before this one in the queue (saga, playlist). */
    previousEnabled: Boolean,
    currentSegment: MediaSegmentDto?,
    seekBack: Duration,
    seekForward: Duration,
    skipBackOnResume: Duration?,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onChangeAudio: (Int) -> Unit,
    onChangeSubtitle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Grows each time the subtitle or audio key of the remote is pressed: opens the track panel. */
    tracksRequest: Int = 0,
    /** Frames shown above the seek bar while moving along it (null when the server has none). */
    trickplay: TrickplaySource? = null,
) {
    val visible = controllerViewState.controlsVisible
    var showChapters by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    LaunchedEffect(tracksRequest) { if (tracksRequest > 0) showTracks = true }
    LaunchedEffect(visible) { if (!visible) showChapters = false }

    val streams = currentPlayback?.mediaSourceInfo?.mediaStreams.orEmpty()
    val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
    val audio = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == currentPlayback?.audioIndex }
    val subtitleIndex = currentPlayback?.subtitleIndex ?: TrackIndex.DISABLED
    val subtitle = streams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == subtitleIndex }
    val prediction = currentPlayback?.let { prediction(it, video) }

    Box(
        modifier
            .fillMaxSize()
            // Any key while the controls are up keeps them up
            .onPreviewKeyEvent {
                if (visible) controllerViewState.pulseControls()
                false
            },
    ) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Scrims for readability (concept: 440px at the bottom, 220px at the top)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.7f to Color.Black.copy(alpha = 0.88f))),
                )
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.75f), 1f to Color.Transparent)),
                )
                TopBar(item, player, prediction, video, audio, Modifier.align(Alignment.TopStart))
                // Diagnostics are part of the controls: shown with them, never over the film alone
                Diagnostics(
                    currentPlayback = currentPlayback,
                    player = player,
                    video = video,
                    audio = audio,
                    analyticsState = analyticsState,
                    methodText =
                        if (currentPlayback?.fromNas == true) {
                            stringResource(R.string.platea_method_nas)
                        } else {
                            prediction?.let { methodText(it) }
                        },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 85.dp, end = PlateaDimens.SafeHorizontal),
                )
                BottomControls(
                    player = player,
                    trickplay = trickplay,
                    controllerViewState = controllerViewState,
                    showPlay = showPlay,
                    chapters = chapters,
                    showChapters = showChapters,
                    onShowChapters = { showChapters = it },
                    audioLabel = audio?.let { listOfNotNull(TrackFormat.language(it.language), channels(it)).joinToString(" ") } ?: stringResource(R.string.platea_none),
                    subtitleLabel =
                        when {
                            subtitleIndex == TrackIndex.ONLY_FORCED -> stringResource(R.string.platea_forced_only)
                            subtitle == null -> stringResource(R.string.platea_none)
                            else -> listOfNotNull(TrackFormat.language(subtitle.language), if (subtitle.isForcedTrack) stringResource(R.string.platea_forced) else null).joinToString(" ")
                        },
                    onTracks = {
                        controllerViewState.pulseControls(Long.MAX_VALUE)
                        showTracks = true
                    },
                    nextEnabled = nextEnabled,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    isEpisode = item?.data?.seriesId != null || previousEnabled,
                    currentSegment = currentSegment,
                    seekBack = seekBack,
                    seekForward = seekForward,
                    skipBackOnResume = skipBackOnResume,
                    onSeek = onSeek,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (showTracks) {
        val text = prediction?.let { methodText(it) }.orEmpty()
        TrackPanel(
            streams = streams,
            audioIndex = currentPlayback?.audioIndex,
            subtitleIndex = subtitleIndex,
            prediction = prediction,
            predictionText = { text },
            onSelect = { type, index -> if (type == MediaStreamType.AUDIO) onChangeAudio(index) else onChangeSubtitle(index) },
            onDismiss = {
                showTracks = false
                controllerViewState.pulseControls()
            },
        )
    }
}

private fun prediction(
    playback: CurrentPlayback,
    video: MediaStream?,
): PlaybackPrediction {
    val method =
        when (playback.playMethod) {
            PlayMethod.DIRECT_PLAY -> PlaybackPrediction.Method.DIRECT_PLAY
            PlayMethod.DIRECT_STREAM -> PlaybackPrediction.Method.REMUX
            PlayMethod.TRANSCODE -> PlaybackPrediction.Method.TRANSCODE
        }
    return PlaybackPrediction(
        method = method,
        dolbyVision = isDovi(video) && method == PlaybackPrediction.Method.DIRECT_PLAY,
        reasons = playback.transcodeInfo?.transcodeReasons?.map { it.serialName }.orEmpty(),
    )
}

private fun isDovi(video: MediaStream?) = video?.videoRangeType?.serialName?.startsWith("DOVI") == true || video?.videoDoViTitle != null

@Composable
private fun methodText(p: PlaybackPrediction): String =
    when (p.method) {
        PlaybackPrediction.Method.DIRECT_PLAY -> stringResource(if (p.dolbyVision) R.string.platea_direct_play_dv else R.string.platea_direct_play)
        PlaybackPrediction.Method.REMUX -> stringResource(R.string.platea_remux)
        PlaybackPrediction.Method.TRANSCODE -> stringResource(R.string.platea_transcode)
    } + (p.reasons.firstOrNull()?.takeIf { p.method != PlaybackPrediction.Method.DIRECT_PLAY }?.let { " · $it" } ?: "")

private fun channels(a: MediaStream): String? =
    when (a.channels) {
        1 -> "1.0"
        2 -> "2.0"
        6 -> "5.1"
        8 -> "7.1"
        null -> null
        else -> "${a.channels}ch"
    }

private fun hdrLabel(video: MediaStream?): String? =
    when {
        video == null -> null
        isDovi(video) -> "DOLBY VISION"
        video.videoRangeType == VideoRangeType.HDR10_PLUS -> "HDR10+"
        video.videoRangeType == VideoRangeType.HDR10 -> "HDR10"
        video.videoRangeType == VideoRangeType.HLG -> "HLG"
        else -> null
    }

/** Current display mode, refreshed every few seconds (the refresh rate switch happens after start). */
@Composable
private fun displayMode(): Display.Mode? {
    val context = LocalContext.current
    val display = remember(context) { runCatching { (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull() }
    val mode by produceState<Display.Mode?>(display?.mode) {
        while (isActive) {
            value = display?.mode
            delay(3.seconds)
        }
    }
    return mode
}

private fun hz(rate: Float) = String.format(Locale.ITALY, "%.3f", rate).trimEnd('0').trimEnd(',') + " Hz"

// --- Top ---

@Composable
private fun TopBar(
    item: BaseItem?,
    player: Player,
    prediction: PlaybackPrediction?,
    video: MediaStream?,
    audio: MediaStream?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val endTime by produceState("…", player) {
        while (isActive) {
            val remaining = ((player.duration - player.currentPosition) / player.playbackParameters.speed).toLong().coerceAtLeast(0).milliseconds
            value = formatTime(context, LocalTime.now().plusSeconds(remaining.inWholeSeconds))
            delay(1.seconds)
        }
    }
    val mode = displayMode()
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier.fillMaxWidth().padding(start = PlateaDimens.SafeHorizontal, end = PlateaDimens.SafeHorizontal, top = PlateaDimens.SafeTop),
    ) {
        Column(Modifier.weight(1f)) {
            val episode = item?.data?.seriesName != null
            Text(
                (if (episode) item?.data?.seriesName else item?.name).orEmpty(),
                style = PlateaType.HeroTitle.copy(fontSize = 18.sp),
                color = PlateaColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode) {
                Text(
                    "S${item?.data?.parentIndexNumber ?: 0}:E${item?.data?.indexNumber ?: 0} · ${item?.name.orEmpty()}",
                    style = PlateaType.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = PlateaColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(stringResource(R.string.ends_at, endTime), style = PlateaType.Body, color = PlateaColors.TextSecondary, modifier = Modifier.padding(top = 3.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            prediction?.let {
                val (label, color) =
                    when (it.method) {
                        PlaybackPrediction.Method.DIRECT_PLAY -> stringResource(R.string.platea_chip_direct) to PlateaColors.OkGreen
                        PlaybackPrediction.Method.REMUX -> stringResource(R.string.platea_chip_remux) to PlateaColors.Gold
                        PlaybackPrediction.Method.TRANSCODE -> stringResource(R.string.platea_chip_transcode) to Color(0xFFF87171)
                    }
                Chip(label, color, color.copy(alpha = 0.2f))
            }
            hdrLabel(video)?.let { Chip(it) }
            audio?.let { Chip(TrackFormat.audioFormat(it).uppercase()) }
            mode?.let { Chip(hz(it.refreshRate)) }
        }
    }
}

@Composable
private fun Chip(
    text: String,
    color: Color = PlateaColors.Text,
    background: Color = Color.White.copy(alpha = 0.1f),
) {
    Text(
        text,
        style = PlateaType.Badge.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold),
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(background).padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

// --- Bottom ---

@Composable
private fun BottomControls(
    player: Player,
    trickplay: TrickplaySource?,
    controllerViewState: ControllerViewState,
    showPlay: Boolean,
    chapters: List<Chapter>,
    showChapters: Boolean,
    onShowChapters: (Boolean) -> Unit,
    audioLabel: String,
    subtitleLabel: String,
    onTracks: () -> Unit,
    nextEnabled: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    isEpisode: Boolean,
    currentSegment: MediaSegmentDto?,
    seekBack: Duration,
    seekForward: Duration,
    skipBackOnResume: Duration?,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val playFocus = remember { FocusRequester() }
    val chaptersFocus = remember { FocusRequester() }
    var returnToChapters by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    LaunchedEffect(showChapters) {
        if (!showChapters && returnToChapters) {
            runCatching { chaptersFocus.requestFocus() }
            returnToChapters = false
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(11.dp),
        modifier = modifier.fillMaxWidth().padding(start = PlateaDimens.SafeHorizontal, end = PlateaDimens.SafeHorizontal, bottom = PlateaDimens.SafeTop),
    ) {
        if (showChapters && chapters.isNotEmpty()) {
            ChapterRow(player, chapters, controllerViewState, onClose = {
                returnToChapters = true
                onShowChapters(false)
            })
        }
        SeekRow(player, chapters, controllerViewState, seekBack, seekForward, onSeek, trickplay)
        Box(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.align(Alignment.CenterStart)) {
                PillButton(stringResource(R.string.platea_player_audio, audioLabel), onClick = onTracks)
                PillButton(stringResource(R.string.platea_player_subtitles, subtitleLabel), onClick = onTracks)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.Center)) {
                // Back to the start; pressed again right away (still in the first seconds): previous episode
                RoundButton(32.dp, onClick = {
                    if (isEpisode && player.currentPosition < RESTART_WINDOW_MS) onPrevious() else player.seekTo(0)
                }) { tint ->
                    Icon(painterResource(R.drawable.baseline_skip_previous_24), contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
                }
                RoundButton(32.dp, onClick = { player.seekBack(seekBack) }) { tint ->
                    SkipGlyph(seekBack.inWholeSeconds, back = true, tint = tint, size = 22.dp, textSize = 7.sp)
                }
                RoundButton(42.dp, modifier = Modifier.focusRequester(playFocus), onClick = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                        skipBackOnResume?.let { player.seekBack(it) }
                    }
                }) { tint ->
                    if (showPlay) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(painterResource(R.drawable.baseline_pause_24), contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                    }
                }
                RoundButton(32.dp, onClick = { player.seekForward(seekForward) }) { tint ->
                    SkipGlyph(seekForward.inWholeSeconds, back = false, tint = tint, size = 22.dp, textSize = 7.sp)
                }
                if (nextEnabled) {
                    RoundButton(32.dp, onClick = onNext) { tint ->
                        Icon(painterResource(R.drawable.baseline_skip_next_24), contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
                    }
                } else {
                    // Keeps play/pause centred when there is nothing after this title
                    Spacer(Modifier.size(32.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.align(Alignment.CenterEnd)) {
                currentSegment?.let { segment ->
                    PillButton(stringResource(segment.type.skipStringRes), onClick = { player.seekTo(segment.endTicks.ticks.inWholeMilliseconds) })
                }
                if (chapters.isNotEmpty()) {
                    PillButton(stringResource(R.string.platea_chapters), selected = showChapters, modifier = Modifier.focusRequester(chaptersFocus), onClick = { onShowChapters(!showChapters) })
                }
            }
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.ButtonRadius)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = PlateaColors.Surface.copy(alpha = 0.6f),
                contentColor = if (selected) PlateaColors.Gold else PlateaColors.Text,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, if (selected) PlateaColors.Gold.copy(alpha = 0.6f) else PlateaColors.Border)),
                focusedBorder = Border(BorderStroke(2.dp, PlateaColors.Text.copy(alpha = 0.35f)), inset = (-2).dp),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
        modifier = modifier.height(28.dp),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = PlateaType.Button.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

@Composable
private fun RoundButton(
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors =
            ClickableSurfaceDefaults.colors(
                // Same dark glass for all; white only under the cursor (play/pause included)
                containerColor = Color.White.copy(alpha = 0.12f),
                focusedContainerColor = PlateaColors.Text,
            ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.5.dp, PlateaColors.Text.copy(alpha = 0.3f)), inset = (-3).dp, shape = CircleShape)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        modifier = modifier.size(size),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content(if (focused) PlateaColors.Ground else PlateaColors.Text)
        }
    }
}

// --- Seek bar ---

@Composable
private fun SeekRow(
    player: Player,
    chapters: List<Chapter>,
    controllerViewState: ControllerViewState,
    seekBack: Duration,
    seekForward: Duration,
    onSeek: (Long) -> Unit,
    trickplay: TrickplaySource?,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var position by remember(player) { mutableLongStateOf(player.currentPosition) }
    var buffered by remember(player) { mutableLongStateOf(player.bufferedPosition) }
    var seekPosition by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(player) {
        while (isActive) {
            position = player.currentPosition
            buffered = player.bufferedPosition
            delay(250)
        }
    }
    LaunchedEffect(focused) { if (!focused) seekPosition = -1L }
    val duration = player.duration.coerceAtLeast(1L)
    val shown = if (focused && seekPosition >= 0) seekPosition else position

    fun seekBy(deltaMs: Long) {
        controllerViewState.pulseControls()
        seekPosition = (shown + deltaMs).coerceIn(0L, duration)
        onSeek(seekPosition)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(timecode(shown), style = PlateaType.Body.copy(fontWeight = FontWeight.SemiBold), color = PlateaColors.Text, modifier = Modifier.width(56.dp))
        Spacer(Modifier.width(6.dp))
        val barHeight by animateDpAsState(if (focused) 5.dp else 4.dp, label = "bar")
        BoxWithConstraints(
            contentAlignment = Alignment.CenterStart,
            modifier =
                Modifier
                    .weight(1f)
                    .height(18.dp)
                    .onPreviewKeyEvent { event ->
                        val left = event.key == Key.DirectionLeft
                        val right = event.key == Key.DirectionRight
                        if (!left && !right) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) {
                            val multiplier = calculateSeekAccelerationMultiplier(event.nativeKeyEvent.repeatCount, duration)
                            if (left) seekBy(-seekBack.inWholeMilliseconds * multiplier) else seekBy(seekForward.inWholeMilliseconds * multiplier)
                        }
                        true
                    }.focusable(interactionSource = interaction),
        ) {
            val width = maxWidth
            Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxWidth((buffered.toFloat() / duration).coerceIn(0f, 1f)).fillMaxHeight().background(Color.White.copy(alpha = 0.35f)))
                Box(Modifier.fillMaxWidth((shown.toFloat() / duration).coerceIn(0f, 1f)).fillMaxHeight().background(PlateaColors.Gold))
            }
            // Chapter marks
            chapters.forEach { c ->
                val fraction = (c.position.inWholeMilliseconds.toFloat() / duration).coerceIn(0f, 1f)
                if (fraction > 0.005f) {
                    Box(Modifier.offset(x = width * fraction - 0.75.dp).width(1.5.dp).height(barHeight + 4.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.7f)))
                }
            }
            // Platea: while moving along the bar, the frame at that point floats above it
            if (trickplay != null && focused && seekPosition >= 0) {
                val previewWidth = trickplay.previewWidth()
                val x = (width * (shown.toFloat() / duration).coerceIn(0f, 1f) - previewWidth / 2).coerceIn(0.dp, (width - previewWidth).coerceAtLeast(0.dp))
                TrickplayFrame(
                    source = trickplay,
                    positionMs = shown,
                    modifier = Modifier.offset(x = x, y = -(TrickplayHeight / 2 + 30.dp)).wrapContentSize(unbounded = true),
                )
            }
            val thumb = if (focused) 13.dp else 9.dp
            Box(
                Modifier
                    .offset(x = width * (shown.toFloat() / duration).coerceIn(0f, 1f) - thumb / 2)
                    .size(thumb)
                    .then(if (focused) Modifier.border(3.dp, Color.White.copy(alpha = 0.25f), CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("−" + timecode(duration - shown), style = PlateaType.Body.copy(fontWeight = FontWeight.SemiBold), color = PlateaColors.TextSecondary, modifier = Modifier.width(60.dp).padding(start = 4.dp))
    }
}

private fun timecode(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

// --- Chapters ---

@Composable
private fun ChapterRow(
    player: Player,
    chapters: List<Chapter>,
    controllerViewState: ControllerViewState,
    onClose: () -> Unit,
) {
    val position = player.currentPosition
    val current = chapters.indexOfLast { it.position.inWholeMilliseconds <= position }.coerceAtLeast(0)
    val listState = rememberLazyListState(current)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val imageUrlService = LocalImageUrlService.current
    val height = with(LocalDensity.current) { 68.dp.roundToPx() }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        // Room for the focus frame and scale of the first and last card
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        modifier =
            Modifier.onPreviewKeyEvent {
                if (it.key == Key.Back && it.type == KeyEventType.KeyUp) {
                    onClose()
                    true
                } else {
                    it.key == Key.Back
                }
            },
    ) {
        itemsIndexed(chapters) { i, chapter ->
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            Column(Modifier.width(121.dp)) {
                Surface(
                    onClick = {
                        player.seekTo(chapter.position.inWholeMilliseconds)
                        onClose()
                        controllerViewState.hideControls()
                    },
                    interactionSource = interaction,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.CardRadius)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = PlateaColors.Placeholder, focusedContainerColor = PlateaColors.Placeholder),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(PlateaDimens.FocusBorder, PlateaColors.Text), shape = RoundedCornerShape(PlateaDimens.CardRadius))),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
                    modifier = Modifier.width(121.dp).height(68.dp).then(if (i == current) Modifier.focusRequester(focus) else Modifier),
                ) {
                    // Shown when the server has no chapter images
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = PlateaType.RowTitle, color = PlateaColors.TextTertiary)
                    }
                    AsyncImage(
                        model = remember(chapter) { imageUrlService.getItemImageUrl(itemId = chapter.itemId, imageType = ImageType.CHAPTER, tag = chapter.tag, imageIndex = chapter.index, fillHeight = height) },
                        contentDescription = chapter.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Chapter number over the frame
                    Text(
                        "${i + 1}",
                        style = PlateaType.Badge.copy(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold),
                        color = PlateaColors.Text,
                        modifier =
                            Modifier
                                .padding(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(PlateaColors.Ground.copy(alpha = 0.75f))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
                Text(
                    chapter.name ?: "${i + 1}",
                    style = PlateaType.CardSub.copy(fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium),
                    color = if (focused) Color.White else PlateaColors.TextCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(timecode(chapter.position.inWholeMilliseconds), style = PlateaType.CardSub, color = PlateaColors.TextMeta)
            }
        }
    }
}

// --- Diagnostics ---

@Composable
private fun Diagnostics(
    currentPlayback: CurrentPlayback?,
    player: Player,
    video: MediaStream?,
    audio: MediaStream?,
    analyticsState: AnalyticsState,
    methodText: String?,
    modifier: Modifier,
) {
    val mode = displayMode()
    val bufferSeconds by produceState(0L, player) {
        while (isActive) {
            value = player.totalBufferedDuration / 1000
            delay(1.seconds)
        }
    }
    val rows =
        listOf(
            stringResource(R.string.platea_diag_method) to (methodText ?: "—"),
            stringResource(R.string.platea_diag_decoder) to (currentPlayback?.videoDecoder ?: "—"),
            stringResource(R.string.platea_diag_format) to
                listOfNotNull(
                    video?.videoDoViTitle ?: hdrLabel(video) ?: "SDR",
                    video?.codec?.uppercase(),
                    if (video?.width != null && video.height != null) "${video.width}×${video.height}" else null,
                ).joinToString(" · "),
            stringResource(R.string.platea_diag_display) to (mode?.let { "${it.physicalWidth}×${it.physicalHeight} @ ${hz(it.refreshRate)}" } ?: "—"),
            stringResource(R.string.platea_diag_audio) to
                (
                    audio?.let { a ->
                        // No decoder and no audio track in the stream: nothing is reaching the TV, not passthrough
                        val receiving = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                        TrackFormat.audioFormat(a) + " → " +
                            (
                                currentPlayback?.audioDecoder
                                    ?: stringResource(if (receiving) R.string.platea_diag_passthrough else R.string.platea_diag_no_audio)
                            )
                    } ?: "—"
                ),
            stringResource(R.string.platea_diag_bitrate) to analyticsState.bitrate,
            stringResource(R.string.platea_diag_dropped) to analyticsState.droppedFrames.toString(),
            stringResource(R.string.platea_diag_buffer) to "$bufferSeconds s",
        )
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .width(270.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PlateaColors.Ground.copy(alpha = 0.88f))
                .border(BorderStroke(0.5.dp, PlateaColors.Avatar), RoundedCornerShape(8.dp))
                .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.platea_diag_title), style = PlateaType.Kicker, color = PlateaColors.TextMeta, modifier = Modifier.padding(bottom = 2.dp))
        rows.forEach { (k, v) ->
            Row {
                Text(k, style = PlateaType.Meta.copy(fontSize = 9.5.sp), color = PlateaColors.TextTertiary, modifier = Modifier.width(85.dp))
                Text(v, style = PlateaType.Meta.copy(fontSize = 9.5.sp), color = PlateaColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
