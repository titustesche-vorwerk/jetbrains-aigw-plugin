package dev.aigw.sandbox.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

interface StreamingProcess {
    /** Returns the exit code, or null if it did not exit within [timeoutMs]. */
    fun waitFor(timeoutMs: Long): Int?
    fun destroy()
}

interface CliRunner {
    fun run(argv: List<String>, workDir: Path? = null, timeoutMs: Long = 60_000): CliResult
    fun start(argv: List<String>, workDir: Path? = null, onLine: (String) -> Unit): StreamingProcess
}

/** UTF-8 command line for [argv] with the Homebrew fallback dirs appended to PATH. */
fun cliCommandLine(argv: List<String>, workDir: Path?): GeneralCommandLine {
    val cmd = GeneralCommandLine(argv).withCharset(StandardCharsets.UTF_8)
    workDir?.let { cmd.withWorkDirectory(it.toFile()) }
    val current = cmd.parentEnvironment["PATH"] ?: System.getenv("PATH")
    val path = (current.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() } + ToolPaths.FALLBACK_DIRS)
        .distinct().joinToString(File.pathSeparator)
    cmd.withEnvironment("PATH", path)
    return cmd
}

/** Runs real processes. Must be called off the EDT. */
class ProcessCliRunner : CliRunner {
    private fun commandLine(argv: List<String>, workDir: Path?): GeneralCommandLine = cliCommandLine(argv, workDir)

    override fun run(argv: List<String>, workDir: Path?, timeoutMs: Long): CliResult {
        val out = CapturingProcessHandler(commandLine(argv, workDir)).runProcess(timeoutMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        if (out.isTimeout) return CliResult(-1, out.stdout, out.stderr + "Timed out after ${timeoutMs}ms")
        return CliResult(out.exitCode, out.stdout, out.stderr)
    }

    override fun start(argv: List<String>, workDir: Path?, onLine: (String) -> Unit): StreamingProcess {
        val handler = OSProcessHandler(commandLine(argv, workDir))
        val done = CountDownLatch(1)
        handler.addProcessListener(object : ProcessListener {
            private val lock = Any()
            private val buffers = HashMap<Key<*>, StringBuilder>()
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.SYSTEM) return
                synchronized(lock) {
                    val sb = buffers.getOrPut(outputType) { StringBuilder() }
                    sb.append(event.text)
                    var i = sb.indexOf("\n")
                    while (i >= 0) {
                        onLine(sb.substring(0, i).trimEnd('\r'))
                        sb.delete(0, i + 1)
                        i = sb.indexOf("\n")
                    }
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                try {
                    synchronized(lock) {
                        buffers.values.filter { it.isNotEmpty() }.forEach { onLine(it.toString()) }
                        buffers.clear()
                    }
                } finally {
                    done.countDown()
                }
            }
        })
        handler.startNotify()
        return object : StreamingProcess {
            override fun waitFor(timeoutMs: Long): Int? {
                if (!handler.waitFor(timeoutMs)) return null
                if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
                return handler.exitCode
            }

            override fun destroy() = handler.destroyProcess()
        }
    }
}
