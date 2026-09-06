package network.columba.app.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/** Private durable inbox. Replay keys and acknowledgments survive process death. */
class TaskStore(
    context: Context,
) : SQLiteOpenHelper(context, "tak_tasks.db", null, 1) {
    data class Row(
        val owner: String,
        val message: TaskCodec.Message,
        val publicKey: String,
        val status: Int,
        val attempts: Int,
        val lastAttempt: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tasks(owner TEXT NOT NULL, issuer TEXT NOT NULL, id TEXT NOT NULL,
            issued INTEGER NOT NULL, expires INTEGER NOT NULL, lat INTEGER NOT NULL, lon INTEGER NOT NULL,
            instruction TEXT NOT NULL, public_key TEXT NOT NULL, digest BLOB NOT NULL,
            status INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_attempt INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(owner,issuer,id))
            """.trimIndent(),
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) = error("Unsupported task schema")

    @Synchronized
    fun receive(
        owner: String,
        message: TaskCodec.Message,
        wire: ByteArray,
        key: String,
        now: Long,
    ): Boolean {
        require(message.kind == TaskCodec.GOTO && message.expires > now && owner == message.recipient)
        val db = writableDatabase
        val digest = MessageDigest.getInstance("SHA-256").digest(wire)
        db.beginTransaction()
        try {
            db.delete("tasks", "expires < ?", arrayOf((now - 86400).toString()))
            db
                .rawQuery(
                    "SELECT digest FROM tasks WHERE owner=? AND issuer=? AND id=?",
                    arrayOf(owner, message.issuer, message.taskId),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        require(cursor.getBlob(0).contentEquals(digest)) { "Task ID reused with different content" }
                        db.setTransactionSuccessful()
                        return false
                    }
                }
            db.rawQuery("SELECT count(*) FROM tasks", null).use {
                check(it.moveToFirst() && it.getInt(0) < 512) { "Task inbox full" }
            }
            val values =
                ContentValues().apply {
                    put("owner", owner)
                    put("issuer", message.issuer)
                    put("id", message.taskId)
                    put("issued", message.issued)
                    put("expires", message.expires)
                    put("lat", message.latE7)
                    put("lon", message.lonE7)
                    put("instruction", message.instruction)
                    put("public_key", key)
                    put("digest", digest)
                    put("status", TaskCodec.RECEIVED)
                }
            db.insertOrThrow("tasks", null, values)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun rows(owner: String): List<Row> =
        readableDatabase
            .rawQuery(
                "SELECT * FROM tasks WHERE owner=? ORDER BY issued DESC",
                arrayOf(owner),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        fun text(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))

                        fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                        add(
                            Row(
                                owner,
                                TaskCodec.Message(
                                    TaskCodec.GOTO,
                                    text("issuer"),
                                    owner,
                                    text("id"),
                                    number("issued"),
                                    number("expires"),
                                    number("lat").toInt(),
                                    number("lon").toInt(),
                                    text("instruction"),
                                ),
                                text("public_key"),
                                number("status").toInt(),
                                number("attempts").toInt(),
                                number("last_attempt"),
                            ),
                        )
                    }
                }
            }

    @Synchronized
    fun decide(
        owner: String,
        issuer: String,
        id: String,
        status: Int,
        now: Long,
    ): Boolean {
        require(status == TaskCodec.ACCEPTED || status == TaskCodec.DECLINED)
        val values =
            ContentValues().apply {
                put("status", status)
                put("attempts", 0)
                put("last_attempt", 0)
            }
        return writableDatabase.update(
            "tasks",
            values,
            "owner=? AND issuer=? AND id=? AND status=? AND expires>?",
            arrayOf(owner, issuer, id, TaskCodec.RECEIVED.toString(), now.toString()),
        ) == 1
    }

    @Synchronized
    fun markAttempt(
        row: Row,
        now: Long,
    ): Boolean {
        val values =
            ContentValues().apply {
                put("attempts", row.attempts + 1)
                put("last_attempt", now)
            }
        return writableDatabase.update(
            "tasks",
            values,
            "owner=? AND issuer=? AND id=? AND status=? AND attempts=?",
            arrayOf(row.owner, row.message.issuer, row.message.taskId, row.status.toString(), row.attempts.toString()),
        ) == 1
    }
}
