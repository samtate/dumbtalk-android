package dev.dumbtalk.app.backup

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Backs the app's data up to a file the person picked, and restores it.
 * Both run with no service started (see DumbTalkApp.maintenance), so no
 * database is open while it's copied or replaced.
 */
object Backups {
    /** Everything DumbTalk keeps: the services' stores and keys, and settings. */
    private val ROOTS = listOf("files", "databases", "shared_prefs")
    /** Received and sent photos and files; optional, as they're most of the size. */
    private val MEDIA = listOf("files/telegram/files/", "files/whatsapp/media/", "files/signal/attachments/")

    /** Writes an encrypted backup to [uri]; returns how much data went into it. */
    fun backUp(context: Context, uri: Uri, passphrase: CharArray, media: Boolean, progress: (Long) -> Unit): Long {
        var total = 0L
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Couldn't open that file")
        BackupFormat.encrypt(out.buffered(), passphrase).use { zip ->
            BackupFormat.writeArchive(zip, context.dataDir, ROOTS, skip = { name -> !media && MEDIA.any(name::startsWith) }) {
                total = it
                progress(it)
            }
        }
        return total
    }

    /**
     * Replaces DumbTalk's data with the backup at [uri]. It's unpacked aside
     * first, so a wrong passphrase or a damaged file changes nothing.
     */
    fun restore(context: Context, uri: Uri, passphrase: CharArray, progress: (Long) -> Unit) {
        val staging = File(context.dataDir, "restore-staging").apply { deleteRecursively() }
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open that file")
            BackupFormat.readArchive(BackupFormat.decrypt(input.buffered(), passphrase), staging, progress)
            for (root in ROOTS) {
                val restored = File(staging, root)
                if (!restored.exists()) continue
                val current = File(context.dataDir, root)
                current.deleteRecursively()
                if (!restored.renameTo(current)) throw IOException("Couldn't put the restored $root in place")
            }
        } finally {
            staging.deleteRecursively()
        }
    }
}
