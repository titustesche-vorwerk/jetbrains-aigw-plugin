package dev.aigw.sandbox.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class ToolPathsTest {
    private fun p(s: String): Path = Path.of(s)

    @Test
    fun usesConfiguredPathFirst() {
        val tp = ToolPaths(mapOf("sbx" to "/x/sbx"), "/a:/b") { true }
        assertEquals(p("/x/sbx"), tp.resolve("sbx"))
    }

    @Test
    fun searchesPathEnv() {
        val tp = ToolPaths(emptyMap(), "/a:/b") { it == p("/b/sbx") }
        assertEquals(p("/b/sbx"), tp.resolve("sbx"))
    }

    @Test
    fun fallsBackToHomebrew() {
        val tp = ToolPaths(emptyMap(), "/usr/bin") { it == p("/opt/homebrew/bin/sbx") }
        assertEquals(p("/opt/homebrew/bin/sbx"), tp.resolve("sbx"))
    }

    @Test
    fun missingBinaryMessage() {
        val tp = ToolPaths(emptyMap(), "/usr/bin") { false }
        val e = assertThrows<ToolNotFound> { tp.require("sbx") }
        assertEquals("sbx not found. Set its path in Settings | Tools | AIGW Sandbox.", e.message)
    }
}
