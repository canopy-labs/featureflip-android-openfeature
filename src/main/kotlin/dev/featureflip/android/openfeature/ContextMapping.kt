package dev.featureflip.android.openfeature

import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.TrackingEventDetails
import dev.openfeature.kotlin.sdk.Value
import kotlin.time.ExperimentalTime

// Context and tracking mapping. This is a copy of the logic in the Java and web
// providers, not an import of it: neither package belongs on Android (the Java
// provider targets the Java SDK's EvaluationContext, the web one needs a DOM-era
// runtime), and the Android SDK takes a plain attribute map.

private const val USER_ID_SNAKE = "user_id"
private const val USER_ID_CAMEL = "userId"

/**
 * Maps an OpenFeature context onto the plain attribute map the Android SDK takes.
 *
 * The targeting key is a separate field in the Kotlin SDK, not an attribute. It
 * becomes `user_id` only when it is non-empty and the caller has not set `user_id`
 * or `userId` explicitly, since an explicit identity beats the targeting key.
 */
internal fun toFeatureflipContext(context: EvaluationContext?): Map<String, Any?> {
    if (context == null) return emptyMap()
    val out = LinkedHashMap<String, Any?>()
    for ((key, value) in context.asMap()) {
        out[key] = toPlain(value)
    }
    val targetingKey = context.getTargetingKey()
    if (targetingKey.isNotEmpty() && USER_ID_SNAKE !in out && USER_ID_CAMEL !in out) {
        out[USER_ID_SNAKE] = targetingKey
    }
    return out
}

/**
 * Layers the OpenFeature attributes over the config context, key by key.
 *
 * An OpenFeature identity replaces BOTH config spellings: the SDK and server read
 * `user_id` first, so a config `user_id` left beside an OpenFeature `userId` would
 * silently win over the identity the caller just set.
 */
internal fun mergeContext(base: Map<String, Any?>, mapped: Map<String, Any?>): Map<String, Any?> {
    if (USER_ID_SNAKE in mapped || USER_ID_CAMEL in mapped) {
        return (base - USER_ID_SNAKE - USER_ID_CAMEL) + mapped
    }
    return base + mapped
}

/**
 * Converts an OpenFeature [Value] to the plain object the SDK's Jackson encodes.
 * An instant becomes its ISO-8601 string: Android's Jackson has no time module
 * (the Java provider passes the `Instant` through; that is a deliberate departure).
 */
@OptIn(ExperimentalTime::class)
internal fun toPlain(value: Value): Any? = when (value) {
    is Value.String -> value.string
    is Value.Boolean -> value.boolean
    is Value.Integer -> value.integer
    is Value.Long -> value.long
    is Value.Double -> value.double
    is Value.Instant -> value.instant.toString()
    is Value.Structure -> value.structure.mapValues { toPlain(it.value) }
    is Value.List -> value.list.map { toPlain(it) }
    Value.Null -> null
}

/**
 * Flattens tracking details into event metadata: the structure's members, plus
 * `value` when present. Null details send no metadata. Ported from the Java provider.
 */
internal fun trackingMetadata(details: TrackingEventDetails?): Map<String, Any?>? {
    if (details == null) return null
    val out = LinkedHashMap<String, Any?>()
    for ((key, value) in details.asMap()) {
        out[key] = toPlain(value)
    }
    if (details.value != null) out["value"] = details.value
    return out
}
