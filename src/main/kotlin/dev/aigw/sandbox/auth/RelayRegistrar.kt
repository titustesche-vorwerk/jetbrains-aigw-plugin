package dev.aigw.sandbox.auth

/**
 * One project's registrations with the callback relay. [started] and [stopped] must be called in the order the
 * transitions happened (from a single consumer); [dispose] may come from any thread at any time. After [dispose],
 * nothing is registered any more, even by a [started] that was blocked in [unpublish] meanwhile.
 *
 * [unpublish] frees the callback port inside the sandbox (blocking), [register]/[unregister] update the relay targets.
 */
class RelayRegistrar(
    private val unpublish: (String) -> Unit,
    private val register: (String) -> Unit,
    private val unregister: (String) -> Unit,
) {
    private val lock = Any()
    private val names = LinkedHashSet<String>() // guarded by lock
    private var disposed = false // guarded by lock

    /** Unpublishes the port and registers [name]. False (nothing registered) once disposed. */
    fun started(name: String): Boolean {
        if (isDisposed()) return false
        unpublish(name)
        synchronized(lock) {
            if (disposed) return false
            register(name)
            names += name
        }
        return true
    }

    /** Unregisters [name] if [started] registered it. True if it was registered. */
    fun stopped(name: String): Boolean = synchronized(lock) {
        if (!names.remove(name)) return false
        unregister(name)
        true
    }

    fun isRegistered(name: String): Boolean = synchronized(lock) { name in names }

    fun dispose() = synchronized(lock) {
        disposed = true
        names.forEach(unregister)
        names.clear()
    }

    private fun isDisposed() = synchronized(lock) { disposed }
}
