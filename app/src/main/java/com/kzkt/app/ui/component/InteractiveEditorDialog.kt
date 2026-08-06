package com.kzkt.app.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kzkt.app.core.TextRenderer
import com.kzkt.app.core.BubbleMeta
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background

enum class DragAction { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR, DRAW }

/**
 * Interactive Touch-up Editor: tap any bubble on the preview image to tweak its translated text.
 */
@Composable
fun InteractiveEditorDialog(
    originalBitmap: Bitmap,
    translations: Map<String, String>,
    coordinateMap: Map<String, IntArray>,
    textRenderer: TextRenderer,
    targetLanguage: String,
    customFontPath: String = "",
    rawTexts: Map<String, String>? = null,
    styles: Map<String, BubbleMeta>? = null,
    onDismiss: () -> Unit,
    onSave: (Bitmap, Map<String, String>, Map<String, IntArray>, Map<String, BubbleMeta>) -> Unit,
) {
    val bubbles = remember {
        val map = androidx.compose.runtime.mutableStateMapOf<String, BubbleMeta>()
        coordinateMap.forEach { (id, box) ->
            val style = styles?.get(id)
            map[id] = style?.copy(text = translations[id] ?: "", box = box, rawText = rawTexts?.get(id))
                ?: BubbleMeta(id, translations[id] ?: "", box, rawText = rawTexts?.get(id))
        }
        map
    }
    var selectedBubbleId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var showOriginal by remember { mutableStateOf(false) }
    var showRawText by remember { mutableStateOf(false) }
    var isDrawingMode by remember { mutableStateOf(false) }
    var drawingStart by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var drawingEnd by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    var currentDragAction by remember { mutableStateOf(DragAction.NONE) }
    var baseBitmap by remember { mutableStateOf(originalBitmap) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val mat = com.kzkt.app.core.ImageProcessor.bitmapToMat(originalBitmap)
                for (bubble in bubbles.values) {
                    com.kzkt.app.core.ImageProcessor.inpaintBubbleText(mat, bubble.box)
                }
                val newBmp = com.kzkt.app.core.ImageProcessor.matToBitmap(mat)
                mat.release()
                baseBitmap = newBmp
            } catch (e: Exception) {
                // Ignore inpainting error, fallback to original
            }
        }
    }

    // Re-render bitmap on translation edit
    val editedBitmap by remember(bubbles.toMap(), baseBitmap) {
        derivedStateOf {
            val resultBmp = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(resultBmp)
            for ((id, bubble) in bubbles) {
                if (bubble.text.isBlank() || bubble.text.uppercase() == "SKIP") continue
                textRenderer.renderTextInBubble(
                    canvas = canvas,
                    bubbleRect = bubble.box,
                    text = if (showRawText && bubble.rawText != null) bubble.rawText!! else bubble.text,
                    backgroundPatch = false, // Background is already inpainted
                    targetLanguage = targetLanguage,
                    customFontPath = customFontPath,
                    isBold = bubble.isBold,
                    isItalic = bubble.isItalic,
                    textAlign = bubble.align,
                    fontPreset = bubble.fontPreset,
                    fontScale = bubble.fontScale,
                    strokeColorHex = bubble.strokeColor
                )
            }
            resultBmp
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                    text = "Interactive Touch-up Editor",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Tap any bubble on the page to edit its translated text live.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Interactive Image View
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { containerSize = it.size }
                        .pointerInput(containerSize, originalBitmap) {
                            detectTapGestures { offset: androidx.compose.ui.geometry.Offset ->
                                if (containerSize.width <= 0 || containerSize.height <= 0) return@detectTapGestures
                                val imgW = originalBitmap.width.toFloat()
                                val imgH = originalBitmap.height.toFloat()
                                val imgAspect = imgW / imgH
                                val containerAspect = containerSize.width.toFloat() / containerSize.height

                                val displayW: Float
                                val displayH: Float
                                val padX: Float
                                val padY: Float

                                if (containerAspect > imgAspect) {
                                    displayH = containerSize.height.toFloat()
                                    displayW = displayH * imgAspect
                                    padX = (containerSize.width - displayW) / 2f
                                    padY = 0f
                                } else {
                                    displayW = containerSize.width.toFloat()
                                    displayH = displayW / imgAspect
                                    padX = 0f
                                    padY = (containerSize.height - displayH) / 2f
                                }

                                val relX = offset.x - padX
                                val relY = offset.y - padY

                                if (relX in 0f..displayW && relY in 0f..displayH) {
                                    val imgX = ((relX / displayW) * imgW).toInt()
                                    val imgY = ((relY / displayH) * imgH).toInt()

                                    // Find tapped bubble
                                    for ((id, bubble) in bubbles) {
                                        val (x1, y1, x2, y2) = bubble.box
                                        if (imgX in x1..x2 && imgY in y1..y2) {
                                            selectedBubbleId = id
                                            editingText = bubble.text
                                            return@detectTapGestures
                                        }
                                    }
                                    // if tap on empty space, deselect
                                    selectedBubbleId = null
                                }
                            }
                        }
                        .pointerInput(containerSize, originalBitmap, isDrawingMode) {
                            detectDragGestures(
                                onDragStart = { offset: androidx.compose.ui.geometry.Offset ->
                                    if (containerSize.width <= 0 || containerSize.height <= 0) return@detectDragGestures
                                    val imgW = originalBitmap.width.toFloat()
                                    val imgH = originalBitmap.height.toFloat()
                                    val imgAspect = imgW / imgH
                                    val containerAspect = containerSize.width.toFloat() / containerSize.height

                                    val displayW: Float
                                    val displayH: Float
                                    val padX: Float
                                    val padY: Float

                                    if (containerAspect > imgAspect) {
                                        displayH = containerSize.height.toFloat()
                                        displayW = displayH * imgAspect
                                        padX = (containerSize.width - displayW) / 2f
                                        padY = 0f
                                    } else {
                                        displayW = containerSize.width.toFloat()
                                        displayH = displayW / imgAspect
                                        padX = 0f
                                        padY = (containerSize.height - displayH) / 2f
                                    }

                                    val relX = offset.x - padX
                                    val relY = offset.y - padY
                                    val imgX = ((relX / displayW) * imgW).toInt()
                                    val imgY = ((relY / displayH) * imgH).toInt()

                                    if (isDrawingMode) {
                                        if (relX in 0f..displayW && relY in 0f..displayH) {
                                            currentDragAction = DragAction.DRAW
                                            drawingStart = Pair(imgX, imgY)
                                            drawingEnd = Pair(imgX, imgY)
                                        }
                                        return@detectDragGestures
                                    }

                                    val selId = selectedBubbleId
                                    if (selId != null) {
                                        val bubble = bubbles[selId]
                                        if (bubble != null) {
                                            val (x1, y1, x2, y2) = bubble.box
                                            val threshold = (20f * (imgW / displayW)).toInt()
                                            
                                            currentDragAction = when {
                                                kotlin.math.abs(imgX - x1) < threshold && kotlin.math.abs(imgY - y1) < threshold -> DragAction.RESIZE_TL
                                                kotlin.math.abs(imgX - x2) < threshold && kotlin.math.abs(imgY - y1) < threshold -> DragAction.RESIZE_TR
                                                kotlin.math.abs(imgX - x1) < threshold && kotlin.math.abs(imgY - y2) < threshold -> DragAction.RESIZE_BL
                                                kotlin.math.abs(imgX - x2) < threshold && kotlin.math.abs(imgY - y2) < threshold -> DragAction.RESIZE_BR
                                                imgX in x1..x2 && imgY in y1..y2 -> DragAction.MOVE
                                                else -> DragAction.NONE
                                            }
                                        }
                                    }
                                },
                                onDrag = { change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: androidx.compose.ui.geometry.Offset ->
                                    if (currentDragAction == DragAction.NONE) return@detectDragGestures
                                    change.consume()
                                    
                                    val imgW = originalBitmap.width.toFloat()
                                    val imgH = originalBitmap.height.toFloat()
                                    val containerAspect = containerSize.width.toFloat() / containerSize.height
                                    val displayW = if (containerAspect > imgW / imgH) containerSize.height * (imgW / imgH) else containerSize.width.toFloat()
                                    val displayH = if (containerAspect > imgW / imgH) containerSize.height.toFloat() else containerSize.width / (imgW / imgH)
                                    
                                    val dx = (dragAmount.x / displayW * imgW).toInt()
                                    val dy = (dragAmount.y / displayH * imgH).toInt()

                                    if (currentDragAction == DragAction.DRAW) {
                                        val end = drawingEnd
                                        if (end != null) {
                                            drawingEnd = Pair(end.first + dx, end.second + dy)
                                        }
                                        return@detectDragGestures
                                    }

                                    val selId = selectedBubbleId ?: return@detectDragGestures
                                    val bubble = bubbles[selId] ?: return@detectDragGestures
                                    val (x1, y1, x2, y2) = bubble.box
                                    
                                    val newBox = when (currentDragAction) {
                                        DragAction.MOVE -> intArrayOf(x1 + dx, y1 + dy, x2 + dx, y2 + dy)
                                        DragAction.RESIZE_TL -> intArrayOf(x1 + dx, y1 + dy, x2, y2)
                                        DragAction.RESIZE_TR -> intArrayOf(x1, y1 + dy, x2 + dx, y2)
                                        DragAction.RESIZE_BL -> intArrayOf(x1 + dx, y1, x2, y2 + dy)
                                        DragAction.RESIZE_BR -> intArrayOf(x1, y1, x2 + dx, y2 + dy)
                                        else -> bubble.box
                                    }
                                    
                                    // Ensure x1 < x2 and y1 < y2
                                    if (currentDragAction != DragAction.MOVE) {
                                        if (newBox[0] >= newBox[2]) newBox[0] = newBox[2] - 1
                                        if (newBox[1] >= newBox[3]) newBox[1] = newBox[3] - 1
                                    }
                                    
                                    bubbles[selId] = bubble.copy(box = newBox)
                                },
                                onDragEnd = {
                                    if (currentDragAction == DragAction.DRAW) {
                                        val start = drawingStart
                                        val end = drawingEnd
                                        if (start != null && end != null) {
                                            val x1 = kotlin.math.min(start.first, end.first)
                                            val y1 = kotlin.math.min(start.second, end.second)
                                            val x2 = kotlin.math.max(start.first, end.first)
                                            val y2 = kotlin.math.max(start.second, end.second)
                                            if (x2 - x1 > 10 && y2 - y1 > 10) {
                                                val newId = "manual_${System.currentTimeMillis()}"
                                                bubbles[newId] = BubbleMeta(newId, "New Text", intArrayOf(x1, y1, x2, y2))
                                                selectedBubbleId = newId
                                                editingText = "New Text"
                                                isDrawingMode = false
                                            }
                                        }
                                        drawingStart = null
                                        drawingEnd = null
                                    }
                                    currentDragAction = DragAction.NONE
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = if (showOriginal) originalBitmap.asImageBitmap() else editedBitmap.asImageBitmap(),
                        contentDescription = "Preview Page",
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // Draw bounding boxes overlay
                    if (!showOriginal) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        if (size.width <= 0 || size.height <= 0) return@Canvas
                        
                        val imgW = originalBitmap.width.toFloat()
                        val imgH = originalBitmap.height.toFloat()
                        val imgAspect = imgW / imgH
                        val containerAspect = size.width / size.height

                        val displayW: Float
                        val displayH: Float
                        val padX: Float
                        val padY: Float

                        if (containerAspect > imgAspect) {
                            displayH = size.height
                            displayW = displayH * imgAspect
                            padX = (size.width - displayW) / 2f
                            padY = 0f
                        } else {
                            displayW = size.width
                            displayH = displayW / imgAspect
                            padX = 0f
                            padY = (size.height - displayH) / 2f
                        }
                        
                        val scaleX = displayW / imgW
                        val scaleY = displayH / imgH

                        // Predefined vibrant colors to easily distinguish boxes
                        val boxColors = listOf(
                            androidx.compose.ui.graphics.Color(0xFFFF5252), // Red
                            androidx.compose.ui.graphics.Color(0xFFFF4081), // Pink
                            androidx.compose.ui.graphics.Color(0xFFE040FB), // Purple
                            androidx.compose.ui.graphics.Color(0xFF7C4DFF), // Deep Purple
                            androidx.compose.ui.graphics.Color(0xFF536DFE), // Indigo
                            androidx.compose.ui.graphics.Color(0xFF448AFF), // Blue
                            androidx.compose.ui.graphics.Color(0xFF40C4FF), // Light Blue
                            androidx.compose.ui.graphics.Color(0xFF18FFFF), // Cyan
                            androidx.compose.ui.graphics.Color(0xFF64FFDA), // Teal
                            androidx.compose.ui.graphics.Color(0xFF69F0AE), // Green
                            androidx.compose.ui.graphics.Color(0xFFFFAB40), // Orange
                            androidx.compose.ui.graphics.Color(0xFFFF6E40)  // Deep Orange
                        )

                        for ((id, bubble) in bubbles) {
                            val (x1, y1, x2, y2) = bubble.box
                            val isSelected = (id == selectedBubbleId)
                            
                            val left = padX + x1 * scaleX
                            val top = padY + y1 * scaleY
                            val right = padX + x2 * scaleX
                            val bottom = padY + y2 * scaleY
                            
                            // Use hash of the ID to consistently pick a color
                            val colorIndex = kotlin.math.abs(id.hashCode()) % boxColors.size
                            val boxColor = boxColors[colorIndex]

                            // If selected, draw a semi-transparent fill background to make it obvious
                            if (isSelected) {
                                drawRect(
                                    color = boxColor.copy(alpha = 0.3f),
                                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                    size = androidx.compose.ui.geometry.Size(right - left, bottom - top)
                                )
                            }

                            drawRect(
                                color = boxColor,
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = if (isSelected) 5.dp.toPx() else 2.5.dp.toPx()
                                ),
                                alpha = 0.9f
                            )

                            if (isSelected) {
                                val r = 8.dp.toPx()
                                val strokeW = 2.dp.toPx()
                                // Corner handles
                                val corners = listOf(
                                    androidx.compose.ui.geometry.Offset(left, top),
                                    androidx.compose.ui.geometry.Offset(right, top),
                                    androidx.compose.ui.geometry.Offset(left, bottom),
                                    androidx.compose.ui.geometry.Offset(right, bottom)
                                )
                                for (corner in corners) {
                                    drawCircle(androidx.compose.ui.graphics.Color.White, radius = r, center = corner)
                                    drawCircle(boxColor, radius = r, center = corner, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW))
                                }
                            }
                        }

                        // Draw drawing rect
                        val dStart = drawingStart
                        val dEnd = drawingEnd
                        if (dStart != null && dEnd != null) {
                            val x1 = padX + dStart.first * scaleX
                            val y1 = padY + dStart.second * scaleY
                            val x2 = padX + dEnd.first * scaleX
                            val y2 = padY + dEnd.second * scaleY
                            
                            val left = kotlin.math.min(x1, x2)
                            val top = kotlin.math.min(y1, y2)
                            val right = kotlin.math.max(x1, x2)
                            val bottom = kotlin.math.max(y1, y2)
                            
                            drawRect(
                                color = androidx.compose.ui.graphics.Color.Green,
                                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                                size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                            )
                        }
                        }
                    }
                    
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Peek Original button
                        androidx.compose.material3.IconButton(
                            onClick = { showOriginal = !showOriginal },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), androidx.compose.foundation.shape.CircleShape)
                        ) {
                            Icon(
                                if (showOriginal) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Original"
                            )
                        }
                        // Toggle Raw Text button
                        if (rawTexts != null && rawTexts.isNotEmpty()) {
                            androidx.compose.material3.IconButton(
                                onClick = { showRawText = !showRawText },
                                modifier = Modifier.background(if (showRawText) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), androidx.compose.foundation.shape.CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.Translate,
                                    contentDescription = "Toggle Raw Text",
                                    tint = if (showRawText) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Edit section if bubble selected
                selectedBubbleId?.let { id ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Editing Bubble #$id", style = MaterialTheme.typography.labelLarge)
                                if (bubbles[id]?.rawText != null) {
                                    Text(
                                        "Raw: ${bubbles[id]?.rawText}", 
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp)
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = editingText,
                                onValueChange = {
                                    editingText = it
                                    val bubble = bubbles[id]
                                    if (bubble != null) {
                                        bubbles[id] = bubble.copy(text = it)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                singleLine = false,
                                maxLines = 3
                            )
                            
                            val bubble = bubbles[id]
                            if (bubble != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconToggleButton(
                                            checked = bubble.isBold,
                                            onCheckedChange = { bubbles[id] = bubble.copy(isBold = it) }
                                        ) { Icon(Icons.Default.FormatBold, "Bold") }
                                        
                                        IconToggleButton(
                                            checked = bubble.isItalic,
                                            onCheckedChange = { bubbles[id] = bubble.copy(isItalic = it) }
                                        ) { Icon(Icons.Default.FormatItalic, "Italic") }

                                        IconButton(
                                            onClick = { 
                                                val nextAlign = when(bubble.align) {
                                                    android.graphics.Paint.Align.LEFT -> android.graphics.Paint.Align.CENTER
                                                    android.graphics.Paint.Align.CENTER -> android.graphics.Paint.Align.RIGHT
                                                    else -> android.graphics.Paint.Align.LEFT
                                                }
                                                bubbles[id] = bubble.copy(align = nextAlign) 
                                            }
                                        ) { 
                                            Icon(
                                                when(bubble.align) {
                                                    android.graphics.Paint.Align.LEFT -> Icons.Default.FormatAlignLeft
                                                    android.graphics.Paint.Align.RIGHT -> Icons.Default.FormatAlignRight
                                                    else -> Icons.Default.FormatAlignCenter
                                                }, "Align"
                                            ) 
                                        }
                                        
                                        var expanded by remember { mutableStateOf(false) }
                                        Box {
                                            TextButton(onClick = { expanded = true }) {
                                                Text(bubble.fontPreset, style = MaterialTheme.typography.labelSmall)
                                            }
                                            androidx.compose.material3.DropdownMenu(
                                                expanded = expanded,
                                                onDismissRequest = { expanded = false }
                                            ) {
                                                val fontChoices = mutableListOf("Default", "Manga (Built-in)", "Serif", "Monospace", "Sans-Serif")
                                                if (customFontPath.isNotBlank()) fontChoices.add(1, "Custom")
                                                
                                                fontChoices.forEach { preset ->
                                                    androidx.compose.material3.DropdownMenuItem(
                                                        text = { Text(preset) },
                                                        onClick = {
                                                            bubbles[id] = bubble.copy(fontPreset = preset)
                                                            expanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    
                                    IconButton(
                                        onClick = {
                                            bubbles.remove(id)
                                            selectedBubbleId = null
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Size", style = MaterialTheme.typography.labelSmall)
                                        androidx.compose.material3.Slider(
                                            value = bubble.fontScale,
                                            onValueChange = { bubbles[id] = bubble.copy(fontScale = it) },
                                            valueRange = 0.5f..2.5f,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                    }
                                    
                                    var strokeExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(onClick = { strokeExpanded = true }) {
                                            val currentStroke = bubble.strokeColor
                                            val strokeName = when (currentStroke) {
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
                                        androidx.compose.material3.DropdownMenu(
                                            expanded = strokeExpanded,
                                            onDismissRequest = { strokeExpanded = false }
                                        ) {
                                            val strokeChoices = listOf(
                                                "Auto" to null,
                                                "Black" to "#000000",
                                                "White" to "#FFFFFF",
                                                "Red" to "#FF0000",
                                                "Green" to "#00FF00",
                                                "Blue" to "#0000FF",
                                                "Yellow" to "#FFFF00"
                                            )
                                            
                                            strokeChoices.forEach { (name, hex) ->
                                                androidx.compose.material3.DropdownMenuItem(
                                                    text = { Text(name) },
                                                    onClick = {
                                                        bubbles[id] = bubble.copy(strokeColor = hex)
                                                        strokeExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Explain why no bubble can be tapped when coordinate data is missing
                // (e.g. PDF reader pages, or outputs without persisted edit metadata).
                if (coordinateMap.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("No editable bubbles found on this page.", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Bubble data is unavailable for this page (e.g. PDF pages). Touch-up editing works on translated image results.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                
                // Add bottom spacer in Column so content can scroll past the bottom bar if necessary
                Spacer(modifier = Modifier.height(80.dp))
            } // Close Column

            // Action buttons wrapper (anchored to bottom)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { isDrawingMode = !isDrawingMode },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDrawingMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isDrawingMode) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(if (isDrawingMode) Icons.Default.Close else Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (isDrawingMode) "Cancel Draw" else "Add Box")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { 
                                val updatedTranslations = bubbles.mapValues { it.value.text }
                                val updatedCoords = bubbles.mapValues { it.value.box }
                                val updatedStyles = bubbles.toMap()
                                onSave(editedBitmap, updatedTranslations, updatedCoords, updatedStyles) 
                            },
                            enabled = bubbles.isNotEmpty(),
                        ) { Text("Save & Apply") }
                    }
                }
            }
        } // Close parent Box
    } // Close Surface
} // Close Dialog
} // Close Function
