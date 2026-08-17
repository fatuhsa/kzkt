package com.kzkt.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kzkt.app.ui.MainViewModel

private data class StepInfo(val label: String, val icon: ImageVector)

/** Stepped translation progress with Scan -> Translate -> Render phases and overall progress bar. */
@Composable
fun TranslationProgressBar(viewModel: MainViewModel) {
    val progress by remember { derivedStateOf { viewModel.translationProgress.value } }
    val done by remember { derivedStateOf { viewModel.translationDone.value } }
    val total by remember { derivedStateOf { viewModel.translationTotal.value } }

    val currentPhase by remember {
        derivedStateOf {
            val lastLog = viewModel.translationLog.lastOrNull() ?: ""
            when {
                lastLog.contains("Inpaint", ignoreCase = true) ||
                lastLog.contains("Render", ignoreCase = true) ||
                lastLog.contains("Saving", ignoreCase = true) ||
                lastLog.contains("Saved", ignoreCase = true) ||
                lastLog.contains("Reassembl", ignoreCase = true) ||
                progress >= 0.90f -> 2 // Render

                lastLog.contains("Translat", ignoreCase = true) ||
                lastLog.contains("Chunk", ignoreCase = true) ||
                lastLog.contains("Provider", ignoreCase = true) ||
                lastLog.contains("LLM", ignoreCase = true) ||
                lastLog.contains("Failover", ignoreCase = true) ||
                lastLog.contains("Waiting", ignoreCase = true) ||
                progress >= 0.25f -> 1 // Translate

                else -> 0 // Scan
            }
        }
    }

    val steps = listOf(
        StepInfo("Scan", Icons.Default.Search),
        StepInfo("Translate", Icons.Default.Translate),
        StepInfo("Render", Icons.Default.Edit),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Stepper Icons + Connecting Lines
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEachIndexed { index, step ->
                val isCompleted = currentPhase > index
                val isActive = currentPhase == index

                // Step Circle
                val circleColor = when {
                    isActive -> MaterialTheme.colorScheme.primaryContainer
                    isCompleted -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                }
                val iconColor = when {
                    isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                    isCompleted -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(circleColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = step.icon,
                        contentDescription = step.label,
                        tint = iconColor,
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Connecting Line between steps
                if (index < steps.size - 1) {
                    val lineColor = if (currentPhase > index) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp)
                            .padding(horizontal = 4.dp)
                            .background(lineColor)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Step Labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            steps.forEachIndexed { index, step ->
                val isActive = currentPhase == index
                val isCompleted = currentPhase > index
                val textColor = when {
                    isActive -> MaterialTheme.colorScheme.onSurface
                    isCompleted -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                }
                Text(
                    text = step.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = textColor,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Linear Progress Bar
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )

        Spacer(Modifier.height(6.dp))

        // Counter: e.g. 1 / 3
        val currentDisplayDone = if (total > 0) minOf(done + 1, total) else done
        Text(
            text = "$currentDisplayDone / $total",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
