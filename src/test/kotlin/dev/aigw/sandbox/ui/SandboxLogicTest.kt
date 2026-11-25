package dev.aigw.sandbox.ui

import dev.aigw.sandbox.sbx.Sandbox
import dev.aigw.sandbox.sbx.SandboxStatus
import dev.aigw.sandbox.sbx.SandboxStatus.OTHER
import dev.aigw.sandbox.sbx.SandboxStatus.RUNNING
import dev.aigw.sandbox.sbx.SandboxStatus.STOPPED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SandboxLogicTest {
    private fun sb(name: String, status: SandboxStatus) = Sandbox(name, "claude", status, emptyList())
    private val isProject: (Sandbox) -> Boolean = { it.name == "ug-claude-myproject" }
    private val none = emptyList<Sandbox>() to emptyList<Sandbox>()

    @Test
    fun firstRefreshWithRunningProjectSandboxIsStarted() {
        val p = sb("ug-claude-myproject", RUNNING)
        assertEquals(listOf(p) to emptyList<Sandbox>(), transitions(null, listOf(p, sb("other", RUNNING)), isProject))
    }

    @Test
    fun firstRefreshWithStoppedProjectSandboxIsNothing() {
        assertEquals(none, transitions(null, listOf(sb("ug-claude-myproject", STOPPED)), isProject))
    }

    @Test
    fun runningToStoppedIsStopped() {
        val stopped = sb("ug-claude-myproject", STOPPED)
        assertEquals(
            emptyList<Sandbox>() to listOf(stopped),
            transitions(listOf(sb("ug-claude-myproject", RUNNING)), listOf(stopped), isProject),
        )
    }

    @Test
    fun runningToOtherIsStopped() {
        val other = sb("ug-claude-myproject", OTHER)
        assertEquals(
            emptyList<Sandbox>() to listOf(other),
            transitions(listOf(sb("ug-claude-myproject", RUNNING)), listOf(other), isProject),
        )
    }

    @Test
    fun stoppedToRunningAndNewlyCreatedRunningAreStarted() {
        val p = sb("ug-claude-myproject", RUNNING)
        assertEquals(listOf(p) to emptyList<Sandbox>(), transitions(listOf(sb("ug-claude-myproject", STOPPED)), listOf(p), isProject))
        assertEquals(listOf(p) to emptyList<Sandbox>(), transitions(emptyList(), listOf(p), isProject))
    }

    @Test
    fun removedWhileRunningIsStoppedWithStoppedStatus() {
        assertEquals(
            emptyList<Sandbox>() to listOf(sb("ug-claude-myproject", STOPPED)),
            transitions(listOf(sb("ug-claude-myproject", RUNNING)), emptyList(), isProject),
        )
    }

    @Test
    fun nonProjectTransitionsAreIgnored() {
        assertEquals(none, transitions(listOf(sb("other", RUNNING)), listOf(sb("other", STOPPED)), isProject))
        assertEquals(none, transitions(listOf(sb("other", STOPPED)), listOf(sb("other", RUNNING)), isProject))
        assertEquals(none, transitions(null, listOf(sb("other", RUNNING)), isProject))
    }

    @Test
    fun unchangedIsNothing() {
        val list = listOf(sb("ug-claude-myproject", RUNNING), sb("other", STOPPED))
        assertEquals(none, transitions(list, list, isProject))
        val stopped = listOf(sb("ug-claude-myproject", STOPPED))
        assertEquals(none, transitions(stopped, stopped, isProject))
    }

    @Test
    fun withStatusReplacesOrAppends() {
        val a = sb("a", STOPPED)
        val b = sb("b", STOPPED)
        assertEquals(listOf(a, b.copy(status = RUNNING)), withStatus(listOf(a, b), b, RUNNING))
        assertEquals(listOf(a, b.copy(status = RUNNING)), withStatus(listOf(a), b, RUNNING))
        assertEquals(listOf(b.copy(status = RUNNING)), withStatus(null, b, RUNNING))
    }

    @Test
    fun markingAfterServiceStartDeduplicatesTheNextRefresh() {
        val stopped = sb("ug-claude-myproject", STOPPED)
        val afterStart = withStatus(listOf(stopped), stopped, RUNNING)
        assertEquals(listOf(stopped.copy(status = RUNNING)) to emptyList<Sandbox>(), transitions(listOf(stopped), afterStart, isProject))
        // the following refresh observes the same state: no second "started"
        assertEquals(none, transitions(afterStart, listOf(stopped.copy(status = RUNNING)), isProject))
    }

    @Test
    fun needsForceOnlyOnFailureMentioningForce() {
        assertTrue(needsForce(1, "plan changed; rerun with --force to apply"))
        assertFalse(needsForce(0, "use --force next time"))
        assertFalse(needsForce(1, "network error"))
        assertFalse(needsForce(2, "--forced-thing")) // not the flag itself
        assertTrue(needsForce(2, "pass --force."))
    }

    @Test
    fun declaredUnionReadsEachFileAndReportsMissing() {
        val files = mapOf(
            "sbxenv.yaml" to "args:\n  agent: {}\n  sandbox: {}\n",
            "sandbox_config/sbxenv.ug-claude.yaml" to "args:\n  ugWorkspace: {}\n",
        )
        val r = declaredUnion(listOf("sbxenv.yaml", "missing.yaml", "sandbox_config/sbxenv.ug-claude.yaml")) { files[it] }
        assertEquals(setOf("agent", "sandbox", "ugWorkspace"), r.declared)
        assertEquals(listOf("missing.yaml"), r.missing)
    }

    @Test
    fun notificationTextEscapesStripsAnsiAndKeepsTail() {
        assertEquals("a &lt;b&gt; &amp; c<br>next", notificationText("\u001B[31ma <b> & c\u001B[0m\nnext\n"))
        assertEquals("…789", notificationText("123456789", max = 3))
        assertEquals("", notificationText("  \n "))
    }
}

class PendingMarksTest {
    private fun sb(status: SandboxStatus) = Sandbox("x", "claude", status, emptyList())

    @Test
    fun contraryObservationWithinWindowIsReplaced() {
        val m = PendingMarks().mark("x", STOPPED, 1000)
        val (out, next) = m.filter(listOf(sb(RUNNING)), 1000 + 5_000)
        assertEquals(listOf(sb(STOPPED)), out)
        assertFalse(next.isEmpty())
    }

    @Test
    fun matchingObservationClearsMark() {
        val m = PendingMarks().mark("x", STOPPED, 1000)
        val (out, next) = m.filter(listOf(sb(STOPPED)), 2000)
        assertEquals(listOf(sb(STOPPED)), out)
        assertTrue(next.isEmpty())
    }

    @Test
    fun observationWinsAfterWindow() {
        val m = PendingMarks().mark("x", STOPPED, 1000)
        val (out, next) = m.filter(listOf(sb(RUNNING)), 1000 + 20_000)
        assertEquals(listOf(sb(RUNNING)), out)
        assertTrue(next.isEmpty())
    }

    @Test
    fun missingSandboxKeepsMarkUntilExpiry() {
        val m = PendingMarks().mark("x", RUNNING, 0)
        assertFalse(m.filter(emptyList(), 100).second.isEmpty())
        assertTrue(m.filter(emptyList(), 30_000).second.isEmpty())
    }
}

/** Project matching (finding 7), busy / in-flight gating (finding 3) and small helpers. */
class SandboxRulesTest {
    private fun sb(name: String, status: SandboxStatus) = Sandbox(name, "claude", status, emptyList())

    // ---- project match (finding 7) ----

    private val base = "/Users/me/IdeaProjects/myproject"
    private fun ws(name: String, vararg w: String) = Sandbox(name, "claude", RUNNING, w.toList())

    @Test
    fun nameMatchesOnlyWithoutWorkspaces() {
        assertTrue(matchesProject(ws("ug-claude-myproject"), "ug-claude-myproject", base))
        assertTrue(matchesProject(ws("ug-claude-myproject", "$base/"), "ug-claude-myproject", base))
        assertFalse(matchesProject(ws("ug-claude-myproject", "/Users/me/other/myproject"), "ug-claude-myproject", base))
        assertTrue(matchesProject(ws("custom", base), "ug-claude-myproject", base))
        assertFalse(matchesProject(ws("other"), "ug-claude-myproject", base))
        assertTrue(matchesProject(ws("ug-claude-myproject", "/x"), "ug-claude-myproject", null))
    }

    @Test
    fun foreignNamedSandboxIsReported() {
        val foreign = ws("ug-claude-myproject", "/Users/me/other/myproject")
        assertEquals(foreign, foreignNamedSandbox(listOf(ws("x", base), foreign), "ug-claude-myproject", base))
        assertEquals(null, foreignNamedSandbox(listOf(ws("ug-claude-myproject", base)), "ug-claude-myproject", base))
        assertEquals(null, foreignNamedSandbox(listOf(ws("ug-claude-myproject")), "ug-claude-myproject", base))
    }

    // ---- busy / in-flight gating (finding 3) ----

    @Test
    fun lifecycleNeedsNoBusyAndNoInFlight() {
        val s = sb("a", RUNNING)
        assertTrue(opAllowed(OpKind.LIFECYCLE, s, emptySet(), emptySet()))
        assertFalse(opAllowed(OpKind.LIFECYCLE, s, setOf("a"), emptySet()))
        assertFalse(opAllowed(OpKind.LIFECYCLE, s, emptySet(), setOf("a")), "Stop while Push/sign-in runs")
        assertTrue(opAllowed(OpKind.LIFECYCLE, s, setOf("b"), setOf("b")))
    }

    @Test
    fun execNeedsRunningAndNoLifecycle() {
        assertTrue(opAllowed(OpKind.EXEC, sb("a", RUNNING), emptySet(), setOf("a")))
        assertFalse(opAllowed(OpKind.EXEC, sb("a", RUNNING), setOf("a"), emptySet()))
        assertFalse(opAllowed(OpKind.EXEC, sb("a", STOPPED), emptySet(), emptySet()))
        assertFalse(opAllowed(OpKind.EXEC, sb("a", OTHER), emptySet(), emptySet()))
    }

    @Test
    fun hostOpsOnlyWaitForLifecycle() {
        assertTrue(opAllowed(OpKind.HOST, sb("a", STOPPED), emptySet(), setOf("a")))
        assertFalse(opAllowed(OpKind.HOST, sb("a", RUNNING), setOf("a"), emptySet()))
    }

    @Test
    fun opsExcludeLifecycleWhileExecInFlight() {
        val seen = mutableListOf<Pair<Set<String>, Set<String>>>()
        val ops = SandboxOps { b, f -> seen += b to f }
        assertTrue(ops.beginExec("a"))
        assertTrue(ops.beginExec("a")) // push + status check may overlap
        assertFalse(ops.beginLifecycle("a"))
        ops.endExec("a")
        assertFalse(ops.beginLifecycle("a"), "one exec still in flight")
        ops.endExec("a")
        assertTrue(ops.beginLifecycle("a"))
        assertFalse(ops.beginExec("a"))
        assertFalse(ops.beginLifecycle("a"))
        assertTrue(ops.beginExec("b"))
        ops.endLifecycle("a")
        assertEquals(emptySet<String>() to setOf("b"), seen.last())
    }

    @Test
    fun handOffKeepsStopOutUntilTheQueuedSignInEnds() {
        val ops = SandboxOps()
        assertTrue(ops.beginLifecycle("a")) // Start
        ops.handOffToExec("a")             // Start done, auto sign-in queued
        assertEquals(emptySet<String>(), ops.busy())
        assertFalse(ops.beginLifecycle("a"), "Stop must wait for the auto sign-in")
        assertTrue(ops.beginExec("a"), "exec ops may run meanwhile")
        ops.endExec("a")
        ops.endExec("a")                   // auto sign-in done
        assertTrue(ops.beginLifecycle("a"))
    }

    @Test
    fun endExecWithoutBeginIsHarmless() {
        val ops = SandboxOps()
        ops.endExec("a")
        ops.endLifecycle("a")
        assertTrue(ops.beginLifecycle("a"))
    }

    // ---- misc ----

    @Test
    fun commitCountGrammar() {
        assertEquals("1 commit", commits(1))
        assertEquals("2 commits", commits(2))
        assertEquals("0 commits", commits(0))
    }

    @Test
    fun cancellationIsNotCaught() {
        assertTrue(runCatchingCancellable { error("x") }.isFailure)
        assertEquals(3, runCatchingCancellable { 3 }.getOrNull())
        org.junit.jupiter.api.assertThrows<java.util.concurrent.CancellationException> {
            runCatchingCancellable { throw java.util.concurrent.CancellationException("c") }
        }
        org.junit.jupiter.api.assertThrows<com.intellij.openapi.progress.ProcessCanceledException> {
            runCatchingCancellable { throw com.intellij.openapi.progress.ProcessCanceledException() }
        }
    }
}
