package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.AutomationRule
import com.example.data.local.entity.ToolExecutionRecord
import com.example.ui.JarvisViewModel
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(
    viewModel: JarvisViewModel,
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val automationRules by viewModel.automationRules.collectAsState()
    val toolExecutions by viewModel.toolExecutions.collectAsState()
    var showAddRuleDialog by remember { mutableStateOf(false) }

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
                    Text(
                        text = "AUTOMATION & LOGS",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = JarvisCyan,
                        fontSize = 18.sp
                    )
                },
                actions = {
                    IconButton(
                        onClick = { showAddRuleDialog = true },
                        modifier = Modifier.testTag("add_rule_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Automation Rule",
                            tint = JarvisCyan
                        )
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Automation Triggers
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "ACTIVE PROTOCOLS & TRIGGERS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan,
                    letterSpacing = 1.sp
                )
            }

            if (automationRules.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No automation rules defined yet.",
                                color = JarvisTextSecondary,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showAddRuleDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan)
                            ) {
                                Text("Add Battery/App Trigger", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                items(automationRules, key = { it.id }) { rule ->
                    AutomationRuleCard(
                        rule = rule,
                        onToggle = { isChecked -> viewModel.toggleAutomationRule(rule.id, isChecked) },
                        onDelete = { viewModel.deleteAutomationRule(rule.id) }
                    )
                }
            }

            // Header: Execution Log History
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "RECENT DIRECTIVE EXECUTION LOGS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = JarvisCyan,
                    letterSpacing = 1.sp
                )
            }

            if (toolExecutions.isEmpty()) {
                item {
                    Text(
                        text = "No tool executions logged yet.",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(toolExecutions, key = { it.id }) { record ->
                    ToolExecutionCard(record = record)
                }
            }
        }
    }

    // Add Rule Dialog
    if (showAddRuleDialog) {
        var ruleTitle by remember { mutableStateOf("") }
        var triggerType by remember { mutableStateOf("BATTERY_LOW") }
        var triggerValue by remember { mutableStateOf("20") }
        var actionType by remember { mutableStateOf("SPEAK_MESSAGE") }
        var actionPayload by remember { mutableStateOf("Warning: Battery level critical. Please connect charger.") }

        AlertDialog(
            onDismissRequest = { showAddRuleDialog = false },
            title = { Text("Configure Automation Rule", color = JarvisCyan) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = ruleTitle,
                        onValueChange = { ruleTitle = it },
                        label = { Text("Rule Title", color = JarvisCyan) },
                        placeholder = { Text("e.g. Low Battery Alert", color = JarvisTextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisCardBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Trigger:", fontSize = 12.sp, color = JarvisTextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("BATTERY_LOW" to "Battery Low", "BATTERY_CHARGING" to "Charging").forEach { (type, label) ->
                            val isSel = triggerType == type
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) JarvisCyan else Color(0xFF14243C)
                            ) {
                                TextButton(onClick = {
                                    triggerType = type
                                    if (type == "BATTERY_CHARGING") {
                                        actionPayload = "Fast charging active. Power systems recharging."
                                    }
                                }) {
                                    Text(label, fontSize = 11.sp, color = if (isSel) Color.Black else JarvisTextPrimary)
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = actionPayload,
                        onValueChange = { actionPayload = it },
                        label = { Text("Action Output Message / App", color = JarvisCyan) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisCardBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (ruleTitle.isNotBlank()) {
                            viewModel.createAutomationRule(
                                title = ruleTitle,
                                triggerType = triggerType,
                                triggerValue = triggerValue,
                                actionType = actionType,
                                actionPayload = actionPayload
                            )
                            showAddRuleDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan)
                ) {
                    Text("Deploy Protocol", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRuleDialog = false }) {
                    Text("Cancel", color = JarvisTextSecondary)
                }
            },
            containerColor = JarvisCardBg
        )
    }
}

@Composable
private fun AutomationRuleCard(
    rule: AutomationRule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = JarvisCardBg),
        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCardBorder),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.title,
                    fontWeight = FontWeight.Bold,
                    color = JarvisTextPrimary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Trigger: ${rule.triggerType} • Action: ${rule.actionType}",
                    color = JarvisCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = rule.actionPayload,
                    color = JarvisTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = JarvisCyan,
                        checkedTrackColor = Color(0xFF004D56)
                    )
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = JarvisTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolExecutionCard(record: ToolExecutionRecord) {
    val dateStr = remember(record.timestamp) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(record.timestamp))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF091222)),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (record.status == "SUCCESS") JarvisAccentGreen.copy(alpha = 0.3f) else JarvisAccentRed.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚡ ${record.toolName}",
                    fontWeight = FontWeight.Bold,
                    color = if (record.status == "SUCCESS") JarvisAccentGreen else JarvisAccentRed,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "$dateStr (${record.durationMillis}ms)",
                    color = JarvisTextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = record.resultJson,
                color = JarvisTextPrimary,
                fontSize = 11.sp,
                maxLines = 2
            )
        }
    }
}
