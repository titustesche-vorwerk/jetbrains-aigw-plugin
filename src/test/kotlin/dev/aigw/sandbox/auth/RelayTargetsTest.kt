package dev.aigw.sandbox.auth

import dev.aigw.sandbox.cli.CliResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayTargetsTest {
    private val fwd: (String) -> CliResult = { CliResult(0, "", "") }

    @Test
    fun pendingWins() {
        val t = RelayTargets()
        t.register("a", forward = fwd); t.register("b", forward = fwd)
        t.markPending("b")
        assertEquals(listOf("b"), t.candidates())
        t.clearPending("b")
        assertEquals(listOf("a", "b"), t.candidates())
    }

    @Test
    fun singleRegisteredIsTarget() {
        val t = RelayTargets()
        assertTrue(t.isEmpty)
        assertEquals(emptyList<String>(), t.candidates())
        t.register("a", forward = fwd)
        assertEquals(listOf("a"), t.candidates())
    }

    @Test
    fun twoWithoutPendingAreBothCandidatesInRegistrationOrder() {
        val t = RelayTargets()
        t.register("b", forward = fwd); t.register("a", forward = fwd)
        assertEquals(listOf("b", "a"), t.candidates())
    }

    @Test
    fun pendingThatIsNotRegisteredIsIgnored() {
        val t = RelayTargets()
        t.register("a", forward = fwd)
        t.markPending("x")
        assertEquals(listOf("a"), t.candidates())
    }

    @Test
    fun unregisterClearsPending() {
        val t = RelayTargets()
        t.register("a", forward = fwd); t.register("b", forward = fwd)
        t.markPending("a")
        t.unregister("a")
        assertEquals(listOf("b"), t.candidates())
        t.unregister("b")
        assertTrue(t.isEmpty)
    }

    @Test
    fun problemHandlerIsPerTargetAndGoneAfterUnregister() {
        val t = RelayTargets()
        var a = 0
        t.register("a", { a++ }, fwd); t.register("b", {}, fwd)
        t.problemHandlerFor("a")!!(RelayOutcome.Rejected)
        assertEquals(1, a)
        t.unregister("a")
        assertNull(t.problemHandlerFor("a"))
    }
}
