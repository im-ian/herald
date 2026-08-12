package dev.imian.herald.parser

import dev.imian.herald.data.ExtractionMethod
import dev.imian.herald.data.NormalizedMessage

object MessageParser {
    fun parse(snapshot: NotificationSnapshot): List<NormalizedMessage> {
        if (snapshot.isGroupSummary) return emptyList()
        if (snapshot.sourcePackage.isBlank() || snapshot.notificationKey.isBlank()) {
            return emptyList()
        }

        val messaging = snapshot.messagingStyle
        if (messaging != null && messaging.messages.isNotEmpty()) {
            return parseMessagingStyle(snapshot, messaging)
        }
        return parseFallback(snapshot)
    }

    private fun parseMessagingStyle(
        snapshot: NotificationSnapshot,
        messaging: MessagingSnapshot,
    ): List<NormalizedMessage> {
        val conversation = messaging.conversationTitle ?: snapshot.title
        val conversationScope = snapshot.sourceConversationId ?: snapshot.notificationKey
        val occurrences = mutableMapOf<String, Int>()

        return messaging.messages.mapNotNull { raw ->
            val text = raw.text?.takeUnless { it.value.isEmpty() }
            val attachment = raw.attachmentMimeType?.takeUnless { it.value.isEmpty() }
            if (text == null && attachment == null) return@mapNotNull null

            val sender = raw.sender ?: if (!messaging.isGroupConversation) snapshot.title else null
            val identity = listOf(
                sender?.value,
                text?.value,
                raw.sentAt?.toString(),
                attachment?.value,
            ).joinToString(separator = "\u0000") { it.orEmpty() }
            val occurrence = occurrences.getOrDefault(identity, 0)
            occurrences[identity] = occurrence + 1

            NormalizedMessage(
                id = EventIdFactory.messaging(
                    sourcePackage = snapshot.sourcePackage,
                    notificationKey = conversationScope,
                    sender = sender?.value,
                    text = text?.value,
                    sentAt = raw.sentAt,
                    attachmentMimeType = attachment?.value,
                    occurrence = occurrence,
                ),
                sourcePackage = snapshot.sourcePackage,
                notificationKey = snapshot.notificationKey,
                sourceConversationId = snapshot.sourceConversationId,
                conversation = conversation?.value,
                sender = sender?.value,
                text = text?.value,
                sentAt = raw.sentAt,
                isGroupConversation = messaging.isGroupConversation,
                hasAttachment = attachment != null,
                attachmentMimeType = attachment?.value,
                extractionMethod = ExtractionMethod.MESSAGING_STYLE,
                contentTruncated = messaging.messagesTruncated ||
                    listOfNotNull(conversation, sender, text, attachment)
                        .any(BoundedString::wasTruncated),
            )
        }
    }

    private fun parseFallback(snapshot: NotificationSnapshot): List<NormalizedMessage> {
        val (content, method) = when {
            snapshot.text != null && snapshot.text.value.isNotEmpty() -> {
                snapshot.text to ExtractionMethod.TEXT
            }
            snapshot.bigText != null && snapshot.bigText.value.isNotEmpty() -> {
                snapshot.bigText to ExtractionMethod.BIG_TEXT
            }
            else -> {
                val line = snapshot.textLines.lastOrNull { it.value.isNotEmpty() }
                    ?: return emptyList()
                line to ExtractionMethod.TEXT_LINES
            }
        }
        val title = snapshot.title

        return listOf(
            NormalizedMessage(
                id = EventIdFactory.fallback(
                    sourcePackage = snapshot.sourcePackage,
                    notificationKey = snapshot.sourceConversationId ?: snapshot.notificationKey,
                    postedAt = snapshot.postedAt,
                    title = title?.value,
                    text = content.value,
                    method = method.wireValue,
                ),
                sourcePackage = snapshot.sourcePackage,
                notificationKey = snapshot.notificationKey,
                sourceConversationId = snapshot.sourceConversationId,
                conversation = title?.value,
                sender = null,
                text = content.value,
                sentAt = null,
                isGroupConversation = null,
                hasAttachment = false,
                attachmentMimeType = null,
                extractionMethod = method,
                contentTruncated = content.wasTruncated || title?.wasTruncated == true,
            ),
        )
    }
}
