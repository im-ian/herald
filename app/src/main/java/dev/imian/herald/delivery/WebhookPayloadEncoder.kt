package dev.imian.herald.delivery

import dev.imian.herald.data.StoredMessageEvent
import org.json.JSONObject

internal object WebhookPayloadEncoder {
    fun encode(event: StoredMessageEvent): ByteArray {
        val source = JSONObject()
            .put("package", event.sourcePackage)
            .put("label", event.sourceLabel)
            .put("notificationKey", event.notificationKey)
            .putNullable("conversationId", event.sourceConversationId)

        val message = JSONObject()
            .putNullable("conversation", event.conversation)
            .putNullable("sender", event.sender)
            .putNullable("text", event.text)
            .putNullable("sentAt", event.sentAt)
            .putNullable("isGroupConversation", event.isGroupConversation)
            .put("hasAttachment", event.hasAttachment)
            .putNullable("attachmentMimeType", event.attachmentMimeType)

        return JSONObject()
            .put("schemaVersion", 1)
            .put("type", "message.received")
            .put("id", event.id)
            .put("capturedAt", event.capturedAt)
            .put("source", source)
            .put("message", message)
            .put("extractionMethod", event.extractionMethod.wireValue)
            .put("contentTruncated", event.contentTruncated)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)
}
