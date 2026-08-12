package dev.imian.herald.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class EventDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(database: SQLiteDatabase) {
        super.onConfigure(database)
        database.rawQuery("PRAGMA secure_delete = ON", null).use { cursor ->
            if (cursor.moveToFirst()) {
                // Reading the result applies secure deletion for this connection.
            }
        }
        database.enableWriteAheadLogging()
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE events (
                id TEXT NOT NULL PRIMARY KEY,
                source_package TEXT NOT NULL,
                source_label TEXT NOT NULL,
                notification_key TEXT NOT NULL,
                source_conversation_id TEXT,
                conversation TEXT,
                sender TEXT,
                message_text TEXT,
                sent_at INTEGER,
                captured_at INTEGER NOT NULL,
                is_group_conversation INTEGER,
                has_attachment INTEGER NOT NULL,
                attachment_mime_type TEXT,
                extraction_method TEXT NOT NULL,
                content_truncated INTEGER NOT NULL,
                delivery_state TEXT NOT NULL,
                delivery_route_id TEXT,
                delivery_attempts INTEGER NOT NULL DEFAULT 0,
                delivered_at INTEGER,
                last_error TEXT
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX events_captured_at_idx ON events(captured_at DESC)",
        )
        database.execSQL(
            "CREATE INDEX events_delivery_state_idx ON events(delivery_state, captured_at)",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            database.execSQL("ALTER TABLE events ADD COLUMN delivery_route_id TEXT")
        }
    }

    private companion object {
        const val DATABASE_NAME = "herald-events.db"
        const val DATABASE_VERSION = 2
    }
}
