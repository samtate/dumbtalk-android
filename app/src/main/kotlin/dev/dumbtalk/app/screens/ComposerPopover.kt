package dev.dumbtalk.app.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dumbtalk.app.ui.Palette

/** In-tree modal: it shares the activity's hardware softkeys and leaves the chat visible. */
@Composable
internal fun ComposerPopover(title: String, anchor: Rect?, onClose: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().semantics { paneTitle = title }) {
        // Ordered 2×2 dither, snapped to physical pixels. Leave the composer clear.
        val chatBottom = (maxHeight - 40.dp).coerceAtLeast(0.dp)
        Canvas(Modifier.fillMaxWidth().height(chatBottom)
            .pointerInput(onClose) { detectTapGestures { onClose() } }) {
            val pixel = 2.dp.toPx().toInt().coerceAtLeast(1).toFloat()
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = if (row % 2 == 0) 0f else pixel
                while (x < size.width) {
                    drawRect(Palette.panel, Offset(x, y), Size(pixel, pixel))
                    x += pixel * 2
                }
                row++
                y += pixel
            }
        }
        // Bounds are in the activity's content root, whose top matches ChatScreen.
        val bottom = with(density) { anchor?.top?.toDp() } ?: (maxHeight - 40.dp)
        val gap = (maxHeight - bottom).coerceAtLeast(40.dp)
        val tailX = with(density) { (anchor?.center?.x ?: 30.dp.toPx()).toDp() } - 8.dp
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = gap).padding(horizontal = 8.dp).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().heightIn(max = (bottom - 18.dp).coerceAtLeast(100.dp))
                .background(Palette.background, RoundedCornerShape(6.dp))
                .border(1.dp, Palette.line, RoundedCornerShape(6.dp)).padding(6.dp)
                .pointerInput(Unit) { detectTapGestures { } }) { content() }
            Canvas(Modifier.fillMaxWidth().height(9.dp)) {
                val x = tailX.toPx().coerceIn(10.dp.toPx(), size.width - 10.dp.toPx())
                val half = 8.dp.toPx()
                val triangle = Path().apply { moveTo(x - half, -1.dp.toPx()); lineTo(x, size.height); lineTo(x + half, -1.dp.toPx()); close() }
                drawPath(triangle, Palette.background)
                val edge = Path().apply { moveTo(x - half, 0f); lineTo(x, size.height); lineTo(x + half, 0f) }
                drawPath(edge, Palette.line, style = Stroke(1.dp.toPx()))
            }
        }
    }
}
