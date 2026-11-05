package com.github.gionnio.platea.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.github.gionnio.platea.home.HeroButton
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaType

/** One choice of an [ActionMenu]. */
data class MenuAction(
    val label: String,
    /** Shows the play icon (play, resume, start over). */
    val play: Boolean = false,
    val onClick: () -> Unit,
)

/** Long-press menu: title, optional subtitle, a column of buttons; the first one gets the focus. */
@Composable
fun ActionMenu(
    title: String,
    subtitle: String?,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // The menu opens while OK is still held (long press): ignore the release of that key and any repeat,
    // otherwise it would click the first option. Only a fresh key press arms the menu.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .onPreviewKeyEvent { event ->
                        if (armed) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            armed = true
                            false
                        } else {
                            // Swallow the held/released OK of the long press
                            event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                        }
                    }.width(300.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PlateaColors.Surface)
                    .border(BorderStroke(0.5.dp, PlateaColors.Avatar), RoundedCornerShape(10.dp))
                    .padding(18.dp),
        ) {
            Text(title, style = PlateaType.RowTitle, color = PlateaColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = PlateaType.CardSub, color = PlateaColors.TextMeta) }
            Spacer(Modifier.height(4.dp))
            actions.forEachIndexed { i, action ->
                HeroButton(
                    label = action.label,
                    primary = action.play,
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    onClick = action.onClick,
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
