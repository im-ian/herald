package dev.imian.herald.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SettingsStore(context)

    @After
    fun resetSettings() {
        store.save(
            SettingsInput(
                webhookUrl = "",
                bearerToken = "",
                allowedPackages = "com.kakao.talk",
                allowInsecureLocalHttp = false,
            ),
        )
    }

    @Test
    fun bearerTokenRoundTripsWithoutAppearingInPreferencesXml() {
        val token = "super-secret-test-token"
        val saved = store.save(
            SettingsInput(
                webhookUrl = "https://example.com/herald",
                bearerToken = token,
                allowedPackages = "com.kakao.talk",
                allowInsecureLocalHttp = false,
            ),
        )

        assertTrue(saved is SaveSettingsResult.Success)
        val read = store.read() as ReadSettingsResult.Success
        assertEquals(token, read.settings.bearerToken)
        val storedValues = context
            .getSharedPreferences("herald-secrets", Context.MODE_PRIVATE)
            .all
            .values
            .map(Any?::toString)
        assertFalse(storedValues.any { it.contains(token) })
        assertFalse(storedValues.any { it.contains("https://example.com/herald") })
    }

    @Test
    fun corruptedCiphertextFailsClosedAndIsNotDeleted() {
        store.save(
            SettingsInput(
                webhookUrl = "https://example.com/herald",
                bearerToken = "secret",
                allowedPackages = "com.kakao.talk",
                allowInsecureLocalHttp = false,
            ),
        )
        val secretPreferences = context.getSharedPreferences(
            SecretStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        secretPreferences.edit()
            .putString(SecretStore.KEY_CIPHERTEXT, "not-valid-ciphertext")
            .commit()

        assertTrue(store.read() is ReadSettingsResult.Error)
        assertEquals(
            "not-valid-ciphertext",
            secretPreferences.getString(SecretStore.KEY_CIPHERTEXT, null),
        )
    }

    @Test
    fun endpointChangeRequiresExplicitTokenDecision() {
        store.save(
            SettingsInput(
                webhookUrl = "https://old.example.com/hook",
                bearerToken = "old-secret",
                allowedPackages = "com.kakao.talk",
                allowInsecureLocalHttp = false,
            ),
        )

        val implicitReuse = store.save(
            SettingsInput(
                webhookUrl = "https://new.example.com/hook",
                bearerToken = null,
                allowedPackages = "com.kakao.talk",
                allowInsecureLocalHttp = false,
            ),
        )
        assertTrue(implicitReuse is SaveSettingsResult.Error)
        val unchanged = (store.read() as ReadSettingsResult.Success).settings
        assertEquals("https://old.example.com/hook", unchanged.webhookUrl)
        assertEquals("old-secret", unchanged.bearerToken)

        val explicitClear = store.save(
            SettingsInput(
                webhookUrl = "https://new.example.com/hook",
                bearerToken = "",
                allowedPackages = "com.kakao.talk",
                allowInsecureLocalHttp = false,
            ),
        )
        assertTrue(explicitClear is SaveSettingsResult.Success)
    }

    @Test
    fun bearerTokenIsRejectedForCleartextWebhook() {
        listOf("http", "HTTP", "HtTp").forEach { scheme ->
            val result = store.save(
                SettingsInput(
                    webhookUrl = "$scheme://127.0.0.1:8080/hook",
                    bearerToken = "must-not-travel-in-cleartext",
                    allowedPackages = "com.kakao.talk",
                    allowInsecureLocalHttp = true,
                ),
            )

            assertTrue("scheme=$scheme", result is SaveSettingsResult.Error)
        }
    }
}
