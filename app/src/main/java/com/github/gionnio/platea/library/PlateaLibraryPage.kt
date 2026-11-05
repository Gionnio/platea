package com.github.gionnio.platea.library

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import com.github.gionnio.platea.home.TopBar
import com.github.gionnio.platea.ui.PlateaError
import com.github.gionnio.platea.ui.components.ActionMenu
import com.github.gionnio.platea.ui.components.MenuAction
import com.github.gionnio.platea.ui.restoreFocus
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType

/** Platea movie / TV library: same top bar as the home, filters, genre, sort and a poster grid. */
@Composable
fun PlateaLibraryPage(
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: PlateaLibraryViewModel =
        hiltViewModel<PlateaLibraryViewModel, PlateaLibraryViewModel.Factory>(
            creationCallback = { it.create(destination.itemId, destination.collectionType) },
        ),
) {
    val state by viewModel.state.collectAsState()
    val memory = viewModel.focusMemory
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) viewModel.refreshVisible()
        firstResume = false
        onPauseOrDispose { }
    }
    var menuFor by remember { mutableStateOf<BaseItem?>(null) }
    var showGenres by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(PlateaColors.Ground)) {
        TopBar(
            libraries = state.libraries,
            userInitial = state.userInitial,
            onNavigate = viewModel::navigate,
            selectedId = viewModel.libraryId,
            onHome = viewModel::home,
        )
        // Title and count
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal, top = 16.dp)) {
            Text(state.title, style = PlateaType.HeroTitle.copy(fontSize = 22.sp), color = PlateaColors.Text)
            state.total?.let {
                Spacer(Modifier.width(10.dp))
                Text(viewModel.countLabel(it), style = PlateaType.Meta, color = PlateaColors.TextMeta, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        // Filters, genre, sort
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = PlateaDimens.SafeHorizontal, end = PlateaDimens.SafeHorizontal, top = 10.dp, bottom = 12.dp),
        ) {
            // Folder libraries show what is on disk: no filters. Collections: sort only.
            if (!viewModel.isFolders) {
                LibraryFilter.entries
                    .filter { f ->
                        when {
                            viewModel.isCollections -> false
                            viewModel.isSeries -> f != LibraryFilter.IN_PROGRESS && f != LibraryFilter.COLLECTIONS
                            else -> true
                        }
                    }.forEach { f -> Chip(stringResource(f.label), selected = state.filter == f) { viewModel.setFilter(f) } }
                Spacer(Modifier.weight(1f))
                if (!viewModel.isCollections && state.filter != LibraryFilter.COLLECTIONS) {
                    Chip(state.genre ?: stringResource(R.string.platea_genre_all), selected = state.genre != null, trailing = "▾") { showGenres = true }
                }
                Chip(stringResource(R.string.platea_sort, stringResource(state.sort.label)), selected = false) { viewModel.nextSort() }
            }
        }
        Grid(state, viewModel, memory, onLongClick = { menuFor = it })
    }

    if (showGenres) {
        GenrePicker(state.genres, state.genre, onPick = {
            showGenres = false
            viewModel.setGenre(it)
        }, onDismiss = { showGenres = false })
    }

    menuFor?.let { item ->
        val resume = item.resumeMs > 0 && !item.played
        ActionMenu(
            title = item.name.orEmpty(),
            subtitle = item.data.productionYear?.toString(),
            actions =
                buildList {
                    if (!viewModel.isSeries) {
                        if (resume) {
                            add(MenuAction(stringResource(R.string.platea_resume), play = true) { menuFor = null; memory.remember("item:${item.id}"); viewModel.play(item, fromStart = false) })
                            add(MenuAction(stringResource(R.string.platea_start_over), play = true) { menuFor = null; memory.remember("item:${item.id}"); viewModel.play(item, fromStart = true) })
                        } else {
                            add(MenuAction(stringResource(R.string.platea_play), play = true) { menuFor = null; memory.remember("item:${item.id}"); viewModel.play(item, fromStart = true) })
                        }
                    }
                    add(MenuAction(stringResource(if (viewModel.isSeries) R.string.platea_go_to_series else R.string.platea_go_to_details)) { menuFor = null; memory.remember("item:${item.id}"); viewModel.open(item) })
                    add(MenuAction(stringResource(if (item.played) R.string.platea_mark_unwatched else R.string.platea_mark_watched)) { menuFor = null; viewModel.setWatched(item, !item.played) })
                },
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun Grid(
    state: PlateaLibraryState,
    viewModel: PlateaLibraryViewModel,
    memory: com.github.gionnio.platea.ui.FocusMemory,
    onLongClick: (BaseItem) -> Unit,
) {
    val gridState = viewModel.gridState
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { viewModel.onVisible(it) }
    }
    if (state.items.isEmpty()) {
        if (state.error != null) {
            PlateaError(state.error) { viewModel.retry() }
        } else if (!state.loading) {
            Text(stringResource(R.string.platea_library_empty), style = PlateaType.Body, color = PlateaColors.TextMeta, modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal, top = 20.dp))
        }
        return
    }
    val first = remember { FocusRequester() }
    // Opening the page the cursor goes to the posters, not to the top bar
    LaunchedEffect(Unit) {
        if (!viewModel.initialFocusDone && !memory.restoring) {
            // The first poster is attached only after the grid's first layout
            for (attempt in 1..10) {
                if (runCatching { first.requestFocus() }.isSuccess) break
                kotlinx.coroutines.delay(50)
            }
        }
        viewModel.initialFocusDone = true
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (viewModel.isFolders) 4 else 7),
        state = gridState,
        contentPadding = PaddingValues(start = PlateaDimens.SafeHorizontal, end = PlateaDimens.SafeHorizontal, top = 4.dp, bottom = PlateaDimens.SafeTop),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        // No focusRestorer here: it redirected the focus given back to the opened poster towards the first one
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(state.items, key = { _, it -> it.id }) { i, item ->
            val focusModifier = Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier).restoreFocus(memory, "item:${item.id}")
            val onClick = {
                memory.remember("item:${item.id}")
                viewModel.open(item)
                Unit
            }
            if (viewModel.isFolders) {
                VideoTile(item, viewModel.still(item), focusModifier, onLongClick = { if (item.data.isFolder != true) onLongClick(item) }, onClick = onClick)
            } else {
                PosterTile(
                    item = item,
                    imageUrl = viewModel.poster(item),
                    isSeries = viewModel.isSeries,
                    focusModifier = focusModifier,
                    onLongClick = { if (item.type != org.jellyfin.sdk.model.api.BaseItemKind.BOX_SET) onLongClick(item) },
                    onClick = onClick,
                )
            }
        }
    }
}

/** Landscape tile for folder libraries: a video frame, or a folder with its name. */
@Composable
private fun VideoTile(
    item: BaseItem,
    imageUrl: String?,
    focusModifier: Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val folder = item.data.isFolder == true
    Column {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.CardRadius)),
            colors = ClickableSurfaceDefaults.colors(containerColor = PlateaColors.Placeholder, focusedContainerColor = PlateaColors.Placeholder),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(PlateaDimens.FocusBorder, PlateaColors.Text), shape = RoundedCornerShape(PlateaDimens.CardRadius))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
            modifier = focusModifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    if (folder) "▤" else item.name.orEmpty(),
                    style = if (folder) PlateaType.HeroTitle else PlateaType.CardSub,
                    color = PlateaColors.TextTertiary,
                    maxLines = 3,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
                if (!folder) AsyncImage(model = imageUrl, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (item.played) {
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp).clip(CircleShape).background(PlateaColors.Ground.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = PlateaColors.Text, modifier = Modifier.size(10.dp))
                    }
                }
            }
        }
        Text(
            item.name.orEmpty(),
            style = if (focused) PlateaType.CardTitle.copy(fontWeight = FontWeight.Bold) else PlateaType.CardTitle,
            color = if (focused) Color.White else PlateaColors.TextCard,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (focused) 8.dp else 6.dp),
        )
    }
}

@Composable
internal fun PosterTile(
    item: BaseItem,
    imageUrl: String?,
    isSeries: Boolean,
    focusModifier: Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.PosterRadius)),
            colors = ClickableSurfaceDefaults.colors(containerColor = PlateaColors.Placeholder, focusedContainerColor = PlateaColors.Placeholder),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(PlateaDimens.FocusBorder, PlateaColors.Text), shape = RoundedCornerShape(PlateaDimens.PosterRadius))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
            modifier = focusModifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Title as fallback when there is no poster
                Text(
                    item.name.orEmpty(),
                    style = PlateaType.CardSub,
                    color = PlateaColors.TextTertiary,
                    maxLines = 3,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
                AsyncImage(model = imageUrl, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                val unplayed = item.data.userData?.unplayedItemCount ?: 0
                when {
                    item.played ->
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(5.dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(PlateaColors.Ground.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = PlateaColors.Text, modifier = Modifier.size(10.dp))
                        }
                    isSeries && unplayed > 0 ->
                        Text(
                            unplayed.toString(),
                            style = PlateaType.Badge.copy(fontWeight = FontWeight.ExtraBold),
                            color = PlateaColors.Ground,
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(5.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(PlateaColors.Gold)
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                }
                val runtime = item.data.runTimeTicks ?: 0
                val position = item.data.userData?.playbackPositionTicks ?: 0
                if (!isSeries && !item.played && runtime > 0 && position > 0) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.22f))) {
                        Box(Modifier.fillMaxWidth((position.toFloat() / runtime).coerceIn(0f, 1f)).fillMaxHeight().background(PlateaColors.Gold))
                    }
                }
            }
        }
        Text(
            item.name.orEmpty(),
            style = if (focused) PlateaType.CardSub.copy(fontWeight = FontWeight.Bold) else PlateaType.CardSub,
            color = if (focused) Color.White else PlateaColors.TextCard,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (focused) 7.dp else 5.dp),
        )
        item.data.productionYear?.let { Text(it.toString(), style = PlateaType.CardSub.copy(fontSize = 8.5.sp), color = PlateaColors.TextMeta) }
    }
}

/** Filter chip: gold when selected, white only under the cursor. */
@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = if (selected) PlateaColors.Gold else PlateaColors.TextSecondary,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, if (selected) PlateaColors.Gold.copy(alpha = 0.7f) else PlateaColors.Avatar), shape = RoundedCornerShape(50)),
                focusedBorder = Border(BorderStroke(2.dp, PlateaColors.Text.copy(alpha = 0.35f)), inset = (-2).dp, shape = RoundedCornerShape(50)),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
        modifier = Modifier.height(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxHeight().padding(horizontal = 11.dp)) {
            Text(label, style = PlateaType.Nav.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            trailing?.let {
                Spacer(Modifier.width(5.dp))
                Text(it, style = PlateaType.Nav)
            }
        }
    }
}

@Composable
private fun GenrePicker(
    genres: List<String>,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val options = listOf<String?>(null) + genres
        val start = options.indexOf(selected).coerceAtLeast(0)
        val focus = remember { FocusRequester() }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(14.dp),
            modifier =
                Modifier
                    .width(300.dp)
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PlateaColors.Surface)
                    .border(BorderStroke(0.5.dp, PlateaColors.Avatar), RoundedCornerShape(10.dp)),
        ) {
            itemsIndexed(options) { i, genre ->
                val isSelected = genre == selected
                Surface(
                    onClick = { onPick(genre) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.ButtonRadius)),
                    colors =
                        ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = if (isSelected) PlateaColors.Gold else PlateaColors.Text,
                            focusedContainerColor = PlateaColors.Text,
                            focusedContentColor = PlateaColors.Ground,
                        ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    modifier = Modifier.fillMaxWidth().then(if (i == start) Modifier.focusRequester(focus) else Modifier),
                ) {
                    Text(
                        genre ?: stringResource(R.string.platea_genre_all),
                        style = PlateaType.CardTitle.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}
