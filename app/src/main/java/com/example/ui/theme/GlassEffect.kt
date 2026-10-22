package com.example.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Liquid glass effect modifier for translucent cards with specular border highlights and soft depth.
 */
fun Modifier.liquidGlassCard(
    shape: Shape = RoundedCornerShape(20.dp),
    isDark: Boolean = false,
    elevation: Dp = 6.dp,
    tint: Color? = null
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        spotColor = if (isDark) Color(0x66000000) else Color(0x1F2563EB),
        ambientColor = if (isDark) Color(0x33000000) else Color(0x140F172A)
    )
    .clip(shape)
    .background(
        tint ?: if (isDark) DarkSurfaceGlass else LightSurfaceGlass
    )
    .border(
        width = 1.2.dp,
        brush = Brush.verticalGradient(
            colors = if (isDark) listOf(
                Color.White.copy(alpha = 0.28f),
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.03f)
            ) else listOf(
                Color.White.copy(alpha = 0.95f),
                Color.White.copy(alpha = 0.50f),
                Color.White.copy(alpha = 0.20f)
            )
        ),
        shape = shape
    )

/**
 * Specular highlight liquid glass for elevated floating elements (e.g. Navigation bar, Action pills).
 */
fun Modifier.liquidGlassElevated(
    shape: Shape = RoundedCornerShape(24.dp),
    isDark: Boolean = false
): Modifier = this
    .shadow(
        elevation = 12.dp,
        shape = shape,
        spotColor = if (isDark) Color(0x80000000) else Color(0x292563EB),
        ambientColor = if (isDark) Color(0x40000000) else Color(0x1A0F172A)
    )
    .clip(shape)
    .background(
        if (isDark) DarkSurfaceGlassElevated else LightSurfaceGlassElevated
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colors = if (isDark) listOf(
                Color.White.copy(alpha = 0.35f),
                Color.White.copy(alpha = 0.12f)
            ) else listOf(
                Color.White.copy(alpha = 0.98f),
                Color.White.copy(alpha = 0.60f)
            )
        ),
        shape = shape
    )

/**
 * Renders animated organic ambient light orbs that illuminate translucent liquid glass layers.
 */
@Composable
fun LiquidAmbientBackground(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ambient_liquid")
    val pulseAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isDark) DarkBackground else LightBackground)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Orb 1: Top Right Azure Glow
            val orb1Center = Offset(
                x = width * (0.85f - 0.08f * pulseAnim),
                y = height * (0.12f + 0.05f * pulseAnim)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = if (isDark) listOf(DarkOrbBlue, Color.Transparent) else listOf(LightOrbBlue, Color.Transparent),
                    center = orb1Center,
                    radius = width * 0.75f
                ),
                radius = width * 0.75f,
                center = orb1Center
            )

            // Orb 2: Top Left Violet Glow
            val orb2Center = Offset(
                x = width * (0.15f + 0.06f * pulseAnim),
                y = height * (0.28f - 0.04f * pulseAnim)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = if (isDark) listOf(DarkOrbPurple, Color.Transparent) else listOf(LightOrbPurple, Color.Transparent),
                    center = orb2Center,
                    radius = width * 0.65f
                ),
                radius = width * 0.65f,
                center = orb2Center
            )

            // Orb 3: Bottom Cyan Glow
            val orb3Center = Offset(
                x = width * (0.6f + 0.05f * pulseAnim),
                y = height * (0.82f + 0.04f * pulseAnim)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = if (isDark) listOf(DarkOrbCyan, Color.Transparent) else listOf(LightOrbCyan, Color.Transparent),
                    center = orb3Center,
                    radius = width * 0.85f
                ),
                radius = width * 0.85f,
                center = orb3Center
            )
        }

        content()
    }
}
