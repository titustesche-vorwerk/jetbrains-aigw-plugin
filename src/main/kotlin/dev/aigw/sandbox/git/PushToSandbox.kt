package dev.aigw.sandbox.git

import dev.aigw.sandbox.sbx.SbxClient

sealed interface PushResult {
    data class Merged(val branch: String) : PushResult
    data object UpToDate : PushResult
    data class Refused(val message: String) : PushResult
    data class Conflict(val branch: String) : PushResult
    data class Failed(val stderr: String) : PushResult
}

/**
 * Merges the host repo's committed branch [localBranch] into the sandbox branch [targetBranch] (which must be checked out there) (mounted read-only at /run/sandbox/source) into the sandbox clone at [dir].
 * Never stashes or resets; a merge left in progress (conflict) is aborted. Blocking: call off the EDT.
 */
class PushToSandbox(private val sbx: SbxClient, private val sandbox: String, private val dir: String) {
    private fun exec(vararg argv: String, timeoutMs: Long = 60_000) = sbx.exec(sandbox, listOf("git") + argv, workDir = dir, timeoutMs = timeoutMs)

    fun push(localBranch: String?, targetBranch: String): PushResult {
        if (localBranch == null) return PushResult.Refused("You're on a detached HEAD. Check out the branch to push first. Nothing was changed.")
        val branch = exec("branch", "--show-current")
        if (!branch.ok) return PushResult.Failed(branch.stderr.ifBlank { branch.stdout })
        val sbxBranch = branch.stdout.trim()
        if (sbxBranch != targetBranch) return PushResult.Refused("Claude is on $sbxBranch. Pick that branch or switch the sandbox first. Nothing was changed.")
        val status = exec("status", "--porcelain", "--untracked-files=no")
        if (!status.ok) return PushResult.Failed(status.stderr.ifBlank { status.stdout })
        if (status.stdout.isNotBlank()) return PushResult.Refused("The sandbox has uncommitted changes (Claude may be mid-edit). Nothing was changed.")
        val fetch = exec("fetch", SOURCE, localBranch, timeoutMs = SandboxGit.FETCH_TIMEOUT_MS)
        if (!fetch.ok) return PushResult.Failed(fetch.stderr.ifBlank { fetch.stdout })
        val merge = exec("merge", "--no-edit", "FETCH_HEAD")
        if (!merge.ok) {
            // A merge that failed before starting (e.g. untracked files in the way) leaves no MERGE_HEAD: nothing to abort.
            if (!exec("rev-parse", "-q", "--verify", "MERGE_HEAD").ok) return PushResult.Failed(merge.stderr.ifBlank { merge.stdout })
            val unmerged = exec("diff", "--name-only", "--diff-filter=U")
            val conflicted = unmerged.ok && unmerged.stdout.isNotBlank()
            val abort = exec("merge", "--abort")
            if (!abort.ok) {
                return PushResult.Failed("The merge failed and `git merge --abort` also failed, so the sandbox may still be mid-merge: ${abort.stderr.ifBlank { abort.stdout }}")
            }
            return if (conflicted) PushResult.Conflict(targetBranch) else PushResult.Failed(merge.stderr.ifBlank { merge.stdout })
        }
        return if (merge.stdout.contains("Already up to date") || merge.stdout.contains("Already up-to-date")) PushResult.UpToDate
        else PushResult.Merged(targetBranch)
    }

    companion object {
        const val SOURCE = "/run/sandbox/source"
    }
}
