package me.lucky.wasted.remote.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardTemplateTest {
    private val template = """
        window.WASTED_CONFIG = {
          supabaseUrl: "__WASTED_SUPABASE_URL__",
          supabasePublishableKey: "__WASTED_PUBLISHABLE_KEY__",
          dashboardUrl: "__WASTED_DASHBOARD_URL__",
          version: "__WASTED_VERSION__",
        };
    """.trimIndent()

    @Test
    fun `placeholders are replaced once with trimmed values`() {
        val rendered = DashboardTemplate.render(
            template,
            " https://abcdefgh.supabase.co ",
            " sb_publishable_abc123 ",
            " 3.0.0 ",
            " https://dashboard.example/ ",
        )

        assertTrue(rendered.contains("https://abcdefgh.supabase.co"))
        assertTrue(rendered.contains("sb_publishable_abc123"))
        assertTrue(rendered.contains("https://dashboard.example/"))
        assertTrue(rendered.contains("3.0.0"))
        assertFalse(rendered.contains("__WASTED_"))
    }

    @Test
    fun `values cannot break out of the script string`() {
        val rendered = DashboardTemplate.render(
            template,
            "https://abcdefgh.supabase.co",
            "\"</script><script>alert(1)</script>",
            "1.0",
        )

        assertFalse(rendered.contains("</script><script>"))
        assertTrue(rendered.contains("\\u003c/script\\u003e"))
    }

    @Test
    fun `backslashes and quotes are escaped`() {
        val escaped = DashboardTemplate.escapeForScript("a\\b\"c'd")

        assertTrue(escaped.contains("a\\\\b"))
        assertTrue(escaped.contains("\\\"c"))
        assertTrue(escaped.contains("\\'d"))
    }
}
