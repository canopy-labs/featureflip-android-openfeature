package dev.featureflip.android.openfeature

import dev.featureflip.android.FeatureflipClient
import dev.featureflip.android.FeatureflipConfig
import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.FeatureProvider
import dev.openfeature.kotlin.sdk.Hook
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.ProviderMetadata
import dev.openfeature.kotlin.sdk.TrackingEventDetails
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * An OpenFeature provider over the Featureflip Android SDK.
 *
 * Construct it from a [FeatureflipConfig] (the provider obtains and owns a client
 * handle) or from a [FeatureflipClient] you already hold.
 *
 * The SDK is client-side: the server evaluates every flag for one user and the
 * SDK caches the answers. That is OpenFeature's static paradigm, so the `context`
 * argument of each read is ignored; the user is set through `initialize` and
 * `onContextSet`, never per evaluation.
 *
 * **One user per core.** All handles for a client key share one core, so an
 * identity applied through one is seen by every holder.
 *
 * **Pre-existing core rule (config path).** The first `initialize` builds the core
 * with the OpenFeature user already merged into the config context, so the first
 * request is for the right user. When the core already existed and was
 * initialized (`isInitialized` read true before `initialize()`), the config is
 * ignored by the SDK, so the OpenFeature user is applied with `identify` instead.
 * Any other live handle on the core (`FeatureflipClient.debugRefCount(clientKey) > 1`)
 * also counts as pre-existing, since the core may be initializing for someone
 * else's context; a repeated identify for an unchanged context is skipped. Rare
 * edge that remains: a concurrent first `get` for a brand-new key, or another
 * handle that built the core without initializing it and closed before our
 * refcount read, can still leave the core on the other caller's context. Treat
 * the OpenFeature user as set by `initialize` unless one of those races applies.
 * `debugRefCount` is a diagnostic of our own SDK whose KDoc pins it for this
 * provider. The SDK dependency is a minimum version, so an app could still
 * resolve a newer SDK without it; that is a `LinkageError` at runtime, not a
 * compile failure, and the provider then treats the core as pre-existing.
 *
 * **Client path.** The SDK's own identity (User-Agent) is left alone, and the
 * OpenFeature user is applied with `identify` when it is non-empty. [shutdown]
 * closes the handle it was given; hand in a handle of its own
 * (`FeatureflipClient.get(config)` returns a further handle on the same core).
 * A closed client-path provider refuses to initialize again. A shutdown that lands
 * while the SDK's own `initialize()` is still running does not close the handle
 * under it: the SDK would go on to start its data source on a released core. The
 * initialize closes it instead, once the SDK call has returned, and throws.
 *
 * **Threading.** The SDK's calls block, so they run on `Dispatchers.IO`. Reads are
 * cache lookups and run on the caller.
 *
 * **No `CACHED` reason.** Values served from the SDK's cache are the normal case
 * for a client-side SDK, not a degraded one, so reasons describe how the server
 * evaluated the flag.
 */
class FeatureflipProvider private constructor(
    private val source: Source,
) : FeatureProvider {

    private sealed interface Source {
        data class Config(val config: FeatureflipConfig) : Source
        data class Client(val client: FeatureflipClient) : Source
    }

    constructor(config: FeatureflipConfig) : this(Source.Config(config))

    constructor(client: FeatureflipClient) : this(Source.Client(client))

    override val hooks: List<Hook<*>> = emptyList()

    override val metadata: ProviderMetadata = object : ProviderMetadata {
        override val name: String = NAME
    }

    @Volatile
    private var client: FeatureflipClient? = null

    /** Client path only: set by [shutdown], after which [initialize] refuses. */
    @Volatile
    private var closed: Boolean = false

    private val lock = Mutex()
    private val generation = AtomicLong()

    /**
     * The newest context set requested so far: its generation and its mapped
     * context. Written by [onContextSet] before it waits on [lock], keeping the
     * higher generation when two calls race, so a caller that is cancelled while
     * queued has still left its request where the lock holder will find it.
     */
    private val latestRequested = AtomicReference<Pair<Long, Map<String, Any?>>?>(null)

    /**
     * Guarded by [lock]. The generation of the newest request known to be on the
     * core: landed by an identify, or skipped because it equalled `lastApplied`.
     * Advanced only after success, so a failed identify is retried by the next holder.
     */
    private var handledGeneration = 0L

    /**
     * The context last applied to the core, as sent. Read by context-set handling.
     * Written only under [stateLock] with the shutdown counter unmoved, and cleared
     * by [shutdown], so it never outlives the handle it describes.
     */
    @Volatile
    private var lastApplied: Map<String, Any?>? = null

    /** Bumped by [shutdown], so an initialize that raced it can tell and clean up. */
    private val shutdowns = AtomicLong()

    private val identifying = AtomicInteger()

    @Volatile
    private var unsubscribe: (() -> Unit)? = null

    /** Guarded by [stateLock]: the handle [unsubscribe] belongs to, so [subscribe] is idempotent. */
    private var subscribed: FeatureflipClient? = null

    private val events = MutableSharedFlow<OpenFeatureProviderEvents>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Guarded by [stateLock]. True from an initialize's entry read to its publish
     * step (or its failure exit). A client-path [shutdown] that sees it set leaves
     * closing `source.client` to that initialize.
     */
    private var initInFlight: Boolean = false

    /**
     * The SDK carries one wrapper. A caller's own is the outermost and more
     * specific identity, so it is kept; ours is added only when none is set.
     */
    private fun identified(config: FeatureflipConfig): FeatureflipConfig =
        if (config.wrapperName.isNullOrEmpty()) {
            config.copy(wrapperName = WRAPPER_NAME, wrapperVersion = PROVIDER_VERSION)
        } else {
            config
        }

    /**
     * True when another live handle shares the core for [clientKey]. The SDK
     * dependency is a minimum version, so an app may resolve a newer SDK in which
     * `debugRefCount` is gone; that is a `LinkageError`, which the `Exception`
     * catches in [initialize] would miss, leaving the provider in Error for good.
     * Answering "shared" instead is fail-safe: the cost is one extra identify.
     */
    private fun sharesCore(clientKey: String): Boolean = try {
        FeatureflipClient.debugRefCount(clientKey) > 1
    } catch (_: LinkageError) {
        true
    }

    /**
     * Guards the small state transitions shared by [initialize], [identifyCounted]
     * and [shutdown]: the shutdown counter, `closed`, `client`, `unsubscribe`,
     * `lastApplied` and `initInFlight`.
     * It is only ever held for a few field reads and writes, never across a
     * suspension point or a network call, so a blocked SDK call can never stall
     * [shutdown] and a suspended coroutine can never hold it. Every race with
     * shutdown is decided here: a transition that sees the counter move after its
     * snapshot is abandoned, and shutdown (which took `client` under the same lock)
     * owns whatever was published before it.
     */
    private val stateLock = Any()

    private fun shutDownDuring(op: String) =
        OpenFeatureError.GeneralError("FeatureflipProvider was shut down during $op")

    private fun shutdownSince(snapshot: Long): Boolean = synchronized(stateLock) { shutdowns.get() != snapshot }

    /**
     * The shutdown counter and the live client, read together. A caller that later
     * calls [identifyCounted] passes both from one snapshot, so the client is the
     * one that was live at that counter value.
     */
    private fun snapshot(): Pair<Long, FeatureflipClient?> = synchronized(stateLock) { shutdowns.get() to client }

    /** Ends an [op] that lost a race with cancellation or [shutdown]. */
    private suspend fun abandoned(op: String): Nothing {
        currentCoroutineContext().ensureActive()
        throw shutDownDuring(op)
    }

    /**
     * Ends an initialize's in-flight window under [stateLock]: clears `initInFlight`
     * and, if no shutdown has landed since [startShutdowns], runs [publish] in the
     * same critical section. Returns true when a shutdown landed, in which case a
     * client-path caller owns closing `source.client`. [publish] must not block.
     */
    private inline fun endInit(startShutdowns: Long, publish: () -> Unit): Boolean = synchronized(stateLock) {
        initInFlight = false
        val moved = shutdowns.get() != startShutdowns
        if (!moved) publish()
        moved
    }

    /** Test seam: runs just before initialize publishes its handle. */
    internal var beforeAssign: (() -> Unit)? = null

    override suspend fun initialize(initialContext: EvaluationContext?) = lock.withLock {
        // One atomic read of everything that decides this call: a shutdown lands
        // wholly before it (visible here) or wholly after (caught at publish).
        val startShutdowns: Long
        val existing: FeatureflipClient?
        synchronized(stateLock) {
            if (closed) {
                throw OpenFeatureError.GeneralError(
                    "FeatureflipProvider is closed: the client it was given has been closed. Construct a new provider.",
                )
            }
            startShutdowns = shutdowns.get()
            existing = client
            initInFlight = true
        }
        // Every exit ends the in-flight window exactly once: the publish step on
        // success, the finally below on any throw before it.
        var ended = false
        try {
            val startGeneration = generation.get()
            val mapped = toFeatureflipContext(initialContext)
            when (source) {
                is Source.Config -> {
                    val merged = mergeContext(source.config.context, mapped)
                    val key = source.config.clientKey
                    // A caller cancelled before the blocking init skips it entirely.
                    currentCoroutineContext().ensureActive()
                    // NonCancellable: the blocking calls cannot be interrupted. Even so,
                    // resuming a cancelled caller on its dispatcher throws and drops the
                    // block's result, so the handle is parked in locals the catch can see.
                    var handle: FeatureflipClient? = null
                    var obtained = false
                    var preExisting = false
                    try {
                        withContext(NonCancellable + Dispatchers.IO) {
                            val c = existing ?: FeatureflipClient.get(identified(source.config).copy(context = merged))
                            handle = c
                            obtained = existing == null
                            try {
                                // Checked BEFORE initialize(). Another live handle also counts:
                                // the core may be initializing for someone else's context.
                                preExisting = c.isInitialized ||
                                    (existing == null && sharesCore(key))
                                c.initialize()
                            } catch (e: Throwable) {
                                if (obtained) c.close()
                                handle = null
                                throw e
                            }
                        }
                    } catch (e: CancellationException) {
                        if (obtained) handle?.close()
                        throw e
                    }
                    val ready = handle!!
                    beforeAssign?.invoke()
                    val active = currentCoroutineContext().isActive
                    ended = true
                    // A moved counter means shutdown already took `client`: leave it alone.
                    val shutDown = endInit(startShutdowns) {
                        if (active) {
                            client = ready
                            subscribe(ready)
                            if (!preExisting) lastApplied = merged
                        }
                    }
                    if (shutDown || !active) {
                        // Only a handle this call obtained is ours to close. A repeat
                        // initialize's `existing` stays published (cancelled) or belongs
                        // to shutdown (already taken).
                        if (obtained) ready.close()
                        abandoned("initialize")
                    }
                    if (!requestedSince(startGeneration) && preExisting && mapped.isNotEmpty() && merged != lastApplied) {
                        identifyCounted(ready, merged, startShutdowns, "initialize")
                    }
                    // A context set arrived while this initialize held the lock, possibly
                    // during the identify above. Its caller may have been cancelled in the
                    // queue, so land the newest request here.
                    if (requestedSince(startGeneration)) landLatestUncancellably(ready, startShutdowns)
                }
                is Source.Client -> {
                    val c = source.client
                    withContext(NonCancellable + Dispatchers.IO) { c.initialize() }
                    beforeAssign?.invoke()
                    val active = currentCoroutineContext().isActive
                    ended = true
                    val shutDown = endInit(startShutdowns) {
                        if (active) {
                            client = c
                            subscribe(c)
                        }
                    }
                    // A shutdown inside the window left the close to us; c.initialize()
                    // has returned, so closing now also stops the data source it started.
                    if (shutDown) c.close()
                    if (shutDown || !active) abandoned("initialize")
                    // An unchanged context is skipped, as on the config path.
                    if (!requestedSince(startGeneration) && mapped.isNotEmpty() && mapped != lastApplied) {
                        identifyCounted(c, mapped, startShutdowns, "initialize")
                    }
                    if (requestedSince(startGeneration)) landLatestUncancellably(c, startShutdowns)
                }
            }
        } catch (e: OpenFeatureError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OpenFeatureError.GeneralError("Featureflip initialize failed: ${e.message ?: e}")
        } finally {
            // Cancelled, or the SDK threw, before the publish step. Nothing was
            // published; a client-path shutdown that landed meanwhile left the close
            // to us, and every blocking SDK call of this initialize is over by now.
            if (!ended && endInit(startShutdowns) {} && source is Source.Client) source.client.close()
        }
    }

    /**
     * Turns the SDK's flag updates into `ProviderConfigurationChanged`. Called with
     * [stateLock] held, so it only registers a listener (a plain list add that
     * invokes nothing) and stores the unsubscribe; it never suspends or blocks.
     *
     * Idempotent per handle: a repeat initialize republishes the same handle and
     * lands here again, and a second listener would double every event.
     *
     * The listener runs on the SDK's delivering thread (stream, poller, or the
     * identify caller), so it only `tryEmit`s and takes no lock. `DROP_OLDEST`
     * makes `tryEmit` always succeed, so that thread never waits on a collector.
     *
     * The counter, not a flag, mirrors web: overlapping identifies resume
     * reporting only after the last one. A poll that lands during an identify is
     * suppressed too; accepted, as on web.
     *
     * The Kotlin SDK has no `PROVIDER_CONTEXT_CHANGED` event. A context change
     * surfaces through `statusFlow` (`Reconciling` then `Ready`).
     */
    private fun subscribe(client: FeatureflipClient) {
        if (subscribed === client && unsubscribe != null) return
        unsubscribe?.invoke()
        subscribed = client
        unsubscribe = client.onUpdate { keys ->
            if (identifying.get() == 0) {
                events.tryEmit(OpenFeatureProviderEvents.ProviderConfigurationChanged(OpenFeatureProviderEvents.EventDetails(flagsChanged = keys.toSet())))
            }
        }
    }

    /**
     * Identifies [context] on [client] on `Dispatchers.IO`, counted in `identifying`.
     *
     * Contract: [client] and [shutdownsAtStart] come from one read under
     * [stateLock], via [snapshot] (initialize uses its own entry read, confirmed
     * by its publish step). The counter is checked before the call and again
     * after; a moved counter throws `GeneralError("... shut down during [op]")`,
     * so a shutdown is reported rather than swallowed. `lastApplied` is written in
     * the same critical section as the second check, so it never lands after a
     * shutdown cleared it. The blocking call runs to completion even if the caller
     * is cancelled, so that write happens inside the IO block and records what
     * actually reached the core. Residual window: the one identify already in
     * flight when shutdown lands still reaches the server, but is reported as
     * abandoned and never recorded.
     */
    private suspend fun identifyCounted(
        client: FeatureflipClient,
        context: Map<String, Any?>,
        shutdownsAtStart: Long,
        op: String,
    ) {
        identifying.incrementAndGet()
        try {
            withContext(Dispatchers.IO) {
                if (shutdownSince(shutdownsAtStart)) throw shutDownDuring(op)
                client.identify(context)
                synchronized(stateLock) {
                    if (shutdowns.get() != shutdownsAtStart) throw shutDownDuring(op)
                    lastApplied = context
                }
            }
        } catch (e: OpenFeatureError) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OpenFeatureError.GeneralError("Featureflip identify failed: ${e.message ?: e}")
        } finally {
            identifying.decrementAndGet()
        }
    }

    override fun shutdown() {
        val taken: FeatureflipClient?
        val unsub: (() -> Unit)?
        val closeSource: Boolean
        synchronized(stateLock) {
            shutdowns.incrementAndGet()
            if (source is Source.Client) closed = true
            taken = client
            client = null
            unsub = unsubscribe
            unsubscribe = null
            subscribed = null
            lastApplied = null
            // Not only `taken`: an initialize racing this call may not have published
            // the handle yet. But while it is inside the SDK's initialize(), closing
            // would release the core before the SDK starts its data source on it,
            // which then runs forever on an orphan (#4901). That initialize sees the
            // counter moved when its window ends and closes source.client itself.
            closeSource = source is Source.Client && !initInFlight
        }
        unsub?.invoke()
        taken?.close()
        if (closeSource) (source as Source.Client).client.close()
    }

    /**
     * Re-targets the core at [newContext]: the config context with the OpenFeature
     * attributes layered over it (config path), or the mapped context alone (client
     * path). Throws `GeneralError` when the provider is uninitialized or shut down, or
     * when the identify fails; the Kotlin SDK reports that as `OpenFeatureStatus.Error`.
     *
     * The SDK commits its context only after a successful identify response, so a
     * failure leaves the previous user's flags serving.
     *
     * **Empty context, client path.** An explicitly empty OpenFeature context
     * identifies `{}`, the anonymous user, whereas [initialize] with an empty or null
     * context leaves the SDK's own identity alone. A set is a request to change the
     * user; an absent initial context requests nothing, and the handle handed in may
     * already carry the app's user.
     *
     * **The newest request lands.** The Kotlin SDK commits its own context BEFORE it
     * calls this, and `setEvaluationContextAndWait` can be cancelled with no newer
     * call to follow (a lifecycle scope torn down). So each call publishes its
     * request to [latestRequested] before it waits on [lock], and whoever holds the
     * lock lands the newest request, not its own ([landLatest]). A caller cancelled
     * in the queue still has its context applied by the holder ahead of it, or by an
     * [initialize] it queued behind. A queued call whose request was already landed
     * finds nothing newer and returns without fetching.
     *
     * Once its request is published, a call waits for the lock and lands under
     * `NonCancellable`: if every caller in a batch is cancelled, the Kotlin SDK
     * restores the status it saved (normally `Ready`), so the newest request must
     * land regardless. A cancelled caller can therefore wait for every identify
     * queued on [lock] ahead of it, plus its own, each bounded by the SDK's HTTP
     * timeouts. When the landing succeeds it then returns normally even though its
     * job is cancelled; the cancellation surfaces at the caller's next suspension
     * point. The remaining gap is on the OpenFeature side: a cancellation after
     * the SDK commits its context but before it calls this method publishes nothing.
     *
     * **Recording.** [identifyCounted] writes `lastApplied` inside its IO block, after
     * `identify` returns and before the block completes, with no suspension point in
     * between, so what landed is recorded even when [initialize]'s own identify is
     * cancelled.
     */
    override suspend fun onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) {
        // Mapped before publishing, so a mapping failure never leaves a request behind.
        val mapped = toFeatureflipContext(newContext)
        val myGeneration = generation.incrementAndGet()
        val mine = myGeneration to mapped
        latestRequested.accumulateAndGet(mine) { current, _ ->
            if (current == null || current.first < mine.first) mine else current
        }
        // Published: from here the request must land even if this caller is cancelled,
        // since a cancelled caller's context may be the newest and nobody else may hold
        // the lock after us. The wait covers every identify queued ahead of us plus our
        // own, each bounded by the SDK's HTTP timeouts.
        // shutdown() never takes `lock`, so it is never blocked by this.
        val caller = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            lock.withLock {
                // Client and counter from one read, so an identify below is aborted if a
                // shutdown lands at any point after it.
                val (shutdownsAtStart, c) = snapshot()
                // Belt and braces: shutdown sets `closed` and nulls `client` in one locked
                // section, so a non-null snapshot client already means not closed then.
                if (c == null || (source is Source.Client && closed)) {
                    // A cancelled caller ends cancelled, not in Error.
                    caller?.ensureActive()
                    throw OpenFeatureError.GeneralError("FeatureflipProvider is closed or was never initialized")
                }
                landLatest(c, shutdownsAtStart, "a context change")
            }
        }
    }

    /** The context a mapped OpenFeature request is applied as. */
    private fun targetFor(mapped: Map<String, Any?>): Map<String, Any?> = when (source) {
        is Source.Config -> mergeContext(source.config.context, mapped)
        is Source.Client -> mapped
    }

    /**
     * Initialize's landing of a context set that arrived while it held the lock.
     * Non-cancellable like [onContextSet]'s, so a cancelled initialize still applies
     * the newest request before giving up the lock; it then ends cancelled.
     */
    private suspend fun landLatestUncancellably(c: FeatureflipClient, shutdownsAtStart: Long) {
        withContext(NonCancellable) { landLatest(c, shutdownsAtStart, "initialize") }
        currentCoroutineContext().ensureActive()
    }

    /** True when a context set has published a request since [generation] read [since]. */
    private fun requestedSince(since: Long): Boolean = (latestRequested.get()?.first ?: 0L) > since

    /**
     * Caller holds [lock]. Lands the newest requested context, and again if a newer
     * one arrives meanwhile, until nothing newer than [handledGeneration] is left.
     * The holder thereby also lands a request whose caller was cancelled in the queue.
     *
     * The skip reads `lastApplied` plainly, like [initialize]: under [lock] the only
     * writer that can race it is [shutdown], which only clears it, and every non-null
     * write happens under [lock] and records a context the core really applied, under
     * an unmoved shutdown counter. So the value seen is accurate or null (behind): a
     * stale read can cause an extra identify, never a wrong skip. A skip that races a
     * shutdown is a set ordered just before it.
     */
    private suspend fun landLatest(c: FeatureflipClient, shutdownsAtStart: Long, op: String) {
        while (true) {
            val (gen, mapped) = latestRequested.get() ?: return
            if (gen <= handledGeneration) return
            val target = targetFor(mapped)
            if (target != lastApplied) identifyCounted(c, target, shutdownsAtStart, op)
            handledGeneration = gen
        }
    }

    override fun getBooleanEvaluation(key: String, defaultValue: Boolean, context: EvaluationContext?): ProviderEvaluation<Boolean> =
        resolve(key, defaultValue, { client?.flagDetail(key) }, ::asBoolean, "boolean")

    override fun getStringEvaluation(key: String, defaultValue: String, context: EvaluationContext?): ProviderEvaluation<String> =
        resolve(key, defaultValue, { client?.flagDetail(key) }, ::asString, "string")

    override fun getIntegerEvaluation(key: String, defaultValue: Int, context: EvaluationContext?): ProviderEvaluation<Int> =
        resolve(key, defaultValue, { client?.flagDetail(key) }, ::asInt, "integer")

    override fun getLongEvaluation(key: String, defaultValue: Long, context: EvaluationContext?): ProviderEvaluation<Long> =
        resolve(key, defaultValue, { client?.flagDetail(key) }, ::asLong, "long")

    override fun getDoubleEvaluation(key: String, defaultValue: Double, context: EvaluationContext?): ProviderEvaluation<Double> =
        resolve(key, defaultValue, { client?.flagDetail(key) }, ::asDouble, "double")

    override fun getObjectEvaluation(key: String, defaultValue: Value, context: EvaluationContext?): ProviderEvaluation<Value> =
        resolve(key, defaultValue, { client?.flagDetail(key) }, ::asStructure, "object")

    override fun track(trackingEventName: String, context: EvaluationContext?, details: TrackingEventDetails?) {
        if (closed) return
        client?.track(trackingEventName, trackingMetadata(details))
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = events.asSharedFlow()

    companion object {
        const val NAME = "featureflip-android"
        const val WRAPPER_NAME = "featureflip-openfeature-android"
    }
}
