package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * State machine representing the real-time AI assistant voice cycle:
 * IDLE -> LISTENING -> THINKING/PROCESSING -> EXECUTING -> SPEAKING -> IDLE/LISTENING_AGAIN
 */
enum class OrbState {
    IDLE,
    LISTENING,
    THINKING,
    EXECUTING,
    SPEAKING,
    ERROR
}

/**
 * Rebuilt Futuristic Animated JARVIS Microphone Component.
 * Faithfully embodies the reference design:
 * - Stable, prominent central microphone icon
 * - IDLE: very smooth breathing pulse and subtle holographic glow
 * - LISTENING: expanding and contracting outer energy rings, soft cyan/blue glow, dynamic RMS voice modulation
 * - PROCESSING: active dual counter-rotating HUD arcs and subtle orbiting plasma shimmer
 * - SPEAKING: smooth acoustic wave and pulse ripple synchronized with speech
 * - Single canonical click action triggering the real voice system
 */
@Composable
fun ArcReactorOrb(
    state: OrbState,
    rmsLevel: Float = 0f,
    size: Dp = 170.dp,
    style: OrbStyle = OrbStyle.PULSE_REACTOR,
    colorTheme: OrbColorTheme = OrbColorTheme.JARVIS_DEFAULT,
    onClick: () -> Unit = {}
) {
    JarvisMicrophone(
        state = state,
        rmsLevel = rmsLevel,
        size = size,
        onClick = onClick
    )
}

/**
 * Direct reference-accurate Futuristic Animated JARVIS Microphone Composable.
 */
@Composable
fun JarvisMicrophone(
    state: OrbState,
    rmsLevel: Float = 0f,
    size: Dp = 170.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "jarvis_mic_anim")

    // Smooth breathing animation for IDLE state (3200ms cycle)
    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_breath"
    )

    // Continuous wave progression for LISTENING ripples (1500ms cycle)
    val waveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_progress"
    )

    // Processing rotations
    val rotClockwise by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rot_clockwise"
    )

    val rotCounterClockwise by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rot_counter"
    )

    // Speaking acoustic pulse (850ms cycle)
    val speakingPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speaking_pulse"
    )

    // Smooth state color transitions
    val targetPrimaryColor = when (state) {
        OrbState.IDLE -> JarvisCyan
        OrbState.LISTENING -> Color(0xFF00F0FF)
        OrbState.THINKING -> Color(0xFF9D4EDD)
        OrbState.EXECUTING -> Color(0xFF00E5FF)
        OrbState.SPEAKING -> Color(0xFF00E676)
        OrbState.ERROR -> JarvisAccentRed
    }

    val targetGlowColor = when (state) {
        OrbState.IDLE -> JarvisCyan.copy(alpha = 0.35f)
        OrbState.LISTENING -> Color(0xFF00F0FF).copy(alpha = 0.65f)
        OrbState.THINKING -> Color(0xFF9D4EDD).copy(alpha = 0.55f)
        OrbState.EXECUTING -> Color(0xFF00E5FF).copy(alpha = 0.65f)
        OrbState.SPEAKING -> Color(0xFF00E676).copy(alpha = 0.60f)
        OrbState.ERROR -> JarvisAccentRed.copy(alpha = 0.55f)
    }

    val primaryColor by animateColorAsState(targetPrimaryColor, tween(400), label = "primaryColor")
    val glowColor by animateColorAsState(targetGlowColor, tween(400), label = "glowColor")

    // Smooth audio RMS spring smoothing (eliminates sudden jumps, quiet voice = subtle, loud = strong)
    val smoothRms by animateFloatAsState(
        targetValue = rmsLevel.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "smoothRms"
    )

    // Dynamic RMS boost
    val voiceBoost = if (state == OrbState.LISTENING) {
        smoothRms * 0.45f
    } else 0f

    // Visually stable core capsule scale (subtle breathing/micro-pulse, never jumps around)
    val coreScale = when (state) {
        OrbState.IDLE -> 0.99f + idleBreath * 0.02f
        OrbState.LISTENING -> 1.0f + voiceBoost * 0.04f
        OrbState.THINKING, OrbState.EXECUTING -> 0.99f + idleBreath * 0.02f
        OrbState.SPEAKING -> 0.99f + speakingPulse * 0.03f
        OrbState.ERROR -> 1.0f
    }

    Box(
        modifier = modifier
            .size(size)
            .testTag("mic_button")
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // 1. Futuristic Animated Canvas Rings & Waves
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = this.size.minDimension / 2f
            val coreRadius = baseRadius * 0.46f

            // A. Ambient Radial Glow Aura
            val ambientAuraRadius = baseRadius * (if (state == OrbState.LISTENING) 0.98f + voiceBoost * 0.2f else 0.88f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = if (state == OrbState.IDLE) 0.16f + idleBreath * 0.12f else 0.35f),
                        glowColor.copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = ambientAuraRadius
                ),
                radius = ambientAuraRadius,
                center = center
            )

            // B. State-Specific Animated Elements
            when (state) {
                OrbState.IDLE -> {
                    // Subtle breathing outer ring
                    val idleRingRadius = coreRadius + (baseRadius - coreRadius) * (0.45f + idleBreath * 0.12f)
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.18f + idleBreath * 0.18f),
                        radius = idleRingRadius,
                        center = center,
                        style = Stroke(width = 1.8f)
                    )

                    // 4 subtle perimeter HUD alignment marks
                    val markRadius = idleRingRadius + 4f
                    for (i in 0 until 4) {
                        val angleRad = Math.toRadians((i * 90.0 + 45.0))
                        val p1 = Offset((center.x + (markRadius - 3f) * cos(angleRad)).toFloat(), (center.y + (markRadius - 3f) * sin(angleRad)).toFloat())
                        val p2 = Offset((center.x + (markRadius + 3f) * cos(angleRad)).toFloat(), (center.y + (markRadius + 3f) * sin(angleRad)).toFloat())
                        drawLine(
                            color = primaryColor.copy(alpha = 0.35f + idleBreath * 0.25f),
                            start = p1,
                            end = p2,
                            strokeWidth = 2f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                OrbState.LISTENING -> {
                    // 3 Expanding fluid ripple wave rings
                    val ringOffsets = floatArrayOf(0f, 0.33f, 0.66f)
                    for (offset in ringOffsets) {
                        val progress = (waveProgress + offset) % 1f
                        val currentRadius = coreRadius + (baseRadius - coreRadius) * progress * (1f + voiceBoost)
                        val ringAlpha = ((1f - progress) * (0.65f + voiceBoost * 0.4f)).coerceIn(0f, 0.9f)
                        val strokeW = (3.5f * (1f - progress * 0.5f)).coerceAtLeast(1.2f)

                        drawCircle(
                            color = primaryColor.copy(alpha = ringAlpha),
                            radius = currentRadius,
                            center = center,
                            style = Stroke(width = strokeW)
                        )
                    }

                    // Energized inner containment ring
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.85f),
                        radius = coreRadius + 6f + voiceBoost * 8f,
                        center = center,
                        style = Stroke(width = 2.5f)
                    )

                    // Dynamic constellation particle orbit field (exact video visual)
                    val particleCount = 28
                    for (i in 0 until particleCount) {
                        val particleAngle = Math.toRadians((i * (360.0 / particleCount) + waveProgress * 360.0))
                        val particleDist = coreRadius + 14f + (i % 5) * 9f + voiceBoost * 24f
                        val px = (center.x + particleDist * cos(particleAngle)).toFloat()
                        val py = (center.y + particleDist * sin(particleAngle)).toFloat()
                        val pAlpha = (0.40f + (i % 4) * 0.18f + voiceBoost * 0.4f).coerceIn(0f, 1f)
                        drawCircle(
                            color = if (i % 2 == 0) primaryColor.copy(alpha = pAlpha) else Color.White.copy(alpha = pAlpha),
                            radius = 2.4f + (i % 3) * 1.3f,
                            center = Offset(px, py)
                        )
                    }
                }

                OrbState.THINKING, OrbState.EXECUTING -> {
                    // Outer clockwise rotating segmented arc ring
                    rotate(rotClockwise, center) {
                        val segments = 3
                        val sweep = 360f / segments
                        for (i in 0 until segments) {
                            val startAngle = i * sweep
                            drawArc(
                                color = primaryColor.copy(alpha = 0.85f),
                                startAngle = startAngle,
                                sweepAngle = sweep * 0.55f,
                                useCenter = false,
                                topLeft = Offset(center.x - baseRadius * 0.82f, center.y - baseRadius * 0.82f),
                                size = Size(baseRadius * 1.64f, baseRadius * 1.64f),
                                style = Stroke(width = 3f, cap = StrokeCap.Round)
                            )
                        }
                    }

                    // Inner counter-clockwise rotating segmented arc ring
                    rotate(rotCounterClockwise, center) {
                        val segments = 2
                        val sweep = 360f / segments
                        for (i in 0 until segments) {
                            val startAngle = i * sweep + 30f
                            drawArc(
                                color = JarvisCyan.copy(alpha = 0.70f),
                                startAngle = startAngle,
                                sweepAngle = sweep * 0.45f,
                                useCenter = false,
                                topLeft = Offset(center.x - baseRadius * 0.66f, center.y - baseRadius * 0.66f),
                                size = Size(baseRadius * 1.32f, baseRadius * 1.32f),
                                style = Stroke(width = 2.2f, cap = StrokeCap.Round)
                            )
                        }
                    }

                    // Orbiting plasma photon particles
                    val orbitRadius = baseRadius * 0.74f
                    for (i in 0 until 4) {
                        val angleRad = Math.toRadians((rotClockwise + i * 90.0))
                        val px = (center.x + orbitRadius * cos(angleRad)).toFloat()
                        val py = (center.y + orbitRadius * sin(angleRad)).toFloat()
                        drawCircle(
                            color = primaryColor,
                            radius = 3.5f,
                            center = Offset(px, py)
                        )
                    }
                }

                OrbState.SPEAKING -> {
                    // Dynamic acoustic wave ripples (harmonic voice frequency arcs)
                    val waveCount = 3
                    for (i in 0 until waveCount) {
                        val progress = ((speakingPulse + i * 0.33f) % 1f)
                        val r = coreRadius + (baseRadius - coreRadius) * progress * 0.95f
                        val a = ((1f - progress) * 0.7f).coerceIn(0f, 0.8f)

                        drawCircle(
                            color = primaryColor.copy(alpha = a),
                            radius = r,
                            center = center,
                            style = Stroke(width = 2.8f)
                        )
                    }

                    // High-tech speech frequency tick circle
                    val tickRadius = baseRadius * 0.78f
                    val ticks = 16
                    for (i in 0 until ticks) {
                        val angleRad = Math.toRadians((i * (360.0 / ticks)))
                        val heightMod = (sin(speakingPulse * 6.28f + i) * 6f).toFloat()
                        val p1 = Offset((center.x + (tickRadius - 3f - heightMod) * cos(angleRad)).toFloat(), (center.y + (tickRadius - 3f - heightMod) * sin(angleRad)).toFloat())
                        val p2 = Offset((center.x + (tickRadius + 3f + heightMod) * cos(angleRad)).toFloat(), (center.y + (tickRadius + 3f + heightMod) * sin(angleRad)).toFloat())
                        drawLine(
                            color = primaryColor.copy(alpha = 0.5f + speakingPulse * 0.3f),
                            start = p1,
                            end = p2,
                            strokeWidth = 2f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                OrbState.ERROR -> {
                    // Alert perimeter ring
                    drawCircle(
                        color = JarvisAccentRed.copy(alpha = 0.75f),
                        radius = coreRadius + 12f,
                        center = center,
                        style = Stroke(width = 2f)
                    )
                }
            }
        }

        // 2. Central High-Tech Cockpit Disc with Stable Microphone Icon
        val coreDiameter = (size * 0.50f).coerceAtLeast(68.dp)
        Box(
            modifier = Modifier
                .size(coreDiameter)
                .scale(coreScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = if (state == OrbState.LISTENING) 0.35f else 0.18f),
                            Color(0xFF0A182A),
                            Color(0xFF03070E)
                        )
                    )
                )
                .border(
                    width = if (state == OrbState.LISTENING) 2.dp else 1.5.dp,
                    color = primaryColor.copy(alpha = if (state == OrbState.LISTENING) 0.95f else 0.65f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            val micIconSize = (coreDiameter * 0.54f).coerceAtLeast(32.dp)

            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "JARVIS Microphone",
                tint = if (state == OrbState.LISTENING) Color.White else primaryColor,
                modifier = Modifier.size(micIconSize)
            )
        }
    }
}

/**
 * Screen Edge Glow Layer.
 * Renders a smooth futuristic ambient glow around the perimeter of the screen:
 * - IDLE: very subtle, gentle breathing glow
 * - LISTENING: stronger luminous cyan/blue glow responsive to speech
 * - PROCESSING: smooth flowing perimeter light
 * - SPEAKING: smooth acoustic glow
 * - Zero touch consumption (placed non-intrusively in background)
 */
@Composable
fun JarvisScreenEdgeGlow(
    state: OrbState,
    rmsLevel: Float = 0f,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "edge_glow_anim")

    val idleBreath by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "edge_idle_breath"
    )

    val speakingPulse by infiniteTransition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "edge_speaking_pulse"
    )

    val flowPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "edge_flow"
    )

    val targetGlowAlpha = when (state) {
        OrbState.IDLE -> idleBreath
        OrbState.LISTENING -> (0.45f + (rmsLevel * 0.25f)).coerceIn(0.40f, 0.70f)
        OrbState.THINKING, OrbState.EXECUTING -> 0.40f
        OrbState.SPEAKING -> speakingPulse
        OrbState.ERROR -> 0.35f
    }

    val glowAlpha by animateFloatAsState(targetGlowAlpha, tween(350), label = "glowAlpha")

    val glowColor = when (state) {
        OrbState.IDLE -> JarvisCyan
        OrbState.LISTENING -> Color(0xFF00E5FF)
        OrbState.THINKING, OrbState.EXECUTING -> Color(0xFF8A2BE2)
        OrbState.SPEAKING -> Color(0xFF00E676)
        OrbState.ERROR -> JarvisAccentRed
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val effectiveAlpha = glowAlpha.coerceIn(0f, 1f)
        val brightAlpha = (effectiveAlpha * 1.25f).coerceIn(0f, 0.95f)
        val dimAlpha = effectiveAlpha * 0.35f

        // Top edge flowing glow
        val topOffset = (w * flowPhase)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    glowColor.copy(alpha = dimAlpha),
                    glowColor.copy(alpha = brightAlpha),
                    glowColor.copy(alpha = dimAlpha)
                ),
                startX = topOffset - w * 0.45f,
                endX = topOffset + w * 0.45f
            ),
            topLeft = Offset.Zero,
            size = Size(w, 40f)
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(glowColor.copy(alpha = effectiveAlpha * 0.45f), Color.Transparent),
                startY = 0f,
                endY = 46f
            ),
            topLeft = Offset.Zero,
            size = Size(w, 46f)
        )

        // Bottom edge flowing glow
        val bottomOffset = w * (1f - flowPhase)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    glowColor.copy(alpha = dimAlpha),
                    glowColor.copy(alpha = brightAlpha),
                    glowColor.copy(alpha = dimAlpha)
                ),
                startX = bottomOffset - w * 0.45f,
                endX = bottomOffset + w * 0.45f
            ),
            topLeft = Offset(0f, h - 40f),
            size = Size(w, 40f)
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, glowColor.copy(alpha = effectiveAlpha * 0.45f)),
                startY = h - 46f,
                endY = h
            ),
            topLeft = Offset(0f, h - 46f),
            size = Size(w, 46f)
        )

        // Left edge flowing glow
        val leftOffset = (h * flowPhase)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    glowColor.copy(alpha = dimAlpha * 0.65f),
                    glowColor.copy(alpha = brightAlpha * 0.65f),
                    glowColor.copy(alpha = dimAlpha * 0.65f)
                ),
                startY = leftOffset - h * 0.35f,
                endY = leftOffset + h * 0.35f
            ),
            topLeft = Offset.Zero,
            size = Size(30f, h)
        )

        // Right edge flowing glow
        val rightOffset = h * (1f - flowPhase)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    glowColor.copy(alpha = dimAlpha * 0.65f),
                    glowColor.copy(alpha = brightAlpha * 0.65f),
                    glowColor.copy(alpha = dimAlpha * 0.65f)
                ),
                startY = rightOffset - h * 0.35f,
                endY = rightOffset + h * 0.35f
            ),
            topLeft = Offset(w - 30f, 0f),
            size = Size(30f, h)
        )
    }
}

/**
 * Continuous Ambient Background Layer (Reference Video Style).
 * Renders smooth floating ambient neural particles and subtle light dust
 * behind the existing Jarvis UI.
 * - Runs smoothly at 60 FPS
 * - Zero touch blocking
 * - Synchronized with assistant state
 */
@Composable
fun JarvisAmbientBackground(
    state: OrbState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ambient_bg_anim")

    val driftTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f, // 2 * PI
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambient_drift"
    )

    val ambientColor = when (state) {
        OrbState.IDLE -> JarvisCyan
        OrbState.LISTENING -> Color(0xFF00E5FF)
        OrbState.THINKING, OrbState.EXECUTING -> Color(0xFF7B2CBF)
        OrbState.SPEAKING -> Color(0xFF00E676)
        OrbState.ERROR -> JarvisAccentRed
    }

    // Precomputed normalized seed positions for 10 floating nodes
    val seeds = remember {
        listOf(
            Triple(0.15f, 0.20f, 2.5f),
            Triple(0.85f, 0.25f, 3.2f),
            Triple(0.25f, 0.50f, 2.0f),
            Triple(0.75f, 0.65f, 3.0f),
            Triple(0.40f, 0.80f, 2.2f),
            Triple(0.90f, 0.85f, 2.8f),
            Triple(0.10f, 0.75f, 1.8f),
            Triple(0.55f, 0.35f, 3.5f),
            Triple(0.30f, 0.15f, 2.1f),
            Triple(0.70f, 0.10f, 2.7f)
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Subtle background radial gradient bloom
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    ambientColor.copy(alpha = if (state == OrbState.LISTENING) 0.08f else 0.04f),
                    Color.Transparent
                ),
                center = Offset(w * 0.5f, h * 0.22f),
                radius = w * 0.65f
            ),
            center = Offset(w * 0.5f, h * 0.22f),
            radius = w * 0.65f
        )

        // Draw the 10 smooth floating ambient particles
        for (i in seeds.indices) {
            val (baseX, baseY, radius) = seeds[i]
            val offsetX = sin(driftTime + i * 1.1f) * 22f
            val offsetY = cos(driftTime * 0.8f + i * 1.5f) * 26f
            val px = (w * baseX) + offsetX
            val py = (h * baseY) + offsetY

            val particleAlpha = (0.12f + sin(driftTime + i) * 0.06f).coerceIn(0.04f, 0.22f)

            drawCircle(
                color = ambientColor.copy(alpha = particleAlpha),
                radius = radius,
                center = Offset(px, py)
            )
        }
    }
}
