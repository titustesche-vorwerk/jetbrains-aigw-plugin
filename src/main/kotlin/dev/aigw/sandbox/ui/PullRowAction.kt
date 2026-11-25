package dev.aigw.sandbox.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.aigw.sandbox.cli.ProcessCliRunner
import dev.aigw.sandbox.cli.ToolNotFound
import dev.aigw.sandbox.git.PullDecision
import dev.aigw.sandbox.git.SandboxGit
import dev.aigw.sandbox.git.defaultPullBranch
import dev.aigw.sandbox.git.planPull
import dev.aigw.sandbox.sbx.Sandbox
import dev.aigw.sandbox.sbx.SandboxStatus
import dev.aigw.sandbox.settings.AigwAppSettings
import git4idea.branch.GitBranchUiHandlerImpl
import git4idea.branch.GitBranchWorker
import git4idea.branch.GitBrancher
import git4idea.commands.Git
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path

/** "Pull": merges the selected sandbox branch into the current local branch. Never stashes, resets or pushes. */
class PullAction(private val service: SandboxService) {
    /** Call on the EDT. [branch]: the sandbox branch from the card; null → the default once the sandbox's branches are known. */
    fun perform(project: Project, s: Sandbox, branch: String?) {
        if (s.status == SandboxStatus.RUNNING) {
            begin(project, s.name, branch)
            return
        }
        val answer = Messages.showYesNoDialog(project, "Start ${s.name} and pull?", "Pull from ${s.name}", Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        service.onceStarted(s.name) {
            ApplicationManager.getApplication().invokeLater({ AigwNotifications.guard(project, "Pull from ${s.name}") { begin(project, s.name, branch) } }, ModalityState.nonModal())
        }
        service.start(s)
    }

    private fun gitFor(project: Project): SandboxGit? {
        val dir = service.projectDir() ?: return null
        return SandboxGit(ProcessCliRunner(), AigwAppSettings.getInstance().toolPaths().require("git"), dir)
    }

    private class Inspected(val branches: List<String>, val head: String?, val local: String?, val dirty: Boolean)

    /** Step 1 (background): look at the sandbox remote and the local repo. */
    private fun begin(project: Project, name: String, branch: String?) {
        saveAllDocuments() // so unsaved editor changes count as dirty (EDT)
        runBackground(project, "Preparing pull from $name", { _ ->
            val git = gitFor(project) ?: error("Project has no base directory")
            if (!git.hasRemote(name)) null
            else Inspected(git.remoteBranches(name), git.remoteHead(name), git.currentBranch(), git.isDirty())
        }) { info ->
            if (info == null) {
                AigwNotifications.error(project, "Cannot pull from $name", "The sandbox has no git remote. Is it running?")
                return@runBackground
            }
            if (info.branches.isEmpty()) {
                AigwNotifications.error(project, "Cannot pull from $name", "The sandbox has no branches.")
                return@runBackground
            }
            val chosen = branch?.takeIf { it in info.branches } ?: defaultPullBranch(info.local, info.branches, info.head)
            when (val d = planPull(info.local, info.dirty, chosen)) {
                is PullDecision.Refuse -> AigwNotifications.error(project, "Cannot pull from $name", d.message)
                is PullDecision.Go -> fetchAndMerge(project, name, d)
            }
        }
    }

    private sealed interface Outcome {
        data class Failed(val title: String, val message: String) : Outcome
        data class UpToDate(val branch: String) : Outcome
        data class Merged(val count: Int) : Outcome
        data object Silent : Outcome // Git4Idea already reported (conflict, error, cancel)
    }

    /** Step 2 (background): fetch, set remote.pushDefault, count, re-check clean tree, then a synchronous Git4Idea merge. */
    private fun fetchAndMerge(project: Project, name: String, go: PullDecision.Go) {
        val ref = "sandbox-$name/${go.branch}"
        runBackground(project, "Pulling ${go.branch} from $name", { indicator ->
            val git = gitFor(project) ?: error("Project has no base directory")
            val r = git.fetch(name, go.branch)
            if (!r.ok) return@runBackground Outcome.Failed("Fetch from $name failed", r.stderr.ifBlank { r.stdout })
            git.setPushDefaultOrigin()
            val count = git.countNew(ref)
                ?: return@runBackground Outcome.Failed("Cannot pull from $name", "Could not compare HEAD with $ref. Is the sandbox still running?")
            if (count == 0) return@runBackground Outcome.UpToDate(go.branch)
            val repo = repository(project)
                ?: return@runBackground Outcome.Failed("Cannot pull from $name", "No git repository at the project root.")
            // The merge must never offer Smart Merge (stash): re-check the tree right before merging.
            if (git.isDirty()) {
                return@runBackground Outcome.Failed(
                    "Cannot pull from $name", "You have uncommitted changes. Commit them first. Pull never stashes your work.",
                )
            }
            repo.update() // pick up the freshly fetched remote branch
            val remoteBranch = repo.branches.findRemoteBranch(ref)
                ?: return@runBackground Outcome.Failed("Cannot pull from $name", "Git does not know the branch $ref after fetching.")
            GitBranchWorker(project, Git.getInstance(), GitBranchUiHandlerImpl(project, indicator))
                .merge(remoteBranch, GitBrancher.DeleteOnMergeOption.NOTHING, listOf(repo))
            repo.update()
            if (git.countNew(ref) == 0) {
                project.guessProjectDirOrNull()?.let { VfsUtil.markDirtyAndRefresh(true, true, true, it) }
                Outcome.Merged(count)
            } else {
                Outcome.Silent
            }
        }) { outcome ->
            service.refreshBranches(name)
            when (outcome) {
                is Outcome.Failed -> AigwNotifications.error(project, outcome.title, outcome.message)
                is Outcome.UpToDate -> AigwNotifications.info(project, "Already up to date with $name/${outcome.branch}")
                is Outcome.Merged -> AigwNotifications.info(project, "${commits(outcome.count)} merged from $name/${go.branch} into ${go.into}")
                Outcome.Silent -> {}
            }
        }
    }

    /** Background thread only (may update the repository synchronously). */
    private fun repository(project: Project): GitRepository? {
        val dir = project.basePath ?: return null
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(dir)) ?: return null
        return GitRepositoryManager.getInstance(project).getRepositoryForRoot(root)
    }

    private fun Project.guessProjectDirOrNull() = basePath?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(it)) }

    /** Runs [work] in a background task, then [then] on the EDT with its result; failures become an error notification. */
    private fun <T> runBackground(project: Project, title: String, work: (ProgressIndicator) -> T, then: (T) -> Unit) {
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

}
