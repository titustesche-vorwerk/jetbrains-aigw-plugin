package dev.aigw.sandbox.auth

import dev.aigw.sandbox.cli.CliResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class CallbackRelayTest {
    private val relay = CallbackRelay()
    private val outcomes = mutableListOf<RelayOutcome>()

    @AfterEach
    fun stop() = relay.dispose()

    private fun start(): Int {
        assertTrue(relay.ensureRunning(0).isSuccess)
        return relay.boundPort!!
    }

    private fun get(port: Int, pq: String, method: String = "GET"): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$pq")).method(method, HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun forwardsPathAndQueryVerbatim() {
        val port = start()
        var got: String? = null
        relay.targets.register("sb") { got = it; CliResult(0, "", "") }
        val r = get(port, "/?code=abc&state=a%2Bb+c")
        assertEquals("/?code=abc&state=a%2Bb+c", got)
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("Signed in to Databricks"))
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun mapsCurlExitCodes() {
        val port = start()
        var code = 7
        relay.targets.register("sb") { CliResult(code, "", "") }
        assertTrue(get(port, "/?code=a&state=b").body().contains("No sign-in is waiting"))
        code = 22
        assertTrue(get(port, "/?code=a&state=b").body().contains("rejected"))
        code = 3
        assertTrue(relay.handle("/?code=a&state=b") is RelayOutcome.Failed)
    }

    @Test
    fun rejectsNonCallback() {
        val port = start()
        var called = false
        relay.targets.register("sb") { called = true; CliResult(0, "", "") }
        val r = get(port, "/favicon.ico")
        assertEquals(400, r.statusCode())
        assertTrue(r.body().contains("Not a Databricks sign-in callback"))
        assertEquals(405, get(port, "/?code=a&state=b", "POST").statusCode())
        assertTrue(!called)
    }

    @Test
    fun noTargetPage() {
        val port = start()
        val r = get(port, "/?code=a&state=b")
        assertTrue(r.body().contains("No running sandbox"))
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun problemGoesToThatTargetsHandlerOnly() {
        val port = start()
        val a = mutableListOf<RelayOutcome>()
        val b = mutableListOf<RelayOutcome>()
        relay.targets.register("a", { a += it }) { CliResult(7, "", "") }
        relay.targets.register("b", { b += it }) { CliResult(22, "", "") }
        relay.targets.markPending("a")
        get(port, "/?code=a&state=b")
        assertEquals(listOf<RelayOutcome>(RelayOutcome.NothingWaiting), a)
        assertTrue(b.isEmpty())
        relay.targets.unregister("a")
        relay.targets.markPending("b")
        get(port, "/?code=a&state=b")
        assertEquals(listOf<RelayOutcome>(RelayOutcome.NothingWaiting), a)
        assertEquals(listOf<RelayOutcome>(RelayOutcome.Rejected), b)
    }

    @Test
    fun reportsBindFailure() {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { s ->
            val r = relay.ensureRunning(s.localPort)
            assertTrue(r.isFailure)
            val m = r.exceptionOrNull()!!.message!!
            assertTrue(m.contains(s.localPort.toString()) && m.contains("in use"))
        }
    }

    @Test
    fun ensureRunningIsIdempotentAndStopIfIdleStops() {
        val port = start()
        assertTrue(relay.ensureRunning(port).isSuccess)
        assertEquals(port, relay.boundPort)
        relay.stopIfIdle()
        assertEquals(null, relay.boundPort)
    }

    // ---- finding 4: several registered sandboxes, no pending login ----

    private fun twoTargets(a: Int, b: Int): Pair<MutableList<String>, MutableList<RelayOutcome>> {
        val called = mutableListOf<String>()
        val problems = mutableListOf<RelayOutcome>()
        relay.targets.register("a", { problems += it }) { called += "a"; CliResult(a, "", "") }
        relay.targets.register("b", { problems += it }) { called += "b"; CliResult(b, "", "") }
        return called to problems
    }

    @Test
    fun triesNextWhenFirstHasNothingListening() {
        val port = start()
        val (called, problems) = twoTargets(7, 0)
        val r = get(port, "/?code=a&state=b")
        assertEquals(200, r.statusCode())
        assertEquals(listOf("a", "b"), called)
        assertTrue(problems.isEmpty())
    }

    @Test
    fun triesNextWhenFirstRejectsTheForeignState() {
        start()
        val (called, _) = twoTargets(22, 0)
        assertEquals(RelayOutcome.Success, relay.handle("/?code=a&state=b"))
        assertEquals(listOf("a", "b"), called)
    }

    @Test
    fun firstSuccessStops() {
        start()
        val (called, _) = twoTargets(0, 0)
        assertEquals(RelayOutcome.Success, relay.handle("/?code=a&state=b"))
        assertEquals(listOf("a"), called)
    }

    @Test
    fun otherFailureStopsAndIsReported() {
        start()
        val (called, _) = twoTargets(3, 0)
        assertEquals(RelayOutcome.Failed(3, ""), relay.handle("/?code=a&state=b"))
        assertEquals(listOf("a"), called)
    }

    @Test
    fun nobodyListeningIsNothingWaiting() {
        val port = start()
        val (called, problems) = twoTargets(7, 7)
        assertTrue(get(port, "/?code=a&state=b").body().contains("No sign-in is waiting"))
        assertEquals(listOf("a", "b"), called)
        assertEquals(listOf<RelayOutcome>(RelayOutcome.NothingWaiting), problems)
    }

    @Test
    fun lastRealOutcomeWinsOverNothingListening() {
        val port = start()
        val (called, problems) = twoTargets(22, 7)
        assertTrue(get(port, "/?code=a&state=b").body().contains("rejected"))
        assertEquals(listOf("a", "b"), called)
        assertEquals(listOf<RelayOutcome>(RelayOutcome.Rejected), problems)
    }

    @Test
    fun pendingLoginIsTheOnlyTarget() {
        start()
        val (called, _) = twoTargets(0, 7)
        relay.targets.markPending("b")
        assertEquals(RelayOutcome.NothingWaiting, relay.handle("/?code=a&state=b"))
        assertEquals(listOf("b"), called)
    }
}
