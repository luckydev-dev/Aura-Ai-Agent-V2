package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AuraTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import com.example.R
import kotlinx.coroutines.launch

enum class OrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ACTING,
    ERROR,
    RECONNECTING
}

@Composable
fun AuraOrb(
    state: OrbState,
    amplitude: Float, // 0.0 to 1.0 (mic or speaker)
    modifier: Modifier = Modifier,
    size: Dp = 260.dp,
    showHeart: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var isClicked by remember { mutableStateOf(false) }

    // One-shot spring scale response to user tap (no continuous looping)
    val clickScale by animateFloatAsState(
        targetValue = if (isClicked) 1.15f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        finishedListener = {
            if (isClicked) isClicked = false
        },
        label = "OrbClickScale"
    )

    // One-shot ripple animation on click
    val rippleProgress = remember { Animatable(0f) }
    val rippleAlpha = remember { Animatable(0f) }

    val handleTap: () -> Unit = {
        isClicked = true
        scope.launch {
            rippleAlpha.snapTo(0.75f)
            rippleProgress.snapTo(0f)
            launch {
                rippleProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
                )
            }
            rippleAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 500, easing = LinearEasing)
            )
        }
        onClick?.invoke()
    }
    val infiniteTransition = rememberInfiniteTransition(label = "OrbInfiniteTransition")

    // Breathing pulse for idle
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "IdlePulse"
    )

    // Swirl rotation for thinking
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SwirlRotation"
    )

    // Ripple radius multiplier for acting
    val actingRipple by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ActingRipple"
    )

    val actingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ActingAlpha"
    )

    // Interpolate amplitude smoothly
    val animatedAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy),
        label = "Amplitude"
    )

    val colors = AuraTheme.colors

    Box(
        modifier = modifier
            .size(size)
            .scale(clickScale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = handleTap
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_centre_button),
            contentDescription = "Maya Centre Button",
            modifier = Modifier
                .fillMaxSize()
                .scale(if (isClicked) 1.08f else (1.0f + animatedAmplitude * 0.05f))
        )
    }
}

private fun DrawScope.drawGlowAndOrb(
    center: Offset,
    radius: Float,
    c1: Color,
    c2: Color,
    c3: Color,
    alpha: Float
) {
    // 1. Ambient outer aura
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                c2.copy(alpha = 0.40f * alpha),
                c3.copy(alpha = 0.18f * alpha),
                Color.Transparent
            ),
            center = center,
            radius = radius * 1.7f
        ),
        radius = radius * 1.7f,
        center = center
    )

    // 2. Mid organic glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                c1.copy(alpha = 0.70f * alpha),
                c2.copy(alpha = 0.45f * alpha),
                Color.Transparent
            ),
            center = center,
            radius = radius * 1.25f
        ),
        radius = radius * 1.25f,
        center = center
    )

    // 3. Core fluid gradient sphere
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                c1.copy(alpha = alpha),
                c2.copy(alpha = alpha),
                c3.copy(alpha = alpha)
            ),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius)
        ),
        radius = radius,
        center = center
    )

    // 4. Subtle inner highlight shine
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.45f * alpha),
                Color.Transparent
            ),
            center = Offset(center.x - radius * 0.35f, center.y - radius * 0.35f),
            radius = radius * 0.7f
        ),
        radius = radius * 0.7f,
        center = Offset(center.x - radius * 0.35f, center.y - radius * 0.35f)
    )
}

private fun DrawScope.drawWaveformRings(
    center: Offset,
    radius: Float,
    amplitude: Float,
    color1: Color,
    color2: Color
) {
    val ringCount = 16
    val maxBarHeight = 32.dp.toPx() * amplitude

    for (i in 0 until ringCount) {
        val angleRad = (i.toFloat() / ringCount) * 2f * PI.toFloat()
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)

        val startX = center.x + radius * cosA
        val startY = center.y + radius * sinA
        val barLen = (maxBarHeight * (0.4f + 0.6f * sin(i * 1.5f).coerceAtLeast(0f)))
        val endX = center.x + (radius + barLen) * cosA
        val endY = center.y + (radius + barLen) * sinA

        drawLine(
            color = if (i % 2 == 0) color1.copy(alpha = 0.7f) else color2.copy(alpha = 0.7f),
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = 3.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

private fun DrawScope.drawSwirlOrb(
    center: Offset,
    radius: Float,
    angleDeg: Float,
    c1: Color,
    c2: Color,
    c3: Color
) {
    val rad = Math.toRadians(angleDeg.toDouble())
    val startX = center.x + (radius * cos(rad)).toFloat()
    val startY = center.y + (radius * sin(rad)).toFloat()
    val endX = center.x - (radius * cos(rad)).toFloat()
    val endY = center.y - (radius * sin(rad)).toFloat()

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(c1.copy(alpha = 0.4f), Color.Transparent),
            center = center,
            radius = radius * 1.5f
        ),
        radius = radius * 1.5f,
        center = center
    )

    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(c1, c2, c3),
            start = Offset(startX, startY),
            end = Offset(endX, endY)
        ),
        radius = radius,
        center = center
    )
}
