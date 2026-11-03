package dev.featureflip.android.openfeature

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.featureflip.android.FeatureflipConfig
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A MockWebServer standing in for the evaluation API, keyed by path. State is
 * mutable and volatile so a test can change what the next request sees.
 *
 * [identifyGate] / [evaluateGate] block the next matching request until the test
 * counts the latch down, which holds a call in flight on a known thread.
 */
internal class FakeEvalServer : AutoCloseable {
    private val json = jacksonObjectMapper()
    private val server = MockWebServer()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile var evaluateFlags: String = "{}"
    @Volatile var identifyFlags: String = "{}"
    @Volatile var identifyStatus: Int = 200
    @Volatile var identifyGate: CountDownLatch? = null
    @Volatile var evaluateGate: CountDownLatch? = null

    /** Runs on the dispatcher thread at the start of every evaluate request. */
    @Volatile var onEvaluate: (() -> Unit)? = null

    /** Runs on the dispatcher thread at the start of every identify request. */
    @Volatile var onIdentify: (() -> Unit)? = null

    /** When set, answers each evaluate from its request context instead of [evaluateFlags]. */
    @Volatile var evaluateResponder: ((Map<String, Any?>) -> String)? = null

    /** When set, answers each identify from its request context instead of [identifyFlags]. */
    @Volatile var identifyResponder: ((Map<String, Any?>) -> String)? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded.add(request)
                return when (request.url.encodedPath) {
                    "/v1/client/evaluate" -> {
                        onEvaluate?.invoke()
                        await(evaluateGate)
                        val flags = evaluateResponder?.invoke(contextOf(request)) ?: evaluateFlags
                        ok("""{"flags":$flags}""")
                    }
                    "/v1/client/identify" -> {
                        onIdentify?.invoke()
                        await(identifyGate)
                        if (identifyStatus != 200) {
                            MockResponse.Builder().code(identifyStatus).build()
                        } else {
                            val flags = identifyResponder?.invoke(contextOf(request)) ?: identifyFlags
                            ok("""{"flags":$flags}""")
                        }
                    }
                    "/v1/client/stream" -> MockResponse.Builder().code(503).build()
                    "/v1/client/events" -> MockResponse.Builder().code(202).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    private fun await(gate: CountDownLatch?) {
        if (gate != null) gate.await(30, TimeUnit.SECONDS)
    }

    private fun ok(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    /** Requests in arrival order. */
    val requests: List<RecordedRequest> get() = recorded.toList()

    fun requestsTo(path: String): List<RecordedRequest> = recorded.filter { it.url.encodedPath == path }

    /** The `context` object of an evaluate or identify body. */
    fun contextOf(request: RecordedRequest): Map<String, Any?> {
        val body: Map<String, Any?> = json.readValue(request.body!!.utf8())
        @Suppress("UNCHECKED_CAST")
        return body["context"] as Map<String, Any?>
    }

    fun config(
        clientKey: String,
        streaming: Boolean = false,
        pollIntervalMs: Long = 60_000,
        context: Map<String, Any?> = emptyMap(),
        wrapperName: String? = null,
        wrapperVersion: String? = null,
    ) = FeatureflipConfig(
        clientKey = clientKey,
        baseUrl = server.url("/").toString().trimEnd('/'),
        context = context,
        streaming = streaming,
        pollIntervalMs = pollIntervalMs,
        wrapperName = wrapperName,
        wrapperVersion = wrapperVersion,
    )

    override fun close() {
        server.close()
    }
}
