package com.kzkt.app.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

@Composable
internal fun ZoomablePageViewer(
    bitmap: Bitmap?,
    onTap: () -> Unit,
    onZoomStateChanged: (Boolean) -> Unit = {},
) {
    if (bitmap == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Viewport size (from layout) is used to clamp pan so the image can never be
    // pushed fully off-screen while zoomed.
    var viewportSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // Pan multiplier: while zoomed, the image follows the finger more eagerly than
    // 1:1 so small drags cover more ground (previous feel was sluggish).
    val panSensitivity = 1.8f

    LaunchedEffect(bitmap) {
        scale = 1f
        offset = Offset.Zero
        onZoomStateChanged(false)
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                    onDoubleClick = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoomStateChanged(false)
                        } else {
                            scale = 2.5f
                            onZoomStateChanged(true)
                        }
                    },
                ).graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                // Custom gesture handling replaces Modifier.transformable, which had
                // `enabled = scale > 1f` — that made pinch-zoom dead at 1x (only the
                // double-tap could zoom in). This handler:
                //  - pinches (2+ fingers) always, even from 1x;
                //  - pans with a single finger only once zoomed;
                //  - leaves 1x single-finger swipes unconsumed so the pager still pages.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pointerCount = event.changes.count { it.pressed }
                            if (pointerCount >= 2 || scale > 1f) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                event.changes.forEach { if (it.positionChanged()) it.consume() }

                                val newScale = (scale * zoomChange).coerceIn(1f, 4f)
                                scale = newScale
                                onZoomStateChanged(newScale > 1f)
                                if (newScale > 1f) {
                                    offset += panChange * panSensitivity
                                    // Keep the image on screen: limit pan to the extra
                                    // space the zoom reveals around the viewport center.
                                    if (viewportSize.width > 0 && viewportSize.height > 0) {
                                        val maxX = viewportSize.width * (newScale - 1f) / 2f
                                        val maxY = viewportSize.height * (newScale - 1f) / 2f
                                        offset =
                                            Offset(
                                                offset.x.coerceIn(-maxX, maxX),
                                                offset.y.coerceIn(-maxY, maxY),
                                            )
                                    }
                                } else {
                                    offset = Offset.Zero
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
