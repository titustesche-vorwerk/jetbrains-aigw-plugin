package dev.aigw.sandbox.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.aigw.sandbox.cli.ProcessCliRunner
import dev.aigw.sandbox.cli.ToolNotFound
import dev.aigw.sandbox.git.PushResult
import dev.aigw.sandbox.git.PushToSandbox
import dev.aigw.sandbox.git.SandboxGit
import dev.aigw.sandbox.sbx.Sandbox
import dev.aigw.sandbox.settings.AigwAppSettings

/** "Push": merges the local committed branch into the selected sandbox branch. Never pushes to GitLab, stashes or resets. */
class PushAction(private val service: SandboxService) {
    /** Call on the EDT. [target]: the sandbox branch selected on the card (must be checked out in the sandbox). */
    fun perform(project: Project, s: Sandbox, target: String?) {
        if (target == null) {
            AigwNotifications.error(project, "Cannot push to ${s.name}", "No sandbox branch selected.")
            return
        }
        val dir = service.projectDir() ?: return
        saveAllDocuments() // flush editor buffers before looking at the tree
        val name = s.name
        if (!service.beginExec(name)) {
            if (service.isBusy(name)) service.notifyBusy(name, "Cannot push")
            else AigwNotifications.notify(project, NotificationType.WARNING, "Cannot push to $name", notificationText("$name is not running."))
            return
        }
        object : Task.Backgroundable(project, "Pushing to $name", false) {
            private var result: Result<Triple<PushResult, Boolean, String?>>? = null
            override fun run(indicator: ProgressIndicator) {
                try {
                    result = runCatchingCancellable {
                        val git = SandboxGit(ProcessCliRunner(), AigwAppSettings.getInstance().toolPaths().require("git"), dir)
                        val dirty = git.isDirty()
                        val branch = git.currentBranch()
                        // Stop/Reset are held off while we are in flight; this catches a stop outside the IDE.
                        val res = if (!service.isRunningNow(name)) PushResult.Refused("$name is not running. Nothing was changed.")
                        else PushToSandbox(service.sbx(), name, dir.toString()).push(branch, target)
                        Triple(res, dirty, branch)
                    }
                } finally {
                    service.endExec(name)
                }
            }

            override fun onFinished() {
                val r = result ?: return
                if (project.isDisposed) return
                service.refreshBranches(name)
                r.onSuccess { (res, dirty, branch) -> report(project, name, res, dirty, branch) }.onFailure {
                    if (it !is ToolNotFound) thisLogger().warn("Push to $name", it)
                    AigwNotifications.error(project, "Push to $name failed", notificationText(it.message ?: it.toString()))
                }
            }
        }.queue()
    }

    private fun report(project: Project, name: String, res: PushResult, dirty: Boolean, branch: String?) {
        val note = if (dirty) "Only committed changes are pushed." else ""
        when (res) {
            is PushResult.Merged -> AigwNotifications.info(project, if (branch == res.branch) "Pushed $branch into $name" else "Pushed $branch into $name/${res.branch}", note)
            PushResult.UpToDate -> AigwNotifications.info(project, "$name already has $branch", note)
            is PushResult.Refused -> AigwNotifications.notify(project, NotificationType.WARNING, "Cannot push to $name", notificationText(res.message))
            is PushResult.Conflict -> AigwNotifications.error(
                project, "Push to $name conflicts",
                "Your changes conflict with Claude's in the sandbox. The merge was aborted, so nothing changed. Ask Claude to merge, or Pull first.",
            )
            is PushResult.Failed -> AigwNotifications.error(project, "Push to $name failed", notificationText(res.stderr))
        }
    }
}
