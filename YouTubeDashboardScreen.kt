package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.ui.JarvisViewModel
import com.example.ui.theme.JarvisCyan
import com.example.youtube.config.YouTubeConfigStore
import com.example.youtube.engine.YouTubeAutomationEngine
import com.example.youtube.model.YouTubePlaybackTarget
import com.example.youtube.model.YouTubeVideoItem
import kotlinx.coroutines.launch

private val BgDark = Color(0xFF0A0E17)
private val CardBg = Color(0xFF121B2B)
private val CardBorder = Color(0xFF1E2D4A)
private val AccentRed = Color(0xFFFF0000)
private val AccentGold = Color(0xFFFFB300)
private val TextWhite = Color(0xFFEEEEEE)
private val TextMuted = Color(0xFF90A4AE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeDashboardScreen(
    viewModel: JarvisViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { YouTubeAutomationEngine.getInstance(context) }
    val configStore = remember { YouTubeConfigStore.getInstance(context) }
    val state by engine.state.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showConfigDialog by remember { mutableStateOf(false) }
    var selectedPlaybackTarget by remember { mutableStateOf(configStore.playbackTarget) }
    var embeddedVideoId by remember { mutableStateOf<String?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.activeVideo) {
        if (state.activeVideo != null && selectedPlaybackTarget == YouTubePlaybackTarget.EMBEDDED) {
            embeddedVideoId = state.activeVideo?.videoId
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentRed),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "YouTube",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "YouTube Automation",
                                color = TextWhite,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Voice & Media Intelligence",
                                color = JarvisCyan,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("youtube_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextWhite
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showConfigDialog = true },
                        modifier = Modifier.testTag("youtube_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = JarvisCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        containerColor = BgDark
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                // Search Input Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, CardBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "SEARCH OR PLAY VIDEO",
                            color = JarvisCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search song or video (e.g. Kesariya)", color = TextMuted, fontSize = 13.sp) },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = "Search", tint = JarvisCyan)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                                    }
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedBorderColor = JarvisCyan,
                                unfocusedBorderColor = CardBorder,
                                focusedContainerColor = Color(0xFF0C1322),
                                unfocusedContainerColor = Color(0xFF0C1322)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("youtube_search_input")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (searchQuery.isNotBlank()) {
                                        scope.launch {
                                            val res = engine.executePlay(searchQuery)
                                            snackbarMessage = res.speechResponse
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("youtube_play_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play Video", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    if (searchQuery.isNotBlank()) {
                                        scope.launch {
                                            val res = engine.executeSearch(searchQuery)
                                            snackbarMessage = res.speechResponse
                                        }
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan),
                                border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("youtube_search_button")
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Search", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Quick Voice Command Chips
            item {
                Text(
                    text = "COMMAND TESTING (EN • HI • GU)",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 2.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val quickCommands = listOf(
                        "Play Kesariya",
                        "YouTube પર Kesariya વગાડો",
                        "Arijit Singh ka song chalao",
                        "Search Hanuman Chalisa",
                        "Play Believer",
                        "YouTube પર Arijit Singh નું song શોધો",
                        "યુટ્યુબ પર Hanuman Chalisa શોધો",
                        "Open YouTube",
                        "YouTube ખોલો",
                        "Pause YouTube",
                        "Resume YouTube",
                        "Agla video chalao",
                        "આગળનું ગીત ચલાવો"
                    )
                    items(quickCommands) { cmd ->
                        SuggestionChip(
                            onClick = {
                                searchQuery = cmd
                                scope.launch {
                                    val res = engine.handleDirective(cmd)
                                    if (res != null) {
                                        snackbarMessage = res.speechResponse
                                    }
                                }
                            },
                            label = { Text(cmd, fontSize = 12.sp, color = TextWhite) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = CardBg
                            ),
                            border = BorderStroke(1.dp, CardBorder),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }

            // Active Video Card or Embedded Player
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    border = BorderStroke(1.dp, if (state.isPlaying) JarvisCyan else CardBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "NOW ACTIVE / LOADED",
                                color = JarvisCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (state.activeVideo != null) {
                                Surface(
                                    color = if (state.isPlaying) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFFFF9100).copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (state.isPlaying) "PLAYING" else "READY",
                                        color = if (state.isPlaying) Color(0xFF00E676) else Color(0xFFFF9100),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val active = state.activeVideo
                        if (embeddedVideoId != null && selectedPlaybackTarget == YouTubePlaybackTarget.EMBEDDED) {
                            EmbeddedYouTubePlayer(
                                videoId = embeddedVideoId!!,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        } else if (active != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (active.thumbnailUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = active.thumbnailUrl,
                                        contentDescription = active.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 110.dp, height = 70.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Black)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 110.dp, height = 70.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.DarkGray),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Movie, contentDescription = null, tint = TextMuted)
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = active.title,
                                        color = TextWhite,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = active.channelTitle.ifBlank { "YouTube Video" },
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                    if (active.videoId.isNotBlank()) {
                                        Text(
                                            text = "ID: ${active.videoId}",
                                            color = JarvisCyan.copy(alpha = 0.7f),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "No active video. Say 'Play Kesariya' or use the search box above.",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Controls Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            IconButton(
                                onClick = { scope.launch { engine.executePrevious() } },
                                modifier = Modifier.testTag("yt_prev_btn")
                            ) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = TextWhite)
                            }
                            IconButton(
                                onClick = { scope.launch { engine.executePause() } },
                                modifier = Modifier.testTag("yt_pause_btn")
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause", tint = AccentGold)
                            }
                            IconButton(
                                onClick = { scope.launch { engine.executeResume() } },
                                modifier = Modifier.testTag("yt_resume_btn")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color(0xFF00E676))
                            }
                            IconButton(
                                onClick = { scope.launch { engine.executeStop() } },
                                modifier = Modifier.testTag("yt_stop_btn")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = AccentRed)
                            }
                            IconButton(
                                onClick = { scope.launch { engine.executeNext() } },
                                modifier = Modifier.testTag("yt_next_btn")
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = TextWhite)
                            }
                        }

                        HorizontalDivider(color = CardBorder, modifier = Modifier.padding(vertical = 8.dp))

                        // Secondary Controls (Volume & Open)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { scope.launch { engine.executeVolumeAdjust(up = true) } },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite),
                                    border = BorderStroke(1.dp, CardBorder),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Vol +", fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = { scope.launch { engine.executeVolumeAdjust(up = false) } },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite),
                                    border = BorderStroke(1.dp, CardBorder),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Vol -", fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = { scope.launch { engine.executeMute(true) } },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite),
                                    border = BorderStroke(1.dp, CardBorder),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Mute", fontSize = 11.sp)
                                }
                            }

                            Button(
                                onClick = { scope.launch { engine.executeOpen() } },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2D4A)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp), tint = JarvisCyan)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open App", fontSize = 11.sp, color = JarvisCyan)
                            }
                        }
                    }
                }
            }

            // Search Results Section
            if (state.searchResults.isNotEmpty()) {
                item {
                    Text(
                        text = "SEARCH RESULTS (${state.searchResults.size})",
                        color = JarvisCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(state.searchResults) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        border = BorderStroke(1.dp, CardBorder),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    if (selectedPlaybackTarget == YouTubePlaybackTarget.EMBEDDED) {
                                        embeddedVideoId = item.videoId
                                    }
                                    engine.openVideo(item)
                                    snackbarMessage = "Playing ${item.title} on YouTube."
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (item.thumbnailUrl.isNotBlank()) {
                                AsyncImage(
                                    model = item.thumbnailUrl,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(width = 80.dp, height = 50.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    color = TextWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.channelTitle,
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                    if (item.isOfficial) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            Icons.Default.Verified,
                                            contentDescription = "Official",
                                            tint = JarvisCyan,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                            Icon(
                                Icons.Default.PlayCircleOutline,
                                contentDescription = "Play",
                                tint = AccentRed,
                                modifier = Modifier.size(24.dp)
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

    // Snackbar alert
    if (snackbarMessage != null) {
        LaunchedEffect(snackbarMessage) {
            kotlinx.coroutines.delay(3500)
            snackbarMessage = null
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Snackbar(
                containerColor = Color(0xFF1E2D4A),
                contentColor = TextWhite,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(snackbarMessage ?: "", fontSize = 12.sp)
            }
        }
    }

    // Settings Dialog
    if (showConfigDialog) {
        var apiKeyInput by remember { mutableStateOf(configStore.getApiKey()) }
        var targetSelect by remember { mutableStateOf(configStore.playbackTarget) }
        var a11yVerify by remember { mutableStateOf(configStore.autoVerifyAccessibility) }

        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = {
                Text("YouTube Configuration", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Preferred Playback Target",
                        color = JarvisCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        YouTubePlaybackTarget.values().forEach { target ->
                            FilterChip(
                                selected = targetSelect == target,
                                onClick = { targetSelect = target },
                                label = { Text(target.name.take(6), fontSize = 10.sp) }
                            )
                        }
                    }

                    Text(
                        text = "YouTube Data API v3 Key (Optional)",
                        color = JarvisCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "If left empty, JARVIS uses intelligent direct search resolution with zero API quota usage.",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        placeholder = { Text("AIzaSy...", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = CardBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Accessibility Verification", color = TextWhite, fontSize = 12.sp)
                        Switch(
                            checked = a11yVerify,
                            onCheckedChange = { a11yVerify = it }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        configStore.setApiKey(apiKeyInput)
                        configStore.playbackTarget = targetSelect
                        configStore.autoVerifyAccessibility = a11yVerify
                        selectedPlaybackTarget = targetSelect
                        showConfigDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan)
                ) {
                    Text("Save", color = BgDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CardBg,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

/**
 * Embedded in-app YouTube Player using Android WebView and YouTube iFrame API.
 * Safely handles Activity lifecycle, memory recycling, and JavaScript bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedYouTubePlayer(
    videoId: String,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                setBackgroundColor(0xFF000000.toInt())
            }
        },
        update = { webView ->
            val embedHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <style>
                        body { margin: 0; padding: 0; background-color: #000000; overflow: hidden; }
                        iframe { width: 100vw; height: 100vh; border: none; }
                    </style>
                </head>
                <body>
                    <iframe src="https://www.youtube.com/embed/$videoId?autoplay=1&enablejsapi=1&playsinline=1"
                            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                            allowfullscreen>
                    </iframe>
                </body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL("https://www.youtube.com", embedHtml, "text/html", "UTF-8", null)
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    )
}
