package com.example.ocr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * Single source of truth for the selection rectangle. Both the outline drawn on the [Canvas]
 * and the four corner handles derive from this ONE state object — there is no separately
 * tracked "handle offset" list, which is what previously let the outline and handles visually
 * drift apart from each other mid-drag.
 */
class SelectionRectState(initialRect: ComposeRect) {
    var rect by mutableStateOf(initialRect)
        private set

    fun dragCorner(corner: Corner, delta: Offset, bounds: ComposeRect, minSizePx: Float) {
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom

        when (corner) {
            Corner.TOP_LEFT -> {
                left = (left + delta.x).coerceIn(bounds.left, right - minSizePx)
                top = (top + delta.y).coerceIn(bounds.top, bottom - minSizePx)
            }
            Corner.TOP_RIGHT -> {
                right = (right + delta.x).coerceIn(left + minSizePx, bounds.right)
                top = (top + delta.y).coerceIn(bounds.top, bottom - minSizePx)
            }
            Corner.BOTTOM_LEFT -> {
                left = (left + delta.x).coerceIn(bounds.left, right - minSizePx)
                bottom = (bottom + delta.y).coerceIn(top + minSizePx, bounds.bottom)
            }
            Corner.BOTTOM_RIGHT -> {
                right = (right + delta.x).coerceIn(left + minSizePx, bounds.right)
                bottom = (bottom + delta.y).coerceIn(top + minSizePx, bounds.bottom)
            }
        }

        rect = ComposeRect(left, top, right, bottom)
    }

    fun dragWhole(delta: Offset, bounds: ComposeRect) {
        val width = rect.width
        val height = rect.height
        val left = (rect.left + delta.x).coerceIn(bounds.left, bounds.right - width)
        val top = (rect.top + delta.y).coerceIn(bounds.top, bounds.bottom - height)
        rect = ComposeRect(left, top, left + width, top + height)
    }
}

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Full-screen translucent selection overlay with a movable/resizable rectangle.
 *
 * Forced to LTR regardless of the app's current UI language: the rectangle represents raw
 * screen pixel coordinates that must be captured and cropped exactly as drawn. If this
 * composable inherited an RTL layout direction (e.g. the user has Arabic selected as the app
 * language), Compose would mirror the X-axis of drag deltas and corner anchoring, silently
 * capturing the wrong region of the screen.
 */
@Composable
fun ScreenSelectionOverlay(
    accentColor: Color,
    onConfirm: (ComposeRect) -> Unit,
    onCancel: () -> Unit
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        ScreenSelectionOverlayContent(accentColor = accentColor, onConfirm = onConfirm, onCancel = onCancel)
    }
}

@Composable
private fun ScreenSelectionOverlayContent(
    accentColor: Color,
    onConfirm: (ComposeRect) -> Unit,
    onCancel: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
    ) {
        val density = LocalDensity.current
        val fullWidthPx = with(density) { maxWidth.toPx() }
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val minSizePx = with(density) { OcrConstants.MIN_SELECTION_SIZE_DP.dp.toPx() }
        val handleTouchPx = with(density) { OcrConstants.HANDLE_TOUCH_TARGET_DP.dp.toPx() }
        val handleVisualDp = OcrConstants.HANDLE_VISUAL_SIZE_DP.dp

        val bounds = remember(fullWidthPx, fullHeightPx) {
            ComposeRect(0f, 0f, fullWidthPx, fullHeightPx)
        }

        // Initial selection: centered rectangle covering roughly 60% width / 30% height.
        val selectionState = remember(bounds) {
            val w = bounds.width * 0.6f
            val h = bounds.height * 0.3f
            val left = bounds.left + (bounds.width - w) / 2f
            val top = bounds.top + (bounds.height - h) / 2f
            SelectionRectState(ComposeRect(left, top, left + w, top + h))
        }

        val rect = selectionState.rect

        // Outline is drawn directly from selectionState.rect — same object the handles read.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                color = accentColor,
                topLeft = rect.topLeft,
                size = ComposeSize(rect.width, rect.height),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Draggable body of the rectangle (moves the whole selection).
        Box(
            modifier = Modifier
                .offsetPx(rect.left, rect.top)
                .sizePx(rect.width, rect.height)
                .pointerInput(selectionState) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        selectionState.dragWhole(dragAmount, bounds)
                    }
                }
        )

        // Four corner handles ONLY — deliberately no mid-edge handles, to keep the interaction
        // simple. Each handle reads its position from, and writes drags back into, the exact
        // same selectionState.rect used for the outline above.
        CornerHandle(
            corner = Corner.TOP_LEFT,
            position = rect.topLeft,
            touchTargetPx = handleTouchPx,
            visualSize = handleVisualDp,
            accentColor = accentColor
        ) { delta -> selectionState.dragCorner(Corner.TOP_LEFT, delta, bounds, minSizePx) }

        CornerHandle(
            corner = Corner.TOP_RIGHT,
            position = Offset(rect.right, rect.top),
            touchTargetPx = handleTouchPx,
            visualSize = handleVisualDp,
            accentColor = accentColor
        ) { delta -> selectionState.dragCorner(Corner.TOP_RIGHT, delta, bounds, minSizePx) }

        CornerHandle(
            corner = Corner.BOTTOM_LEFT,
            position = Offset(rect.left, rect.bottom),
            touchTargetPx = handleTouchPx,
            visualSize = handleVisualDp,
            accentColor = accentColor
        ) { delta -> selectionState.dragCorner(Corner.BOTTOM_LEFT, delta, bounds, minSizePx) }

        CornerHandle(
            corner = Corner.BOTTOM_RIGHT,
            position = Offset(rect.right, rect.bottom),
            touchTargetPx = handleTouchPx,
            visualSize = handleVisualDp,
            accentColor = accentColor
        ) { delta -> selectionState.dragCorner(Corner.BOTTOM_RIGHT, delta, bounds, minSizePx) }

        // Bottom action bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        ) {
            Text(
                text = "Drag corners to resize \u00B7 Drag inside to move",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f))
                ) {
                    Text("Cancel", color = Color.White)
                }
                Button(
                    onClick = { onConfirm(selectionState.rect) },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Text("Scan", color = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun CornerHandle(
    corner: Corner,
    position: Offset,
    touchTargetPx: Float,
    visualSize: Dp,
    accentColor: Color,
    onDrag: (Offset) -> Unit
) {
    val halfTouch = touchTargetPx / 2f
    Box(
        modifier = Modifier
            .offsetPx(position.x - halfTouch, position.y - halfTouch)
            .sizePx(touchTargetPx, touchTargetPx)
            .pointerInput(corner) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(visualSize)
                .background(accentColor, CircleShape)
        )
    }
}

/** Positions a composable at an absolute pixel offset within its BoxWithConstraints parent. */
private fun Modifier.offsetPx(xPx: Float, yPx: Float): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.place(IntOffset(xPx.toInt(), yPx.toInt()))
    }
}

/** Sizes a composable using absolute pixel dimensions rather than dp. */
private fun Modifier.sizePx(widthPx: Float, heightPx: Float): Modifier = this.layout { measurable, constraints ->
    val w = max(0, widthPx.toInt())
    val h = max(0, heightPx.toInt())
    val placeable = measurable.measure(
        constraints.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h)
    )
    layout(placeable.width, placeable.height) {
        placeable.place(0, 0)
    }
}
