package dev.imian.herald.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EventRepository(context: Context) {
    private val database = EventDatabase(context.applicationContext)
    private val mutex = Mutex()
    private val _recentEvents = MutableStateFlow<List<StoredMessageEvent>>(emptyList())

    val recentEvents: StateFlow<List<StoredMessageEvent>> = _recentEvents.asStateFlow()

    suspend fun refresh(now: Long = System.currentTimeMillis()) = withDatabaseLock {
        val writable = database.writableDatabase
        if (pruneOldEvents(writable, now)) checkpointWal(writable)
        _recentEvents.value = queryRecent(writable)
    }

    suspend fun record(
        messages: List<NormalizedMessage>,
        sourceLabel: String,
        deliveryRouteId: String?,
        capturedAt: Long = System.currentTimeMillis(),
    ): List<String> = withDatabaseLock {
        if (messages.isEmpty()) return@withDatabaseLock emptyList()

        val writable = database.writableDatabase
        val insertedIds = mutableListOf<String>()
        var pruned = false
        writable.transaction {
            messages.forEach { message ->
                val values = ContentValues().apply {
                    put("id", message.id)
                    put("source_package", message.sourcePackage)
                    put("source_label", sourceLabel)
                    put("notification_key", message.notificationKey)
                    put("source_conversation_id", message.sourceConversationId)
                    put("conversation", message.conversation)
                    put("sender", message.sender)
                    put("message_text", message.text)
                    putNullableLong("sent_at", message.sentAt)
                    put("captured_at", capturedAt)
                    putNullableBoolean("is_group_conversation", message.isGroupConversation)
                    put("has_attachment", message.hasAttachment.asDatabaseInt())
                    put("attachment_mime_type", message.attachmentMimeType)
                    put("extraction_method", message.extractionMethod.wireValue)
                    put("content_truncated", message.contentTruncated.asDatabaseInt())
                    put(
                        "delivery_state",
                        if (deliveryRouteId != null) {
                            DeliveryState.PENDING.databaseValue
                        } else {
                            DeliveryState.LOCAL.databaseValue
                        },
                    )
                    put("delivery_route_id", deliveryRouteId)
                }
                val rowId = insertWithOnConflict(
                    "events",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (rowId != -1L) insertedIds += message.id
            }

            pruned = pruneOldEvents(this, capturedAt)
        }
        if (pruned) checkpointWal(writable)

        _recentEvents.value = queryRecent(writable)
        insertedIds
    }

    suspend fun eventById(id: String): StoredMessageEvent? = withDatabaseLock {
        database.readableDatabase.query(
            "events",
            COLUMNS,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toEvent() else null
        }
    }

    suspend fun pendingIds(): List<String> = withDatabaseLock {
        database.readableDatabase.query(
            "events",
            arrayOf("id"),
            "delivery_state = ?",
            arrayOf(DeliveryState.PENDING.databaseValue),
            null,
            null,
            "captured_at ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    suspend fun markDelivered(id: String, deliveredAt: Long = System.currentTimeMillis()) =
        updateDelivery(id, checkpointAfter = true) { values ->
            values.put("delivery_state", DeliveryState.DELIVERED.databaseValue)
            values.put("delivered_at", deliveredAt)
            values.put("notification_key", "[redacted]")
            values.putNull("source_conversation_id")
            values.putNull("conversation")
            values.putNull("sender")
            values.putNull("message_text")
            values.putNull("attachment_mime_type")
            values.putNull("last_error")
        }

    suspend fun markFailure(id: String, error: String, terminal: Boolean) =
        updateDelivery(id) { values ->
            values.put(
                "delivery_state",
                if (terminal) {
                    DeliveryState.FAILED.databaseValue
                } else {
                    DeliveryState.PENDING.databaseValue
                },
            )
            values.put("last_error", error.take(MAX_ERROR_LENGTH))
        }

    suspend fun retryFailed(deliveryRouteId: String): List<String> = withDatabaseLock {
        val ids = database.readableDatabase.query(
            "events",
            arrayOf("id"),
            "delivery_state = ?",
            arrayOf(DeliveryState.FAILED.databaseValue),
            null,
            null,
            "captured_at ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        if (ids.isNotEmpty()) {
            val values = ContentValues().apply {
                put("delivery_state", DeliveryState.PENDING.databaseValue)
                put("delivery_route_id", deliveryRouteId)
                put("delivery_attempts", 0)
                putNull("delivered_at")
                putNull("last_error")
            }
            database.writableDatabase.update(
                "events",
                values,
                "delivery_state = ?",
                arrayOf(DeliveryState.FAILED.databaseValue),
            )
            _recentEvents.value = queryRecent(database.readableDatabase)
        }
        ids
    }

    suspend fun clear() = withDatabaseLock {
        val writable = database.writableDatabase
        writable.delete("events", null, null)
        checkpointWal(writable)
        _recentEvents.value = emptyList()
    }

    suspend fun pruneExpired(now: Long = System.currentTimeMillis()) = withDatabaseLock {
        val writable = database.writableDatabase
        if (pruneOldEvents(writable, now)) checkpointWal(writable)
        _recentEvents.value = queryRecent(writable)
    }

    private suspend fun updateDelivery(
        id: String,
        checkpointAfter: Boolean = false,
        updateValues: (ContentValues) -> Unit,
    ) = withDatabaseLock {
        val values = ContentValues().apply {
            put("delivery_attempts", currentAttempts(database.readableDatabase, id) + 1)
            updateValues(this)
        }
        database.writableDatabase.update("events", values, "id = ?", arrayOf(id))
        if (checkpointAfter) checkpointWal(database.writableDatabase)
        _recentEvents.value = queryRecent(database.readableDatabase)
    }

    private fun currentAttempts(database: SQLiteDatabase, id: String): Int =
        database.query(
            "events",
            arrayOf("delivery_attempts"),
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun pruneOldEvents(database: SQLiteDatabase, now: Long): Boolean {
        val expiredCount = database.delete(
            "events",
            "delivery_state != ? AND captured_at < ?",
            arrayOf(
                DeliveryState.PENDING.databaseValue,
                (now - RETENTION_MILLIS).toString(),
            ),
        )
        val overflowCount = database.compileStatement(
            """
            DELETE FROM events
            WHERE delivery_state != '${DeliveryState.PENDING.databaseValue}'
              AND id NOT IN (
                SELECT id FROM events
                WHERE delivery_state != '${DeliveryState.PENDING.databaseValue}'
                ORDER BY captured_at DESC
                LIMIT $MAX_RETAINED_NON_PENDING_EVENTS
            )
            """.trimIndent(),
        ).executeUpdateDelete()
        return expiredCount > 0 || overflowCount > 0
    }

    private fun checkpointWal(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            if (cursor.moveToFirst()) {
                // Running the query to completion performs the checkpoint.
            }
        }
    }

    private fun queryRecent(database: SQLiteDatabase): List<StoredMessageEvent> =
        database.query(
            "events",
            COLUMNS,
            null,
            null,
            null,
            null,
            "captured_at DESC",
            MAX_VISIBLE_EVENTS.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toEvent())
            }
        }

    private suspend fun <T> withDatabaseLock(block: () -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock { block() }
        }

    private fun Cursor.toEvent(): StoredMessageEvent = StoredMessageEvent(
        id = getString(getColumnIndexOrThrow("id")),
        sourcePackage = getString(getColumnIndexOrThrow("source_package")),
        sourceLabel = getString(getColumnIndexOrThrow("source_label")),
        notificationKey = getString(getColumnIndexOrThrow("notification_key")),
        sourceConversationId = getNullableString("source_conversation_id"),
        conversation = getNullableString("conversation"),
        sender = getNullableString("sender"),
        text = getNullableString("message_text"),
        sentAt = getNullableLong("sent_at"),
        capturedAt = getLong(getColumnIndexOrThrow("captured_at")),
        isGroupConversation = getNullableBoolean("is_group_conversation"),
        hasAttachment = getInt(getColumnIndexOrThrow("has_attachment")) == 1,
        attachmentMimeType = getNullableString("attachment_mime_type"),
        extractionMethod = ExtractionMethod.fromDatabase(
            getString(getColumnIndexOrThrow("extraction_method")),
        ),
        contentTruncated = getInt(getColumnIndexOrThrow("content_truncated")) == 1,
        deliveryState = DeliveryState.fromDatabase(
            getString(getColumnIndexOrThrow("delivery_state")),
        ),
        deliveryRouteId = getNullableString("delivery_route_id"),
        deliveryAttempts = getInt(getColumnIndexOrThrow("delivery_attempts")),
        deliveredAt = getNullableLong("delivered_at"),
        lastError = getNullableString("last_error"),
    )

    private fun Cursor.getNullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getNullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.getNullableBoolean(column: String): Boolean? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index) == 1
    }

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullableBoolean(key: String, value: Boolean?) {
        if (value == null) putNull(key) else put(key, value.asDatabaseInt())
    }

    private fun Boolean.asDatabaseInt(): Int = if (this) 1 else 0

    private companion object {
        const val MAX_RETAINED_NON_PENDING_EVENTS = 500
        const val MAX_VISIBLE_EVENTS = 100
        const val MAX_ERROR_LENGTH = 240
        const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L

        val COLUMNS = arrayOf(
            "id",
            "source_package",
            "source_label",
            "notification_key",
            "source_conversation_id",
            "conversation",
            "sender",
            "message_text",
            "sent_at",
            "captured_at",
            "is_group_conversation",
            "has_attachment",
            "attachment_mime_type",
            "extraction_method",
            "content_truncated",
            "delivery_state",
            "delivery_route_id",
            "delivery_attempts",
            "delivered_at",
            "last_error",
        )
    }
}
