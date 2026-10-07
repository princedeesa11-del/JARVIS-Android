package com.example

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.service.ScreenCaptureService
import com.example.ui.JarvisViewModel
import com.example.ui.components.MayaDrawerContent
import com.example.ui.components.MayaDrawerDestination
import com.example.ui.screens.*
import com.example.ui.theme.JarvisCardBorder
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisDeepBg
import com.example.ui.theme.JarvisTextSecondary
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private val viewModel: JarvisViewModel by viewModels()

  private val voicePermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
    val audioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] == true
    if (audioGranted) {
      com.example.service.WakeWordService.start(this)
    }
  }

  private val screenCaptureLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == RESULT_OK && result.data != null) {
      ScreenCaptureService.onScreenCaptured = { bitmap ->
        viewModel.onScreenCaptured(bitmap)
      }
      ScreenCaptureService.onCaptureFailed = { error ->
        viewModel.speakText("Screen capture failed: $error")
      }
      ScreenCaptureService.start(this, result.resultCode, result.data!!)
    } else {
      viewModel.speakText("Screen capture was cancelled.")
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Ensure audio & notification permissions are requested and background assistant starts
    val permissionsToRequest = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    val notGranted = permissionsToRequest.filter {
      androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    if (notGranted.isNotEmpty()) {
      voicePermissionLauncher.launch(notGranted.toTypedArray())
    } else {
      com.example.service.WakeWordService.start(this)
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.screenCaptureRequest.collect {
          val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
          if (mpManager != null) {
            screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
          } else {
            viewModel.speakText("Screen projection is not supported on this device.")
          }
        }
      }
    }

    setContent {
      MyApplicationTheme {
        JarvisAppRoot(viewModel = viewModel)
      }
    }
  }
}

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
  object HUD : Screen("hud", "JARVIS", Icons.Default.PlayArrow)
  object Vision : Screen("vision", "Vision", Icons.Default.Search)
  object Memory : Screen("memory", "Memory", Icons.Default.Favorite)
  object Studio : Screen("studio", "Studio", Icons.Default.Create)
  object Automate : Screen("automate", "Automate", Icons.Default.CheckCircle)
  object Config : Screen("config", "Config", Icons.Default.Settings)
}

@Composable
fun JarvisAppRoot(viewModel: JarvisViewModel) {
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route ?: Screen.HUD.route

  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val scope = rememberCoroutineScope()

  // Back button handling: if drawer is open, back closes drawer
  BackHandler(enabled = drawerState.isOpen) {
    scope.launch { drawerState.close() }
  }

  val items = listOf(
    Screen.HUD,
    Screen.Vision,
    Screen.Memory,
    Screen.Studio,
    Screen.Automate,
    Screen.Config
  )

  ModalNavigationDrawer(
    drawerState = drawerState,
    gesturesEnabled = true,
    drawerContent = {
      MayaDrawerContent(
        currentRoute = currentRoute,
        onDestinationSelected = { dest ->
          scope.launch { drawerState.close() }
          if (currentRoute != dest.route) {
            navController.navigate(dest.route) {
              popUpTo(navController.graph.startDestinationId) {
                saveState = true
              }
              launchSingleTop = true
              restoreState = true
            }
          }
        }
      )
    }
  ) {
    Scaffold(
      bottomBar = {
        NavigationBar(
          containerColor = Color(0xFF070E1C),
          tonalElevation = 8.dp
        ) {
          items.forEach { screen ->
            val isSelected = currentRoute == screen.route
            NavigationBarItem(
              icon = {
                Icon(
                  imageVector = screen.icon,
                  contentDescription = screen.title,
                  modifier = Modifier.size(20.dp),
                  tint = if (isSelected) JarvisCyan else JarvisTextSecondary
                )
              },
              label = {
                Text(
                  text = screen.title,
                  fontSize = 10.sp,
                  color = if (isSelected) JarvisCyan else JarvisTextSecondary
                )
              },
              selected = isSelected,
              onClick = {
                if (currentRoute != screen.route) {
                  navController.navigate(screen.route) {
                    popUpTo(navController.graph.startDestinationId) {
                      saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                  }
                }
              },
              colors = NavigationBarItemDefaults.colors(
                indicatorColor = Color(0xFF10233F)
              ),
              modifier = Modifier.testTag("nav_${screen.route}")
            )
          }
        }
      },
      containerColor = JarvisDeepBg,
      modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
      NavHost(
        navController = navController,
        startDestination = Screen.HUD.route,
        modifier = Modifier.padding(innerPadding)
      ) {
        // MAIN MENU DESTINATIONS
        composable(Screen.HUD.route) {
          JarvisMainScreen(
            viewModel = viewModel,
            onOpenDrawer = { scope.launch { drawerState.open() } }
          )
        }
        composable("maya_home") {
          MayaHomeScreen(
            viewModel = viewModel,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onNavigateTo = { route ->
              navController.navigate(route) {
                popUpTo(navController.graph.startDestinationId) {
                  saveState = true
                }
                launchSingleTop = true
                restoreState = true
              }
            }
          )
        }
        composable(Screen.Memory.route) {
          MemoryScreen(
            viewModel = viewModel,
            onOpenDrawer = { scope.launch { drawerState.open() } }
          )
        }
        composable("chat") {
          JarvisMainScreen(
            viewModel = viewModel,
            onOpenDrawer = { scope.launch { drawerState.open() } }
          )
        }

        // PRODUCTIVITY DESTINATIONS
        composable("markets") {
          MarketsScreen(
            viewModel = viewModel,
            onOpenDrawer = { scope.launch { drawerState.open() } }
          )
        }
        composable("documents") {
          DocumentsScreen(
            viewModel = viewModel,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onNavigateToVision = {
              navController.navigate(Screen.Vision.route) {
                launchSingleTop = true
              }
            }
          )
        }
        composable(Screen.Studio.route) {
          WebStudioScreen(
            viewModel = viewModel,
            onOpenDrawer = { scope.launch { drawerState.open() } }
          )
        }
        composable("whiteboard") {
          WhiteboardScreen(
            viewModel = viewModel,
            onOpenDrawer = { scope.launch { drawerState.open() } }
          )
        }

        // SYSTEM DESTINATIONS
        composable(Screen.Config.route) {
          SettingsScreen(
            viewModel = viewModel,
            onNavigateTo = { route ->
              navController.navigate(route) {
                launchSingleTop = true
              }
            },
            onBack = {
              if (!navController.popBackStack()) {
                navController.navigate(Screen.HUD.route) {
                  popUpTo(Screen.HUD.route) { inclusive = true }
                }
              }
            },
            onOpenDrawer = { scope.launch { drawerState.open() } }
          )
        }

        // MAYA SETTINGS SUB-ROUTES
        composable("settings_personal") {
          PersonalSettingsScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() }
          )
        }
        composable("personal") {
          PersonalSettingsScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() }
          )
        }
        composable("settings_maya") {
          MayaAssistantSettingsScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            onNavigateToAppearance = { navController.navigate("appearance") }
          )
        }
        composable("settings_voice") {
          VoiceSettingsScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() }
          )
        }
        composable("voice") {
          VoiceSettingsScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() }
          )
        }
        composable("settings_skills") {
          SkillsSettingsScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            onNavigateToAutomation = { navController.navigate(Screen.Automate.route) }
          )
        }
        composable("settings_subagents") {
          SubAgentsSettingsScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            onNavigateToTouchGuard = { navController.navigate("touch_guard") }
          )
        }
        composable("settings_email") {
          EmailSettingsScreen(
            onBack = { navController.popBackStack() }
          )
        }
        composable("settings_whatsapp") {
          WhatsAppGroupsAndReportsScreen(
            onBack = { navController.popBackStack() },
            onOpenAutoReply = { navController.navigate("whatsapp_auto_reply") },
            onOpenCloudApi = { navController.navigate("whatsapp_cloud") }
          )
        }
        composable("settings_whatsapp_cloud") {
          com.example.ui.screens.WhatsAppCloudDashboardScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() }
          )
        }
        composable("whatsapp_cloud") {
          com.example.ui.screens.WhatsAppCloudDashboardScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() }
          )
        }
        composable("youtube_dashboard") {
          com.example.ui.screens.YouTubeDashboardScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() }
          )
        }
        composable("youtube") {
          com.example.ui.screens.YouTubeDashboardScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() }
          )
        }
        composable("settings_social") {
          SocialMediaSettingsScreen(
            onBack = { navController.popBackStack() }
          )
        }
        composable("settings_connectors") {
          ConnectorsSettingsScreen(
            onBack = { navController.popBackStack() }
          )
        }
        composable("settings_backup") {
          BackupSettingsScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() }
          )
        }
        composable("settings_advanced") {
          AdvancedSettingsScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            onNavigateTo = { route -> navController.navigate(route) }
          )
        }
        composable("settings_optional") {
          OptionalSettingsScreen(
            onBack = { navController.popBackStack() }
          )
        }
        composable("appearance") {
          AppearanceScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() }
          )
        }
        composable("touch_guard") {
          TouchGuardScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() }
          )
        }

        // OTHER EXISTING SCREENS
        composable(Screen.Vision.route) {
          VisionScreen(
            viewModel = viewModel,
            onOpenDrawer = { scope.launch { drawerState.open() } }
          )
        }
        composable(Screen.Automate.route) {
          AutomationScreen(
            viewModel = viewModel,
            onOpenDrawer = { scope.launch { drawerState.open() } }
          )
        }
        composable("screen_lock") {
          ScreenLockScreen(
            onBack = { navController.popBackStack() },
            onNavigateToPatternPin = { navController.navigate("pattern_pin") }
          )
        }
        composable("pattern_pin") {
          PatternPinScreen(
            onBack = { navController.popBackStack() }
          )
        }
        composable("whatsapp_auto_reply") {
          com.example.ui.screens.WhatsAppAutoReplyScreen(
            onBack = { navController.popBackStack() }
          )
        }
      }
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  MyApplicationTheme { Greeting("Android") }
}

