package com.github.gionnio.platea.person

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.home.MetaLine
import com.github.gionnio.platea.library.PosterTile
import com.github.gionnio.platea.ui.FocusMemory
import com.github.gionnio.platea.ui.PlateaError
import com.github.gionnio.platea.ui.PlateaLoading
import com.github.gionnio.platea.ui.components.ExpandableOverview
import com.github.gionnio.platea.ui.restoreFocus
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType

/** Platea person page: portrait, dates, biography, and the person's movies and series in the library. */
@Composable
fun PlateaPersonPage(
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: PlateaPersonViewModel =
        hiltViewModel<PlateaPersonViewModel, PlateaPersonViewModel.Factory>(creationCallback = { it.create(destination.itemId) }),
) {
    val state by viewModel.state.collectAsState()
    val memory = viewModel.focusMemory
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) viewModel.load()
        firstResume = false
        onPauseOrDispose { }
    }
    val person = state.person
    Box(modifier.fillMaxSize().background(PlateaColors.Ground)) {
        if (person == null) {
            if (state.loading) PlateaLoading() else PlateaError(state.error) { viewModel.load() }
            return@Box
        }
        // The cursor starts on the first title
        val first = remember { FocusRequester() }
        val titles = state.movies.size + state.series.size
        LaunchedEffect(person.id, titles > 0) { if (!memory.restoring && titles > 0) runCatching { first.requestFocus() } }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 36.dp, bottom = PlateaDimens.SafeTop),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(Modifier.padding(horizontal = PlateaDimens.SafeHorizontal)) {
                    Box(Modifier.width(120.dp).height(180.dp).clip(RoundedCornerShape(PlateaDimens.CardRadius)).background(PlateaColors.Placeholder), contentAlignment = Alignment.Center) {
                        Text(person.name.orEmpty().take(1), style = PlateaType.HeroTitle, color = PlateaColors.TextTertiary)
                        AsyncImage(model = viewModel.photo(person), contentDescription = person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(560.dp)) {
                        Text(person.name.orEmpty(), style = PlateaType.HeroTitle.copy(fontSize = 30.sp), color = PlateaColors.Text, maxLines = 1)
                        if (state.facts.isNotEmpty()) MetaLine(null, state.facts)
                        person.data.overview?.takeIf { it.isNotBlank() }?.let { ExpandableOverview(it, 540.dp, maxLines = 4) }
                    }
                }
            }
            if (state.movies.isNotEmpty()) item { TitleRow(stringResource(R.string.platea_search_movies), state.movies, false, viewModel, memory, first) }
            if (state.series.isNotEmpty()) item { TitleRow(stringResource(R.string.platea_search_series), state.series, true, viewModel, memory, first.takeIf { state.movies.isEmpty() }) }
            if (!state.loading && state.movies.isEmpty() && state.series.isEmpty()) {
                item { Text(stringResource(R.string.platea_person_none), style = PlateaType.Body, color = PlateaColors.TextMeta, modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal)) }
            }
        }
    }
}

@Composable
private fun TitleRow(
    title: String,
    items: List<BaseItem>,
    isSeries: Boolean,
    viewModel: PlateaPersonViewModel,
    memory: FocusMemory,
    first: FocusRequester?,
) {
    Column {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal, bottom = 9.dp)) {
            Text(title, style = PlateaType.RowTitle, color = PlateaColors.Text)
            Spacer(Modifier.width(8.dp))
            Text(items.size.toString(), style = PlateaType.CardSub, color = PlateaColors.TextTertiary)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = PlateaDimens.SafeHorizontal, vertical = 4.dp),
        ) {
            items(items.distinctBy { it.id }, key = { it.id }) { item ->
                Box(Modifier.width(104.dp)) {
                    PosterTile(
                        item = item,
                        imageUrl = viewModel.poster(item),
                        isSeries = isSeries,
                        focusModifier =
                            Modifier
                                .restoreFocus(memory, "t:${item.id}")
                                .then(if (first != null && item.id == items.first().id) Modifier.focusRequester(first) else Modifier),
                        onLongClick = {},
                    ) {
                        memory.remember("t:${item.id}")
                        viewModel.open(item)
                    }
                }
            }
        }
    }
}
