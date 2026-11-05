package com.github.gionnio.platea.details

import com.github.gionnio.platea.util.isForcedTrack
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.ChosenStreams
import com.github.gionnio.platea.data.model.TrackIndex
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/** One choice of the panel: a real track or a special subtitle mode (none, forced only). */
private data class TrackOption(
    val index: Int,
    val title: String,
    val detail: String?,
    val tags: List<String>,
    val selected: Boolean,
)

/**
 * Audio and subtitles panel, opened from the right over the page: two columns, the selected track marked in gold,
 * the playback decision updated live at the top. Generic: movies, episodes and the player use it.
 *
 * @param onSelect type (AUDIO/SUBTITLE) and track index, or [TrackIndex.DISABLED]/[TrackIndex.ONLY_FORCED] for subtitles
 */
@Composable
fun TrackPanel(
    chosen: ChosenStreams,
    prediction: PlaybackPrediction?,
    predictionText: (PlaybackPrediction) -> String,
    onSelect: (MediaStreamType, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val subtitle =
        when {
            chosen.itemPlayback?.subtitleIndex == TrackIndex.ONLY_FORCED -> TrackIndex.ONLY_FORCED
            chosen.subtitlesDisabled || chosen.subtitleStream == null -> TrackIndex.DISABLED
            else -> chosen.subtitleStream.index
        }
    TrackPanel(chosen.source.mediaStreams.orEmpty(), chosen.audioStream?.index, subtitle, prediction, predictionText, onSelect, onDismiss)
}

/**
 * @param audioIndex selected audio track
 * @param subtitleIndex selected subtitle track, or [TrackIndex.DISABLED]/[TrackIndex.ONLY_FORCED]
 */
@Composable
fun TrackPanel(
    streams: List<MediaStream>,
    audioIndex: Int?,
    subtitleIndex: Int,
    prediction: PlaybackPrediction?,
    predictionText: (PlaybackPrediction) -> String,
    onSelect: (MediaStreamType, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val forcedOnlyMode = subtitleIndex == TrackIndex.ONLY_FORCED
    val audio =
        streams.filter { it.type == MediaStreamType.AUDIO }.map { a ->
            TrackOption(
                index = a.index,
                title = listOfNotNull(TrackFormat.language(a.language), TrackFormat.extraTitle(a)).joinToString(" · ").ifBlank { stringResource(R.string.platea_unknown_language) },
                detail = TrackFormat.audioFormat(a),
                tags = listOfNotNull(if (a.isDefault) stringResource(R.string.platea_tag_default) else null),
                selected = a.index == audioIndex,
            )
        }
    val subtitleStreams = streams.filter { it.type == MediaStreamType.SUBTITLE }
    val subtitles =
        buildList {
            if (subtitleStreams.any { it.isForcedTrack }) {
                add(TrackOption(TrackIndex.ONLY_FORCED, stringResource(R.string.platea_forced_only), stringResource(R.string.platea_forced_only_detail), emptyList(), forcedOnlyMode))
            }
            add(TrackOption(TrackIndex.DISABLED, stringResource(R.string.platea_none), null, emptyList(), subtitleIndex < 0 && !forcedOnlyMode))
            subtitleStreams.forEach { s -> add(subtitleOption(s, selected = s.index == subtitleIndex)) }
        }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(560.dp)
                        .background(PlateaColors.Surface)
                        .border(BorderStroke(0.5.dp, PlateaColors.Avatar))
                        .padding(start = 24.dp, end = PlateaDimens.SafeHorizontal, top = PlateaDimens.SafeTop + 6.dp, bottom = PlateaDimens.SafeTop),
            ) {
                Text(stringResource(R.string.platea_audio_subtitles), style = PlateaType.RowTitle.copy(fontSize = 17.sp), color = PlateaColors.Text)
                Spacer(Modifier.size(8.dp))
                prediction?.let { PredictionPill(it, predictionText(it)) }
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxSize()) {
                    TrackColumn(stringResource(R.string.platea_tech_audio), audio, focusFirstSelected = true, Modifier.weight(1f)) { onSelect(MediaStreamType.AUDIO, it) }
                    TrackColumn(stringResource(R.string.platea_tech_subtitles), subtitles, focusFirstSelected = false, Modifier.weight(1f)) { onSelect(MediaStreamType.SUBTITLE, it) }
                }
            }
        }
    }
}

@Composable
private fun subtitleOption(
    s: MediaStream,
    selected: Boolean,
) = TrackOption(
    index = s.index,
    title = listOfNotNull(TrackFormat.language(s.language), TrackFormat.extraTitle(s)).joinToString(" · ").ifBlank { stringResource(R.string.platea_unknown_language) },
    detail = TrackFormat.subtitleFormat(s),
    tags =
        listOfNotNull(
            if (s.isForcedTrack) stringResource(R.string.platea_tag_forced) else null,
            if (s.isDefault) stringResource(R.string.platea_tag_default) else null,
            if (s.isExternal) stringResource(R.string.platea_tag_external) else null,
        ),
    selected = selected,
)

@Composable
private fun TrackColumn(
    title: String,
    options: List<TrackOption>,
    focusFirstSelected: Boolean,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    Column(modifier) {
        Text(title.uppercase(), style = PlateaType.Kicker, color = PlateaColors.TextMeta, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(options, key = { it.index }) { option ->
                val requester = remember { FocusRequester() }
                if (focusFirstSelected && option.selected) {
                    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
                }
                TrackRow(option, Modifier.focusRequester(requester)) { onPick(option.index) }
            }
        }
    }
}

@Composable
private fun TrackRow(
    option: TrackOption,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.ButtonRadius)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = PlateaColors.Text,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = if (option.selected) Border(BorderStroke(1.dp, PlateaColors.Gold.copy(alpha = 0.6f))) else Border.None,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            // Selected marker
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (option.selected) PlateaColors.Gold else Color.Transparent)
                    .border(BorderStroke(1.dp, if (option.selected) PlateaColors.Gold else PlateaColors.Border), CircleShape),
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(option.title, style = PlateaType.CardTitle.copy(fontWeight = if (option.selected) FontWeight.Bold else FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                option.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = PlateaType.CardSub, color = if (focused) PlateaColors.Ground.copy(alpha = 0.7f) else PlateaColors.TextMeta, maxLines = 1)
                }
            }
            option.tags.forEach { tag ->
                Spacer(Modifier.width(5.dp))
                Text(
                    tag,
                    style = PlateaType.Badge,
                    color = if (focused) PlateaColors.Ground else PlateaColors.TextSecondary,
                    modifier =
                        Modifier
                            .border(BorderStroke(0.75.dp, if (focused) PlateaColors.Ground.copy(alpha = 0.4f) else PlateaColors.Border), RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}
