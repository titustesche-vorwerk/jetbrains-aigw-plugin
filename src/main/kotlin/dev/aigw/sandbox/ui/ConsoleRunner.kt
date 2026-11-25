package dev.aigw.sandbox.ui

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.aigw.sandbox.cli.cliCommandLine
import java.nio.file.Path

/** Runs a CLI command with its output in a Run tool window tab. */
object ConsoleRunner {
    /** [onExit] receives the exit code (-1 if the process could not be started), on a background thread. */
    fun run(project: Project, title: String, argv: List<String>, workDir: Path, onExit: (Int) -> Unit) =
        runWithOutput(project, title, argv, workDir) { code, _ -> onExit(code) }

    /**
     * Like [run], but [onExit] also receives the combined stdout+stderr text (on a background thread).
     * If the process cannot be started, [onExit] gets `-1` and the error message.
     */
    fun runWithOutput(
        project: Project,
        title: String,
        argv: List<String>,
        workDir: Path,
        onExit: (exitCode: Int, output: String) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val handler = try {
                OSProcessHandler(cliCommandLine(argv, workDir))
            } catch (e: Exception) {
                thisLogger().info("Could not start ${argv.firstOrNull()}", e)
                onExit(-1, e.message ?: e.toString())
                return@executeOnPooledThread
            }
            val output = StringBuilder()
            val exited = java.util.concurrent.atomic.AtomicBoolean(false)
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType == ProcessOutputTypes.SYSTEM) return
                    synchronized(output) { output.append(event.text) }
                }

                override fun processTerminated(event: ProcessEvent) {
                    val text = synchronized(output) { output.toString() }
                    if (exited.compareAndSet(false, true)) ApplicationManager.getApplication().executeOnPooledThread { onExit(event.exitCode, text) }
                }
            })
            // RunContentExecutor builds the console UI and calls startNotify(); output stays buffered until then.
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) {
                    handler.startNotify()
                    handler.destroyProcess()
                    return@invokeLater
                }
                try {
                    RunContentExecutor(project, handler)
                        .withTitle(title)
                        .withActivateToolWindow(true)
                        .withFocusToolWindow(false)
                        .run()
                } catch (e: Exception) {
                    // startNotify() never ran, so processTerminated would never fire: report the failure ourselves.
                    thisLogger().warn("Could not open the Run console for $title", e)
                    val message = e.message ?: e.toString()
                    // destroyProcess() is queued until startNotify() has run, so start notifying first.
                    if (exited.compareAndSet(false, true)) {
                        try {
                            if (!handler.isStartNotified) handler.startNotify()
                            handler.destroyProcess()
                        } finally {
                            ApplicationManager.getApplication().executeOnPooledThread { onExit(-1, message) }
                        }
                    }
                }
            }, ModalityState.nonModal())
        }
    }
}
