# Changelog

## 0.1.2 — 2026-11-03

### Changed

- Requires the Featureflip Android SDK 3.5.4 or later. Before 3.5.4, a stream connect still being opened when the provider shut down could open its connection anyway and hold it open on the server until it timed out (#4947). A late `connection-ready` from a replaced connection could also leave the SDK pointing `setEvaluationContext` at a closed connection (#4946). 3.5.3 also keeps the on-disk flag cache in step with the flags being served, so a cold start no longer serves older flags (#4945).

## 0.1.1 — 2026-11-03

### Changed

- Requires the Featureflip Android SDK 3.5.1 or later. On 3.5.0, a poll or stream update still in flight for the previous user when a new evaluation context was set could replace the new user's flags with the old user's until the next poll. 3.5.1 drops those responses. (#4937)

## 0.1.0 — 2026-11-03

Initial release. (#4883)

- OpenFeature provider for Android and Kotlin, backed by the Featureflip Android SDK: boolean, string, integer, long, double and object resolution. A flag whose value is not the type you asked for resolves to your default with `TYPE_MISMATCH`, distinct from an evaluation failure. Numbers the SDK decodes past `Long` (a JSON integer above `Long.MAX_VALUE`, or `1e30`) are a `TYPE_MISMATCH` for long reads and still resolve as doubles.
- Reason mapping: `rule-match` is `TARGETING_MATCH`, `fallthrough` is `DEFAULT`, `flag-disabled` is `DISABLED`, and `prerequisite-failed` is surfaced as its own `PREREQUISITE_FAILED` reason rather than mislabelled. `ruleId` and `prerequisiteKey` are attached as flag metadata. There is no `CACHED` reason: values served from the SDK's cache are the normal case for a client-side SDK, not a degraded one.
- Context mapping and re-targeting: the OpenFeature `targetingKey` becomes the Featureflip `user_id`, and an explicit `user_id` or `userId` attribute wins over it. The SDK is client-side, so the per-read context is ignored; the user is set at initialization and when you call `setEvaluationContext`. Context sets are serialised and the newest one always lands, even if its caller was cancelled. An identity that arrives under a different spelling than the one in your config replaces it instead of being sent beside it. `Value.Instant` attributes reach the wire as ISO-8601 strings.
- `PROVIDER_CONFIGURATION_CHANGED` on every flag-configuration change, carrying the affected keys. It is not emitted for the provider's own identify, and a cold start is not a change.
- Tracking through OpenFeature's `track`, with the numeric value omitted when absent rather than sent as zero.
- Construct from a `FeatureflipConfig` (the provider obtains and owns a client handle) or from a `FeatureflipClient` you hold (`shutdown()` closes the handle you gave it, so hand in one of its own).
- Requests made through the provider identify it: `User-Agent: featureflip-android/<version> featureflip-openfeature-android/<version>`. A `wrapperName` you set on your config is kept.
- Requires `io.featureflip:featureflip-android` 3.5.0 or later, the OpenFeature Kotlin SDK 0.9.0 and Java 17.
