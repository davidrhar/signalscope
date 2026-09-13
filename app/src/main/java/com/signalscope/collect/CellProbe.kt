package com.signalscope.collect

import android.content.Context
import android.net.*
import android.os.SystemClock
import com.signalscope.store.Db
import com.signalscope.store.ProbeResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Probes bound to the CELLULAR network specifically, even while Wi-Fi is the default route.
 *
 * Why this exists: Phase C tried to attribute outcomes to cells and found `validated` was 100 %
 * for every cell on every band — because the default route was Wi-Fi throughout. Nothing was
 * riding on the cellular bearer, so there was no cellular outcome to attribute. Passive radio
 * measurement tells you what the modem *hears*; it cannot tell you whether data *works*, and on a
 * Wi-Fi-connected phone the difference is total.
 *
 * `requestNetwork` + `bindSocket` puts our own traffic on cellular without disturbing anything
 * else on the device: no other app moves, the default route is untouched, and the user's Wi-Fi
 * session is unaffected.
 *
 * Cost is the reason this is rate-limited rather than continuous. A probe on an idle radio forces
 * an RRC promotion, which `device-findings.md` measured as more expensive than the whole rest of
 * the collector — so probing is deliberately infrequent, and skipped entirely when the radio is
 * already dormant and nothing needs measuring.
 */
object CellProbe {

    private const val TCP_HOST = "one.one.one.one"
    private const val TCP_PORT = 443
    private const val TIMEOUT_MS = 6_000

    @Volatile private var cellular: Network? = null

    /**
     * Consecutive failures to bind a socket to the cellular network, and the app context needed
     * to rebuild the reservation when they pile up.
     *
     * Binding to a non-default network is only permitted while the app actually holds a request
     * for it. That reservation can go away underneath us -- observed on 2026-09-13, when every
     * bind from 07:00 onward failed with `EPERM (Operation not permitted)` against two different
     * net ids and kept failing for over three hours, recovering only when the process was
     * restarted and `requestNetwork` ran again.
     *
     * The damage was not the outage, it was the reporting. Every one of those binds was written
     * to `probe_result` as a FAILED PROBE, so the night's data says the cellular bearer failed
     * 100 % of the time in four journeys and three excursions. It did not; our own handle was
     * dead. A broken instrument reporting as a broken network is the failure mode this project
     * exists to eliminate, so it is fixed in two directions at once: the reservation now repairs
     * itself, and a bind failure is no longer recordable as a network outcome.
     */
    @Volatile private var bindFailStreak = 0
    @Volatile private var appCtx: Context? = null
    @Volatile private var lastRebuildElapsed = 0L

    /** Two in a row is already abnormal; the observed fault produced hundreds. */
    private const val BIND_FAIL_REBUILD = 2

    /** Don't thrash the platform if rebuilding does not help. */
    private const val REBUILD_MIN_GAP_MS = 60_000L

    /** True when the last attempts failed to bind, i.e. the instrument is broken, not the network. */
    val instrumentBroken: Boolean get() = bindFailStreak >= BIND_FAIL_REBUILD

    private fun isBindDenied(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            val m = e.message ?: ""
            if (m.contains("Binding socket to network") || m.contains("EPERM")) return true
            e = e.cause
        }
        return false
    }

    /**
     * Tear the request down and ask for it again. `unregisterNetworkCallback` then
     * `requestNetwork` is the only way back: the Network handle we hold is valid-looking but
     * unusable, so nothing short of a new reservation restores it.
     */
    private fun rebuildReservation() {
        val ctx = appCtx ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRebuildElapsed < REBUILD_MIN_GAP_MS) return
        lastRebuildElapsed = now
        runCatching { stop(ctx) }
        runCatching { start(ctx) }
    }

    private fun noteBindFailure() {
        bindFailStreak++
        if (bindFailStreak >= BIND_FAIL_REBUILD) rebuildReservation()
    }

    private fun noteBindSuccess() { bindFailStreak = 0 }

    private var cb: ConnectivityManager.NetworkCallback? = null

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        if (cb != null) return
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { cellular = n }
            override fun onLost(n: Network) { if (cellular == n) cellular = null }
        }
        cb = c
        runCatching {
            cm.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                c
            )
        }
    }

    fun stop(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cb?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        cb = null; cellular = null
    }

    /** Runs [body] with the cellular Network if one is available; no-op otherwise. */
    suspend fun <T> withCellular(body: suspend (Network) -> T): T? {
        val n = cellular ?: return null
        return body(n)
    }

    /**
     * Which address family the probe used.
     *
     * Split out because two of the five real cellular failures on 2026-09-12 were IPv6 *connect*
     * timeouts (2606:4700:4700::100 and ::111) while the IPv4 attempts in the same period
     * succeeded in a few hundred milliseconds. If that asymmetry is real rather than coincidence,
     * it is the one repair in this project that is a genuine fix and needs no privilege at all --
     * but a probe that takes whatever DNS hands back first can never establish it.
     */
    enum class Family(val label: String) { ANY("any"), V4("v4"), V6("v6") }

    /**
     * What a `probe_result.probeType` means, in one place.
     *
     * Two different things share that table: reachability probes, which connect and hand-shake
     * and whose latency is the cost of REACHING the network, and transfer probes, whose latency
     * is the cost of MOVING 8 KB. Mixing them corrupts any percentile computed over both -- it
     * already would have moved the excursion's p90 -- so both the map and the diagnostic panels
     * have to separate them, and until now each did it with its own `startsWith` test against a
     * string. A probe kind added later would have been silently absorbed by one filter and
     * silently dropped by the other, with no error anywhere. Classify here instead.
     */
    enum class Kind { REACHABILITY, TRANSFER, INSTRUMENT, RECOVERY, UNKNOWN }

    /**
     * One summary row per stall: how long after a timed-out probe the network was usable again.
     *
     * Exists because every one of the 23 genuine failures in the 2026-09-13 diagnosis landed
     * between 6,010 and 7,003 ms -- and 6 s is [TIMEOUT_MS]. The instrument was cutting stalls off
     * at its own timeout and reporting the cut-off as their length, so it could not distinguish a
     * seven-second pause from a thirty-second outage. That distinction is the difference between an
     * app stalling and an app saying "reconnecting", which is the complaint this project exists
     * for.
     */
    const val KIND_RECOVERY = "STALL"

    /**
     * Rows that must never enter a success or failure rate.
     *
     * Instrument faults describe this app. Recovery summaries describe a stall that is already
     * counted -- as the failed probe that opened it -- and are built from attempts fired BECAUSE
     * something failed, so pooling them would count one outage twice and inflate the failure rate
     * precisely when the network is worst. One predicate, so a consumer cannot exclude one and
     * forget the other.
     */
    fun excludedFromRates(probeType: String, errorCode: String?): Boolean =
        kindOf(probeType, errorCode).let { it == Kind.INSTRUMENT || it == Kind.RECOVERY }

    /**
     * Rows recorded when the app could not bind a socket to the cellular network at all. They
     * describe this app, not the network, and are excluded from every success and failure rate.
     */
    const val KIND_INSTRUMENT = "BINDFAIL"

    /**
     * Classify a stored row, using its error text as well as its type.
     *
     * The 248 rows recorded before [KIND_INSTRUMENT] existed carry an ordinary probe type and an
     * outcome of FAIL, so type alone still reads them as network failures — which is the very
     * thing that made four journeys and three excursions look like a total cellular outage. The
     * error text is the only surviving evidence of what they really were, so it is honoured.
     */
    fun kindOf(probeType: String, errorCode: String?): Kind =
        if (errorCode != null && errorCode.contains("Binding socket")) Kind.INSTRUMENT
        else kindOf(probeType)

    fun isReachability(probeType: String, errorCode: String?) =
        kindOf(probeType, errorCode) == Kind.REACHABILITY

    fun kindOf(probeType: String): Kind = when {
        probeType.startsWith(KIND_INSTRUMENT) -> Kind.INSTRUMENT
        probeType.startsWith(KIND_RECOVERY) -> Kind.RECOVERY
        probeType.startsWith("UP") || probeType.startsWith("DOWN") -> Kind.TRANSFER
        probeType.startsWith("TLS") || probeType.startsWith("TCP") ||
            probeType.startsWith("DNS") -> Kind.REACHABILITY
        else -> Kind.UNKNOWN
    }

    /** Reachability only. This is the set every wake-up statistic in the app is computed over. */
    fun isReachability(probeType: String) = kindOf(probeType) == Kind.REACHABILITY

    data class Result(
        val ok: Boolean, val kind: String, val ms: Long, val detail: String,
        val family: Family = Family.ANY
    )

    /**
     * One TCP+TLS probe over cellular. Deliberately not ICMP: `isReachable` falls back to a TCP
     * connect on Android and misreports, so we do the honest thing explicitly.
     */
    suspend fun probeOnce(ctx: Context, family: Family = Family.ANY): Result =
        withContext(Dispatchers.IO) {
        // requestNetwork is asynchronous: the first probe after service start would otherwise fire
        // before onAvailable and report "no cellular network" when one was moments away.
        var waited = 0
        while (cellular == null && waited < 15_000) { delay(500); waited += 500 }
        val net = cellular ?: return@withContext Result(false, "TCP", 0, "no cellular network", family)
        val t0 = SystemClock.elapsedRealtime()
        runCatching {
            // DNS on the cellular network, not the default one.
            val all = net.getAllByName(TCP_HOST)
            val addr = when (family) {
                Family.V4 -> all.firstOrNull { it is Inet4Address }
                Family.V6 -> all.firstOrNull { it is Inet6Address }
                Family.ANY -> all.firstOrNull()
            } ?: return@withContext Result(
                false, "DNS", SystemClock.elapsedRealtime() - t0, "no ${family.label} address", family)
            val dnsMs = SystemClock.elapsedRealtime() - t0

            val sock = Socket()
            net.bindSocket(sock)
            noteBindSuccess()
            sock.connect(InetSocketAddress(addr, TCP_PORT), TIMEOUT_MS)
            val tcpMs = SystemClock.elapsedRealtime() - t0

            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(sock, TCP_HOST, TCP_PORT, true) as javax.net.ssl.SSLSocket
            tls.soTimeout = TIMEOUT_MS
            tls.startHandshake()
            val total = SystemClock.elapsedRealtime() - t0
            runCatching { tls.close() }
            Result(true, "TLS", total, "dns ${dnsMs}ms tcp ${tcpMs}ms", family)
        }.getOrElse {
            // A refused bind is OUR fault, not the network's, and must never be counted as a
            // failed probe -- doing so is what made 2026-09-13 look like a night of total
            // cellular outage. It gets its own kind so every rate in the app can exclude it, and
            // it triggers a rebuild of the reservation that caused it.
            if (isBindDenied(it)) {
                noteBindFailure()
                return@getOrElse Result(
                    false, KIND_INSTRUMENT, SystemClock.elapsedRealtime() - t0,
                    "cannot bind to the cellular network: " +
                        (it.message?.take(60) ?: it.javaClass.simpleName), family)
            }
            Result(false, "TLS", SystemClock.elapsedRealtime() - t0,
                it.javaClass.simpleName + (it.message?.let { m -> ": ${m.take(60)}" } ?: ""), family)
        }
    }

    /**
     * Records a probe alongside the serving cell at that instant. The cell is not stored on the
     * row — `probe_result` has no cell column and adding one would mean a schema migration that
     * discards collected samples. The analysis joins by `elapsedNanos` against `radio_sample`,
     * which is what the monotonic clock in the data model is for.
     */
    suspend fun probeAndRecord(ctx: Context) {
        // Paired, and back to back rather than on alternate cycles. The thing being controlled for
        // is interference, which varies minute to minute on a shared capacity band -- so arms
        // separated by minutes would let a bad minute masquerade as a bad address family. The
        // order alternates so neither family is systematically second (and so pays any
        // RRC-promotion cost the other one avoided).
        val v6First = System.currentTimeMillis() / 1000 % 2 == 0L
        val order = if (v6First) listOf(Family.V6, Family.V4) else listOf(Family.V4, Family.V6)
        var stallStartElapsed = 0L
        for (fam in order) {
            // Network.getAllByName takes no timeout and will block indefinitely on a non-default
            // network. Without this bound the probe loop stops after its first call, silently,
            // which is exactly how it failed: one row, no error, no further probes.
            val r = withTimeoutOrNull(25_000) { probeOnce(ctx, fam) }
                ?: Result(false, "TCP", 25_000, "probe timed out", fam)
            record(ctx, r)
            // Remember when the FIRST network failure in this cycle began -- its start, not its
            // end, since the stall began when the attempt did.
            if (stallStartElapsed == 0L && isNetworkTimeout(r)) {
                stallStartElapsed = SystemClock.elapsedRealtime() - r.ms
            }
            // No address of this family at all is a property of the bearer, not a failure to
            // compare -- recorded once above, and there is nothing to pair it against.
            if (r.kind == "DNS" && !r.ok) continue
        }
        // After the pair, never inside it: measuring recovery between the two families would
        // change what the second probe of the pair means.
        if (stallStartElapsed != 0L) measureRecovery(ctx, stallStartElapsed)
    }

    /** A timeout on the network, as opposed to an app fault or an absent address family. */
    private fun isNetworkTimeout(r: Result): Boolean =
        !r.ok && r.kind != KIND_INSTRUMENT && r.kind != "DNS" &&
            (r.detail.contains("Timeout") || r.detail.contains("timed out"))

    /** Longest a stall is followed. Beyond this it is recorded as "at least" rather than a length. */
    private const val RECOVERY_MAX_MS = 60_000L

    /** Pause between recovery attempts, on top of the attempt's own duration. */
    private const val RECOVERY_STEP_MS = 2_000L

    @Volatile private var recovering = false

    /**
     * Keep trying until the network answers again, and record how long that took.
     *
     * Re-probes with exactly the recipe [probeOnce] uses, so the recovery is comparable with the
     * failure that opened it. Continuous retrying keeps the radio connected, which means this
     * measures "time until a retrying app gets through" rather than "time until an idle phone
     * would find the network working" -- and the former is the one that matters, because a retrying
     * app is precisely what a reconnecting call or meeting is.
     *
     * Resolution is coarse by construction: a failing attempt takes up to [TIMEOUT_MS] to fail, so
     * recovery is located to within roughly six seconds. That is enough to separate a stall of a
     * few seconds from one of half a minute, which is the question.
     *
     * The attempts are deliberately NOT written as probe rows. They are fired because something
     * failed rather than at a random time, so recording them would inflate the failure rate
     * exactly while the network is at its worst. One summary row is written instead.
     */
    private suspend fun measureRecovery(ctx: Context, startElapsed: Long) {
        if (recovering) return
        recovering = true
        try {
            var attempts = 0
            var recoveredAt = 0L
            var abortReason: String? = null
            while (SystemClock.elapsedRealtime() - startElapsed < RECOVERY_MAX_MS) {
                delay(RECOVERY_STEP_MS)
                attempts++
                val r = withTimeoutOrNull(25_000) { probeOnce(ctx, Family.ANY) } ?: continue
                if (r.kind == KIND_INSTRUMENT) {
                    // We cannot measure the network if we cannot reach it. Stop rather than record a
                    // length that is really the length of our own fault.
                    abortReason = "abandoned: app could not bind to the cellular network"
                    break
                }
                if (r.ok) { recoveredAt = SystemClock.elapsedRealtime(); break }
            }
            val recovered = recoveredAt != 0L
            if (abortReason != null) return
            val lengthMs = if (recovered) recoveredAt - startElapsed else RECOVERY_MAX_MS
            val dao = Db.get(ctx).dao()
            runCatching {
                dao.insertProbe(
                    ProbeResult(
                        elapsedNanos = SystemClock.elapsedRealtimeNanos(),
                        wallMillis = System.currentTimeMillis(),
                        netId = cellular?.toString() ?: "—",
                        probeType = KIND_RECOVERY,
                        target = "$TCP_HOST/recovery attempts=$attempts",
                        // OK = the network came back and latencyMs is the stall's length.
                        // FAIL = it had not come back by the bound, so latencyMs is a FLOOR: the
                        // stall lasted at least this long. Right-censored, never a measured value.
                        outcome = if (recovered) "OK" else "FAIL",
                        latencyMs = lengthMs.toInt(),
                        errorCode = if (recovered) null
                        else "not recovered within ${RECOVERY_MAX_MS / 1000}s — length is a floor"
                    )
                )
            }
        } finally {
            recovering = false
        }
    }

    private suspend fun record(ctx: Context, r: Result) {
        val dao = Db.get(ctx).dao()
        runCatching {
            dao.insertProbe(
                ProbeResult(
                    elapsedNanos = SystemClock.elapsedRealtimeNanos(),
                    wallMillis = System.currentTimeMillis(),
                    netId = cellular?.toString() ?: "—",
                    probeType = r.kind + if (r.family == Family.ANY) "" else r.family.label,
                    target = "$TCP_HOST/${r.family.label}",
                    outcome = if (r.ok) "OK" else "FAIL",
                    latencyMs = r.ms.toInt(),
                    errorCode = if (r.ok) null else r.detail.take(80)
                )
            )
        }
    }

    // ----------------------------------------------------------- uplink vs downlink
    //
    // Everything else this app collects -- RSRP, RSRQ, SINR, CQI, the bars -- measures the
    // DOWNLINK: how well the phone hears the tower. The tower transmits tens of watts from a mast;
    // the phone transmits about 0.2 W from a hand, often with a palm over the antenna. The
    // direction that is plausibly failing is therefore the one we have never instrumented: the
    // tower failing to hear the phone.
    //
    // Two observations pushed this from a hunch to a measurement:
    //
    //  - The serving band on the reference device is TDD (duplexMode=2 for that EARFCN, against
    //    duplexMode=1 for an FDD band). TDD shares one channel by taking turns, and operators
    //    weight those turns toward download, so uplink opportunities are scarcer by configuration
    //    rather than by luck. Nothing here is specific to that device or band -- the asymmetry is
    //    measured, not assumed, and a symmetric FDD carrier should simply come back as no effect.
    //  - Timing advance, the single uplink-derived field the platform exposes, is UNAVAILABLE on
    //    all 7,040 samples collected on this device, both SIMs, every band. The hypothesis cannot
    //    be tested passively at all.
    //
    // So it is tested actively: time a small upload against a small download, back to back.

    enum class Dir(val label: String) { DOWN("DOWN"), UP("UP") }

    /**
     * One direction of one cycle.
     *
     * [totalMs] is the comparable figure and the one recorded. It covers a *fresh* TLS connection
     * plus [bytes] moved in [dir] -- DNS, TCP, handshake, request, payload, response. Both legs are
     * measured to that identical recipe, and both send `Connection: close` so the second leg cannot
     * inherit a warm socket from the first, which would otherwise hand whichever direction ran
     * second a free handshake and look exactly like a direction effect.
     *
     * The payload phase alone is deliberately NOT the reported figure. For an 8 KB download the
     * whole body can arrive in the same burst as the response headers, so a body-only stopwatch
     * reads a handful of milliseconds down against a full round trip up, and manufactures an
     * enormous asymmetry out of where the measurement boundary happened to fall. Setup is common to
     * both legs instead: it cancels in the *difference* and drags the *ratio* toward 1.0, so this
     * measure understates asymmetry. It cannot invent one.
     */
    data class Leg(
        val dir: Dir,
        val ok: Boolean,
        val bytes: Int,
        val totalMs: Long,
        /**
         * Everything up to `connect()` returning: DNS, TCP, TLS, and -- on the download leg, where
         * the platform's HttpURLConnection may issue the request during connect() -- possibly the
         * request too. Informational only, never compared across directions for that reason. It is
         * here because it is roughly the RRC-promotion cost, and a first leg that paid 5 s to wake
         * a dormant radio needs to be recognisable as that rather than as a slow direction.
         */
        val setupMs: Long,
        val detail: String
    ) {
        /** Bits per millisecond is kbit/s exactly. 0 when the leg failed -- never a flattering 0. */
        val kbitPerSec: Int
            get() = if (!ok || totalMs <= 0) 0 else ((bytes.toLong() * 8) / totalMs).toInt()
    }

    /**
     * One paired cycle. [skipped] non-null means **no measurement was taken** (rate limit, data
     * budget, no cellular network, or the cycle blew its wall-clock bound) -- nothing is recorded in
     * that case, because a skip is not a failure and must not be counted as one in either direction.
     */
    data class AsymResult(
        val down: Leg?,
        val up: Leg?,
        val downFirst: Boolean,
        val skipped: String? = null
    )

    /** Latest cycle, for whatever wants to render it. Null until the first one runs. */
    val lastAsym = MutableStateFlow<AsymResult?>(null)

    // speed.cloudflare.com is the endpoint because it is the only widely-available host that does
    // *both* directions with an exact byte count: `__down?bytes=N` returns precisely N bytes, and
    // `__up` accepts a POST body and echoes what it received in `cf-meta-upload-bytes`, which lets
    // the upload leg prove the bytes actually landed instead of trusting a write() that only
    // reached the kernel buffer. Verified 2026-09-12: both return 200, Content-Length 8192 down,
    // cf-meta-upload-bytes 8192 up. Anycast, so it is also the nearest edge for any user of this
    // repo rather than a server near one developer.
    private const val ASYM_HOST = "speed.cloudflare.com"
    private const val ASYM_DOWN_URL = "https://speed.cloudflare.com/__down?bytes="
    private const val ASYM_UP_URL = "https://speed.cloudflare.com/__up"

    /**
     * DATA COST. This traffic is billed to the user, so the arithmetic is written down rather than
     * felt. Per direction: 8 KB payload. Per cycle: two directions, each on its own fresh
     * connection (see [Leg]), so two TLS handshakes as well.
     *
     *   payload            2 x 8,192 B                          = 16.4 KB
     *   TLS handshake      2 x ~5 KB (ECDSA chain, measured)     = 10.0 KB
     *   request + response headers  2 x ~2 KB (the __up response
     *                      carries ~1.7 KB of CF metadata)       =  4.0 KB
     *                                                            ---------
     *   worst case per cycle, rounded up                          32 KB   [ASYM_CYCLE_BYTES]
     *
     * At the 5-minute floor: 12 cycles/h x 32 KB = 384 KB/h, i.e. 9.2 MB/day if the phone probed
     * flat out for 24 h. At the metered cadence of 15 minutes: 4 cycles/h x 32 KB = 128 KB/h, i.e.
     * 3.1 MB/day. Neither is acceptable as an upper bound on someone else's bill, so a hard daily
     * ceiling sits underneath both: 1 MB/day = 32 cycles/day, about 30 MB/month worst case. 32
     * paired samples a day is comfortably above what the summary needs to say anything (10 pairs),
     * so the ceiling costs statistics nothing. It can be reached before the day ends -- at the
     * metered cadence that takes about 8 h, at the floor about 2.7 h -- and then this probe simply
     * stops until UTC midnight. That is the intended trade: cost beats coverage.
     *
     * Metered is the conservative branch and unknown counts as metered.
     */
    private const val ASYM_BYTES = 8 * 1024
    private const val ASYM_MIN_GAP_MS = 5 * 60_000L
    private const val ASYM_MIN_GAP_METERED_MS = 15 * 60_000L
    private const val ASYM_CYCLE_BYTES = 32 * 1024L
    private const val ASYM_DAY_BUDGET_BYTES = 1_048_576L

    /**
     * A body that ignores `bytes=` must not be paid for. 64 KB is eight times what we asked for:
     * generous enough that a legitimate short read still completes, small enough that a
     * misconfigured or hijacked endpoint costs pennies rather than a plan.
     */
    private const val ASYM_READ_CAP = 64 * 1024
    private const val ASYM_SOCKET_TIMEOUT_MS = 6_000
    /** Both legs, end to end. Two legs x (connect + read) at 6 s each, plus slack. */
    private const val ASYM_CYCLE_BUDGET_MS = 40_000L
    private const val ASYM_PREFS = "cellprobe_asym"

    /**
     * Detached on purpose. `withTimeoutOrNull` around a blocking socket call does nothing if the
     * call is awaited inside the caller's own scope: cancellation cannot interrupt a thread parked
     * in `connect()` or in a DNS lookup, and `withContext`/`coroutineScope` will not return until
     * that thread does. So the cycle runs in a scope nobody joins, and the caller abandons it on
     * timeout. This is the same class of bug as `Network.getAllByName`, which takes no timeout and
     * blocks indefinitely on a non-default network -- one call stopped the probe loop dead, with no
     * error anywhere. An abandoned cycle here leaks one thread until its 6 s socket timeouts fire;
     * [asymInFlight] makes sure a leaked one can never overlap the next.
     */
    private val asymScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var asymInFlight = false
    /** Monotonic backstop for the rate limit: the wall clock can jump, `elapsedRealtime` cannot. */
    @Volatile private var asymLastElapsed = 0L

    /**
     * Measures uplink against downlink once, on the cellular network, and returns both legs.
     *
     * Rate limit and daily data ceiling are enforced HERE rather than in the caller, so no caller
     * -- a tick loop, a UI button, a future experiment -- can bill the user more than the arithmetic
     * on [ASYM_BYTES] allows. A gated call returns [AsymResult.skipped] and spends nothing.
     */
    suspend fun probeAsymmetric(ctx: Context): AsymResult {
        // requestNetwork is asynchronous, as in probeOnce: the first call after service start would
        // otherwise report "no cellular network" when one was moments away.
        var waited = 0
        while (cellular == null && waited < 15_000) { delay(500); waited += 500 }
        val net = cellular
            ?: return AsymResult(null, null, true, skipped = "no cellular network")

        // Claimed atomically. Two callers arriving together -- the tick loop and a UI button, say --
        // must not both decide the other one is not running and both spend the data.
        val claimed = synchronized(this) {
            if (asymInFlight) false else { asymInFlight = true; true }
        }
        if (!claimed) return AsymResult(null, null, true, skipped = "previous cycle in flight")

        val gate = asymGate(ctx)
        if (gate != null) {
            asymInFlight = false
            return AsymResult(null, null, true, skipped = gate)
        }

        // Alternate which direction leads, and do it with a persisted counter rather than clock
        // parity, so the split is exactly even over a small number of cycles. The leading leg pays
        // any RRC promotion and, on this cadence, arrives on a radio that has had five minutes to
        // fall dormant -- device-findings.md measured that promotion at ~290 ms median and a 5.3 s
        // tail, which is far larger than the effect being looked for. If one direction always went
        // first it would simply lose.
        val prefs = runCatching { ctx.getSharedPreferences(ASYM_PREFS, Context.MODE_PRIVATE) }.getOrNull()
        val cycle = prefs?.getInt("cycle", 0) ?: 0
        val downFirst = cycle % 2 == 0

        // Charged before the transfer, never after, so a process death mid-cycle cannot under-count
        // the bill. Over-counting a cycle that failed early is the safe direction of that error.
        charge(ctx)
        runCatching { prefs?.edit()?.putInt("cycle", cycle + 1)?.apply() }
        asymLastElapsed = SystemClock.elapsedRealtime()

        val job = runCatching { asymScope.async {
            try {
                val order = if (downFirst) listOf(Dir.DOWN, Dir.UP) else listOf(Dir.UP, Dir.DOWN)
                // Back to back, not on alternate cycles. The confound is interference, which varies
                // minute to minute on a shared capacity band, so an upload measured minutes from
                // its download would let a bad minute impersonate a bad direction. Same pattern,
                // and same reason, as the IPv4-vs-IPv6 pairing in probeAndRecord above.
                val legs = order.map { transfer(net, it) }
                AsymResult(
                    down = legs.first { it.dir == Dir.DOWN },
                    up = legs.first { it.dir == Dir.UP },
                    downFirst = downFirst
                )
            } finally {
                asymInFlight = false
            }
        } }.getOrElse {
            // The cycle never started, so nothing will ever clear the claim. Clear it here or this
            // probe is dead for the life of the process.
            asymInFlight = false
            return AsymResult(null, null, downFirst, skipped = it.javaClass.simpleName)
        }
        val out = withTimeoutOrNull(ASYM_CYCLE_BUDGET_MS) { runCatching { job.await() }.getOrNull() }
        if (out == null) {
            job.cancel()
            // The partial result went with the cancelled cycle, so there is nothing honest to
            // record: half a pair cannot be compared, and recording it would confuse the pairing in
            // asymSummary as well as the failure counts.
            return AsymResult(null, null, downFirst, skipped = "cycle exceeded ${ASYM_CYCLE_BUDGET_MS / 1000} s")
        }
        lastAsym.value = out
        return out
    }

    /**
     * [probeAsymmetric] plus one `probe_result` row per leg.
     *
     * Safe to call on any cadence -- it self-limits (see [asymGate]) and does nothing when gated, so
     * the caller does not have to hold the cost policy. Nothing is written for a skipped cycle: an
     * absent measurement must never read as a good one, and a synthetic FAIL row for "we chose not
     * to spend the data" would be exactly that, inverted.
     */
    suspend fun probeAsymmetricAndRecord(ctx: Context) {
        val r = runCatching { probeAsymmetric(ctx) }.getOrNull() ?: return
        if (r.skipped != null) return
        // Recorded in the order they ran, so the row order in the table still shows which leg led.
        val ordered = if (r.downFirst) listOf(r.down, r.up) else listOf(r.up, r.down)
        ordered.filterNotNull().forEach { recordLeg(ctx, it) }
    }

    /**
     * Null when the cycle may run; otherwise why it may not. Both branches are cost controls:
     * cadence bounds the burst rate, the daily ceiling bounds the month.
     */
    private fun asymGate(ctx: Context): String? {
        val prefs = runCatching { ctx.getSharedPreferences(ASYM_PREFS, Context.MODE_PRIVATE) }
            .getOrNull() ?: return "no preferences"   // cannot account for the data, so do not spend it
        val now = System.currentTimeMillis()
        val day = now / 86_400_000L
        val spent = if (prefs.getLong("day", -1L) == day) prefs.getLong("bytes", 0L) else 0L
        if (spent + ASYM_CYCLE_BYTES > ASYM_DAY_BUDGET_BYTES) {
            return "daily data ceiling reached (${spent / 1024} KB of ${ASYM_DAY_BUDGET_BYTES / 1024} KB)"
        }
        val gap = if (cellularMetered(ctx)) ASYM_MIN_GAP_METERED_MS else ASYM_MIN_GAP_MS
        // Persisted, so a service restart loop cannot probe once per restart and walk straight
        // through the cadence.
        val last = prefs.getLong("last", 0L)
        if (last != 0L && now - last in 0 until gap) {
            return "rate limited (${(gap - (now - last)) / 1000} s to go)"
        }
        if (asymLastElapsed != 0L && SystemClock.elapsedRealtime() - asymLastElapsed < gap) {
            return "rate limited (monotonic)"
        }
        return null
    }

    private fun charge(ctx: Context) {
        runCatching {
            val prefs = ctx.getSharedPreferences(ASYM_PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val day = now / 86_400_000L                    // UTC day; the exact boundary is irrelevant
            val spent = if (prefs.getLong("day", -1L) == day) prefs.getLong("bytes", 0L) else 0L
            prefs.edit()
                .putLong("day", day)
                .putLong("bytes", spent + ASYM_CYCLE_BYTES)
                .putLong("last", now)
                .apply()
        }
    }

    /**
     * Metered status of the *cellular* network, not of the default route -- this probe always rides
     * cellular even while Wi-Fi carries everything else, so Wi-Fi being free is beside the point.
     * Unknown counts as metered: guessing wrong here costs the user money.
     */
    private fun cellularMetered(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cellular ?: return true) ?: return true
        !(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
          caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
    }.getOrElse { true }

    /**
     * One direction, blocking, on the cellular network. `net.openConnection` reuses the Network this
     * file already holds -- there is exactly one `requestNetwork` in the app and this is not a second
     * one -- and puts DNS, the socket and the handshake on cellular without touching the default
     * route.
     */
    private fun transfer(net: Network, dir: Dir): Leg {
        val t0 = SystemClock.elapsedRealtime()
        var setup = 0L
        var conn: HttpsURLConnection? = null
        return runCatching {
            val url = URL(if (dir == Dir.DOWN) "$ASYM_DOWN_URL$ASYM_BYTES" else ASYM_UP_URL)
            // Cast to HttpsURLConnection rather than HttpURLConnection so the TLS requirement is a
            // compile-time property of the code and not a property of a string literal.
            val c = (net.openConnection(url) as HttpsURLConnection).also { conn = it }
            c.connectTimeout = ASYM_SOCKET_TIMEOUT_MS
            c.readTimeout = ASYM_SOCKET_TIMEOUT_MS
            c.useCaches = false
            // A cached or compressed body is not 8 KB on the wire, and the byte count is the whole
            // basis of the comparison.
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("Cache-Control", "no-store")
            // Each leg pays its own handshake; see the note on Leg.
            c.setRequestProperty("Connection", "close")

            if (dir == Dir.DOWN) {
                c.requestMethod = "GET"
                c.connect()
                setup = SystemClock.elapsedRealtime() - t0
                val code = c.responseCode
                if (code != 200) {
                    Leg(dir, false, ASYM_BYTES, SystemClock.elapsedRealtime() - t0, setup, "HTTP $code")
                } else if (c.contentLength > ASYM_READ_CAP) {
                    // Refuse to pay for a body we did not ask for, before reading a byte of it.
                    Leg(dir, false, ASYM_BYTES, SystemClock.elapsedRealtime() - t0, setup,
                        "oversize body ${c.contentLength}")
                } else {
                    var read = 0
                    val buf = ByteArray(4096)
                    c.inputStream.use { ins ->
                        while (read < ASYM_READ_CAP) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            read += n
                        }
                    }
                    val total = SystemClock.elapsedRealtime() - t0
                    if (read != ASYM_BYTES) {
                        Leg(dir, false, read, total, setup, "short body $read of $ASYM_BYTES")
                    } else {
                        Leg(dir, true, read, total, setup, "setup=${setup}ms")
                    }
                }
            } else {
                c.requestMethod = "POST"
                c.doOutput = true
                // Fixed length, so nothing buffers the body and reports a write that never left the
                // handset -- the failure mode where an upload measurement times the memcpy.
                c.setFixedLengthStreamingMode(ASYM_BYTES)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.connect()
                setup = SystemClock.elapsedRealtime() - t0
                c.outputStream.use { out ->
                    out.write(upBody())
                    out.flush()
                }
                // responseCode is what makes this an uplink measurement. write() returns as soon as
                // 8 KB is in the kernel buffer, which on a stalled uplink is instant and would read
                // as a fast upload; the server's response is the first moment the bytes are known
                // to have crossed the air interface.
                val code = c.responseCode
                val echoed = c.getHeaderField("cf-meta-upload-bytes")?.trim()?.toIntOrNull()
                val total = SystemClock.elapsedRealtime() - t0
                runCatching { c.inputStream.use { it.readBytes() } }   // 0 bytes; drains the response
                when {
                    code != 200 -> Leg(dir, false, ASYM_BYTES, total, setup, "HTTP $code")
                    // The endpoint tells us what it received. If it did not receive all 8 KB, this
                    // is not a slow upload, it is an unknown one, and it is recorded as a failure
                    // rather than as a fast time over fewer bytes.
                    echoed != null && echoed != ASYM_BYTES ->
                        Leg(dir, false, echoed, total, setup, "server got $echoed of $ASYM_BYTES")
                    else -> Leg(dir, true, ASYM_BYTES, total, setup, "setup=${setup}ms")
                }
            }
        }.getOrElse {
            Leg(dir, false, ASYM_BYTES, SystemClock.elapsedRealtime() - t0, setup,
                it.javaClass.simpleName + (it.message?.let { m -> ": ${m.take(50)}" } ?: ""))
        }.also {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * The upload payload. Deterministic across runs so every cycle sends identical bytes, and
     * pseudo-random rather than zeros so a transparent compressing proxy cannot quietly turn 8 KB
     * into 40 bytes and make the uplink look fast.
     */
    private val upBodyBytes: ByteArray by lazy {
        ByteArray(ASYM_BYTES).also { java.util.Random(1).nextBytes(it) }
    }
    private fun upBody(): ByteArray = upBodyBytes

    /**
     * Encoding of a leg into the fixed `probe_result` columns. The table is not migrated for this:
     *
     *  - `probeType` carries the direction and the size: "UP8K" / "DOWN8K".
     *  - `target` carries the endpoint and the byte count.
     *  - `latencyMs` is [Leg.totalMs], the millisecond figure it claims to be. Throughput needs no
     *    column: bytes are in `target`, so kbit/s = bytes x 8 / latencyMs exactly (8,192 B over
     *    latencyMs ms = 65,536 / latencyMs kbit/s), and deriving it is lossless where storing a
     *    rounded rate in an Int would not be.
     *  - `errorCode` is free text. On a FAIL row it is the error, as everywhere else in this file.
     *    On an OK row it carries `setup=Nms`, the connect+TLS portion of the same measurement --
     *    `outcome` is the field that says whether the probe passed, so nothing is ambiguous, and
     *    without setup a first-leg RRC promotion is invisible and reads as a slow direction.
     */
    private suspend fun recordLeg(ctx: Context, leg: Leg) {
        val dao = runCatching { Db.get(ctx).dao() }.getOrNull() ?: return
        runCatching {
            dao.insertProbe(
                ProbeResult(
                    elapsedNanos = SystemClock.elapsedRealtimeNanos(),
                    wallMillis = System.currentTimeMillis(),
                    netId = cellular?.toString() ?: "—",
                    probeType = "${leg.dir.label}${ASYM_BYTES / 1024}K",
                    target = "$ASYM_HOST${if (leg.dir == Dir.DOWN) "/__down" else "/__up"} ${ASYM_BYTES}B",
                    outcome = if (leg.ok) "OK" else "FAIL",
                    latencyMs = leg.totalMs.coerceIn(0L, ASYM_CYCLE_BUDGET_MS).toInt(),
                    errorCode = leg.detail.take(80)
                )
            )
        }
    }

    // -------------------------------------------------------------- reading it back

    /** Pairs are written back to back; anything further apart than this is two different cycles. */
    private const val ASYM_PAIR_WINDOW_MS = 60_000L
    /** Below this the interval is too wide to say anything, so it says that instead. */
    private const val ASYM_MIN_PAIRS = 10

    data class AsymSummary(
        val pairs: Int,
        val complete: Int,
        val upFailures: Int,
        val downFailures: Int,
        val downMedianMs: Int?,
        val upMedianMs: Int?,
        /** Geometric mean of up/down time for the same payload, with a 95 % interval. */
        val ratio: Double?,
        val ratioLo: Double?,
        val ratioHi: Double?,
        val downFirstFraction: Double,
        val report: String
    )

    /**
     * Uplink/downlink asymmetry from recorded rows, with an interval, and willing to say that there
     * isn't any.
     *
     * Validity comes before effect size, in that order and never the other way round: a ratio
     * computed over three pairs, or over pairs where half the uploads failed outright, or where one
     * direction happened to lead every time, is not a small result -- it is not a result. Each of
     * those is reported instead of the number, not alongside it.
     */
    fun asymSummary(rows: List<ProbeResult>): AsymSummary {
        val mine = rows
            .filter {
                it.probeType.startsWith(Dir.UP.label) || it.probeType.startsWith(Dir.DOWN.label)
            }
            .sortedBy { it.elapsedNanos }

        // Pair by adjacency on the monotonic clock. The two legs of a cycle are written back to
        // back, so a neighbouring row of the opposite direction within the window is its partner;
        // anything unpartnered (a cycle whose other leg was never written) is dropped rather than
        // compared against a leg from a different five minutes.
        val pairs = mutableListOf<Pair<ProbeResult, ProbeResult>>()   // (down, up)
        var downFirst = 0
        var i = 0
        while (i + 1 < mine.size) {
            val a = mine[i]
            val b = mine[i + 1]
            val aUp = a.probeType.startsWith(Dir.UP.label)
            val bUp = b.probeType.startsWith(Dir.UP.label)
            val gapMs = (b.elapsedNanos - a.elapsedNanos) / 1_000_000
            if (aUp != bUp && gapMs in 0..ASYM_PAIR_WINDOW_MS) {
                if (!aUp) downFirst++
                pairs += (if (aUp) b else a) to (if (aUp) a else b)
                i += 2
            } else {
                i += 1
            }
        }

        val complete = pairs.filter {
            it.first.outcome == "OK" && it.second.outcome == "OK" &&
                it.first.latencyMs > 0 && it.second.latencyMs > 0
        }
        val upFail = pairs.count { it.second.outcome != "OK" }
        val downFail = pairs.count { it.first.outcome != "OK" }
        val downFirstFrac = if (pairs.isEmpty()) 0.0 else downFirst / pairs.size.toDouble()

        fun median(v: List<Int>) = if (v.isEmpty()) null else v.sorted()[v.size / 2]
        val downMed = median(complete.map { it.first.latencyMs })
        val upMed = median(complete.map { it.second.latencyMs })

        // Paired log ratios. Paired because each pair shares its own minute of interference, and
        // logs because a ratio is multiplicative -- averaging raw ratios lets one 5x pair outweigh
        // five 0.8x ones.
        val logs = complete.map {
            kotlin.math.ln(it.second.latencyMs.toDouble() / it.first.latencyMs.toDouble())
        }
        // (geometric mean, low, high) or null when there is not enough to bound anything.
        val ci: Triple<Double, Double, Double>? = if (logs.size < 2) null else {
            val m = logs.average()
            val sd = kotlin.math.sqrt(logs.sumOf { (it - m) * (it - m) } / (logs.size - 1))
            val half = 1.96 * sd / kotlin.math.sqrt(logs.size.toDouble())
            Triple(kotlin.math.exp(m), kotlin.math.exp(m - half), kotlin.math.exp(m + half))
        }

        val report = buildString {
            append("${pairs.size} paired cycles, ${complete.size} complete\n")
            append("failed legs: up $upFail, down $downFail\n")
            if (downMed != null && upMed != null) {
                append("median for ${ASYM_BYTES / 1024} KB: down ${downMed}ms, up ${upMed}ms\n")
            }
            append(
                when {
                    complete.size < ASYM_MIN_PAIRS ->
                        "TOO FEW SAMPLES — ${complete.size} complete pairs, need $ASYM_MIN_PAIRS. No conclusion."
                    // A direction that fails outright is the loudest evidence there is, but it is
                    // not a ratio, and a ratio computed over the survivors of a failing direction
                    // is a survivorship artefact reading as health.
                    upFail + downFail > pairs.size / 4 ->
                        ("INCOMPLETE — %d of %d pairs lost a leg (up %d, down %d). That split is " +
                         "itself the finding and it is not a ratio: any ratio here would be " +
                         "computed over the survivors of a failing direction, which is exactly how " +
                         "a broken direction reads as a healthy one. No ratio reported.")
                            .format(upFail + downFail, pairs.size, upFail, downFail)
                    downFirstFrac < 0.3 || downFirstFrac > 0.7 ->
                        ("CONFOUNDED — download led %.0f%% of cycles. The leading leg pays the RRC " +
                         "promotion, so order imbalance this large is indistinguishable from a " +
                         "direction effect. No conclusion.").format(downFirstFrac * 100)
                    ci == null ->
                        "NO INTERVAL — not enough variance information. No conclusion."
                    ci.third / ci.second > 10 ->
                        "TOO VARIABLE — interval spans %.1fx to %.1fx. No conclusion."
                            .format(ci.second, ci.third)
                    ci.second > 1.0 ->
                        ("UPLINK SLOWER — %d KB takes %.2fx as long up as down (95%% CI %.2f–%.2f), " +
                         "i.e. uplink throughput is about 1/%.1f of downlink. Both legs include one " +
                         "connection setup, which is common to them and drags the ratio toward 1, " +
                         "so this is a lower bound on the asymmetry.")
                            .format(ASYM_BYTES / 1024, ci.first, ci.second, ci.third, ci.first)
                    ci.third < 1.0 ->
                        ("DOWNLINK SLOWER — %d KB takes %.2fx as long down as up (95%% CI " +
                         "%.2f–%.2f). This is the opposite of the hypothesis and is reported as " +
                         "measured.")
                            .format(ASYM_BYTES / 1024, 1.0 / ci.first, 1.0 / ci.third, 1.0 / ci.second)
                    else ->
                        ("NO DETECTABLE ASYMMETRY at this sample size — %.2fx (95%% CI %.2f–%.2f) " +
                         "spans 1.0 over %d pairs.")
                            .format(ci.first, ci.second, ci.third, complete.size)
                }
            )
        }

        return AsymSummary(
            pairs = pairs.size, complete = complete.size,
            upFailures = upFail, downFailures = downFail,
            downMedianMs = downMed, upMedianMs = upMed,
            ratio = ci?.first, ratioLo = ci?.second, ratioHi = ci?.third,
            downFirstFraction = downFirstFrac, report = report
        )
    }

    /**
     * [asymSummary] over everything in the table. On demand only -- it reads every probe row, which
     * is thousands after a day of collecting, so it does not belong on a tick.
     */
    suspend fun asymSummary(ctx: Context): AsymSummary? = runCatching {
        asymSummary(Db.get(ctx).dao().probesSince(0))
    }.getOrNull()
}
