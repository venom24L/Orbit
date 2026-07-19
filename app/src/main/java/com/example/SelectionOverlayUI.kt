package com.example

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun SelectionOverlayUI(
    accentColor: Color,
    onCancel: () -> Unit,
    onCapture: (Float, Float, Float, Float) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()

        // Initialize selection coordinates in center
        var boxLeft by remember { mutableStateOf(0f) }
        var boxTop by remember { mutableStateOf(0f) }
        var boxRight by remember { mutableStateOf(0f) }
        var boxBottom by remember { mutableStateOf(0f) }
        var isInitialized by remember { mutableStateOf(false) }

        if (!isInitialized) {
            boxLeft = screenWidth * 0.15f
            boxRight = screenWidth * 0.85f
            boxTop = screenHeight * 0.3f
            boxBottom = screenHeight * 0.6f
            isInitialized = true
        }

        val density = LocalDensity.current
        val minSizePx = with(density) { 100.dp.toPx() }

        // Background dark overlay with transparent center cutout using BlendMode.Clear
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.99f } // Forces offscreen buffer layer for clear blend mode
                .drawBehind {
                    // 1. Draw solid dark backdrop
                    drawRect(color = Color.Black.copy(alpha = 0.7f))

                    // 2. Cut out the transparent inner region
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset(boxLeft, boxTop),
                        size = Size(boxRight - boxLeft, boxBottom - boxTop),
                        blendMode = BlendMode.Clear
                    )

                    // 3. Draw high-contrast neon styling border
                    drawRect(
                        color = accentColor,
                        topLeft = Offset(boxLeft, boxTop),
                        size = Size(boxRight - boxLeft, boxBottom - boxTop),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
        ) {
            // Drag Region: Center of the selection box to reposition the entire frame
            Box(
                modifier = Modifier
                    .offset { IntOffset(boxLeft.roundToInt(), boxTop.roundToInt()) }
                    .size(
                        width = with(density) { (boxRight - boxLeft).toDp() },
                        height = with(density) { (boxBottom - boxTop).toDp() }
                    )
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val width = boxRight - boxLeft
                            val height = boxBottom - boxTop

                            var newLeft = boxLeft + dragAmount.x
                            var newTop = boxTop + dragAmount.y
                            var newRight = newLeft + width
                            var newBottom = newTop + height

                            // Constrain bounds to screen limits
                            if (newLeft < 0f) {
                                newLeft = 0f
                                newRight = width
                            }
                            if (newRight > screenWidth) {
                                newRight = screenWidth
                                newLeft = screenWidth - width
                            }
                            if (newTop < 0f) {
                                newTop = 0f
                                newBottom = height
                            }
                            if (newBottom > screenHeight) {
                                newBottom = screenHeight
                                newTop = screenHeight - height
                            }

                            boxLeft = newLeft
                            boxTop = newTop
                            boxRight = newRight
                            boxBottom = newBottom
                        }
                    }
            )

            // 48dp Accessible Corner Handles (Touch targets are large, visual representations are sleek)
            DragHandle(
                x = boxLeft,
                y = boxTop,
                color = accentColor
            ) { dx, dy ->
                boxLeft = (boxLeft + dx).coerceIn(0f, boxRight - minSizePx)
                boxTop = (boxTop + dy).coerceIn(0f, boxBottom - minSizePx)
            }

            DragHandle(
                x = boxRight,
                y = boxTop,
                color = accentColor
            ) { dx, dy ->
                boxRight = (boxRight + dx).coerceIn(boxLeft + minSizePx, screenWidth)
                boxTop = (boxTop + dy).coerceIn(0f, boxBottom - minSizePx)
            }

            DragHandle(
                x = boxLeft,
                y = boxBottom,
                color = accentColor
            ) { dx, dy ->
                boxLeft = (boxLeft + dx).coerceIn(0f, boxRight - minSizePx)
                boxBottom = (boxBottom + dy).coerceIn(boxTop + minSizePx, screenHeight)
            }

            DragHandle(
                x = boxRight,
                y = boxBottom,
                color = accentColor
            ) { dx, dy ->
                boxRight = (boxRight + dx).coerceIn(boxLeft + minSizePx, screenWidth)
                boxBottom = (boxBottom + dy).coerceIn(boxTop + minSizePx, screenHeight)
            }

            // High-fidelity docked control bar containing Action triggers
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp)
                    .background(Color(0xE6121212), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { onCapture(boxLeft, boxTop, boxRight, boxBottom) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "OCR scanning icon",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan Selection", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DragHandle(
    x: Float,
    y: Float,
    color: Color,
    onDrag: (Float, Float) -> Unit
) {
    val handleSize = 48.dp
    val density = LocalDensity.current
    val sizePx = with(density) { handleSize.toPx() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (x - sizePx / 2).roundToInt(),
                    (y - sizePx / 2).roundToInt()
                )
            }
            .size(handleSize)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color, RoundedCornerShape(50))
                .border(2.dp, Color.White, RoundedCornerShape(50))
        )
    }
}
