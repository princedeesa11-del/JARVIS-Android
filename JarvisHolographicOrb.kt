package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.OrbColorTheme
import com.example.ui.theme.OrbStyle
import kotlin.math.cos
import kotlin.math.sin

/**
 * Universal Unified JARVIS Holographic Orb Composable.
 * Renders multiple selectable holographic styles (Pulse Reactor, Particle Swarm,
 * F.R.I.D.A.Y. Interface, Neural Sphere) with customizable colors, sizing, and dynamic audio-reactive feedback.
 */
@Composable
fun JarvisHolographicOrb(
    state: OrbState,
    rmsLevel: Float = 0f,
    style: OrbStyle = OrbStyle.PULSE_REACTOR,
    colorTheme: OrbColorTheme = OrbColorTheme.JARVIS_DEFAULT,
    size: Dp = 150.dp,
    onClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "jarvis_orb_anim")

    val speedMultiplier = when (state) {
        OrbState.EXECUTING -> 0.4f
        OrbState.THINKING -> 0.6f
        OrbState.LISTENING -> 0.8f
        else -> 1.0f
    }

    // Core continuous rotations
    val rotationA by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (7000 * speedMultiplier).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rot_a"
    )

    val rotationB by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (4500 * speedMultiplier).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rot_b"
    )

    // Breathing pulse
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (state == OrbState.EXECUTING) 750 else 1600,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Secondary ripple for particles/waves
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    // Color resolution: base theme modulated by operational state
    val (primaryColor, glowColor, secondaryColor) = when (state) {
        OrbState.ERROR -> Triple(Color(0xFFFF1744), Color(0xFFD50000), Color(0xFFFF9100))
        OrbState.LISTENING -> Triple(Color(0xFFFFD700), Color(0xFFFF9100), colorTheme.primaryColor)
        OrbState.SPEAKING -> Triple(Color(0xFF00E676), Color(0xFF00C853), colorTheme.secondaryColor)
        OrbState.EXECUTING -> Triple(colorTheme.accentColor, colorTheme.primaryColor, colorTheme.secondaryColor)
        OrbState.THINKING -> Triple(Color(0xFF7C4DFF), Color(0xFF651FFF), colorTheme.primaryColor)
        OrbState.IDLE -> Triple(colorTheme.primaryColor, colorTheme.glowColor, colorTheme.secondaryColor)
    }

    val dynamicBoost = if (state == OrbState.LISTENING || state == OrbState.SPEAKING) {
        (rmsLevel * 0.45f).coerceIn(0f, 0.6f)
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = (this.size.minDimension / 2f) * (pulseScale + dynamicBoost)

            // Ambient glow halo behind every style
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.35f + dynamicBoost * 0.3f),
                        glowColor.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.25f
                ),
                radius = baseRadius * 1.25f,
                center = center
            )

            when (style) {
                OrbStyle.PULSE_REACTOR -> {
                    drawPulseReactor(
                        center = center,
                        radius = baseRadius,
                        rotA = rotationA,
                        rotB = rotationB,
                        primaryColor = primaryColor,
                        glowColor = glowColor,
                        secondaryColor = secondaryColor,
                        dynamicBoost = dynamicBoost
                    )
                }
                OrbStyle.PARTICLE_SWARM -> {
                    drawParticleSwarm(
                        center = center,
                        radius = baseRadius,
                        rotA = rotationA,
                        rotB = rotationB,
                        wavePhase = wavePhase,
                        primaryColor = primaryColor,
                        glowColor = glowColor,
                        secondaryColor = secondaryColor,
                        dynamicBoost = dynamicBoost
                    )
                }
                OrbStyle.FRIDAY_INTERFACE -> {
                    drawFridayInterface(
                        center = center,
                        radius = baseRadius,
                        rotA = rotationA,
                        rotB = rotationB,
                        primaryColor = primaryColor,
                        glowColor = glowColor,
                        secondaryColor = secondaryColor,
                        dynamicBoost = dynamicBoost
                    )
                }
                OrbStyle.NEURAL_SPHERE -> {
                    drawNeuralSphere(
                        center = center,
                        radius = baseRadius,
                        rotA = rotationA,
                        rotB = rotationB,
                        wavePhase = wavePhase,
                        primaryColor = primaryColor,
                        glowColor = glowColor,
                        secondaryColor = secondaryColor,
                        dynamicBoost = dynamicBoost
                    )
                }
            }
        }
    }
}

/**
 * Pulse Reactor style (Stark Arc Reactor geometry)
 */
private fun DrawScope.drawPulseReactor(
    center: Offset,
    radius: Float,
    rotA: Float,
    rotB: Float,
    primaryColor: Color,
    glowColor: Color,
    secondaryColor: Color,
    dynamicBoost: Float
) {
    // Outer Segmented Arc Ring
    rotate(rotA, center) {
        val segments = 8
        val sweep = 360f / segments
        for (i in 0 until segments) {
            val startAngle = i * sweep + (sweep * 0.15f)
            val arcSweep = sweep * 0.7f
            drawArc(
                color = primaryColor.copy(alpha = 0.85f),
                startAngle = startAngle,
                sweepAngle = arcSweep,
                useCenter = false,
                topLeft = Offset(center.x - radius * 0.86f, center.y - radius * 0.86f),
                size = Size(radius * 1.72f, radius * 1.72f),
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
        }
    }

    // Inner Concentric Ring with Coils
    rotate(rotB, center) {
        drawCircle(
            color = primaryColor.copy(alpha = 0.4f),
            radius = radius * 0.65f,
            center = center,
            style = Stroke(width = 2.5f)
        )

        val coils = 6
        for (i in 0 until coils) {
            val angleRad = Math.toRadians((i * (360.0 / coils)).toDouble())
            val r1 = radius * 0.52f
            val r2 = radius * 0.65f
            val p1 = Offset((center.x + r1 * cos(angleRad)).toFloat(), (center.y + r1 * sin(angleRad)).toFloat())
            val p2 = Offset((center.x + r2 * cos(angleRad)).toFloat(), (center.y + r2 * sin(angleRad)).toFloat())
            drawLine(
                color = secondaryColor.copy(alpha = 0.9f),
                start = p1,
                end = p2,
                strokeWidth = 4f,
                cap = StrokeCap.Round
            )
        }
    }

    // Core Inscription Ring
    drawCircle(
        color = primaryColor.copy(alpha = 0.6f),
        radius = radius * 0.42f,
        center = center,
        style = Stroke(width = 2f)
    )

    // Fusion Energy Core
    val coreRadius = radius * 0.28f * (1f + dynamicBoost * 0.5f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White,
                primaryColor,
                glowColor.copy(alpha = 0.5f),
                Color.Transparent
            ),
            center = center,
            radius = coreRadius * 1.25f
        ),
        radius = coreRadius,
        center = center
    )
}

/**
 * Particle Swarm style (Multi-orbit orbital swarm around a graviton singularity)
 */
private fun DrawScope.drawParticleSwarm(
    center: Offset,
    radius: Float,
    rotA: Float,
    rotB: Float,
    wavePhase: Float,
    primaryColor: Color,
    glowColor: Color,
    secondaryColor: Color,
    dynamicBoost: Float
) {
    // 3 orbital rings tilted and rotated
    val angles = listOf(rotA, rotB, (rotA + 180f) % 360f)
    val ringScales = listOf(0.85f, 0.68f, 0.50f)

    ringScales.forEachIndexed { idx, scale ->
        val ringRadius = radius * scale
        rotate(angles[idx], center) {
            drawCircle(
                color = primaryColor.copy(alpha = 0.25f),
                radius = ringRadius,
                center = center,
                style = Stroke(width = 1.5f)
            )

            // Orbiting particles along each ring
            val particleCount = 6 + idx * 2
            for (p in 0 until particleCount) {
                val pAngle = Math.toRadians((p * (360.0 / particleCount) + (wavePhase * 360.0)).toDouble())
                val px = (center.x + ringRadius * cos(pAngle)).toFloat()
                val py = (center.y + ringRadius * sin(pAngle)).toFloat()
                val pSize = (3.5f + (idx * 1.5f)) * (1f + dynamicBoost * 0.4f)

                drawCircle(
                    color = if (p % 2 == 0) primaryColor else secondaryColor,
                    radius = pSize,
                    center = Offset(px, py)
                )
            }
        }
    }

    // Graviton Core
    val coreRadius = radius * 0.25f * (1f + dynamicBoost * 0.5f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, primaryColor, glowColor.copy(alpha = 0.6f), Color.Transparent),
            center = center,
            radius = coreRadius * 1.4f
        ),
        radius = coreRadius,
        center = center
    )
}

/**
 * F.R.I.D.A.Y. Interface style (Angular Irish AI tactical HUD)
 */
private fun DrawScope.drawFridayInterface(
    center: Offset,
    radius: Float,
    rotA: Float,
    rotB: Float,
    primaryColor: Color,
    glowColor: Color,
    secondaryColor: Color,
    dynamicBoost: Float
) {
    // 1. Angular tactical corner brackets (4 corners)
    val bLen = radius * 0.85f
    val cornerLen = radius * 0.22f
    val corners = listOf(
        Offset(center.x - bLen, center.y - bLen), // Top-left
        Offset(center.x + bLen, center.y - bLen), // Top-right
        Offset(center.x + bLen, center.y + bLen), // Bottom-right
        Offset(center.x - bLen, center.y + bLen)  // Bottom-left
    )

    // Corner brackets
    drawBracket(corners[0], cornerLen, 1f, 1f, secondaryColor)
    drawBracket(corners[1], cornerLen, -1f, 1f, secondaryColor)
    drawBracket(corners[2], cornerLen, -1f, -1f, secondaryColor)
    drawBracket(corners[3], cornerLen, 1f, -1f, secondaryColor)

    // 2. Compass/Telemetry Outer Ring with ticks
    rotate(rotA, center) {
        drawCircle(
            color = primaryColor.copy(alpha = 0.45f),
            radius = radius * 0.78f,
            center = center,
            style = Stroke(width = 2f)
        )

        val ticks = 16
        for (i in 0 until ticks) {
            val angleRad = Math.toRadians((i * (360.0 / ticks)).toDouble())
            val rIn = if (i % 4 == 0) radius * 0.68f else radius * 0.72f
            val rOut = radius * 0.78f
            val p1 = Offset((center.x + rIn * cos(angleRad)).toFloat(), (center.y + rIn * sin(angleRad)).toFloat())
            val p2 = Offset((center.x + rOut * cos(angleRad)).toFloat(), (center.y + rOut * sin(angleRad)).toFloat())
            drawLine(
                color = if (i % 4 == 0) secondaryColor else primaryColor.copy(alpha = 0.6f),
                start = p1,
                end = p2,
                strokeWidth = if (i % 4 == 0) 3.5f else 1.8f
            )
        }
    }

    // 3. Counter-rotating HUD Reticle Segments
    rotate(rotB, center) {
        val sweep = 45f
        for (quad in 0..3) {
            val start = quad * 90f + 22.5f
            drawArc(
                color = primaryColor,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - radius * 0.58f, center.y - radius * 0.58f),
                size = Size(radius * 1.16f, radius * 1.16f),
                style = Stroke(width = 3.5f, cap = StrokeCap.Round)
            )
        }
    }

    // 4. Central Target Crosshair & Core
    val crossLen = radius * 0.28f
    drawLine(
        color = secondaryColor.copy(alpha = 0.8f),
        start = Offset(center.x - crossLen, center.y),
        end = Offset(center.x + crossLen, center.y),
        strokeWidth = 2f
    )
    drawLine(
        color = secondaryColor.copy(alpha = 0.8f),
        start = Offset(center.x, center.y - crossLen),
        end = Offset(center.x, center.y + crossLen),
        strokeWidth = 2f
    )

    val coreRadius = radius * 0.20f * (1f + dynamicBoost * 0.45f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, primaryColor, glowColor.copy(alpha = 0.6f), Color.Transparent),
            center = center,
            radius = coreRadius * 1.3f
        ),
        radius = coreRadius,
        center = center
    )
}

private fun DrawScope.drawBracket(corner: Offset, len: Float, dirX: Float, dirY: Float, color: Color) {
    val path = Path().apply {
        moveTo(corner.x + (dirX * len), corner.y)
        lineTo(corner.x, corner.y)
        lineTo(corner.x, corner.y + (dirY * len))
    }
    drawPath(path = path, color = color.copy(alpha = 0.85f), style = Stroke(width = 3f, cap = StrokeCap.Square))
}

/**
 * Neural Sphere style (Interconnected synaptic network oscillating with cognitive harmonics)
 */
private fun DrawScope.drawNeuralSphere(
    center: Offset,
    radius: Float,
    rotA: Float,
    rotB: Float,
    wavePhase: Float,
    primaryColor: Color,
    glowColor: Color,
    secondaryColor: Color,
    dynamicBoost: Float
) {
    // Dual oscillating concentric rings
    rotate(rotA, center) {
        val nodeCount = 10
        val ringR = radius * 0.72f
        val points = mutableListOf<Offset>()

        for (i in 0 until nodeCount) {
            val ang = Math.toRadians((i * (360.0 / nodeCount)).toDouble())
            val px = (center.x + ringR * cos(ang)).toFloat()
            val py = (center.y + ringR * sin(ang)).toFloat()
            points.add(Offset(px, py))
        }

        // Draw chords between nodes
        for (i in 0 until nodeCount) {
            val nextIdx = (i + 3) % nodeCount
            drawLine(
                color = primaryColor.copy(alpha = 0.3f),
                start = points[i],
                end = points[nextIdx],
                strokeWidth = 1.5f
            )
            drawCircle(
                color = if (i % 2 == 0) secondaryColor else primaryColor,
                radius = 4f,
                center = points[i]
            )
        }
    }

    rotate(rotB, center) {
        val innerNodeCount = 6
        val innerR = radius * 0.48f
        val innerPoints = mutableListOf<Offset>()

        for (i in 0 until innerNodeCount) {
            val ang = Math.toRadians((i * (360.0 / innerNodeCount)).toDouble())
            val px = (center.x + innerR * cos(ang)).toFloat()
            val py = (center.y + innerR * sin(ang)).toFloat()
            innerPoints.add(Offset(px, py))
        }

        for (i in 0 until innerNodeCount) {
            val next = (i + 1) % innerNodeCount
            drawLine(
                color = secondaryColor.copy(alpha = 0.5f),
                start = innerPoints[i],
                end = innerPoints[next],
                strokeWidth = 2f
            )
            drawCircle(
                color = Color.White,
                radius = 3.5f,
                center = innerPoints[i]
            )
        }
    }

    // Synaptic Nucleus
    val coreRadius = radius * 0.24f * (1f + dynamicBoost * 0.5f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, primaryColor, glowColor.copy(alpha = 0.5f), Color.Transparent),
            center = center,
            radius = coreRadius * 1.3f
        ),
        radius = coreRadius,
        center = center
    )
}
