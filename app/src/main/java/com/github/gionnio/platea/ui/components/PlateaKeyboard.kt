package com.github.gionnio.platea.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.gionnio.platea.R
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType

private val LETTERS = listOf("1234567890", "qwertyuiop", "asdfghjkl-", "zxcvbnm.:/")
private val SYMBOLS = listOf("1234567890", "!@#$%^&*()", "-_=+[]{};:", "'\",.<>/?\\|", "~`€£§°")
private val KeySize = 34.dp
private val KeyGap = 4.dp

/**
 * A text field for the remote: it only shows its value and takes the cursor like any button, so moving
 * between fields never opens a keyboard. OK opens the Platea keyboard ([PlateaKeyboardDialog]).
 */
@Composable
fun PlateaTextField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.ButtonRadius)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = PlateaColors.Ground.copy(alpha = 0.6f),
                contentColor = if (value.isEmpty()) PlateaColors.TextTertiary else PlateaColors.Text,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, PlateaColors.Border))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        modifier = modifier.height(36.dp),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                when {
                    value.isEmpty() -> placeholder
                    password -> "•".repeat(value.length)
                    else -> value
                },
                style = PlateaType.Button.copy(fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Platea on-screen keyboard: letters with shift, a symbols page, the text shown above (masked for passwords,
 * with a "show" key). A physical keyboard types into it too. Back cancels, "Done" confirms.
 */
@Composable
fun PlateaKeyboardDialog(
    title: String,
    initial: String,
    password: Boolean = false,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(!password) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val width = KeySize * 10 + KeyGap * 9

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(KeyGap),
            modifier =
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(PlateaColors.Surface)
                    .border(BorderStroke(0.5.dp, PlateaColors.Avatar), RoundedCornerShape(12.dp))
                    .padding(20.dp)
                    .width(width)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val char = event.nativeKeyEvent.unicodeChar
                        when {
                            event.key == Key.Backspace || event.key == Key.Delete -> {
                                text = text.dropLast(1)
                                true
                            }
                            char > 32 || (char == 32 && event.key == Key.Spacebar) -> {
                                text += char.toChar()
                                true
                            }
                            else -> false
                        }
                    },
        ) {
            Text(title.uppercase(), style = PlateaType.Kicker, color = PlateaColors.TextMeta)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(PlateaDimens.ButtonRadius))
                        .background(PlateaColors.Ground.copy(alpha = 0.6f))
                        .border(BorderStroke(1.dp, PlateaColors.Border), RoundedCornerShape(PlateaDimens.ButtonRadius))
                        .padding(horizontal = 12.dp),
            ) {
                Text(
                    if (reveal) text else "•".repeat(text.length),
                    style = PlateaType.RowTitle,
                    color = PlateaColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(Modifier.padding(start = 2.dp).width(1.5.dp).height(16.dp).background(PlateaColors.Gold))
            }
            Spacer(Modifier.height(4.dp))
            (if (symbols) SYMBOLS else LETTERS).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                    row.forEachIndexed { c, ch ->
                        val key = if (shift && !symbols) ch.uppercaseChar() else ch
                        Key(key.toString(), Modifier.size(KeySize).then(if (r == 1 && c == 0) Modifier.focusRequester(first) else Modifier)) { text += key }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                Key("⇧", Modifier.weight(1f).height(KeySize), active = shift && !symbols) { shift = !shift }
                Key(if (symbols) "abc" else "!#1", Modifier.weight(1.2f).height(KeySize), active = symbols) { symbols = !symbols }
                Key(stringResource(R.string.platea_key_space), Modifier.weight(2.4f).height(KeySize)) { text += " " }
                Key("⌫", Modifier.weight(1f).height(KeySize)) { text = text.dropLast(1) }
                Key(stringResource(R.string.platea_key_clear), Modifier.weight(1.8f).height(KeySize)) { text = "" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                if (password) {
                    Key(stringResource(if (reveal) R.string.platea_key_hide else R.string.platea_key_show), Modifier.weight(1f).height(KeySize), active = reveal) { reveal = !reveal }
                }
                Key(stringResource(R.string.platea_key_done), Modifier.weight(2f).height(KeySize)) { onDone(text) }
            }
        }
    }
}

@Composable
private fun Key(
    label: String,
    modifier: Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = PlateaColors.Ground.copy(alpha = 0.55f),
                contentColor = if (active) PlateaColors.Gold else PlateaColors.Text,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = if (active) Border(BorderStroke(1.dp, PlateaColors.Gold.copy(alpha = 0.7f)), shape = RoundedCornerShape(5.dp)) else Border.None,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = PlateaType.Button.copy(fontSize = if (label.length > 3) 9.5.sp else 13.sp), color = Color.Unspecified, maxLines = 1)
        }
    }
}
