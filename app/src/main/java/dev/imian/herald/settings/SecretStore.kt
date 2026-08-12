package dev.imian.herald.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal data class SecretRouting(
    val webhookUrl: String,
    val bearerToken: String,
    val allowInsecureLocalHttp: Boolean,
)

internal sealed interface SecretRoutingRead {
    data object Missing : SecretRoutingRead
    data class Success(val routing: SecretRouting) : SecretRoutingRead
    data object Failure : SecretRoutingRead
}

internal class SecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun readRouting(): SecretRoutingRead {
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null)
        val encodedIv = preferences.getString(KEY_IV, null)
        if (encodedCiphertext == null && encodedIv == null) return SecretRoutingRead.Missing
        if (encodedCiphertext == null || encodedIv == null) return SecretRoutingRead.Failure

        return try {
            val key = existingKey() ?: return SecretRoutingRead.Failure
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            cipher.updateAAD(AAD)
            val plaintext = cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
            val json = JSONObject(plaintext.toString(Charsets.UTF_8))
            if (json.getInt("version") != ROUTING_SCHEMA_VERSION) {
                return SecretRoutingRead.Failure
            }
            SecretRoutingRead.Success(
                SecretRouting(
                    webhookUrl = json.getString("webhookUrl"),
                    bearerToken = json.getString("bearerToken"),
                    allowInsecureLocalHttp = json.getBoolean("allowInsecureLocalHttp"),
                ),
            )
        } catch (_: Exception) {
            // Fail closed. Keep the ciphertext so the user can explicitly replace it.
            SecretRoutingRead.Failure
        }
    }

    fun writeRouting(routing: SecretRouting): Boolean {
        val plaintext = JSONObject()
            .put("version", ROUTING_SCHEMA_VERSION)
            .put("webhookUrl", routing.webhookUrl)
            .put("bearerToken", routing.bearerToken)
            .put("allowInsecureLocalHttp", routing.allowInsecureLocalHttp)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext)

        // Both values live in one AtomicFile-backed preference commit.
        return preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    internal companion object {
        const val PREFERENCES_NAME = "herald-secrets"
        const val KEY_CIPHERTEXT = "routing-ciphertext"
        const val KEY_IV = "routing-iv"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "herald-routing-v2"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val ROUTING_SCHEMA_VERSION = 1
        private val AAD = "dev.imian.herald:routing:v2".toByteArray(Charsets.UTF_8)
    }
}
