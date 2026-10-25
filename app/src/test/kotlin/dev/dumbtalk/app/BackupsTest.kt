package dev.dumbtalk.app

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.dumbtalk.app.backup.BackupUnreadable
import dev.dumbtalk.app.backup.Backups
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun data(path: String) = File(context.dataDir, path)

    @Test
    fun aBackupRestoresTheDataAndAWrongPassphraseChangesNothing() {
        data("files/whatsapp/messages.db").apply { parentFile!!.mkdirs(); writeText("chats") }
        data("files/whatsapp/media/photo.jpg").apply { parentFile!!.mkdirs(); writeText("photo") }
        data("shared_prefs/setup.xml").apply { parentFile!!.mkdirs(); writeText("<map/>") }
        val file = Uri.fromFile(File(context.cacheDir, "test.dtbackup"))
        Backups.backUp(context, file, "a long passphrase".toCharArray(), media = false) {}

        // Things change after the backup…
        data("files/whatsapp/messages.db").writeText("newer chats")
        data("files/telegram/td.binlog").apply { parentFile!!.mkdirs(); writeText("telegram") }

        // …a wrong passphrase leaves them alone…
        assertThrows(BackupUnreadable::class.java) { Backups.restore(context, file, "wrong".toCharArray()) {} }
        assertEquals("newer chats", data("files/whatsapp/messages.db").readText())

        // …and the right one puts back exactly what was backed up.
        Backups.restore(context, file, "a long passphrase".toCharArray()) {}
        assertEquals("chats", data("files/whatsapp/messages.db").readText())
        assertEquals("<map/>", data("shared_prefs/setup.xml").readText())
        assertFalse("media was left out", data("files/whatsapp/media/photo.jpg").exists())
        assertFalse("files from after the backup are gone", data("files/telegram/td.binlog").exists())
        assertTrue(context.dataDir.listFiles()!!.none { it.name == "restore-staging" })
    }
}
