package dev.aigw.sandbox.git

import dev.aigw.sandbox.cli.FakeCliRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SandboxGitTest {
    private val repo = Path.of("/repo")
    private val base = listOf("git", "-C", "/repo")
    private fun git(r: FakeCliRunner) = SandboxGit(r, Path.of("git"), repo)

    @Test fun fetchArgv() {
        val r = FakeCliRunner()
        git(r).fetch("ug-claude-myproject", "fix/xy")
        assertEquals(base + listOf("fetch", "sandbox-ug-claude-myproject", "fix/xy"), r.calls.single())
    }

    @Test fun setPushDefaultArgv() {
        val r = FakeCliRunner()
        git(r).setPushDefaultOrigin()
        assertEquals(base + listOf("config", "remote.pushDefault", "origin"), r.calls.single())
    }

    @Test fun remoteBranches() {
        val r = FakeCliRunner().on(base + listOf("ls-remote", "--heads", "sandbox-s"), dev.aigw.sandbox.cli.CliResult(0, "a\trefs/heads/m\n", ""))
        assertEquals(listOf("m"), git(r).remoteBranches("s"))
    }

    @Test fun currentBranchNullWhenDetached() {
        val r = FakeCliRunner().on(base + listOf("symbolic-ref", "--short", "-q", "HEAD"), dev.aigw.sandbox.cli.CliResult(1, "", ""))
        assertNull(git(r).currentBranch())
    }

    @Test fun currentBranchAndDirty() {
        val r = FakeCliRunner()
            .on(base + listOf("symbolic-ref", "--short", "-q", "HEAD"), dev.aigw.sandbox.cli.CliResult(0, "main\n", ""))
            .on(base + listOf("status", "--porcelain", "--untracked-files=no"), dev.aigw.sandbox.cli.CliResult(0, " M a\n", ""))
        assertEquals("main", git(r).currentBranch())
        assertTrue(git(r).isDirty())
    }

    @Test fun countNewAndHasRemote() {
        val r = FakeCliRunner()
            .on(base + listOf("rev-list", "--count", "HEAD..sandbox-s/m"), dev.aigw.sandbox.cli.CliResult(0, "3\n", ""))
            .on(base + listOf("remote"), dev.aigw.sandbox.cli.CliResult(0, "origin\nsandbox-s\n", ""))
        assertEquals(3, git(r).countNew("sandbox-s/m"))
        assertTrue(git(r).hasRemote("s"))
        assertFalse(git(r).hasRemote("sandbox"))
    }

    @Test fun countNewFailsClosed() {
        val r = FakeCliRunner()
            .on(base + listOf("rev-list", "--count", "HEAD..bad"), dev.aigw.sandbox.cli.CliResult(128, "", "bad revision"))
            .on(base + listOf("rev-list", "--count", "HEAD..junk"), dev.aigw.sandbox.cli.CliResult(0, "oops\n", ""))
        assertNull(git(r).countNew("bad"))
        assertNull(git(r).countNew("junk"))
    }

    @Test fun isDirtyFailsClosed() {
        val r = FakeCliRunner().on(base + listOf("status", "--porcelain", "--untracked-files=no"), dev.aigw.sandbox.cli.CliResult(128, "", "fatal"))
        assertTrue(git(r).isDirty())
        val clean = FakeCliRunner()
        assertFalse(git(clean).isDirty())
    }

    @Test fun mergeBaseParsesSha() {
        val r = FakeCliRunner().on(base + listOf("merge-base", "HEAD", "sandbox-s/m"), dev.aigw.sandbox.cli.CliResult(0, "abc123def\n", ""))
        assertEquals("abc123def", git(r).mergeBase("sandbox-s/m"))
    }

    @Test fun mergeBaseFailsClosed() {
        val r = FakeCliRunner()
            .on(base + listOf("merge-base", "HEAD", "bad"), dev.aigw.sandbox.cli.CliResult(1, "", ""))
            .on(base + listOf("merge-base", "HEAD", "blank"), dev.aigw.sandbox.cli.CliResult(0, "\n", ""))
        assertNull(git(r).mergeBase("bad"))
        assertNull(git(r).mergeBase("blank"))
    }
}
