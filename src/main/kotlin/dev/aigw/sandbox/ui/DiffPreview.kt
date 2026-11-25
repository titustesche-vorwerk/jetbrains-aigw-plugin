package dev.aigw.sandbox.ui

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.SimpleAsyncChangesBrowser
import com.intellij.openapi.vfs.LocalFileSystem
import dev.aigw.sandbox.cli.ProcessCliRunner
import dev.aigw.sandbox.cli.ToolNotFound
import dev.aigw.sandbox.git.SandboxGit
import dev.aigw.sandbox.sbx.Sandbox
import dev.aigw.sandbox.settings.AigwAppSettings
import git4idea.changes.GitChangeUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Row action "Diff": shows what a sandbox branch would bring (changes from the merge-base of HEAD and the branch to the branch),
 * read-only, in the IDE's changes browser. Never merges, checks out, stashes or touches config.
 */
class DiffAction(private val service: SandboxService) {
    /** Call on the EDT. [branch]: the sandbox branch selected on the card. */
    fun perform(project: Project, s: Sandbox, branch: String?) {
        val name = s.name
        if (branch == null) {
            AigwNotifications.error(project, "Cannot diff with $name", "No sandbox branch selected.")
            return
        }
        val dir = service.projectDir() ?: return
        runDiffTask(project, "Preparing diff with $name", {
            // Resolving git may throw ToolNotFound: background only, reported by runDiffTask.
            val git = SandboxGit(ProcessCliRunner(), AigwAppSettings.getInstance().toolPaths().require("git"), dir)
            git.takeIf { it.hasRemote(name) }
        }) { git ->
            if (git == null) {
                AigwNotifications.error(project, "Cannot diff with $name", "The sandbox has no git remote. Is it running?")
                return@runDiffTask
            }
            fetchAndShow(project, git, name, branch)
        }
    }

    private sealed interface Fetched {
        data class Failed(val title: String, val message: String) : Fetched
        data class Ready(val repo: GitRepository) : Fetched
    }

    private fun fetchAndShow(project: Project, git: SandboxGit, name: String, branch: String) {
        val ref = "${git.remoteName(name)}/$branch"
        runDiffTask(project, "Fetching $branch from $name", {
            val r = git.fetch(name, branch)
            if (!r.ok) return@runDiffTask Fetched.Failed("Fetch from $name failed", r.stderr.ifBlank { r.stdout })
            // Background only: resolving the repository may update it synchronously, which the EDT must not do.
            repository(project)?.let { Fetched.Ready(it) } ?: Fetched.Failed("Cannot diff with $name", "No git repository at the project root.")
        }) { fetched ->
            when (fetched) {
                is Fetched.Failed -> AigwNotifications.error(project, fetched.title, fetched.message)
                is Fetched.Ready -> showSandboxDiff(project, fetched.repo, ref, git, "$name/$branch")
            }
        }
    }

    /** Background thread only. */
    private fun repository(project: Project): GitRepository? {
        val dir = project.basePath ?: return null
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(dir)) ?: return null
        return GitRepositoryManager.getInstance(project).getRepositoryForRoot(root)
    }
}

/**
 * Computes the changes from merge-base(HEAD, [ref]) to [ref] in the background and shows them (or a "no new commits" notice).
 * [ref] must already be fetched, e.g. `sandbox-x/main`; [label] is what the dialog title shows, e.g. `x/main`. Call on the EDT.
 */
fun showSandboxDiff(project: Project, repo: GitRepository, ref: String, git: SandboxGit, label: String = ref) {
    saveAllDocuments()
    runDiffTask(project, "Comparing HEAD with $ref", {
        repo.update() // background only: asserts non-EDT in 262
        val base = git.mergeBase(ref) ?: error("Could not find a common ancestor of HEAD and $ref.")
        (GitChangeUtils.getDiff(repo, base, ref, false) ?: error("git diff $base..$ref failed.")).toList()
    }) { changes ->
        if (changes.isEmpty()) {
            AigwNotifications.info(
                project, "No commits in $ref that your branch doesn't have yet.", "(Diff shows only what Claude committed in the sandbox.)",
            )
        } else {
            ChangesDialog(project, "Changes in $label", changes).show()
        }
    }
}

/** Non-modal, read-only list of changes; double-click opens the built-in diff viewer. Holds no background work. */
private class ChangesDialog(project: Project, dialogTitle: String, private val changes: List<Change>) : DialogWrapper(project, false) {
    private val browser = SimpleAsyncChangesBrowser(project, false, true)

    init {
        title = dialogTitle
        setModal(false)
        setOKButtonText("Close")
        init()
        Disposer.register(disposable) { browser.shutdown() }
        browser.setChangesToDisplay(changes)
    }

    override fun createCenterPanel(): JComponent = browser

    override fun createActions() = arrayOf(okAction)
}

private fun <T> runDiffTask(project: Project, title: String, work: (ProgressIndicator) -> T, then: (T) -> Unit) {
    object : Task.Backgroundable(project, title, false) {
        private var result: Result<T>? = null
        override fun run(indicator: ProgressIndicator) {
            result = runCatchingCancellable { work(indicator) }
        }

        override fun onFinished() {
            val r = result ?: return
            if (project.isDisposed) return
            r.onSuccess { AigwNotifications.guard(project, title) { then(it) } }.onFailure {
                if (it !is ToolNotFound) thisLogger().warn(title, it)
                AigwNotifications.error(project, "$title failed", it.message ?: it.toString())
            }
        }
    }.queue()
}
