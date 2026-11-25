package dev.aigw.sandbox.sbx

import dev.aigw.sandbox.cli.FakeCliRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class SbxClientTest {
    private val sbx = Path.of("/bin/sbx")
    private val s = sbx.toString()

    @Test
    fun execPassesArgvVerbatim() {
        val f = FakeCliRunner()
        SbxClient(f, sbx).exec("sb", listOf("curl", "-fs", "http://localhost:8020/?code=a&state=b%2B"), workDir = "/p")
        assertEquals(listOf(s, "exec", "-w", "/p", "sb", "curl", "-fs", "http://localhost:8020/?code=a&state=b%2B"), f.calls.single())
    }

    @Test
    fun execWithoutWorkDir() {
        val f = FakeCliRunner()
        SbxClient(f, sbx).exec("sb", listOf("ls"))
        assertEquals(listOf(s, "exec", "sb", "ls"), f.calls.single())
    }

    @Test
    fun listThrowsOnNonZero() {
        val f = FakeCliRunner().on(s, "ls", "--json", stderr = "daemon down", exitCode = 1)
        val e = assertThrows<SbxException> { SbxClient(f, sbx).list() }
        assertTrue(e.message!!.contains("daemon down"))
    }

    @Test
    fun listParsesStdout() {
        val f = FakeCliRunner().on(s, "ls", "--json", stdout = """[{"name":"a","agent":"claude","status":"running"}]""")
        assertEquals(SandboxStatus.RUNNING, SbxClient(f, sbx).list().single().status)
    }

    @Test
    fun lifecycleArgv() {
        val f = FakeCliRunner()
        val c = SbxClient(f, sbx)
        c.stop("n"); c.rm("n"); c.unpublishPort("n", 8020); c.wake("n"); c.envRun(Path.of("/p"), listOf("env", "run"))
        assertEquals(
            listOf(
                listOf(s, "stop", "n"),
                listOf(s, "--no-input", "rm", "n"),
                listOf(s, "port", "unpublish", "n", "8020"),
                listOf(s, "exec", "n", "true"),
                listOf(s, "env", "run"),
            ),
            f.calls,
        )
    }
}
