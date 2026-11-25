package dev.aigw.sandbox.auth

import dev.aigw.sandbox.sbx.SbxClient

private val URL_TOKEN = Regex("""https://\S+""")

/** First `https://…` token of [line] that contains "authorize", minus trailing `.,)`; null if none. */
fun extractAuthorizeUrl(line: String): String? =
    URL_TOKEN.findAll(line).map { it.value.trimEnd('.', ',', ')') }.firstOrNull { "authorize" in it }

/** Prefixes `https://` when [host] has no scheme. */
fun normalizeHost(host: String): String {
    val h = host.trim()
    return if (h.contains("://")) h else "https://$h"
}

/** Databricks sign-in inside a sandbox. Call off the EDT. [log] receives diagnostics only (never tokens). */
class SignIn(private val sbx: SbxClient, private val log: (String) -> Unit = {}) {
    /** `ug auth-token` exit code 0. The token on stdout is discarded without being inspected or logged. */
    fun isSignedIn(name: String): Boolean {
        val signedIn = sbx.exec(name, listOf("ug", "auth-token")).exitCode == 0
        log("ug auth-token in $name: signedIn=$signedIn")
        return signedIn
    }

    fun profileExists(name: String, profile: String): Boolean =
        sbx.exec(name, listOf("sh", "-c", "grep -qxF \"[\$0]\" ~/.databrickscfg", profile)).exitCode == 0

    /** Exactly one browser login: `ug configure` always forces its own OAuth login, so never also `databricks auth login`. */
    fun commands(host: String, profile: String, profileExists: Boolean): List<List<String>> {
        val configure = listOf("ug", "configure", "--agents", "claude", "--profile", profile)
        if (profileExists) return listOf(configure)
        val append = listOf("sh", "-c", "printf '\\n[%s]\\nhost = %s\\n' \"\$0\" \"\$1\" >> ~/.databrickscfg", profile, host)
        return listOf(append, configure)
    }
}
