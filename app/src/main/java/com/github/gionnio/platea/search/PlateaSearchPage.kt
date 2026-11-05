package com.github.gionnio.platea.search

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.home.TopBar
import com.github.gionnio.platea.library.PosterTile
import com.github.gionnio.platea.ui.FocusMemory
import com.github.gionnio.platea.ui.PlateaError
import com.github.gionnio.platea.ui.restoreFocus
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType

private const val KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private val KeySize = 32.dp
private val KeyGap = 4.dp

/**
 * Platea search: an on-screen keyboard on the left (no system keyboard covering the screen), results on the
 * right updated while typing, one row per kind. A physical keyboard types into it too.
 */
@Composable
fun PlateaSearchPage(
    initialQuery: String,
    modifier: Modifier = Modifier,
    viewModel: PlateaSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val memory = viewModel.focusMemory
    LaunchedEffect(Unit) { if (initialQuery.isNotBlank() && state.query.isEmpty()) viewModel.setQuery(initialQuery) }
    var showVoice by remember { mutableStateOf(false) }
    if (showVoice) VoiceSearch(viewModel.voice, viewModel::setQuery, onDismiss = { showVoice = false })

    Column(
        modifier
            .fillMaxSize()
            .background(PlateaColors.Ground)
            .onPreviewKeyEvent { event ->
                // Typing from a keyboard (or a remote app that sends characters)
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val char = event.nativeKeyEvent.unicodeChar
                when {
                    event.key == Key.Backspace || event.key == Key.Delete -> {
                        viewModel.setQuery(state.query.dropLast(1))
                        true
                    }
                    char != 0 && (char.toChar().isLetterOrDigit() || (char.toChar() == ' ' && event.key == Key.Spacebar)) -> {
                        viewModel.setQuery(state.query + char.toChar())
                        true
                    }
                    else -> false
                }
            },
    ) {
        TopBar(
            libraries = state.libraries,
            userInitial = state.userInitial,
            onNavigate = viewModel::navigate,
            onHome = viewModel::home,
            searchSelected = true,
        )
        Row(Modifier.fillMaxSize().padding(top = 18.dp)) {
            Keyboard(
                query = state.query,
                onQuery = viewModel::setQuery,
                // Only on devices with speech recognition (not on Fire TV)
                onVoice = if (viewModel.voice.isAvailable) ({ showVoice = true }) else null,
                requestInitialFocus = !viewModel.initialFocusDone && !memory.restoring,
                onInitialFocus = { viewModel.initialFocusDone = true },
                modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal),
            )
            Spacer(Modifier.width(28.dp))
            Results(state, viewModel, memory)
        }
    }
}

@Composable
private fun Keyboard(
    query: String,
    onQuery: (String) -> Unit,
    onVoice: (() -> Unit)?,
    requestInitialFocus: Boolean,
    onInitialFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (requestInitialFocus) runCatching { first.requestFocus() }
        onInitialFocus()
    }
    val width = KeySize * 6 + KeyGap * 5
    Column(modifier.width(width), verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        // What has been typed
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(PlateaDimens.ButtonRadius))
                    .background(PlateaColors.Surface)
                    .border(BorderStroke(1.dp, PlateaColors.Avatar), RoundedCornerShape(PlateaDimens.ButtonRadius))
                    .padding(horizontal = 10.dp),
        ) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.platea_search_hint), style = PlateaType.Body, color = PlateaColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(query, style = PlateaType.RowTitle, color = PlateaColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Box(Modifier.padding(start = 2.dp).width(1.5.dp).height(15.dp).background(PlateaColors.Gold))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        KEYS.chunked(6).forEachIndexed { r, rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                rowKeys.forEachIndexed { c, key ->
                    KeyButton(
                        label = key.toString(),
                        modifier = Modifier.size(KeySize).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                    ) { onQuery(query + key) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            KeyButton(stringResource(R.string.platea_key_space), Modifier.weight(1.2f).height(KeySize)) { if (query.isNotEmpty()) onQuery("$query ") }
            KeyButton("⌫", Modifier.weight(0.8f).height(KeySize)) { onQuery(query.dropLast(1)) }
            KeyButton(stringResource(R.string.platea_key_clear), Modifier.weight(1.2f).height(KeySize)) { onQuery("") }
        }
        onVoice?.let { KeyButton(stringResource(R.string.platea_key_voice), Modifier.fillMaxWidth().height(KeySize), onClick = it) }
    }
}

@Composable
private fun KeyButton(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = PlateaColors.Surface,
                contentColor = PlateaColors.Text,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = PlateaType.Button.copy(fontSize = if (label.length > 1) 9.5.sp else 13.sp), maxLines = 1)
        }
    }
}

@Composable
private fun Results(
    state: PlateaSearchState,
    viewModel: PlateaSearchViewModel,
    memory: FocusMemory,
) {
    val results = state.results
    when {
        state.query.isBlank() ->
            Text(stringResource(R.string.platea_search_empty), style = PlateaType.Body, color = PlateaColors.TextMeta, modifier = Modifier.padding(top = 10.dp))
        state.error != null -> PlateaError(state.error) { viewModel.retry() }
        results == null ->
            Text(stringResource(R.string.platea_searching), style = PlateaType.Body, color = PlateaColors.TextMeta, modifier = Modifier.padding(top = 10.dp))
        results.isEmpty() ->
            Text(stringResource(R.string.platea_search_none, state.query.trim()), style = PlateaType.Body, color = PlateaColors.TextMeta, modifier = Modifier.padding(top = 10.dp))
        else ->
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = PlateaDimens.SafeTop),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results.entries.toList(), key = { it.key }) { (group, list) ->
                    Column {
                        Text(
                            stringResource(
                                when (group) {
                                    SearchGroup.MOVIES -> R.string.platea_search_movies
                                    SearchGroup.SERIES -> R.string.platea_search_series
                                    SearchGroup.EPISODES -> R.string.platea_search_episodes
                                    SearchGroup.PEOPLE -> R.string.platea_search_people
                                    SearchGroup.COLLECTIONS -> R.string.platea_filter_collections
                                },
                            ),
                            style = PlateaType.RowTitle,
                            color = PlateaColors.Text,
                            modifier = Modifier.padding(bottom = 9.dp),
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(start = 4.dp, end = PlateaDimens.SafeHorizontal, top = 4.dp, bottom = 2.dp),
                        ) {
                            items(list.distinctBy { it.id }, key = { it.id }) { item ->
                                val restore = Modifier.restoreFocus(memory, "res:${item.id}")
                                val onClick = {
                                    memory.remember("res:${item.id}")
                                    viewModel.open(item)
                                }
                                when (group) {
                                    SearchGroup.EPISODES -> EpisodeResult(item, viewModel.still(item), restore, onClick)
                                    SearchGroup.PEOPLE -> PersonResult(item, viewModel.poster(item), restore, onClick)
                                    else ->
                                        Box(Modifier.width(96.dp)) {
                                            PosterTile(
                                                item = item,
                                                imageUrl = viewModel.poster(item),
                                                isSeries = group == SearchGroup.SERIES,
                                                focusModifier = restore,
                                                onLongClick = {},
                                                onClick = onClick,
                                            )
                                        }
                                }
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun EpisodeResult(
    item: BaseItem,
    imageUrl: String?,
    focusModifier: Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(Modifier.width(170.dp)) {
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.CardRadius)),
            colors = ClickableSurfaceDefaults.colors(containerColor = PlateaColors.Placeholder, focusedContainerColor = PlateaColors.Placeholder),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(PlateaDimens.FocusBorder, PlateaColors.Text), shape = RoundedCornerShape(PlateaDimens.CardRadius))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
            modifier = focusModifier.width(170.dp).height(96.dp),
        ) {
            AsyncImage(model = imageUrl, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            item.name.orEmpty(),
            style = if (focused) PlateaType.CardSub.copy(fontWeight = FontWeight.Bold) else PlateaType.CardSub,
            color = if (focused) Color.White else PlateaColors.TextCard,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
        Text(
            listOfNotNull(item.data.seriesName, "S${item.data.parentIndexNumber ?: 0}:E${item.data.indexNumber ?: 0}").joinToString(" · "),
            style = PlateaType.CardSub.copy(fontSize = 8.5.sp),
            color = PlateaColors.TextMeta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PersonResult(
    item: BaseItem,
    imageUrl: String?,
    focusModifier: Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = PlateaColors.Placeholder, focusedContainerColor = PlateaColors.Placeholder),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(PlateaDimens.FocusBorder, PlateaColors.Text), shape = CircleShape)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            modifier = focusModifier.size(64.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(item.name.orEmpty().take(1), style = PlateaType.RowTitle, color = PlateaColors.TextTertiary)
                AsyncImage(model = imageUrl, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            item.name.orEmpty(),
            style = PlateaType.CardSub.copy(fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold),
            color = if (focused) Color.White else PlateaColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}
