package dev.aigw.sandbox.usage

import dev.aigw.sandbox.sbx.Sandbox
import dev.aigw.sandbox.sbx.SandboxStatus
import dev.aigw.sandbox.ui.StatusPlan
import dev.aigw.sandbox.ui.statusPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Finding 1: the status widget decides from a fresh listing, never from the panel's cache. */
class StatusPlanTest {
    private fun sb(st: SandboxStatus, name: String = "p") = Sandbox(name, "claude", st, emptyList())

    @Test
    fun runningChecksSignIn() = assertEquals(StatusPlan.CheckSignIn("p"), statusPlan(listOf(sb(SandboxStatus.RUNNING)), "p", emptySet()))

    @Test
    fun freshListStoppedOutsideTheIdeNeverExecs() {
        // The cache may still say RUNNING; only the fresh listing counts.
        assertEquals(StatusPlan.NoSandbox, statusPlan(listOf(sb(SandboxStatus.STOPPED)), "p", emptySet()))
        assertEquals(StatusPlan.NoSandbox, statusPlan(listOf(sb(SandboxStatus.OTHER)), "p", emptySet()))
        assertEquals(StatusPlan.NoSandbox, statusPlan(emptyList(), "p", emptySet()))
        assertEquals(StatusPlan.NoSandbox, statusPlan(listOf(sb(SandboxStatus.RUNNING, "other")), "p", emptySet()))
    }

    @Test
    fun failedListingNeverExecs() = assertEquals(StatusPlan.Unavailable, statusPlan(null, "p", emptySet()))

    @Test
    fun lifecycleCommandInFlightWaits() =
        assertEquals(StatusPlan.Wait, statusPlan(listOf(sb(SandboxStatus.RUNNING)), "p", setOf("p")))

    @Test
    fun otherSandboxBusyDoesNotMatter() =
        assertEquals(StatusPlan.CheckSignIn("p"), statusPlan(listOf(sb(SandboxStatus.RUNNING)), "p", setOf("q")))

    @Test
    fun sandboxOfAnotherProjectIsNotOurs() =
        assertEquals(StatusPlan.NoSandbox, statusPlan(listOf(sb(SandboxStatus.RUNNING)), "p", emptySet()) { false })
}
