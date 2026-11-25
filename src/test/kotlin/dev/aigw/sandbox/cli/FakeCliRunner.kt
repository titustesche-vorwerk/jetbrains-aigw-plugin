package dev.aigw.sandbox.cli

import java.nio.file.Path

/** Test double: responses matched by longest argv prefix; records every argv received. */
class FakeCliRunner(
    private val default: CliResult = CliResult(0, "", ""),
) : CliRunner {
    class StartConfig(val lines: List<String>, val exitCode: Int)

    val calls = mutableListOf<List<String>>()
    private val responses = mutableListOf<Pair<List<String>, CliResult>>()
    private val startConfigs = mutableListOf<Pair<List<String>, StartConfig>>()

    fun on(prefix: List<String>, result: CliResult): FakeCliRunner = apply { responses += prefix to result }

    fun on(vararg prefix: String, stdout: String = "", stderr: String = "", exitCode: Int = 0): FakeCliRunner =
        on(prefix.toList(), CliResult(exitCode, stdout, stderr))

    fun onStart(prefix: List<String>, lines: List<String>, exitCode: Int = 0): FakeCliRunner =
        apply { startConfigs += prefix to StartConfig(lines, exitCode) }

    private fun <T> longest(argv: List<String>, list: List<Pair<List<String>, T>>): T? =
        list.filter { (p, _) -> argv.size >= p.size && argv.subList(0, p.size) == p }
            .maxByOrNull { it.first.size }?.second

    override fun run(argv: List<String>, workDir: Path?, timeoutMs: Long): CliResult {
        calls += argv
        return longest(argv, responses) ?: default
    }

    override fun start(argv: List<String>, workDir: Path?, onLine: (String) -> Unit): StreamingProcess {
        calls += argv
        val cfg = longest(argv, startConfigs) ?: StartConfig(emptyList(), 0)
        cfg.lines.forEach(onLine)
        return object : StreamingProcess {
            override fun waitFor(timeoutMs: Long): Int? = cfg.exitCode
            override fun destroy() {}
        }
    }
}
