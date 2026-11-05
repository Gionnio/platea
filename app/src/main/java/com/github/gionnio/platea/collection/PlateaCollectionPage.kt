package com.github.gionnio.platea.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.home.HeroButton
import com.github.gionnio.platea.home.MetaLine
import com.github.gionnio.platea.library.PosterTile
import com.github.gionnio.platea.ui.PlateaError
import com.github.gionnio.platea.ui.PlateaLoading
import com.github.gionnio.platea.ui.components.ActionMenu
import com.github.gionnio.platea.ui.components.ExpandableOverview
import com.github.gionnio.platea.ui.components.MenuAction
import com.github.gionnio.platea.ui.restoreFocus
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType
import org.jellyfin.sdk.model.api.BaseItemKind

/** Platea collection / saga page: backdrop, what to watch next, and the titles in release order. */
@Composable
fun PlateaCollectionPage(
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: PlateaCollectionViewModel =
        hiltViewModel<PlateaCollectionViewModel, PlateaCollectionViewModel.Factory>(creationCallback = { it.create(destination.itemId) }),
) {
    val state by viewModel.state.collectAsState()
    val memory = viewModel.focusMemory
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) viewModel.load()
        firstResume = false
        onPauseOrDispose { }
    }
    var menuFor by remember { mutableStateOf<BaseItem?>(null) }
    val collection = state.collection

    Box(modifier.fillMaxSize().background(PlateaColors.Ground)) {
        if (collection == null) {
            if (state.loading) PlateaLoading() else PlateaError(state.error) { viewModel.load() }
            return@Box
        }
        // Backdrop of the collection, faded to the left and to the bottom
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.78f).fillMaxHeight(0.62f)) {
            AsyncImage(model = remember(collection.id, state.next?.id) { viewModel.backdrop(collection) }, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to PlateaColors.Ground, 0.25f to PlateaColors.Ground.copy(alpha = 0.8f), 0.65f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to PlateaColors.Ground)))
        }
        Column(Modifier.fillMaxSize().padding(top = 44.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal).width(450.dp)) {
                Text(
                    collection.name.orEmpty().removeSuffix(" - Collezione").removeSuffix(" - Collection"),
                    style = PlateaType.HeroTitle.copy(fontSize = 30.sp),
                    color = PlateaColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.meta.isNotEmpty()) MetaLine(null, state.meta)
                collection.data.overview?.takeIf { it.isNotBlank() }?.let { ExpandableOverview(it, 420.dp) }
                val play = remember { FocusRequester() }
                LaunchedEffect(state.next?.id) {
                    if (!memory.restoring || memory.key == "play") {
                        runCatching { play.requestFocus() }
                        if (memory.key == "play") memory.key = null
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    state.next?.let { next ->
                        val resume = next.resumeMs > 0 && !next.played
                        HeroButton(
                            stringResource(if (resume) R.string.platea_collection_resume else R.string.platea_collection_play, next.name.orEmpty()),
                            primary = true,
                            modifier = Modifier.focusRequester(play),
                        ) {
                            memory.remember("play")
                            viewModel.play(next)
                        }
                    }
                }
            }
            if (state.items.isEmpty()) {
                Text(stringResource(R.string.platea_collection_empty), style = PlateaType.Body, color = PlateaColors.TextMeta, modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal, top = 30.dp))
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(start = PlateaDimens.SafeHorizontal, end = PlateaDimens.SafeHorizontal, top = 26.dp, bottom = 4.dp),
                ) {
                    itemsIndexed(state.items.distinctBy { it.id }, key = { _, it -> it.id }) { _, item ->
                        Box(Modifier.width(118.dp)) {
                            PosterTile(
                                item = item,
                                imageUrl = viewModel.poster(item),
                                isSeries = item.type == BaseItemKind.SERIES,
                                focusModifier = Modifier.restoreFocus(memory, "t:${item.id}"),
                                onLongClick = { menuFor = item },
                            ) {
                                memory.remember("t:${item.id}")
                                viewModel.open(item)
                            }
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { item ->
        ActionMenu(
            title = item.name.orEmpty(),
            subtitle = item.data.productionYear?.toString(),
            actions =
                buildList {
                    if (item.type == BaseItemKind.MOVIE) {
                        add(MenuAction(stringResource(R.string.platea_play_from_here), play = true) {
                            menuFor = null
                            memory.remember("t:${item.id}")
                            viewModel.play(item)
                        })
                    }
                    add(MenuAction(stringResource(R.string.platea_go_to_details)) {
                        menuFor = null
                        memory.remember("t:${item.id}")
                        viewModel.open(item)
                    })
                    add(MenuAction(stringResource(if (item.played) R.string.platea_mark_unwatched else R.string.platea_mark_watched)) {
                        menuFor = null
                        viewModel.setWatched(item, !item.played)
                    })
                },
            onDismiss = { menuFor = null },
        )
    }
}
