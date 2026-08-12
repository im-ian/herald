package dev.imian.herald.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dev.imian.herald.parser.BoundedString
import dev.imian.herald.parser.MessagingSnapshot
import dev.imian.herald.parser.NotificationSnapshot
import dev.imian.herald.parser.RawMessage

internal object NotificationSnapshotFactory {
    fun create(statusBarNotification: StatusBarNotification): NotificationSnapshot {
        val notification = statusBarNotification.notification
        val extras = notification.extras
        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
        val allMessages = style?.messages.orEmpty()
        val boundedMessages = allMessages.takeLast(MAX_MESSAGES).map { message ->
            RawMessage(
                sender = message.person?.name.toBoundedString(MAX_PERSON_CHARS),
                text = message.text.toBoundedString(MAX_MESSAGE_CHARS),
                sentAt = message.timestamp.takeIf { it > 0L },
                attachmentMimeType = if (message.dataUri != null) {
                    message.dataMimeType.toBoundedString(MAX_MIME_TYPE_CHARS)
                } else {
                    null
                },
            )
        }

        return NotificationSnapshot(
            sourcePackage = statusBarNotification.packageName,
            notificationKey = statusBarNotification.key,
            sourceConversationId = notification.shortcutId
                .toBoundedString(MAX_CONVERSATION_ID_CHARS)
                ?.value,
            postedAt = statusBarNotification.postTime,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)
                .toBoundedString(MAX_PERSON_CHARS),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)
                .toBoundedString(MAX_MESSAGE_CHARS),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                .toBoundedString(MAX_MESSAGE_CHARS),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                .orEmpty()
                .takeLast(MAX_TEXT_LINES)
                .mapNotNull { it.toBoundedString(MAX_MESSAGE_CHARS) },
            messagingStyle = style?.let {
                MessagingSnapshot(
                    conversationTitle = it.conversationTitle
                        .toBoundedString(MAX_CONVERSATION_CHARS),
                    isGroupConversation = it.isGroupConversation,
                    messages = boundedMessages,
                    messagesTruncated = allMessages.size > MAX_MESSAGES,
                )
            },
        )
    }

    private fun CharSequence?.toBoundedString(maxChars: Int): BoundedString? {
        if (this == null) return null
        val truncated = length > maxChars
        val value = if (truncated) subSequence(0, maxChars).toString() else toString()
        return BoundedString(value = value, wasTruncated = truncated)
    }

    private const val MAX_MESSAGES = 25
    private const val MAX_TEXT_LINES = 25
    private const val MAX_MESSAGE_CHARS = 16_384
    private const val MAX_PERSON_CHARS = 512
    private const val MAX_CONVERSATION_CHARS = 1_024
    private const val MAX_CONVERSATION_ID_CHARS = 512
    private const val MAX_MIME_TYPE_CHARS = 256
}
