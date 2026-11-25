package dev.aigw.sandbox.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import dev.aigw.sandbox.auth.SignIn
import dev.aigw.sandbox.cli.CliResult
import dev.aigw.sandbox.sbx.Sandbox
import dev.aigw.sandbox.sbx.SandboxStatus
import dev.aigw.sandbox.usage.USAGE_NOT_AVAILABLE
import dev.aigw.sandbox.usage.summarizeUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.event.MouseEvent

private const val WIDGET_ID = "AigwStatus"
private const val NO_SANDBOX = "Databricks: no running sandbox"
private const val SIGNED_OUT = "Databricks: signed out"
private const val REFRESH_MS = 5 * 60_000L
private const val USAGE_TIMEOUT_MS = 30_000L

class AigwStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "AIGW Sandbox"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = AigwStatusWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

/**
 * What a status refresh may do. Decided from a fresh `sbx ls` right before each `sbx exec`: only a sandbox that is
 * RUNNING now and has no lifecycle command in flight is ever exec'd into (exec would wake a stopped one).
 */
sealed interface StatusPlan {
    /** `sbx ls` failed: don't exec, say the status is unknown. */
    data object Unavailable : StatusPlan
    data object NoSandbox : StatusPlan

    /** A start/stop/reset is running: don't exec now, keep the current text (the end of the command triggers a refresh). */
    data object Wait : StatusPlan
    data class CheckSignIn(val name: String) : StatusPlan
}

/** [fresh] is the listing just taken (null if it failed); [isOurs] picks the project sandbox named [name]. */
fun statusPlan(fresh: List<Sandbox>?, name: String, busy: Set<String>, isOurs: (Sandbox) -> Boolean = { true }): StatusPlan {
    fresh ?: return StatusPlan.Unavailable
    val s = fresh.firstOrNull { it.name == name && isOurs(it) } ?: return StatusPlan.NoSandbox
    return when {
        s.status != SandboxStatus.RUNNING -> StatusPlan.NoSandbox
        name in busy -> StatusPlan.Wait
        else -> StatusPlan.CheckSignIn(name)
    }
}

private class AigwStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private val service = SandboxService.getInstance(project)
    private val scope: CoroutineScope = service.childScope("AigwStatusWidget")
    private val triggers = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var statusBar: StatusBar? = null
    @Volatile private var text = NO_SANDBOX
    @Volatile private var tooltip: String? = null
    @Volatile private var state = AuthState.UNKNOWN

    private val authListener: () -> Unit = { requestRefresh() }
    private val sandboxListener: (Sandbox) -> Unit = { requestRefresh() }

    override fun ID(): String = WIDGET_ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText(): String = text
    override fun getAlignment(): Float = 0.5f
    override fun getTooltipText(): String? = tooltip

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        AigwNotifications.guard(project, "AIGW status") {
            val s = projectSandbox()
            if (s != null && s.status == SandboxStatus.RUNNING && state == AuthState.SIGNED_OUT) {
                service.signIn(s) // checks busy / in-flight / running itself
            } else {
                ToolWindowManager.getInstance(project).getToolWindow("AIGW Sandboxes")?.activate(null)
            }
        }
    }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        service.authChanged += authListener
        service.onStarted += sandboxListener
        service.onStopped += sandboxListener
        scope.launch(Dispatchers.IO) {
            for (x in triggers) compute()
        }
        scope.launch {
            while (isActive) {
                requestRefresh()
                delay(REFRESH_MS)
            }
        }
        // A check skipped because of a start/stop/reset runs again when that command ends.
        scope.launch {
            service.busy.collect { requestRefresh() }
        }
    }

    override fun dispose() {
        service.authChanged -= authListener
        service.onStarted -= sandboxListener
        service.onStopped -= sandboxListener
        statusBar = null
        triggers.close()
        scope.cancel()
    }

    private fun projectSandbox(): Sandbox? = service.sandboxes.value.firstOrNull { service.isNamedProjectSandbox(it) }

    /** A fresh `sbx ls`, then the plan for it. */
    private suspend fun freshPlan(): StatusPlan =
        statusPlan(service.refreshAndGet(), service.projectSandboxName(), service.busy.value, service::isProjectSandbox)

    private fun requestRefresh() {
        triggers.trySend(Unit)
    }

    private suspend fun compute() {
        val name = when (val plan = freshPlan()) {
            StatusPlan.Unavailable -> return show(AuthState.UNKNOWN, "Databricks: status unavailable", service.listError.value)
            StatusPlan.NoSandbox -> return show(AuthState.UNKNOWN, NO_SANDBOX, null)
            StatusPlan.Wait -> return
            is StatusPlan.CheckSignIn -> plan.name
        }
        // Holding an exec operation keeps our own Stop/Reset out until the checks are done.
        if (!service.beginExec(name)) return
        try {
            val sbx = service.sbx()
            val signedIn = SignIn(sbx).isSignedIn(name)
            service.setAuth(name, if (signedIn) AuthState.SIGNED_IN else AuthState.SIGNED_OUT)
            if (!signedIn) return show(AuthState.SIGNED_OUT, SIGNED_OUT, null)
            // Re-check right before the next exec: the sandbox may have been stopped outside the IDE meanwhile.
            when (freshPlan()) {
                is StatusPlan.CheckSignIn -> {}
                StatusPlan.NoSandbox -> return show(AuthState.UNKNOWN, NO_SANDBOX, null)
                else -> return show(AuthState.SIGNED_IN, "Databricks: signed in · $USAGE_NOT_AVAILABLE", null)
            }
            val r = sbx.exec(name, listOf("ug", "usage"), timeoutMs = USAGE_TIMEOUT_MS)
            show(AuthState.SIGNED_IN, "Databricks: signed in · ${summarizeUsage(r)}", tooltipFor(r))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            show(AuthState.UNKNOWN, "Databricks: status unavailable", e.message)
        } finally {
            service.endExec(name)
        }
    }

    private fun show(newState: AuthState, newText: String, newTip: String?) {
        state = newState
        text = newText
        tooltip = newTip
        val bar = statusBar ?: return
        ApplicationManager.getApplication().invokeLater { bar.updateWidget(WIDGET_ID) }
    }

    private fun tooltipFor(r: CliResult): String =
        if (r.exitCode != 0) USAGE_NOT_AVAILABLE
        else stripAnsi(r.stdout).trim().ifEmpty { USAGE_NOT_AVAILABLE }
}
