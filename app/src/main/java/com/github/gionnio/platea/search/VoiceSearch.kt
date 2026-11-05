package com.github.gionnio.platea.search

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.github.gionnio.platea.R
import com.github.gionnio.platea.ui.components.VoiceInputManager
import com.github.gionnio.platea.ui.components.VoiceInputState
import com.github.gionnio.platea.home.HeroButton
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaType

/**
 * Voice search, only where Android speech recognition exists (Google TV: TCL, Sony). Fire TV has no
 * recognition service and Alexa dictation does not reach sideloaded apps: there the VOICE key is hidden.
 */
@Composable
fun VoiceSearch(
    voice: VoiceInputManager,
    onQuery: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .width(400.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PlateaColors.Surface)
                    .border(BorderStroke(0.5.dp, PlateaColors.Avatar), RoundedCornerShape(12.dp))
                    .padding(22.dp),
        ) {
            Listening(voice, onQuery, onDismiss)
        }
    }
}

@Composable
private fun Listening(
    voice: VoiceInputManager,
    onQuery: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by voice.state.collectAsState()
    val partial by voice.partialResult.collectAsState()
    val level by voice.soundLevel.collectAsState()
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) voice.onPermissionGranted() else voice.onPermissionDenied()
        }
    LaunchedEffect(Unit) { if (voice.hasPermission) voice.startListening() else permission.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(Unit) { onDispose { voice.stopListening() } }
    LaunchedEffect(state) {
        (state as? VoiceInputState.Result)?.let {
            onQuery(it.text)
            voice.acknowledge()
            onDismiss()
        }
    }
    val button = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { button.requestFocus() } }
    val error = state as? VoiceInputState.Error
    Text(
        when (state) {
            is VoiceInputState.Error -> stringResource(error!!.messageResId)
            VoiceInputState.Processing -> stringResource(R.string.platea_voice_processing)
            VoiceInputState.Listening -> stringResource(R.string.platea_voice_listening)
            else -> stringResource(R.string.platea_voice_starting)
        },
        style = PlateaType.RowTitle.copy(fontSize = 17.sp),
        color = if (error != null) PlateaColors.TextSecondary else PlateaColors.Text,
    )
    // What has been understood so far
    Text(partial.ifBlank { " " }, style = PlateaType.HeroTitle.copy(fontSize = 22.sp), color = PlateaColors.Gold, maxLines = 2)
    // Microphone level
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(PlateaColors.Avatar)) {
        Box(Modifier.fillMaxWidth(if (state == VoiceInputState.Listening) level.coerceIn(0.02f, 1f) else 0f).fillMaxHeight().background(PlateaColors.Gold))
    }
    if (error != null && error.isRetryable) {
        HeroButton(stringResource(R.string.platea_voice_retry), primary = false, modifier = Modifier.fillMaxWidth().focusRequester(button)) { voice.startListening() }
    } else {
        HeroButton(stringResource(R.string.platea_voice_close), primary = false, modifier = Modifier.fillMaxWidth().focusRequester(button), onClick = onDismiss)
    }
}
