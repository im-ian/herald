package dev.imian.herald.delivery

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.imian.herald.HeraldApplication
import dev.imian.herald.data.DeliveryState
import dev.imian.herald.data.ExtractionMethod
import dev.imian.herald.data.NormalizedMessage
import dev.imian.herald.settings.SettingsInput
import dev.imian.herald.settings.ReadSettingsResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeliveryPipelineEndToEndTest {
    private val application: HeraldApplication = ApplicationProvider.getApplicationContext()
    private val container get() = application.container

    @After
    fun cleanUp() {
        runBlocking {
            container.settingsStore.save(
                SettingsInput(
                    webhookUrl = "",
                    bearerToken = "",
                    allowedPackages = "com.kakao.talk",
                    allowInsecureLocalHttp = false,
                ),
            )
            container.eventRepository.clear()
        }
    }

    @Test
    fun pendingEventIsPostedWithIdempotencyKeyAndThenRedacted() {
        runBlocking {
            container.eventRepository.clear()
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10_000
                val captured = CompletableFuture<CapturedRequest>()
                thread(name = "herald-test-webhook", isDaemon = true) {
                    try {
                        captured.complete(serveOneRequest(server))
                    } catch (error: Throwable) {
                        captured.completeExceptionally(error)
                    }
                }

                val saved = container.settingsStore.save(
                    SettingsInput(
                        webhookUrl = "http://127.0.0.1:${server.localPort}/hook",
                        bearerToken = "",
                        allowedPackages = "com.kakao.talk",
                        allowInsecureLocalHttp = true,
                    ),
                )
                assertTrue(saved is dev.imian.herald.settings.SaveSettingsResult.Success)

                val ids = container.eventRepository.record(
                    messages = listOf(testMessage()),
                    sourceLabel = "KakaoTalk",
                    deliveryRouteId = (
                        container.settingsStore.read() as ReadSettingsResult.Success
                    ).settings.deliveryRouteId,
                    capturedAt = 100L,
                )
                container.deliveryScheduler.schedule(ids)

                assertTrue(awaitDelivery())
                val request = captured.get(2, TimeUnit.SECONDS)
                assertEquals("POST /hook HTTP/1.1", request.requestLine)
                assertEquals("delivery-e2e-id", request.headers["idempotency-key"])
                assertNull(request.headers["authorization"])
                assertTrue(request.body.contains("\"id\":\"delivery-e2e-id\""))
                assertTrue(request.body.contains("\"text\":\"hello\""))

                val delivered = container.eventRepository.eventById("delivery-e2e-id")!!
                assertEquals(DeliveryState.DELIVERED, delivered.deliveryState)
                assertNull(delivered.sender)
                assertNull(delivered.text)
            }
        }
    }

    private suspend fun awaitDelivery(timeoutMillis: Long = 10_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (
                container.eventRepository.eventById("delivery-e2e-id")?.deliveryState ==
                DeliveryState.DELIVERED
            ) {
                return true
            }
            delay(50L)
        }
        return false
    }

    private fun serveOneRequest(server: ServerSocket): CapturedRequest =
        server.accept().use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine()
            val headers = buildMap {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        put(
                            line.substring(0, separator).lowercase(Locale.US),
                            line.substring(separator + 1).trim(),
                        )
                    }
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = CharArray(contentLength)
            var offset = 0
            while (offset < body.size) {
                val read = reader.read(body, offset, body.size - offset)
                if (read < 0) break
                offset += read
            }
            socket.getOutputStream().use { output ->
                output.write(
                    "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII),
                )
                output.flush()
            }
            CapturedRequest(requestLine, headers, body.concatToString(0, offset))
        }

    private fun testMessage() = NormalizedMessage(
        id = "delivery-e2e-id",
        sourcePackage = "com.kakao.talk",
        notificationKey = "notification-key",
        sourceConversationId = "conversation-id",
        conversation = "room",
        sender = "sender",
        text = "hello",
        sentAt = 50L,
        isGroupConversation = false,
        hasAttachment = false,
        attachmentMimeType = null,
        extractionMethod = ExtractionMethod.MESSAGING_STYLE,
        contentTruncated = false,
    )

    private data class CapturedRequest(
        val requestLine: String,
        val headers: Map<String, String>,
        val body: String,
    )
}
