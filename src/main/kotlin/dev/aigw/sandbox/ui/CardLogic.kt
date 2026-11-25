package dev.aigw.sandbox.ui

import dev.aigw.sandbox.git.defaultPullBranch
import dev.aigw.sandbox.sbx.Sandbox
import dev.aigw.sandbox.sbx.SandboxStatus

/** Databricks sign-in state of a sandbox as last checked (`ug auth-token`); UNKNOWN when not running or not checked yet. */
enum class AuthState { UNKNOWN, SIGNED_OUT, SIGNED_IN }

/** Branches of a sandbox's git remote and the one selected in its card (the sandbox side of Pull/Push/Diff). */
data class BranchState(
    val branches: List<String> = emptyList(),
    val selected: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** New branch list: keeps [prev]'s selection while it still exists, else the default (same name as [local], then [head]). */
fun reduceBranches(prev: BranchState?, branches: List<String>, head: String?, local: String?): BranchState {
    val keep = prev?.selected?.takeIf { it in branches }
    return BranchState(branches, keep ?: defaultPullBranch(local, branches, head))
}

/** Auth state after a status change: a sandbox that isn't running has an unknown sign-in. */
fun authOnTransition(prev: AuthState, nowRunning: Boolean): AuthState = if (nowRunning) prev else AuthState.UNKNOWN

data class ControlState(val visible: Boolean, val enabled: Boolean, val text: String, val tooltip: String? = null)

data class CardState(
    val toggleIsStop: Boolean,
    val toggle: ControlState,
    val connect: ControlState,
    val signIn: ControlState,
    val branch: ControlState,
    val pull: ControlState,
    val push: ControlState,
    val diff: ControlState,
    val reset: ControlState,
    val showGitRow: Boolean,
)

/**
 * Everything a sandbox card shows. [lifecycleBusy]: a start/stop/reset command runs for [s]; [execInFlight]: a
 * `sbx exec` operation (sign-in, push, status check) runs for it. [localBranch]: branch checked out in the IDE.
 */
fun cardState(
    s: Sandbox,
    isProject: Boolean,
    lifecycleBusy: Boolean,
    execInFlight: Boolean,
    auth: AuthState,
    branches: BranchState?,
    localBranch: String?,
): CardState {
    val running = s.status == SandboxStatus.RUNNING
    val stopped = s.status == SandboxStatus.STOPPED
    val busyTip = "Another operation is running on ${s.name}"
    val notStartable = "Sandbox is not running or stopped"
    val selected = branches?.selected

    fun ctl(text: String, enabled: Boolean, tip: String?, visible: Boolean = true) =
        if (lifecycleBusy) ControlState(visible, false, text, busyTip) else ControlState(visible, enabled, text, tip)

    val toggle = when {
        execInFlight && !lifecycleBusy -> ControlState(true, false, if (running) "Stop" else "Start", busyTip)
        running -> ctl("Stop", true, "Stop ${s.name}")
        stopped -> ctl("Start", true, "Start ${s.name}")
        else -> ctl("Start", false, notStartable)
    }
    val connect = ctl("Connect", running || stopped, if (running || stopped) "Open Claude in a terminal" else notStartable)

    val signIn = when {
        !running -> ctl("Sign in to Databricks", false, "Start the sandbox to check the sign-in", isProject)
        execInFlight -> ctl(if (auth == AuthState.SIGNED_IN) "Signed in" else "Sign in to Databricks", false, busyTip, isProject)
        auth == AuthState.SIGNED_IN -> ctl("Signed in", false, "Signed in to Databricks", isProject)
        auth == AuthState.SIGNED_OUT -> ctl("Sign in to Databricks", true, "Sign in with your browser", isProject)
        else -> ctl("Sign in to Databricks", false, "Checking the sign-in…", isProject)
    }

    val branchText = selected ?: "No branch"
    val branch = when {
        !running -> ctl(branchText, false, "Start the sandbox to load its branches", isProject)
        branches?.loading == true -> ctl(branchText, false, "Loading branches…", isProject)
        branches?.error != null -> ctl(branchText, false, "Could not load branches: ${branches.error}", isProject)
        branches == null || branches.branches.isEmpty() -> ctl(branchText, false, "The sandbox has no branches", isProject)
        else -> ctl(branchText, true, "Sandbox branch for Pull, Push and Diff", isProject)
    }

    val pull = when {
        stopped -> ctl("Pull", true, "Start the sandbox and pull ${selected ?: localBranch ?: "its branch"}", isProject)
        !running -> ctl("Pull", false, notStartable, isProject)
        selected == null -> ctl("Pull", false, "No sandbox branch selected", isProject)
        else -> ctl("Pull", true, "Merge $selected from the sandbox into your branch", isProject)
    }
    val push = when {
        !running -> ctl("Push", false, "Start the sandbox first", isProject)
        execInFlight -> ctl("Push", false, busyTip, isProject)
        selected == null -> ctl("Push", false, "No sandbox branch selected", isProject)
        localBranch == null -> ctl("Push", false, "You're on a detached HEAD", isProject)
        else -> ctl("Push", true, "Merge $localBranch into the sandbox's $selected", isProject)
    }
    val diff = when {
        !running -> ctl("Diff", false, "Start the sandbox first", isProject)
        selected == null -> ctl("Diff", false, "No sandbox branch selected", isProject)
        else -> ctl("Diff", true, "Show what $selected in the sandbox would bring", isProject)
    }
    val reset = if (execInFlight && !lifecycleBusy) ControlState(true, false, "Reset…", busyTip)
    else ctl("Reset…", true, "Remove the sandbox")

    return CardState(running, toggle, connect, signIn, branch, pull, push, diff, reset, isProject)
}
