package dev.aigw.sandbox.auth

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.aigw.sandbox.cli.CliResult
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed interface RelayOutcome {
    object Success : RelayOutcome
    object NothingWaiting : RelayOutcome
    object Rejected : RelayOutcome
    data class Failed(val code: Int, val stderr: String) : RelayOutcome
    object NoSandbox : RelayOutcome
}

/** curl exit code → outcome: 0 success, 7 connection refused (nothing listening), 22 HTTP error. */
fun outcomeForCurlExit(r: CliResult): RelayOutcome = when (r.exitCode) {
    0 -> RelayOutcome.Success
    7 -> RelayOutcome.NothingWaiting
    22 -> RelayOutcome.Rejected
    else -> RelayOutcome.Failed(r.exitCode, r.stderr)
}

/**
 * Loopback HTTP server on the host that replays the browser's OAuth redirect (`/?code=…&state=…`) inside the sandbox,
 * where the CLI's own listener is. Query strings carry the auth code: never log them.
 */
@Service(Service.Level.APP)
class CallbackRelay : Disposable {
    val targets = RelayTargets()

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    val boundPort: Int? @Synchronized get() = server?.address?.port

    /** Binds 127.0.0.1:[port] (0 = ephemeral). Idempotent; rebinds if running on another port. */
    @Synchronized
    fun ensureRunning(port: Int): Result<Unit> {
        if (server != null) {
            if (port == 0 || boundPort == port) return Result.success(Unit)
            stopLocked()
        }
        val srv = try {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        } catch (e: IOException) {
            return Result.failure(IOException("Port $port is in use (${e.message})", e))
        }
        val ex = Executors.newFixedThreadPool(4) { r -> Thread(r, "aigw-callback-relay").apply { isDaemon = true } }
        srv.executor = ex
        srv.createContext("/") { exchange -> serve(exchange) }
        srv.start()
        server = srv
        executor = ex
        return Result.success(Unit)
    }

    /** Stops the server when no sandbox is registered. */
    @Synchronized
    fun stopIfIdle() {
        if (targets.isEmpty) stopLocked()
    }

    private fun stopLocked() {
        server?.stop(0)
        executor?.shutdownNow()
        server = null
        executor = null
    }

    @Synchronized
    override fun dispose() = stopLocked()

    private fun serve(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                respond(exchange, 405, "Method not allowed")
                return
            }
            val uri = exchange.requestURI
            val query = uri.rawQuery
            if (query == null || !hasParam(query, "code") || !hasParam(query, "state")) {
                respond(exchange, 400, "Not a Databricks sign-in callback")
                return
            }
            val (name, outcome) = dispatch((uri.rawPath ?: "/") + "?" + query)
            val (status, text) = pageFor(outcome)
            if (name != null && outcome != RelayOutcome.Success) {
                try {
                    targets.problemHandlerFor(name)?.invoke(outcome)
                } catch (e: Exception) {
                    thisLogger().warn("Callback problem handler failed", e)
                }
            }
            respond(exchange, status, text)
        } catch (e: Exception) {
            thisLogger().warn("Callback request failed: ${e.javaClass.simpleName}")
            try {
                respond(exchange, 500, "Internal error")
            } catch (_: Exception) {
            }
        } finally {
            exchange.close()
        }
    }

    internal fun handle(pathAndQuery: String): RelayOutcome = dispatch(pathAndQuery).second

    /**
     * The name of the sandbox whose outcome counts (null if none is registered) and that outcome.
     * Several candidates (no login pending): each is tried in turn until one has a listener that accepts the code. A
     * wrong sandbox answers 7 (nothing listening) or 22 (its CLI rejects the foreign `state`), so both mean "try the
     * next"; the result is the last outcome from a sandbox that had a listener, else "nothing waiting".
     */
    private fun dispatch(pathAndQuery: String): Pair<String?, RelayOutcome> {
        val names = targets.candidates()
        if (names.isEmpty()) return null to RelayOutcome.NoSandbox
        var answered: Pair<String, RelayOutcome>? = null
        var last: String? = null
        for (name in names) {
            val forward = targets.forwardFor(name) ?: continue // unregistered meanwhile
            last = name
            val outcome = try {
                val result = forward(pathAndQuery)
                thisLogger().info("Relayed sign-in callback to $name (path length ${pathAndQuery.length}): exit ${result.exitCode}")
                outcomeForCurlExit(result)
            } catch (e: Exception) {
                RelayOutcome.Failed(-1, e.message ?: e.toString())
            }
            when (outcome) {
                RelayOutcome.NothingWaiting -> {}
                RelayOutcome.Rejected -> answered = name to outcome
                else -> return name to outcome
            }
        }
        return answered ?: (last to if (last == null) RelayOutcome.NoSandbox else RelayOutcome.NothingWaiting)
    }

    private fun hasParam(query: String, key: String) = query.split('&').any { it.startsWith("$key=") }

    private fun pageFor(o: RelayOutcome): Pair<Int, String> = when (o) {
        RelayOutcome.Success -> 200 to "Signed in to Databricks. You can close this tab."
        RelayOutcome.NothingWaiting -> 502 to
            "No sign-in is waiting in the sandbox (it timed out or was cancelled). Start the sign-in again from the IDE."
        RelayOutcome.Rejected -> 502 to "The sandbox rejected this sign-in code (already used or expired). Sign in again."
        RelayOutcome.NoSandbox -> 503 to "No running sandbox is registered with the IDE."
        is RelayOutcome.Failed -> 502 to "Could not hand the sign-in to the sandbox (exit ${o.code}). ${o.stderr.trim()}"
    }

    private fun respond(exchange: HttpExchange, status: Int, text: String) {
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>AIGW Sandbox</title></head>" +
            "<body><p>${escapeHtml(text)}</p></body></html>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        fun getInstance(): CallbackRelay = service()
    }
}

private fun escapeHtml(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
