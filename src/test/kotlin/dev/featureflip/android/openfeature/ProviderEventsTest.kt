package dev.featureflip.android.openfeature

import dev.featureflip.android.FeatureflipClient
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProviderEventsTest {
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

    private fun flags(a: Boolean, b: Boolean = true) =
        """{"a":{"value":$a,"variation":"v","reason":"fallthrough"},"b":{"value":$b,"variation":"v","reason":"fallthrough"}}"""

    /** Collects `observe()` into a channel, live before the caller goes on. */
    private fun CoroutineScope.collect(provider: FeatureflipProvider): Channel<OpenFeatureProviderEvents> {
        val channel = Channel<OpenFeatureProviderEvents>(Channel.UNLIMITED)
        launch(start = CoroutineStart.UNDISPATCHED) { provider.observe().collect { channel.send(it) } }
        return channel
    }

    private fun changed(event: OpenFeatureProviderEvents): Set<String>? =
        (event as? OpenFeatureProviderEvents.ProviderConfigurationChanged)?.eventDetails?.flagsChanged

    private suspend fun awaitRequests(path: String, n: Int, timeoutMs: Long = 10_000) =
        withTimeout(timeoutMs) { while (server.requestsTo(path).size < n) delay(20) }

    @Test
    fun `a polled change emits ConfigurationChanged with the changed keys`() = runBlocking<Unit> {
        coroutineScope {
            server.evaluateFlags = flags(a = true)
            val provider = FeatureflipProvider(server.config(key, pollIntervalMs = 100))
            val events = collect(provider)
            provider.initialize(null)

            server.evaluateFlags = flags(a = false)
            val event = withTimeout(5_000) { events.receive() }

            assertThat(event).isInstanceOf(OpenFeatureProviderEvents.ProviderConfigurationChanged::class.java)
            assertThat(changed(event)).isEqualTo(setOf("a"))
            provider.shutdown()
            coroutineContext[Job]!!.cancelChildren()
        }
    }

    @Test
    fun `initialize emits nothing`() = runBlocking<Unit> {
        coroutineScope {
            server.evaluateFlags = flags(a = true)
            val provider = FeatureflipProvider(server.config(key, pollIntervalMs = 100))
            val events = collect(provider)
            provider.initialize(null)

            assertThat(server.requestsTo("/v1/client/evaluate")).isNotEmpty()
            assertThat(withTimeoutOrNull(500) { events.receive() }).isNull()
            provider.shutdown()
            coroutineContext[Job]!!.cancelChildren()
        }
    }

    @Test
    fun `a provider identify does not emit, and a later poll change still does`() = runBlocking<Unit> {
        coroutineScope {
            // Initial load is state A. The identify answers state B, and every poll after the
            // first evaluate also answers B, so only the identify itself differs from A.
            server.evaluateFlags = flags(a = true)
            server.identifyFlags = flags(a = false, b = false)
            val evaluates = AtomicInteger()
            // The poller's immediate first poll (evaluate #2) would otherwise race the
            // provider's identify: answering B before `identifying` is raised emits a
            // genuine poll change. Holding it until the identify request has reached the
            // server means `identifying` is already raised (it is incremented before the
            // request goes out), so any B it applies lands during or after the identify.
            val identified = CountDownLatch(1)
            server.onIdentify = { identified.countDown() }
            server.onEvaluate = {
                if (evaluates.incrementAndGet() > 1) {
                    identified.await(10, TimeUnit.SECONDS)
                    server.evaluateFlags = flags(a = false, b = false)
                }
            }
            val provider = FeatureflipProvider(FeatureflipClient.get(server.config(key, pollIntervalMs = 100)))
            val events = collect(provider)
            // The client path identifies a non-empty context at initialize.
            provider.initialize(ImmutableContext("u1"))
            assertThat(server.requestsTo("/v1/client/identify")).isNotEmpty()

            assertThat(withTimeoutOrNull(500) { events.receive() }).isNull()

            server.onEvaluate = null
            server.evaluateFlags = flags(a = false, b = true)
            val event = withTimeout(5_000) { events.receive() }
            assertThat(changed(event)).isEqualTo(setOf("b"))
            provider.shutdown()
            coroutineContext[Job]!!.cancelChildren()
        }
    }

    @Test
    fun `initialize twice subscribes once`() = runBlocking<Unit> {
        coroutineScope {
            server.evaluateFlags = flags(a = true)
            val provider = FeatureflipProvider(server.config(key, pollIntervalMs = 100))
            val events = collect(provider)
            provider.initialize(null)
            provider.initialize(null)

            server.evaluateFlags = flags(a = false)
            assertThat(changed(withTimeout(5_000) { events.receive() })).isEqualTo(setOf("a"))
            assertThat(withTimeoutOrNull(1_000) { events.receive() }).isNull()
            provider.shutdown()
            coroutineContext[Job]!!.cancelChildren()
        }
    }

    @Test
    fun `shutdown stops events`() = runBlocking<Unit> {
        coroutineScope {
            server.evaluateFlags = flags(a = true)
            // An app handle on the same core keeps it polling after the provider lets go
            // of its own handle. Without it the core dies with the provider's handle, and
            // no event could arrive whether or not the provider unsubscribed.
            val app = FeatureflipClient.get(server.config(key, pollIntervalMs = 100))
            try {
                app.initialize()
                val provider = FeatureflipProvider(server.config(key, pollIntervalMs = 100))
                val events = collect(provider)
                provider.initialize(null)
                provider.shutdown()

                val appSaw = CountDownLatch(1)
                app.onUpdate { appSaw.countDown() }
                server.evaluateFlags = flags(a = false)
                // The core delivered the change to its listeners, so a provider listener
                // left registered would have emitted it by now.
                assertThat(appSaw.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(withTimeoutOrNull(500) { events.receive() }).isNull()
            } finally {
                app.close()
            }
            coroutineContext[Job]!!.cancelChildren()
        }
    }

    @Test
    fun `a stalled collector never blocks the poller`() = runBlocking<Unit> {
        coroutineScope {
            val flip = AtomicBoolean(true)
            server.evaluateFlags = flags(a = true)
            server.onEvaluate = { server.evaluateFlags = flags(a = flip.getAndSet(!flip.get())) }
            val provider = FeatureflipProvider(server.config(key, pollIntervalMs = 20))
            launch(start = CoroutineStart.UNDISPATCHED) {
                provider.observe().collect { awaitCancellation() }
            }
            provider.initialize(null)

            // More polls than the 64-slot buffer, each a change, with the collector stuck on the first.
            // 100 polls at 20 ms is two seconds unloaded; a busy CI runner needs headroom.
            awaitRequests("/v1/client/evaluate", 100, timeoutMs = 30_000)

            // `b` never changed while `a` alternated, so only a poller still running after
            // the overflow can serve its new value. Set through onEvaluate so a flip already
            // in flight cannot overwrite it.
            server.onEvaluate = { server.evaluateFlags = flags(a = false, b = false) }
            withTimeout(10_000) {
                while (provider.getBooleanEvaluation("b", true, null).value) delay(20)
            }
            provider.shutdown()
            coroutineContext[Job]!!.cancelChildren()
        }
    }
}
