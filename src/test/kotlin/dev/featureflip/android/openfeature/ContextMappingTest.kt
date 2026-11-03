package dev.featureflip.android.openfeature

import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.ImmutableStructure
import dev.openfeature.kotlin.sdk.TrackingEventDetails
import dev.openfeature.kotlin.sdk.Value
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ContextMappingTest {
    @Test
    fun `null context maps to an empty map`() {
        assertThat(toFeatureflipContext(null)).isEmpty()
    }

    @Test
    fun `targetingKey becomes user_id`() {
        assertThat(toFeatureflipContext(ImmutableContext("u1"))).isEqualTo(mapOf("user_id" to "u1"))
    }

    @Test
    fun `an empty targetingKey adds no user_id`() {
        assertThat(toFeatureflipContext(ImmutableContext())).isEmpty()
    }

    @Test
    fun `explicit user_id wins over targetingKey`() {
        val ctx = ImmutableContext("tk", mapOf("user_id" to Value.String("a")))
        assertThat(toFeatureflipContext(ctx)).isEqualTo(mapOf("user_id" to "a"))
    }

    @Test
    fun `explicit userId wins over targetingKey and keeps its spelling`() {
        val ctx = ImmutableContext("tk", mapOf("userId" to Value.String("a")))
        assertThat(toFeatureflipContext(ctx)).isEqualTo(mapOf("userId" to "a"))
    }

    @Test
    fun `attributes convert to plain values`() {
        val ctx = ImmutableContext(
            attributes = mapOf(
                "s" to Value.String("x"),
                "b" to Value.Boolean(true),
                "i" to Value.Integer(3),
                "l" to Value.Long(3_000_000_000L),
                "d" to Value.Double(2.5),
                "n" to Value.Null,
                "m" to Value.Structure(mapOf("k" to Value.Integer(1), "l" to Value.List(listOf(Value.String("z"))))),
                "list" to Value.List(listOf(Value.Integer(1), Value.Structure(mapOf("a" to Value.Boolean(false))))),
            ),
        )
        val out = toFeatureflipContext(ctx)
        assertThat(out["s"]).isEqualTo("x")
        assertThat(out["b"]).isEqualTo(true)
        assertThat(out["i"]).isInstanceOf(Int::class.javaObjectType).isEqualTo(3)
        assertThat(out["l"]).isInstanceOf(Long::class.javaObjectType).isEqualTo(3_000_000_000L)
        assertThat(out["d"]).isInstanceOf(Double::class.javaObjectType).isEqualTo(2.5)
        assertThat(out).containsKey("n")
        assertThat(out["n"]).isNull()
        assertThat(out["m"]).isInstanceOf(Map::class.java).isEqualTo(mapOf("k" to 1, "l" to listOf("z")))
        assertThat(out["list"]).isInstanceOf(List::class.java).isEqualTo(listOf(1, mapOf("a" to false)))
    }

    @Test
    fun `instant becomes an ISO-8601 string`() {
        val i = Instant.parse("2026-11-02T10:15:30Z")
        val ctx = ImmutableContext(
            attributes = mapOf("at" to Value.Instant(i), "m" to Value.Structure(mapOf("at" to Value.Instant(i)))),
        )
        val out = toFeatureflipContext(ctx)
        assertThat(out["at"]).isEqualTo("2026-11-02T10:15:30Z")
        assertThat(out["m"]).isEqualTo(mapOf("at" to "2026-11-02T10:15:30Z"))
    }

    @Test
    fun `openfeature attributes win key by key over the config context`() {
        val merged = mergeContext(mapOf("plan" to "free", "app" to "x"), mapOf("plan" to "pro"))
        assertThat(merged).isEqualTo(mapOf("plan" to "pro", "app" to "x"))
    }

    @Test
    fun `an empty openfeature context keeps the config context`() {
        val base = mapOf<String, Any?>("plan" to "free")
        assertThat(mergeContext(base, emptyMap())).isEqualTo(base)
    }

    @Test
    fun `an openfeature identity replaces both config identity spellings`() {
        val merged = mergeContext(
            mapOf("user_id" to "cfg", "userId" to "cfg2", "app" to "x"),
            mapOf("userId" to "of"),
        )
        assertThat(merged).isEqualTo(mapOf("app" to "x", "userId" to "of"))
    }

    @Test
    fun `tracking with no details sends no metadata`() {
        assertThat(trackingMetadata(null)).isNull()
    }

    @Test
    fun `tracking flattens the structure and adds value`() {
        val d = TrackingEventDetails(9.99, ImmutableStructure("currency" to Value.String("USD")))
        assertThat(trackingMetadata(d)).isEqualTo(mapOf("currency" to "USD", "value" to 9.99))
    }

    @Test
    fun `tracking omits an absent value`() {
        val d = TrackingEventDetails(null, ImmutableStructure("currency" to Value.String("USD")))
        assertThat(trackingMetadata(d)).isEqualTo(mapOf("currency" to "USD"))
    }

    @Test
    fun `tracking converts an instant`() {
        val d = TrackingEventDetails(null, ImmutableStructure("at" to Value.Instant(Instant.parse("2026-11-02T10:15:30Z"))))
        assertThat(trackingMetadata(d)).isEqualTo(mapOf("at" to "2026-11-02T10:15:30Z"))
    }
}
