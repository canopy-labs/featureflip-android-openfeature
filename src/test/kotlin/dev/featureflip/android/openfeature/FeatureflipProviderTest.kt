package dev.featureflip.android.openfeature

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.featureflip.android.FeatureflipClient
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.ImmutableStructure
import dev.openfeature.kotlin.sdk.TrackingEventDetails
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class FeatureflipProviderTest {
    private val json = jacksonObjectMapper()
    private lateinit var server: FakeEvalServer
    private lateinit var key: String

    @BeforeEach
    fun setUp() {
        server = FakeEvalServer()
        key = "client-" + UUID.randomUUID()
    }

    @AfterEach
    fun tearDown() {
        FeatureflipClient.resetForTesting()
        server.close()
    }

    private val flags = """{"a":{"value":true,"variation":"on","reason":"fallthrough"}}"""

    private fun events(): List<Map<String, Any?>> {
        val body = server.requestsTo("/v1/client/events").map { it.body!!.utf8() }
        return body.flatMap { b ->
            val parsed: Map<String, Any?> = json.readValue(b)
            @Suppress("UNCHECKED_CAST")
            parsed["events"] as List<Map<String, Any?>>
        }
    }

    @Test
    fun `the config path's first request is an evaluate carrying the merged context and the wrapper user agent`() = runBlocking {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(server.config(key, context = mapOf("plan" to "free", "app" to "x")))
        provider.initialize(ImmutableContext("u1", mapOf("plan" to Value.String("pro"))))

        val first = server.requests.first()
        assertThat(first.url.encodedPath).isEqualTo("/v1/client/evaluate")
        assertThat(server.contextOf(first)).isEqualTo(mapOf("plan" to "pro", "app" to "x", "user_id" to "u1"))
        assertThat(first.headers["User-Agent"])
            .matches("^featureflip-android/\\S+ featureflip-openfeature-android/$PROVIDER_VERSION$")
        assertThat(server.requestsTo("/v1/client/identify")).isEmpty()
        provider.shutdown()
    }

    @Test
    fun `the stream connect carries the wrapper token`() = runBlocking {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(server.config(key, streaming = true))
        provider.initialize(null)

        // The stream connects on a background thread after initialize returns.
        withTimeout(10_000) { while (server.requestsTo("/v1/client/stream").isEmpty()) delay(20) }
        val streams = server.requestsTo("/v1/client/stream")
        assertThat(streams.first().headers["User-Agent"]).endsWith(" featureflip-openfeature-android/$PROVIDER_VERSION")
        provider.shutdown()
    }

    @Test
    fun `a caller's own wrapper is kept`() = runBlocking {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(server.config(key, wrapperName = "my-app", wrapperVersion = "1.0"))
        provider.initialize(null)

        val ua = server.requests.first().headers["User-Agent"]!!
        assertThat(ua).endsWith(" my-app/1.0")
        assertThat(ua).doesNotContain("featureflip-openfeature-android")
        provider.shutdown()
    }

    @Test
    fun `the config path identifies the openfeature user on a core that was already initialized`() = runBlocking {
        server.evaluateFlags = flags
        server.identifyFlags = """{"a":{"value":"id","variation":"v","reason":"fallthrough"}}"""
        val earlier = FeatureflipClient.get(server.config(key, context = mapOf("user_id" to "earlier")))
        earlier.initialize()

        val provider = FeatureflipProvider(server.config(key, context = mapOf("user_id" to "earlier")))
        provider.initialize(ImmutableContext("of-user"))

        val identifies = server.requestsTo("/v1/client/identify")
        assertThat(identifies).hasSize(1)
        assertThat(server.contextOf(identifies.single())["user_id"]).isEqualTo("of-user")
        assertThat(provider.getStringEvaluation("a", "d", null).value).isEqualTo("id")
        provider.shutdown()
        earlier.close()
    }

    @Test
    fun `the client path identifies only when the openfeature context is non-empty`() = runBlocking {
        server.evaluateFlags = flags
        val first = FeatureflipClient.get(server.config(key))
        val p1 = FeatureflipProvider(first)
        p1.initialize(null)
        assertThat(server.requestsTo("/v1/client/identify")).isEmpty()

        val second = FeatureflipClient.get(server.config(key))
        val p2 = FeatureflipProvider(second)
        p2.initialize(ImmutableContext("u1"))
        val identifies = server.requestsTo("/v1/client/identify")
        assertThat(identifies).hasSize(1)
        assertThat(server.contextOf(identifies.single())["user_id"]).isEqualTo("u1")
        p1.shutdown()
        p2.shutdown()
    }

    @Test
    fun `a client handed in keeps its own identity`() = runBlocking {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(FeatureflipClient.get(server.config(key)))
        provider.initialize(null)

        assertThat(server.requests.first().headers["User-Agent"]).doesNotContain("featureflip-openfeature-android")
        provider.shutdown()
    }

    @Test
    fun `a provider read is reported as a flag read`() = runBlocking {
        server.evaluateFlags = flags
        val client = FeatureflipClient.get(server.config(key))
        val provider = FeatureflipProvider(client)
        provider.initialize(null)

        provider.getBooleanEvaluation("a", false, null)
        client.flushAndAwait()

        val evals = events().filter { it["type"] == "Evaluation" && it["flagKey"] == "a" }
        assertThat(evals).isNotEmpty
        provider.shutdown()
    }

    @Test
    fun `numbers decoded by the sdk resolve through the type guards`() = runBlocking {
        server.evaluateFlags = """{
            "i":{"value":3,"variation":"v","reason":"fallthrough"},
            "whole":{"value":3.0,"variation":"v","reason":"fallthrough"},
            "big":{"value":3000000000,"variation":"v","reason":"fallthrough"},
            "beyond":{"value":9223372036854775808,"variation":"v","reason":"fallthrough"},
            "huge":{"value":1e30,"variation":"v","reason":"fallthrough"},
            "d":{"value":2.5,"variation":"v","reason":"fallthrough"},
            "obj":{"value":{"a":[1,2.5,null,"x"]},"variation":"v","reason":"fallthrough"},
            "s":{"value":"42","variation":"v","reason":"fallthrough"}
        }"""
        val provider = FeatureflipProvider(server.config(key))
        provider.initialize(null)

        assertThat(provider.getIntegerEvaluation("i", 0, null).value).isEqualTo(3)
        assertThat(provider.getIntegerEvaluation("whole", 0, null).value).isEqualTo(3)
        assertThat(provider.getIntegerEvaluation("big", 0, null).errorCode).isEqualTo(ErrorCode.TYPE_MISMATCH)

        assertThat(provider.getLongEvaluation("big", 0L, null).value).isEqualTo(3_000_000_000L)
        assertThat(provider.getLongEvaluation("beyond", 0L, null).errorCode).isEqualTo(ErrorCode.TYPE_MISMATCH)
        assertThat(provider.getLongEvaluation("huge", 0L, null).errorCode).isEqualTo(ErrorCode.TYPE_MISMATCH)

        assertThat(provider.getDoubleEvaluation("huge", 0.0, null).value).isEqualTo(1e30)
        assertThat(provider.getDoubleEvaluation("beyond", 0.0, null).errorCode).isNull()
        assertThat(provider.getDoubleEvaluation("d", 0.0, null).value).isEqualTo(2.5)

        val obj = provider.getObjectEvaluation("obj", Value.Null, null)
        assertThat(obj.value).isEqualTo(
            Value.Structure(
                mapOf("a" to Value.List(listOf(Value.Integer(1), Value.Double(2.5), Value.Null, Value.String("x")))),
            ),
        )

        assertThat(provider.getBooleanEvaluation("s", false, null).errorCode).isEqualTo(ErrorCode.TYPE_MISMATCH)
        assertThat(provider.getStringEvaluation("s", "", null).value).isEqualTo("42")
        provider.shutdown()
    }

    @Test
    fun `a server-hidden flag is FLAG_NOT_FOUND`() = runBlocking {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(server.config(key))
        provider.initialize(null)

        assertThat(provider.getBooleanEvaluation("a", false, null).value).isTrue()
        val hidden = provider.getBooleanEvaluation("hidden", false, null)
        assertThat(hidden.errorCode).isEqualTo(ErrorCode.FLAG_NOT_FOUND)
        assertThat(hidden.value).isFalse()
        provider.shutdown()
    }

    @Test
    fun `track forwards the name and flattened metadata`() = runBlocking {
        server.evaluateFlags = flags
        val client = FeatureflipClient.get(server.config(key))
        val provider = FeatureflipProvider(client)
        provider.initialize(null)

        provider.track(
            "purchase",
            null,
            TrackingEventDetails(
                9.99,
                ImmutableStructure(
                    "currency" to Value.String("USD"),
                    "at" to Value.Instant(Instant.parse("2026-11-02T10:15:30Z")),
                ),
            ),
        )
        client.flushAndAwait()

        val custom = events().single { it["flagKey"] == "purchase" }
        assertThat(custom["metadata"]).isEqualTo(
            mapOf("currency" to "USD", "at" to "2026-11-02T10:15:30Z", "value" to 9.99),
        )
        provider.shutdown()
    }

    @Test
    fun `shutdown closes the handle the provider obtained`() = runBlocking {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(server.config(key))
        provider.initialize(null)
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(1)

        provider.shutdown()
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(0)
        Unit // a JUnit test method must return void; the assertion's type would hide it
    }

    @Test
    fun `the config path re-initializes with a fresh handle after shutdown`() = runBlocking {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(server.config(key))
        provider.initialize(null)
        provider.shutdown()
        provider.initialize(null)

        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(1)
        assertThat(provider.getBooleanEvaluation("a", false, null).value).isTrue()
        provider.shutdown()
    }

    @Test
    fun `a closed client-path provider refuses to initialize`() {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(FeatureflipClient.get(server.config(key)))
        runBlocking { provider.initialize(null) }
        provider.shutdown()

        assertThrows(OpenFeatureError.GeneralError::class.java) {
            runBlocking { provider.initialize(null) }
        }
    }

    @Test
    fun `reads before initialize are FLAG_NOT_FOUND`() {
        val provider = FeatureflipProvider(server.config(key))
        val r = provider.getBooleanEvaluation("a", true, null)
        assertThat(r.errorCode).isEqualTo(ErrorCode.FLAG_NOT_FOUND)
        assertThat(r.value).isTrue()
    }

    private suspend fun awaitRequest(path: String) =
        withTimeout(10_000) { while (server.requestsTo(path).isEmpty()) delay(20) }

    @Test
    fun `a shutdown during initialize leaves no live handle`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val gate = CountDownLatch(1)
        server.evaluateGate = gate
        val provider = FeatureflipProvider(server.config(key))
        val init = async(Dispatchers.Default) { runCatching { provider.initialize(null) } }
        awaitRequest("/v1/client/evaluate")

        provider.shutdown()
        gate.countDown()
        init.await()

        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(0)
    }

    @Test
    fun `a cancelled initialize leaves no live handle`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val gate = CountDownLatch(1)
        server.evaluateGate = gate
        val provider = FeatureflipProvider(server.config(key))
        val init = launch(Dispatchers.Default) { provider.initialize(null) }
        awaitRequest("/v1/client/evaluate")

        init.cancel()
        gate.countDown()
        init.join()

        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(0)
    }

    @Test
    fun `another live handle on the core makes the provider identify`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val other = FeatureflipClient.get(server.config(key)) // never initialized
        val provider = FeatureflipProvider(server.config(key))
        provider.initialize(ImmutableContext("of-user"))

        val identifies = server.requestsTo("/v1/client/identify")
        assertThat(identifies).hasSize(1)
        assertThat(server.contextOf(identifies.single())["user_id"]).isEqualTo("of-user")
        provider.shutdown()
        other.close()
    }

    @Test
    fun `a repeat initialize with an unchanged context does not identify again`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val other = FeatureflipClient.get(server.config(key))
        val provider = FeatureflipProvider(server.config(key))
        provider.initialize(ImmutableContext("of-user"))
        provider.initialize(ImmutableContext("of-user"))

        assertThat(server.requestsTo("/v1/client/identify")).hasSize(1)
        provider.shutdown()
        other.close()
    }

    @Test
    fun `a repeat client-path initialize with an unchanged context does not identify again`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(FeatureflipClient.get(server.config(key)))
        provider.initialize(ImmutableContext("of-user"))
        provider.initialize(ImmutableContext("of-user"))

        assertThat(server.requestsTo("/v1/client/identify")).hasSize(1)
        provider.shutdown()
    }

    @Test
    fun `track after shutdown on the client path is a no-op`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val client = FeatureflipClient.get(server.config(key))
        val keeper = FeatureflipClient.get(server.config(key)) // keeps the core alive
        val provider = FeatureflipProvider(client)
        provider.initialize(null)
        provider.shutdown()

        provider.track("late", null, null)
        keeper.flushAndAwait()

        assertThat(events().filter { it["flagKey"] == "late" }).isEmpty()
        keeper.close()
    }

    @Test
    fun `a shutdown between the check and the assignment leaves no live handle`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(server.config(key))
        provider.beforeAssign = { provider.shutdown() }

        assertThrows(OpenFeatureError.GeneralError::class.java) { runBlocking { provider.initialize(null) } }

        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(0)
        assertThat(provider.getBooleanEvaluation("a", false, null).errorCode).isEqualTo(ErrorCode.FLAG_NOT_FOUND)
    }

    @Test
    fun `a shutdown racing a client-path initialize closes the handed-in client and does not identify`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val client = FeatureflipClient.get(server.config(key))
        val keeper = FeatureflipClient.get(server.config(key))
        val provider = FeatureflipProvider(client)
        provider.beforeAssign = { provider.shutdown() }

        assertThrows(OpenFeatureError.GeneralError::class.java) {
            runBlocking { provider.initialize(ImmutableContext("u1")) }
        }

        assertThat(client.isInitialized).isFalse()
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(1)
        assertThat(server.requestsTo("/v1/client/identify")).isEmpty()
        keeper.close()
    }

    @Test
    fun `a shutdown during initialize throws instead of reporting ready`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val gate = CountDownLatch(1)
        server.evaluateGate = gate
        val provider = FeatureflipProvider(server.config(key))
        val init = async(Dispatchers.Default) { runCatching { provider.initialize(null) } }
        awaitRequest("/v1/client/evaluate")

        provider.shutdown()
        gate.countDown()

        assertThat(init.await().exceptionOrNull()).isInstanceOf(OpenFeatureError.GeneralError::class.java)
    }

    @Test
    fun `a cancelled repeat initialize keeps the previous handle`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(server.config(key))
        provider.initialize(null)

        val second = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { provider.initialize(null) }
        provider.beforeAssign = { second.cancel() }
        second.start()
        second.join()

        assertThat(provider.getBooleanEvaluation("a", false, null).value).isTrue()
        provider.shutdown()
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(0)
    }

    @Test
    fun `a client-path shutdown before initialize is not ignored`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(FeatureflipClient.get(server.config(key)))
        provider.shutdown()

        assertThrows(OpenFeatureError.GeneralError::class.java) {
            runBlocking { provider.initialize(ImmutableContext("u1")) }
        }
        assertThat(server.requestsTo("/v1/client/identify")).isEmpty()
    }

    @Test
    fun `a shutdown during the initial identify is reported`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val gate = CountDownLatch(1)
        server.identifyGate = gate
        val provider = FeatureflipProvider(FeatureflipClient.get(server.config(key)))
        val init = async(Dispatchers.Default) { runCatching { provider.initialize(ImmutableContext("u1")) } }
        awaitRequest("/v1/client/identify")

        provider.shutdown()
        gate.countDown()

        assertThat(init.await().exceptionOrNull()).isInstanceOf(OpenFeatureError.GeneralError::class.java)
        assertThrows(OpenFeatureError.GeneralError::class.java) { runBlocking { provider.initialize(null) } }
    }

    @Test
    fun `a client-path shutdown during the sdk initialize does not orphan the core`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val gate = CountDownLatch(1)
        server.evaluateGate = gate
        // The provider holds the core's only handle, and it is not initialized yet.
        val provider = FeatureflipProvider(FeatureflipClient.get(server.config(key, streaming = true)))
        val init = async(Dispatchers.Default) { runCatching { provider.initialize(null) } }
        awaitRequest("/v1/client/evaluate")

        provider.shutdown()
        gate.countDown()

        assertThat(init.await().exceptionOrNull()).isInstanceOf(OpenFeatureError.GeneralError::class.java)
        assertThat(FeatureflipClient.debugRefCount(key)).isEqualTo(0)
        // The SDK starts the stream at the end of its initialize, before the provider
        // can close the handle, so one connect may already be on the wire. An orphaned
        // core reconnects (first retry within 1s), so >= 2 connects by ~1.0s. Wait the
        // whole window and bound the total, with no early snapshot to race a slow CPU.
        delay(1_800)
        assertThat(server.requestsTo("/v1/client/stream").size).isLessThanOrEqualTo(1)
        assertThat(server.requestsTo("/v1/client/evaluate")).hasSize(1)
    }

    @Test
    fun `re-initializing after shutdown identifies even for an equal context`() = runBlocking<Unit> {
        server.evaluateFlags = flags
        val provider = FeatureflipProvider(server.config(key))
        provider.initialize(ImmutableContext("u1"))
        val app = FeatureflipClient.get(server.config(key)) // keeps the core alive
        provider.shutdown()
        app.identify(mapOf("user_id" to "u2"))

        provider.initialize(ImmutableContext("u1"))

        val users = server.requestsTo("/v1/client/identify").map { server.contextOf(it)["user_id"] }
        assertThat(users).containsExactly("u2", "u1")
        provider.shutdown()
        app.close()
    }
}
