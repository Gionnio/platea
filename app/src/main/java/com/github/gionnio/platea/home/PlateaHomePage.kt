package com.github.gionnio.platea.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.ui.preferences.PreferenceScreenOption
import com.github.gionnio.platea.ui.components.ActionMenu
import com.github.gionnio.platea.ui.components.MenuAction
import com.github.gionnio.platea.ui.restoreFocus
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private const val ROTATION_MS = 12_000L
private const val HERO_PRIMARY = "hero:primary"
private const val HERO_SECONDARY = "hero:secondary"

private fun cardKey(
    row: RowUi,
    card: CardUi,
) = "${row.key}:${card.id}"

/**
 * Platea home: featured rotation (next episode first, then new titles), then the rows.
 * Remote: ▲ from the first row reaches the featured buttons; ◀ on the first button and ▶ on the last one
 * move to the previous/next featured title.
 */
@Composable
fun PlateaHomePage(
    modifier: Modifier = Modifier,
    viewModel: PlateaHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        viewModel.checkUpdate()
        onPauseOrDispose { viewModel.stopTheme() }
    }

    // Benchmark marker: home fully drawn once the rows are loaded
    androidx.activity.compose.ReportDrawnWhen { !state.loading }
    val featured = state.featured
    val themePlaying by viewModel.themePlaying.collectAsState()
    var index by rememberSaveable { mutableIntStateOf(0) }
    val current = featured.getOrNull(if (featured.isEmpty()) 0 else index.mod(featured.size))
    var heroFocused by remember { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }

    // Rotation pauses while the remote is on the featured area
    LaunchedEffect(featured.size, heroFocused, index) {
        if (!heroFocused && featured.size > 1) {
            delay(ROTATION_MS)
            index = (index + 1).mod(featured.size)
        }
    }
    // The theme plays only while the remote is on the featured area; leaving it fades the theme out
    LaunchedEffect(current?.id, heroFocused) { viewModel.playTheme(if (heroFocused) current?.theme else null) }
    LaunchedEffect(featured.isNotEmpty()) {
        // Coming back from another screen: the element that opened it gets the focus instead
        if (featured.isNotEmpty() && (!viewModel.focusMemory.restoring || viewModel.focusMemory.key == HERO_PRIMARY)) {
            runCatching { primaryFocus.requestFocus() }
            viewModel.focusMemory.key = null
        }
    }

    // Row with the remote focus: -1 = top bar or featured area. The featured area folds away below the first row.
    var focusedRow by rememberSaveable { mutableIntStateOf(-1) }
    // Long press menu on a continue watching card
    var menu by remember { mutableStateOf<Pair<RowUi, CardUi>?>(null) }
    val heroVisible = focusedRow <= 0
    val backdropAlpha by animateFloatAsState(if (heroVisible) 1f else 0.35f, tween(400), label = "backdrop")

    Box(modifier.fillMaxSize().stopBringIntoView().background(PlateaColors.Ground)) {
        Backdrop(current?.backdropUrl, Modifier.alpha(backdropAlpha))
        Column(Modifier.fillMaxSize()) {
            TopBar(
                libraries = state.libraries,
                userInitial = state.userInitial,
                onNavigate = viewModel::navigate,
                modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedRow = -1 },
            )
            AnimatedVisibility(
                visible = heroVisible,
                enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                exit = shrinkVertically(tween(300)) + fadeOut(tween(200)),
            ) {
                if (current != null) {
                    Hero(
                        item = current,
                        count = featured.size,
                        index = index.mod(featured.size.coerceAtLeast(1)),
                        primaryFocus = primaryFocus,
                        onPrevious = { index = (index - 1).mod(featured.size) },
                        onNext = { index = (index + 1).mod(featured.size) },
                        onFocusChange = {
                            heroFocused = it
                            if (it) focusedRow = -1
                        },
                        onNavigate = viewModel::navigate,
                        themePlaying = themePlaying,
                        focusMemory = viewModel.focusMemory,
                    )
                } else {
                    Spacer(Modifier.height(240.dp))
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(bottom = PlateaDimens.SafeTop),
                modifier = Modifier.fillMaxSize(),
            ) {
                state.error?.let { error ->
                    item("error") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = PlateaDimens.SafeHorizontal, vertical = 12.dp)) {
                            Text(stringResource(R.string.platea_error_server), style = PlateaType.Body, color = PlateaColors.TextSecondary)
                            Spacer(Modifier.width(12.dp))
                            HeroButton(stringResource(R.string.platea_retry), primary = false) { viewModel.load() }
                        }
                    }
                }
                itemsIndexed(state.rows, key = { _, row -> row.key }) { i, row ->
                    HomeRow(
                        row,
                        onClick = {
                            viewModel.focusMemory.remember(cardKey(row, it))
                            viewModel.navigate(it.destination)
                        },
                        onLongClick = { card -> menu = row to card },
                        focusMemory = viewModel.focusMemory,
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedRow = i },
                    )
                }
            }
        }
        UpdatePrompt(viewModel, Modifier.align(Alignment.BottomEnd))
        menu?.let { (row, card) ->
            CardMenu(
                card = card,
                onDismiss = { menu = null },
                onPick = { dest ->
                    menu = null
                    viewModel.focusMemory.remember(cardKey(row, card))
                    viewModel.navigate(dest)
                },
            )
        }
    }
}

@Composable
private fun CardMenu(
    card: CardUi,
    onDismiss: () -> Unit,
    onPick: (Destination) -> Unit,
) {
    val actions = card.actions ?: return
    val options =
        buildList {
            add(MenuAction(actions.resumeLabel ?: stringResource(R.string.platea_play), play = true) { onPick(card.destination) })
            if (actions.resumeLabel != null) add(MenuAction(stringResource(R.string.platea_start_over), play = true) { onPick(actions.startOver) })
            actions.episode?.let { add(MenuAction(stringResource(R.string.platea_go_to_episode)) { onPick(it) }) }
            add(MenuAction(actions.detailsLabel) { onPick(actions.details) })
        }
    // Episodes: "S8:E7 · 5 min rimasti"; movies already show the time left in the first button
    ActionMenu(card.title, card.subtitle?.takeIf { it.startsWith("S") && it.contains(":E") }, options, onDismiss)
}

/**
 * The home is a fixed screen: "bring into view" requests from focused buttons stop here instead of moving
 * the whole window. The rows list inside still scrolls to show the focused row.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.stopBringIntoView(): Modifier =
    bringIntoViewResponder(
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero

            override suspend fun bringChildIntoView(localRect: () -> Rect?) = Unit
        },
    )

// --- Backdrop ---

@Composable
private fun Backdrop(
    url: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(422.dp)) {
        Crossfade(url, animationSpec = tween(600), label = "backdrop", modifier = Modifier.align(Alignment.TopEnd)) { u ->
            AsyncImage(
                model = u,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.width(750.dp).height(422.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .width(750.dp)
                .height(422.dp)
                .background(
                    Brush.horizontalGradient(
                        0f to PlateaColors.Ground,
                        0.24f to PlateaColors.Ground.copy(alpha = 0.88f),
                        0.6f to PlateaColors.Ground.copy(alpha = 0.25f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(422.dp)
                .background(
                    Brush.verticalGradient(
                        0f to PlateaColors.Ground.copy(alpha = 0.75f),
                        0.2f to Color.Transparent,
                        0.58f to Color.Transparent,
                        1f to PlateaColors.Ground,
                    ),
                ),
        )
    }
}

// --- Top bar ---

/** A new release on the NAS: install it or postpone it; then the download and install progress. */
@Composable
private fun UpdatePrompt(
    viewModel: PlateaHomeViewModel,
    modifier: Modifier = Modifier,
) {
    val update by viewModel.updater.state.collectAsState()
    val installed = viewModel.updater.installedVersion
    when (val u = update) {
        is com.github.gionnio.platea.update.UpdateState.Available ->
            if (u.release.versionCode != viewModel.updater.dismissed) {
                ActionMenu(
                    title = stringResource(R.string.platea_update_available),
                    subtitle = stringResource(R.string.platea_update_available_version, u.release.versionName, installed),
                    actions =
                        listOf(
                            MenuAction(stringResource(R.string.platea_update_install)) { viewModel.installUpdate(u) },
                            MenuAction(stringResource(R.string.platea_update_later)) { viewModel.updater.postpone() },
                        ),
                    onDismiss = { viewModel.updater.postpone() },
                )
            }
        is com.github.gionnio.platea.update.UpdateState.Downloading ->
            UpdateNote(stringResource(R.string.platea_update_downloading, u.percent), modifier)
        is com.github.gionnio.platea.update.UpdateState.Installing ->
            UpdateNote(stringResource(R.string.platea_update_installing), modifier)
        is com.github.gionnio.platea.update.UpdateState.Failed -> {
            UpdateNote(stringResource(R.string.platea_update_failed, u.message), modifier)
            LaunchedEffect(u) {
                delay(8_000)
                viewModel.updater.clearFailed()
            }
        }
        else -> Unit
    }
}

@Composable
private fun UpdateNote(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = PlateaType.CardSub,
        color = PlateaColors.Text,
        modifier =
            modifier
                .padding(end = PlateaDimens.SafeHorizontal, bottom = PlateaDimens.SafeTop)
                .clip(RoundedCornerShape(6.dp))
                .background(PlateaColors.Surface.copy(alpha = 0.92f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
internal fun TopBar(
    libraries: List<NavLibrary>,
    userInitial: String,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    /** Library page being shown (its pill is selected); null on the home. */
    selectedId: java.util.UUID? = null,
    onHome: () -> Unit = {},
    /** The search page is being shown: its icon is marked instead of a library. */
    searchSelected: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = PlateaDimens.SafeHorizontal, end = PlateaDimens.SafeHorizontal, top = PlateaDimens.SafeTop)
                .height(28.dp),
    ) {
        Image(
            painterResource(R.drawable.platea_mark),
            contentDescription = null,
            modifier = Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)),
        )
        Spacer(Modifier.width(7.dp))
        Text("Platea", style = PlateaType.Brand, color = PlateaColors.Text)
        Spacer(Modifier.width(24.dp))
        NavPill(stringResource(R.string.platea_home), selected = selectedId == null && !searchSelected, onClick = onHome)
        libraries.forEach { lib ->
            val id = (lib.destination as? Destination.MediaItem)?.itemId
            NavPill(lib.name, selected = id != null && id == selectedId) { if (id != selectedId) onNavigate(lib.destination) }
        }
        IconPill(Icons.Filled.Search, stringResource(R.string.platea_search), selected = searchSelected) { if (!searchSelected) onNavigate(Destination.Search()) }
        Spacer(Modifier.weight(1f))
        Clock()
        Spacer(Modifier.width(12.dp))
        // OK: settings. Long press: change user or server
        val session: PlateaSessionViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
        var sessionMenu by remember { mutableStateOf(false) }
        if (sessionMenu) {
            ActionMenu(
                title = session.userName.ifBlank { userInitial },
                subtitle = session.serverName,
                actions =
                    listOf(
                        MenuAction(stringResource(R.string.platea_switch_user)) {
                            sessionMenu = false
                            session.switchUser()
                        },
                        MenuAction(stringResource(R.string.platea_access_switch_server)) {
                            sessionMenu = false
                            session.switchServer()
                        },
                        MenuAction(stringResource(R.string.platea_settings)) {
                            sessionMenu = false
                            onNavigate(Destination.Settings(PreferenceScreenOption.BASIC))
                        },
                    ),
                onDismiss = { sessionMenu = false },
            )
        }
        Surface(
            onClick = { onNavigate(Destination.Settings(PreferenceScreenOption.BASIC)) },
            onLongClick = { sessionMenu = true },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = PlateaColors.Avatar, focusedContainerColor = PlateaColors.Text),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            modifier = Modifier.size(24.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(userInitial, style = PlateaType.Nav)
            }
        }
    }
}

@Composable
private fun NavPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors =
            ClickableSurfaceDefaults.colors(
                // Selected page: gold, never white (white means "cursor here")
                containerColor = Color.Transparent,
                contentColor = if (selected) PlateaColors.Gold else PlateaColors.TextSecondary,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = if (selected) Border(BorderStroke(1.dp, PlateaColors.Gold.copy(alpha = 0.7f)), shape = RoundedCornerShape(50)) else Border.None,
                focusedBorder = Border.None,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Text(label, style = PlateaType.Nav, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

@Composable
private fun IconPill(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = if (selected) PlateaColors.Gold else PlateaColors.TextSecondary,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = if (selected) Border(BorderStroke(1.dp, PlateaColors.Gold.copy(alpha = 0.7f)), shape = CircleShape) else Border.None,
                focusedBorder = Border.None,
            ),
        modifier = Modifier.padding(start = 4.dp).size(24.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(((60 - now.second) * 1000L).coerceAtLeast(1000L))
        }
    }
    Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), style = PlateaType.Nav, color = PlateaColors.TextSecondary)
}

// --- Featured ---

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Hero(
    item: FeaturedUi,
    count: Int,
    index: Int,
    primaryFocus: FocusRequester,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onNavigate: (Destination) -> Unit,
    themePlaying: Boolean,
    focusMemory: com.github.gionnio.platea.ui.FocusMemory,
) {
    Box(Modifier.fillMaxWidth().height(265.dp)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal, top = 29.dp).width(430.dp),
        ) {
            Text(item.kicker, style = PlateaType.Kicker, color = PlateaColors.Gold)
            var logoFailed by remember(item.id) { mutableStateOf(item.logoUrl == null) }
            Box(Modifier.height(PlateaDimens.HeroLogoHeight), contentAlignment = Alignment.CenterStart) {
                if (!logoFailed) {
                    AsyncImage(
                        model = item.logoUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        onError = { logoFailed = true },
                        modifier = Modifier.widthIn(max = PlateaDimens.HeroLogoMaxWidth).fillMaxHeight(),
                    )
                } else {
                    Text(item.title, style = PlateaType.HeroTitle, color = PlateaColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            MetaLine(item.highlight, item.meta)
            if (item.badges.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { item.badges.forEach { Badge(it) } }
            }
            item.overview?.let {
                Text(it, style = PlateaType.Body, color = PlateaColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(380.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp).onFocusChanged { onFocusChange(it.hasFocus) },
            ) {
                val primaryTarget = item.primary ?: item.secondary
                HeroButton(
                    label = item.primaryLabel,
                    primary = true,
                    modifier =
                        Modifier
                            .focusRequester(primaryFocus)
                            .onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && count > 1) {
                                    onPrevious()
                                    true
                                } else {
                                    false
                                }
                            },
                ) {
                    focusMemory.remember(HERO_PRIMARY)
                    onNavigate(primaryTarget)
                }
                HeroButton(
                    label = item.secondaryLabel,
                    primary = false,
                    modifier =
                        Modifier.restoreFocus(focusMemory, HERO_SECONDARY).onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight && count > 1) {
                                onNext()
                                runCatching { primaryFocus.requestFocus() }
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    focusMemory.remember(HERO_SECONDARY)
                    onNavigate(item.secondary)
                }
                if (item.theme != null) ThemeIcon(themePlaying)
            }
        }
        if (count > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = PlateaDimens.SafeHorizontal, bottom = 22.dp),
            ) {
                repeat(count) { i ->
                    Box(
                        Modifier
                            .width(if (i == index) 14.dp else 5.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (i == index) PlateaColors.Text else PlateaColors.Border),
                    )
                }
            }
        }
    }
}

@Composable
internal fun MetaLine(
    highlight: String?,
    meta: List<String>,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val parts = listOfNotNull(highlight) + meta
        parts.forEachIndexed { i, part ->
            if (i > 0) Text("  •  ", style = PlateaType.Meta, color = PlateaColors.Separator)
            Text(
                part,
                style = if (i == 0 && highlight != null) PlateaType.Meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else PlateaType.Meta,
                color = if (i == 0 && highlight != null) PlateaColors.Text else PlateaColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun Badge(text: String) {
    Text(
        text,
        style = PlateaType.Badge,
        color = PlateaColors.Text,
        modifier =
            Modifier
                .border(BorderStroke(0.75.dp, PlateaColors.Border), RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
internal fun HeroButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.ButtonRadius)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = PlateaColors.Surface.copy(alpha = 0.6f),
                contentColor = PlateaColors.Text,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, PlateaColors.Border)),
                focusedBorder = Border(BorderStroke(2.dp, PlateaColors.Text.copy(alpha = 0.35f)), inset = (-2).dp),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
        modifier = modifier.height(PlateaDimens.ButtonHeight),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxHeight().padding(horizontal = if (primary) 18.dp else 16.dp),
        ) {
            if (primary) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = PlateaType.Button)
        }
    }
}

/** Theme song indicator: just the equalizer, animated while the theme is audible. */
@Composable
internal fun ThemeIcon(playing: Boolean) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val bars = listOf(4f, 7f, 10f, 6f, 3f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 10.dp).height(12.dp),
    ) {
        bars.forEachIndexed { i, rest ->
            val animated by transition.animateFloat(
                initialValue = 2.5f,
                targetValue = 12f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 380 + i * 90, easing = LinearEasing), RepeatMode.Reverse),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(2.dp)
                    .height((if (playing) animated else rest).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (playing) PlateaColors.Gold else PlateaColors.Gold.copy(alpha = 0.45f)),
            )
        }
    }
}

// --- Rows ---

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomeRow(
    row: RowUi,
    onClick: (CardUi) -> Unit,
    onLongClick: (CardUi) -> Unit,
    focusMemory: com.github.gionnio.platea.ui.FocusMemory,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(top = if (row.style == RowStyle.LANDSCAPE) 9.dp else 14.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = PlateaDimens.SafeHorizontal, bottom = 15.dp)) {
            Text(row.title, style = PlateaType.RowTitle, color = PlateaColors.Text)
            row.note?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = PlateaType.CardSub, color = PlateaColors.TextTertiary)
            }
        }
        LazyRow(
            contentPadding = PaddingValues(start = PlateaDimens.SafeHorizontal, end = PlateaDimens.SafeHorizontal),
            horizontalArrangement = Arrangement.spacedBy(if (row.style == RowStyle.LANDSCAPE) PlateaDimens.CardGap else PlateaDimens.PosterGap),
            modifier = Modifier.focusRestorer(),
        ) {
            items(row.cards.distinctBy { it.id }, key = { it.id }) { card ->
                val restore = Modifier.restoreFocus(focusMemory, cardKey(row, card))
                when (row.style) {
                    RowStyle.LANDSCAPE -> LandscapeCard(card, restore, onLongClick = card.actions?.let { { onLongClick(card) } }) { onClick(card) }
                    RowStyle.POSTER -> PosterCard(card, restore) { onClick(card) }
                }
            }
        }
    }
}

@Composable
private fun cardSurfaceColors() =
    ClickableSurfaceDefaults.colors(
        containerColor = PlateaColors.Placeholder,
        focusedContainerColor = PlateaColors.Placeholder,
    )

@Composable
private fun cardBorder(radius: Dp) =
    ClickableSurfaceDefaults.border(
        focusedBorder = Border(BorderStroke(PlateaDimens.FocusBorder, PlateaColors.Text), shape = RoundedCornerShape(radius)),
    )

@Composable
private fun LandscapeCard(
    card: CardUi,
    focusModifier: Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(Modifier.width(PlateaDimens.CardLandscapeWidth)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.CardRadius)),
            colors = cardSurfaceColors(),
            border = cardBorder(PlateaDimens.CardRadius),
            scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
            modifier = focusModifier.width(PlateaDimens.CardLandscapeWidth).height(PlateaDimens.CardLandscapeHeight),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = card.imageUrl, contentDescription = card.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                card.tag?.let {
                    Text(
                        it,
                        style = PlateaType.Badge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
                        color = PlateaColors.Gold,
                        modifier =
                            Modifier
                                .padding(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(PlateaColors.Ground.copy(alpha = 0.85f))
                                .padding(horizontal = 5.dp, vertical = 2.5.dp),
                    )
                }
                card.progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.22f))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(PlateaColors.Gold))
                    }
                }
            }
        }
        Spacer(Modifier.height(if (focused) 9.dp else 6.dp))
        Text(
            card.title,
            style = if (focused) PlateaType.CardTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else PlateaType.CardTitle,
            color = if (focused) Color.White else PlateaColors.TextCard,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        card.subtitle?.let {
            Text(it, style = PlateaType.CardSub, color = PlateaColors.TextMeta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PosterCard(
    card: CardUi,
    focusModifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.PosterRadius)),
        colors = cardSurfaceColors(),
        border = cardBorder(PlateaDimens.PosterRadius),
        scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
        modifier = focusModifier.width(PlateaDimens.PosterWidth).heightIn(min = PlateaDimens.PosterHeight).height(PlateaDimens.PosterHeight),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(model = card.imageUrl, contentDescription = card.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            card.rating?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(PlateaColors.Ground.copy(alpha = 0.88f))
                            .padding(horizontal = 4.5.dp, vertical = 2.5.dp),
                ) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = PlateaColors.Gold, modifier = Modifier.size(8.dp))
                    Spacer(Modifier.width(2.5.dp))
                    Text(it, style = PlateaType.Badge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold), color = PlateaColors.Text)
                }
            }
            card.badge?.let {
                Text(
                    it,
                    style = PlateaType.Badge,
                    color = PlateaColors.Text,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(5.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(PlateaColors.Ground.copy(alpha = 0.88f))
                            .padding(vertical = 3.5.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
