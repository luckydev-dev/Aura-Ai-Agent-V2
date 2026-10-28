package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.UserFactEntity
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    val shapes = AuraTheme.shapes
    val repository = remember { MemoryRepository(context) }

    val facts by repository.getAllFacts().collectAsState(initial = emptyList())

    val presetCategories = remember { listOf("Alias", "Preference", "Contact", "Other") }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingFact by remember { mutableStateOf<UserFactEntity?>(null) }

    // Dialog state
    var dialogKey by remember { mutableStateOf("") }
    var dialogValue by remember { mutableStateOf("") }
    var dialogCategory by remember { mutableStateOf("Alias") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        AuraTopBar(
            title = "Aura Memory",
            onBack = onBack,
            actions = {
                AuraButton(
                    text = "Add Fact",
                    onClick = {
                        dialogKey = ""
                        dialogValue = ""
                        dialogCategory = "Alias"
                        showAddDialog = true
                    },
                    isPrimary = true,
                    testTag = "add_fact_btn"
                )
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        androidx.compose.foundation.text.BasicText(
            text = "User Facts & Aliases",
            style = typography.titleMedium.copy(color = colors.textPrimary)
        )
        Spacer(modifier = Modifier.height(4.dp))
        androidx.compose.foundation.text.BasicText(
            text = "Maya injects these memories directly into every conversation so she knows your preferences, relationships, and app aliases.",
            style = typography.bodySmall.copy(color = colors.textSecondary)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (facts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.text.BasicText(
                    text = "No saved memories yet.\nTap 'Add Fact' above to teach Maya your preferences or aliases.",
                    style = typography.bodyMedium.copy(color = colors.textTertiary)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(facts, key = { it.id }) { fact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                            .clickable {
                                editingFact = fact
                                dialogKey = fact.key
                                dialogValue = fact.value
                                dialogCategory = presetCategories.find { it.equals(fact.category, ignoreCase = true) } ?: "Other"
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AuraBadge(text = fact.category.uppercase(), isSuccess = true)
                                Spacer(modifier = Modifier.width(8.dp))
                                androidx.compose.foundation.text.BasicText(
                                    text = fact.key,
                                    style = typography.labelLarge.copy(color = colors.textPrimary)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = fact.value,
                                style = typography.bodyMedium.copy(color = colors.textSecondary)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AuraIconButton(
                                icon = AuraIcons.Edit,
                                onClick = {
                                    editingFact = fact
                                    dialogKey = fact.key
                                    dialogValue = fact.value
                                    dialogCategory = presetCategories.find { it.equals(fact.category, ignoreCase = true) } ?: "Other"
                                },
                                contentDescription = "Edit Fact",
                                size = 32.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            AuraIconButton(
                                icon = AuraIcons.Close,
                                onClick = {
                                    scope.launch { repository.deleteFact(fact.id) }
                                },
                                contentDescription = "Delete Fact",
                                size = 32.dp
                            )
                        }
                    }
                }
            }
        }

        if (facts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            AuraButton(
                text = "Clear All Memories",
                onClick = {
                    scope.launch { repository.clearAllFacts() }
                },
                isPrimary = false,
                isDanger = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                testTag = "clear_memories_btn"
            )
        }
    }

    // Add or Edit Fact Modal Dialog
    if (showAddDialog || editingFact != null) {
        val isEditing = editingFact != null
        val dialogTitle = if (isEditing) "Edit Fact / Alias" else "Add New Fact / Alias"

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable {
                    showAddDialog = false
                    editingFact = null
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clip(shapes.large)
                    .background(colors.surface)
                    .border(1.dp, colors.border, shapes.large)
                    .clickable(enabled = false) {}
                    .padding(20.dp)
            ) {
                androidx.compose.foundation.text.BasicText(
                    text = dialogTitle,
                    style = typography.titleMedium.copy(color = colors.textPrimary)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Category selector chips
                androidx.compose.foundation.text.BasicText(
                    text = "Category",
                    style = typography.labelSmall.copy(color = colors.textTertiary)
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(presetCategories) { cat ->
                        AuraChip(
                            text = cat,
                            selected = dialogCategory.equals(cat, ignoreCase = true),
                            onClick = { dialogCategory = cat }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Key field
                androidx.compose.foundation.text.BasicText(
                    text = "Key / Label",
                    style = typography.labelSmall.copy(color = colors.textTertiary)
                )
                Spacer(modifier = Modifier.height(4.dp))
                AuraTextField(
                    value = dialogKey,
                    onValueChange = { dialogKey = it },
                    placeholder = "e.g. nickname, mom, gym app, work email",
                    singleLine = true,
                    testTag = "fact_key_input"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Value field
                androidx.compose.foundation.text.BasicText(
                    text = "Value / Details",
                    style = typography.labelSmall.copy(color = colors.textTertiary)
                )
                Spacer(modifier = Modifier.height(4.dp))
                AuraTextField(
                    value = dialogValue,
                    onValueChange = { dialogValue = it },
                    placeholder = "e.g. Lucky, Pooja (+91 98...), FitNotes",
                    singleLine = false,
                    testTag = "fact_value_input"
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuraButton(
                        text = "Cancel",
                        onClick = {
                            showAddDialog = false
                            editingFact = null
                        },
                        isPrimary = false
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    AuraButton(
                        text = if (isEditing) "Update" else "Save",
                        onClick = {
                            if (dialogKey.isNotBlank() && dialogValue.isNotBlank()) {
                                scope.launch {
                                    val currentEditing = editingFact
                                    if (currentEditing != null) {
                                        repository.updateFact(
                                            id = currentEditing.id,
                                            key = dialogKey.trim(),
                                            value = dialogValue.trim(),
                                            category = dialogCategory.lowercase().trim()
                                        )
                                    } else {
                                        repository.saveFact(
                                            key = dialogKey.trim(),
                                            value = dialogValue.trim(),
                                            category = dialogCategory.lowercase().trim()
                                        )
                                    }

                                    val lower = dialogKey.lowercase().trim()
                                    if (lower in listOf("owner_name", "preferred_name", "nickname", "name", "call_me", "user_name") || lower.contains("name")) {
                                        com.example.core.settings.AuraPreferences(context).setOwnerName(dialogValue.trim())
                                    }

                                    showAddDialog = false
                                    editingFact = null
                                }
                            }
                        },
                        isPrimary = true,
                        enabled = dialogKey.isNotBlank() && dialogValue.isNotBlank(),
                        testTag = "save_fact_confirm_btn"
                    )
                }
            }
        }
    }
}
