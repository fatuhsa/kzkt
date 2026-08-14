package com.kzkt.app.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kzkt.app.ui.MainViewModel

/** Pill-shaped status badge (YOLO ready / loading / error). */
@Composable
fun StatusChip(
    icon: ImageVector,
    text: String,
    container: Color,
    content: Color,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = content)
        }
    }
}

/** YOLO model load status shown at the top of the Translate screen. */
@Composable
fun YoloStatusCard(viewModel: MainViewModel) {
    val yoloReady by remember { derivedStateOf { viewModel.yoloReady.value } }
    val yoloError by remember { derivedStateOf { viewModel.yoloError.value } }

    if (yoloReady) {
        StatusChip(
            icon = Icons.Default.CheckCircle,
            text = "YOLO ready",
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        val error = yoloError
        if (error != null) {
            StatusChip(
                icon = Icons.Default.Error,
                text = error,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            StatusChip(
                icon = Icons.Default.HourglassEmpty,
                text = "Loading YOLO...",
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
