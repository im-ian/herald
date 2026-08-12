package dev.imian.herald.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookValidatorTest {
    @Test
    fun `empty endpoint disables delivery`() {
        val result = WebhookValidator.validate("   ", allowInsecureLocalHttp = false)

        assertTrue(result.isValid)
        assertNull(result.normalizedUrl)
    }

    @Test
    fun `https endpoint is accepted and normalized`() {
        val result = WebhookValidator.validate(
            "https://example.com/hooks/../herald",
            allowInsecureLocalHttp = false,
        )

        assertTrue(result.isValid)
        assertEquals("https://example.com/herald", result.normalizedUrl)
    }

    @Test
    fun `userinfo and fragments are rejected`() {
        assertFalse(
            WebhookValidator.validate(
                "https://user:pass@example.com/hook",
                allowInsecureLocalHttp = false,
            ).isValid,
        )
        assertFalse(
            WebhookValidator.validate(
                "https://example.com/hook#secret",
                allowInsecureLocalHttp = false,
            ).isValid,
        )
    }

    @Test
    fun `public cleartext endpoint is always rejected`() {
        val result = WebhookValidator.validate(
            "http://example.com/hook",
            allowInsecureLocalHttp = true,
        )

        assertFalse(result.isValid)
    }

    @Test
    fun `private IPv4 requires explicit opt-in`() {
        assertFalse(
            WebhookValidator.validate(
                "http://192.168.1.20:8080/hook",
                allowInsecureLocalHttp = false,
            ).isValid,
        )
        assertTrue(
            WebhookValidator.validate(
                "http://192.168.1.20:8080/hook",
                allowInsecureLocalHttp = true,
            ).isValid,
        )
    }

    @Test
    fun `mixed case scheme is canonicalized before security decisions`() {
        val cleartext = WebhookValidator.validate(
            "HtTp://127.0.0.1:8080/hook",
            allowInsecureLocalHttp = true,
        )
        val secure = WebhookValidator.validate(
            "HTTPS://example.com/hook",
            allowInsecureLocalHttp = false,
        )

        assertEquals("http://127.0.0.1:8080/hook", cleartext.normalizedUrl)
        assertTrue(cleartext.isCleartext)
        assertEquals("https://example.com/hook", secure.normalizedUrl)
        assertFalse(secure.isCleartext)
    }

    @Test
    fun `loopback addresses and localhost are accepted with opt-in`() {
        assertTrue(
            WebhookValidator.validate(
                "http://127.0.0.1:8080/hook",
                allowInsecureLocalHttp = true,
            ).isValid,
        )
        assertTrue(
            WebhookValidator.validate(
                "http://localhost/hook",
                allowInsecureLocalHttp = true,
            ).isValid,
        )
        assertTrue(
            WebhookValidator.validate(
                "http://[::1]:8080/hook",
                allowInsecureLocalHttp = true,
            ).isValid,
        )
    }

    @Test
    fun `local-looking DNS names are rejected for cleartext`() {
        assertFalse(
            WebhookValidator.validate(
                "http://jarvis.local/hook",
                allowInsecureLocalHttp = true,
            ).isValid,
        )
        assertFalse(
            WebhookValidator.validate(
                "http://subdomain.localhost/hook",
                allowInsecureLocalHttp = true,
            ).isValid,
        )
    }

    @Test
    fun `hostnames that merely start like private IPv6 are rejected`() {
        assertFalse(
            WebhookValidator.validate(
                "http://fcevil.example/hook",
                allowInsecureLocalHttp = true,
            ).isValid,
        )
        assertFalse(
            WebhookValidator.validate(
                "http://fea.example/hook",
                allowInsecureLocalHttp = true,
            ).isValid,
        )
    }

    @Test
    fun `non-http schemes and missing hosts are rejected`() {
        assertFalse(
            WebhookValidator.validate(
                "ftp://example.com/file",
                allowInsecureLocalHttp = false,
            ).isValid,
        )
        assertFalse(
            WebhookValidator.validate(
                "https:///missing-host",
                allowInsecureLocalHttp = false,
            ).isValid,
        )
    }

    @Test
    fun `credential changes create a different delivery route`() {
        val endpoint = "https://example.com/hook"

        assertNotEquals(
            WebhookRouteId.from(endpoint, "tenant-a-token"),
            WebhookRouteId.from(endpoint, "tenant-b-token"),
        )
        assertNotEquals(
            WebhookRouteId.from(endpoint, "tenant-a-token"),
            WebhookRouteId.from("https://other.example.com/hook", "tenant-a-token"),
        )
        assertEquals(
            WebhookRouteId.from(endpoint, "tenant-a-token"),
            WebhookRouteId.from(endpoint, "tenant-a-token"),
        )
    }
}
