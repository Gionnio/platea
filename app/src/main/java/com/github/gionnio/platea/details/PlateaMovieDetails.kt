package com.github.gionnio.platea.details

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.ChosenStreams
import com.github.gionnio.platea.data.model.Person
import com.github.gionnio.platea.services.TrailerService
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.home.HeroButton
import com.github.gionnio.platea.home.MetaLine
import com.github.gionnio.platea.ui.PlateaError
import com.github.gionnio.platea.ui.PlateaLoading
import com.github.gionnio.platea.ui.components.ExpandableOverview
import com.github.gionnio.platea.ui.components.WatchedToggle
import com.github.gionnio.platea.ui.restoreFocus
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType
import java.util.Locale
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * Platea movie page: full-screen backdrop, logo, actions, technical sheet with the real playback decision,
 * cast. Built on Wholphin's [MovieViewModel] (tracks, trailers, people).
 */
@Composable
fun PlateaMovieDetails(
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: PlateaMovieViewModel =
        hiltViewModel<PlateaMovieViewModel, PlateaMovieViewModel.Factory>(
            creationCallback = { it.create(destination.itemId) },
        ),
    details: PlateaDetailsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val movie = state.movie
    val chosen = state.chosen
    val prediction by details.prediction.collectAsState()
    LaunchedEffect(movie?.id, chosen?.source?.id, chosen?.audioStream?.index, chosen?.subtitleStream?.index) {
        if (movie != null) details.predict(movie.id, chosen)
    }

    // Platea: every return to the page reads the movie again (as the series page does). A page kept in the back
    // stack otherwise shows the tracks of a file that was remuxed meanwhile.
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) viewModel.load(everything = false)
        firstResume = false
        onPauseOrDispose { }
    }

    var showTracks by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(PlateaColors.Ground)) {
        if (movie == null) {
            if (state.loading) PlateaLoading() else PlateaError(state.error) { viewModel.load(everything = true) }
            return@Box
        }
        val playFocus = remember { FocusRequester() }
        LaunchedEffect(movie.id) {
            if (!details.focusMemory.restoring || details.focusMemory.key == "play") {
                runCatching { playFocus.requestFocus() }
                details.focusMemory.key = null
            }
        }
        val memory = details.focusMemory

        // Backdrop, full screen
        AsyncImage(
            model = details.backdrop(movie),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to PlateaColors.Ground,
                    0.3f to PlateaColors.Ground.copy(alpha = 0.92f),
                    0.62f to PlateaColors.Ground.copy(alpha = 0.35f),
                    1f to PlateaColors.Ground.copy(alpha = 0.1f),
                ),
            ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(210.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.85f to PlateaColors.Ground)),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal, top = 48.dp).width(450.dp),
        ) {
            var logoFailed by remember(movie.id) { mutableStateOf(false) }
            Box(Modifier.height(65.dp), contentAlignment = Alignment.CenterStart) {
                if (!logoFailed) {
                    AsyncImage(
                        model = details.logo(movie),
                        contentDescription = movie.name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        onError = { logoFailed = true },
                        modifier = Modifier.widthIn(max = 320.dp).fillMaxHeight(),
                    )
                } else {
                    Text(movie.name.orEmpty(), style = PlateaType.HeroTitle, color = PlateaColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaLine(null, details.meta(movie))
                movie.data.communityRating?.let {
                    Text("  •  ", style = PlateaType.Meta, color = PlateaColors.Separator)
                    Icon(Icons.Filled.Star, contentDescription = null, tint = PlateaColors.Gold, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(String.format(Locale.ITALY, "%.1f", it), style = PlateaType.Meta, color = PlateaColors.TextSecondary)
                }
            }
            movie.data.overview?.let {
                ExpandableOverview(it, 420.dp, style = PlateaType.Body.copy(fontSize = PlateaType.Body.fontSize * 1.04f))
            }

            // Actions
            // The buttons may be wider than the text column: let the row extend to the right instead of squeezing them
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 3.dp).wrapContentWidth(align = Alignment.Start, unbounded = true),
            ) {
                val resumeMs = movie.resumeMs
                if (resumeMs > 0) {
                    HeroButton(
                        label = stringResource(R.string.platea_resume_left, details.timeLeft(movie)),
                        primary = true,
                        modifier = Modifier.focusRequester(playFocus),
                    ) {
                        memory.remember("play")
                        viewModel.navigateTo(Destination.Playback(movie.id, resumeMs))
                    }
                    HeroButton(stringResource(R.string.platea_from_start), primary = false, modifier = Modifier.restoreFocus(memory, "start")) {
                        memory.remember("start")
                        viewModel.navigateTo(Destination.Playback(movie.id, 0))
                    }
                } else {
                    HeroButton(stringResource(R.string.platea_play), primary = true, modifier = Modifier.focusRequester(playFocus)) {
                        memory.remember("play")
                        viewModel.navigateTo(Destination.Playback(movie.id, 0))
                    }
                }
                state.trailers.firstOrNull()?.let { trailer ->
                    HeroButton(stringResource(R.string.platea_trailer), primary = false, modifier = Modifier.restoreFocus(memory, "trailer")) {
                        memory.remember("trailer")
                        TrailerService.onClick(context, trailer, viewModel::navigateTo)
                    }
                }
                HeroButton(stringResource(R.string.platea_audio_subtitles), primary = false) {
                    showTracks = true
                }
                WatchedToggle(movie.played, stringResource(R.string.platea_watched)) { viewModel.setWatched(it) }
            }

            TechSheet(chosen, prediction, details, Modifier.padding(top = 6.dp))
        }

        // Cast
        if (state.people.isNotEmpty()) {
            Column(Modifier.align(Alignment.BottomStart).padding(bottom = 22.dp)) {
                Text(
                    stringResource(R.string.platea_cast),
                    style = PlateaType.RowTitle.copy(fontSize = PlateaType.RowTitle.fontSize * 0.93f),
                    color = PlateaColors.Text,
                    modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal, bottom = 9.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = PlateaDimens.SafeHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(state.people.distinctBy { it.id }.take(20), key = { it.id }) { person ->
                        PersonCard(person, Modifier.restoreFocus(memory, "person:${person.id}")) {
                            memory.remember("person:${person.id}")
                            viewModel.navigateTo(Destination.MediaItem(person.id, BaseItemKind.PERSON))
                        }
                    }
                }
            }
        }
    }

    if (showTracks && movie != null && chosen != null) {
        TrackPanel(
            chosen = chosen,
            prediction = prediction,
            predictionText = details::predictionText,
            onSelect = { type, index -> viewModel.saveTrackSelection(type, index) },
            onDismiss = { showTracks = false },
        )
    }
}

/** What the file really is, and how Platea will play it (server decision with Platea's profile). */
@Composable
private fun TechSheet(
    chosen: ChosenStreams?,
    prediction: PlaybackPrediction?,
    details: PlateaDetailsViewModel,
    modifier: Modifier = Modifier,
) {
    if (chosen == null) return
    Column(
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier =
            modifier
                .width(420.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PlateaColors.Surface.copy(alpha = 0.82f))
                .border(BorderStroke(0.5.dp, PlateaColors.Avatar), RoundedCornerShape(8.dp))
                .padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
            Text(stringResource(R.string.platea_tech_sheet), style = PlateaType.Kicker, color = PlateaColors.TextMeta)
            Spacer(Modifier.weight(1f))
            prediction?.let { PredictionPill(it, details.predictionText(it)) }
        }
        // Two columns, as in the concept
        details.techCells(chosen).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                pair.forEach { cell ->
                    Row(Modifier.weight(1f)) {
                        Text(cell.label, style = PlateaType.Meta.copy(fontSize = 10.5.sp), color = PlateaColors.TextTertiary, modifier = Modifier.width(60.dp))
                        Text(
                            cell.value,
                            style = PlateaType.Meta.copy(fontSize = 10.5.sp),
                            color = if (cell.muted) PlateaColors.TextTertiary else PlateaColors.Text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PredictionPill(
    p: PlaybackPrediction,
    text: String,
) {
    val color =
        when (p.method) {
            PlaybackPrediction.Method.DIRECT_PLAY -> PlateaColors.OkGreen
            PlaybackPrediction.Method.REMUX -> PlateaColors.Gold
            PlaybackPrediction.Method.TRANSCODE -> Color(0xFFF87171)
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (p.method == PlaybackPrediction.Method.DIRECT_PLAY) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = color, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = PlateaType.Chip.copy(fontWeight = FontWeight.Bold), color = color, maxLines = 1)
    }
}

@Composable
private fun PersonCard(
    person: Person,
    focusModifier: Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = PlateaColors.Placeholder, focusedContainerColor = PlateaColors.Placeholder),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(PlateaDimens.FocusBorder, PlateaColors.Text), shape = CircleShape)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            modifier = focusModifier.size(56.dp),
        ) {
            AsyncImage(model = person.imageUrl, contentDescription = person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(5.dp))
        Text(
            person.name.orEmpty(),
            style = PlateaType.CardSub.copy(fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold),
            color = if (focused) Color.White else PlateaColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            person.role.orEmpty(),
            style = PlateaType.CardSub.copy(fontSize = PlateaType.CardSub.fontSize * 0.9f),
            color = PlateaColors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
