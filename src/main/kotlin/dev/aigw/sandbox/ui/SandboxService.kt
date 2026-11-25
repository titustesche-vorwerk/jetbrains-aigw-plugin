package dev.aigw.sandbox.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.coroutines.childScope
import dev.aigw.sandbox.auth.CallbackRelay
import dev.aigw.sandbox.auth.RelayOutcome
import dev.aigw.sandbox.auth.RelayRegistrar
import dev.aigw.sandbox.auth.SignIn
import dev.aigw.sandbox.auth.extractAuthorizeUrl
import dev.aigw.sandbox.auth.normalizeHost
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.runBlockingCancellable
import dev.aigw.sandbox.settings.AigwConfigurable
import java.util.concurrent.atomic.AtomicBoolean
import dev.aigw.sandbox.cli.CliResult
import dev.aigw.sandbox.cli.ProcessCliRunner
import dev.aigw.sandbox.cli.ToolNotFound
import dev.aigw.sandbox.git.SandboxGit
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import dev.aigw.sandbox.sbx.Sandbox
import dev.aigw.sandbox.sbx.SandboxStatus
import dev.aigw.sandbox.sbx.SbxClient
import dev.aigw.sandbox.sbx.SbxException
import dev.aigw.sandbox.sbx.sortForProject
import dev.aigw.sandbox.settings.AigwAppSettings
import dev.aigw.sandbox.settings.AigwProjectSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import dev.aigw.sandbox.sbx.envArgv as buildEnvArgv

/** Balloon notifications in the "AIGW Sandbox" group. [content] is HTML; pass CLI output through [notificationText]. */
object AigwNotifications {
    const val GROUP = "AIGW Sandbox"

    fun notify(project: Project, type: NotificationType, title: String, content: String, vararg actions: NotificationAction) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(title, content, type)
        actions.forEach { n.addAction(it) }
        n.notify(project)
    }

    fun error(project: Project, title: String, rawText: String) =
        notify(project, NotificationType.ERROR, title, notificationText(rawText))

    fun info(project: Project, title: String, content: String = "", vararg actions: NotificationAction) =
        notify(project, NotificationType.INFORMATION, title, content, *actions)

    /**
     * For EDT entry points that don't hold the write-intent lock (Swing button/menu listeners, notification actions,
     * status bar clicks, invokeLater continuations): runs [block] with it, and turns any exception into an error
     * notification titled "[title] failed" instead of a silent no-op.
     */
    fun guard(project: Project, title: String, block: () -> Unit) {
        try {
            WriteIntentReadAction.run(Runnable(block))
        } catch (e: ProcessCanceledException) {
            // cancelled by the user
        } catch (e: CancellationException) {
            // cancelled
        } catch (e: Exception) {
            if (e !is ToolNotFound) thisLogger().warn(title, e)
            if (!project.isDisposed) error(project, "$title failed", e.message ?: e.toString())
        }
    }

    /** A notification action whose handler runs through [guard]. */
    fun action(project: Project, text: String, block: () -> Unit): NotificationAction =
        NotificationAction.createSimpleExpiring(text) { guard(project, text, block) }
}

/** Saves all editor documents; safe from any EDT caller, with or without the write-intent lock. */
fun saveAllDocuments() = WriteIntentReadAction.run(Runnable { FileDocumentManager.getInstance().saveAllDocuments() })

/**
 * Lists the user's sandboxes and runs lifecycle commands for them. All CLI work runs off the EDT in [cs].
 *
 * The "project sandbox" (managed through `sbx env run/rm` with the project's env files) is the one named
 * [projectSandboxName]; [isProjectSandbox] additionally accepts sandboxes whose workspace is the project dir.
 */
@Service(Service.Level.PROJECT)
class SandboxService(private val project: Project, private val cs: CoroutineScope) : Disposable {
    private val _sandboxes = MutableStateFlow<List<Sandbox>>(emptyList())

    /** Last `sbx ls` result, project sandboxes first ([sortForProject]). */
    val sandboxes: StateFlow<List<Sandbox>> = _sandboxes.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** True once a `sbx ls` succeeded. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())

    /** Names of sandboxes with a lifecycle command (start/stop/reset) in flight. */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())

    /** Names of sandboxes with an exec operation (sign-in, push, status check) in flight. Start/Stop/Reset wait for them. */
    val inFlight: StateFlow<Set<String>> = _inFlight.asStateFlow()

    private val ops = SandboxOps { b, f ->
        _busy.value = b
        _inFlight.value = f
    }

    /**
     * Fired (on a background thread) when a project sandbox is observed to become running: on the first refresh that
     * sees it running, on later running transitions, and right after a successful [start]. Each transition fires once,
     * and all start/stop hooks run one at a time in the order the transitions were observed.
     */
    val onStarted: MutableList<(Sandbox) -> Unit> = CopyOnWriteArrayList()

    /** Like [onStarted] for running → stopped/other/removed (after a successful [stop] or [reset], or observed). */
    val onStopped: MutableList<(Sandbox) -> Unit> = CopyOnWriteArrayList()

    /** Fired (background thread) after a successful [signIn]; the status bar refreshes from it. */
    val authChanged: MutableList<() -> Unit> = CopyOnWriteArrayList()

    private val signingIn = ConcurrentHashMap.newKeySet<String>()

    private val _auth = MutableStateFlow<Map<String, AuthState>>(emptyMap())

    /** Last known Databricks sign-in state per sandbox (absent = [AuthState.UNKNOWN]). */
    val auth: StateFlow<Map<String, AuthState>> = _auth.asStateFlow()

    private val _branches = MutableStateFlow<Map<String, BranchState>>(emptyMap())

    /** Branches of each running project sandbox and the one selected on its card (sandbox side of Pull/Push/Diff). */
    val branches: StateFlow<Map<String, BranchState>> = _branches.asStateFlow()

    private val _localBranch = MutableStateFlow<String?>(null)

    /** Branch checked out in the IDE (null: detached HEAD or not known yet). */
    val localBranch: StateFlow<String?> = _localBranch.asStateFlow()

    val pullAction = PullAction(this)
    val pushAction = PushAction(this)
    val diffAction = DiffAction(this)
    val connectAction = ConnectAction(this)

    private val mutex = Mutex()
    private var observed: List<Sandbox>? = null // guarded by mutex
    private var marks = PendingMarks() // guarded by mutex
    private var pollJob: Job? = null // guarded by this
    private val _listError = MutableStateFlow<String?>(null)

    /** Message of the last failed `sbx ls`, or null after a successful one. */
    val listError: StateFlow<String?> = _listError.asStateFlow()
    private val warnedMissingEnvFiles = ConcurrentHashMap.newKeySet<String>()
    private val warnedForeign = ConcurrentHashMap.newKeySet<String>()

    private val registrar = RelayRegistrar(
        unpublish = ::unpublishCallbackPort,
        register = { name ->
            val port = AigwAppSettings.getInstance().callbackPort()
            CallbackRelay.getInstance().targets.register(name, ::reportRelayProblem) { pq ->
                sbx().exec(name, listOf("curl", "-fs", "--max-time", "10", "http://localhost:$port$pq"))
            }
        },
        unregister = { CallbackRelay.getInstance().targets.unregister(it) },
    )

    private sealed interface LifecycleEvent {
        data class Started(val s: Sandbox) : LifecycleEvent
        data class Stopped(val s: Sandbox) : LifecycleEvent

        /** Queued after our own successful Start/Create; carries one exec operation of [name] ([SandboxOps.handOffToExec]). */
        data class AutoSignIn(val name: String) : LifecycleEvent
    }

    /** Filled under [mutex] in observation order, consumed by one coroutine, so hooks never run out of order. */
    private val events = SerialQueue<LifecycleEvent>(cs, { e, x -> thisLogger().error("Sandbox lifecycle event failed: $e", x) }) { e ->
        when (e) {
            is LifecycleEvent.Started -> {
                runHook(::relayStarted, e.s)
                onStarted.forEach { hook -> runHook(hook, e.s) }
                refreshBranches(e.s.name)
                refreshAuth(e.s.name)
            }
            is LifecycleEvent.Stopped -> {
                runHook(::relayStopped, e.s)
                onStopped.forEach { hook -> runHook(hook, e.s) }
                _auth.update { it + (e.s.name to authOnTransition(it[e.s.name] ?: AuthState.UNKNOWN, nowRunning = false)) }
                _branches.update { m -> m[e.s.name]?.let { m + (e.s.name to it.copy(loading = false)) } ?: m }
            }
            is LifecycleEvent.AutoSignIn -> autoSignIn(e.name)
        }
    }

    init {
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repo ->
            if (repo.root.path == project.basePath) _localBranch.value = repo.currentBranchName
        })
    }

    fun setAuth(name: String, a: AuthState) = _auth.update { it + (name to a) }

    fun selectBranch(name: String, branch: String) =
        _branches.update { m -> m + (name to (m[name] ?: BranchState()).copy(selected = branch)) }

    /** Reloads [name]'s branch list (and the local branch) in the background; no-op unless it is RUNNING. Host git only. */
    fun refreshBranches(name: String) {
        if (_sandboxes.value.none { it.name == name && it.status == SandboxStatus.RUNNING }) return
        val dir = projectDir() ?: return
        _branches.update { m -> m + (name to (m[name] ?: BranchState()).copy(loading = true, error = null)) }
        cs.launch(Dispatchers.IO) {
            try {
                val git = SandboxGit(ProcessCliRunner(), AigwAppSettings.getInstance().toolPaths().require("git"), dir)
                val local = git.currentBranch()
                _localBranch.value = local
                val (list, head) = if (git.hasRemote(name)) git.remoteBranches(name) to git.remoteHead(name) else emptyList<String>() to null
                _branches.update { m -> m + (name to reduceBranches(m[name], list, head, local)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is ToolNotFound) thisLogger().warn("Loading branches of $name failed", e)
                _branches.update { m -> m + (name to (m[name] ?: BranchState()).copy(loading = false, error = e.message ?: e.toString())) }
            }
        }
    }

    /** Checks [name]'s sign-in in the background (`ug auth-token`, exit code only) if it is RUNNING right now. */
    private fun refreshAuth(name: String) {
        if (!beginExec(name)) return
        cs.launch(Dispatchers.IO) {
            try {
                if (isRunningNow(name)) setAuth(name, if (SignIn(sbx()).isSignedIn(name)) AuthState.SIGNED_IN else AuthState.SIGNED_OUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Sign-in check failed for $name", e)
            } finally {
                endExec(name)
            }
        }
    }

    /** Runs [block] (background thread) once when [name] is next observed to start; gives up after 10 minutes. */
    fun onceStarted(name: String, block: () -> Unit) {
        lateinit var hook: (Sandbox) -> Unit
        hook = { s ->
            if (s.name == name && onStarted.remove(hook)) block()
        }
        onStarted += hook
        cs.launch {
            delay(START_WAIT_MS)
            onStarted.remove(hook)
        }
    }

    fun projectDir(): Path? = project.basePath?.let { Path.of(it) }

    fun projectSandboxName(): String =
        AigwProjectSettings.getInstance(project).effectiveSandboxName(projectDir()?.fileName?.toString() ?: project.name)

    /** [s] is one of this project's sandboxes: its workspace is the project dir (or it has the project sandbox name and no workspace). */
    fun isProjectSandbox(s: Sandbox): Boolean = matchesProject(s, projectSandboxName(), project.basePath)

    /** [s] is the project sandbox managed through `sbx env run/rm` (named [projectSandboxName] and ours). */
    fun isNamedProjectSandbox(s: Sandbox): Boolean = s.name == projectSandboxName() && isProjectSandbox(s)

    fun isBusy(name: String): Boolean = name in ops.busy()

    /** Whether an [OpKind] command may run for [s] now (busy and in-flight rules; EXEC also needs RUNNING). */
    fun canRun(kind: OpKind, s: Sandbox): Boolean = opAllowed(kind, s, ops.busy(), ops.inFlight())

    /**
     * Begins an exec operation on [name] if the last listing shows it RUNNING and no lifecycle command runs for it.
     * While it is in flight, Start/Stop/Reset of [name] are refused. Pair with [endExec].
     */
    fun beginExec(name: String): Boolean {
        if (_sandboxes.value.none { it.name == name && it.status == SandboxStatus.RUNNING }) return false
        return ops.beginExec(name)
    }

    fun endExec(name: String) = ops.endExec(name)

    /**
     * Blocking (background thread, e.g. a progress task): runs `sbx ls` now and tells whether [name] is RUNNING.
     * False if the listing fails. Call it right before `sbx exec`, which would wake a stopped sandbox.
     */
    fun isRunningNow(name: String): Boolean =
        runBlockingCancellable { refreshAndGet() }?.any { it.name == name && it.status == SandboxStatus.RUNNING } == true

    /** Tells the user that [name] can't take a command right now. */
    fun notifyBusy(name: String, what: String) = AigwNotifications.notify(
        project, NotificationType.WARNING, "$what: $name is busy",
        notificationText("Wait until the current command for $name has finished, then try again."),
    )

    /** Create is possible: the list is loaded, the project has a dir, and no sandbox has the project sandbox name. */
    fun canCreateProjectSandbox(): Boolean {
        val name = projectSandboxName()
        return _loaded.value && projectDir() != null && _sandboxes.value.none { it.name == name } && !isBusy(name)
    }

    /** A fresh client (settings may change). Throws [ToolNotFound]. Call off the EDT. */
    fun sbx(): SbxClient = SbxClient(ProcessCliRunner(), sbxPath())

    private fun sbxPath(): Path = AigwAppSettings.getInstance().toolPaths().require("sbx")

    /**
     * argv (without the leading sbx path) for `sbx env <verb>` on the project sandbox, using the project's env files,
     * the `args:` they declare, the sandbox name and the configured env args. Reads files: call off the EDT.
     */
    fun envArgv(verb: String, detach: Boolean, noInput: Boolean, force: Boolean): List<String> {
        val dir = projectDir() ?: error("Project has no base directory")
        val settings = AigwProjectSettings.getInstance(project)
        val files = settings.effectiveEnvFiles()
        val union = declaredUnion(files) { f ->
            try {
                dir.resolve(f).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
            } catch (_: IOException) {
                null
            }
        }
        val newlyMissing = union.missing.filter { warnedMissingEnvFiles.add(it) }
        if (newlyMissing.isNotEmpty()) {
            AigwNotifications.notify(
                project, NotificationType.WARNING, "Env file not found",
                notificationText("Not found in $dir: ${newlyMissing.joinToString(", ")}"),
            )
        }
        return buildEnvArgv(
            verb, files, projectSandboxName(), settings.state.envArgs.toList(), union.declared,
            detach = detach, noInput = noInput, force = force, workspaceHost = AigwAppSettings.getInstance().state.workspaceHost,
        )
    }

    /** A child scope of the service scope (cancelled with the project), e.g. for UI collectors. */
    fun childScope(name: String): CoroutineScope = cs.childScope(name)

    // ---- listing ----

    fun refresh() {
        cs.launch { refreshNow() }
    }

    /** Polls `sbx ls` every 15 s while [active] (the tool window is visible). */
    @Synchronized
    fun setPollingActive(active: Boolean) {
        if (!active) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = cs.launch {
            while (isActive) {
                refreshNow()
                delay(POLL_MS)
            }
        }
    }

    /**
     * Runs `sbx ls` now (listing never wakes a sandbox), publishes the result and queues the observed transitions.
     * Returns the new [sandboxes] value, or null if the listing failed.
     */
    suspend fun refreshAndGet(): List<Sandbox>? = if (refreshNow()) _sandboxes.value else null

    private suspend fun refreshNow(): Boolean {
        val list = mutex.withLock {
            val list = try {
                withContext(Dispatchers.IO) { sbx().list() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportRefreshError(e)
                return false
            }
            _listError.value = null
            val (filtered, remaining) = marks.filter(list, System.currentTimeMillis())
            marks = remaining
            val sorted = sorted(filtered)
            _sandboxes.value = sorted
            _loaded.value = true
            observeLocked(sorted)
            list
        }
        warnIfForeign(list)
        return true
    }

    /** One warning per sandbox/workspace when a sandbox has the project sandbox name but belongs to another directory. */
    private fun warnIfForeign(list: List<Sandbox>) {
        val s = foreignNamedSandbox(list, projectSandboxName(), project.basePath) ?: return
        if (!warnedForeign.add(s.name + "\u0000" + s.workspaces.joinToString("\u0000"))) return
        AigwNotifications.notify(
            project, NotificationType.WARNING, "Sandbox ${s.name} belongs to another directory",
            notificationText(
                "${s.name} has this project's sandbox name, but its workspace is ${s.workspaces.joinToString(", ")}, " +
                    "not ${project.basePath}. The IDE treats it as another project's sandbox. " +
                    "Set a different sandbox name in Settings | Tools | AIGW Sandbox.",
            ),
        )
    }

    private fun reportRefreshError(e: Exception) {
        if (e !is ToolNotFound && e !is SbxException) thisLogger().warn("sbx ls failed", e)
        val msg = e.message ?: e.toString()
        if (msg == _listError.value) return // don't repeat the same balloon every poll
        _listError.value = msg
        AigwNotifications.error(project, "Could not list sandboxes", msg)
    }

    private fun sorted(list: List<Sandbox>): List<Sandbox> {
        val name = projectSandboxName()
        val base = project.basePath?.let { sortForProject(list, it) } ?: list.sortedBy { it.name }
        return base.sortedBy { it.name != name } // stable: the named project sandbox first
    }

    /** Call under [mutex]: queues the transitions from the last observation to [new], in order. */
    private fun observeLocked(new: List<Sandbox>) {
        val (started, stopped) = transitions(observed, new, ::isProjectSandbox)
        observed = new
        started.forEach { events.offer(LifecycleEvent.Started(it)) }
        stopped.forEach { events.offer(LifecycleEvent.Stopped(it)) }
    }

    /** Record a status change caused by our own command, so the next refresh doesn't fire it again. */
    private suspend fun markStatus(s: Sandbox, status: SandboxStatus) {
        mutex.withLock {
            marks = marks.mark(s.name, status, System.currentTimeMillis())
            if (_sandboxes.value.any { it.name == s.name }) _sandboxes.value = sorted(withStatus(_sandboxes.value, s, status))
            observeLocked(withStatus(observed, s, status))
        }
    }

    private fun runHook(hook: (Sandbox) -> Unit, s: Sandbox) {
        try {
            hook(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().error("Sandbox lifecycle hook failed for ${s.name}", e)
        }
    }

    // ---- OAuth callback relay ----

    /** Lifecycle consumer only (see [events]). */
    private fun relayStarted(s: Sandbox) {
        val name = s.name
        try {
            sbx()
        } catch (e: ToolNotFound) {
            AigwNotifications.error(project, "Sign-in relay not started for $name", e.message ?: e.toString())
            return
        }
        if (!registrar.started(name)) return // disposed meanwhile
        val port = AigwAppSettings.getInstance().callbackPort()
        CallbackRelay.getInstance().ensureRunning(port).onFailure {
            AigwNotifications.error(
                project, "Databricks sign-in callback unavailable",
                "Port $port is in use, so the Databricks sign-in callback can't be caught. " +
                    "Is the sandbox still publishing it? (sbx port unpublish $name $port)",
            )
        }
    }

    /** Blocking. Frees the callback port that sbx publishes for [name], so the relay can bind it on the host. */
    private fun unpublishCallbackPort(name: String) {
        val port = AigwAppSettings.getInstance().callbackPort()
        try {
            val r = sbx().unpublishPort(name, port)
            if (!r.ok) AigwNotifications.error(project, "Could not unpublish port $port of $name", r.stderr.ifBlank { r.stdout })
        } catch (e: Exception) {
            AigwNotifications.error(project, "Could not unpublish port $port of $name", e.message ?: e.toString())
        }
    }

    /** Lifecycle consumer only (see [events]). */
    private fun relayStopped(s: Sandbox) {
        registrar.stopped(s.name)
        CallbackRelay.getInstance().stopIfIdle()
    }

    private fun reportRelayProblem(o: RelayOutcome) {
        val text = when (o) {
            RelayOutcome.Success -> return
            RelayOutcome.NothingWaiting -> "No sign-in is waiting in the sandbox (it timed out or was cancelled). Start the sign-in again from the IDE."
            RelayOutcome.Rejected -> "The sandbox rejected this sign-in code (already used or expired). Sign in again."
            RelayOutcome.NoSandbox -> "No running sandbox is registered with the IDE."
            is RelayOutcome.Failed -> "curl exited with ${o.code} while handing the sign-in to the sandbox. ${o.stderr}"
        }
        AigwNotifications.error(project, "Databricks sign-in not completed", text)
    }

    override fun dispose() {
        events.close()
        registrar.dispose()
        CallbackRelay.getInstance().stopIfIdle()
    }

    // ---- Databricks sign-in ----

    /**
     * Lifecycle consumer only, after our own successful Start/Create: sign in if the relay is registered and the sandbox
     * isn't signed in yet. Owns one exec operation of [name] (handed off by the start), released here or by the sign-in task.
     */
    private suspend fun autoSignIn(name: String) {
        var handedOver = false
        try {
            if (!registrar.isRegistered(name)) return
            if (_sandboxes.value.none { it.name == name && it.status == SandboxStatus.RUNNING }) return
            val signedIn = try {
                withContext(Dispatchers.IO) { SignIn(sbx()).isSignedIn(name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Sign-in check failed for $name", e)
                return
            }
            setAuth(name, if (signedIn) AuthState.SIGNED_IN else AuthState.SIGNED_OUT)
            if (!signedIn) handedOver = launchSignIn(name)
        } finally {
            if (!handedOver) ops.endExec(name)
        }
    }

    /**
     * Signs [s] in to Databricks in a cancellable background task (one browser login). Refused (with a notice) while a
     * lifecycle command runs for it or it isn't running; while signing in, Start/Stop/Reset of [s] are disabled.
     */
    fun signIn(s: Sandbox) {
        val name = s.name
        if (!beginExec(name)) {
            if (isBusy(name)) notifyBusy(name, "Cannot sign in")
            else AigwNotifications.notify(project, NotificationType.WARNING, "Cannot sign in", notificationText("$name is not running. Start it first."))
            return
        }
        if (!launchSignIn(name)) ops.endExec(name)
    }

    /** Starts the sign-in task, which ends one exec operation of [name] when done. False if nothing was started. */
    private fun launchSignIn(name: String): Boolean {
        val state = AigwAppSettings.getInstance().state
        val host = state.workspaceHost.orEmpty().trim()
        val profile = state.profile.orEmpty().trim()
        if (host.isBlank() || profile.isBlank()) {
            AigwNotifications.notify(
                project, NotificationType.WARNING, "Databricks workspace not configured",
                "Set the workspace host and CLI profile in the AIGW Sandbox settings.",
                AigwNotifications.action(project, "Open Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AigwConfigurable::class.java)
                },
            )
            return false
        }
        if (!signingIn.add(name)) return false
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Signing in to Databricks…", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    if (isRunningNow(name)) runSignIn(name, normalizeHost(host), profile, indicator)
                    else AigwNotifications.notify(project, NotificationType.WARNING, "Sign-in skipped", notificationText("$name is not running."))
                } finally {
                    signingIn.remove(name)
                    ops.endExec(name)
                }
            }
        })
        return true
    }

    private fun runSignIn(name: String, host: String, profile: String, indicator: ProgressIndicator) {
        val relay = CallbackRelay.getInstance()
        relay.targets.markPending(name)
        try {
            val client = sbx()
            val signIn = SignIn(client)
            val commands = signIn.commands(host, profile, signIn.profileExists(name, profile))
            for (argv in commands) {
                val tail = ArrayDeque<String>()
                val opened = AtomicBoolean(false)
                val proc = client.execStreaming(name, argv) { line ->
                    synchronized(tail) { tail.addLast(line); if (tail.size > 20) tail.removeFirst() }
                    val url = extractAuthorizeUrl(line)
                    if (url != null && opened.compareAndSet(false, true)) BrowserUtil.browse(url)
                }
                val deadline = System.currentTimeMillis() + SIGN_IN_TIMEOUT_MS
                var exit: Int? = null
                while (exit == null) {
                    if (indicator.isCanceled || System.currentTimeMillis() >= deadline) {
                        proc.destroy()
                        if (!indicator.isCanceled) AigwNotifications.error(project, "Databricks sign-in timed out", "No sign-in completed within 5 minutes.")
                        return
                    }
                    exit = proc.waitFor(1000)
                }
                if (exit != 0) {
                    val out = synchronized(tail) { tail.joinToString("\n") }
                    AigwNotifications.error(project, "Databricks sign-in failed (exit $exit)", out)
                    return
                }
            }
            setAuth(name, AuthState.SIGNED_IN)
            AigwNotifications.info(project, "Signed in to Databricks ($profile)")
            authChanged.forEach { l ->
                try {
                    l()
                } catch (e: Exception) {
                    thisLogger().warn("authChanged listener failed", e)
                }
            }
        } catch (e: ToolNotFound) {
            AigwNotifications.error(project, "Databricks sign-in failed", e.message ?: e.toString())
        } catch (e: Exception) {
            thisLogger().warn("Databricks sign-in failed for $name", e)
            AigwNotifications.error(project, "Databricks sign-in failed", e.message ?: e.toString())
        } finally {
            relay.targets.clearPending(name)
        }
    }

    // ---- lifecycle ----

    /** Begins a lifecycle command for [name], or tells the user why not (another command or an exec operation runs). */
    private fun beginBusy(name: String, what: String): Boolean {
        if (ops.beginLifecycle(name)) return true
        notifyBusy(name, what)
        return false
    }

    private fun endBusy(name: String) = ops.endLifecycle(name)

    /**
     * Starts [s], or creates the project sandbox when [s] is null. The project sandbox goes through
     * `sbx --no-input env run --detach …` in a Run console; other sandboxes are woken with `sbx exec <name> true`.
     */
    fun start(s: Sandbox?) {
        if (s != null && !isNamedProjectSandbox(s)) wake(s) else runProjectEnvRun(s, force = false)
    }

    private fun runProjectEnvRun(s: Sandbox?, force: Boolean) {
        val name = projectSandboxName()
        val verb = if (s == null) "Create" else "Start"
        if (!beginBusy(name, "Cannot ${verb.lowercase()}")) return
        cs.launch {
            val (argv, dir) = try {
                withContext(Dispatchers.IO) {
                    val dir = projectDir() ?: error("Project has no base directory")
                    (listOf(sbxPath().toString()) + envArgv("run", detach = true, noInput = true, force = force)) to dir
                }
            } catch (e: CancellationException) {
                endBusy(name)
                throw e
            } catch (e: Exception) {
                endBusy(name)
                AigwNotifications.error(project, "Could not ${verb.lowercase()} $name", e.message ?: e.toString())
                return@launch
            }
            ConsoleRunner.runWithOutput(project, "$verb $name", argv, dir) { code, output ->
                cs.launch {
                    var handedOff = false
                    try {
                        when {
                            code == 0 -> {
                                if (s != null) markStatus(s, SandboxStatus.RUNNING)
                                refreshNow()
                                // Queued behind the start transition, so the relay is registered first; holding an exec
                                // operation from here on keeps Stop/Reset disabled until the sign-in check is done.
                                ops.handOffToExec(name)
                                handedOff = true
                                events.offer(LifecycleEvent.AutoSignIn(name))
                            }
                            !force && needsForce(code, output) -> AigwNotifications.notify(
                                project, NotificationType.WARNING, "$verb $name needs confirmation",
                                "sbx wants to apply changes to the sandbox. Review the plan in the Run console.",
                                AigwNotifications.action(project, "Apply anyway") { runProjectEnvRun(s, force = true) },
                            )
                            else -> AigwNotifications.error(project, "$verb $name failed (exit $code)", output)
                        }
                    } finally {
                        if (!handedOff) endBusy(name) // after the hand-off, busy may already belong to a later command
                    }
                }
            }
        }
    }

    private fun wake(s: Sandbox) = background(s, "Starting ${s.name}", { it.wake(s.name) }) {
        markStatus(s, SandboxStatus.RUNNING)
        refreshNow()
        AigwNotifications.info(project, "Sandbox ${s.name} started")
    }

    /** `sbx stop <name>` in the background. */
    fun stop(s: Sandbox) = background(s, "Stopping ${s.name}", { it.stop(s.name) }) {
        markStatus(s, SandboxStatus.STOPPED)
        refreshNow()
    }

    /**
     * Asks for confirmation (call on the EDT), then removes [s]: the project sandbox via `sbx --no-input env rm --force …`
     * in a Run console (then offers "Create again"), others via `sbx --no-input rm <name>`.
     */
    fun reset(s: Sandbox) {
        val answer = Messages.showYesNoDialog(
            project, "Remove sandbox ${s.name}? Uncommitted work inside it is lost.", "Reset Sandbox", Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        if (!isNamedProjectSandbox(s)) {
            background(s, "Removing ${s.name}", { it.rm(s.name) }) {
                refreshNow()
                AigwNotifications.info(project, "Sandbox ${s.name} removed")
            }
            return
        }
        val name = s.name
        if (!beginBusy(name, "Cannot reset")) return
        cs.launch {
            val (argv, dir) = try {
                withContext(Dispatchers.IO) {
                    val dir = projectDir() ?: error("Project has no base directory")
                    (listOf(sbxPath().toString()) + envArgv("rm", detach = false, noInput = true, force = true)) to dir
                }
            } catch (e: CancellationException) {
                endBusy(name)
                throw e
            } catch (e: Exception) {
                endBusy(name)
                AigwNotifications.error(project, "Could not reset $name", e.message ?: e.toString())
                return@launch
            }
            ConsoleRunner.runWithOutput(project, "Reset $name", argv, dir) { code, output ->
                cs.launch {
                    try {
                        if (code == 0) {
                            refreshNow()
                            AigwNotifications.info(
                                project, "Sandbox $name removed", "",
                                AigwNotifications.action(project, "Create again") { start(null) },
                            )
                        } else {
                            AigwNotifications.error(project, "Reset $name failed (exit $code)", output)
                        }
                    } finally {
                        endBusy(name)
                    }
                }
            }
        }
    }

    /** Runs [command] with a background progress indicator; non-zero exit / errors become an error balloon. */
    private fun background(s: Sandbox, title: String, command: (SbxClient) -> CliResult, onSuccess: suspend () -> Unit) {
        if (!beginBusy(s.name, "Cannot run \"$title\"")) return
        cs.launch {
            try {
                val r = withBackgroundProgress(project, title) {
                    withContext(Dispatchers.IO) { command(sbx()) }
                }
                if (r.ok) onSuccess()
                else AigwNotifications.error(project, "$title failed (exit ${r.exitCode})", r.stderr.ifBlank { r.stdout })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is ToolNotFound && e !is SbxException) thisLogger().warn(title, e)
                AigwNotifications.error(project, "$title failed", e.message ?: e.toString())
            } finally {
                endBusy(s.name)
            }
        }
    }


    companion object {
        const val POLL_MS = 15_000L
        const val START_WAIT_MS = 10 * 60_000L
        const val SIGN_IN_TIMEOUT_MS = 5 * 60_000L

        fun getInstance(project: Project): SandboxService = project.service()
    }
}
