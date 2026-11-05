package com.github.gionnio.platea.settings

import android.content.pm.ActivityInfo
import android.os.Build
import androidx.annotation.Dimension
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Text
import com.github.gionnio.platea.R
import com.github.gionnio.platea.preferences.AppPreference
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.SubtitlePreferences
import com.github.gionnio.platea.preferences.resetSubtitles
import com.github.gionnio.platea.preferences.updateInterfacePreferences
import com.github.gionnio.platea.ui.findActivity
import com.github.gionnio.platea.ui.preferences.subtitle.SubtitleSettings
import com.github.gionnio.platea.ui.preferences.subtitle.SubtitleSettings.calculateEdgeSize
import com.github.gionnio.platea.ui.preferences.subtitle.SubtitleSettings.toSubtitleStyle
import com.github.gionnio.platea.util.Media3SubtitleOverride
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType

/**
 * Subtitle style: a live preview on the left, the options on the right. One style for SDR and, if wanted,
 * a separate one for HDR films (bright white subtitles dazzle in HDR). Options and storage are the inherited
 * ones ([SubtitleSettings]).
 */
@OptIn(UnstableApi::class)
@Composable
fun PlateaSubtitleStylePage(
    initial: AppPreferences,
    hdr: Boolean,
    modifier: Modifier = Modifier,
    viewModel: PlateaSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val display = LocalView.current.display
    val all by viewModel.preferences.collectAsState(initial = initial)
    val prefs = if (hdr) all.interfacePreferences.hdrSubtitlesPreferences else all.interfacePreferences.subtitlesPreferences
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // The HDR page shows the preview in HDR, as the film would
        DisposableEffect(hdr) {
            if (hdr) context.findActivity()?.window?.colorMode = ActivityInfo.COLOR_MODE_HDR
            onDispose { context.findActivity()?.window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT }
        }
    }
    var focused by remember { mutableStateOf<AppPreference<SubtitlePreferences, *>?>(null) }
    val save = { change: (SubtitlePreferences) -> SubtitlePreferences ->
        viewModel.update { app ->
            app.updateInterfacePreferences {
                if (hdr) hdrSubtitlesPreferences = change(hdrSubtitlesPreferences) else subtitlesPreferences = change(subtitlesPreferences)
            }
        }
    }
    val groups =
        remember(hdr, display, prefs.useSeparateHdr) {
            val base = SubtitleSettings.preferences.map { it.title to it.preferences }
            if (!hdr && display != null && SubtitleSettings.shouldShowHdr(display)) {
                base + (R.string.hdr to listOfNotNull(SubtitleSettings.SeparateHdr, SubtitleSettings.HdrSettings.takeIf { prefs.useSeparateHdr }))
            } else {
                base
            }
        }

    Row(modifier.fillMaxSize().background(PlateaColors.Ground)) {
        // Preview over a film frame
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
            Image(painterResource(R.mipmap.eclipse), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            when (focused) {
                SubtitleSettings.Margin ->
                    Preview(prefs, stringResource(R.string.platea_sub_sample_margin), Modifier.fillMaxSize()) { it.setBottomPaddingFraction(prefs.margin / 100f) }
                SubtitleSettings.ImageOpacity ->
                    Preview(
                        SubtitlePreferences.newBuilder().apply { resetSubtitles() }.build(),
                        stringResource(R.string.platea_sub_sample_image),
                        Modifier.fillMaxSize().alpha(prefs.imageSubtitleOpacity / 100f),
                    )
                else ->
                    Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.padding(16.dp).fillMaxSize()) {
                        listOf(R.string.platea_sub_sample_1 to 48.dp, R.string.platea_sub_sample_2 to 24.dp, R.string.platea_sub_sample_3 to 0.dp).forEach { (text, bottom) ->
                            Preview(prefs, stringResource(text), Modifier.weight(1f).fillMaxWidth().padding(bottom = bottom)) {
                                Media3SubtitleOverride(prefs.calculateEdgeSize(density)).apply(it)
                            }
                        }
                    }
            }
        }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(start = 16.dp, end = PlateaDimens.SafeHorizontal, top = PlateaDimens.SafeTop + 6.dp, bottom = PlateaDimens.SafeTop),
            modifier = Modifier.width(400.dp).fillMaxHeight().background(PlateaColors.Ground),
        ) {
            item {
                Text(
                    stringResource(if (hdr) R.string.hdr_subtitle_style else R.string.subtitle_style),
                    style = PlateaType.HeroTitle.copy(fontSize = 22.sp),
                    color = PlateaColors.Text,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                )
            }
            groups.forEachIndexed { g, (title, list) ->
                item { Text(stringResource(title).uppercase(), style = PlateaType.Kicker, color = PlateaColors.Gold, modifier = Modifier.padding(top = 12.dp, bottom = 3.dp, start = 12.dp)) }
                list.forEachIndexed { i, pref ->
                    item {
                        PrefRow(
                            pref,
                            prefs,
                            Modifier.then(if (g == 0 && i == 0) Modifier.focusRequester(first) else Modifier).onFocusChanged { if (it.hasFocus) focused = pref },
                            onChange = save,
                            onNavigate = viewModel.navigationManager::navigateTo,
                            // The only plain button of this page is "Reset"
                            onClick = { save { SubtitlePreferences.newBuilder().apply { resetSubtitles() }.build() } },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Preview(
    prefs: SubtitlePreferences,
    text: String,
    modifier: Modifier,
    extra: (SubtitleView) -> Unit = {},
) {
    AndroidView(
        factory = { SubtitleView(it) },
        update = {
            it.setStyle(prefs.toSubtitleStyle())
            it.setFixedTextSize(Dimension.SP, prefs.fontSize.toFloat())
            it.setCues(listOf(Cue.Builder().setText(text).build()))
            extra(it)
        },
        modifier = modifier,
    )
}
