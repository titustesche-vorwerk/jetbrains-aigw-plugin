package dev.aigw.sandbox.auth

import dev.aigw.sandbox.cli.FakeCliRunner
import dev.aigw.sandbox.sbx.SbxClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SignInTest {
    private val sbxPath = Path.of("/bin/sbx")
    private val s = sbxPath.toString()
    private val url = "https://example.cloud.databricks.com/oidc/v1/authorize?client_id=databricks-cli&code_challenge=abc&code_challenge_method=S256&redirect_uri=http%3A%2F%2Flocalhost%3A8020&response_type=code&scope=offline_access+all-apis&state=xyz"

    private fun signIn(f: FakeCliRunner, log: (String) -> Unit = {}) = SignIn(SbxClient(f, sbxPath), log)

    @Test
    fun extractsUrl() {
        assertEquals(url, extractAuthorizeUrl("Open this URL in your browser: $url"))
        assertEquals(
            "https://dbc-x.cloud.databricks.com/oidc/v1/authorize?client_id=databricks-cli&redirect_uri=http%3A%2F%2Flocalhost%3A8020&state=s",
            extractAuthorizeUrl("Please open https://dbc-x.cloud.databricks.com/oidc/v1/authorize?client_id=databricks-cli&redirect_uri=http%3A%2F%2Flocalhost%3A8020&state=s. in your browser"),
        )
        assertNull(extractAuthorizeUrl("• A browser may open for `databricks auth login`."))
        assertNull(extractAuthorizeUrl("see https://example.com/docs for help"))
    }

    @Test
    fun isSignedInUsesExitCodeOnly() {
        val logs = mutableListOf<String>()
        val ok = FakeCliRunner().on(s, "exec", "sb", "ug", "auth-token", stdout = "secret")
        assertTrue(signIn(ok) { logs += it }.isSignedIn("sb"))
        val no = FakeCliRunner().on(s, "exec", "sb", "ug", "auth-token", stdout = "secret", exitCode = 1)
        assertFalse(signIn(no) { logs += it }.isSignedIn("sb"))
        assertTrue(logs.none { "secret" in it })
    }

    @Test
    fun commandSequenceForExistingProfile() {
        assertEquals(
            listOf(listOf("ug", "configure", "--agents", "claude", "--profile", "p")),
            signIn(FakeCliRunner()).commands("https://h", "p", true),
        )
    }

    @Test
    fun commandSequenceForNewProfile() {
        assertEquals(
            listOf(
                listOf("sh", "-c", "printf '\\n[%s]\\nhost = %s\\n' \"\$0\" \"\$1\" >> ~/.databrickscfg", "p", "https://h"),
                listOf("ug", "configure", "--agents", "claude", "--profile", "p"),
            ),
            signIn(FakeCliRunner()).commands("https://h", "p", false),
        )
    }

    @Test
    fun hostGetsSchemeWhenMissing() {
        assertEquals("https://h.com", normalizeHost("h.com"))
        assertEquals("http://h.com", normalizeHost(" http://h.com "))
    }

    @Test
    fun profileExistsUsesGrepExitCode() {
        val yes = FakeCliRunner()
        assertTrue(signIn(yes).profileExists("sb", "p"))
        assertEquals(listOf(s, "exec", "sb", "sh", "-c", "grep -qxF \"[\$0]\" ~/.databrickscfg", "p"), yes.calls.single())
        val no = FakeCliRunner().on(s, "exec", exitCode = 1)
        assertFalse(signIn(no).profileExists("sb", "p"))
    }
}
