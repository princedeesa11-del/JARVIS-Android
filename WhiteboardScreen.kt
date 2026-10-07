package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.JarvisViewModel
import com.example.ui.theme.*

data class WhiteboardStroke(
    val path: List<Offset>,
    val color: Color,
    val strokeWidth: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhiteboardScreen(
    viewModel: JarvisViewModel,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strokes = remember { mutableStateListOf<WhiteboardStroke>() }
    var currentPath by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var selectedColor by remember { mutableStateOf(JarvisCyan) }
    var selectedWidth by remember { mutableStateOf(6f) }

    val colors = listOf(
        JarvisCyan,
        Color.White,
        Color(0xFFFFD700), // Gold
        Color(0xFF00E676), // Green
        Color(0xFFFF5252), // Red
        Color(0xFFB388FF)  // Purple
    )

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.testTag("drawer_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Navigation Menu",
                            tint = JarvisCyan
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Study / Whiteboard",
                            fontWeight = FontWeight.Bold,
                            color = JarvisTextPrimary,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = JarvisCyan.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "Canvas",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = JarvisCyan,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    // Undo button
                    IconButton(
                        onClick = {
                            if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
                        },
                        enabled = strokes.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Undo,
                            contentDescription = "Undo",
                            tint = if (strokes.isNotEmpty()) JarvisCyan else JarvisTextSecondary
                        )
                    }

                    // Clear canvas button
                    IconButton(
                        onClick = {
                            strokes.clear()
                            currentPath = emptyList()
                        },
                        enabled = strokes.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear Canvas",
                            tint = if (strokes.isNotEmpty()) Color(0xFFFF5252) else JarvisTextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = JarvisDeepBg)
            )
        },
        containerColor = JarvisDeepBg,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Palette & Stroke Controls
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, JarvisCardBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Color Pickers
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        colors.forEach { color ->
                            val isSelected = selectedColor == color
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDrag = { _, _ -> },
                                            onDragEnd = { selectedColor = color }
                                        )
                                    }
                                    .clickable { selectedColor = color }
                            )
                        }
                    }

                    // Width Chips
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4f to "S", 8f to "M", 14f to "L").forEach { (width, label) ->
                            val isSelected = selectedWidth == width
                            Surface(
                                onClick = { selectedWidth = width },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) JarvisCyan.copy(alpha = 0.2f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (isSelected) JarvisCyan else JarvisCardBorder)
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) JarvisCyan else JarvisTextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Whiteboard Drawing Surface
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF07101E))
                    .border(1.dp, JarvisCyan.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .testTag("whiteboard_canvas_area")
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(selectedColor, selectedWidth) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentPath = listOf(offset)
                                },
                                onDrag = { change, _ ->
                                    currentPath = currentPath + change.position
                                },
                                onDragEnd = {
                                    if (currentPath.isNotEmpty()) {
                                        strokes.add(WhiteboardStroke(currentPath, selectedColor, selectedWidth))
                                        currentPath = emptyList()
                                    }
                                },
                                onDragCancel = {
                                    currentPath = emptyList()
                                }
                            )
                        }
                ) {
                    // Draw committed strokes
                    strokes.forEach { stroke ->
                        if (stroke.path.size > 1) {
                            val path = Path().apply {
                                moveTo(stroke.path.first().x, stroke.path.first().y)
                                for (i in 1 until stroke.path.size) {
                                    lineTo(stroke.path[i].x, stroke.path[i].y)
                                }
                            }
                            drawPath(
                                path = path,
                                color = stroke.color,
                                style = Stroke(
                                    width = stroke.strokeWidth,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        } else if (stroke.path.size == 1) {
                            drawCircle(
                                color = stroke.color,
                                radius = stroke.strokeWidth / 2,
                                center = stroke.path.first()
                            )
                        }
                    }

                    // Draw current active stroke
                    if (currentPath.size > 1) {
                        val path = Path().apply {
                            moveTo(currentPath.first().x, currentPath.first().y)
                            for (i in 1 until currentPath.size) {
                                lineTo(currentPath[i].x, currentPath[i].y)
                            }
                        }
                        drawPath(
                            path = path,
                            color = selectedColor,
                            style = Stroke(
                                width = selectedWidth,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }

                if (strokes.isEmpty() && currentPath.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Brush,
                            contentDescription = null,
                            tint = JarvisCyan.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Interactive Hunter AI Whiteboard",
                            color = JarvisTextSecondary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Draw, solve formulas, or brainstorm with your finger",
                            color = JarvisTextSecondary.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // AI Study Assistant Prompt Suggestions
            Card(
                colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                border = BorderStroke(1.dp, JarvisCardBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "AI Study Assistant",
                        fontWeight = FontWeight.Bold,
                        color = JarvisCyan,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val prompts = listOf(
                            "Create 5 Flashcards on Physics",
                            "Explain Quantum Computing simply",
                            "Generate a study roadmap",
                            "Test me with 3 quiz questions"
                        )
                        items(prompts) { prompt ->
                            Surface(
                                onClick = { viewModel.sendMessage(prompt) },
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF10233F),
                                border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = prompt,
                                    color = JarvisTextPrimary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
