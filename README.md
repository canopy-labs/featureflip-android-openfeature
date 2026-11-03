# Featureflip OpenFeature Provider (Android)

[OpenFeature](https://openfeature.dev) provider for [Featureflip](https://featureflip.io), backed by the Featureflip Android SDK and built for the [OpenFeature Kotlin SDK](https://github.com/open-feature/kotlin-sdk).

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.featureflip:featureflip-android-openfeature:0.1.1")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'io.featureflip:featureflip-android-openfeature:0.1.1'
}
```

The Featureflip Android SDK, the OpenFeature Kotlin SDK and `kotlinx-coroutines-core` come along as `api` dependencies.

## Quick Start

Set the provider once per process, not per Activity. An `Application` with a scope that lives as long as the app is the natural place:

```kotlin
import android.app.Application
import dev.featureflip.android.FeatureflipConfig
import dev.featureflip.android.openfeature.FeatureflipProvider
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val provider = FeatureflipProvider(
            FeatureflipConfig(clientKey = "your-client-sdk-key", applicationContext = this),
        )
        appScope.launch {
            OpenFeatureAPI.setProviderAndWait(
                provider,
                initialContext = ImmutableContext("user-123", mapOf("plan" to Value.String("pro"))),
            )
            // The provider is ready here, so this reads the real value.
            val enabled = OpenFeatureAPI.getClient().getBooleanValue("new-checkout", false)
        }
    }
}

// The user signs out, or another one signs in (from any coroutine):
OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("user-456"))
```

An Activity's `lifecycleScope` is cancelled whenever the screen rotates. Set up there and each rotation swaps in a new provider, which fetches every flag again while the old one shuts down.

`applicationContext` lets the SDK save the id it generates for an anonymous user. Leave it out and that id changes on every restart, so an anonymous user can fall into a different rollout bucket each time the app starts.

Use `setProviderAndWait` rather than `setProvider`. The plain form returns before initialization has finished, so a read on the next line can hand back its default. Switching users has the same pair. `setEvaluationContextAndWait` returns once the new user's flags are in. `setEvaluationContext` returns at once, and reads serve the previous user's flags until its fetch completes.

## Client-side key

The Android SDK is a client SDK. Use a client SDK key, not a server key.

Only flags marked `clientSideVisible` are delivered to client SDKs, even when the flag is enabled. A flag that is not visible to clients reads as your default, with `FLAG_NOT_FOUND`. Set `clientSideVisible` when you create a flag you want to read from the app.

## Static context

A client SDK asks the server to evaluate every flag for one user and caches the answers. That is OpenFeature's static paradigm, so the Kotlin SDK's reads take no context of their own. The user is set when the provider initializes and each time you call `setEvaluationContext`.

OpenFeature's `targetingKey` becomes Featureflip's user id, which is what percentage rollouts bucket on and what `user_id` targeting rules read. An explicit `user_id` or `userId` attribute takes precedence over `targetingKey`, and between the two spellings `user_id` wins. If your `FeatureflipConfig.context` carries an identity under one spelling and the OpenFeature context under the other, the OpenFeature one replaces it; the two are never sent side by side. Every other attribute is passed through for targeting rules and segments. `Value.Instant` attributes are sent as ISO-8601 strings.

Context sets are serialised: a set runs its identify to completion before the next begins, and the newest requested context always lands, even if the caller that asked for it was cancelled. A failed identify leaves the previous user's flags serving.

## Two ways to construct it

### From a config

```kotlin
val provider = FeatureflipProvider(
    FeatureflipConfig(clientKey = "your-client-sdk-key", streaming = true, initTimeoutMs = 5_000),
)
```

The provider obtains a client handle itself and owns it. The first `initialize` builds the SDK with the OpenFeature user merged into your config context, so the first request is already for the right user.

All handles for a client key share one underlying client, and the SDK ignores the config when one already exists. The provider detects that case (the client is already initialized, or another handle on it is live) and applies the OpenFeature user with `identify` instead. Two races remain that it cannot see: a concurrent first `get` for a brand-new key, and another handle that built the client without initializing it and closed before the provider looked. Either can leave the client on the other caller's context.

### From a client you hold

```kotlin
val provider = FeatureflipProvider(FeatureflipClient.get(config))
```

`shutdown()` closes the handle you give it. Hand in a handle of the provider's own (`FeatureflipClient.get(config)` returns a further handle on the same underlying client), not the one the rest of your app is using. A shut-down provider on this path will not initialize again.

On this path the SDK's own identity is left alone, and an OpenFeature context is applied with `identify` only when it is non-empty at initialization. A later `setEvaluationContext` with an explicitly empty context identifies the anonymous user, because a set is a request to change the user.

## Identity

Requests made through a provider built from a config carry `User-Agent: featureflip-android/<version> featureflip-openfeature-android/<version>`, so Featureflip can count OpenFeature usage apart from direct SDK use. The SDK carries a single wrapper, so a `wrapperName` you set on your own config is kept and the provider adds none. A client you pass in was built before the provider saw it, so its requests are unchanged.

## Evaluation reasons

| Featureflip | OpenFeature |
|---|---|
| `rule-match` | `TARGETING_MATCH` |
| `fallthrough` | `DEFAULT` |
| `flag-disabled` | `DISABLED` |
| `prerequisite-failed` | `PREREQUISITE_FAILED` |
| flag absent | `ERROR` + `FLAG_NOT_FOUND` |
| `error` | `ERROR` + `GENERAL` |
| anything else | `UNKNOWN` |

`PREREQUISITE_FAILED` is not a standard OpenFeature reason. Reasons are open strings, so it is surfaced verbatim rather than mislabelled as `DEFAULT` or `DISABLED`, the same choice the other Featureflip providers make. It is not an error: an unmet prerequisite serves the flag's off variation, which is a real value your code should receive.

There is no `CACHED` reason. A client-side SDK serves every read from its cache, so the SDK keeps no marker for where a value came from, and the normal case would be mislabelled as a degraded one. Reasons describe how the server evaluated the flag.

Where a flag's evaluation carries them, `ruleId` and `prerequisiteKey` are attached as flag metadata.

## Types

A flag whose value is not of the type you asked for resolves to your default with `TYPE_MISMATCH`, distinctly from a genuine evaluation failure.

* **Integer** reads accept a whole number within `Int` range, however its value happened to be serialized: JSON does not distinguish `1` from `1.0`.
* **Long** reads accept a whole number within `Long` range. A JSON integer above `Long.MAX_VALUE`, or a value like `1e30`, is a `TYPE_MISMATCH`.
* **Double** reads accept any number.
* **Object** reads accept objects and arrays only. A string, number or boolean resolves to your default with `TYPE_MISMATCH`, since reaching for an object value means you want structure.

## Reacting to flag changes

The provider emits `ProviderConfigurationChanged` when the SDK reports that flags changed, carrying the affected flag keys:

```kotlin
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents

OpenFeatureAPI.observe<OpenFeatureProviderEvents.ProviderConfigurationChanged>()
    .collect { event -> println("flags changed: ${event.eventDetails?.flagsChanged}") }
```

The provider's own identify is not reported: switching users changes every flag, and OpenFeature signals it through the status instead. The Kotlin SDK has no context-changed event. After `setEvaluationContext` the status goes `Reconciling` and then `Ready`; observe the status flow if you need to know when the new user's flags are in.

## Tracking

```kotlin
import dev.openfeature.kotlin.sdk.ImmutableStructure
import dev.openfeature.kotlin.sdk.TrackingEventDetails
import dev.openfeature.kotlin.sdk.Value

client.track(
    "checkout-completed",
    TrackingEventDetails(99.99, ImmutableStructure("currency" to Value.String("USD"))),
)
```

Featureflip counts each event name, per environment. The tracking event details are transmitted with the event but are not stored, and event counts are not surfaced in the app or API. The numeric value is optional and is omitted when absent rather than recorded as zero. `Value.Instant` details are sent as ISO-8601 strings.

## Threading

The SDK's identify and initialize calls block, so the provider runs them on `Dispatchers.IO`. Flag reads are cache lookups and run on the caller. The change callback never blocks the SDK's poller or stream: events go through a buffer of 64. With no collector they are discarded, and a collector that falls more than 64 events behind loses the oldest.

## Behaviour on startup

A failed initial flag load does not fail initialization. The SDK keeps retrying in the background, and reads serve any flags cached from a previous session, or your defaults, until flags arrive. When they do, that is reported as a configuration change.

## Requirements

Java 17 or higher, the Featureflip Android SDK 3.5.1 or later, and the OpenFeature Kotlin SDK 0.9.0.

## License

Apache-2.0
