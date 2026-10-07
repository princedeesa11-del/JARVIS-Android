package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Visual styles for the JARVIS Holographic Orb / Core.
 */
enum class OrbStyle(val id: String, val displayName: String, val description: String) {
    PULSE_REACTOR(
        id = "pulse_reactor",
        displayName = "Pulse Reactor",
        description = "Rotary Tony Stark arc reactor with pulsing energy core, segmented arcs and concentric magnetic coils."
    ),
    PARTICLE_SWARM(
        id = "particle_swarm",
        displayName = "Particle Swarm",
        description = "Dynamic orbital particle cloud swirling in multi-phase elliptical rings around a graviton core."
    ),
    FRIDAY_INTERFACE(
        id = "friday_interface",
        displayName = "F.R.I.D.A.Y. Interface",
        description = "Futuristic Irish-AI tactical HUD with angular targeting brackets, telemetry ticks, and dual counter-rotating scan rings."
    ),
    NEURAL_SPHERE(
        id = "neural_sphere",
        displayName = "Neural Sphere",
        description = "Synaptic matrix with interconnected neural nodes and harmonic waveform oscillations."
    );

    companion object {
        fun fromId(id: String): OrbStyle {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: PULSE_REACTOR
        }
    }
}

/**
 * Color palettes for the JARVIS Holographic Orb & HUD elements.
 */
enum class OrbColorTheme(
    val id: String,
    val displayName: String,
    val primaryColor: Color,
    val glowColor: Color,
    val secondaryColor: Color,
    val accentColor: Color,
    val isGradient: Boolean = false
) {
    JARVIS_DEFAULT(
        id = "jarvis_cyan",
        displayName = "JARVIS Blue (Default)",
        primaryColor = Color(0xFF00E5FF),
        glowColor = Color(0xFF0091EA),
        secondaryColor = Color(0xFFFFD700),
        accentColor = Color(0xFF80D8FF)
    ),
    STARK_ORANGE(
        id = "stark_orange",
        displayName = "Stark Orange",
        primaryColor = Color(0xFFFF6D00),
        glowColor = Color(0xFFFF3D00),
        secondaryColor = Color(0xFFFFD600),
        accentColor = Color(0xFFFFAB40)
    ),
    DEEP_BLUE(
        id = "deep_blue",
        displayName = "Cobalt Blue",
        primaryColor = Color(0xFF2979FF),
        glowColor = Color(0xFF1565C0),
        secondaryColor = Color(0xFF00E5FF),
        accentColor = Color(0xFF82B1FF)
    ),
    NEON_GRADIENT(
        id = "neon_gradient",
        displayName = "Neon Gradient",
        primaryColor = Color(0xFFD500F9),
        glowColor = Color(0xFF651FFF),
        secondaryColor = Color(0xFF00E5FF),
        accentColor = Color(0xFFFF4081),
        isGradient = true
    ),
    TACTICAL_GREEN(
        id = "tactical_green",
        displayName = "Tactical Green",
        primaryColor = Color(0xFF00E676),
        glowColor = Color(0xFF00B248),
        secondaryColor = Color(0xFF76FF03),
        accentColor = Color(0xFFB9F6CA)
    ),
    SOLAR_GOLD(
        id = "solar_gold",
        displayName = "Solar Gold",
        primaryColor = Color(0xFFFFD700),
        glowColor = Color(0xFFFF8F00),
        secondaryColor = Color(0xFFFF3D00),
        accentColor = Color(0xFFFFE57F)
    ),
    CRIMSON_REACTOR(
        id = "crimson_reactor",
        displayName = "Crimson Core",
        primaryColor = Color(0xFFFF1744),
        glowColor = Color(0xFFD50000),
        secondaryColor = Color(0xFFFF9100),
        accentColor = Color(0xFFFF8A80)
    );

    companion object {
        fun fromId(id: String): OrbColorTheme {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: JARVIS_DEFAULT
        }
    }
}
