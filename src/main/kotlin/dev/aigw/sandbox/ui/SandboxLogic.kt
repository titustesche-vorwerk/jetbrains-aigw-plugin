package dev.aigw.sandbox.ui

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import dev.aigw.sandbox.sbx.Sandbox
import dev.aigw.sandbox.sbx.SandboxStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Status transitions of project sandboxes between two observations: (started, stopped).
 * `old == null` means "first observation": every running project sandbox counts as started.
 * A running project sandbox that disappears counts as stopped (reported with status STOPPED).
 */
fun transitions(
    old: List<Sandbox>?,
    new: List<Sandbox>,
    isProject: (Sandbox) -> Boolean,
): Pair<List<Sandbox>, List<Sandbox>> {
    val oldByName = old.orEmpty().associateBy { it.name }
    val newNames = new.mapTo(HashSet()) { it.name }
    val started = new.filter {
        isProject(it) && it.status == SandboxStatus.RUNNING && oldByName[it.name]?.status != SandboxStatus.RUNNING
    }
    val stopped = new.filter {
        isProject(it) && it.status != SandboxStatus.RUNNING && oldByName[it.name]?.status == SandboxStatus.RUNNING
    } + old.orEmpty().filter {
        it.name !in newNames && isProject(it) && it.status == SandboxStatus.RUNNING
    }.map { it.copy(status = SandboxStatus.STOPPED) }
    return started to stopped
}

/** [list] with [s] set to [status] (replacing the entry with the same name, or appended). */
fun withStatus(list: List<Sandbox>?, s: Sandbox, status: SandboxStatus): List<Sandbox> {
    val updated = s.copy(status = status)
    val current = list.orEmpty()
    return if (current.any { it.name == s.name }) current.map { if (it.name == s.name) updated else it } else current + updated
}

private val FORCE_FLAG = Regex("""(^|[^\w-])--force(?![\w-])""")

/** `sbx env run/rm` failed because it wants confirmation (the plan changed): rerun with `--force`. */
fun needsForce(exitCode: Int, output: String): Boolean = exitCode != 0 && FORCE_FLAG.containsMatchIn(output)

data class DeclaredArgs(val declared: Set<String>, val missing: List<String>)

/** Union of `args:` keys over [files]; [read] returns a file's text or null if it doesn't exist. */
fun declaredUnion(files: List<String>, read: (String) -> String?): DeclaredArgs {
    val declared = linkedSetOf<String>()
    val missing = mutableListOf<String>()
    for (f in files) {
        val text = read(f)
        if (text == null) missing += f else declared += dev.aigw.sandbox.sbx.declaredArgs(text)
    }
    return DeclaredArgs(declared, missing)
}

private val ANSI = Regex("""\u001B\[[0-9;?]*[A-Za-z]""")

/** [raw] without ANSI escape codes. */
fun stripAnsi(raw: String): String = ANSI.replace(raw, "")

/** CLI output as notification HTML: ANSI codes stripped, trimmed, last [max] chars kept, escaped, newlines as `<br>`. */
fun notificationText(raw: String, max: Int = 1500): String {
    val clean = stripAnsi(raw).trim()
    val tail = if (clean.length > max) "…" + clean.takeLast(max) else clean
    return tail.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", "").replace("\n", "<br>")
}

/**
 * Status changes made by our own commands (start/stop), kept until an observation agrees or [WINDOW_MS] pass, so a
 * stale `sbx ls` taken before the change landed can't fire the opposite lifecycle hook.
 */
data class PendingMarks(private val marks: Map<String, Pair<SandboxStatus, Long>> = emptyMap()) {
    fun isEmpty(): Boolean = marks.isEmpty()

    fun mark(name: String, status: SandboxStatus, now: Long): PendingMarks = PendingMarks(marks + (name to (status to now)))

    /** [observed] with contradicting entries replaced by their marked status, plus the marks that remain. */
    fun filter(observed: List<Sandbox>, now: Long): Pair<List<Sandbox>, PendingMarks> {
        if (marks.isEmpty()) return observed to this
        val remaining = HashMap(marks)
        marks.forEach { (name, m) -> if (now - m.second >= WINDOW_MS) remaining.remove(name) }
        val out = observed.map { s ->
            val m = remaining[s.name] ?: return@map s
            if (s.status == m.first) {
                remaining.remove(s.name)
                s
            } else {
                s.copy(status = m.first)
            }
        }
        return out to PendingMarks(remaining)
    }

    companion object {
        const val WINDOW_MS = 20_000L
    }
}

/**
 * Whether [s] is the open project's sandbox. A sandbox that reports workspaces must have the project dir among them;
 * the name alone only counts when it reports none (or the project has no dir).
 */
fun matchesProject(s: Sandbox, projectSandboxName: String, basePath: String?): Boolean =
    if (basePath == null) s.name == projectSandboxName
    else s.belongsTo(basePath) || (s.name == projectSandboxName && s.workspaces.isEmpty())

/** A sandbox that has the project sandbox name but another workspace (so it is not ours), or null. */
fun foreignNamedSandbox(list: List<Sandbox>, projectSandboxName: String, basePath: String?): Sandbox? =
    list.firstOrNull { it.name == projectSandboxName && !matchesProject(it, projectSandboxName, basePath) }

/**
 * What a sandbox command needs.
 * [LIFECYCLE] (start/stop/reset): no other lifecycle command and no exec operation in flight for that sandbox.
 * [EXEC] (`sbx exec`: sign-in, push, status check): a RUNNING sandbox and no lifecycle command in flight.
 * [HOST] (host-side git, terminal): no lifecycle command in flight.
 */
enum class OpKind { LIFECYCLE, EXEC, HOST }

fun opAllowed(kind: OpKind, s: Sandbox, busy: Set<String>, inFlight: Set<String>): Boolean = when (kind) {
    OpKind.LIFECYCLE -> s.name !in busy && s.name !in inFlight
    OpKind.EXEC -> s.status == SandboxStatus.RUNNING && s.name !in busy
    OpKind.HOST -> s.name !in busy
}

/**
 * Lifecycle commands ("busy") and exec operations ("in flight") per sandbox, with the [OpKind] rules applied atomically.
 * Exec operations may overlap each other (counted). [onChange] runs under the lock: keep it cheap. Thread-safe.
 */
class SandboxOps(private val onChange: (busy: Set<String>, inFlight: Set<String>) -> Unit = { _, _ -> }) {
    private var busy = emptySet<String>()
    private var inFlight = emptyMap<String, Int>()

    @Synchronized
    fun beginLifecycle(name: String): Boolean {
        if (name in busy || name in inFlight) return false
        busy = busy + name
        changed()
        return true
    }

    @Synchronized
    fun endLifecycle(name: String) {
        if (name !in busy) return
        busy = busy - name
        changed()
    }

    /** Refused while a lifecycle command runs for [name]. The caller still checks that the sandbox is RUNNING. */
    @Synchronized
    fun beginExec(name: String): Boolean {
        if (name in busy) return false
        inFlight = inFlight + (name to (inFlight[name] ?: 0) + 1)
        changed()
        return true
    }

    @Synchronized
    fun endExec(name: String) {
        val n = inFlight[name] ?: return
        inFlight = if (n <= 1) inFlight - name else inFlight + (name to n - 1)
        changed()
    }

    /** Ends [name]'s lifecycle command and begins an exec operation in one step, so no Stop can slip in between. */
    @Synchronized
    fun handOffToExec(name: String) {
        busy = busy - name
        inFlight = inFlight + (name to (inFlight[name] ?: 0) + 1)
        changed()
    }

    @Synchronized
    fun busy(): Set<String> = busy

    @Synchronized
    fun inFlight(): Set<String> = inFlight.keys

    private fun changed() = onChange(busy, inFlight.keys)
}

/**
 * Handles events one at a time, in the order they were [offer]ed, on one coroutine in [scope] (on [Dispatchers.IO]).
 * A failing handler is reported to [onError] and does not stop the queue.
 */
class SerialQueue<E>(scope: CoroutineScope, private val onError: (E, Exception) -> Unit, private val handle: suspend (E) -> Unit) {
    private val channel = Channel<E>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (e in channel) {
                try {
                    handle(e)
                } catch (c: CancellationException) {
                    throw c
                } catch (x: Exception) {
                    onError(e, x)
                }
            }
        }
    }

    /** Never blocks; ignored once [close]d. */
    fun offer(e: E) {
        channel.trySend(e)
    }

    fun close() {
        channel.close()
    }
}

/** Like [runCatching], but progress and coroutine cancellation propagate instead of becoming a failure. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: ProcessCanceledException) {
    throw e
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/** "1 commit" / "n commits". */
fun commits(n: Int): String = if (n == 1) "1 commit" else "$n commits"
