// Shared Dumb UI kit: generated from dumb-kit/ui/Family.kt by sync.py. Edit it there.
package dev.dumbtalk.app.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Choices made once for every Dumb app, such as which key acts as the left
 * softkey. Each app keeps its own copy and publishes it through
 * [FamilyProvider]. A first start adopts what any sibling already chose, and
 * a later change is passed to the others, so answering in one app answers
 * for all of them.
 */
object Family {
    enum class LeftKey {
        /** D-pad Left presses the left softkey, for systems (DumberOS) that take the real one. */
        DPAD,

        /** The real left softkey; D-pad Left only moves. */
        SOFTKEY,
    }

    /** The apps that share these choices; each also exists with a .debug suffix. */
    private val siblings = listOf("camera", "clock", "contacts", "home", "mail", "phone", "sparky", "talk")
        .flatMap { listOf("dev.dumb$it.app", "dev.dumb$it.app.debug") }

    internal const val CHANGED = "dev.dumb.family.CHANGED"
    internal const val EXTRA_FROM = "from"
    private const val PREFS = "family"
    private const val LEFT_KEY = "left_key"
    private const val LEFT_KEY_AT = "left_key_at"

    /** This app's choice, or null if nobody in the family has made one yet. */
    fun leftKey(context: Context): LeftKey? =
        prefs(context).getString(LEFT_KEY, null)?.let { runCatching { LeftKey.valueOf(it) }.getOrNull() }

    /** Records the user's choice and tells the installed siblings, which adopt it. */
    fun setLeftKey(context: Context, value: LeftKey) {
        store(context, value.name, System.currentTimeMillis())
        installedSiblings(context).forEach { pkg ->
            context.sendBroadcast(Intent(CHANGED).setPackage(pkg).putExtra(EXTRA_FROM, context.packageName))
        }
    }

    /**
     * Adopts the newest choice any installed sibling has made, for a first
     * start (before asking). Each query may start that app's process, so this
     * isn't for every resume; later changes arrive through [FamilyReceiver].
     */
    fun sync(context: Context): LeftKey? {
        installedSiblings(context).forEach { adopt(context, it) }
        return leftKey(context)
    }

    /** Takes [pkg]'s choice if it is a sibling's and newer than ours. */
    internal fun adopt(context: Context, pkg: String) {
        if (pkg !in siblings || pkg == context.packageName) return
        val (value, at) = read(context, pkg) ?: return
        if (at > prefs(context).getLong(LEFT_KEY_AT, 0)) store(context, value, at)
    }

    private fun installedSiblings(context: Context) = siblings.filter { pkg ->
        pkg != context.packageName && runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    private fun read(context: Context, pkg: String): Pair<String, Long>? = runCatching {
        context.contentResolver.query(Uri.parse("content://$pkg.family"), null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val value = cursor.getString(cursor.getColumnIndexOrThrow("value")) ?: return@use null
            value to cursor.getLong(cursor.getColumnIndexOrThrow("at"))
        }
    }.getOrNull()

    private fun store(context: Context, value: String, at: Long) {
        prefs(context).edit().putString(LEFT_KEY, value).putLong(LEFT_KEY_AT, at).apply()
    }

    internal fun row(context: Context): Pair<String, Long>? {
        val prefs = prefs(context)
        return prefs.getString(LEFT_KEY, null)?.let { it to prefs.getLong(LEFT_KEY_AT, 0) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Publishes this app's [Family] choices to its siblings, read-only. They
 * aren't private: just which key the user presses for Menu.
 */
class FamilyProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(arrayOf("key", "value", "at"))
        Family.row(context ?: return cursor)?.let { (value, at) -> cursor.addRow(arrayOf<Any>("left_key", value, at)) }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/**
 * A sibling changed a shared choice. The broadcast only names the sender:
 * the value is read back from that sibling's own provider, so another app
 * can't set it by sending this.
 */
class FamilyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val from = intent.getStringExtra(Family.EXTRA_FROM) ?: return
        val pending = goAsync()
        Thread {
            try {
                Family.adopt(context, from)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
