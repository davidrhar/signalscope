package com.signalscope.collect

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.SystemClock
import com.signalscope.store.MapProbeJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * When cellular is bad and Wi-Fi is there, move the traffic — or say, with numbers, that we cannot.
 *
 * ## Why this exists, and what it is honestly worth
 *
 * The lever this project has actually shipped is [BearerWarmth], and fresh data has cut it down to
 * size: cold wake-ups fail 8.0 % (16/199) against 3.3 % warm (5/153), a ratio of 2.46 at Fisher
 * p = 0.047. Real, modest, and not an answer to "my call dropped". The bearer move is the large
 * lever named in `docs/automatic-actions.md` (item 4) and `docs/remediation-levers.md` §2 — "the
 * single largest win where Wi-Fi exists" — and it has never been built.
 *
 * It is also the lever with the widest gap between what it promises and what a normal app may do,
 * so the tiers are stated in the code rather than implied:
 *
 *  - **Tier 0, and it works:** we can bind our OWN sockets to a chosen bearer, exactly as
 *    [CellProbe] does ([probeBearer] below). That moves this app's traffic and nobody else's. It
 *    is honest and it is small; it is also what makes the Wi-Fi half of the comparison possible.
 *  - **Tier 0, and it is the actual deliverable:** *recommend*, in plain words, with the evidence
 *    attached and a deep link to the settings screen that does it ([recommendation]).
 *  - **Tier 2 (Shizuku):** enabling the Wi-Fi radio is reachable from the shell UID. Setting the
 *    Wi-Fi-calling preference is not — see [investigateTier2], where each door is knocked on at
 *    runtime and the ones that are shut are recorded as shut.
 *  - **Not possible at any tier:** moving another app's traffic. Android provides no mechanism and
 *    `docs/optimisation.md` already rejects pretending otherwise.
 *
 * ## The cost that shapes every threshold in this file
 *
 * Measured, and the reason the constants below are as sticky as they are: the Wi-Fi-to-cellular
 * transition **changes the IP address**, which kills every open socket rather than pausing it.
 * That is what makes an app say "reconnecting" instead of stalling, and it is true in reverse
 * too. So a recommendation to switch bearer is not free advice — every time the user takes it,
 * every open connection on the phone is torn down. A lever that flaps is worse than no lever, and
 * here it is worse in a way that can be counted.
 *
 * ## Three rules this file is built around
 *
 *  1. **Know both bearers at once.** The default route says nothing about the other one. Phase C
 *     assumed it did and produced a phase of invalid data — every cell "100 % validated" because
 *     Wi-Fi carried everything. There are two callbacks here for that reason.
 *  2. **Prefer measured outcome to signal.** The excursion settled this: a median SINR of 0 dB
 *     produced zero failures in 68 probes. Signal is the weather. The comparison below is made of
 *     probe outcomes, and RSSI appears only as a described field, never as a verdict.
 *  3. **A decision with no evidence reports itself as "not measured".** Never as a preference.
 *     Every derived judgement in [State] carries its own sample count and coverage.
 */
object BearerMove {

    // ================================================================ what we know about a bearer

    /**
     * One bearer, as the platform currently describes it.
     *
     * [present] is "a Network of this transport exists", which is not the same as "it carries
     * anything" ([isDefault]) and not the same as "it reaches the internet" ([validated]). Keeping
     * the three apart is the whole point: a Wi-Fi that is present, default and unvalidated is a
     * captive portal, and it is a completely different situation from no Wi-Fi at all.
     */
    data class BearerView(
        val transport: String,
        val present: Boolean = false,
        val validated: Boolean = false,
        val notSuspended: Boolean = true,
        /** Unknown counts as metered. Guessing wrong here costs the user money. */
        val metered: Boolean = true,
        /**
         * Wi-Fi RSSI in dBm where the platform reports one, null otherwise.
         * `NetworkCapabilities.getSignalStrength()` carries it from API 29 with no extra
         * permission — the same field and the same reasoning as [HandoverPredictor]. Some builds
         * never populate it, so null disables the limbs that use it rather than the feature.
         */
        val signalDbm: Int? = null,
        val isDefault: Boolean = false,
        val netId: String? = null,
        /** Elapsed time of the last callback about this bearer. Freshness, not decoration. */
        val lastSeenElapsed: Long = 0L
    ) {
        val describe: String
            get() = when {
                !present -> "$transport: none"
                else -> buildString {
                    append(transport)
                    append(if (validated) ": validated" else ": NOT validated")
                    if (!notSuspended) append(", suspended")
                    append(if (metered) ", metered" else ", unmetered")
                    signalDbm?.let { append(", $it dBm") }
                    if (isDefault) append(", default route")
                }
            }
    }

    // ================================================================ what we measured on it

    /**
     * Reachability evidence for one bearer over [EVIDENCE_WINDOW_MS], and its own coverage.
     *
     * [instrument] is not a failure. Rows classified [CellProbe.Kind.INSTRUMENT] describe a fault
     * in THIS APP — a socket we could not bind — and counting them as network failures is what
     * painted 21 map bins as route loss on a network that was working. They are counted here so
     * the absence is visible, and excluded from every rate.
     */
    data class Evidence(
        val bearer: String,
        val source: String,
        val samples: Int = 0,
        val failures: Int = 0,
        val instrument: Int = 0,
        val p50Ms: Int? = null,
        val p90Ms: Int? = null,
        /** Wilson bounds on the failure rate. Null when there is nothing to bound. */
        val failLo: Double? = null,
        val failHi: Double? = null
    ) {
        val measured: Boolean get() = samples >= MIN_SAMPLES
        val failRate: Double? get() = if (samples <= 0) null else failures.toDouble() / samples

        val basis: String
            get() = when {
                samples == 0 && instrument == 0 -> "$bearer: not measured (no samples)"
                !measured -> "$bearer: not measured ($samples of $MIN_SAMPLES samples" +
                    (if (instrument > 0) ", $instrument instrument fault(s) excluded" else "") + ")"
                else -> "%s: %d cold probes, %d failed (%.0f–%.0f %%), p50 %s, p90 %s%s".format(
                    bearer, samples, failures,
                    (failLo ?: 0.0) * 100, (failHi ?: 0.0) * 100,
                    p50Ms?.let { "$it ms" } ?: "—", p90Ms?.let { "$it ms" } ?: "—",
                    if (instrument > 0) ", $instrument instrument fault(s) excluded" else ""
                )
            }
    }

    /** Which bearer is better right now — including the two answers that are not a preference. */
    enum class Verdict(val label: String) {
        NOT_MEASURED("not measured"),
        TOO_CLOSE("no measurable difference"),
        WIFI_BETTER("Wi-Fi is measurably better"),
        CELLULAR_BETTER("cellular is measurably better")
    }

    /**
     * A recommendation, with the evidence that produced it and the price of taking it.
     *
     * Nothing here is device-specific. [deepLinkId] names an entry in [ActionDeepLinks], which
     * resolves the actual settings activity against the package manager at the moment it is
     * offered — there is a note in that file about a blurb that had one handset's behaviour baked
     * into it, and this file does not repeat it.
     */
    data class Advice(
        val id: String,
        /** 0 = no privilege needed. Nothing here is above Tier 0. */
        val tier: Int,
        val headline: String,
        val body: String,
        val evidence: String,
        val deepLinkId: String?,
        /** What accepting it costs, always stated, because it is never nothing. */
        val cost: String = COST_OF_SWITCHING,
        val raisedWall: Long = System.currentTimeMillis()
    )

    /** What is observable about calls riding Wi-Fi, and what is not. */
    data class VoWifiView(
        /** Subscriptions whose ServiceState carries a PS/WLAN registration row at all. */
        val subsObservable: Int = 0,
        val subsRegistered: Int = 0,
        val subsSeen: Int = 0,
        /** True when every reading we have is older than the service-state staleness bound. */
        val stale: Boolean = true
    ) {
        val summary: String
            get() = when {
                subsSeen == 0 -> "no subscription seen yet — not observable"
                subsObservable == 0 ->
                    "$subsSeen subscription(s), none reports an IWLAN registration row — this " +
                        "build does not expose it, so VoWiFi is NOT observable here"
                stale -> "$subsRegistered of $subsObservable subscription(s) registered on IWLAN, " +
                    "but the reading is stale — reported as unknown, not as off"
                else -> "$subsRegistered of $subsObservable subscription(s) registered on IWLAN"
            }
    }

    /** Outcome of knocking on one Tier-2 door. */
    enum class LeverVerdict(val label: String) {
        UNTESTED("not tested"),
        WORKS("works, verified by effect"),
        NO_OP("accepted and changed nothing"),
        REFUSED("refused"),
        ABSENT("not present on this build"),
        PRESENT_UNVERIFIED("present, effect not verified"),
        WONT_ATTEMPT("available, deliberately not used")
    }

    data class Lever(
        val id: String,
        val tier: Int,
        val verdict: LeverVerdict,
        val detail: String
    )

    data class State(
        val watching: Boolean = false,
        val wifi: BearerView = BearerView("WIFI"),
        val cellular: BearerView = BearerView("CELLULAR"),
        /** From [LiveState.net], read-only: which bearer the rest of the phone is using. */
        val defaultTransport: String = "—",
        val verdict: Verdict = Verdict.NOT_MEASURED,
        val verdictBasis: String = "nothing measured yet",
        val cellEvidence: Evidence = Evidence("cellular", SRC_CELL),
        val wifiEvidence: Evidence = Evidence("Wi-Fi", SRC_WIFI),
        val advice: Advice? = null,
        /** Non-null exactly when [advice] is null: why nothing is being recommended. */
        val suppressedReason: String? = "not started",
        val vowifi: VoWifiView = VoWifiView(),
        val levers: List<Lever> = emptyList(),
        /** Wi-Fi binds that were refused. Ours, not the network's — never a Wi-Fi failure. */
        val wifiBindFailures: Int = 0,
        val note: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    /**
     * The standing recommendation, or null when there is none.
     *
     * Pure and non-blocking: it returns what the engine last decided, so a UI may call it on every
     * recomposition. When it returns null, [State.suppressedReason] says why — "not measured" and
     * "measured, and there is nothing to recommend" are different answers and must not collapse
     * into the same silence.
     */
    fun recommendation(): Advice? = _state.value.advice

    // ================================================================ constants, with reasons

    private const val SRC_CELL = "probe_result, cold reachability rows"
    private const val SRC_WIFI = "Wi-Fi-bound TLS, same recipe as CellProbe"

    const val COST_OF_SWITCHING =
        "Switching bearer changes the IP address, which kills every open socket rather than " +
            "pausing it: apps reconnect rather than resume. Worth paying once; not worth paying " +
            "repeatedly."

    /** The deep link offered with the advice. Resolved by [ActionDeepLinks], never hardcoded. */
    private const val WIFI_LINK_ID = "wifi"

    /**
     * How far back evidence is drawn from.
     *
     * Thirty minutes. Long enough to hold ~40 cellular probe cycles at the 45 s off-Wi-Fi cadence
     * in [CollectorService], short enough that it still describes here and now — the excursion
     * measured 2.1 serving-cell changes per minute, so an hour-wide window would average over
     * several different radio situations and call the result "current".
     */
    private const val EVIDENCE_WINDOW_MS = 30 * 60_000L

    /**
     * Samples below which a bearer is "not measured" rather than good or bad.
     *
     * Six, and it is a floor on honesty rather than on power: at six clean probes a Wilson
     * interval is still about 40 points wide, which is why the comparison in [decide] additionally
     * requires the two intervals to separate before it will name a winner. Six merely stops the
     * word "measured" being attached to two probes.
     */
    private const val MIN_SAMPLES = 6

    /**
     * Cellular failure rate — read off the *lower* Wilson bound — above which the bearer is losing
     * the route rather than merely being slow. 0.20, the same threshold and the same reasoning as
     * [MapProbeJoin.FAIL_RATE_LOSS], and well clear of the 8.0 % cold-wake-up rate that is this
     * device's ordinary background: advice fires when cellular is materially worse than its own
     * measured baseline, not when it is merely being itself.
     */
    private const val CELL_FAIL_FLOOR = MapProbeJoin.FAIL_RATE_LOSS

    /**
     * Hysteresis on that limb: once advice is up, it stands until the failure rate falls below
     * half the floor. Two different numbers on purpose — a single threshold with a rate hovering
     * on it is precisely how a lever flaps, and each flap costs the user a bearer change and every
     * socket on the phone.
     */
    private const val CELL_FAIL_CLEAR = CELL_FAIL_FLOOR / 2

    /** `coverage-map.md` class 4's latency limb, reused so the app has one definition of "slow". */
    private const val SLOW_P90_MS = MapProbeJoin.SLOW_P90_MS

    /**
     * How much better the other bearer's p90 must be before latency alone justifies advice.
     *
     * 2.0x. Probe latency on this network is spiky rather than merely high — p50 145 ms against
     * p90 711 ms in the excursion, a 4.9x spread within one healthy bearer. A ratio smaller than
     * two would therefore fire on the ordinary width of the distribution rather than on a
     * difference between bearers.
     */
    private const val LATENCY_RATIO = 2.0

    /**
     * The condition must hold continuously for this long before advice is raised.
     *
     * Sixty seconds, chosen against the probe cadence rather than as a round number: cellular
     * probes run every 45 s while cellular holds the default route, so a minute of dwell
     * guarantees the verdict survived at least one probe cycle that arrived *after* the condition
     * was first seen, rather than resting entirely on rows already in hand when it was noticed.
     */
    private const val RAISE_DWELL_MS = 60_000L

    /**
     * And it must fail continuously for this long before advice is withdrawn. Deliberately twice
     * [RAISE_DWELL_MS]: withdrawing on the first good probe, then re-raising on the next bad one,
     * is flapping with extra steps.
     */
    private const val CLEAR_DWELL_MS = 120_000L

    /**
     * Minimum gap between one piece of advice being withdrawn and the same advice being offered
     * again. Ten minutes. The user cannot act on a bearer change twice in five minutes without
     * paying two teardowns of every open connection, so offering it twice in five minutes is worse
     * than not offering it at all.
     */
    private const val ADVICE_MIN_GAP_MS = 10 * 60_000L

    /** Gate re-evaluation cadence. Cheap: cached callback fields and a couple of comparisons. */
    private const val TICK_MS = 15_000L

    /** Stored-row evidence is re-read at most this often; it is a table scan, not a getter. */
    private const val EVIDENCE_REFRESH_MS = 120_000L

    /**
     * Wi-Fi probe cadence while the question is live — cellular carries the default route and a
     * Wi-Fi network exists, so "should this move?" is a question with an answer.
     *
     * 90 s, and above [MapProbeJoin.COLD_GAP_MS] (30 s) by a wide margin on purpose: every Wi-Fi
     * sample must qualify as *cold* under the same definition the cellular rows are filtered by,
     * or the comparison is a warm bearer against a cold one. That is the mistake the excursion
     * analysis had to correct for, and it is the difference between 12.3 % and 0 %.
     */
    private const val WIFI_PROBE_GAP_MS = 90_000L

    /** With no live question, a slow baseline is still worth having, at a tenth of the cost. */
    private const val WIFI_PROBE_IDLE_GAP_MS = 15 * 60_000L

    /**
     * Metered Wi-Fi is somebody's phone hotspot and the bytes are billed. Same cadence and same
     * reasoning as [CellProbe]'s metered branch; unknown counts as metered.
     */
    private const val WIFI_PROBE_METERED_GAP_MS = 15 * 60_000L

    /** Below this, and not charging, a discretionary measurement does not run. */
    private const val BATTERY_FLOOR = 20
    private const val BATTERY_REFRESH_MS = 60_000L

    /** Same host, port and timeout as [CellProbe.probeOnce], so the two numbers are comparable. */
    private const val PROBE_HOST = "one.one.one.one"
    private const val PROBE_PORT = 443
    private const val PROBE_TIMEOUT_MS = 6_000

    /** Ring buffer bound. Thirty samples at 90 s is 45 minutes, wider than the window. */
    private const val MAX_SAMPLES = 30

    /** Deep-link resolution is a package-manager query; it does not need to run on every tick. */
    private const val LINK_REFRESH_MS = 10 * 60_000L

    /** Seconds a Tier-2 actuation is given to produce an observable effect before it is a no-op. */
    private const val TIER2_VERIFY_MS = 12_000L

    // ================================================================ lifecycle and callbacks

    private val lock = Any()
    private var job: Job? = null
    private var cm: ConnectivityManager? = null
    private var appCtx: Context? = null

    @Volatile private var wifiNetwork: Network? = null
    @Volatile private var wifiView = BearerView("WIFI")
    @Volatile private var cellView = BearerView("CELLULAR")

    private var wifiCb: ConnectivityManager.NetworkCallback? = null
    private var cellCb: ConnectivityManager.NetworkCallback? = null

    private class Sample(val atElapsed: Long, val ok: Boolean, val ms: Int)

    /** Wi-Fi outcomes, in memory only. No new table, no migration. */
    private val wifiSamples = ArrayDeque<Sample>()

    /**
     * Binds to the Wi-Fi network that were refused. Ours, not Wi-Fi's, and kept out of every rate
     * for the same reason [CellProbe.instrumentBroken] exists: a broken instrument reporting as a
     * broken network is the failure mode this project exists to eliminate.
     */
    @Volatile private var wifiBindFails = 0

    @Volatile private var deepLinkAvailable: Boolean? = null

    /**
     * Two callbacks, for the reason [ConnectivityCollector] gives: the default route describes
     * whatever the phone happens to be using and says nothing whatever about the other bearer.
     *
     * The **Wi-Fi** one is a `requestNetwork`, not a listen-only registration, and the difference
     * is load-bearing: binding a socket to a non-default network is only permitted while the app
     * holds a request for it (observed as a three-hour run of `EPERM` in [CellProbe]). Without the
     * request we could watch Wi-Fi but never measure it while cellular held the route, which is
     * the only situation this whole file is about. It is the Wi-Fi counterpart of the single
     * cellular reservation [CellProbe] holds, and like that one there is exactly one of it.
     *
     * The **cellular** one is deliberately listen-only. [CellProbe] already holds the app's one
     * cellular reservation; a second would be a second reason for the modem to stay up that
     * nobody is accounting for.
     */
    fun start(ctx: Context, scope: CoroutineScope) {
        val app = ctx.applicationContext
        synchronized(lock) {
            if (job?.isActive == true) return
            appCtx = app
            val manager = runCatching { app.getSystemService(ConnectivityManager::class.java) }
                .getOrNull()
            if (manager == null) {
                _state.value = _state.value.copy(
                    watching = false, note = "no ConnectivityManager", suppressedReason =
                        "cannot watch either bearer on this device")
                return
            }
            cm = manager

            val w = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    wifiNetwork = n
                }

                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    wifiNetwork = n
                    runCatching { wifiView = read("WIFI", n, caps) }
                }

                override fun onLost(n: Network) {
                    if (wifiNetwork == n) wifiNetwork = null
                    // Absent, not bad. An absent bearer has no measurements and no verdict; the
                    // samples are dropped so a Wi-Fi network we rejoin later is not judged on
                    // another network's history.
                    wifiView = BearerView("WIFI", lastSeenElapsed = SystemClock.elapsedRealtime())
                    synchronized(wifiSamples) { wifiSamples.clear() }
                }
            }
            val c = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    runCatching { cellView = read("CELLULAR", n, caps) }
                }

                override fun onLost(n: Network) {
                    cellView = BearerView("CELLULAR", lastSeenElapsed = SystemClock.elapsedRealtime())
                }
            }

            runCatching {
                manager.requestNetwork(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    w
                )
                wifiCb = w
            }.onFailure {
                _state.value = _state.value.copy(
                    note = "could not reserve Wi-Fi: ${it.javaClass.simpleName} — Wi-Fi can be " +
                        "watched but not measured while it is not the default route")
            }
            runCatching {
                manager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .build(),
                    c
                )
                cellCb = c
            }

            job = scope.launch { engine(app) }
            _state.value = _state.value.copy(watching = true, suppressedReason = "starting")
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel(); job = null
            wifiCb?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
            cellCb?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
            wifiCb = null; cellCb = null; wifiNetwork = null
        }
        _state.value = _state.value.copy(
            watching = false, advice = null, suppressedReason = "stopped")
    }

    private fun read(transport: String, n: Network, caps: NetworkCapabilities): BearerView {
        val defaultTransport = runCatching { LiveState.net.value.transport }.getOrDefault("—")
        return BearerView(
            transport = transport,
            present = true,
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            notSuspended = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
            // Unknown counts as metered, as everywhere else in this app.
            metered = !(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)),
            // SIGNAL_STRENGTH_UNSPECIFIED is Int.MIN_VALUE, and some builds never populate the
            // field at all. Null then, so an absent reading disables the limbs that use it rather
            // than arriving as a very good or very bad number.
            signalDbm = runCatching { caps.signalStrength }
                .getOrNull()
                ?.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && it < 0 },
            isDefault = defaultTransport == transport,
            netId = n.toString(),
            lastSeenElapsed = SystemClock.elapsedRealtime()
        )
    }

    // ================================================================ the engine

    private suspend fun engine(ctx: Context) {
        var lastEvidenceElapsed = 0L
        var lastWifiProbeElapsed = 0L
        var lastLinkElapsed = 0L
        var batteryPct = -1
        var batteryCharging = false
        var lastBatteryElapsed = 0L

        // Hysteresis state. `candidate` is what the measurements currently argue for; `standing`
        // is what we are actually saying. They are separate so that a condition has to persist
        // before it becomes advice, and persist in the other direction before it stops being it.
        var candidateId: String? = null
        var candidateSinceElapsed = 0L
        var standing: Advice? = null
        var standingFailingSinceElapsed = 0L
        val lastWithdrawnElapsed = HashMap<String, Long>()

        var cellEvidence = Evidence("cellular", SRC_CELL)

        try {
            while (currentCoroutineContext().isActive) {
                val now = SystemClock.elapsedRealtime()

                if (now - lastBatteryElapsed >= BATTERY_REFRESH_MS || lastBatteryElapsed == 0L) {
                    val b = readBattery(ctx)
                    batteryPct = b.first; batteryCharging = b.second
                    lastBatteryElapsed = now
                }

                if (now - lastLinkElapsed >= LINK_REFRESH_MS || lastLinkElapsed == 0L) {
                    lastLinkElapsed = now
                    deepLinkAvailable = runCatching {
                        ActionDeepLinks.resolve(ctx).firstOrNull { it.link.id == WIFI_LINK_ID }
                            ?.available
                    }.getOrNull()
                }

                if (now - lastEvidenceElapsed >= EVIDENCE_REFRESH_MS || lastEvidenceElapsed == 0L) {
                    lastEvidenceElapsed = now
                    cellEvidence = runCatching { readCellEvidence(ctx) }
                        .getOrDefault(Evidence("cellular", SRC_CELL))
                }

                val wifi = wifiView
                val cell = cellView
                val defaultTransport = runCatching { LiveState.net.value.transport }
                    .getOrDefault("—")

                // The question is live when the phone is on one bearer and the other one exists.
                // That is the only situation in which probing Wi-Fi buys an answer rather than a
                // number, so it sets the cadence.
                val questionLive = wifi.present && cell.present
                val gap = when {
                    wifi.metered -> WIFI_PROBE_METERED_GAP_MS
                    questionLive -> WIFI_PROBE_GAP_MS
                    else -> WIFI_PROBE_IDLE_GAP_MS
                }
                val batteryStarved = batteryPct in 0 until BATTERY_FLOOR && !batteryCharging
                if (wifi.present && !batteryStarved && now - lastWifiProbeElapsed >= gap) {
                    lastWifiProbeElapsed = now
                    runCatching { probeWifi() }
                }

                val wifiEvidence = summarise("Wi-Fi", SRC_WIFI, wifiSamples, wifiBindFails)
                val decision = decide(wifi, cell, wifiEvidence, cellEvidence, standing != null)

                // ------------------------------------------------ hysteresis
                val wantId = decision.adviceId
                if (wantId == null || wantId != candidateId) {
                    candidateId = wantId
                    candidateSinceElapsed = now
                }

                if (standing != null) {
                    if (wantId == standing.id) {
                        standingFailingSinceElapsed = 0L
                    } else {
                        if (standingFailingSinceElapsed == 0L) standingFailingSinceElapsed = now
                        if (now - standingFailingSinceElapsed >= CLEAR_DWELL_MS) {
                            lastWithdrawnElapsed[standing.id] = now
                            standing = null
                            standingFailingSinceElapsed = 0L
                        }
                    }
                }

                var suppressed: String? = null
                if (standing == null && wantId != null) {
                    val heldMs = now - candidateSinceElapsed
                    val sinceWithdrawn = lastWithdrawnElapsed[wantId]?.let { now - it }
                    when {
                        heldMs < RAISE_DWELL_MS ->
                            suppressed = "condition has held ${heldMs / 1000}s of the " +
                                "${RAISE_DWELL_MS / 1000}s required before anything is recommended"
                        sinceWithdrawn != null && sinceWithdrawn < ADVICE_MIN_GAP_MS ->
                            suppressed = "same advice was withdrawn " +
                                "${sinceWithdrawn / 60_000}m ago; a bearer change costs every open " +
                                "socket, so it is not re-offered for " +
                                "${ADVICE_MIN_GAP_MS / 60_000} minutes"
                        else -> standing = decision.advice
                    }
                }
                if (standing == null && suppressed == null) suppressed = decision.why

                _state.value = _state.value.copy(
                    watching = true,
                    wifi = wifi,
                    cellular = cell,
                    defaultTransport = defaultTransport,
                    verdict = decision.verdict,
                    verdictBasis = decision.why,
                    cellEvidence = cellEvidence,
                    wifiEvidence = wifiEvidence,
                    advice = standing,
                    suppressedReason = if (standing == null) suppressed else null,
                    vowifi = runCatching { readVoWifi() }.getOrDefault(VoWifiView()),
                    wifiBindFailures = wifiBindFails
                )

                delay(TICK_MS)
            }
        } finally {
            synchronized(lock) { if (job?.isActive != true) job = null }
        }
    }

    // ================================================================ measurement (Tier 0)

    /**
     * One reachability probe on a socket bound to the Wi-Fi network — **Tier 0**, the same
     * `requestNetwork` + `bindSocket` mechanism [CellProbe] uses, and the same recipe: DNS on that
     * network, TCP connect, TLS handshake, one 6 s timeout, total elapsed. Identical recipe
     * because the number is compared against `probe_result` rows produced by that code, and two
     * latencies measured to two different definitions are not a comparison.
     *
     * This helps this app only. No other app's traffic moves, the default route is untouched, and
     * saying so plainly is the honest description of what Tier 0 buys.
     */
    private suspend fun probeWifi() = withContext(Dispatchers.IO) {
        val net = wifiNetwork ?: return@withContext
        val t0 = SystemClock.elapsedRealtime()
        val outcome = runCatching {
            val addr = net.getAllByName(PROBE_HOST).firstOrNull()
                ?: return@runCatching null   // no address: a property of the link, not a failure
            val sock = Socket()
            net.bindSocket(sock)
            sock.connect(InetSocketAddress(addr, PROBE_PORT), PROBE_TIMEOUT_MS)
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(sock, PROBE_HOST, PROBE_PORT, true) as SSLSocket
            tls.soTimeout = PROBE_TIMEOUT_MS
            tls.startHandshake()
            runCatching { tls.close() }
            true
        }.getOrElse { t ->
            // A refused bind is ours. It must never be recorded as a Wi-Fi failure — the same
            // distinction CellProbe had to introduce after 248 of them read as a night of total
            // cellular outage.
            if (isBindDenied(t)) {
                wifiBindFails++
                return@withContext
            }
            false
        } ?: return@withContext

        val ms = (SystemClock.elapsedRealtime() - t0).toInt()
        synchronized(wifiSamples) {
            wifiSamples.addLast(Sample(SystemClock.elapsedRealtime(), outcome, ms))
            while (wifiSamples.size > MAX_SAMPLES) wifiSamples.removeFirst()
        }
    }

    private fun isBindDenied(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            val m = e.message ?: ""
            if (m.contains("Binding socket to network") || m.contains("EPERM")) return true
            e = e.cause
        }
        return false
    }

    private fun summarise(
        bearer: String,
        source: String,
        samples: ArrayDeque<Sample>,
        instrument: Int
    ): Evidence {
        val cut = SystemClock.elapsedRealtime() - EVIDENCE_WINDOW_MS
        val live = synchronized(samples) { samples.filter { it.atElapsed >= cut } }
        if (live.isEmpty()) return Evidence(bearer, source, instrument = instrument)
        val fails = live.count { !it.ok }
        val ok = live.filter { it.ok }.map { it.ms }.sorted()
        val w = wilson(fails, live.size)
        return Evidence(
            bearer = bearer, source = source,
            samples = live.size, failures = fails, instrument = instrument,
            p50Ms = MapProbeJoin.percentile(ok, 0.5),
            p90Ms = MapProbeJoin.percentile(ok, 0.9),
            failLo = w?.first, failHi = w?.second
        )
    }

    /**
     * Cellular evidence from `probe_result`, through [MapProbeJoin.read] so that coldness and the
     * instrument-fault exclusion are decided by the one piece of code that already knows how.
     *
     * **Cold rows only.** Every Wi-Fi sample this file takes is cold by construction — the probe
     * cadence is 90 s against a 30 s cold gap — so comparing against warm cellular rows would be
     * a cold bearer against a warm one, which is exactly the confound the excursion analysis had
     * to control for to get 12.3 % against 0 %. Warm rows are not evidence of nothing; they are
     * evidence of a different question.
     *
     * Windowing is on wall time because `elapsedNanos` restarts at boot, and rows outlive boots.
     */
    private suspend fun readCellEvidence(ctx: Context): Evidence = withContext(Dispatchers.IO) {
        val rows = runCatching { MapProbeJoin.read(ctx) }.getOrNull()
            ?: return@withContext Evidence("cellular", SRC_CELL)
        val cut = System.currentTimeMillis() - EVIDENCE_WINDOW_MS
        val inWindow = rows.filter { it.wall >= cut }

        // Counted, never rated. A row we could not even attempt says nothing about the bearer.
        val instrument = inWindow.count {
            CellProbe.kindOf(it.probeType, it.errorCode) == CellProbe.Kind.INSTRUMENT
        }
        val usable = inWindow.filter {
            it.onBearer && it.cold && CellProbe.isReachability(it.probeType, it.errorCode)
        }
        if (usable.isEmpty()) {
            return@withContext Evidence("cellular", SRC_CELL, instrument = instrument)
        }
        val fails = usable.count { !it.ok }
        val ok = usable.filter { it.ok }.map { it.latencyMs }.sorted()
        val w = wilson(fails, usable.size)
        Evidence(
            bearer = "cellular", source = SRC_CELL,
            samples = usable.size, failures = fails, instrument = instrument,
            p50Ms = MapProbeJoin.percentile(ok, 0.5),
            p90Ms = MapProbeJoin.percentile(ok, 0.9),
            failLo = w?.first, failHi = w?.second
        )
    }

    /**
     * Wilson score interval on a failure rate. Wilson rather than the normal approximation because
     * the counts here are small and often zero, where the normal interval collapses to a point and
     * would report certainty we do not have.
     */
    private fun wilson(k: Int, n: Int): Pair<Double, Double>? {
        if (n <= 0) return null
        val z = 1.96
        val p = k.toDouble() / n
        val denom = 1 + z * z / n
        val centre = p + z * z / (2.0 * n)
        val half = z * kotlin.math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n))
        return ((centre - half) / denom).coerceAtLeast(0.0) to
            ((centre + half) / denom).coerceAtMost(1.0)
    }

    // ================================================================ the decision

    private class Decision(
        val verdict: Verdict,
        val why: String,
        val adviceId: String?,
        val advice: Advice?
    )

    const val ADVICE_MOVE_TO_WIFI = "move-to-wifi"
    const val ADVICE_LEAVE_WIFI = "leave-wifi"

    /**
     * Which bearer is better, and whether that is worth saying out loud.
     *
     * Three limbs, strongest first, and every one of them is an outcome rather than a signal
     * level. RSSI is described in [State] and used by nothing here: the excursion measured a
     * median SINR of 0 dB producing zero failures in 68 probes, which is as clear a statement as
     * the data can make that the level does not predict the outcome on this kind of network.
     *
     * [standing] loosens the thresholds for advice that is already up — that is the hysteresis,
     * and it is asymmetric on purpose.
     */
    private fun decide(
        wifi: BearerView,
        cell: BearerView,
        wifiEv: Evidence,
        cellEv: Evidence,
        standing: Boolean
    ): Decision {
        val failFloor = if (standing) CELL_FAIL_CLEAR else CELL_FAIL_FLOOR

        if (!wifi.present && !cell.present) {
            return Decision(Verdict.NOT_MEASURED, "neither bearer is present", null, null)
        }

        // --- Limb 1: the route. The platform's own validation probe is a measured outcome, and
        // it is the one piece of evidence available with no samples of ours at all. An unvalidated
        // bearer is one Android has itself decided does not reach the internet.
        if (wifi.present && cell.present) {
            if (!cell.validated && wifi.validated && cell.isDefault) {
                return moveToWifi(
                    wifi, cell, wifiEv, cellEv,
                    "cellular holds the default route and is not validated — the platform's own " +
                        "reachability check says it does not reach the internet — while Wi-Fi is " +
                        "validated."
                )
            }
            if (!wifi.validated && cell.validated && wifi.isDefault) {
                return leaveWifi(
                    wifi, cell, wifiEv, cellEv,
                    "Wi-Fi holds the default route and is not validated, while cellular is. " +
                        "An associated but unreachable access point is the case the phone is " +
                        "slowest to abandon on its own."
                )
            }
            if (wifi.present && !wifi.notSuspended && cell.validated && wifi.isDefault) {
                return leaveWifi(
                    wifi, cell, wifiEv, cellEv,
                    "Wi-Fi is suspended while cellular is validated."
                )
            }
        }

        // --- Limbs 2 and 3 need measurements on both sides, and say so when they do not have
        // them. This is the branch that must never quietly become a preference.
        if (!cellEv.measured || !wifiEv.measured) {
            val missing = listOfNotNull(
                if (!cellEv.measured) cellEv.basis else null,
                if (!wifiEv.measured) wifiEv.basis else null
            ).joinToString("; ")
            return Decision(
                Verdict.NOT_MEASURED,
                "not measured — $missing",
                null, null
            )
        }

        val cellLo = cellEv.failLo ?: 0.0
        val cellHi = cellEv.failHi ?: 1.0
        val wifiLo = wifiEv.failLo ?: 0.0
        val wifiHi = wifiEv.failHi ?: 1.0

        // --- Limb 2: failures. The intervals must separate, not merely the point estimates:
        // with these sample sizes two point estimates differ by chance most of the time.
        if (cellLo >= failFloor && wifiHi < cellLo) {
            return moveToWifi(
                wifi, cell, wifiEv, cellEv,
                "cellular is losing the route: at least %.0f %% of cold connections failed, " +
                    "against at most %.0f %% on Wi-Fi.".format(cellLo * 100, wifiHi * 100)
            )
        }
        if (wifiLo >= failFloor && cellHi < wifiLo) {
            return leaveWifi(
                wifi, cell, wifiEv, cellEv,
                "Wi-Fi is losing the route: at least %.0f %% of cold connections failed, " +
                    "against at most %.0f %% on cellular.".format(wifiLo * 100, cellHi * 100)
            )
        }

        // --- Limb 3: latency, and only once a bearer is slow in absolute terms as well as
        // relative ones. A bearer twice as fast as another fast bearer is not a reason to tear
        // down every socket on the phone.
        val cp90 = cellEv.p90Ms
        val wp90 = wifiEv.p90Ms
        if (cp90 != null && wp90 != null) {
            if (cp90 >= SLOW_P90_MS && cp90 >= wp90 * LATENCY_RATIO) {
                return moveToWifi(
                    wifi, cell, wifiEv, cellEv,
                    "cellular p90 is $cp90 ms against ${wp90} ms on Wi-Fi — slow in absolute " +
                        "terms and more than ${LATENCY_RATIO}x the alternative."
                )
            }
            if (wp90 >= SLOW_P90_MS && wp90 >= cp90 * LATENCY_RATIO) {
                return leaveWifi(
                    wifi, cell, wifiEv, cellEv,
                    "Wi-Fi p90 is $wp90 ms against ${cp90} ms on cellular — slow in absolute " +
                        "terms and more than ${LATENCY_RATIO}x the alternative."
                )
            }
        }

        return Decision(
            Verdict.TOO_CLOSE,
            "both bearers measured and neither is measurably better — ${cellEv.basis}; " +
                "${wifiEv.basis}. Nothing to recommend.",
            null, null
        )
    }

    private fun moveToWifi(
        wifi: BearerView, cell: BearerView, wifiEv: Evidence, cellEv: Evidence, why: String
    ): Decision {
        // Advice to move ONTO Wi-Fi is pointless when Wi-Fi already carries everything.
        if (wifi.isDefault) {
            return Decision(
                Verdict.WIFI_BETTER,
                "Wi-Fi is better and already holds the default route — $why",
                null, null
            )
        }
        if (!wifi.present) {
            return Decision(Verdict.NOT_MEASURED, "no Wi-Fi network to move to", null, null)
        }
        return Decision(
            Verdict.WIFI_BETTER, why, ADVICE_MOVE_TO_WIFI,
            Advice(
                id = ADVICE_MOVE_TO_WIFI,
                tier = 0,
                headline = "Wi-Fi is measurably better here than the mobile network",
                body = buildString {
                    append("Cellular is carrying your traffic and Wi-Fi is connected. ")
                    append("Moving to it would take everything off the failing bearer — not just ")
                    append("this app, which is all this app can move on its own. ")
                    if (deepLinkAvailable == true) {
                        append("The settings screen that controls when the phone switches is one ")
                        append("tap away.")
                    } else {
                        append("This build has no settings screen we can open for it directly.")
                    }
                },
                evidence = "$why\n${cellEv.basis}\n${wifiEv.basis}\n" +
                    "${cell.describe}\n${wifi.describe}",
                deepLinkId = WIFI_LINK_ID
            )
        )
    }

    private fun leaveWifi(
        wifi: BearerView, cell: BearerView, wifiEv: Evidence, cellEv: Evidence, why: String
    ): Decision {
        if (!wifi.isDefault) {
            return Decision(
                Verdict.CELLULAR_BETTER,
                "cellular is better and Wi-Fi does not hold the default route — $why",
                null, null
            )
        }
        if (!cell.present) {
            return Decision(
                Verdict.NOT_MEASURED, "no cellular network to fall back to", null, null)
        }
        return Decision(
            Verdict.CELLULAR_BETTER, why, ADVICE_LEAVE_WIFI,
            Advice(
                id = ADVICE_LEAVE_WIFI,
                tier = 0,
                headline = "This Wi-Fi is worse than the mobile network right now",
                body = buildString {
                    append("The phone is using Wi-Fi and the mobile network is measurably ")
                    append("better. Disconnecting from this access point — or forgetting it, if ")
                    append("it does this every time — moves everything back. ")
                    append("Android will not always do it on its own: an access point that is ")
                    append("associated and reachable-looking keeps its score until it fails hard.")
                },
                evidence = "$why\n${wifiEv.basis}\n${cellEv.basis}\n" +
                    "${wifi.describe}\n${cell.describe}",
                deepLinkId = WIFI_LINK_ID
            )
        )
    }

    // ================================================================ VoWiFi: what is observable

    /**
     * Whether calls *could* be riding Wi-Fi, from data already collected.
     *
     * `ServiceState.getNetworkRegistrationInfoList()` carries a `domain=PS transportType=WLAN` row
     * when the ePDG tunnel is up, and [TelephonyCollector] already reads it into
     * [SimState.iwlanRegistered]. That is genuinely useful: it is the difference between "Wi-Fi
     * calling is configured" and "Wi-Fi calling is registered right now".
     *
     * **It is not the same as "this call is on Wi-Fi", and nothing available to this app is.**
     * The per-call transport lives in `Call.Details.PROPERTY_WIFI`, which is readable only by an
     * `InCallService` — i.e. by the default dialler. So the honest description of VoWiFi here is
     * *observable, not actionable*: we can say whether the tunnel is up, we cannot say whether a
     * given call is using it, and [investigateTier2] establishes that we cannot turn it on either.
     *
     * Staleness is reported rather than smoothed over. A registration reading from an hour ago is
     * not evidence that VoWiFi is off now, and [SimState.serviceStale] is the check that keeps an
     * absent reading from arriving as a negative one.
     */
    private fun readVoWifi(): VoWifiView {
        val sims = runCatching { LiveState.sims.value.values.toList() }.getOrDefault(emptyList())
        if (sims.isEmpty()) return VoWifiView()
        val observable = sims.filter { it.iwlanRegistered != null }
        return VoWifiView(
            subsObservable = observable.size,
            subsRegistered = observable.count { it.iwlanRegistered == true },
            subsSeen = sims.size,
            stale = observable.isEmpty() || observable.all { it.serviceStale() }
        )
    }

    // ================================================================ Tier 2: knock on each door

    /**
     * Ask the shell UID what it can actually do about the bearer, and believe only what can be
     * observed afterwards.
     *
     * **Exit status is not evidence.** [PhaseA] reported ten successful trials that changed
     * nothing because it trusted a zero exit code; `cmd phone set-allowed-network-types-for-users`
     * returns success on some encodings and never changes the value. Every actuation below is
     * judged by reading the state back, and an accepted command with no observable effect is
     * recorded as [LeverVerdict.NO_OP], which is a result rather than a failure.
     *
     * On demand only — never from a tick. It runs shell commands, and one of them can change the
     * state of the device.
     *
     * What was found by running these as uid 2000 (which is exactly Shizuku's UID) on one handset
     * on 2026-09-13 is in the comments on each lever. They are recorded as observations, not as
     * expectations: the code classifies whatever it gets back at runtime, so a build where a door
     * is open will be reported as open.
     */
    suspend fun investigateTier2(ctx: Context, mayEnableWifi: Boolean = false): List<Lever> {
        if (runCatching { ShizukuBridge.state.value }.getOrNull() != ShizukuState.READY) {
            val l = listOf(
                Lever("shizuku", 2, LeverVerdict.UNTESTED,
                    "Shizuku is not ready, so no Tier 2 lever can be tested. This is not evidence " +
                        "that any of them work.")
            )
            _state.value = _state.value.copy(levers = l)
            return l
        }

        val out = mutableListOf<Lever>()

        // ---- 1. Read the Wi-Fi radio state.
        //
        // Worth having on its own: this app holds no ACCESS_WIFI_STATE and is not going to declare
        // one, so at Tier 0 it cannot distinguish "Wi-Fi is switched off" from "Wi-Fi is on and
        // there is nothing in range" -- both arrive as no callback at all. The shell can tell them
        // apart. Observed working at uid 2000: the first line reads "Wifi is enabled".
        //
        // Only the first line is ever parsed. The rest of that output contains the SSID, the BSSID
        // and the MAC address, and none of those belong anywhere in this app.
        val status = runCatching { ShizukuBridge.exec("cmd wifi status") }.getOrNull()
        val firstLine = status?.out?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val wifiEnabled: Boolean? = when {
            firstLine.contains("is enabled", true) -> true
            firstLine.contains("is disabled", true) -> false
            else -> null
        }
        out += Lever(
            "wifi-state-read", 2,
            when {
                wifiEnabled != null -> LeverVerdict.WORKS
                status?.out.isNullOrBlank() -> LeverVerdict.ABSENT
                else -> LeverVerdict.REFUSED
            },
            when {
                wifiEnabled == true -> "Wi-Fi radio is ON, read from the shell — a state this app " +
                    "cannot see at Tier 0 without declaring ACCESS_WIFI_STATE."
                wifiEnabled == false -> "Wi-Fi radio is OFF, read from the shell."
                else -> "The shell would not report Wi-Fi state: ${status?.out?.take(120) ?: "no output"}"
            }
        )

        // ---- 2. Enable the Wi-Fi radio.
        //
        // The command exists (`cmd wifi set-wifi-enabled enabled`, and `svc wifi enable` behind
        // it) and the shell UID holds both CHANGE_WIFI_STATE and NETWORK_SETTINGS, which is what
        // gates it. That is a strong reason to expect it to work and it is NOT verification.
        //
        // Two rules here, and both are deliberate:
        //  - We never turn Wi-Fi OFF. Not to create a test, not to measure anything. The app has
        //    no business disabling a user's connectivity, and on a device reached over wireless
        //    debugging it also severs the only channel that could turn it back on.
        //  - With Wi-Fi already on there is nothing to observe, so the verdict is
        //    PRESENT_UNVERIFIED and says which half is missing. An untested lever reported as
        //    working is exactly the Phase A mistake.
        //
        // Verification, when it does run, is by EFFECT: the state is read back, and the Wi-Fi
        // callback in this file is given a window to deliver a network. Exit code is ignored.
        if (wifiEnabled == false && mayEnableWifi) {
            val before = wifiNetwork != null
            runCatching { ShizukuBridge.exec("cmd wifi set-wifi-enabled enabled") }
            val deadline = SystemClock.elapsedRealtime() + TIER2_VERIFY_MS
            var nowEnabled: Boolean? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(1_000)
                val s = runCatching { ShizukuBridge.exec("cmd wifi status") }.getOrNull()
                val line = s?.out?.lineSequence()?.firstOrNull()?.trim().orEmpty()
                if (line.contains("is enabled", true)) { nowEnabled = true; break }
                if (line.contains("is disabled", true)) nowEnabled = false
            }
            val gotNetwork = !before && wifiNetwork != null
            out += Lever(
                "wifi-enable", 2,
                if (nowEnabled == true) LeverVerdict.WORKS else LeverVerdict.NO_OP,
                if (nowEnabled == true)
                    "Wi-Fi radio went from off to on and stayed on" +
                        (if (gotNetwork) ", and a Wi-Fi network arrived on our callback." else
                            ". No network associated within ${TIER2_VERIFY_MS / 1000}s, which is " +
                                "a statement about what is in range, not about the lever.")
                else
                    "The command was accepted and the radio did not come on within " +
                        "${TIER2_VERIFY_MS / 1000}s. Verified by reading the state back, not by " +
                        "exit code — the same check Phase A lacked."
            )
        } else {
            out += Lever(
                "wifi-enable", 2, LeverVerdict.PRESENT_UNVERIFIED,
                "`cmd wifi set-wifi-enabled` is in the shell's command surface and the shell UID " +
                    "holds CHANGE_WIFI_STATE and NETWORK_SETTINGS, so it should work. Not " +
                    "verified: " + (
                    if (wifiEnabled != false) "Wi-Fi is already on, and this app does not turn it " +
                        "off to manufacture a test."
                    else "enabling was not requested."
                    )
            )
        }

        // ---- 3. The Wi-Fi calling preference. Three doors, all of them shut.
        //
        // (a) The legacy global setting. On this build `settings get global wfc_ims_enabled`
        //     returns null, along with wfc_ims_mode and volte_vt_enabled: these moved out of
        //     Settings.Global and into the per-subscription telephony provider years ago, so
        //     writing them would be writing to a key nothing reads. That is precisely the failure
        //     mode that looks like success.
        val g = runCatching { ShizukuBridge.exec("settings get global wfc_ims_enabled") }.getOrNull()
        val gv = g?.out?.trim().orEmpty()
        out += Lever(
            "vowifi-global-setting", 2,
            if (gv.isEmpty() || gv.equals("null", true)) LeverVerdict.ABSENT
            else LeverVerdict.PRESENT_UNVERIFIED,
            if (gv.isEmpty() || gv.equals("null", true))
                "wfc_ims_enabled is null in Settings.Global — the VoWiFi preference is not stored " +
                    "there on this build. Writing it would change a key nothing reads."
            else "wfc_ims_enabled = $gv in Settings.Global. Present, but whether telephony reads " +
                "it here is unverified, so it is not written."
        )

        // (b) The per-subscription table where it actually lives. Reading it as the shell UID was
        //     refused outright: "Access SIMINFO table from not phone/system UID". Shizuku cannot
        //     read this, so it cannot write it either, and a lever that cannot read its own state
        //     could not be verified by effect even if it could write.
        val sim = runCatching {
            ShizukuBridge.exec(
                "content query --uri content://telephony/siminfo --projection sim_id:wfc_ims_enabled")
        }.getOrNull()
        val simOut = sim?.out.orEmpty()
        out += Lever(
            "vowifi-siminfo", 2,
            if (simOut.contains("SecurityException", true) ||
                simOut.contains("Permission denied", true)) LeverVerdict.REFUSED
            else if (simOut.isBlank()) LeverVerdict.ABSENT else LeverVerdict.PRESENT_UNVERIFIED,
            "The per-subscription VoWiFi preference lives in the telephony provider's SIMINFO " +
                "table. Shell UID says: " + (simOut.lineSequence().firstOrNull()?.take(140)
                ?: "no output")
        )

        // (c) Carrier configuration, which is what decides whether the VoWiFi setting is even
        //     offered to the user. `cmd phone cc get-value` was refused for the shell UID on the
        //     handset this was written against, despite that UID holding
        //     READ_PRIVILEGED_PHONE_STATE -- a vendor restriction on top of AOSP. Recorded as
        //     measured rather than assumed, because a build that allows it will say so here.
        val cc = runCatching {
            ShizukuBridge.exec("cmd phone cc get-value carrier_wfc_ims_available_bool")
        }.getOrNull()
        val ccOut = cc?.out.orEmpty()
        out += Lever(
            "vowifi-carrier-config", 2,
            if (ccOut.contains("Permission denied", true) ||
                ccOut.contains("SecurityException", true)) LeverVerdict.REFUSED
            else if (ccOut.isBlank()) LeverVerdict.ABSENT else LeverVerdict.WORKS,
            "Whether the carrier offers Wi-Fi calling at all. Shell UID says: " +
                (ccOut.lineSequence().firstOrNull()?.take(140) ?: "no output")
        )

        // (d) The one IMS lever the shell does have, and why it is not used. `cmd phone ims
        //     enable|disable` switches IMS wholesale for a slot. There is no VoWiFi-specific
        //     subcommand. IMS is the bearer VoLTE rides on as well, so the only direction that
        //     command could take us is "disable IMS", which removes HD calling to fix nothing.
        //     Available, deliberately not used, and that is the finding.
        out += Lever(
            "ims-toggle", 2, LeverVerdict.WONT_ATTEMPT,
            "`cmd phone ims enable|disable` exists but switches IMS for the whole slot, not the " +
                "Wi-Fi-calling preference. IMS also carries VoLTE, so using it here would trade " +
                "HD calling for nothing. Not attempted."
        )

        _state.value = _state.value.copy(levers = out)
        return out
    }

    // ================================================================ odds and ends

    /** Percent and charging. -1 percent means the platform would not say, which is not "flat". */
    private fun readBattery(ctx: Context): Pair<Int, Boolean> = runCatching {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        pct to (status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL)
    }.getOrDefault(-1 to false)

    /** One-line readback for a panel or a log, with its coverage attached rather than implied. */
    fun summary(s: State = _state.value): String = buildString {
        append(s.cellular.describe).append('\n')
        append(s.wifi.describe).append('\n')
        append("verdict: ${s.verdict.label} — ${s.verdictBasis}\n")
        val a = s.advice
        if (a != null) {
            append("recommending (tier ${a.tier}): ${a.headline}\n")
            append(a.cost)
        } else {
            append("recommending nothing — ${s.suppressedReason ?: "no reason recorded"}")
        }
        if (s.wifiBindFailures > 0) {
            append("\n${s.wifiBindFailures} Wi-Fi bind(s) refused — ours, not the network's, and " +
                "excluded from every rate above")
        }
        append("\nVoWiFi: ${s.vowifi.summary} (observable only; no tier available here can set it)")
    }
}
