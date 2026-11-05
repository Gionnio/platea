package com.github.gionnio.platea.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.gionnio.platea.R
import com.github.gionnio.platea.home.HeroButton
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaType

/** Start-up screen: the Platea mark and name on the velvet ground, with a thin gold line running underneath. */
@Composable
fun PlateaSplash(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(PlateaColors.Ground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Image(
                    painterResource(R.drawable.platea_mark),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Text("Platea", style = PlateaType.Brand.copy(fontSize = 30.sp), color = PlateaColors.Text)
            }
            Spacer(Modifier.height(22.dp))
            // Indeterminate progress: a short gold segment sliding along a faint track
            val track = 120.dp
            val segment = 36.dp
            val position by rememberInfiniteTransition(label = "splash").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
                label = "segment",
            )
            Box(Modifier.width(track).height(2.dp).clip(RoundedCornerShape(1.dp)).background(PlateaColors.Avatar)) {
                Box(
                    Modifier
                        .offset(x = (track - segment) * position)
                        .width(segment)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                        .background(PlateaColors.Gold),
                )
            }
        }
    }
}

/** Loading indicator for the Platea screens and the player: a thin gold ring, nothing else. */
@Composable
fun PlateaLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            color = PlateaColors.Gold,
            trackColor = PlateaColors.Gold.copy(alpha = 0.15f),
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(34.dp),
        )
    }
}

/** Mark and name in the top-left corner, as in the top bar: used on the sign-in screens. */
@Composable
fun PlateaBrand(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.then(Modifier.height(28.dp)),
    ) {
        Image(
            painterResource(R.drawable.platea_mark),
            contentDescription = null,
            modifier = Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)),
        )
        Spacer(Modifier.width(7.dp))
        Text("Platea", style = PlateaType.Brand, color = PlateaColors.Text)
    }
}

/** A page could not load: what happened, in plain words, and a button to try again. */
@Composable
fun PlateaError(
    detail: String?,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.platea_error_server),
    onRetry: () -> Unit,
) {
    val retry = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = PlateaType.RowTitle.copy(fontSize = 17.sp), color = PlateaColors.Text)
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = PlateaType.CardSub, color = PlateaColors.TextTertiary, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp).width(520.dp))
            }
            Spacer(Modifier.height(16.dp))
            HeroButton(stringResource(R.string.platea_retry), primary = false, modifier = Modifier.focusRequester(retry), onClick = onRetry)
        }
    }
}
