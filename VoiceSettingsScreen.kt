package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.JarvisViewModel
import com.example.ui.theme.*
import com.example.voice.JarvisVoiceStyle
import com.example.voice.SupportedLanguage

/**
 * Settings → Voice Screen adhering to all user requirements:
 * 1. Jarvis Indian Male voice profile
 * 2. Existing Jarvis voice profile
 * 3. Other available system voices (dynamically inspected)
 * 4. Language selection (English, Indian English, Hindi, Gujarati)
 * 5. Speech rate and pitch modulation
 * 6. "Test Voice" button
 * 7. Current selected voice name display
 * 8. TTS engine name display
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    viewModel: JarvisViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val activeVoiceStyle by viewModel.activeVoiceStyle.collectAsState()
    val selectedVoiceName by viewModel.selectedVoiceName.collectAsState()
    val ttsEngineName by viewModel.ttsEngineName.collectAsState()
    val availableVoices by viewModel.availableVoices.collectAsState()
    val speechPitch by viewModel.speechPitch.collectAsState()
    val speechRate by viewModel.speechRate.collectAsState()
    val activeLanguage by viewModel.activeLanguage.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()

    var voiceFilter by remember { mutableStateOf("indian") } // "indian", "male", "all"
    var showAllSystemVoices by remember { mutableStateOf(false) }

    val filteredVoices = remember(availableVoices, voiceFilter) {
        when (voiceFilter) {
            "indian" -> availableVoices.filter { it.isIndian }
            "male" -> availableVoices.filter { it.isMale }
            else -> availableVoices
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("voice_settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = JarvisCyan
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Voice Settings",
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextPrimary,
                            fontSize = 18.sp
                        )
                        Text(
                            text = ttsEngineName,
                            fontSize = 11.sp,
                            color = JarvisCyan.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.testCurrentVoice()
                            Toast.makeText(context, "Testing current voice synthesis...", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("voice_settings_quick_test")
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.Default.GraphicEq else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Test Voice",
                            tint = if (isSpeaking) JarvisAccentGreen else JarvisCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF070F1E))
            )
        },
        containerColor = Color(0xFF070F1E),
        modifier = modifier.testTag("voice_settings_screen")
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(2.dp))
            }

            // ========================================================
            // ACTIVE ENGINE & SELECTED VOICE STATUS CARD
            // ========================================================
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A192F)),
                    border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("active_voice_status_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isSpeaking) JarvisAccentGreen else JarvisCyan)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isSpeaking) "SPEAKING NOW" else "TTS SYNTHESIS ENGINE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isSpeaking) JarvisAccentGreen else JarvisCyan
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF0F2B48)
                            ) {
                                Text(
                                    text = activeLanguage.code.uppercase(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = JarvisCyan,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Engine Name
                        Text(
                            text = ttsEngineName,
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextPrimary,
                            fontSize = 16.sp
                        )

                        // Current Selected Voice Name
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF050E1A))
                                .border(1.dp, JarvisCardBorder, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "ACTIVE VOICE HARMONIC",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = JarvisTextSecondary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = selectedVoiceName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = JarvisCyan
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Profile: ${activeVoiceStyle.title} • Pitch: ${String.format("%.2fx", speechPitch)} • Speed: ${String.format("%.2fx", speechRate)}",
                                fontSize = 11.sp,
                                color = JarvisTextSecondary
                            )
                        }
                    }
                }
            }

            // ========================================================
            // VOICE PROFILES (JARVIS INDIAN MALE & CORE PROFILES)
            // ========================================================
            item {
                Text(
                    text = "CALIBRATED VOICE PROFILES",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan
                )
            }

            items(JarvisVoiceStyle.entries) { style ->
                val isSelected = activeVoiceStyle == style
                val isIndianMale = style == JarvisVoiceStyle.INDIAN_MALE

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFF0D2545) else JarvisCardBg
                    ),
                    border = BorderStroke(
                        if (isSelected) 1.5.dp else 1.dp,
                        if (isSelected) JarvisCyan else JarvisCardBorder
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setVoiceStyle(style) }
                        .testTag("voice_profile_${style.id}")
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.setVoiceStyle(style) },
                                    colors = RadioButtonDefaults.colors(selectedColor = JarvisCyan)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = style.title,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) JarvisCyan else JarvisTextPrimary,
                                            fontSize = 15.sp
                                        )
                                        if (isIndianMale) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = Color(0xFF00384D)
                                            ) {
                                                Text(
                                                    text = "RECOMMENDED",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = JarvisCyan,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = style.subtitle,
                                        fontSize = 12.sp,
                                        color = JarvisTextSecondary
                                    )
                                }
                            }

                            Button(
                                onClick = { viewModel.testVoiceStyle(style) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) JarvisCyan else Color(0xFF132A4A)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("test_profile_${style.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Test voice",
                                    tint = if (isSelected) Color.Black else JarvisCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Test",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else JarvisCyan
                                )
                            }
                        }
                    }
                }
            }

            // ========================================================
            // LANGUAGE SELECTION
            // ========================================================
            item {
                Text(
                    text = "LANGUAGE & MULTILINGUAL BEHAVIOR",
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
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("language_selection_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Spoken Directive & Reply Language",
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextPrimary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Jarvis automatically adapts to Hindi and Gujarati scripts in responses, while defaulting to Indian English for general command execution.",
                            fontSize = 12.sp,
                            color = JarvisTextSecondary
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        SupportedLanguage.entries.forEach { lang ->
                            val isSelected = activeLanguage == lang
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) JarvisCyan.copy(alpha = 0.12f) else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setLanguage(lang) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { viewModel.setLanguage(lang) },
                                        colors = RadioButtonDefaults.colors(selectedColor = JarvisCyan)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = lang.displayName,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) JarvisCyan else JarvisTextPrimary,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "Locale: ${lang.code}",
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

            // ========================================================
            // SPEECH RATE & PITCH SLIDERS
            // ========================================================
            item {
                Text(
                    text = "ACOUSTIC MODULATION",
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
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("acoustic_modulation_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Speech Pitch
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Speech Pitch", fontSize = 13.sp, color = JarvisTextSecondary)
                                Text(
                                    text = String.format("%.2fx", speechPitch),
                                    fontSize = 13.sp,
                                    color = JarvisCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = speechPitch,
                                onValueChange = { viewModel.setSpeechPitch(it) },
                                valueRange = 0.5f..1.5f,
                                colors = SliderDefaults.colors(
                                    thumbColor = JarvisCyan,
                                    activeTrackColor = JarvisCyan
                                ),
                                modifier = Modifier.testTag("speech_pitch_slider")
                            )
                        }

                        // Speech Rate
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Speech Speed (Rate)", fontSize = 13.sp, color = JarvisTextSecondary)
                                Text(
                                    text = String.format("%.2fx", speechRate),
                                    fontSize = 13.sp,
                                    color = JarvisCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Slider(
                                value = speechRate,
                                onValueChange = { viewModel.setSpeechRate(it) },
                                valueRange = 0.6f..1.6f,
                                colors = SliderDefaults.colors(
                                    thumbColor = JarvisCyan,
                                    activeTrackColor = JarvisCyan
                                ),
                                modifier = Modifier.testTag("speech_rate_slider")
                            )
                        }

                        // Reset to Profile Default
                        OutlinedButton(
                            onClick = {
                                viewModel.setSpeechPitch(activeVoiceStyle.pitch)
                                viewModel.setSpeechRate(activeVoiceStyle.speechRate)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan),
                            border = BorderStroke(1.dp, JarvisCardBorder),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().testTag("reset_modulation_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Modulation to Profile Defaults", fontSize = 12.sp)
                        }
                    }
                }
            }

            // ========================================================
            // DYNAMICALLY INSPECTED SYSTEM VOICES
            // ========================================================
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "INSTALLED SYSTEM VOICES (${availableVoices.size})",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = JarvisCyan
                    )

                    TextButton(
                        onClick = { showAllSystemVoices = !showAllSystemVoices },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = if (showAllSystemVoices) "Collapse" else "Expand All",
                            color = JarvisCyan,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                    border = BorderStroke(1.dp, JarvisCardBorder),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("system_voices_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Device Text-to-Speech Voices",
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextPrimary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Dynamically detected from your Android OS TTS engine. You may pick an explicit system voice or retain the automated Jarvis Indian Male resolver.",
                            fontSize = 12.sp,
                            color = JarvisTextSecondary
                        )

                        // Filters row
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = voiceFilter == "indian",
                                onClick = { voiceFilter = "indian" },
                                label = { Text("Indian (${availableVoices.count { it.isIndian }})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = JarvisCyan,
                                    selectedLabelColor = Color.Black
                                )
                            )
                            FilterChip(
                                selected = voiceFilter == "male",
                                onClick = { voiceFilter = "male" },
                                label = { Text("Male (${availableVoices.count { it.isMale }})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = JarvisCyan,
                                    selectedLabelColor = Color.Black
                                )
                            )
                            FilterChip(
                                selected = voiceFilter == "all",
                                onClick = { voiceFilter = "all" },
                                label = { Text("All (${availableVoices.size})", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = JarvisCyan,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }

                        if (filteredVoices.isEmpty()) {
                            Text(
                                text = "No installed voices found matching current filter.",
                                fontSize = 12.sp,
                                color = JarvisTextSecondary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            val displayList = if (showAllSystemVoices) filteredVoices else filteredVoices.take(5)

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                displayList.forEach { voiceItem ->
                                    val isCurrentVoice = selectedVoiceName.contains(voiceItem.id, ignoreCase = true) || voiceItem.isSelected
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isCurrentVoice) JarvisCyan.copy(alpha = 0.15f) else Color(0xFF0F1B30),
                                        border = BorderStroke(
                                            1.dp,
                                            if (isCurrentVoice) JarvisCyan else JarvisCardBorder
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.selectCustomVoice(voiceItem.id) }
                                            .testTag("system_voice_${voiceItem.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = voiceItem.displayName,
                                                        fontWeight = if (isCurrentVoice) FontWeight.Bold else FontWeight.SemiBold,
                                                        color = if (isCurrentVoice) JarvisCyan else JarvisTextPrimary,
                                                        fontSize = 13.sp
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = Color(0xFF132A4A)
                                                    ) {
                                                        Text(
                                                            text = voiceItem.languageTag,
                                                            fontSize = 10.sp,
                                                            color = JarvisCyan,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                    if (voiceItem.isIndian) {
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = Color(0xFF00384D)
                                                        ) {
                                                            Text(
                                                                text = "Indian",
                                                                fontSize = 10.sp,
                                                                color = JarvisCyan,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = Color(0xFF1A2638)
                                                    ) {
                                                        Text(
                                                            text = if (voiceItem.isMale) "Male" else "Female",
                                                            fontSize = 10.sp,
                                                            color = JarvisTextSecondary,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Button(
                                                onClick = {
                                                    viewModel.selectCustomVoice(voiceItem.id)
                                                    viewModel.testCurrentVoice("Testing speech on ${voiceItem.displayName}.")
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isCurrentVoice) JarvisCyan else Color(0xFF1E3A5F)
                                                ),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.PlayArrow,
                                                    contentDescription = "Test voice",
                                                    tint = if (isCurrentVoice) Color.Black else JarvisCyan,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    text = "Select",
                                                    fontSize = 11.sp,
                                                    color = if (isCurrentVoice) Color.Black else JarvisCyan,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }

                                if (!showAllSystemVoices && filteredVoices.size > 5) {
                                    OutlinedButton(
                                        onClick = { showAllSystemVoices = true },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan),
                                        border = BorderStroke(1.dp, JarvisCardBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Show All ${filteredVoices.size} Voices", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ========================================================
            // PRIMARY "TEST VOICE" BUTTON & QUICK TEST CHIPS
            // ========================================================
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1F38)),
                    border = BorderStroke(1.5.dp, JarvisCyan),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().testTag("primary_test_voice_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.testCurrentVoice()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = JarvisCyan,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("primary_test_voice_button")
                        ) {
                            Icon(
                                imageVector = if (isSpeaking) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                                contentDescription = "Test Voice",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isSpeaking) "JARVIS Speaking..." else "Test Voice",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        Text(
                            text = "Quick Test Phrases:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextSecondary
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AssistChip(
                                onClick = {
                                    viewModel.testCurrentVoice("Hello! I am Jarvis, your intelligent assistant. All neural systems are fully operational.")
                                },
                                label = { Text("English (IN)", fontSize = 11.sp) },
                                colors = AssistChipDefaults.assistChipColors(labelColor = JarvisCyan)
                            )
                            AssistChip(
                                onClick = {
                                    viewModel.testCurrentVoice("नमस्ते! मैं जार्विस हूँ। आपके सभी निर्देश निष्पादन के लिए तैयार हैं।")
                                },
                                label = { Text("हिन्दी (Hindi)", fontSize = 11.sp) },
                                colors = AssistChipDefaults.assistChipColors(labelColor = JarvisCyan)
                            )
                            AssistChip(
                                onClick = {
                                    viewModel.testCurrentVoice("નમસ્તે! હું જાર્વિસ છું. આપની શી સેવા કરી શકું?")
                                },
                                label = { Text("ગુજરાતી (Gujarati)", fontSize = 11.sp) },
                                colors = AssistChipDefaults.assistChipColors(labelColor = JarvisCyan)
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
