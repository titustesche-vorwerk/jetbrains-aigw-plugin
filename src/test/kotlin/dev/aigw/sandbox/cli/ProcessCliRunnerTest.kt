package dev.aigw.sandbox.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections

class ProcessCliRunnerTest {
    private val runner = ProcessCliRunner()

    @Test
    fun startDeliversAllLinesIncludingTrailingPartialBeforeWaitForReturns() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val p = runner.start(listOf("/bin/sh", "-c", "printf 'a\\nb'")) { lines += it }
        assertEquals(0, p.waitFor(10_000))
        assertEquals(listOf("a", "b"), synchronized(lines) { lines.toList() })
    }

    @Test
    fun runCapturesOutputAndExitCode() {
        val r = runner.run(listOf("/bin/sh", "-c", "echo out; echo err 1>&2; exit 3"))
        assertEquals(3, r.exitCode)
        assertFalse(r.ok)
        assertEquals("out", r.stdout.trim())
        assertEquals("err", r.stderr.trim())
    }

    @Test
    fun runReportsTimeout() {
        val r = runner.run(listOf("/bin/sleep", "5"), timeoutMs = 300)
        assertFalse(r.ok)
        assertTrue(r.stderr.contains("Timed out"))
    }

    @Test
    fun runAcceptsHugeTimeout() {
        assertTrue(runner.run(listOf("/bin/echo", "hi"), timeoutMs = Long.MAX_VALUE).ok)
    }

    @Test
    fun waitForReturnsNullOnTimeout() {
        val p = runner.start(listOf("/bin/sleep", "5")) {}
        assertNull(p.waitFor(200))
        p.destroy()
    }
}
