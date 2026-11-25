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

class CardLogicTest {
    private fun sb(status: SandboxStatus) = Sandbox("sb", "claude", status, listOf("/p"))
    private val branches = BranchState(listOf("master", "fix/xy"), selected = "master")

    private fun card(
        status: SandboxStatus,
        auth: AuthState = AuthState.SIGNED_IN,
        isProject: Boolean = true,
        lifecycleBusy: Boolean = false,
        execInFlight: Boolean = false,
        b: BranchState? = branches,
        local: String? = "master",
    ) = cardState(sb(status), isProject, lifecycleBusy, execInFlight, auth, b, local)

    @Test fun runningSignedInProjectSandbox() {
        val c = card(RUNNING)
        assertTrue(c.toggleIsStop)
        assertEquals("Stop", c.toggle.text)
        assertTrue(c.toggle.enabled)
        assertTrue(c.connect.enabled)
        assertEquals(ControlState(true, false, "Signed in", "Signed in to Databricks"), c.signIn)
        assertTrue(c.showGitRow)
        assertTrue(c.branch.enabled)
        assertTrue(c.pull.enabled && c.push.enabled && c.diff.enabled)
        assertTrue(c.reset.enabled)
    }

    @Test fun runningSignedOut() {
        val c = card(RUNNING, auth = AuthState.SIGNED_OUT)
        assertEquals(ControlState(true, true, "Sign in to Databricks", "Sign in with your browser"), c.signIn)
    }

    @Test fun runningAuthUnknownWaitsForCheck() {
        val c = card(RUNNING, auth = AuthState.UNKNOWN)
        assertEquals(ControlState(true, false, "Sign in to Databricks", "Checking the sign-in…"), c.signIn)
    }

    @Test fun stoppedProjectSandbox() {
        val c = card(STOPPED, auth = AuthState.UNKNOWN)
        assertFalse(c.toggleIsStop)
        assertEquals("Start", c.toggle.text)
        assertTrue(c.toggle.enabled)
        assertTrue(c.connect.enabled)
        assertEquals(ControlState(true, false, "Sign in to Databricks", "Start the sandbox to check the sign-in"), c.signIn)
        assertEquals(ControlState(true, false, "master", "Start the sandbox to load its branches"), c.branch)
        assertTrue(c.pull.enabled)
        assertEquals("Start the sandbox and pull master", c.pull.tooltip)
        assertEquals(ControlState(true, false, "Push", "Start the sandbox first"), c.push)
        assertEquals(ControlState(true, false, "Diff", "Start the sandbox first"), c.diff)
    }

    @Test fun otherStatusCannotStart() {
        val c = card(OTHER)
        assertFalse(c.toggle.enabled)
        assertEquals("Sandbox is not running or stopped", c.toggle.tooltip)
        assertFalse(c.connect.enabled)
    }

    @Test fun lifecycleBusyDisablesEverything() {
        val c = card(RUNNING, lifecycleBusy = true)
        val busy = "Another operation is running on sb"
        listOf(c.toggle, c.connect, c.signIn, c.branch, c.pull, c.push, c.diff, c.reset).forEach {
            assertFalse(it.enabled, it.text)
            assertEquals(busy, it.tooltip, it.text)
        }
    }

    @Test fun execInFlightKeepsConnectPullDiff() {
        val c = card(RUNNING, execInFlight = true)
        assertFalse(c.toggle.enabled)
        assertFalse(c.reset.enabled)
        assertFalse(c.signIn.enabled)
        assertFalse(c.push.enabled)
        assertTrue(c.connect.enabled)
        assertTrue(c.pull.enabled)
        assertTrue(c.diff.enabled)
    }

    @Test fun nonProjectHasNoGitRowNoSignIn() {
        val c = card(RUNNING, isProject = false)
        assertFalse(c.showGitRow)
        assertFalse(c.signIn.visible)
        assertTrue(c.toggle.enabled)
        assertTrue(c.connect.enabled)
    }

    @Test fun detachedHeadDisablesPush() {
        val c = card(RUNNING, local = null)
        assertEquals(ControlState(true, false, "Push", "You're on a detached HEAD"), c.push)
        assertTrue(c.pull.enabled)
    }

    @Test fun noBranchesOrLoading() {
        assertEquals("The sandbox has no branches", card(RUNNING, b = BranchState()).branch.tooltip)
        assertFalse(card(RUNNING, b = BranchState()).pull.enabled)
        val loading = card(RUNNING, b = branches.copy(loading = true)).branch
        assertEquals(ControlState(true, false, "master", "Loading branches…"), loading)
    }

    @Test fun branchLoadErrorShown() {
        val c = card(RUNNING, b = BranchState(error = "git not found"))
        assertEquals(ControlState(true, false, "No branch", "Could not load branches: git not found"), c.branch)
    }

    @Test fun authResetOnStop() {
        assertEquals(AuthState.UNKNOWN, authOnTransition(AuthState.SIGNED_IN, nowRunning = false))
        assertEquals(AuthState.SIGNED_OUT, authOnTransition(AuthState.SIGNED_OUT, nowRunning = true))
    }
}
