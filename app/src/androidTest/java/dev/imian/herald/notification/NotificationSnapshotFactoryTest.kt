package dev.imian.herald.notification

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.imian.herald.parser.MessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationSnapshotFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun frameworkMessagingStylePreservesConversationSendersOrderAndStableIds() {
        val first = statusBarNotification(
            messages = listOf(
                FixtureMessage(sender = "민지", text = "첫 번째", sentAt = 1_000L),
            ),
        )
        val updated = statusBarNotification(
            messages = listOf(
                FixtureMessage(sender = "민지", text = "첫 번째", sentAt = 1_000L),
                FixtureMessage(sender = "현우", text = "두 번째\n줄", sentAt = 2_000L),
            ),
        )

        val firstSnapshot = NotificationSnapshotFactory.create(first)
        val updatedSnapshot = NotificationSnapshotFactory.create(updated)
        val firstParsed = MessageParser.parse(firstSnapshot)
        val updatedParsed = MessageParser.parse(updatedSnapshot)

        assertFalse(firstSnapshot.isGroupSummary)
        assertEquals("주말 모임", updatedSnapshot.messagingStyle?.conversationTitle?.value)
        assertTrue(updatedSnapshot.messagingStyle?.isGroupConversation == true)
        assertEquals(listOf("민지", "현우"), updatedParsed.map { it.sender })
        assertEquals(listOf("첫 번째", "두 번째\n줄"), updatedParsed.map { it.text })
        assertEquals(firstParsed.single().id, updatedParsed.first().id)
    }

    private fun statusBarNotification(messages: List<FixtureMessage>): StatusBarNotification {
        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName("나").build(),
        )
            .setConversationTitle("주말 모임")
            .setGroupConversation(true)

        messages.forEach { message ->
            style.addMessage(
                NotificationCompat.MessagingStyle.Message(
                    message.text,
                    message.sentAt,
                    Person.Builder().setName(message.sender).build(),
                ),
            )
        }

        val notification: Notification = NotificationCompat.Builder(context, "test-channel")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setStyle(style)
            .build()

        return StatusBarNotification(
            context.packageName,
            context.packageName,
            42,
            "conversation",
            Process.myUid(),
            Process.myPid(),
            0,
            notification,
            Process.myUserHandle(),
            3_000L,
        )
    }

    private data class FixtureMessage(
        val sender: String,
        val text: String,
        val sentAt: Long,
    )
}
