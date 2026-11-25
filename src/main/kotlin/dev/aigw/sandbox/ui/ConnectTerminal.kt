package dev.aigw.sandbox.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import dev.aigw.sandbox.sbx.Sandbox
import dev.aigw.sandbox.settings.AigwAppSettings
import org.jetbrains.plugins.terminal.startup.TerminalProcessType

/**
 * Row action "Connect": opens the agent in an IDE terminal tab titled with the sandbox name (or focuses the existing one).
 * The agent command is the tab's own process (no interactive shell, so no shell rc files run); the tab shows its
 * output until it is closed or the next Connect replaces it.
 */
class ConnectAction(private val service: SandboxService) {
    /** Call on the EDT. */
    fun perform(project: Project, s: Sandbox) = connect(project, s, service.isNamedProjectSandbox(s))

    private fun connect(project: Project, s: Sandbox, isProject: Boolean) {
        if (focusExisting(project, s.name)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val argv = try {
                val sbx = AigwAppSettings.getInstance().toolPaths().require("sbx").toString()
                // The `ug claude` entrypoint only applies via `sbx env run`; other sandboxes use plain `sbx run`.
                if (isProject) listOf(sbx) + service.envArgv("run", detach = false, noInput = false, force = false)
                else listOf(sbx, "run", "--name", s.name)
            } catch (e: Exception) {
                thisLogger().warn("Connect to ${s.name} failed", e)
                AigwNotifications.error(project, "Cannot connect to ${s.name}", e.message ?: e.toString())
                return@executeOnPooledThread
            }
            val dir = (service.projectDir()?.toString() ?: project.basePath)
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                AigwNotifications.guard(project, "Connect to ${s.name}") {
                    if (!focusExisting(project, s.name)) open(project, s.name, dir, argv)
                }
            }, ModalityState.nonModal())
        }
    }

    private fun toolWindow(project: Project) = ToolWindowManager.getInstance(project).getToolWindow("Terminal")

    /** EDT. Selects the Terminal tab titled [name] if its agent still runs; closes it if the agent has exited. */
    private fun focusExisting(project: Project, name: String): Boolean {
        val manager = TerminalToolWindowTabsManager.getInstance(project)
        val tab = manager.tabs.firstOrNull { it.content.displayName == name } ?: return false
        if (tab.view.sessionState.value is TerminalViewSessionState.Terminated) {
            manager.closeTab(tab) // a dead tab: start a fresh agent instead
            return false
        }
        val tw = toolWindow(project) ?: return false
        tw.activate { tw.contentManager.setSelectedContent(tab.content, true) }
        return true
    }

    /** EDT. A new Terminal tab whose process is [argv] itself, started in [dir]. */
    private fun open(project: Project, name: String, dir: String?, argv: List<String>) {
        TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
            .tabName(name)
            .shellCommand(argv)
            .processType(TerminalProcessType.NON_SHELL)
            .closeOnProcessTermination(false) // keep the output (e.g. an error) readable after the agent exits
            .apply { if (dir != null) workingDirectory(dir) }
            .requestFocus(true)
            .createTab()
        toolWindow(project)?.activate(null)
    }
}
