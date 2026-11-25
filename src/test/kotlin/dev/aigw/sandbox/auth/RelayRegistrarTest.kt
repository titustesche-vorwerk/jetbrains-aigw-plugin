package dev.aigw.sandbox.auth

import dev.aigw.sandbox.cli.CliResult
import dev.aigw.sandbox.ui.SerialQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Finding 2: start/stop hooks applied in observation order, and nothing registered after dispose. */
class RelayRegistrarTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val targets = RelayTargets()
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Blocks the first unpublish until released, like a slow `sbx port unpublish`. */
    private val unpublishEntered = CountDownLatch(1)
    private val releaseUnpublish = CountDownLatch(1)

    private val registrar = RelayRegistrar(
        unpublish = { name ->
            log += "unpublish $name"
            unpublishEntered.countDown()
            releaseUnpublish.await(5, TimeUnit.SECONDS)
        },
        register = { name -> log += "register $name"; targets.register(name) { CliResult(0, "", "") } },
        unregister = { name -> log += "unregister $name"; targets.unregister(name) },
    )

    private sealed interface Ev {
        data class Started(val name: String) : Ev
        data class Stopped(val name: String) : Ev
        data class AutoSignIn(val name: String, val sawRegistered: (Boolean) -> Unit) : Ev
        data class Done(val latch: CountDownLatch) : Ev
    }

    private val queue = SerialQueue<Ev>(scope, { _, e -> throw AssertionError(e) }) { e ->
        when (e) {
            is Ev.Started -> registrar.started(e.name)
            is Ev.Stopped -> registrar.stopped(e.name)
            is Ev.AutoSignIn -> e.sawRegistered(registrar.isRegistered(e.name))
            is Ev.Done -> e.latch.countDown()
        }
    }

    @AfterEach
    fun tearDown() {
        releaseUnpublish.countDown()
        scope.cancel()
    }

    private fun drain() {
        val done = CountDownLatch(1)
        queue.offer(Ev.Done(done))
        assertTrue(done.await(5, TimeUnit.SECONDS), "queue did not drain")
    }

    @Test
    fun startThenStopLeavesNothingRegisteredEvenWhenUnpublishIsSlow() {
        queue.offer(Ev.Started("a"))
        assertTrue(unpublishEntered.await(5, TimeUnit.SECONDS))
        queue.offer(Ev.Stopped("a")) // observed while the start hook is still unpublishing
        releaseUnpublish.countDown()
        drain()
        assertFalse(registrar.isRegistered("a"))
        assertTrue(targets.isEmpty)
        assertEquals(listOf("unpublish a", "register a", "unregister a"), log.toList())
    }

    @Test
    fun autoSignInQueuedAfterStartSeesTheRegistration() {
        var saw: Boolean? = null
        queue.offer(Ev.Started("a"))
        queue.offer(Ev.AutoSignIn("a") { saw = it })
        releaseUnpublish.countDown()
        drain()
        assertEquals(true, saw)
        assertEquals(listOf("a"), targets.candidates())
    }

    @Test
    fun disposeDuringStartRegistersNothing() {
        queue.offer(Ev.Started("a"))
        assertTrue(unpublishEntered.await(5, TimeUnit.SECONDS))
        registrar.dispose() // project closed while the start hook is in `sbx port unpublish`
        releaseUnpublish.countDown()
        drain()
        assertFalse(registrar.isRegistered("a"))
        assertTrue(targets.isEmpty)
        assertFalse(log.contains("register a"))
    }

    @Test
    fun disposeUnregistersAndLaterStartsAreIgnored() {
        releaseUnpublish.countDown()
        assertTrue(registrar.started("a"))
        registrar.dispose()
        assertTrue(targets.isEmpty)
        assertFalse(registrar.started("b"))
        assertTrue(targets.isEmpty)
    }

    @Test
    fun stopOfUnknownSandboxDoesNothing() {
        assertFalse(registrar.stopped("x"))
        assertTrue(log.isEmpty())
    }

    @Test
    fun failingHandlerDoesNotStopTheQueue() {
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        val q = SerialQueue<Int>(scope, { e, _ -> errors += "failed $e" }) { if (it == 1) error("boom") else done.countDown() }
        q.offer(1)
        q.offer(2)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("failed 1"), errors.toList())
    }
}
