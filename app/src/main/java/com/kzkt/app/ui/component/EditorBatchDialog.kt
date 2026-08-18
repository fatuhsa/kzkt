package com.kzkt.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kzkt.app.core.BubbleMeta

/**
 * Batch Edit dialog: find & replace text across all bubbles, or apply one common
 * style (bold/italic/align/size) to every bubble. Pure presentational — all
 * mutations flow through the callbacks so the caller keeps full control of
 * history/undo snapshots.
 */
@Composable
fun BatchEditDialog(
    bubbles: Map<String, BubbleMeta>,
    findText: String,
    onFindTextChange: (String) -> Unit,
    replaceText: String,
    onReplaceTextChange: (String) -> Unit,
    batchBold: Boolean,
    onBatchBoldChange: (Boolean) -> Unit,
    batchItalic: Boolean,
    onBatchItalicChange: (Boolean) -> Unit,
    batchAlign: android.graphics.Paint.Align,
    onBatchAlignChange: (android.graphics.Paint.Align) -> Unit,
    batchFontScale: Float,
    onBatchFontScaleChange: (Float) -> Unit,
    onReplaceAll: () -> Unit,
    onApplyStyleAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch Edit") },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Find & Replace", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = findText,
                    onValueChange = onFindTextChange,
                    label = { Text("Find") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replaceText,
                    onValueChange = onReplaceTextChange,
                    label = { Text("Replace with") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onReplaceAll,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Replace in all bubbles") }

                HorizontalDivider()

                Text("Apply style to ALL bubbles", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = batchBold,
                        onClick = { onBatchBoldChange(!batchBold) },
                        label = { Text("Bold") },
                    )
                    FilterChip(
                        selected = batchItalic,
                        onClick = { onBatchItalicChange(!batchItalic) },
                        label = { Text("Italic") },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Align", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                    listOf(
                        android.graphics.Paint.Align.LEFT to "Left",
                        android.graphics.Paint.Align.CENTER to "Center",
                        android.graphics.Paint.Align.RIGHT to "Right",
                    ).forEach { (align, label) ->
                        FilterChip(
                            selected = batchAlign == align,
                            onClick = { onBatchAlignChange(align) },
                            label = { Text(label) },
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Size", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = batchFontScale,
                        onValueChange = onBatchFontScaleChange,
                        valueRange = 0.5f..2.5f,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    Text("%.1fx".format(batchFontScale), style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = onApplyStyleAll,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Apply to all") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
