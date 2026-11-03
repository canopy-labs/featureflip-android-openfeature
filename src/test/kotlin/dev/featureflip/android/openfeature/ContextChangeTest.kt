package dev.featureflip.android.openfeature

import dev.featureflip.android.FeatureflipClient
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

class ContextChangeTest {
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

    /** One string flag `who` naming the user the flags were evaluated for. */
    private fun whoFlags(user: String) = """{"who":{"value":"$user","variation":"v","reason":"fallthrough"}}"""

    private fun who(provider: FeatureflipProvider) = provider.getStringEvaluation("who", "default", null).value

    private fun identifiedUsers(): List<Any?> =
        server.requestsTo("/v1/client/identify").map { server.contextOf(it)["user_id"] }

    private suspend fun awaitRequests(path: String, n: Int) =
        withTimeout(10_000) { while (server.requestsTo(path).size < n) delay(20) }

    /**
     * Identify answers name the user they were sent for, and so does a later poll for a
     * user an identify succeeded for. Everything else answers `init`. A response for the
     * previous user that lands after an identify is the SDK's to drop (3.5.1), so the
     * tests need no ordering of their own.
     */
    private fun answerByUser() {
        val identified = ConcurrentHashMap.newKeySet<String>()
        server.identifyResponder = { context ->
            val user = context["user_id"].toString()
            identified.add(user)
            whoFlags(user)
        }
        server.evaluateResponder = { context ->
            whoFlags(context["user_id"]?.toString()?.takeIf { it in identified } ?: "init")
        }
    }

    @Test
    fun `onContextSet identifies the merged context`() = runBlocking<Unit> {
        val provider = FeatureflipProvider(server.config(key, context = mapOf("app" to "x")))
        answerByUser()
        provider.initialize(null)

        provider.onContextSet(null, ImmutableContext("u2"))

        val identifies = server.requestsTo("/v1/client/identify")
        assertThat(identifies).hasSize(1)
        assertThat(server.contextOf(identifies.single())).isEqualTo(mapOf("app" to "x", "user_id" to "u2"))
        assertThat(who(provider)).isEqualTo("u2")
        provider.shutdown()
    }

    @Test
    fun `an unchanged context makes no request`() = runBlocking<Unit> {
        // setProviderAndWait(ctx) followed by setEvaluationContext(ctx).
        val provider = FeatureflipProvider(server.config(key))
        answerByUser()
        val ctx = ImmutableContext("u1")
        provider.initialize(ctx)

        provider.onContextSet(ctx, ImmutableContext("u1"))

        assertThat(server.requestsTo("/v1/client/identify")).isEmpty()
        provider.shutdown()
    }

    @Test
    fun `a failed identify throws GeneralError and keeps serving the old flags`() = runBlocking<Unit> {
        val provider = FeatureflipProvider(server.config(key))
        answerByUser()
        server.identifyStatus = 500
        provider.initialize(null)

        assertThrows(OpenFeatureError.GeneralError::class.java) {
            runBlocking { provider.onContextSet(null, ImmutableContext("u2")) }
        }

        assertThat(server.requestsTo("/v1/client/identify")).hasSize(1)
        assertThat(who(provider)).isEqualTo("init")
        provider.shutdown()
    }

    @Test
    fun `identify runs off the calling thread`() = runBlocking<Unit> {
        val client = FeatureflipClient.get(server.config(key))
        val provider = FeatureflipProvider(client)
        answerByUser()
        provider.initialize(null)
        // Only the identify changes `who`.
        val threads = CopyOnWriteArrayList<String>()
        client.onUpdate { keys -> if ("who" in keys) threads.add(Thread.currentThread().name) }

        provider.onContextSet(null, ImmutableContext("u2"))

        // The SDK notifies an identify synchronously on the thread that called it.
        assertThat(threads).hasSize(1)
        assertThat(threads.single()).startsWith("DefaultDispatcher-worker")
        assertThat(Thread.currentThread().name).doesNotStartWith("DefaultDispatcher-worker")
        provider.shutdown()
    }

    @Test
    fun `two overlapping context sets land on the later one`() = runBlocking<Unit> {
        val provider = FeatureflipProvider(server.config(key))
        answerByUser()
        provider.initialize(null)
        val gate = CountDownLatch(1)
        server.identifyGate = gate

        val a = launch(Dispatchers.Default) { provider.onContextSet(null, ImmutableContext("u1")) }
        awaitRequests("/v1/client/identify", 1)
        // Undispatched: B has bumped the generation and is queued on the lock before we go on.
        val b = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            provider.onContextSet(null, ImmutableContext("u2"))
        }
        gate.countDown()
        a.join()
        b.join()

        assertThat(identifiedUsers()).containsExactly("u1", "u2")
        assertThat(who(provider)).isEqualTo("u2")
        provider.shutdown()
    }

    @Test
    fun `a superseded context set does not fetch`() = runBlocking<Unit> {
        val provider = FeatureflipProvider(server.config(key))
        answerByUser()
        provider.initialize(null)
        val gate = CountDownLatch(1)
        server.identifyGate = gate

        val a = launch(Dispatchers.Default) { provider.onContextSet(null, ImmutableContext("u1")) }
        awaitRequests("/v1/client/identify", 1)
        val b = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            provider.onContextSet(null, ImmutableContext("u2"))
        }
        val c = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            provider.onContextSet(null, ImmutableContext("u3"))
        }
        gate.countDown()
        a.join()
        b.join()
        c.join()

        assertThat(identifiedUsers()).containsExactly("u1", "u3")
        assertThat(who(provider)).isEqualTo("u3")
        provider.shutdown()
    }

    @Test
    fun `a cancelled context set releases the lock and records what landed`() = runBlocking<Unit> {
        val provider = FeatureflipProvider(server.config(key))
        answerByUser()
        provider.initialize(null)
        val gate = CountDownLatch(1)
        server.identifyGate = gate

        val a = launch(Dispatchers.Default) { provider.onContextSet(null, ImmutableContext("u1")) }
        awaitRequests("/v1/client/identify", 1)
        // The identify is on the wire and cannot be interrupted; cancel the caller anyway.
        a.cancel()
        gate.countDown()
        a.join()
        assertThat(a.isCancelled).isTrue()

        // u1 reached the core, so an equal follow-up is skipped...
        withTimeout(5_000) { provider.onContextSet(null, ImmutableContext("u1")) }
        assertThat(identifiedUsers()).containsExactly("u1")
        assertThat(who(provider)).isEqualTo("u1")
        // ...and the lock was released, so a different one goes through.
        withTimeout(5_000) { provider.onContextSet(null, ImmutableContext("u2")) }
        assertThat(identifiedUsers()).containsExactly("u1", "u2")
        assertThat(who(provider)).isEqualTo("u2")
        provider.shutdown()
    }

    @Test
    fun `a context set during initialize waits for it and lands`() = runBlocking<Unit> {
        val provider = FeatureflipProvider(server.config(key))
        answerByUser()
        val gate = CountDownLatch(1)
        server.evaluateGate = gate

        val init = launch(Dispatchers.Default) { provider.initialize(ImmutableContext("u1")) }
        // The initial evaluate is held at the server, so initialize holds the lock.
        awaitRequests("/v1/client/evaluate", 1)
        val set = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            provider.onContextSet(null, ImmutableContext("u2"))
        }
        gate.countDown()
        init.join()
        set.join()

        assertThat(server.contextOf(server.requestsTo("/v1/client/evaluate").first())["user_id"]).isEqualTo("u1")
        assertThat(identifiedUsers()).containsExactly("u2")
        assertThat(who(provider)).isEqualTo("u2")
        provider.shutdown()
    }

    @Test
    fun `a cancelled newer context set still lands`() = runBlocking<Unit> {
        // setEvaluationContextAndWait runs in the caller's scope, so B can be cancelled
        // with nothing newer to follow, after the SDK has already committed u2.
        val provider = FeatureflipProvider(server.config(key))
        answerByUser()
        provider.initialize(null)
        val gate = CountDownLatch(1)
        server.identifyGate = gate

        val a = launch(Dispatchers.Default) { provider.onContextSet(null, ImmutableContext("u1")) }
        awaitRequests("/v1/client/identify", 1)
        // Undispatched: B has published its request and is queued on the lock.
        val b = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            provider.onContextSet(null, ImmutableContext("u2"))
        }
        b.cancel()
        // Joined after the release: once published, B waits for the lock uncancellably.
        gate.countDown()
        a.join()
        b.join()
        assertThat(b.isCancelled).isTrue()

        assertThat(identifiedUsers()).containsExactly("u1", "u2")
        assertThat(who(provider)).isEqualTo("u2")
        provider.shutdown()
    }

    @Test
    fun `a context set cancelled during initialize still lands`() = runBlocking<Unit> {
        // The client path identifies a non-empty initial context, so this initialize
        // would identify u1 if no set arrived.
        val provider = FeatureflipProvider(FeatureflipClient.get(server.config(key)))
        answerByUser()
        val gate = CountDownLatch(1)
        server.evaluateGate = gate

        val init = launch(Dispatchers.Default) { provider.initialize(ImmutableContext("u1")) }
        awaitRequests("/v1/client/evaluate", 1)
        val b = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            provider.onContextSet(null, ImmutableContext("u2"))
        }
        b.cancel()
        // Joined after the release: once published, B waits for the lock uncancellably.
        gate.countDown()
        init.join()
        b.join()
        assertThat(b.isCancelled).isTrue()

        assertThat(identifiedUsers()).containsExactly("u2")
        assertThat(who(provider)).isEqualTo("u2")
        provider.shutdown()
    }

    @Test
    fun `a context set cancelled during initialize's own identify still lands`() = runBlocking<Unit> {
        val provider = FeatureflipProvider(FeatureflipClient.get(server.config(key)))
        answerByUser()
        val gate = CountDownLatch(1)
        server.identifyGate = gate

        // The client path identifies u1 at initialize; hold that identify at the server.
        val init = launch(Dispatchers.Default) { provider.initialize(ImmutableContext("u1")) }
        awaitRequests("/v1/client/identify", 1)
        val b = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            provider.onContextSet(null, ImmutableContext("u2"))
        }
        b.cancel()
        // Joined after the release: once published, B waits for the lock uncancellably.
        gate.countDown()
        init.join()
        b.join()
        assertThat(b.isCancelled).isTrue()

        assertThat(identifiedUsers()).containsExactly("u1", "u2")
        assertThat(who(provider)).isEqualTo("u2")
        provider.shutdown()
    }

    @Test
    fun `a context set whose holder and successor are both cancelled still lands the newest`() = runBlocking<Unit> {
        // Both callers in one lifecycle scope, torn down together while A's identify is out.
        val provider = FeatureflipProvider(server.config(key))
        answerByUser()
        provider.initialize(null)
        val gate = CountDownLatch(1)
        server.identifyGate = gate

        val a = launch(Dispatchers.Default) { provider.onContextSet(null, ImmutableContext("u1")) }
        awaitRequests("/v1/client/identify", 1)
        val b = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            provider.onContextSet(null, ImmutableContext("u2"))
        }
        a.cancel()
        b.cancel()
        gate.countDown()
        a.join()
        b.join()

        assertThat(a.isCancelled).isTrue()
        assertThat(b.isCancelled).isTrue()
        assertThat(identifiedUsers()).containsExactly("u1", "u2")
        assertThat(who(provider)).isEqualTo("u2")
        provider.shutdown()
    }

    @OptIn(DelicateCoroutinesApi::class) // CoroutineStart.ATOMIC: run the body on a job cancelled before it starts
    @Test
    fun `an uncontended context set cancelled before its identify still lands`() = runBlocking<Unit> {
        val provider = FeatureflipProvider(server.config(key))
        answerByUser()
        provider.initialize(null)

        // ATOMIC: the body runs even though the job is cancelled before it starts, so the
        // call publishes and takes the free lock (withLock's fast path never checks
        // cancellation) on an already-cancelled job, ahead of its identify.
        val job = launch(start = CoroutineStart.ATOMIC) { provider.onContextSet(null, ImmutableContext("u2")) }
        job.cancel()
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(identifiedUsers()).containsExactly("u2")
        assertThat(who(provider)).isEqualTo("u2")
        provider.shutdown()
    }

    @Test
    fun `onContextSet before initialize throws GeneralError`() {
        val provider = FeatureflipProvider(server.config(key))

        val error = assertThrows(OpenFeatureError.GeneralError::class.java) {
            runBlocking { provider.onContextSet(null, ImmutableContext("u1")) }
        }

        assertThat(error.message).contains("closed or was never initialized")
        assertThat(server.requests).isEmpty()
    }
}
