package dev.aigw.sandbox.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory

class SandboxToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SandboxPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false))

        val service = SandboxService.getInstance(project)
        fun syncPolling() = service.setPollingActive(!toolWindow.isDisposed && toolWindow.isVisible)
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) = syncPolling()
                override fun toolWindowShown(shown: ToolWindow) {
                    if (shown.id == toolWindow.id) syncPolling()
                }
            },
        )
        Disposer.register(toolWindow.disposable) { service.setPollingActive(false) }
        service.refresh() // on open, even if visibility is not reported yet
        syncPolling()
    }

    companion object {
        const val ID = "AIGW Sandboxes"
    }
}
