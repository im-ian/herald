package dev.imian.herald.delivery

import dev.imian.herald.BuildConfig
import dev.imian.herald.data.StoredMessageEvent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal sealed interface DeliveryResult {
    data object Success : DeliveryResult
    data class RetryableFailure(val reason: String) : DeliveryResult
    data class TerminalFailure(val reason: String) : DeliveryResult
}

internal class WebhookClient {
    fun deliver(
        endpoint: String,
        bearerToken: String,
        event: StoredMessageEvent,
    ): DeliveryResult {
        val url = try {
            URL(endpoint)
        } catch (_: Exception) {
            return DeliveryResult.TerminalFailure("웹훅 주소를 열 수 없습니다.")
        }
        if (bearerToken.isNotEmpty() && !url.protocol.equals("https", ignoreCase = true)) {
            return DeliveryResult.TerminalFailure(
                "암호화되지 않은 연결에는 인증정보를 보낼 수 없습니다.",
            )
        }
        val connection = try {
            url.openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return DeliveryResult.TerminalFailure("웹훅 주소를 열 수 없습니다.")
        }

        return try {
            val body = WebhookPayloadEncoder.encode(event)
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Idempotency-Key", event.id)
            connection.setRequestProperty("User-Agent", "Herald/${BuildConfig.VERSION_NAME}")
            if (bearerToken.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            connection.outputStream.use { it.write(body) }

            when (val status = connection.responseCode) {
                in 200..299 -> DeliveryResult.Success
                408, 425, 429 -> DeliveryResult.RetryableFailure("HTTP $status")
                in 500..599 -> DeliveryResult.RetryableFailure("HTTP $status")
                else -> DeliveryResult.TerminalFailure("HTTP $status")
            }
        } catch (_: IOException) {
            DeliveryResult.RetryableFailure("네트워크 연결 실패")
        } catch (_: RuntimeException) {
            DeliveryResult.TerminalFailure("요청 생성 실패")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000
    }
}
