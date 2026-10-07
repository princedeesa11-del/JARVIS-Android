package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.JarvisCardBorder
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisDeepBg
import com.example.ui.theme.JarvisTextPrimary
import com.example.ui.theme.JarvisTextSecondary

/**
 * Section groupings matching the Maya drawer specification:
 * - MAIN MENU
 * - PRODUCTIVITY
 * - SYSTEM
 */
enum class DrawerSection(val title: String) {
    MAIN("MAIN MENU"),
    PRODUCTIVITY("PRODUCTIVITY"),
    SYSTEM("SYSTEM")
}

/**
 * All 9 Maya Drawer menu destinations.
 */
enum class MayaDrawerDestination(
    val id: String,
    val title: String,
    val route: String,
    val icon: ImageVector,
    val section: DrawerSection
) {
    // MAIN MENU
    HOME("home", "Home", "hud", Icons.Default.Home, DrawerSection.MAIN),
    MAYA_HOME("maya_home", "Executive Hub", "maya_home", Icons.Default.Dashboard, DrawerSection.MAIN),
    MEMORIES("memories", "Memories", "memory", Icons.Default.Favorite, DrawerSection.MAIN),
    CHAT("chat", "Chat", "chat", Icons.AutoMirrored.Filled.Chat, DrawerSection.MAIN),

    // PRODUCTIVITY
    MARKETS("markets", "Markets", "markets", Icons.Default.TrendingUp, DrawerSection.PRODUCTIVITY),
    DOCUMENTS("documents", "Documents", "documents", Icons.Default.Description, DrawerSection.PRODUCTIVITY),
    WEBSITE_CODING("web_coding", "Website / Coding", "studio", Icons.Default.Code, DrawerSection.PRODUCTIVITY),
    STUDY_WHITEBOARD("whiteboard", "Study / Whiteboard", "whiteboard", Icons.Default.Brush, DrawerSection.PRODUCTIVITY),
    WHATSAPP_CLOUD("whatsapp_cloud", "WhatsApp Cloud", "whatsapp_cloud", Icons.Default.CloudSync, DrawerSection.PRODUCTIVITY),

    // SYSTEM
    SETTINGS("settings", "Settings", "config", Icons.Default.Settings, DrawerSection.SYSTEM);

    companion object {
        fun fromRoute(route: String?): MayaDrawerDestination {
            return when (route) {
                "hud" -> HOME
                "maya_home", "jarvis_home" -> MAYA_HOME
                "memory" -> MEMORIES
                "chat" -> CHAT
                "markets" -> MARKETS
                "documents" -> DOCUMENTS
                "studio" -> WEBSITE_CODING
                "whiteboard" -> STUDY_WHITEBOARD
                "whatsapp_cloud", "settings_whatsapp_cloud" -> WHATSAPP_CLOUD
                "config" -> SETTINGS
                else -> HOME
            }
        }
    }
}

/**
 * Maya Left Navigation Drawer Content Composable.
 * Features:
 * - Header: JARVIS logo, "JARVIS", "Autonomous AI System"
 * - Dark navy background
 * - Rounded menu buttons
 * - Blue highlight for selected item
 * - Scrolling support
 */
@Composable
fun MayaDrawerContent(
    currentRoute: String,
    onDestinationSelected: (MayaDrawerDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val selectedDestination = MayaDrawerDestination.fromRoute(currentRoute)

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF070F1E),
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        modifier = modifier
            .width(300.dp)
            .fillMaxHeight()
            .testTag("maya_navigation_drawer")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            // ==========================================
            // HEADER: JARVIS Core Brand
            // ==========================================
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 20.dp, start = 4.dp, end = 4.dp)
                    .testTag("drawer_header")
            ) {
                // JARVIS Reactor Core Icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF0A2540), Color(0xFF001933))
                            )
                        )
                        .border(1.dp, JarvisCyan.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AllInclusive,
                        contentDescription = "JARVIS Logo",
                        tint = JarvisCyan,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = "JARVIS",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp,
                        color = Color.White,
                        letterSpacing = 0.8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Autonomous AI System",
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = JarvisCyan.copy(alpha = 0.9f)
                    )
                }
            }

            HorizontalDivider(
                color = JarvisCardBorder.copy(alpha = 0.6f),
                thickness = 1.dp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Group items by Section
            val sections = listOf(
                DrawerSection.MAIN to listOf(
                    MayaDrawerDestination.HOME,
                    MayaDrawerDestination.MAYA_HOME,
                    MayaDrawerDestination.MEMORIES,
                    MayaDrawerDestination.CHAT
                ),
                DrawerSection.PRODUCTIVITY to listOf(
                    MayaDrawerDestination.MARKETS,
                    MayaDrawerDestination.DOCUMENTS,
                    MayaDrawerDestination.WEBSITE_CODING,
                    MayaDrawerDestination.STUDY_WHITEBOARD,
                    MayaDrawerDestination.WHATSAPP_CLOUD
                ),
                DrawerSection.SYSTEM to listOf(
                    MayaDrawerDestination.SETTINGS
                )
            )

            sections.forEach { (section, items) ->
                // Section Title Label
                Text(
                    text = section.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = JarvisTextSecondary.copy(alpha = 0.7f),
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp)
                )

                // Navigation Items in this section
                items.forEach { dest ->
                    val isSelected = selectedDestination == dest

                    val backgroundColor = if (isSelected) {
                        Color(0xFF13325B) // Vibrant Blue / Navy highlight
                    } else {
                        Color.Transparent
                    }

                    val borderColor = if (isSelected) {
                        BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.5f))
                    } else null

                    val contentColor = if (isSelected) {
                        Color.White
                    } else {
                        JarvisTextPrimary
                    }

                    val iconTint = if (isSelected) {
                        JarvisCyan
                    } else {
                        JarvisTextSecondary
                    }

                    Surface(
                        onClick = { onDestinationSelected(dest) },
                        shape = RoundedCornerShape(12.dp),
                        color = backgroundColor,
                        border = borderColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .testTag("drawer_item_${dest.id}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Icon(
                                imageVector = dest.icon,
                                contentDescription = dest.title,
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )

                            Spacer(modifier = Modifier.width(14.dp))

                            Text(
                                text = dest.title,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 14.sp,
                                color = contentColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))
            Spacer(modifier = Modifier.height(24.dp))

            // ==========================================
            // FOOTER: Platform Status Badge
            // ==========================================
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0C172C),
                border = BorderStroke(1.dp, JarvisCardBorder.copy(alpha = 0.7f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Hunter AI • Connected",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            color = JarvisTextPrimary
                        )
                        Text(
                            text = "All neural modules ready",
                            fontSize = 10.sp,
                            color = JarvisTextSecondary
                        )
                    }
                }
            }
        }
    }
}

typealias JarvisDrawerDestination = MayaDrawerDestination

@Composable
fun JarvisDrawerContent(
    currentRoute: String,
    onDestinationSelected: (MayaDrawerDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    MayaDrawerContent(currentRoute, onDestinationSelected, modifier)
}
