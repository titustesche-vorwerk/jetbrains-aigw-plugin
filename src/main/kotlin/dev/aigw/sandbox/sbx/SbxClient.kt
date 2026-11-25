package dev.aigw.sandbox.sbx

import dev.aigw.sandbox.cli.CliResult
import dev.aigw.sandbox.cli.CliRunner
import dev.aigw.sandbox.cli.StreamingProcess
import java.nio.file.Path

class SbxException(msg: String) : RuntimeException(msg)

/** Blocking wrapper around the sbx CLI. Call off the EDT. All commands are argv lists. */
class SbxClient(private val runner: CliRunner, private val sbx: Path) {
    private val exe = sbx.toString()

    fun list(): List<Sandbox> {
        val r = runner.run(listOf(exe, "ls", "--json"))
        if (!r.ok) throw SbxException("sbx ls failed (exit ${r.exitCode}): ${r.stderr.trim()}")
        return try {
            parseSbxList(r.stdout)
        } catch (e: RuntimeException) {
            throw SbxException("Could not parse sbx ls output: ${e.message}")
        }
    }

    fun stop(name: String): CliResult = runner.run(listOf(exe, "stop", name))

    fun rm(name: String): CliResult = runner.run(listOf(exe, "--no-input", "rm", name))

    fun unpublishPort(name: String, port: Int): CliResult =
        runner.run(listOf(exe, "port", "unpublish", name, port.toString()))

    fun exec(name: String, argv: List<String>, workDir: String? = null, timeoutMs: Long = 60_000): CliResult =
        runner.run(execArgv(name, argv, workDir), timeoutMs = timeoutMs)

    fun execStreaming(name: String, argv: List<String>, onLine: (String) -> Unit): StreamingProcess =
        runner.start(execArgv(name, argv, null), onLine = onLine)

    fun envRun(projectDir: Path, envArgv: List<String>): CliResult =
        runner.run(listOf(exe) + envArgv, workDir = projectDir, timeoutMs = 20 * 60_000L)

    /** Starts a stopped sandbox without attaching an agent. */
    fun wake(name: String): CliResult = runner.run(listOf(exe, "exec", name, "true"))

    private fun execArgv(name: String, argv: List<String>, workDir: String?): List<String> =
        buildList {
            add(exe); add("exec")
            if (workDir != null) { add("-w"); add(workDir) }
            add(name)
            addAll(argv)
        }
}
