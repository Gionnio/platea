package com.github.gionnio.platea.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.useExistingImageAsPlaceholder
import coil3.request.ImageRequest
import coil3.request.transitionFactory
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.services.ScreensaverService
import com.github.gionnio.platea.ui.CrossFadeFactory
import com.github.gionnio.platea.ui.util.LocalClock
import com.github.gionnio.platea.ui.PlateaBrand
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.extensions.ticks

@HiltViewModel
class ScreensaverViewModel
    @Inject
    constructor(
        private val screensaverService: ScreensaverService,
        val preferencesDataStore: DataStore<AppPreferences>,
    ) : ViewModel() {
        val currentItem = screensaverService.createItemFlow(viewModelScope)
    }

sealed interface ScreensaverItem {
    data class Error(
        val exception: Exception,
    ) : ScreensaverItem

    data object Empty : ScreensaverItem

    data class CurrentItem(
        val item: BaseItem,
        val backdropUrl: String,
        val logoUrl: String?,
        val title: String,
    ) : ScreensaverItem
}

@Composable
fun AppScreensaver(
    prefs: AppPreferences,
    modifier: Modifier = Modifier,
    viewModel: ScreensaverViewModel = hiltViewModel(),
) {
    val currentItem by viewModel.currentItem.collectAsState(null)
    AppScreensaverContent(
        currentItem = currentItem,
        showClock = prefs.interfacePreferences.screensaverPreference.showClock,
        duration = prefs.interfacePreferences.screensaverPreference.duration.milliseconds,
        animate = prefs.interfacePreferences.screensaverPreference.animate,
        modifier = modifier,
    )
}

/**
 * The Platea screensaver: the backdrop of a title of the library under the velvet scrim of the home, with its
 * logo (or its name), a gold rule and a line of facts at the bottom left, the brand and the clock at the top.
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
fun AppScreensaverContent(
    currentItem: ScreensaverItem?,
    showClock: Boolean,
    duration: Duration,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(PlateaColors.Ground)) {
        val infiniteTransition = rememberInfiniteTransition()
        val scale by infiniteTransition.animateFloat(
            1f,
            if (animate) 1.1f else 1f,
            infiniteRepeatable(
                tween(durationMillis = duration.inWholeMilliseconds.toInt(), delayMillis = 500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        )
        val shown = currentItem as? ScreensaverItem.CurrentItem
        if (currentItem == null || shown != null) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(shown?.backdropUrl)
                        .transitionFactory(CrossFadeFactory(2000.milliseconds))
                        .useExistingImageAsPlaceholder(true)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
            )
        }
        // Velvet from the bottom left, like the hero of the home, and a soft band for the brand and the clock
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        0f to PlateaColors.Ground.copy(alpha = 0.96f),
                        0.45f to PlateaColors.Ground.copy(alpha = 0.55f),
                        0.8f to Color.Transparent,
                        start = Offset(0f, Float.POSITIVE_INFINITY),
                        end = Offset(Float.POSITIVE_INFINITY, 0f),
                    ),
                ).background(
                    Brush.verticalGradient(0f to PlateaColors.Ground.copy(alpha = 0.6f), 0.24f to Color.Transparent),
                ),
        )

        PlateaBrand(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = PlateaDimens.SafeHorizontal, top = PlateaDimens.SafeTop)
                .alpha(0.85f),
        )
        if (showClock) {
            Text(
                LocalClock.current.timeString.value,
                style = PlateaType.RowTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                color = PlateaColors.Text,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = PlateaDimens.SafeHorizontal, top = PlateaDimens.SafeTop),
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = PlateaDimens.SafeHorizontal, bottom = 36.dp)
                .widthIn(max = 420.dp),
        ) {
            when (currentItem) {
                ScreensaverItem.Empty -> Caption(stringResource(R.string.platea_saver_empty))
                is ScreensaverItem.Error -> Caption(stringResource(R.string.platea_error_server))
                null -> {}
                is ScreensaverItem.CurrentItem -> TitleBlock(currentItem)
            }
        }
    }
}

@OptIn(ExperimentalCoilApi::class)
@Composable
private fun TitleBlock(shown: ScreensaverItem.CurrentItem) {
    var logoFailed by remember(shown.item.id) { mutableStateOf(shown.logoUrl == null) }
    if (!logoFailed) {
        AsyncImage(
            model =
                ImageRequest
                    .Builder(LocalContext.current)
                    .data(shown.logoUrl)
                    .transitionFactory(CrossFadeFactory(750.milliseconds))
                    .build(),
            contentDescription = shown.title,
            alignment = Alignment.BottomStart,
            onError = { logoFailed = true },
            modifier = Modifier.height(PlateaDimens.HeroLogoHeight * 1.3f).widthIn(max = PlateaDimens.HeroLogoMaxWidth),
        )
    } else {
        Text(shown.title, style = PlateaType.HeroTitle, color = PlateaColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(10.dp))
    Box(Modifier.width(40.dp).height(2.dp).background(PlateaColors.Gold))
    val facts =
        remember(shown.item.id) {
            val data = shown.item.data
            listOfNotNull(
                data.productionYear?.toString(),
                data.genres?.firstOrNull(),
                data.runTimeTicks?.ticks?.inWholeMinutes?.takeIf { it > 0 && data.type != BaseItemKind.SERIES }?.let {
                    if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min"
                },
            )
        }
    if (facts.isNotEmpty()) {
        Spacer(Modifier.height(9.dp))
        Text(facts.joinToString("  ·  "), style = PlateaType.Meta.copy(fontSize = 12.sp), color = PlateaColors.TextSecondary, maxLines = 1)
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = PlateaType.RowTitle, color = PlateaColors.TextSecondary)
}
