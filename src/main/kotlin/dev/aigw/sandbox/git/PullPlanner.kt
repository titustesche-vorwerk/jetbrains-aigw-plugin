package dev.aigw.sandbox.git

/** Branch names from `git ls-remote --heads` output (names may contain slashes). */
fun parseLsRemoteHeads(out: String): List<String> =
    out.lineSequence().mapNotNull { line ->
        line.substringAfter('\t', "").trim().takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/")
    }.filter { it.isNotEmpty() }.toList()

/** The branch HEAD points to, from `git ls-remote --symref <remote> HEAD` output; null when not reported. */
fun parseSymrefHead(out: String): String? =
    out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("ref: refs/heads/") }
        ?.removePrefix("ref: refs/heads/")?.substringBefore('\t')?.trim()?.takeIf { it.isNotEmpty() }

sealed interface PullDecision {
    data class Go(val branch: String, val into: String) : PullDecision
    data class Refuse(val message: String) : PullDecision
}

/** Detached HEAD first, then a dirty tree, then a missing branch choice. Pull never stashes. */
fun planPull(localBranch: String?, dirty: Boolean, chosen: String?): PullDecision = when {
    localBranch == null -> PullDecision.Refuse("You're on a detached HEAD. Check out the branch to merge into first.")
    dirty -> PullDecision.Refuse("You have uncommitted changes. Commit them first. Pull never stashes your work.")
    chosen == null -> PullDecision.Refuse("No sandbox branch selected.")
    else -> PullDecision.Go(chosen, localBranch)
}

/** The local branch name if the sandbox has it, else the sandbox's HEAD branch, else the first branch. */
fun defaultPullBranch(localBranch: String?, sandboxBranches: List<String>, sandboxHead: String?): String? =
    when {
        sandboxBranches.isEmpty() -> null
        localBranch != null && localBranch in sandboxBranches -> localBranch
        sandboxHead != null && sandboxHead in sandboxBranches -> sandboxHead
        else -> sandboxBranches.first()
    }
