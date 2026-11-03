package dev.featureflip.android.openfeature

import dev.featureflip.android.FlagValue
import dev.openfeature.kotlin.sdk.EvaluationMetadata
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.Reason
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import java.math.BigInteger

/**
 * The reason returned when a flag short-circuited because a prerequisite did not
 * serve its expected variation. No standard OpenFeature reason models it, and
 * reasons are open strings, so it is surfaced verbatim (as the Node, .NET, Python,
 * Go and Java providers do) rather than mislabelled as DEFAULT or DISABLED.
 */
internal const val PREREQUISITE_FAILED = "PREREQUISITE_FAILED"

private const val RULE_MATCH_PREFIX = "rule-match:"
private const val TWO_POW_63 = 9.223372036854775807E18

/**
 * Builds the evaluation OpenFeature should see for one flag.
 *
 * [lookup] is a lambda so the "flagDetail throws" path is testable: the client is
 * final with a private constructor, and its `flagDetail` never throws in practice.
 * A null result means the flag is absent. A [guard] returns null when the served
 * value is not the requested type, which is reported as TYPE_MISMATCH.
 *
 * Order: lookup (an exception is GENERAL), then null or `flag-not-found` is
 * FLAG_NOT_FOUND, then reason `error` is GENERAL with the guard skipped (guarding
 * a default would mislabel a real error as TYPE_MISMATCH), then the guard.
 * `prerequisite-failed` is NOT an error: it serves the off variation, a real value.
 */
internal fun <T> resolve(
    key: String,
    defaultValue: T,
    lookup: () -> FlagValue?,
    guard: (Any?) -> T?,
    expectedType: String,
): ProviderEvaluation<T> {
    val flag = try {
        lookup()
    } catch (e: Exception) {
        return error(defaultValue, ErrorCode.GENERAL, e.message ?: e.toString())
    }
    if (flag == null || flag.reason == "flag-not-found") {
        return error(defaultValue, ErrorCode.FLAG_NOT_FOUND, "Flag '$key' was not found")
    }
    if (flag.reason == "error") {
        return error(defaultValue, ErrorCode.GENERAL, "Flag '$key' could not be evaluated")
    }
    val value = guard(flag.value)
        ?: return error(defaultValue, ErrorCode.TYPE_MISMATCH, "Flag '$key' did not resolve to a $expectedType value")

    val ruleId = if (flag.reason.startsWith(RULE_MATCH_PREFIX)) {
        flag.reason.removePrefix(RULE_MATCH_PREFIX).ifEmpty { null }
    } else {
        null
    }
    val prerequisiteKey = flag.prerequisiteKey
    val metadata = if (ruleId == null && prerequisiteKey == null) {
        EvaluationMetadata.EMPTY
    } else {
        val builder = EvaluationMetadata.builder()
        if (ruleId != null) builder.putString("ruleId", ruleId)
        if (prerequisiteKey != null) builder.putString("prerequisiteKey", prerequisiteKey)
        builder.build()
    }
    return ProviderEvaluation(
        value = value,
        variant = flag.variation.ifEmpty { null },
        reason = mapReason(flag.reason),
        metadata = metadata,
    )
}

private fun <T> error(defaultValue: T, code: ErrorCode, message: String): ProviderEvaluation<T> =
    ProviderEvaluation(
        value = defaultValue,
        reason = Reason.ERROR.name,
        errorCode = code,
        errorMessage = message,
    )

/**
 * Translates a Featureflip reason string to an OpenFeature one. Anything absent
 * from the table is UNKNOWN, so a reason added later degrades instead of lying.
 *
 * Reason strings, confirmed against `apps/evaluation-api`:
 * - `FlagEvaluator` emits `rule-match:{id}`, `fallthrough`, `flag-disabled`,
 *   `prerequisite-failed` and `error`. `error` comes from prerequisite depth
 *   overflow, a failed prerequisite evaluation, or a variation missing from the flag.
 * - The client evaluate, identify and SSE paths all forward these through
 *   `EvaluateAllFlagsCommand` and `ClientFlagValueMapper` unchanged.
 * - The SSE delta also sends `FLAG_REMOVED`, which the SDK's merge consumes and
 *   never stores.
 * - `flag-not-found` is never sent. The SDK synthesizes it only for inspector
 *   events. It is handled anyway because it costs nothing.
 */
internal fun mapReason(reason: String): String = when {
    reason.startsWith(RULE_MATCH_PREFIX) -> Reason.TARGETING_MATCH.name
    reason == "fallthrough" -> Reason.DEFAULT.name
    reason == "flag-disabled" -> Reason.DISABLED.name
    reason == "prerequisite-failed" -> PREREQUISITE_FAILED
    else -> Reason.UNKNOWN.name
}

// --- Type guards ---------------------------------------------------------
//
// Flag values arrive as decoded JSON, so a number is whatever shape Jackson chose
// for how it was written. JSON does not distinguish 1 from 1.0, so the numeric
// guards accept whole-number doubles too: a flag's readability must not depend on
// how its value happened to be serialized.

internal fun asBoolean(v: Any?): Boolean? = v as? Boolean

internal fun asString(v: Any?): String? = v as? String

internal fun asInt(v: Any?): Int? = when (v) {
    is Int -> v
    is Long -> if (v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) v.toInt() else null
    is Double, is Float -> {
        val d = (v as Number).toDouble()
        if (d == Math.rint(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
    }
    else -> null
}

/**
 * Like [asInt], plus Kotlin's extra read: any integral number in `Long` range,
 * `BigInteger` included (Jackson decodes a JSON integer past `Long` as one).
 */
internal fun asLong(v: Any?): Long? = when (v) {
    is Int -> v.toLong()
    is Long -> v
    is Short -> v.toLong()
    is Byte -> v.toLong()
    is BigInteger -> if (v.bitLength() <= 63) v.toLong() else null
    is Double, is Float -> {
        val d = (v as Number).toDouble()
        if (d == Math.rint(d) && d >= -TWO_POW_63 && d < TWO_POW_63) d.toLong() else null
    }
    else -> null
}

/** Any JSON number, integral or not: a flag holding 1 is a perfectly good double. */
internal fun asDouble(v: Any?): Double? = (v as? Number)?.toDouble()

/**
 * Objects and arrays only. A scalar is deliberately rejected: a caller reaching
 * for an object value wants structure, and [Value] would happily wrap a scalar.
 */
internal fun asStructure(v: Any?): Value? = if (v is Map<*, *> || v is List<*>) toValue(v) else null

/** Converts a decoded JSON value to an OpenFeature [Value]. */
internal fun toValue(v: Any?): Value = when (v) {
    null -> Value.Null
    is Map<*, *> -> Value.Structure(v.entries.associate { it.key.toString() to toValue(it.value) })
    is List<*> -> Value.List(v.map { toValue(it) })
    is Boolean -> Value.Boolean(v)
    is String -> Value.String(v)
    is Int -> Value.Integer(v)
    is Long -> Value.Long(v)
    is BigInteger -> Value.Double(v.toDouble())
    is Number -> Value.Double(v.toDouble())
    // Nothing else can come out of JSON, but silently dropping a shape is worse
    // than stringifying it.
    else -> Value.String(v.toString())
}
