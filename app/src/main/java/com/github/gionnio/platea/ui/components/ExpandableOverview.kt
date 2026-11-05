package com.github.gionnio.platea.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaType

/**
 * Overview text cut to [maxLines]. When it does not fit, the remote can reach it (up from the buttons) and OK
 * expands it in place, pushing the rest of the page down; OK again or leaving it folds it back.
 * When it fits it is plain text that focus skips.
 */
@Composable
fun ExpandableOverview(
    text: String,
    width: Dp,
    style: TextStyle = PlateaType.Body,
    maxLines: Int = 2,
) {
    var overflows by remember(text) { mutableStateOf(false) }
    var expanded by remember(text) { mutableStateOf(false) }
    if (!overflows) {
        Text(
            text,
            style = style,
            color = PlateaColors.TextSecondary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (it.hasVisualOverflow) overflows = true },
            modifier = Modifier.width(width),
        )
        return
    }
    Surface(
        onClick = { expanded = !expanded },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = PlateaColors.TextSecondary,
                focusedContainerColor = PlateaColors.Surface.copy(alpha = 0.85f),
                focusedContentColor = PlateaColors.Text,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier =
            Modifier
                .width(width + 12.dp)
                .onFocusChanged { if (!it.hasFocus) expanded = false }
                .animateContentSize(tween(200)),
    ) {
        Text(
            text,
            style = style,
            maxLines = if (expanded) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}
