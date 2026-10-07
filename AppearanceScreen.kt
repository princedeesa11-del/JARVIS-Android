package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.JarvisViewModel
import com.example.ui.components.JarvisHolographicOrb
import com.example.ui.components.OrbState
import com.example.ui.theme.*

/**
 * JARVIS Appearance & Holographic Orb Customization Screen.
 * Provides live interactive customization of:
 * 1. Orb Style (Pulse Reactor, Particle Swarm, F.R.I.D.A.Y. Interface, Neural Sphere)
 * 2. Colour Themes (Default Blue, Orange, Cobalt Blue, Neon Gradient, Green, Gold, Crimson)
 * 3. Floating Orb Size Slider with immediate live preview and persistence
 * 4. "Use Orb on Home" ON/OFF toggle (switches between Holographic Core Orb and Tactical AI Avatar)
 * All preferences are persisted locally and restored on restart/reboot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    viewModel: JarvisViewModel,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val orbStyle by viewModel.orbStyle.collectAsState()
    val orbColorTheme by viewModel.orbColorTheme.collectAsState()
    val orbSizeDp by viewModel.orbSizeDp.collectAsState()
    val useOrbOnHome by viewModel.useOrbOnHome.collectAsState()
    val orbState by viewModel.orbState.collectAsState()
    val rmsLevel by viewModel.rmsLevel.collectAsState()

    // Test simulation state for preview (Cycle states to see how the orb looks when idle, listening, executing, speaking)
    var previewOrbState by remember { mutableStateOf(OrbState.IDLE) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "APPEARANCE & ORB",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = JarvisCyan,
                            fontSize = 18.sp,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Holographic Core Customization",
                            fontSize = 11.sp,
                            color = JarvisTextSecondary
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = JarvisCyan
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = JarvisDeepBg)
            )
        },
        containerColor = JarvisDeepBg,
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // SECTION: LIVE INTERACTIVE PREVIEW CARD
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = BorderStroke(1.dp, orbColorTheme.primaryColor.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xFF070F1E),
                                        Color(0xFF0D1C34),
                                        Color(0xFF070F1E)
                                    )
                                )
                            )
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "LIVE CORE PREVIEW",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = orbColorTheme.primaryColor,
                                letterSpacing = 1.5.sp
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = orbColorTheme.primaryColor.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, orbColorTheme.primaryColor.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "${orbSizeDp}dp • ${orbStyle.displayName}",
                                    fontSize = 10.sp,
                                    color = JarvisTextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Live Rendering of the customized Holographic Orb
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            JarvisHolographicOrb(
                                state = previewOrbState,
                                rmsLevel = if (previewOrbState == OrbState.LISTENING || previewOrbState == OrbState.SPEAKING) 0.65f else rmsLevel,
                                style = orbStyle,
                                colorTheme = orbColorTheme,
                                size = orbSizeDp.dp,
                                onClick = {
                                    // Cycle simulation state on tap for interactive previewing
                                    previewOrbState = when (previewOrbState) {
                                        OrbState.IDLE -> OrbState.LISTENING
                                        OrbState.LISTENING -> OrbState.THINKING
                                        OrbState.THINKING -> OrbState.EXECUTING
                                        OrbState.EXECUTING -> OrbState.SPEAKING
                                        OrbState.SPEAKING -> OrbState.IDLE
                                        OrbState.ERROR -> OrbState.IDLE
                                    }
                                }
                            )
                        }

                        Text(
                            text = "Tap preview orb to test animation states (${previewOrbState.name})",
                            fontSize = 11.sp,
                            color = JarvisTextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // SECTION 1: ORB STYLE SELECTOR
            item {
                Text(
                    text = "1. ORB VISUAL STYLE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OrbStyle.values().forEach { style ->
                            val isSelected = style == orbStyle
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) orbColorTheme.primaryColor.copy(alpha = 0.15f) else Color(0xFF091220),
                                border = BorderStroke(
                                    1.5.dp,
                                    if (isSelected) orbColorTheme.primaryColor else Color(0xFF14243A)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setOrbStyle(style) }
                                    .testTag("style_${style.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { viewModel.setOrbStyle(style) },
                                        colors = RadioButtonDefaults.colors(selectedColor = orbColorTheme.primaryColor)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = style.displayName,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) orbColorTheme.primaryColor else JarvisTextPrimary,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = style.description,
                                            fontSize = 11.sp,
                                            color = JarvisTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 2: COLOUR THEMES
            item {
                Text(
                    text = "2. HOLOGRAPHIC COLOUR PALETTE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(OrbColorTheme.values()) { palette ->
                                val isSelected = palette == orbColorTheme
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) Color(0xFF162D4A) else Color(0xFF0A1424),
                                    border = BorderStroke(
                                        2.dp,
                                        if (isSelected) palette.primaryColor else Color(0xFF182840)
                                    ),
                                    modifier = Modifier
                                        .clickable { viewModel.setOrbColorTheme(palette) }
                                        .testTag("color_${palette.id}")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (palette.isGradient) {
                                                        Brush.linearGradient(
                                                            colors = listOf(palette.primaryColor, palette.secondaryColor)
                                                        )
                                                    } else {
                                                        Brush.radialGradient(
                                                            colors = listOf(Color.White, palette.primaryColor)
                                                        )
                                                    }
                                                )
                                                .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = palette.displayName,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) palette.primaryColor else JarvisTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // SECTION 3: FLOATING ORB SIZE SLIDER
            item {
                Text(
                    text = "3. FLOATING ORB SCALE / SIZE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Scale Radius",
                                fontWeight = FontWeight.Bold,
                                color = JarvisTextPrimary,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "${orbSizeDp} dp",
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = orbColorTheme.primaryColor,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = orbSizeDp.toFloat(),
                            onValueChange = { viewModel.setOrbSizeDp(it.toInt()) },
                            valueRange = 80f..220f,
                            steps = 14,
                            colors = SliderDefaults.colors(
                                thumbColor = orbColorTheme.primaryColor,
                                activeTrackColor = orbColorTheme.primaryColor,
                                inactiveTrackColor = Color(0xFF14243A)
                            ),
                            modifier = Modifier.testTag("orb_size_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Compact (80dp)", fontSize = 10.sp, color = JarvisTextSecondary)
                            Text("Balanced (140dp)", fontSize = 10.sp, color = JarvisTextSecondary)
                            Text("Expansive (220dp)", fontSize = 10.sp, color = JarvisTextSecondary)
                        }
                    }
                }
            }

            // SECTION 4: USE ORB ON HOME TOGGLE
            item {
                Text(
                    text = "4. HOME DISPLAY MODE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Use Holographic Orb on Home",
                                    fontWeight = FontWeight.Bold,
                                    color = JarvisTextPrimary,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (useOrbOnHome) {
                                        "Enabled: Home screen renders the real-time Holographic Arc Reactor Orb."
                                    } else {
                                        "Disabled: Home screen displays the Tactical JARVIS Character HUD Avatar."
                                    },
                                    fontSize = 12.sp,
                                    color = JarvisTextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = useOrbOnHome,
                                onCheckedChange = { viewModel.setUseOrbOnHome(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = orbColorTheme.primaryColor
                                ),
                                modifier = Modifier.testTag("use_orb_on_home_switch")
                            )
                        }
                    }
                }
            }

            // Reset to JARVIS Defaults Button
            item {
                OutlinedButton(
                    onClick = {
                        viewModel.setOrbStyle(OrbStyle.PULSE_REACTOR)
                        viewModel.setOrbColorTheme(OrbColorTheme.JARVIS_DEFAULT)
                        viewModel.setOrbSizeDp(140)
                        viewModel.setUseOrbOnHome(true)
                    },
                    border = BorderStroke(1.dp, JarvisCardBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                        .testTag("reset_appearance_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset Defaults",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reset to Default JARVIS Hologram",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
