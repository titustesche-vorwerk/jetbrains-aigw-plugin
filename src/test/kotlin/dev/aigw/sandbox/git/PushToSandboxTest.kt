package dev.aigw.sandbox.git

import dev.aigw.sandbox.cli.CliResult
import dev.aigw.sandbox.cli.FakeCliRunner
import dev.aigw.sandbox.sbx.SbxClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class PushToSandboxTest {
    private val pre = listOf("sbx", "exec", "-w", "/p", "sb")
    private fun push(r: FakeCliRunner, branch: String?, target: String = branch ?: "master") = PushToSandbox(SbxClient(r, Path.of("sbx")), "sb", "/p").push(branch, target)
    private fun clean(branch: String = "master") = FakeCliRunner()
        .at(pre + listOf("git", "branch", "--show-current"), stdout = "$branch\n")
        .at(pre + listOf("git", "status"), stdout = "")

    @Test fun detachedHeadRefused() {
        val r = FakeCliRunner()
        assertEquals(PushResult.Refused("You're on a detached HEAD. Check out the branch to push first. Nothing was changed."), push(r, null))
        assertTrue(r.calls.isEmpty())
    }

    @Test fun refusesOtherBranch() {
        val r = clean("feat")
        val res = push(r, "master")
        assertFalse(r.calls.any { "status" in it || "fetch" in it || "merge" in it })
        assertEquals(PushResult.Refused("Claude is on feat. Pick that branch or switch the sandbox first. Nothing was changed."), res)
    }

    @Test fun refusesWhenSandboxOnOtherBranch() {
        val r = clean("fix/xy")
        assertEquals(PushResult.Refused("Claude is on fix/xy. Pick that branch or switch the sandbox first. Nothing was changed."), push(r, "master", "master"))
        assertFalse(r.calls.any { "status" in it || "fetch" in it || "merge" in it })
    }

    @Test fun pushesLocalBranchIntoSelectedSandboxBranch() {
        val r = clean("fix/xy").at(pre + listOf("git", "merge"), stdout = "Merge made by the 'ort' strategy.")
        assertEquals(PushResult.Merged("fix/xy"), push(r, "feature/a", "fix/xy"))
        assertEquals(
            listOf(
                pre + listOf("git", "branch", "--show-current"),
                pre + listOf("git", "status", "--porcelain", "--untracked-files=no"),
                pre + listOf("git", "fetch", "/run/sandbox/source", "feature/a"),
                pre + listOf("git", "merge", "--no-edit", "FETCH_HEAD"),
            ),
            r.calls,
        )
    }

    @Test fun refusesDirtySandbox() {
        val r = FakeCliRunner().at(pre + listOf("git", "branch", "--show-current"), stdout = "master").at(pre + listOf("git", "status", "--porcelain", "--untracked-files=no"), stdout = " M a.txt\n")
        val res = push(r, "master")
        assertEquals(PushResult.Refused("The sandbox has uncommitted changes (Claude may be mid-edit). Nothing was changed."), res)
        assertFalse(r.calls.any { "fetch" in it || "merge" in it })
    }

    @Test fun failsClosedOnBranchError() {
        val r = FakeCliRunner().at(pre + listOf("git", "branch", "--show-current"), stderr = "boom", exitCode = 1)
        assertEquals(PushResult.Failed("boom"), push(r, "master"))
        assertFalse(r.calls.any { "fetch" in it })
    }

    @Test fun failsClosedOnStatusError() {
        val r = FakeCliRunner().at(pre + listOf("git", "branch", "--show-current"), stdout = "master").at(pre + listOf("git", "status", "--porcelain", "--untracked-files=no"), stderr = "bad", exitCode = 128)
        assertEquals(PushResult.Failed("bad"), push(r, "master"))
        assertFalse(r.calls.any { "fetch" in it })
    }

    @Test fun fetchFailure() {
        val r = clean().at(pre + listOf("git", "fetch"), stderr = "nope", exitCode = 1)
        assertEquals(PushResult.Failed("nope"), push(r, "master"))
        assertFalse(r.calls.any { "merge" in it })
    }

    @Test fun mergesWhenClean() {
        val r = clean().at(pre + listOf("git", "merge"), stdout = "Merge made by the 'ort' strategy.")
        assertEquals(PushResult.Merged("master"), push(r, "master"))
        assertEquals(
            listOf(
                pre + listOf("git", "branch", "--show-current"),
                pre + listOf("git", "status", "--porcelain", "--untracked-files=no"),
                pre + listOf("git", "fetch", "/run/sandbox/source", "master"),
                pre + listOf("git", "merge", "--no-edit", "FETCH_HEAD"),
            ),
            r.calls,
        )
    }

    @Test fun upToDate() {
        val r = clean().at(pre + listOf("git", "merge"), stdout = "Already up to date.\n")
        assertEquals(PushResult.UpToDate, push(r, "master"))
    }

    @Test fun upToDateOldGit() {
        val r = clean().at(pre + listOf("git", "merge"), stdout = "Already up-to-date.\n")
        assertEquals(PushResult.UpToDate, push(r, "master"))
    }

    /** A merge that stopped mid-way (MERGE_HEAD exists). */
    private fun failingMerge(unmerged: String) = clean()
        .at(pre + listOf("git", "merge", "--no-edit"), stderr = "merge err", exitCode = 1)
        .at(pre + listOf("git", "rev-parse", "-q", "--verify", "MERGE_HEAD"), stdout = "abc\n")
        .at(pre + listOf("git", "diff", "--name-only", "--diff-filter=U"), stdout = unmerged)

    @Test fun abortsOnConflict() {
        val r = failingMerge("a.txt\n")
        assertEquals(PushResult.Conflict("master"), push(r, "master"))
        assertEquals(pre + listOf("git", "merge", "--abort"), r.calls.last())
    }

    @Test fun mergeFailureWithoutConflictIsFailed() {
        val r = failingMerge("")
        assertEquals(PushResult.Failed("merge err"), push(r, "master"))
        assertEquals(pre + listOf("git", "merge", "--abort"), r.calls.last())
    }

    @Test fun mergeThatNeverStartedIsNotAborted() {
        val r = clean()
            .at(pre + listOf("git", "merge", "--no-edit"), stderr = "merge err", exitCode = 1)
            .at(pre + listOf("git", "rev-parse", "-q", "--verify", "MERGE_HEAD"), exitCode = 1)
        assertEquals(PushResult.Failed("merge err"), push(r, "master"))
        assertEquals(pre + listOf("git", "rev-parse", "-q", "--verify", "MERGE_HEAD"), r.calls.last())
        assertFalse(r.calls.any { it.takeLast(2) == listOf("merge", "--abort") })
    }

    @Test fun abortOnlyAfterMergeHeadCheck() {
        val r = failingMerge("a.txt\n")
        push(r, "master")
        val i = r.calls.indexOf(pre + listOf("git", "rev-parse", "-q", "--verify", "MERGE_HEAD"))
        assertTrue(i >= 0 && i < r.calls.indexOf(pre + listOf("git", "merge", "--abort")))
    }

    @Test fun abortFailureIsReported() {
        val r = failingMerge("a.txt\n").at(pre + listOf("git", "merge", "--abort"), stderr = "no abort", exitCode = 1)
        val res = push(r, "master") as PushResult.Failed
        assertTrue(res.stderr.contains("may still be mid-merge") && res.stderr.contains("no abort"))
    }
}

private fun FakeCliRunner.at(prefix: List<String>, stdout: String = "", stderr: String = "", exitCode: Int = 0) =
    on(prefix, CliResult(exitCode, stdout, stderr))
