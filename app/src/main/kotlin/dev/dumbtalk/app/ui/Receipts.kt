package dev.dumbtalk.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.dumbtalk.core.Receipt

/** Read ticks stand out in green; sent and delivered stay grey. */
val ReadColor = Color(0xFF6EE07A)

/** ⏱ while sending, ✓ sent, ✓✓ delivered, green ✓✓ read. */
fun receiptMark(receipt: Receipt?): AnnotatedString = buildAnnotatedString {
    when (receipt?.state) {
        null -> withStyle(SpanStyle(color = Palette.muted)) { append("⏱") }
        Receipt.State.SENT -> withStyle(SpanStyle(color = Palette.muted)) { append("✓") }
        Receipt.State.DELIVERED -> withStyle(SpanStyle(color = Palette.muted)) { append("✓✓") }
        Receipt.State.READ -> withStyle(SpanStyle(color = ReadColor)) { append("✓✓") }
    }
}
