package dev.dumbtalk.app

import dev.dumbtalk.app.backup.BackupFormat
import dev.dumbtalk.app.backup.BackupUnreadable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    private val passphrase = "correct horse".toCharArray()

    /** Few iterations: the format, not PBKDF2's cost, is under test. */
    private fun seal(plain: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> BackupFormat.encrypt(out, passphrase.copyOf(), iterations = 1_000).use { it.write(plain) } }.toByteArray()

    private fun open(sealed: ByteArray, with: CharArray = passphrase.copyOf()): ByteArray =
        BackupFormat.decrypt(ByteArrayInputStream(sealed), with).readBytes()

    @Test
    fun aRoundTripOverSeveralChunksGivesBackTheSameBytes() {
        val plain = Random(1).nextBytes(2_500_000)
        assertArrayEquals(plain, open(seal(plain)))
        assertArrayEquals(ByteArray(0), open(seal(ByteArray(0))))
    }

    @Test
    fun aWrongPassphraseIsRefused() {
        val error = assertThrows(BackupUnreadable::class.java) { open(seal(ByteArray(10)), "wrong".toCharArray()) }
        assertTrue(error.message!!.startsWith("Wrong passphrase"))
    }

    @Test
    fun aChangedByteIsCaught() {
        val sealed = seal(Random(2).nextBytes(1_500_000))
        sealed[sealed.size - 100] = (sealed[sealed.size - 100] + 1).toByte()
        assertThrows(BackupUnreadable::class.java) { open(sealed) }
    }

    @Test
    fun aFileCutShortIsCaughtEvenAtAChunkBoundary() {
        val sealed = seal(Random(3).nextBytes(1_500_000))
        // Drop the whole last chunk: what's left is still well formed, but never ends.
        val firstChunkEnd = 9 + 16 + 4 + 4 + 4 + (1 shl 20) + 16
        assertThrows(BackupUnreadable::class.java) { open(sealed.copyOf(firstChunkEnd)) }
        assertThrows(BackupUnreadable::class.java) { open(sealed.copyOf(sealed.size - 1)) }
    }

    @Test
    fun anArchiveKeepsTheChosenFilesAndRestoresThem() {
        val source = Files.createTempDirectory("backup-src").toFile()
        File(source, "files/whatsapp/messages.db").apply { parentFile!!.mkdirs(); writeText("chats") }
        File(source, "files/whatsapp/media/photo.jpg").apply { parentFile!!.mkdirs(); writeText("big") }
        File(source, "shared_prefs/setup.xml").apply { parentFile!!.mkdirs(); writeText("<map/>") }
        File(source, "cache/tmp").apply { parentFile!!.mkdirs(); writeText("not wanted") }

        val sealed = ByteArrayOutputStream().also { out ->
            BackupFormat.encrypt(out, passphrase.copyOf(), iterations = 1_000).use { zip ->
                BackupFormat.writeArchive(zip, source, listOf("files", "shared_prefs"), skip = { it.startsWith("files/whatsapp/media/") })
            }
        }.toByteArray()

        val dest = Files.createTempDirectory("backup-dest").toFile()
        BackupFormat.readArchive(BackupFormat.decrypt(ByteArrayInputStream(sealed), passphrase.copyOf()), dest)
        assertEquals("chats", File(dest, "files/whatsapp/messages.db").readText())
        assertEquals("<map/>", File(dest, "shared_prefs/setup.xml").readText())
        assertFalse(File(dest, "files/whatsapp/media/photo.jpg").exists())
        assertFalse(File(dest, "cache/tmp").exists())
    }

    @Test
    fun anEntryOutsideTheRestoreFolderIsRefused() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("../escaped")); it.write(1); it.closeEntry() }
        }.toByteArray()
        val dest = Files.createTempDirectory("backup-dest").toFile()
        assertThrows(BackupUnreadable::class.java) { BackupFormat.readArchive(ByteArrayInputStream(zip), dest) }
        assertFalse(File(dest.parentFile, "escaped").exists())
    }
}
