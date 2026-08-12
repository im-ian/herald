package dev.imian.herald.delivery

import dev.imian.herald.data.DeliveryState
import dev.imian.herald.data.ExtractionMethod
import dev.imian.herald.data.StoredMessageEvent
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookClientTest {
    @Test
    fun `client refuses bearer credentials on mixed case cleartext scheme`() {
        val result = WebhookClient().deliver(
            endpoint = "HtTp://127.0.0.1:1/hook",
            bearerToken = "must-not-travel-in-cleartext",
            event = event(),
        )

        assertTrue(result is DeliveryResult.TerminalFailure)
    }

    private fun event() = StoredMessageEvent(
        id = "event-id",
        sourcePackage = "com.kakao.talk",
        sourceLabel = "KakaoTalk",
        notificationKey = "notification-key",
        sourceConversationId = null,
        conversation = "room",
        sender = "sender",
        text = "message",
        sentAt = null,
        capturedAt = 1L,
        isGroupConversation = null,
        hasAttachment = false,
        attachmentMimeType = null,
        extractionMethod = ExtractionMethod.TEXT,
        contentTruncated = false,
        deliveryState = DeliveryState.PENDING,
        deliveryRouteId = null,
        deliveryAttempts = 0,
        deliveredAt = null,
        lastError = null,
    )
}
