package dev.imian.herald.parser

import dev.imian.herald.data.ExtractionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParserTest {
    @Test
    fun `direct MessagingStyle message keeps explicit fields`() {
        val result = MessageParser.parse(
            snapshot(
                title = "알림 제목",
                messaging = messaging(
                    conversation = "지원 채팅",
                    messages = listOf(raw(sender = "민지", text = "안녕하세요", sentAt = 1_000L)),
                ),
            ),
        )

        assertEquals(1, result.size)
        assertEquals("지원 채팅", result.single().conversation)
        assertEquals("민지", result.single().sender)
        assertEquals("안녕하세요", result.single().text)
        assertEquals(1_000L, result.single().sentAt)
        assertEquals(false, result.single().isGroupConversation)
        assertEquals(ExtractionMethod.MESSAGING_STYLE, result.single().extractionMethod)
    }

    @Test
    fun `group message uses conversation title and does not invent missing sender`() {
        val result = MessageParser.parse(
            snapshot(
                title = "프로젝트 방",
                messaging = messaging(
                    conversation = "프로젝트 방",
                    isGroup = true,
                    messages = listOf(raw(sender = null, text = "회의 시작")),
                ),
            ),
        ).single()

        assertEquals("프로젝트 방", result.conversation)
        assertNull(result.sender)
        assertEquals(true, result.isGroupConversation)
    }

    @Test
    fun `direct message may use title when sender is absent`() {
        val result = MessageParser.parse(
            snapshot(
                title = "민지",
                messaging = messaging(messages = listOf(raw(sender = null, text = "도착했어"))),
            ),
        ).single()

        assertEquals("민지", result.sender)
    }

    @Test
    fun `multiple MessagingStyle messages preserve source order`() {
        val result = MessageParser.parse(
            snapshot(
                messaging = messaging(
                    messages = listOf(
                        raw("A", "첫 번째", 1L),
                        raw("B", "두 번째", 2L),
                        raw("A", "세 번째", 3L),
                    ),
                ),
            ),
        )

        assertEquals(listOf("첫 번째", "두 번째", "세 번째"), result.map { it.text })
    }

    @Test
    fun `MessagingStyle wins over duplicated fallback extras`() {
        val result = MessageParser.parse(
            snapshot(
                text = "fallback duplicate",
                bigText = "fallback expanded",
                messaging = messaging(messages = listOf(raw("A", "structured", 1L))),
            ),
        )

        assertEquals(listOf("structured"), result.map { it.text })
    }

    @Test
    fun `empty messages are skipped while whitespace and newlines remain exact`() {
        val result = MessageParser.parse(
            snapshot(
                messaging = messaging(
                    messages = listOf(
                        raw("A", null),
                        raw("A", ""),
                        raw("A", "  \n메시지🙂\n  "),
                    ),
                ),
            ),
        )

        assertEquals(listOf("  \n메시지🙂\n  "), result.map { it.text })
    }

    @Test
    fun `media-only message becomes an attachment event without URI`() {
        val result = MessageParser.parse(
            snapshot(
                messaging = messaging(
                    messages = listOf(raw(text = null, attachmentMimeType = "image/jpeg")),
                ),
            ),
        ).single()

        assertNull(result.text)
        assertTrue(result.hasAttachment)
        assertEquals("image/jpeg", result.attachmentMimeType)
    }

    @Test
    fun `plain text fallback emits one event`() {
        val result = MessageParser.parse(snapshot(title = "민지", text = "일반 알림")).single()

        assertEquals("민지", result.conversation)
        assertNull(result.sender)
        assertEquals("일반 알림", result.text)
        assertNull(result.sentAt)
        assertNull(result.isGroupConversation)
        assertEquals(ExtractionMethod.TEXT, result.extractionMethod)
    }

    @Test
    fun `big text is used only when text is absent`() {
        val withText = MessageParser.parse(
            snapshot(text = "짧은 본문", bigText = "긴 본문"),
        ).single()
        val withoutText = MessageParser.parse(
            snapshot(text = null, bigText = "긴 본문"),
        ).single()

        assertEquals("짧은 본문", withText.text)
        assertEquals(ExtractionMethod.TEXT, withText.extractionMethod)
        assertEquals("긴 본문", withoutText.text)
        assertEquals(ExtractionMethod.BIG_TEXT, withoutText.extractionMethod)
    }

    @Test
    fun `last non-empty text line is the final fallback`() {
        val result = MessageParser.parse(
            snapshot(textLines = listOf("이전", "", "최신")),
        ).single()

        assertEquals("최신", result.text)
        assertEquals(ExtractionMethod.TEXT_LINES, result.extractionMethod)
    }

    @Test
    fun `group summary and malformed identity are ignored`() {
        assertTrue(MessageParser.parse(snapshot(isGroupSummary = true, text = "summary")).isEmpty())
        assertTrue(MessageParser.parse(snapshot(sourcePackage = "", text = "message")).isEmpty())
        assertTrue(MessageParser.parse(snapshot(notificationKey = "", text = "message")).isEmpty())
    }

    @Test
    fun `truncation provenance is retained`() {
        val result = MessageParser.parse(
            snapshot(
                title = bounded("room", truncated = true),
                messaging = MessagingSnapshot(
                    conversationTitle = null,
                    isGroupConversation = false,
                    messages = listOf(raw("A", "message")),
                    messagesTruncated = true,
                ),
            ),
        ).single()

        assertTrue(result.contentTruncated)
    }

    @Test
    fun `repeated callback produces stable IDs`() {
        val input = snapshot(
            messaging = messaging(messages = listOf(raw("A", "same", 100L))),
        )

        assertEquals(MessageParser.parse(input).single().id, MessageParser.parse(input).single().id)
    }

    @Test
    fun `history update adds only one unseen ID`() {
        val first = MessageParser.parse(
            snapshot(messaging = messaging(messages = listOf(raw("A", "one", 1L)))),
        )
        val updated = MessageParser.parse(
            snapshot(
                messaging = messaging(
                    messages = listOf(raw("A", "one", 1L), raw("B", "two", 2L)),
                ),
            ),
        )

        val unseen = updated.filterNot { candidate -> first.any { it.id == candidate.id } }
        assertEquals(listOf("two"), unseen.map { it.text })
    }

    @Test
    fun `identical messages in one snapshot use occurrence ordinal`() {
        val parsed = MessageParser.parse(
            snapshot(
                messaging = messaging(
                    messages = listOf(raw("A", "same", null), raw("A", "same", null)),
                ),
            ),
        )

        assertEquals(2, parsed.size)
        assertNotEquals(parsed[0].id, parsed[1].id)
        assertEquals(parsed.map { it.id }, MessageParser.parse(
            snapshot(
                messaging = messaging(
                    messages = listOf(raw("A", "same", null), raw("A", "same", null)),
                ),
            ),
        ).map { it.id })
    }

    @Test
    fun `same content in another package or conversation is distinct`() {
        val base = MessageParser.parse(
            snapshot(messaging = messaging(messages = listOf(raw("A", "same", 1L)))),
        ).single()
        val otherPackage = MessageParser.parse(
            snapshot(
                sourcePackage = "org.telegram.messenger",
                messaging = messaging(messages = listOf(raw("A", "same", 1L))),
            ),
        ).single()
        val otherConversation = MessageParser.parse(
            snapshot(
                sourceConversationId = "another-shortcut",
                messaging = messaging(messages = listOf(raw("A", "same", 1L))),
            ),
        ).single()

        assertNotEquals(base.id, otherPackage.id)
        assertNotEquals(base.id, otherConversation.id)
    }

    @Test
    fun `stable conversation id survives notification key changes`() {
        val before = MessageParser.parse(
            snapshot(
                notificationKey = "old-key",
                sourceConversationId = "room-1",
                messaging = messaging(messages = listOf(raw("A", "same", 1L))),
            ),
        ).single()
        val after = MessageParser.parse(
            snapshot(
                notificationKey = "new-key",
                sourceConversationId = "room-1",
                messaging = messaging(messages = listOf(raw("A", "same", 1L))),
            ),
        ).single()

        assertEquals(before.id, after.id)
    }

    @Test
    fun `fallback post time distinguishes a later identical notification`() {
        val first = MessageParser.parse(snapshot(postedAt = 1L, text = "same")).single()
        val replay = MessageParser.parse(snapshot(postedAt = 1L, text = "same")).single()
        val later = MessageParser.parse(snapshot(postedAt = 2L, text = "same")).single()

        assertEquals(first.id, replay.id)
        assertNotEquals(first.id, later.id)
    }

    @Test
    fun `conversation label changes do not replay structured message`() {
        val before = MessageParser.parse(
            snapshot(
                title = "old",
                messaging = messaging(conversation = "old", messages = listOf(raw("A", "same", 1L))),
            ),
        ).single()
        val after = MessageParser.parse(
            snapshot(
                title = "new",
                messaging = messaging(conversation = "new", messages = listOf(raw("A", "same", 1L))),
            ),
        ).single()

        assertEquals(before.id, after.id)
        assertFalse(before.conversation == after.conversation)
    }

    private fun snapshot(
        sourcePackage: String = "com.kakao.talk",
        notificationKey: String = "kakao-key",
        sourceConversationId: String? = "shortcut-1",
        postedAt: Long = 500L,
        isGroupSummary: Boolean = false,
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        textLines: List<String> = emptyList(),
        messaging: MessagingSnapshot? = null,
    ) = NotificationSnapshot(
        sourcePackage = sourcePackage,
        notificationKey = notificationKey,
        sourceConversationId = sourceConversationId,
        postedAt = postedAt,
        isGroupSummary = isGroupSummary,
        title = title?.let(::bounded),
        text = text?.let(::bounded),
        bigText = bigText?.let(::bounded),
        textLines = textLines.map(::bounded),
        messagingStyle = messaging,
    )

    private fun snapshot(
        sourcePackage: String = "com.kakao.talk",
        notificationKey: String = "kakao-key",
        sourceConversationId: String? = "shortcut-1",
        postedAt: Long = 500L,
        isGroupSummary: Boolean = false,
        title: BoundedString,
        messaging: MessagingSnapshot,
    ) = NotificationSnapshot(
        sourcePackage = sourcePackage,
        notificationKey = notificationKey,
        sourceConversationId = sourceConversationId,
        postedAt = postedAt,
        isGroupSummary = isGroupSummary,
        title = title,
        text = null,
        bigText = null,
        textLines = emptyList(),
        messagingStyle = messaging,
    )

    private fun messaging(
        conversation: String? = null,
        isGroup: Boolean = false,
        messages: List<RawMessage>,
    ) = MessagingSnapshot(
        conversationTitle = conversation?.let(::bounded),
        isGroupConversation = isGroup,
        messages = messages,
    )

    private fun raw(
        sender: String? = null,
        text: String? = null,
        sentAt: Long? = null,
        attachmentMimeType: String? = null,
    ) = RawMessage(
        sender = sender?.let(::bounded),
        text = text?.let(::bounded),
        sentAt = sentAt,
        attachmentMimeType = attachmentMimeType?.let(::bounded),
    )

    private fun bounded(value: String, truncated: Boolean = false) =
        BoundedString(value, truncated)
}
