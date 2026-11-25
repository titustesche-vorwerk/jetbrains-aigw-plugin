package dev.aigw.sandbox.auth

import dev.aigw.sandbox.cli.CliResult

/** Sandboxes that can receive an OAuth callback. Thread-safe. */
class RelayTargets {
    private class Target(val forward: (String) -> CliResult, val onProblem: (RelayOutcome) -> Unit)

    private val forwards = LinkedHashMap<String, Target>()
    private var pending: String? = null

    @Synchronized
    fun register(name: String, onProblem: (RelayOutcome) -> Unit = {}, forward: (String) -> CliResult) {
        forwards[name] = Target(forward, onProblem)
    }

    @Synchronized
    fun unregister(name: String) {
        forwards.remove(name)
        if (pending == name) pending = null
    }

    /** [name] is waiting for a sign-in callback (ignored if not registered). */
    @Synchronized
    fun markPending(name: String) {
        if (name in forwards) pending = name
    }

    @Synchronized
    fun clearPending(name: String) {
        if (pending == name) pending = null
    }

    /**
     * Where to send a callback: only the pending sandbox if it is registered, else every registered one in
     * registration order (the relay tries them in turn). Empty if none is registered.
     */
    @Synchronized
    fun candidates(): List<String> = pending?.takeIf { it in forwards }?.let { listOf(it) } ?: forwards.keys.toList()

    @Synchronized
    fun forwardFor(name: String): ((String) -> CliResult)? = forwards[name]?.forward

    /** The problem handler of [name]'s current registration, or null once unregistered. */
    @Synchronized
    fun problemHandlerFor(name: String): ((RelayOutcome) -> Unit)? = forwards[name]?.onProblem

    val isEmpty: Boolean @Synchronized get() = forwards.isEmpty()
}
