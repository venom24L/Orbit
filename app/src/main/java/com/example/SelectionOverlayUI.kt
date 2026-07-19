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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

enum class DragTarget {
    NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER
}

@Composable
fun SelectionOverlayUI(
    accentColor: Color,
    onCancel: () -> Unit,
    onCapture: (Float, Float, Float, Float) -> Unit
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            val screenWidth = constraints.maxWidth.toFloat()
            val screenHeight = constraints.maxHeight.toFloat()

            // 1. Single State representation of the selection rectangle
            var selectionRect by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
            var isInitialized by remember { mutableStateOf(false) }

            if (!isInitialized) {
                selectionRect = Rect(
                    left = screenWidth * 0.15f,
                    top = screenHeight * 0.3f,
                    right = screenWidth * 0.85f,
                    bottom = screenHeight * 0.6f
                )
                isInitialized = true
            }

            val density = LocalDensity.current
            val minSizePx = with(density) { 40.dp.toPx() }
            val handleRadiusPx = with(density) { 28.dp.toPx() } // Generous hit area around the corner coordinates

            var dragTarget by remember { mutableStateOf(DragTarget.NONE) }

            // Entire overlay box has a single, absolute pointer tracker
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(screenWidth, screenHeight, minSizePx, handleRadiusPx) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val rect = selectionRect
                                val topLeft = Offset(rect.left, rect.top)
                                val topRight = Offset(rect.right, rect.top)
                                val bottomLeft = Offset(rect.left, rect.bottom)
                                val bottomRight = Offset(rect.right, rect.bottom)

                                dragTarget = when {
                                    (startOffset - topLeft).getDistance() <= handleRadiusPx -> DragTarget.TOP_LEFT
                                    (startOffset - topRight).getDistance() <= handleRadiusPx -> DragTarget.TOP_RIGHT
                                    (startOffset - bottomLeft).getDistance() <= handleRadiusPx -> DragTarget.BOTTOM_LEFT
                                    (startOffset - bottomRight).getDistance() <= handleRadiusPx -> DragTarget.BOTTOM_RIGHT
                                    rect.contains(startOffset) -> DragTarget.CENTER
                                    else -> DragTarget.NONE
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (dragTarget == DragTarget.NONE) return@detectDragGestures

                                val rect = selectionRect
                                when (dragTarget) {
                                    DragTarget.TOP_LEFT -> {
                                        val newLeft = (rect.left + dragAmount.x).coerceIn(0f, rect.right - minSizePx)
                                        val newTop = (rect.top + dragAmount.y).coerceIn(0f, rect.bottom - minSizePx)
                                        selectionRect = Rect(newLeft, newTop, rect.right, rect.bottom)
                                    }
                                    DragTarget.TOP_RIGHT -> {
                                        val newRight = (rect.right + dragAmount.x).coerceIn(rect.left + minSizePx, screenWidth)
                                        val newTop = (rect.top + dragAmount.y).coerceIn(0f, rect.bottom - minSizePx)
                                        selectionRect = Rect(rect.left, newTop, newRight, rect.bottom)
                                    }
                                    DragTarget.BOTTOM_LEFT -> {
                                        val newLeft = (rect.left + dragAmount.x).coerceIn(0f, rect.right - minSizePx)
                                        val newBottom = (rect.bottom + dragAmount.y).coerceIn(rect.top + minSizePx, screenHeight)
                                        selectionRect = Rect(newLeft, rect.top, rect.right, newBottom)
                                    }
                                    DragTarget.BOTTOM_RIGHT -> {
                                        val newRight = (rect.right + dragAmount.x).coerceIn(rect.left + minSizePx, screenWidth)
                                        val newBottom = (rect.bottom + dragAmount.y).coerceIn(rect.top + minSizePx, screenHeight)
                                        selectionRect = Rect(rect.left, rect.top, newRight, newBottom)
                                    }
                                    DragTarget.CENTER -> {
                                        val width = rect.width
                                        val height = rect.height

                                        var newLeft = rect.left + dragAmount.x
                                        var newTop = rect.top + dragAmount.y
                                        var newRight = newLeft + width
                                        var newBottom = newTop + height

                                        // Constrain the dragged bounds to the screen boundaries
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

                                        selectionRect = Rect(newLeft, newTop, newRight, newBottom)
                                    }
                                    DragTarget.NONE -> {}
                                }
                            },
                            onDragEnd = {
                                dragTarget = DragTarget.NONE
                            },
                            onDragCancel = {
                                dragTarget = DragTarget.NONE
                            }
                        )
                    }
                    .graphicsLayer { alpha = 0.99f } // Forces offscreen buffer layer for clear blend mode
                    .drawBehind {
                        // 1. Draw solid dark backdrop
                        drawRect(color = Color.Black.copy(alpha = 0.7f))

                        // 2. Cut out the transparent inner region from the exact selectionRect state
                        drawRect(
                            color = Color.Transparent,
                            topLeft = Offset(selectionRect.left, selectionRect.top),
                            size = Size(selectionRect.width, selectionRect.height),
                            blendMode = BlendMode.Clear
                        )

                        // 3. Draw high-contrast neon styling border
                        drawRect(
                            color = accentColor,
                            topLeft = Offset(selectionRect.left, selectionRect.top),
                            size = Size(selectionRect.width, selectionRect.height),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
            ) {
                // Draw visually beautiful corner handles derived directly from selectionRect
                DragHandle(x = selectionRect.left, y = selectionRect.top, color = accentColor)
                DragHandle(x = selectionRect.right, y = selectionRect.top, color = accentColor)
                DragHandle(x = selectionRect.left, y = selectionRect.bottom, color = accentColor)
                DragHandle(x = selectionRect.right, y = selectionRect.bottom, color = accentColor)

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
                            onClick = { onCapture(selectionRect.left, selectionRect.top, selectionRect.right, selectionRect.bottom) },
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
}

@Composable
fun DragHandle(
    x: Float,
    y: Float,
    color: Color
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
            .size(handleSize),
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
