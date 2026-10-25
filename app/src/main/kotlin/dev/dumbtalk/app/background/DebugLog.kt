package dev.dumbtalk.app.background

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import dev.dumbtalk.app.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * This app's recent log lines (the bridges and TDLib log there too) as a file
 * to attach to a bug report. Any app may read its own log without permission.
 */
object DebugLog {
    /** Writes the log and offers it to a sharing app; false if none can take it. */
    suspend fun share(context: Context): Boolean {
        val file = withContext(Dispatchers.IO) {
            val log = runCatching {
                ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}")
                    .redirectErrorStream(true).start()
                    .inputStream.bufferedReader().use { it.readText() }
            }.getOrElse { "Couldn't read the log: ${it.message}" }
            File(context.cacheDir, "dumbtalk-log.txt").apply {
                writeText(
                    "DumbTalk ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})\n" +
                        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n\n$log",
                )
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "DumbTalk debug log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching {
            context.startActivity(Intent.createChooser(send, "Share debug log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}
