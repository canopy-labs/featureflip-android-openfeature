package dev.featureflip.android.openfeature

import dev.featureflip.android.FlagValue
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger

class ResolutionMappingTest {
    private fun fv(value: Any?, variation: String = "on", reason: String = "fallthrough", prereq: String? = null) =
        FlagValue(value, variation, reason, prereq)

    private fun bool(flag: FlagValue?, default: Boolean = false): ProviderEvaluation<Boolean> =
        resolve("k", default, { flag }, ::asBoolean, "boolean")

    @Test
    fun `rule match maps to TARGETING_MATCH with its ruleId`() {
        val e = bool(fv(true, reason = "rule-match:r1"))
        assertThat(e.reason).isEqualTo("TARGETING_MATCH")
        assertThat(e.metadata.getString("ruleId")).isEqualTo("r1")
        assertThat(e.variant).isEqualTo("on")
        assertThat(e.value).isTrue()
    }

    @Test
    fun `a rule match with an empty id omits ruleId`() {
        val e = bool(fv(true, reason = "rule-match:"))
        assertThat(e.reason).isEqualTo("TARGETING_MATCH")
        assertThat(e.metadata.getString("ruleId")).isNull()
    }

    @Test
    fun `fallthrough maps to DEFAULT`() {
        assertThat(bool(fv(true)).reason).isEqualTo("DEFAULT")
    }

    @Test
    fun `flag-disabled maps to DISABLED`() {
        assertThat(bool(fv(false, "off", "flag-disabled")).reason).isEqualTo("DISABLED")
    }

    @Test
    fun `prerequisite-failed maps to PREREQUISITE_FAILED with its prerequisiteKey and serves the value`() {
        val e = bool(fv(false, "off", "prerequisite-failed", "parent"), default = true)
        assertThat(e.reason).isEqualTo(PREREQUISITE_FAILED)
        assertThat(e.metadata.getString("prerequisiteKey")).isEqualTo("parent")
        assertThat(e.value).isFalse()
        assertThat(e.errorCode).isNull()
    }

    @Test
    fun `an error reason serves the default as GENERAL and skips the type guard`() {
        val e = bool(fv("not-a-bool", "off", "error"), default = true)
        assertThat(e.value).isTrue()
        assertThat(e.reason).isEqualTo("ERROR")
        assertThat(e.errorCode).isEqualTo(ErrorCode.GENERAL)
        assertThat(e.errorMessage).isEqualTo("Flag 'k' could not be evaluated")
    }

    @Test
    fun `a flag-not-found reason is FLAG_NOT_FOUND`() {
        val e = bool(fv(null, "", "flag-not-found"), default = true)
        assertThat(e.value).isTrue()
        assertThat(e.reason).isEqualTo("ERROR")
        assertThat(e.errorCode).isEqualTo(ErrorCode.FLAG_NOT_FOUND)
    }

    @Test
    fun `a missing flag is FLAG_NOT_FOUND`() {
        val e = bool(null, default = true)
        assertThat(e.value).isTrue()
        assertThat(e.reason).isEqualTo("ERROR")
        assertThat(e.errorCode).isEqualTo(ErrorCode.FLAG_NOT_FOUND)
        assertThat(e.errorMessage).isEqualTo("Flag 'k' was not found")
    }

    @Test
    fun `an unknown reason maps to UNKNOWN and serves the value`() {
        val e = bool(fv(true, reason = "experiment:x"))
        assertThat(e.reason).isEqualTo("UNKNOWN")
        assertThat(e.value).isTrue()
        assertThat(e.errorCode).isNull()
    }

    @Test
    fun `an empty variation leaves variant null`() {
        assertThat(bool(fv(true, variation = "")).variant).isNull()
    }

    @Test
    fun `a throwing lookup is GENERAL with the exceptions message`() {
        val e = resolve("k", true, { throw IllegalStateException("boom") }, ::asBoolean, "boolean")
        assertThat(e.value).isTrue()
        assertThat(e.reason).isEqualTo("ERROR")
        assertThat(e.errorCode).isEqualTo(ErrorCode.GENERAL)
        assertThat(e.errorMessage).isEqualTo("boom")
    }

    @Test
    fun `a wrong type is TYPE_MISMATCH`() {
        val e = bool(fv("s"), default = true)
        assertThat(e.value).isTrue()
        assertThat(e.reason).isEqualTo("ERROR")
        assertThat(e.errorCode).isEqualTo(ErrorCode.TYPE_MISMATCH)
        assertThat(e.errorMessage).isEqualTo("Flag 'k' did not resolve to a boolean value")
    }

    @Test
    fun `mapReason table`() {
        assertThat(mapReason("rule-match:x")).isEqualTo("TARGETING_MATCH")
        assertThat(mapReason("fallthrough")).isEqualTo("DEFAULT")
        assertThat(mapReason("flag-disabled")).isEqualTo("DISABLED")
        assertThat(mapReason("prerequisite-failed")).isEqualTo("PREREQUISITE_FAILED")
        assertThat(mapReason("zzz")).isEqualTo("UNKNOWN")
    }

    @Test
    fun `asBoolean and asString`() {
        assertThat(asBoolean(true)).isTrue()
        assertThat(asBoolean("true")).isNull()
        assertThat(asString("s")).isEqualTo("s")
        assertThat(asString(3)).isNull()
    }

    @Test
    fun `asInt`() {
        assertThat(asInt(3)).isEqualTo(3)
        assertThat(asInt(3L)).isEqualTo(3)
        assertThat(asInt(3.0)).isEqualTo(3)
        assertThat(asInt(3.5)).isNull()
        assertThat(asInt(3_000_000_000L)).isNull()
        assertThat(asInt(1e10)).isNull()
        assertThat(asInt("3")).isNull()
    }

    @Test
    fun `asLong`() {
        assertThat(asLong(3)).isEqualTo(3L)
        assertThat(asLong(3_000_000_000L)).isEqualTo(3_000_000_000L)
        assertThat(asLong(BigInteger("9223372036854775807"))).isEqualTo(Long.MAX_VALUE)
        assertThat(asLong(BigInteger("9223372036854775808"))).isNull()
        assertThat(asLong(3.0)).isEqualTo(3L)
        assertThat(asLong(9.223372036854775807E18)).isNull()
        assertThat(asLong(1e30)).isNull()
        assertThat(asLong(3.5)).isNull()
        assertThat(asLong("3")).isNull()
    }

    @Test
    fun `asDouble`() {
        assertThat(asDouble(3)).isEqualTo(3.0)
        assertThat(asDouble(BigInteger("10"))).isEqualTo(BigInteger("10").toDouble())
        assertThat(asDouble(1e30)).isEqualTo(1e30)
        assertThat(asDouble("3")).isNull()
    }

    @Test
    fun `asStructure`() {
        assertThat(asStructure(mapOf("a" to 1))).isInstanceOf(Value.Structure::class.java)
        assertThat(asStructure(listOf(1))).isInstanceOf(Value.List::class.java)
        assertThat(asStructure(null)).isNull()
        assertThat(asStructure("s")).isNull()
        assertThat(asStructure(3)).isNull()
        assertThat(asStructure(true)).isNull()
    }

    @Test
    fun `toValue recurses`() {
        assertThat(toValue(3)).isEqualTo(Value.Integer(3))
        assertThat(toValue(3L)).isEqualTo(Value.Long(3L))
        assertThat(toValue(BigInteger("10"))).isEqualTo(Value.Double(10.0))
        assertThat(toValue(2.5)).isEqualTo(Value.Double(2.5))
        assertThat(toValue(null)).isEqualTo(Value.Null)
        assertThat(toValue(true)).isEqualTo(Value.Boolean(true))
        assertThat(toValue("s")).isEqualTo(Value.String("s"))
        assertThat(toValue(mapOf("a" to listOf(1, mapOf("b" to null)))))
            .isEqualTo(Value.Structure(mapOf("a" to Value.List(listOf(Value.Integer(1), Value.Structure(mapOf("b" to Value.Null)))))))
        assertThat(toValue(java.util.UUID(0, 0))).isEqualTo(Value.String("00000000-0000-0000-0000-000000000000"))
    }
}
