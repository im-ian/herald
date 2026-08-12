package dev.imian.herald.parser

data class NotificationSnapshot(
    val sourcePackage: String,
    val notificationKey: String,
    val sourceConversationId: String?,
    val postedAt: Long,
    val isGroupSummary: Boolean,
    val title: BoundedString?,
    val text: BoundedString?,
    val bigText: BoundedString?,
    val textLines: List<BoundedString>,
    val messagingStyle: MessagingSnapshot?,
)

data class MessagingSnapshot(
    val conversationTitle: BoundedString?,
    val isGroupConversation: Boolean,
    val messages: List<RawMessage>,
    val messagesTruncated: Boolean = false,
)

data class RawMessage(
    val sender: BoundedString?,
    val text: BoundedString?,
    val sentAt: Long?,
    val attachmentMimeType: BoundedString?,
)

data class BoundedString(
    val value: String,
    val wasTruncated: Boolean = false,
)
