package com.kzkt.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kzkt.app.core.BubbleMeta

/**
 * Bubble Edit Card: floating/embedded card for editing a selected bubble's text,
 * font preset, bold/italic, alignment, font scale, and edge stroke color.
 */
@Composable
fun BubbleEditCard(
    bubbleId: String,
    bubble: BubbleMeta,
    editingText: String,
    customFontPath: String,
    onTextChange: (String) -> Unit,
    onBoldChange: (Boolean) -> Unit,
    onItalicChange: (Boolean) -> Unit,
    onAlignChange: (android.graphics.Paint.Align) -> Unit,
    onFontPresetChange: (String) -> Unit,
    onFontScaleChange: (Float) -> Unit,
    onFontScaleChangeFinished: () -> Unit,
    onStrokeColorChange: (String?) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Editing Bubble #$bubbleId", style = MaterialTheme.typography.labelLarge)
                if (bubble.rawText != null) {
                    Text(
                        "Raw: ${bubble.rawText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
                    )
                }
            }
            OutlinedTextField(
                value = editingText,
                onValueChange = onTextChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                singleLine = false,
                maxLines = 3,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconToggleButton(
                        checked = bubble.isBold,
                        onCheckedChange = onBoldChange,
                    ) { Icon(Icons.Default.FormatBold, "Bold") }

                    IconToggleButton(
                        checked = bubble.isItalic,
                        onCheckedChange = onItalicChange,
                    ) { Icon(Icons.Default.FormatItalic, "Italic") }

                    IconButton(
                        onClick = {
                            val nextAlign =
                                when (bubble.align) {
                                    android.graphics.Paint.Align.LEFT -> android.graphics.Paint.Align.CENTER
                                    android.graphics.Paint.Align.CENTER -> android.graphics.Paint.Align.RIGHT
                                    else -> android.graphics.Paint.Align.LEFT
                                }
                            onAlignChange(nextAlign)
                        },
                    ) {
                        Icon(
                            when (bubble.align) {
                                android.graphics.Paint.Align.LEFT -> Icons.AutoMirrored.Filled.FormatAlignLeft
                                android.graphics.Paint.Align.RIGHT -> Icons.AutoMirrored.Filled.FormatAlignRight
                                else -> Icons.Default.FormatAlignCenter
                            },
                            "Align",
                        )
                    }

                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(bubble.fontPreset, style = MaterialTheme.typography.labelSmall)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            val fontChoices =
                                mutableListOf("Default", "Manga (Built-in)", "Serif", "Monospace", "Sans-Serif")
                            if (customFontPath.isNotBlank()) fontChoices.add(1, "Custom")

                            fontChoices.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset) },
                                    onClick = {
                                        onFontPresetChange(preset)
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Size", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = bubble.fontScale,
                        onValueChange = onFontScaleChange,
                        onValueChangeFinished = onFontScaleChangeFinished,
                        valueRange = 0.5f..2.5f,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }

                var strokeExpanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { strokeExpanded = true }) {
                        val strokeName =
                            when (bubble.strokeColor) {
                                "#FF0000" -> "Red"
                                "#00FF00" -> "Green"
                                "#0000FF" -> "Blue"
                                "#FFFF00" -> "Yellow"
                                "#000000" -> "Black"
                                "#FFFFFF" -> "White"
                                else -> "Auto"
                            }
                        Text("Edge: $strokeName", style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(
                        expanded = strokeExpanded,
                        onDismissRequest = { strokeExpanded = false },
                    ) {
                        val strokeChoices =
                            listOf(
                                "Auto" to null,
                                "Black" to "#000000",
                                "White" to "#FFFFFF",
                                "Red" to "#FF0000",
                                "Green" to "#00FF00",
                                "Blue" to "#0000FF",
                                "Yellow" to "#FFFF00",
                            )

                        strokeChoices.forEach { (name, hex) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onStrokeColorChange(hex)
                                    strokeExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
