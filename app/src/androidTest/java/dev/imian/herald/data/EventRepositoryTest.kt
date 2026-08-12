package dev.imian.herald.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.imian.herald.HeraldApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventRepositoryTest {
    private val repository: EventRepository
        get() = ApplicationProvider.getApplicationContext<HeraldApplication>()
            .container
            .eventRepository

    @Before
    fun clearBefore() = runBlocking {
        repository.clear()
    }

    @After
    fun clearAfter() = runBlocking {
        repository.clear()
    }

    @Test
    fun duplicateEventIdIsInsertedOnlyOnce() = runBlocking {
        val event = testMessage(id = "stable-id")

        val first = repository.record(
            messages = listOf(event),
            sourceLabel = "KakaoTalk",
            deliveryRouteId = "route-id",
            capturedAt = 100L,
        )
        val replay = repository.record(
            messages = listOf(event),
            sourceLabel = "KakaoTalk",
            deliveryRouteId = "route-id",
            capturedAt = 200L,
        )

        assertEquals(listOf("stable-id"), first)
        assertEquals(emptyList<String>(), replay)
        assertEquals(1, repository.recentEvents.value.size)
        assertEquals(100L, repository.recentEvents.value.single().capturedAt)
    }

    @Test
    fun successfulDeliveryRedactsSensitiveFieldsButRetainsIdempotencyId() = runBlocking {
        repository.record(
            messages = listOf(testMessage(id = "event-to-redact")),
            sourceLabel = "KakaoTalk",
            deliveryRouteId = "route-id",
            capturedAt = 100L,
        )

        repository.markDelivered("event-to-redact", deliveredAt = 300L)
        val stored = repository.eventById("event-to-redact")!!

        assertEquals("event-to-redact", stored.id)
        assertEquals(DeliveryState.DELIVERED, stored.deliveryState)
        assertEquals(300L, stored.deliveredAt)
        assertEquals("[redacted]", stored.notificationKey)
        assertNull(stored.sourceConversationId)
        assertNull(stored.conversation)
        assertNull(stored.sender)
        assertNull(stored.text)
        assertNull(stored.attachmentMimeType)
    }

    @Test
    fun retentionNeverDeletesPendingOutboxRows() = runBlocking {
        repository.record(
            messages = listOf(testMessage(id = "old-pending")),
            sourceLabel = "KakaoTalk",
            deliveryRouteId = "route-id",
            capturedAt = 100L,
        )
        repository.record(
            messages = listOf(testMessage(id = "old-local")),
            sourceLabel = "KakaoTalk",
            deliveryRouteId = null,
            capturedAt = 100L,
        )

        repository.pruneExpired(now = 8L * 24L * 60L * 60L * 1_000L)

        assertEquals(DeliveryState.PENDING, repository.eventById("old-pending")?.deliveryState)
        assertNull(repository.eventById("old-local"))
    }

    private fun testMessage(id: String) = NormalizedMessage(
        id = id,
        sourcePackage = "com.kakao.talk",
        notificationKey = "notification-key",
        sourceConversationId = "room-id",
        conversation = "가족방",
        sender = "민지",
        text = "민감한 메시지",
        sentAt = 50L,
        isGroupConversation = true,
        hasAttachment = false,
        attachmentMimeType = null,
        extractionMethod = ExtractionMethod.MESSAGING_STYLE,
        contentTruncated = false,
    )
}
