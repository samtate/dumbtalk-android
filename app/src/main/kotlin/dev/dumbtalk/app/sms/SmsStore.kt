package dev.dumbtalk.app.sms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.dumbtalk.core.ConversationUpdate
import java.util.UUID

/** App-only flags and multipart receipts. Messages stay in Android's SMS provider. */
internal class SmsStore(context: Context) : SQLiteOpenHelper(context, "sms-options.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats (thread INTEGER PRIMARY KEY, address TEXT, favourite INTEGER NOT NULL DEFAULT 0, muted INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE attempts (message INTEGER PRIMARY KEY, token TEXT NOT NULL, parts INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE results (message INTEGER, part INTEGER, kind TEXT, code INTEGER, PRIMARY KEY(message,part,kind))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    data class Flags(val address: String?, val favourite: Boolean, val muted: Boolean, val archived: Boolean)
    fun chats(): Map<Long, Flags> = buildMap {
        readableDatabase.query("chats", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) put(c.getLong(c.getColumnIndexOrThrow("thread")), Flags(
                c.getString(c.getColumnIndexOrThrow("address")),
                c.getInt(c.getColumnIndexOrThrow("favourite")) != 0,
                c.getInt(c.getColumnIndexOrThrow("muted")) != 0,
                c.getInt(c.getColumnIndexOrThrow("archived")) != 0,
            ))
        }
    }
    fun update(thread: Long, address: String? = null, update: ConversationUpdate? = null) {
        val db = writableDatabase
        db.insertWithOnConflict("chats", null, ContentValues().apply { put("thread", thread) }, SQLiteDatabase.CONFLICT_IGNORE)
        val values = ContentValues().apply {
            address?.let { put("address", it) }
            update?.favourite?.let { put("favourite", if (it) 1 else 0) }
            update?.muted?.let { put("muted", if (it) 1 else 0) }
            update?.archived?.let { put("archived", if (it) 1 else 0) }
        }
        if (values.size() > 0) db.update("chats", values, "thread=?", arrayOf(thread.toString()))
    }
    fun deleteChat(thread: Long, messages: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("chats", "thread=?", arrayOf(thread.toString()))
            messages.forEach { message ->
                db.delete("attempts", "message=?", arrayOf(message.toString()))
                db.delete("results", "message=?", arrayOf(message.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun beginAttempt(message: Long, parts: Int): String {
        val token = UUID.randomUUID().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("results", "message=?", arrayOf(message.toString()))
            db.insertWithOnConflict("attempts", null, ContentValues().apply {
                put("message", message); put("token", token); put("parts", parts)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return token
    }
    /** Returns null for a stale callback; otherwise the current aggregate result. */
    @Synchronized
    fun result(message: Long, token: String, part: Int, kind: String, code: Int): Pair<Boolean, Boolean>? {
        val db = writableDatabase
        val parts = db.query("attempts", arrayOf("parts"), "message=? AND token=?", arrayOf(message.toString(), token), null, null, null).use {
            if (!it.moveToFirst()) return null
            it.getInt(0)
        }
        if (part !in 0 until parts) return null
        db.insertWithOnConflict("results", null, ContentValues().apply {
            put("message", message); put("part", part); put("kind", kind); put("code", code)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        var complete = 0
        var failed = false
        db.query("results", arrayOf("code"), "message=? AND kind=?", arrayOf(message.toString(), kind), null, null, null).use {
            while (it.moveToNext()) { if (it.getInt(0) == 0) complete++ else failed = true }
        }
        return failed to (complete == parts)
    }
}
