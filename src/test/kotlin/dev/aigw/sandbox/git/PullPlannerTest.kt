package dev.aigw.sandbox.git

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PullPlannerTest {
    @Test fun parsesHeadsWithSlashes() =
        assertEquals(listOf("master", "fix/xy"), parseLsRemoteHeads("abc\trefs/heads/master\ndef\trefs/heads/fix/xy\n"))

    @Test fun parsesSymrefHead() = assertEquals("fix/xy", parseSymrefHead("ref: refs/heads/fix/xy\tHEAD\nabc\tHEAD\n"))

    @Test fun symrefNullWhenAbsent() = assertNull(parseSymrefHead("abc\tHEAD\n"))

    @Test fun defaultPrefersSameName() = assertEquals("b", defaultPullBranch("b", listOf("a", "b"), "a"))

    @Test fun defaultFallsBackToSandboxHead() = assertEquals("a", defaultPullBranch("zz", listOf("b", "a"), "a"))

    @Test fun defaultFallsBackToFirst() = assertEquals("b", defaultPullBranch(null, listOf("b", "a"), null))

    @Test fun defaultNullWhenNoBranches() = assertNull(defaultPullBranch("b", emptyList(), "a"))

    @Test fun refusesDetachedHead() = assertEquals(
        PullDecision.Refuse("You're on a detached HEAD. Check out the branch to merge into first."), planPull(null, false, "x"),
    )

    @Test fun refusesDirtyTree() = assertEquals(
        PullDecision.Refuse("You have uncommitted changes. Commit them first. Pull never stashes your work."), planPull("m", true, "x"),
    )

    @Test fun refusesNoBranchChosen() = assertEquals(PullDecision.Refuse("No sandbox branch selected."), planPull("m", false, null))

    @Test fun goes() = assertEquals(PullDecision.Go("x", "m"), planPull("m", false, "x"))
}
