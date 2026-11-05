package com.github.gionnio.platea.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.gionnio.platea.R
import com.github.gionnio.platea.home.HeroButton
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaType

private const val MIN_LENGTH = 4
private const val MAX_LENGTH = 8

/** Digits typed on a remote or keyboard that has number keys. */
private fun digit(key: Key): String? =
    when (key) {
        Key.Zero, Key.NumPad0 -> "0"
        Key.One, Key.NumPad1 -> "1"
        Key.Two, Key.NumPad2 -> "2"
        Key.Three, Key.NumPad3 -> "3"
        Key.Four, Key.NumPad4 -> "4"
        Key.Five, Key.NumPad5 -> "5"
        Key.Six, Key.NumPad6 -> "6"
        Key.Seven, Key.NumPad7 -> "7"
        Key.Eight, Key.NumPad8 -> "8"
        Key.Nine, Key.NumPad9 -> "9"
        else -> null
    }

/**
 * Asks for the profile PIN (digits, from the on-screen pad or the number keys of the remote). It checks by
 * itself: [onSuccess] as soon as the digits match, "wrong PIN" and a fresh start when they do not.
 *
 * @param onUsePassword adds a button to sign in with the server password instead (sign-in screen only)
 */
@Composable
fun PlateaPinVerify(
    expected: String,
    title: String,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
    onUsePassword: (() -> Unit)? = null,
) {
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    PinFrame(
        title = title,
        count = input.length,
        message = if (wrong) stringResource(R.string.platea_pin_wrong) else null,
        onDismiss = onDismiss,
        onDelete = { input = input.dropLast(1) },
        confirmLabel = null,
        onConfirm = {},
        extraLabel = onUsePassword?.let { stringResource(R.string.platea_pin_use_password) },
        onExtra = onUsePassword,
    ) { symbol ->
        wrong = false
        val next = input + symbol
        when {
            next == expected -> onSuccess()
            next.length >= expected.length -> {
                wrong = true
                input = ""
            }
            else -> input = next
        }
    }
}

/** Sets a new PIN: type it, OK, type it again, OK. */
@Composable
fun PlateaPinCreate(
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var first by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Int?>(null) }
    var step by remember { mutableStateOf(0) }
    PinFrame(
        step = step,
        title = stringResource(if (first == null) R.string.platea_pin_new else R.string.platea_pin_repeat),
        count = input.length,
        message = message?.let { stringResource(it) },
        onDismiss = onDismiss,
        onDelete = { input = input.dropLast(1) },
        confirmLabel = stringResource(R.string.platea_pin_ok),
        onConfirm = {
            val chosen = first
            when {
                input.length < MIN_LENGTH -> {
                    message = R.string.platea_pin_short
                    input = ""
                    step++
                }
                chosen == null -> {
                    first = input
                    input = ""
                    step++
                    message = null
                }
                input == chosen -> onCreated(chosen)
                else -> {
                    message = R.string.platea_pin_mismatch
                    first = null
                    input = ""
                    step++
                }
            }
        },
    ) { symbol ->
        message = null
        if (input.length < MAX_LENGTH) input += symbol
    }
}

/** Title, one dot per digit, message, and the number pad: arrows move on the pad, OK presses a key. */
@Composable
private fun PinFrame(
    title: String,
    count: Int,
    message: String?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    /** Bottom-right key of the pad (create: confirm); null leaves the place empty. */
    confirmLabel: String?,
    onConfirm: () -> Unit,
    extraLabel: String? = null,
    onExtra: (() -> Unit)? = null,
    /** Changes when the entry starts over: the cursor goes back to "1". */
    step: Any = Unit,
    onSymbol: (String) -> Unit,
) {
    val first = remember { FocusRequester() }
    val confirm = remember { FocusRequester() }
    // The cursor starts on "1" at every step (new PIN, repeat, after an error)…
    LaunchedEffect(step) { runCatching { first.requestFocus() } }
    // …and jumps to the confirm key as soon as the fourth digit is typed
    LaunchedEffect(count) { if (confirmLabel != null && count == MIN_LENGTH) runCatching { confirm.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .width(300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PlateaColors.Surface)
                    .border(BorderStroke(0.5.dp, PlateaColors.Avatar), RoundedCornerShape(12.dp))
                    .padding(22.dp)
                    // Number keys of a remote or keyboard type too
                    .onPreviewKeyEvent { event ->
                        val typed = digit(event.key)
                        val delete = event.key == Key.Backspace || event.key == Key.Delete
                        if (typed == null && !delete) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            if (typed != null) onSymbol(typed) else onDelete()
                        }
                        true
                    },
        ) {
            Text(title, style = PlateaType.RowTitle.copy(fontSize = 17.sp), color = PlateaColors.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(18.dp)) {
                repeat(maxOf(count, MIN_LENGTH)) { i ->
                    Box(
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (i < count) PlateaColors.Gold else Color.Transparent)
                            .border(BorderStroke(1.2.dp, if (i < count) PlateaColors.Gold else PlateaColors.Border), CircleShape),
                    )
                }
            }
            Text(message ?: " ", style = PlateaType.Body, color = Color(0xFFF87171), textAlign = TextAlign.Center)
            listOf("123", "456", "789").forEachIndexed { r, keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    keys.forEachIndexed { c, key ->
                        PadKey(key.toString(), if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier) { onSymbol(key.toString()) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PadKey("⌫", Modifier, onClick = onDelete)
                PadKey("0", Modifier) { onSymbol("0") }
                if (confirmLabel != null) PadKey(confirmLabel, Modifier.focusRequester(confirm), onClick = onConfirm) else Box(Modifier.size(PadWidth, PadHeight))
            }
            if (extraLabel != null && onExtra != null) {
                HeroButton(extraLabel, primary = false, modifier = Modifier.padding(top = 4.dp), onClick = onExtra)
            }
        }
    }
}

private val PadWidth = 62.dp
private val PadHeight = 40.dp

@Composable
private fun PadKey(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = PlateaColors.Ground.copy(alpha = 0.55f),
                contentColor = PlateaColors.Text,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = modifier.size(PadWidth, PadHeight),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = PlateaType.Button.copy(fontSize = if (label.length > 1) 12.sp else 16.sp))
        }
    }
}
