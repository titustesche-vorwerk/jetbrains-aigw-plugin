package dev.aigw.sandbox.git

import dev.aigw.sandbox.cli.CliResult
import dev.aigw.sandbox.cli.CliRunner
import java.nio.file.Path

/** git commands against the project's root repo and its `sandbox-<name>` remotes. Blocking: call off the EDT. */
class SandboxGit(private val runner: CliRunner, private val git: Path, private val repo: Path) {
    fun remoteName(sandbox: String) = "sandbox-$sandbox"

    private fun run(vararg args: String, timeoutMs: Long = 60_000): CliResult =
        runner.run(listOf(git.toString(), "-C", repo.toString()) + args, null, timeoutMs)

    fun remoteBranches(sandbox: String): List<String> {
        val r = run("ls-remote", "--heads", remoteName(sandbox))
        return if (r.ok) parseLsRemoteHeads(r.stdout) else emptyList()
    }

    fun remoteHead(sandbox: String): String? {
        val r = run("ls-remote", "--symref", remoteName(sandbox), "HEAD")
        return if (r.ok) parseSymrefHead(r.stdout) else null
    }

    fun fetch(sandbox: String, branch: String): CliResult = run("fetch", remoteName(sandbox), branch, timeoutMs = FETCH_TIMEOUT_MS)

    /** The checked-out branch, or null when HEAD is detached. */
    fun currentBranch(): String? {
        val r = run("symbolic-ref", "--short", "-q", "HEAD")
        return if (r.ok) r.stdout.trim().ifEmpty { null } else null
    }

    /** Fails closed: a failing `git status` counts as dirty. */
    fun isDirty(): Boolean {
        val r = run("status", "--porcelain", "--untracked-files=no")
        return !r.ok || r.stdout.isNotBlank()
    }

    /** Commits reachable from [ref] but not from HEAD; null when git fails or prints something unparseable. */
    fun countNew(ref: String): Int? {
        val r = run("rev-list", "--count", "HEAD..$ref")
        return if (r.ok) r.stdout.trim().toIntOrNull() else null
    }

    /** The merge-base of HEAD and [ref] (full sha); null when git fails or there is no common ancestor. */
    fun mergeBase(ref: String): String? {
        val r = run("merge-base", "HEAD", ref)
        return if (r.ok) r.stdout.trim().ifEmpty { null } else null
    }

    fun setPushDefaultOrigin() {
        run("config", "remote.pushDefault", "origin")
    }

    /** The sandbox remote only exists while the sandbox runs. */
    fun hasRemote(sandbox: String): Boolean = run("remote").stdout.lineSequence().any { it.trim() == remoteName(sandbox) }

    companion object {
        const val FETCH_TIMEOUT_MS = 5 * 60_000L
    }
}
