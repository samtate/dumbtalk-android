package dev.dumbtalk.app

import dev.dumbtalk.app.ui.attachmentLine
import dev.dumbtalk.app.ui.formatSize
import dev.dumbtalk.core.Attachment
import dev.dumbtalk.core.AttachmentKind
import dev.dumbtalk.core.Direction
import dev.dumbtalk.core.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentTextTest {
    private fun message(attachment: Attachment) = Message("m", "c", 1, Direction.INCOMING, attachments = listOf(attachment))

    @Test
    fun sizesAreReadable() {
        assertEquals("1 KB", formatSize(10))
        assertEquals("340 KB", formatSize(340 * 1024))
        assertEquals("2.3 MB", formatSize((2.3 * 1024 * 1024).toLong()))
    }

    @Test
    fun linesDescribeWhatPicturesDont() {
        assertNull(attachmentLine(message(Attachment("a", AttachmentKind.IMAGE, size = 5))))
        assertEquals("▶ Video · 2.0 MB", attachmentLine(message(Attachment("a", AttachmentKind.VIDEO, size = 2L * 1024 * 1024))))
        assertEquals("▶ Audio message", attachmentLine(message(Attachment("a", AttachmentKind.AUDIO))))
        assertEquals("report.pdf · 120 KB", attachmentLine(message(Attachment("a", AttachmentKind.FILE, filename = "report.pdf", size = 120L * 1024))))
        assertEquals("File", attachmentLine(message(Attachment("a", AttachmentKind.FILE))))
    }
}
