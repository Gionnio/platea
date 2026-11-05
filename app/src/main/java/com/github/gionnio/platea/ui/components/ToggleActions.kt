package com.github.gionnio.platea.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens

/** Square icon button with the Platea look; the content draws its own state. */
@Composable
private fun ToggleButton(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interaction,
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
        modifier = modifier.size(PlateaDimens.ButtonHeight),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content(focused) }
    }
}

/** Short "pop" when the state flips, so the press is visible even with the cursor still on the button. */
@Composable
private fun popScale(state: Boolean): Float {
    var last by remember { mutableStateOf(state) }
    var popping by remember { mutableStateOf(false) }
    if (last != state) {
        last = state
        popping = true
    }
    val scale by animateFloatAsState(
        targetValue = if (popping) 1.25f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        finishedListener = { popping = false },
        label = "pop",
    )
    return scale
}

/**
 * Watched toggle: an empty ring with a check when not watched, a filled circle when watched (gold, dark when focused).
 * The state flips immediately on press (optimistic), the server call follows.
 */
@Composable
fun WatchedToggle(
    watched: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
) {
    var state by remember(watched) { mutableStateOf(watched) }
    ToggleButton(label, modifier, onClick = {
        state = !state
        onToggle(state)
    }) { focused ->
        // Focused: everything dark like the button text; not focused: gold when watched
        val on = if (focused) PlateaColors.Ground else PlateaColors.Gold
        val off = if (focused) PlateaColors.Ground else PlateaColors.Text
        val fill by animateColorAsState(if (state) on else Color.Transparent, tween(180), label = "fill")
        val ring = if (state) on else off
        val check = if (state) (if (focused) PlateaColors.Text else PlateaColors.Ground) else off
        Box(
            Modifier
                .scale(popScale(state))
                .size(13.dp)
                .clip(CircleShape)
                .background(fill)
                .border(BorderStroke(1.2.dp, ring), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = label, tint = check, modifier = Modifier.size(9.dp))
        }
    }
}
