package dev.imian.herald.settings

import java.net.URI
import java.net.InetAddress

object WebhookValidator {
    data class Result(
        val normalizedUrl: String?,
        val error: String?,
    ) {
        val isValid: Boolean get() = error == null
        val isCleartext: Boolean
            get() = normalizedUrl
                ?.substringBefore(':')
                ?.equals("http", ignoreCase = true) == true
    }

    fun validate(rawUrl: String, allowInsecureLocalHttp: Boolean): Result {
        val value = rawUrl.trim()
        if (value.isEmpty()) return Result(normalizedUrl = null, error = null)
        if (value.length > MAX_URL_LENGTH) {
            return Result(null, "웹훅 주소가 너무 깁니다.")
        }

        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return Result(null, "올바른 웹훅 주소를 입력해 주세요.")
        }

        if (uri.isOpaque || uri.host.isNullOrBlank()) {
            return Result(null, "웹훅 주소에는 호스트가 필요합니다.")
        }
        if (uri.rawUserInfo != null) {
            return Result(null, "웹훅 주소에 사용자 정보는 넣을 수 없습니다.")
        }
        if (uri.rawFragment != null) {
            return Result(null, "웹훅 주소의 #fragment는 제거해 주세요.")
        }

        return when (uri.scheme?.lowercase()) {
            "https" -> Result(uri.normalizedWithLowercaseScheme(), null)
            "http" -> {
                if (!allowInsecureLocalHttp) {
                    Result(null, "HTTP는 로컬 네트워크 허용을 켠 경우에만 사용할 수 있습니다.")
                } else if (!isLocalHost(uri.host)) {
                    Result(null, "암호화되지 않은 HTTP는 localhost 또는 사설 IP만 허용됩니다.")
                } else {
                    Result(uri.normalizedWithLowercaseScheme(), null)
                }
            }
            else -> Result(null, "웹훅은 HTTPS 주소여야 합니다.")
        }
    }

    private fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.lowercase().removePrefix("[").removeSuffix("]")
        if (host == "localhost") {
            return true
        }
        if (':' in host && IPV6_LITERAL.matches(host)) {
            val address = try {
                InetAddress.getByName(host)
            } catch (_: Exception) {
                return false
            }
            val firstByte = address.address.firstOrNull()?.toInt()?.and(0xff) ?: return false
            return address.isLoopbackAddress ||
                address.isSiteLocalAddress ||
                address.isLinkLocalAddress ||
                firstByte and 0xfe == 0xfc
        }

        val octets = host.split('.')
        if (octets.size != 4) return false
        val numbers = octets.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false

        return numbers[0] == 10 ||
            numbers[0] == 127 ||
            (numbers[0] == 172 && numbers[1] in 16..31) ||
            (numbers[0] == 192 && numbers[1] == 168)
    }

    private fun URI.normalizedWithLowercaseScheme(): String {
        val normalized = normalize().toASCIIString()
        val separator = normalized.indexOf(':')
        return scheme.lowercase() + normalized.substring(separator)
    }

    private const val MAX_URL_LENGTH = 2_048
    private val IPV6_LITERAL = Regex("^[0-9a-f:.]+$")
}
