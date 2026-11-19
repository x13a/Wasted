package me.lucky.wasted.remote.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigValidatorTest {
    private val projectUrl = "https://abcdefgh.supabase.co"
    private val publishableKey = "sb_publishable_abc123"
    private val websiteUrl = "https://wasted.example.com"

    private fun errors(
        projectUrl: String = this.projectUrl,
        publishableKey: String = this.publishableKey,
        websiteUrl: String = this.websiteUrl,
    ) = RemoteConfigValidator.validate(projectUrl, publishableKey, websiteUrl)

    @Test
    fun `valid https config passes`() {
        assertTrue(errors().isEmpty())
    }

    @Test
    fun `http urls rejected`() {
        assertTrue(
            errors(projectUrl = "http://127.0.0.1:54321")
                .any { it.field == RemoteConfigField.PROJECT_URL },
        )
        assertTrue(
            errors(projectUrl = "http://localhost:54321")
                .any { it.field == RemoteConfigField.PROJECT_URL },
        )
        assertTrue(
            errors(websiteUrl = "http://wasted.example.com")
                .any { it.field == RemoteConfigField.WEBSITE_URL },
        )
    }

    @Test
    fun `url with credentials rejected`() {
        assertTrue(
            errors(projectUrl = "https://user:pass@abcdefgh.supabase.co")
                .any { it.field == RemoteConfigField.PROJECT_URL },
        )
    }

    @Test
    fun `url without scheme rejected`() {
        assertTrue(
            errors(projectUrl = "abcdefgh.supabase.co")
                .any { it.field == RemoteConfigField.PROJECT_URL },
        )
    }

    @Test
    fun `url with query or fragment rejected`() {
        assertTrue(
            errors(projectUrl = "https://abcdefgh.supabase.co?x=1")
                .any { it.field == RemoteConfigField.PROJECT_URL },
        )
        assertTrue(
            errors(projectUrl = "https://abcdefgh.supabase.co#frag")
                .any { it.field == RemoteConfigField.PROJECT_URL },
        )
    }

    @Test
    fun `blank fields rejected`() {
        assertEquals(3, errors(projectUrl = " ", publishableKey = "", websiteUrl = "\t").size)
    }

    @Test
    fun `oversized url rejected`() {
        assertTrue(
            errors(websiteUrl = "https://example.com/" + "a".repeat(600))
                .any { it.field == RemoteConfigField.WEBSITE_URL },
        )
    }

    @Test
    fun `secret key prefix rejected`() {
        assertTrue(
            errors(publishableKey = "sb_secret_abc123")
                .any { it.field == RemoteConfigField.PUBLISHABLE_KEY },
        )
    }

    @Test
    fun `legacy anon jwt accepted`() {
        assertTrue(errors(publishableKey = jwt("anon")).isEmpty())
    }

    @Test
    fun `legacy service role jwt rejected`() {
        assertTrue(
            errors(publishableKey = jwt("service_role"))
                .any { it.field == RemoteConfigField.PUBLISHABLE_KEY },
        )
    }

    @Test
    fun `malformed jwt rejected`() {
        assertTrue(
            errors(publishableKey = "eyJhbGciOiJIUzI1NiJ9.not base64!.sig")
                .any { it.field == RemoteConfigField.PUBLISHABLE_KEY },
        )
        assertTrue(
            errors(publishableKey = "eyJhbGciOiJIUzI1NiJ9")
                .any { it.field == RemoteConfigField.PUBLISHABLE_KEY },
        )
    }

    @Test
    fun `random string rejected as key`() {
        assertTrue(
            errors(publishableKey = "not-a-key")
                .any { it.field == RemoteConfigField.PUBLISHABLE_KEY },
        )
    }

    @Test
    fun `connection validation ignores the website url`() {
        assertTrue(RemoteConfigValidator.validateConnection(projectUrl, publishableKey).isEmpty())
        assertEquals(
            2,
            RemoteConfigValidator.validateConnection("http://localhost:54321", "sb_secret_x").size,
        )
    }

    private fun jwt(role: String): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        fun segment(json: String) = encoder.encodeToString(json.toByteArray(Charsets.UTF_8))
        return "${segment("{\"alg\":\"HS256\",\"typ\":\"JWT\"}")}." +
            "${segment("{\"role\":\"$role\"}")}.signature"
    }
}
