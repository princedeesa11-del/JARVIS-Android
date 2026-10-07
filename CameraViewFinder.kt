package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.camera.CameraXManager
import com.example.ui.theme.JarvisAccentRed
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisGold

@Composable
fun CameraViewFinder(
    onImageCaptured: (Bitmap) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val cameraXManager = remember { CameraXManager(context) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            cameraXManager.shutdown()
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF080E1C))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Camera Permission",
                    tint = JarvisGold,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "CAMERA PERMISSION REQUIRED",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "JARVIS needs direct optical sensor access to capture live high-definition frames for neural vision and OCR.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan),
                    modifier = Modifier.testTag("request_camera_permission_button")
                ) {
                    Text("Grant Camera Access", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(onClick = onClose) {
                    Text("Cancel", color = JarvisCyan)
                }
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // CameraX Live Surface View
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }.also { pv ->
                    previewViewRef = pv
                    cameraXManager.initialize {
                        cameraXManager.bindCamera(lifecycleOwner, pv) { err ->
                            captureError = err
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize().testTag("camerax_preview_view")
        )

        // JARVIS Optical HUD Overlay Reticle
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .border(1.dp, JarvisCyan.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
        )

        // Top Controls: Switch Camera, Torch, Close
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .background(Color(0x80000000), CircleShape)
                    .testTag("camerax_close_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close Camera", tint = Color.White)
            }

            Text(
                text = "OPTICAL SENSOR // CAMERAX",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = JarvisCyan
            )

            Row {
                IconButton(
                    onClick = {
                        cameraXManager.toggleTorch(
                            onSuccess = { isTorchOn = it },
                            onError = { captureError = it }
                        )
                    },
                    modifier = Modifier.background(Color(0x80000000), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isTorchOn) Icons.Default.Check else Icons.Default.Info,
                        contentDescription = "Torch Toggle",
                        tint = if (isTorchOn) JarvisGold else Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        previewViewRef?.let { pv ->
                            cameraXManager.switchCamera(lifecycleOwner, pv) { err ->
                                captureError = err
                            }
                        }
                    },
                    modifier = Modifier
                        .background(Color(0x80000000), CircleShape)
                        .testTag("camerax_switch_lens_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Switch Camera Lens", tint = Color.White)
                }
            }
        }

        // Error message banner
        AnimatedVisibility(
            visible = captureError != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 90.dp, start = 20.dp, end = 20.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xCC330C12)),
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisAccentRed)
            ) {
                Text(
                    text = captureError ?: "",
                    color = JarvisAccentRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // Bottom Capture Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 36.dp)
                .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    if (!isCapturing) {
                        isCapturing = true
                        cameraXManager.captureStillImage(
                            onSuccess = { bitmap ->
                                isCapturing = false
                                onImageCaptured(bitmap)
                            },
                            onError = { err ->
                                isCapturing = false
                                captureError = err
                            }
                        )
                    }
                },
                enabled = !isCapturing,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = JarvisCyan,
                    contentColor = Color.Black
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .size(76.dp)
                    .border(3.dp, Color.White, CircleShape)
                    .testTag("camerax_shutter_button")
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = Color.Black,
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Capture Frame",
                        modifier = Modifier.size(36.dp),
                        tint = Color.Black
                    )
                }
            }
        }
    }
}
