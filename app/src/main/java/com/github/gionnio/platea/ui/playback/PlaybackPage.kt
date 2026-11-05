package com.github.gionnio.platea.ui.playback

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.children
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPresentationState
import com.github.gionnio.platea.R
import com.github.gionnio.platea.preferences.AssPlaybackMode
import com.github.gionnio.platea.preferences.DpadSeekMode
import com.github.gionnio.platea.preferences.UserPreferences
import com.github.gionnio.platea.preferences.skipBackOnResume
import com.github.gionnio.platea.ui.AppColors
import com.github.gionnio.platea.ui.LocalImageUrlService
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.ui.playback.overlay.rememberSeekBarState
import com.github.gionnio.platea.ui.preferences.subtitle.SubtitleSettings.applyTo
import com.github.gionnio.platea.ui.preferences.subtitle.SubtitleSettings.calculateEdgeSize
import com.github.gionnio.platea.ui.tryRequestFocus
import com.github.gionnio.platea.util.ExceptionHandler
import com.github.gionnio.platea.util.LoadingState
import com.github.gionnio.platea.util.Media3SubtitleOverride
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import timber.log.Timber

/**
 * The actual playback page which shows media & playback controls
 */
@OptIn(UnstableApi::class)
@Composable
fun PlaybackPage(
    preferences: UserPreferences,
    destination: Destination,
    modifier: Modifier = Modifier,
    viewModel: PlaybackViewModel =
        hiltViewModel<PlaybackViewModel, PlaybackViewModel.Factory>(
            creationCallback = { it.create(destination) },
        ),
) {
    LifecycleStartEffect(destination) {
        onStopOrDispose {
            viewModel.release()
        }
    }
    val state by viewModel.state.collectAsState()
    when (val st = state.loading) {
        is LoadingState.Error -> {
            // Network failures say "server"; anything else is about this title
            val network =
                generateSequence(st.exception) { it.cause }.any {
                    it is java.io.IOException || (it is ApiClientException && it !is InvalidStatusException)
                }
            com.github.gionnio.platea.ui.PlateaError(
                detail = st.localizedMessage,
                modifier = modifier.background(Color.Black),
                title = stringResource(if (network) R.string.platea_error_server else R.string.platea_error_playback),
                onRetry = viewModel::retry,
            )
        }

        LoadingState.Pending,
        LoadingState.Loading,
        -> {
            com.github.gionnio.platea.ui.PlateaLoading(modifier.background(Color.Black))
        }

        LoadingState.Success -> {
            val playerState by viewModel.currentPlayer.collectAsState()
            PlaybackPageContent(
                playerInstance = playerState!!,
                preferences = preferences,
                destination = destination,
                viewModel = viewModel,
                modifier = modifier,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlaybackPageContent(
    playerInstance: PlayerInstance,
    preferences: UserPreferences,
    destination: Destination,
    modifier: Modifier = Modifier,
    viewModel: PlaybackViewModel,
) {
    val state by viewModel.state.collectAsState()
    val player = playerInstance.player

    val prefs = preferences.appPreferences.playbackPreferences
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val contentScale = prefs.globalContentScale.scale
    // The subtitle and audio keys of the remote open the Platea track panel
    var tracksRequest by remember { mutableIntStateOf(0) }

    val presentationState = rememberPresentationState(player, false)
    val playbackState by rememberPlayerState(player)
    var showBuffering by remember { mutableStateOf(false) }
    LaunchedEffect(playbackState) {
        if (playbackState == PlayerState.BUFFERING) {
            // Delay before showing the loading indicator
            // So if buffering is quick, the UI won't flash
            delay(250)
            showBuffering = true
        } else {
            showBuffering = false
        }
    }
    val scaledModifier =
        Modifier.resizeWithContentScale(contentScale, presentationState.videoSizeDp)
    val focusRequester = remember { FocusRequester() }
    val playPauseState = rememberPlayPauseButtonState(player)
    val seekBarState = rememberSeekBarState(player, scope)

    LaunchedEffect(Unit) {
        focusRequester.tryRequestFocus()
    }
    val controllerViewState = remember { viewModel.controllerViewState }

    var skipIndicatorDuration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controllerViewState.controlsVisible) {
        // If controller shows/hides, immediately cancel the skip indicator
        skipIndicatorDuration = 0L
    }
    var skipPosition by remember { mutableLongStateOf(0L) }
    val updateSkipIndicator = { delta: Long ->
        if ((skipIndicatorDuration > 0 && delta < 0) || (skipIndicatorDuration < 0 && delta > 0)) {
            skipIndicatorDuration = 0
        }
        skipIndicatorDuration += delta
        skipPosition = player.currentPosition
    }
    val keyHandler =
        remember(preferences) {
            PlaybackKeyHandler(
                player = player,
                controlsEnabled = state.nextUp == null,
                skipWithLeftRight = true,
                seekForward = preferences.appPreferences.playbackPreferences.skipForwardMs.milliseconds,
                seekBack = preferences.appPreferences.playbackPreferences.skipBackMs.milliseconds,
                getDurationMs = { player.duration.coerceAtLeast(0L) },
                controllerViewState = controllerViewState,
                updateSkipIndicator = updateSkipIndicator,
                clearSkipIndicator = { skipIndicatorDuration = 0 },
                skipBackOnResume = preferences.appPreferences.playbackPreferences.skipBackOnResume,
                onInteraction = viewModel::reportInteraction,
                oneClickPause = preferences.appPreferences.playbackPreferences.oneClickPause,
                onStop = {
                    player.stop()
                    viewModel.navigationManager.goBack()
                },
                onTracksKey = { tracksRequest++ },
                onNext = viewModel::playNextUp,
                onPrevious = viewModel::playPreviousOrFetch,
            )
        }

    val showSegment =
        state.currentSegment?.interacted == false &&
            state.nextUp == null && !controllerViewState.controlsVisible && skipIndicatorDuration == 0L
    BackHandler(showSegment) {
        viewModel.updateSegment(state.currentSegment?.segment?.id, true)
    }

    Box(
        modifier
            .background(
                if (state.nextUp == null) Color.Black else com.github.gionnio.platea.ui.theme.PlateaColors.Ground,
            )
            // The media keys of the remote also work while the cursor is on "Skip intro" or on the next
            // episode card, which sit outside the player area below
            .onKeyEvent { if (isMedia(it)) keyHandler.onKeyEvent(it) else false },
    ) {
        val playerSize by animateFloatAsState(if (state.nextUp == null) 1f else .6f)
        Box(
            modifier =
                Modifier
                    .fillMaxSize(playerSize)
                    .align(Alignment.TopCenter)
                    .onKeyEvent(keyHandler::onKeyEvent)
                    .focusRequester(focusRequester)
                    .focusable(),
        ) {
            var playerSurfaceSize by remember { mutableStateOf(IntSize.Zero) }
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier =
                    scaledModifier.onSizeChanged {
                        playerSurfaceSize = it
                    },
            )
            if (presentationState.coverSurface) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black),
                ) {
                    com.github.gionnio.platea.ui.PlateaLoading()
                }
            } else {
                AnimatedVisibility(
                    visible = showBuffering || state.stalled,
                    enter = fadeIn(tween(easing = LinearEasing)),
                    exit = fadeOut(tween(easing = LinearEasing)),
                    modifier = Modifier.matchParentSize(),
                ) {
                    com.github.gionnio.platea.ui.PlateaLoading(Modifier.background(AppColors.TransparentBlack25))
                
                }
            }

            // If D-pad skipping, show the amount skipped in an animation
            if (!controllerViewState.controlsVisible && skipIndicatorDuration != 0L) {
                // Skip time mode: show seek distance indicator
                // Platea: the symbol on the side the film moves towards
                com.github.gionnio.platea.player.PlateaSkipIndicator(
                    durationMs = skipIndicatorDuration,
                    onFinish = { skipIndicatorDuration = 0L },
                    modifier =
                        Modifier
                            .align(if (skipIndicatorDuration < 0) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(horizontal = 96.dp),
                )
            
                // Show a small progress bar along the bottom of the screen
                if (prefs.dpadSeekMode == DpadSeekMode.SEEKBAR_MINIMAL) {
                    val percent = skipPosition.toFloat() / player.duration.toFloat()
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .background(com.github.gionnio.platea.ui.theme.PlateaColors.Gold)
                                        .clip(RectangleShape)
                                        .height(3.dp)
                                        .fillMaxWidth(percent),
                            )
                        }
                    }
                }
            }

            if (!controllerViewState.controlsVisible && skipIndicatorDuration == 0L) {
                com.github.gionnio.platea.player.PlateaPauseIndicator(player = player, modifier = Modifier.align(Alignment.Center))
            }

            // The playback controls

            com.github.gionnio.platea.player.PlateaPlayerOverlay(
                item = state.currentPlayback?.item,
                player = player,
                controllerViewState = controllerViewState,
                showPlay = playPauseState.showPlay,
                currentPlayback = state.currentPlayback,
                chapters = state.currentMediaInfo.chapters,
                analyticsState = state.analyticsState,
                nextEnabled = state.hasNext,
                previousEnabled = state.hasPrevious,
                currentSegment = state.currentSegment?.segment,
                seekBack = preferences.appPreferences.playbackPreferences.skipBackMs.milliseconds,
                seekForward = preferences.appPreferences.playbackPreferences.skipForwardMs.milliseconds,
                skipBackOnResume = preferences.appPreferences.playbackPreferences.skipBackOnResume,
                onSeek = seekBarState::onValueChange,
                onNext = viewModel::playNextUp,
                onPrevious = viewModel::playPreviousOrFetch,
                onChangeAudio = viewModel::changeAudioStream,
                onChangeSubtitle = { viewModel.changeSubtitleStream(it) },
                tracksRequest = tracksRequest,
                trickplay =
                    remember(state.currentPlayback?.item?.id, state.currentPlayback?.mediaSourceInfo?.id) {
                        viewModel.trickplaySource(state.currentPlayback)
                    },
                modifier = Modifier.padding(WindowInsets.systemBars.asPaddingValues()),
            )
        

            var subtitleStyleAppliedForHdr by remember { mutableStateOf(state.currentMediaInfo.videoStream?.hdr == true) }
            val subtitleSettings =
                remember(state.currentMediaInfo) {
                    Timber.v("subtitle choice: ${state.currentMediaInfo.videoStream?.hdr}")
                    val useSeparate =
                        preferences.appPreferences.interfacePreferences.subtitlesPreferences.useSeparateHdr
                    if (useSeparate && state.currentMediaInfo.videoStream?.hdr == true) {
                        preferences.appPreferences.interfacePreferences.hdrSubtitlesPreferences
                    } else {
                        preferences.appPreferences.interfacePreferences.subtitlesPreferences
                    }
                }
            val subtitleImageOpacity =
                remember(subtitleSettings) { subtitleSettings.imageSubtitleOpacity / 100f }

            // Subtitles
            val subtitleMaxSize by animateFloatAsState(if (controllerViewState.controlsVisible) .7f else 1f)
            val isImageSubtitles =
                remember(state.subtitleCues) { state.subtitleCues.firstOrNull()?.bitmap != null }

            // Reset cueCount when density changes to force re-applying subtitle style
            var cueCount by remember(density) { mutableIntStateOf(0) }

            val subtitleVisible =
                skipIndicatorDuration == 0L &&
                    state.currentPlayback?.subtitleIndexEnabled == true &&
                    !presentationState.coverSurface

            AndroidView(
                factory = { context ->
                    SubtitleView(context).apply {
                        subtitleSettings.applyTo(this)
                        playerInstance.assHandler?.let { assHandler ->
                            if (prefs.overrides.assPlaybackMode == AssPlaybackMode.ASS_LIBASS) {
                                Timber.v("Adding AssSubtitleView")
                                addView(
                                    AssSubtitleView(context, assHandler).apply {
                                        layoutParams =
                                            FrameLayout
                                                .LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                ).apply { gravity = Gravity.CENTER }
                                    },
                                )
                            }
                        }
                    }
                },
                update = { subtitleView ->
                    if (subtitleStyleAppliedForHdr != state.currentMediaInfo.videoStream?.hdr) {
                        // If the media has switched between SDR & HDR, reapply subtitle settings
                        Timber.v("Switching SDR/HDR, reapplying subtitle style")
                        subtitleSettings.applyTo(subtitleView)
                    }
                    subtitleView.setCues(state.subtitleCues)
                    if (state.subtitleCues.size > cueCount) {
                        Timber.i("Applying subtitle style to SubtitleView")
                        // The output creates a painter for each cue, so need to apply the changes when the number of cues increases
                        Media3SubtitleOverride(subtitleSettings.calculateEdgeSize(density))
                            .apply(subtitleView)
                        cueCount = state.subtitleCues.size
                    }
                    subtitleView.children.firstOrNull { it is AssSubtitleView }?.let {
                        (it as? AssSubtitleView)?.apply {
                            val resized =
                                layoutParams.let { it.width != playerSurfaceSize.width || it.height != playerSurfaceSize.height }

                            if (resized && playerSurfaceSize.width > 0 && playerSurfaceSize.height > 0) {
                                Timber.v("Resizing AssSubtitleView: %s", playerSurfaceSize)
                                layoutParams =
                                    FrameLayout
                                        .LayoutParams(
                                            playerSurfaceSize.width,
                                            playerSurfaceSize.height,
                                        ).apply { gravity = Gravity.CENTER }
                            }
                        }
                    }
                },
                onReset = {
                    it.setCues(null)
                },
                modifier =
                    Modifier
                        .fillMaxSize(subtitleMaxSize)
                        .align(Alignment.TopCenter)
                        .background(Color.Transparent)
                        .graphicsLayer {
                            alpha =
                                if (!subtitleVisible) {
                                    0f
                                } else if (isImageSubtitles) {
                                    subtitleImageOpacity
                                } else {
                                    1f
                                }
                        },
            )
        }

        // Ask to skip intros, etc button
        AnimatedVisibility(
            showSegment,
            modifier =
                Modifier
                    .padding(40.dp)
                    .align(Alignment.BottomEnd),
        ) {
            state.currentSegment?.let { segment ->
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    focusRequester.tryRequestFocus()
                    delay(10.seconds)
                    viewModel.updateSegment(segment.segment.id, true)
                }
                com.github.gionnio.platea.player.PlateaSkipSegmentButton(
                    type = segment.segment.type,
                    onClick = { viewModel.updateSegment(segment.segment.id, false) },
                    modifier = Modifier.focusRequester(focusRequester),
                )
            
            }
        }

        // Next up episode
        BackHandler(state.nextUp != null) {
            if (player.isPlaying) {
                scope.launch(ExceptionHandler()) {
                    viewModel.cancelUpNextEpisode()
                }
            } else {
                viewModel.navigationManager.goBack()
            }
        }
        AnimatedVisibility(
            state.nextUp != null,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter),
        ) {
            state.nextUp?.let {
                var autoPlayEnabled by remember { mutableStateOf(viewModel.shouldAutoPlayNextUp()) }
                var timeLeft by remember {
                    mutableLongStateOf(
                        preferences.appPreferences.playbackPreferences.autoPlayNextDelaySeconds,
                    )
                }
                BackHandler(timeLeft > 0 && autoPlayEnabled) {
                    timeLeft = -1
                    autoPlayEnabled = false
                }
                if (autoPlayEnabled) {
                    LaunchedEffect(Unit) {
                        if (timeLeft == 0L) {
                            viewModel.playNextUp()
                        } else {
                            while (timeLeft > 0) {
                                delay(1.seconds)
                                timeLeft--
                            }
                            if (timeLeft == 0L && autoPlayEnabled) {
                                viewModel.playNextUp()
                            }
                        }
                    }
                }
                com.github.gionnio.platea.player.PlateaNextUp(
                    episode = it,
                    imageUrl = LocalImageUrlService.current.getItemImageUrl(it, org.jellyfin.sdk.model.api.ImageType.PRIMARY, fillWidth = 480, useSeriesForPrimary = false),
                    secondsLeft = if (autoPlayEnabled && timeLeft >= 0) timeLeft else null,
                    totalSeconds = preferences.appPreferences.playbackPreferences.autoPlayNextDelaySeconds,
                    onPlay = {
                        viewModel.reportInteraction()
                        controllerViewState.hideControls()
                        viewModel.playNextUp()
                    },
                    modifier =
                        Modifier
                            .fillMaxHeight(1 - playerSize)
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                )
            
            }
        }
    }
}
