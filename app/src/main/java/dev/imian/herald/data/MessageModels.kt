package dev.imian.herald.data

enum class DeliveryState(val databaseValue: String) {
    LOCAL("local"),
    PENDING("pending"),
    DELIVERED("delivered"),
    FAILED("failed");

    companion object {
        fun fromDatabase(value: String): DeliveryState =
            entries.firstOrNull { it.databaseValue == value } ?: FAILED
    }
}

enum class ExtractionMethod(val wireValue: String) {
    MESSAGING_STYLE("messaging_style"),
    TEXT("text"),
    BIG_TEXT("big_text"),
    TEXT_LINES("text_lines");

    companion object {
        fun fromDatabase(value: String): ExtractionMethod =
            entries.firstOrNull { it.wireValue == value } ?: TEXT
    }
}

data class NormalizedMessage(
    val id: String,
    val sourcePackage: String,
    val notificationKey: String,
    val sourceConversationId: String?,
    val conversation: String?,
    val sender: String?,
    val text: String?,
    val sentAt: Long?,
    val isGroupConversation: Boolean?,
    val hasAttachment: Boolean,
    val attachmentMimeType: String?,
    val extractionMethod: ExtractionMethod,
    val contentTruncated: Boolean,
)

data class StoredMessageEvent(
    val id: String,
    val sourcePackage: String,
    val sourceLabel: String,
    val notificationKey: String,
    val sourceConversationId: String?,
    val conversation: String?,
    val sender: String?,
    val text: String?,
    val sentAt: Long?,
    val capturedAt: Long,
    val isGroupConversation: Boolean?,
    val hasAttachment: Boolean,
    val attachmentMimeType: String?,
    val extractionMethod: ExtractionMethod,
    val contentTruncated: Boolean,
    val deliveryState: DeliveryState,
    val deliveryRouteId: String?,
    val deliveryAttempts: Int,
    val deliveredAt: Long?,
    val lastError: String?,
)
