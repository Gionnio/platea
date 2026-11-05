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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.home.Badge
import com.github.gionnio.platea.home.HeroButton
import com.github.gionnio.platea.home.MetaLine
import com.github.gionnio.platea.home.ThemeIcon
import com.github.gionnio.platea.ui.PlateaError
import com.github.gionnio.platea.ui.PlateaLoading
import com.github.gionnio.platea.ui.components.ActionMenu
import com.github.gionnio.platea.ui.components.ExpandableOverview
import com.github.gionnio.platea.ui.components.MenuAction
import com.github.gionnio.platea.ui.components.WatchedToggle
import com.github.gionnio.platea.ui.restoreFocus
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType

private val EpisodeWidth = 208.dp
private val EpisodeHeight = 117.dp

/**
 * Platea series page: backdrop, logo, resume or play the next episode (or start it over), season tabs and
 * the episodes of the selected season, theme song while the page is open.
 */
@Composable
fun PlateaSeriesDetails(
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: PlateaSeriesViewModel =
        hiltViewModel<PlateaSeriesViewModel, PlateaSeriesViewModel.Factory>(
            creationCallback = { it.create(destination.itemId, destination.initialEpisodeId) },
        ),
    details: PlateaDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val themePlaying by viewModel.themePlaying.collectAsState()
    val prediction by details.prediction.collectAsState()
    val memory = viewModel.focusMemory

    // Coming back from the player: progress and watched marks have changed
    var resumed by remember { mutableStateOf(false) }
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) viewModel.load()
        firstResume = false
        resumed = true
        onPauseOrDispose {
            resumed = false
            viewModel.stopTheme()
        }
    }
    // The theme plays while the page is open (it is resolved after the series loads)
    LaunchedEffect(state.theme, resumed) { if (resumed && state.theme != null) viewModel.playTheme() }

    val next = state.next
    LaunchedEffect(next?.id, state.nextChosen?.audioStream?.index, state.nextChosen?.subtitleStream?.index) {
        if (next != null) details.predict(next.id, state.nextChosen)
    }

    var menuFor by remember { mutableStateOf<BaseItem?>(null) }

    Box(modifier.fillMaxSize().background(PlateaColors.Ground)) {
        val series = state.series
        if (series == null) {
            if (state.error != null) PlateaError(state.error) { viewModel.load(firstTime = true) } else PlateaLoading()
            return@Box
        }

        // Backdrop on the top right, faded into the ground on the left and at the bottom (as in the concept)
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.78f).fillMaxHeight(0.7f)) {
            AsyncImage(
                model = viewModel.backdrop(series),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to PlateaColors.Ground,
                        0.25f to PlateaColors.Ground.copy(alpha = 0.8f),
                        0.65f to Color.Transparent,
                    ),
                ),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to PlateaColors.Ground)))
        }

        // Theme song chip
        if (state.theme != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = PlateaDimens.SafeTop, end = PlateaDimens.SafeHorizontal)
                        .clip(RoundedCornerShape(50))
                        .background(PlateaColors.Surface.copy(alpha = 0.85f))
                        .border(BorderStroke(0.5.dp, PlateaColors.Avatar), RoundedCornerShape(50))
                        .padding(start = 1.dp, end = 11.dp, top = 6.dp, bottom = 6.dp),
            ) {
                ThemeIcon(themePlaying)
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.platea_theme), style = PlateaType.Chip.copy(fontWeight = FontWeight.SemiBold), color = PlateaColors.Text)
            }
        }

        Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
            Hero(series, next, state, prediction, details, viewModel, memory)
            Spacer(Modifier.height(22.dp))
            // Up from the episodes goes to the season tabs (not past them to the buttons)
            val seasonsFocus = remember { FocusRequester() }
            SeasonTabs(state, viewModel, memory, Modifier.focusRequester(seasonsFocus))
            Spacer(Modifier.height(14.dp))
            Episodes(state, viewModel, memory, upTo = seasonsFocus, onLongClick = { menuFor = it })
        }
    }

    // Long press on an episode
    menuFor?.let { ep ->
        val resume = ep.resumeMs > 0 && !ep.played
        ActionMenu(
            title = "${ep.data.indexNumber ?: ""}. ${ep.name.orEmpty()}",
            subtitle = viewModel.code(ep) + (if (resume) " · " + stringResource(R.string.platea_time_left, viewModel.timeLeft(ep)) else ""),
            actions =
                buildList {
                    if (resume) {
                        add(MenuAction(stringResource(R.string.platea_resume_left, viewModel.timeLeft(ep)), play = true) { menuFor = null; memory.remember("ep:${ep.id}"); viewModel.play(ep) })
                        add(MenuAction(stringResource(R.string.platea_start_over), play = true) { menuFor = null; memory.remember("ep:${ep.id}"); viewModel.play(ep, fromStart = true) })
                    } else {
                        add(MenuAction(stringResource(R.string.platea_play), play = true) { menuFor = null; memory.remember("ep:${ep.id}"); viewModel.play(ep, fromStart = true) })
                    }
                    add(MenuAction(stringResource(R.string.platea_audio_subtitles)) { menuFor = null; viewModel.openTracks(ep) })
                    add(
                        MenuAction(stringResource(if (ep.played) R.string.platea_mark_unwatched else R.string.platea_mark_watched)) {
                            menuFor = null
                            viewModel.setEpisodeWatched(ep, !ep.played)
                        },
                    )
                },
            onDismiss = { menuFor = null },
        )
    }

    val tracksItem = state.tracksItem
    val tracksChosen = state.tracksChosen
    if (tracksItem != null && tracksChosen != null) {
        TrackPanel(
            chosen = tracksChosen,
            prediction = if (tracksItem.id == next?.id) prediction else null,
            predictionText = details::predictionText,
            onSelect = { type, index -> viewModel.saveTrackSelection(type, index) },
            onDismiss = viewModel::closeTracks,
        )
    }
}

@Composable
private fun Hero(
    series: BaseItem,
    next: BaseItem?,
    state: PlateaSeriesState,
    prediction: PlaybackPrediction?,
    details: PlateaDetailsViewModel,
    viewModel: PlateaSeriesViewModel,
    memory: com.github.gionnio.platea.ui.FocusMemory,
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(next?.id) {
        if (next != null && (!memory.restoring || memory.key == "play")) {
            runCatching { playFocus.requestFocus() }
            if (memory.key == "play") memory.key = null
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal).width(450.dp),
    ) {
        var logoFailed by remember(series.id) { mutableStateOf(false) }
        Box(Modifier.height(PlateaDimens.HeroLogoHeight), contentAlignment = Alignment.CenterStart) {
            if (!logoFailed) {
                AsyncImage(
                    model = viewModel.logo(series),
                    contentDescription = series.name,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    onError = { logoFailed = true },
                    modifier = Modifier.widthIn(max = 300.dp).fillMaxHeight(),
                )
            } else {
                Text(series.name.orEmpty(), style = PlateaType.HeroTitle, color = PlateaColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        MetaLine(null, state.meta)
        if (state.badges.isNotEmpty() || prediction != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                state.badges.forEach { Badge(it) }
                prediction?.let {
                    Spacer(Modifier.width(3.dp))
                    PredictionPill(it, details.predictionText(it))
                }
            }
        }
        series.data.overview?.let { ExpandableOverview(it, 420.dp) }
        // The buttons may be wider than the text column: let the row extend to the right instead of squeezing them
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 3.dp).wrapContentWidth(align = Alignment.Start, unbounded = true),
            ) {
            if (next != null) {
                val code = viewModel.code(next)
                if (next.resumeMs > 0 && !next.played) {
                    HeroButton(stringResource(R.string.platea_resume_episode, code), primary = true, modifier = Modifier.focusRequester(playFocus)) {
                        memory.remember("play")
                        viewModel.play(next)
                    }
                    HeroButton(stringResource(R.string.platea_from_start), primary = false, modifier = Modifier.restoreFocus(memory, "start")) {
                        memory.remember("start")
                        viewModel.play(next, fromStart = true)
                    }
                } else {
                    HeroButton(stringResource(R.string.platea_play_episode, code), primary = true, modifier = Modifier.focusRequester(playFocus)) {
                        memory.remember("play")
                        viewModel.play(next, fromStart = true)
                    }
                }
                HeroButton(stringResource(R.string.platea_audio_subtitles), primary = false) { viewModel.openTracks(next) }
            }
            // Watched refers to the episode on the play button, not to the whole series
            // (whole seasons: long press on a season tab)
            if (next != null) {
                androidx.compose.runtime.key(next.id) {
                    WatchedToggle(next.played, stringResource(R.string.platea_watched)) { viewModel.setEpisodeWatched(next, it) }
                }
            }
        }
    }
}

/** Season pills: moving onto a season shows its episodes. */
@Composable
private fun SeasonTabs(
    state: PlateaSeriesState,
    viewModel: PlateaSeriesViewModel,
    memory: com.github.gionnio.platea.ui.FocusMemory,
    modifier: Modifier,
) {
    var menuFor by remember { mutableStateOf<SeasonUi?>(null) }
    menuFor?.let { season ->
        ActionMenu(
            title = season.label,
            subtitle = null,
            actions =
                listOf(
                    MenuAction(stringResource(if (season.played) R.string.platea_season_mark_unwatched else R.string.platea_season_mark_watched)) {
                        menuFor = null
                        viewModel.setSeasonWatched(season, !season.played)
                    },
                ),
            onDismiss = { menuFor = null },
        )
    }
    if (state.seasons.size <= 1 && state.seasons.firstOrNull()?.number != 0) {
        // A single season needs no tabs; keep the space so the episodes stay where the concept puts them
        state.seasons.firstOrNull()?.let {
            Text(it.label, style = PlateaType.RowTitle, color = PlateaColors.Text, modifier = modifier.padding(start = PlateaDimens.SafeHorizontal))
        }
        return
    }
    val selected = remember { FocusRequester() }
    LazyRow(
        contentPadding = PaddingValues(horizontal = PlateaDimens.SafeHorizontal),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier.focusRestorer(selected),
    ) {
        itemsIndexed(state.seasons.distinctBy { it.id }, key = { _, s -> s.id }) { i, season ->
            val isSelected = i == state.selectedSeason
            Surface(
                onClick = { viewModel.selectSeason(i) },
                onLongClick = { menuFor = season },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors =
                    ClickableSurfaceDefaults.colors(
                        // Selected season: gold, white only under the cursor
                        containerColor = Color.Transparent,
                        contentColor = if (isSelected) PlateaColors.Gold else PlateaColors.TextSecondary,
                        focusedContainerColor = PlateaColors.Text,
                        focusedContentColor = PlateaColors.Ground,
                    ),
                border =
                    ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(1.dp, if (isSelected) PlateaColors.Gold.copy(alpha = 0.7f) else PlateaColors.Avatar), shape = RoundedCornerShape(50)),
                        focusedBorder = Border(BorderStroke(2.dp, PlateaColors.Text.copy(alpha = 0.35f)), inset = (-2).dp, shape = RoundedCornerShape(50)),
                    ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
                modifier =
                    Modifier
                        .height(26.dp)
                        .then(if (isSelected) Modifier.focusRequester(selected) else Modifier)
                        .restoreFocus(memory, "season:${season.id}")
                        .onFocusChanged { if (it.isFocused) viewModel.selectSeason(i) },
            ) {
                Box(Modifier.fillMaxHeight().padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
                    Text(season.label, style = PlateaType.Nav.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun Episodes(
    state: PlateaSeriesState,
    viewModel: PlateaSeriesViewModel,
    memory: com.github.gionnio.platea.ui.FocusMemory,
    upTo: FocusRequester,
    onLongClick: (BaseItem) -> Unit,
) {
    val episodes = state.episodes
    if (episodes == null) {
        Box(Modifier.height(EpisodeHeight))
        return
    }
    if (episodes.isEmpty()) {
        Text(stringResource(R.string.platea_no_episodes), style = PlateaType.Body, color = PlateaColors.TextMeta, modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal))
        return
    }
    val season = state.seasons.getOrNull(state.selectedSeason)
    // Entering the row lands on the episode to resume (or the first unwatched one), shown in view
    val startIndex =
        episodes.indexOfFirst { it.id == state.next?.id }.takeIf { it >= 0 }
            ?: episodes.indexOfFirst { !it.played }.takeIf { it >= 0 }
            ?: 0
    val listState = rememberLazyListState()
    val entry = remember(season?.id) { FocusRequester() }
    // Coming back from the player the row must land on the episode that was played, not on the default one:
    // the focusRestorer fallback follows it
    val returning = remember(season?.id) { memory.key?.takeIf { it.startsWith("ep:") }?.removePrefix("ep:") }
    val returningIndex = episodes.indexOfFirst { it.id.toString() == returning }
    val entryIndex = returningIndex.takeIf { it >= 0 } ?: startIndex
    LaunchedEffect(season?.id) {
        // The episode to land on must be composed to take the focus: scroll to it first (opened on an episode
        // from "Vai all'episodio" the row starts at the first one)
        if (!memory.restoring) {
            listState.scrollToItem(startIndex)
        } else if (returningIndex >= 0) {
            listState.scrollToItem(returningIndex)
        }
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = PlateaDimens.SafeHorizontal, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(PlateaDimens.CardGap),
        modifier =
            Modifier
                .focusRestorer(entry)
                .focusProperties { if (state.seasons.size > 1 || state.seasons.firstOrNull()?.number == 0) up = upTo },
    ) {
        itemsIndexed(episodes.distinctBy { it.id }, key = { _, e -> e.id }) { i, ep ->
            EpisodeCard(
                episode = ep,
                viewModel = viewModel,
                focusModifier =
                    Modifier
                        .then(if (i == entryIndex) Modifier.focusRequester(entry) else Modifier)
                        .restoreFocus(memory, "ep:${ep.id}"),
                onLongClick = { onLongClick(ep) },
            ) {
                memory.remember("ep:${ep.id}")
                viewModel.play(ep, fromStart = ep.played)
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: BaseItem,
    viewModel: PlateaSeriesViewModel,
    focusModifier: Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(Modifier.width(EpisodeWidth)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.CardRadius)),
            colors = ClickableSurfaceDefaults.colors(containerColor = PlateaColors.Placeholder, focusedContainerColor = PlateaColors.Placeholder),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(PlateaDimens.FocusBorder, PlateaColors.Text), shape = RoundedCornerShape(PlateaDimens.CardRadius))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
            modifier = focusModifier.width(EpisodeWidth).height(EpisodeHeight),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = viewModel.still(episode), contentDescription = episode.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (episode.played) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(PlateaColors.Ground.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = PlateaColors.Text, modifier = Modifier.size(11.dp))
                    }
                }
                viewModel.progress(episode)?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.18f))) {
                        Box(
                            Modifier
                                .fillMaxWidth(p.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(if (episode.played) PlateaColors.Separator else PlateaColors.Gold),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(if (focused) 9.dp else 6.dp))
        Text(
            "${episode.data.indexNumber ?: ""}. ${episode.name.orEmpty()}",
            style = if (focused) PlateaType.CardTitle.copy(fontWeight = FontWeight.Bold) else PlateaType.CardTitle,
            color = if (focused) Color.White else PlateaColors.TextCard,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(viewModel.episodeMeta(episode), style = PlateaType.CardSub, color = PlateaColors.TextMeta, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        if (focused) {
            episode.data.overview?.let {
                Text(it, style = PlateaType.CardSub, color = PlateaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
