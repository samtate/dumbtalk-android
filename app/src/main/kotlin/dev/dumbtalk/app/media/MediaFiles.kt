package dev.dumbtalk.app.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File

/** Files in and out: viewing attachments in other apps and copying picked files in. */
object MediaFiles {
    private fun authority(context: Context) = "${context.packageName}.files"

    /** Opens a downloaded attachment in whichever app handles its type. False if none does. */
    fun open(context: Context, path: String, mime: String?): Boolean {
        val uri = FileProvider.getUriForFile(context, authority(context), File(path))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime?.substringBefore(';') ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Copies a picked document into the cache (services need a file path). */
    fun copyIn(context: Context, uri: Uri): Picked {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "file"
        val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
        val file = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't read that file" }
            file.outputStream().use { input.copyTo(it) }
        }
        return Picked(file.absolutePath, resolver.getType(uri) ?: "application/octet-stream")
    }

    data class Picked(val path: String, val mime: String)

    /** Decodes an image no larger than [maxSide] pixels on its long side. */
    fun thumbnail(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
