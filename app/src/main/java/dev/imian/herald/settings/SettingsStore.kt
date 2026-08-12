package dev.imian.herald.settings

import android.content.Context
import java.security.MessageDigest

data class HeraldSettings(
    val webhookUrl: String,
    val bearerToken: String,
    val allowedPackages: Set<String>,
    val allowInsecureLocalHttp: Boolean,
) {
    val deliveryEndpoint: String? get() = webhookUrl.ifBlank { null }
    val deliveryRouteId: String?
        get() = deliveryEndpoint?.let { endpoint ->
            WebhookRouteId.from(endpoint, bearerToken)
        }
}

data class SettingsInput(
    val webhookUrl: String,
    /** null keeps the stored token; empty explicitly clears it. */
    val bearerToken: String?,
    val allowedPackages: String,
    val allowInsecureLocalHttp: Boolean,
)

sealed interface ReadSettingsResult {
    data class Success(val settings: HeraldSettings) : ReadSettingsResult
    data class Error(val message: String, val allowedPackages: Set<String>) : ReadSettingsResult
}

sealed interface SaveSettingsResult {
    data class Success(val settings: HeraldSettings) : SaveSettingsResult
    data class Error(val message: String) : SaveSettingsResult
}

class SettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val secretStore = SecretStore(context)

    @Synchronized
    fun read(): ReadSettingsResult {
        val packages = readAllowedPackages()
        return when (val secret = secretStore.readRouting()) {
            SecretRoutingRead.Missing -> ReadSettingsResult.Success(
                HeraldSettings(
                    webhookUrl = "",
                    bearerToken = "",
                    allowedPackages = packages,
                    allowInsecureLocalHttp = false,
                ),
            )
            SecretRoutingRead.Failure -> ReadSettingsResult.Error(
                message = "보안 설정을 읽을 수 없습니다. 웹훅과 토큰을 다시 저장해 주세요.",
                allowedPackages = packages,
            )
            is SecretRoutingRead.Success -> {
                val routing = secret.routing
                val validation = WebhookValidator.validate(
                    routing.webhookUrl,
                    routing.allowInsecureLocalHttp,
                )
                if (!validation.isValid || validation.normalizedUrl.orEmpty() != routing.webhookUrl) {
                    ReadSettingsResult.Error(
                        message = "저장된 웹훅 설정이 올바르지 않습니다. 다시 저장해 주세요.",
                        allowedPackages = packages,
                    )
                } else {
                    ReadSettingsResult.Success(
                        HeraldSettings(
                            webhookUrl = routing.webhookUrl,
                            bearerToken = routing.bearerToken,
                            allowedPackages = packages,
                            allowInsecureLocalHttp = routing.allowInsecureLocalHttp,
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun save(input: SettingsInput): SaveSettingsResult {
        val enteredToken = input.bearerToken
        if (enteredToken != null && enteredToken.any { it == '\r' || it == '\n' }) {
            return SaveSettingsResult.Error("토큰에는 줄바꿈을 넣을 수 없습니다.")
        }
        if (enteredToken != null && enteredToken.length > MAX_TOKEN_LENGTH) {
            return SaveSettingsResult.Error("토큰이 너무 깁니다.")
        }

        val packages = parsePackages(input.allowedPackages)
        if (packages.isEmpty()) {
            return SaveSettingsResult.Error("수집할 앱 패키지를 하나 이상 입력해 주세요.")
        }
        val invalidPackage = packages.firstOrNull { !PACKAGE_NAME.matches(it) }
        if (invalidPackage != null) {
            return SaveSettingsResult.Error("올바르지 않은 패키지 이름: $invalidPackage")
        }

        val endpoint = WebhookValidator.validate(
            input.webhookUrl,
            input.allowInsecureLocalHttp,
        )
        if (!endpoint.isValid) {
            return SaveSettingsResult.Error(endpoint.error.orEmpty())
        }
        val normalizedUrl = endpoint.normalizedUrl.orEmpty()

        val existingRouting = when (val existing = secretStore.readRouting()) {
            SecretRoutingRead.Missing -> null
            SecretRoutingRead.Failure -> {
                if (enteredToken == null) {
                    return SaveSettingsResult.Error(
                        "기존 보안 설정을 읽을 수 없습니다. 토큰을 다시 입력하거나 삭제해 주세요.",
                    )
                }
                null
            }
            is SecretRoutingRead.Success -> existing.routing
        }

        if (
            existingRouting?.webhookUrl != null &&
            existingRouting.webhookUrl != normalizedUrl &&
            existingRouting.bearerToken.isNotEmpty() &&
            enteredToken == null
        ) {
            return SaveSettingsResult.Error(
                "웹훅 주소가 바뀌면 토큰을 다시 입력하거나 삭제해야 합니다.",
            )
        }

        val finalToken = when {
            normalizedUrl.isEmpty() -> ""
            enteredToken != null -> enteredToken
            else -> existingRouting?.bearerToken.orEmpty()
        }
        if (endpoint.isCleartext && finalToken.isNotEmpty()) {
            return SaveSettingsResult.Error("암호화되지 않은 HTTP 웹훅에는 Bearer token을 사용할 수 없습니다.")
        }

        val routing = SecretRouting(
            webhookUrl = normalizedUrl,
            bearerToken = finalToken,
            allowInsecureLocalHttp = input.allowInsecureLocalHttp,
        )
        val routingSaved = try {
            secretStore.writeRouting(routing)
        } catch (_: Exception) {
            false
        }
        if (!routingSaved) {
            return SaveSettingsResult.Error("보안 저장소에 웹훅 설정을 저장하지 못했습니다.")
        }
        if (!preferences.edit().putStringSet(KEY_ALLOWED_PACKAGES, packages).commit()) {
            return SaveSettingsResult.Error("앱 allowlist를 저장하지 못했습니다.")
        }

        return SaveSettingsResult.Success(
            HeraldSettings(
                webhookUrl = routing.webhookUrl,
                bearerToken = routing.bearerToken,
                allowedPackages = packages,
                allowInsecureLocalHttp = routing.allowInsecureLocalHttp,
            ),
        )
    }

    @Synchronized
    fun isPackageAllowed(packageName: String): Boolean =
        readAllowedPackages().contains(packageName)

    @Synchronized
    fun readAllowedPackages(): Set<String> =
        preferences.getStringSet(KEY_ALLOWED_PACKAGES, null)
            ?.toSet()
            ?.ifEmpty { DEFAULT_ALLOWED_PACKAGES }
            ?: DEFAULT_ALLOWED_PACKAGES

    companion object {
        val DEFAULT_ALLOWED_PACKAGES = setOf("com.kakao.talk")

        fun packagesAsText(packages: Set<String>): String = packages.sorted().joinToString("\n")

        internal fun parsePackages(value: String): Set<String> = value
            .split(',', '\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()

        private val PACKAGE_NAME =
            Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private const val PREFERENCES_NAME = "herald-settings"
        private const val KEY_ALLOWED_PACKAGES = "allowed-packages"
        private const val MAX_TOKEN_LENGTH = 4_096
    }
}

object WebhookRouteId {
    fun from(endpoint: String, bearerToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("herald-route-v2", endpoint, bearerToken).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update(0)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
