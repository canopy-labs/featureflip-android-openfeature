@file:OptIn(ExperimentalIsolatedApi::class)

package dev.featureflip.android.openfeature

import dev.featureflip.android.FeatureflipClient
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.OpenFeatureAPIInstance
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.isolated.ExperimentalIsolatedApi
import dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The provider driven through a real, isolated OpenFeature Kotlin SDK instance, over
 * a real [FeatureflipClient] talking to [FakeEvalServer].
 */
class OpenFeatureE2eTest {
    private lateinit var server: FakeEvalServer
    private lateinit var key: String
    private lateinit var api: OpenFeatureAPIInstance

    @BeforeEach
    fun setUp() {
        server = FakeEvalServer()
        key = "client-" + UUID.randomUUID()
        api = createOpenFeatureAPIInstance()
    }

    @AfterEach
    fun tearDown() {
        runBlocking { api.shutdown() }
        FeatureflipClient.resetForTesting()
        server.close()
    }

    private fun flag(value: Boolean, variation: String, reason: String) =
        """{"value":$value,"variation":"$variation","reason":"$reason"}"""

    /** Answers `user-2`'s requests with [identifyA] and everyone else's with [initialA]. */
    private fun answerByUser(initialA: String, identifyA: String) {
        val responder = { context: Map<String, Any?> ->
            "{\"a\":${if (context["user_id"] == "user-2") identifyA else initialA}}"
        }
        server.evaluateResponder = responder
        server.identifyResponder = responder
    }

    @Test
    fun `setProviderAndWait serves the targeted value with its details`() = runBlocking<Unit> {
        server.evaluateFlags = """{"a":${flag(true, "on", "rule-match:r1")}}"""
        api.setProviderAndWait(FeatureflipProvider(server.config(key)), ImmutableContext("user-1"))

        val details = api.getClient().getBooleanDetails("a", false)

        assertThat(details.value).isTrue()
        assertThat(details.reason).isEqualTo("TARGETING_MATCH")
        assertThat(details.variant).isEqualTo("on")
        assertThat(details.metadata.getString("ruleId")).isEqualTo("r1")
        assertThat(details.errorCode).isNull()
    }

    @Test
    fun `setEvaluationContextAndWait re-targets`() = runBlocking<Unit> {
        answerByUser(
            initialA = flag(true, "on", "fallthrough"),
            identifyA = flag(false, "off", "fallthrough"),
        )
        api.setProviderAndWait(FeatureflipProvider(server.config(key)), ImmutableContext("user-1"))

        api.setEvaluationContextAndWait(ImmutableContext("user-2"))

        val identifies = server.requestsTo("/v1/client/identify")
        assertThat(identifies).hasSize(1)
        assertThat(server.contextOf(identifies.single())["user_id"]).isEqualTo("user-2")
        assertThat(api.getClient().getBooleanValue("a", true)).isFalse()
        assertThat(api.getStatus()).isEqualTo(OpenFeatureStatus.Ready)
    }

    @Test
    fun `a failed context change moves status to Error and the old flags keep serving`() = runBlocking<Unit> {
        server.evaluateFlags = """{"a":${flag(true, "on", "fallthrough")}}"""
        server.identifyStatus = 500
        api.setProviderAndWait(FeatureflipProvider(server.config(key)), ImmutableContext("user-1"))

        api.setEvaluationContextAndWait(ImmutableContext("user-2"))

        assertThat(api.getStatus()).isInstanceOf(OpenFeatureStatus.Error::class.java)
        assertThat(server.requestsTo("/v1/client/identify")).hasSize(1)
        assertThat(api.getClient().getBooleanValue("a", false)).isTrue()
    }

    @Test
    fun `ProviderConfigurationChanged reaches api observers`() = runBlocking<Unit> {
        coroutineScope {
            server.evaluateFlags = """{"a":${flag(true, "on", "fallthrough")}}"""
            val events = Channel<OpenFeatureProviderEvents.ProviderConfigurationChanged>(Channel.UNLIMITED)
            val collecting = launch(start = CoroutineStart.UNDISPATCHED, context = Dispatchers.Default) {
                api.observe<OpenFeatureProviderEvents.ProviderConfigurationChanged>().collect { events.send(it) }
            }
            api.setProviderAndWait(FeatureflipProvider(server.config(key, pollIntervalMs = 100)), ImmutableContext("user-1"))

            server.evaluateFlags = """{"a":${flag(false, "off", "fallthrough")}}"""
            val event = withTimeout(10_000) { events.receive() }

            assertThat(event.eventDetails?.flagsChanged).isEqualTo(setOf("a"))
            collecting.cancel()
        }
    }

    @Test
    fun `setting the context the provider started with makes no request`() = runBlocking<Unit> {
        server.evaluateFlags = """{"a":${flag(true, "on", "fallthrough")}}"""
        api.setProviderAndWait(FeatureflipProvider(server.config(key)), ImmutableContext("user-1"))

        api.setEvaluationContextAndWait(ImmutableContext("user-1"))

        assertThat(server.requestsTo("/v1/client/identify")).isEmpty()
        assertThat(api.getStatus()).isEqualTo(OpenFeatureStatus.Ready)
    }
}
