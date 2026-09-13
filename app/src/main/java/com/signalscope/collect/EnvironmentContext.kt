package com.signalscope.collect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Is the phone indoors? A heuristic from the sky the GNSS receiver can see, and nothing more.
 *
 * Why this exists: the data SIM's network is mostly LTE Band 40 TDD, and a 2.3 GHz signal loses
 * on the order of 24 dB through coated glass and concrete. The same RSRP or SINR means a healthy
 * outdoor cell edge or a good cell heard through a wall, and until now nothing in the app could
 * tell which. Without that split, "signal is poor here" cannot be separated from "the phone is in
 * a building here", and most use in this city is indoors.
 *
 * ## Method
 *
 * A receiver with open sky sees many satellites at high carrier-to-noise density (C/N0); one
 * under a roof sees fewer, weaker, and uses fewer of them in its fix. Two features are taken from
 * `GnssStatus` over a short window -- the mean C/N0 of the strongest few satellites, and the
 * number used in the fix -- and each is mapped linearly between a "blocked sky" and an "open sky"
 * threshold. The thresholds are named below with their reasoning. **This is a heuristic.** It has
 * not been calibrated against ground truth on this handset, and the output is a score shaped like
 * a probability, clamped away from 0 and 1 so nothing downstream can mistake it for certainty.
 *
 * Known confounders, stated so the number is read with them in mind:
 *  - A phone in a bag or a pocket attenuates like a wall. Outdoors-in-a-bag reads indoor-ish.
 *  - Vehicles: a bus or car roof lowers C/N0 moderately; underground rail removes the sky
 *    entirely and reads as deep indoor, which for radio purposes it is.
 *  - Urban canyons between tall buildings block low-elevation satellites while the phone is
 *    technically outdoors.
 *  - Next to a window the sky is half-visible; expect middling values, which is honest.
 *
 * ## Power -- the hard constraint
 *
 * GNSS is the most expensive radio this app touches (38 mA measured). So this object NEVER asks
 * for position. It listens for satellite status only while [MapLocationCollector] is already
 * running for its own reasons (the service's power gate or the Map tab), and unregisters the
 * moment it stops. Registering a `GnssStatus.Callback` does not itself start the GNSS engine; it
 * only reports on an engine something else has started.
 *
 * A consequence worth knowing: MapLocationCollector asks the fused provider at BALANCED accuracy,
 * which answers from Wi-Fi and cell where it can. It may never turn GNSS on at all. Then no status
 * arrives, and [State.indoorProbability] stays null with a reason -- the correct answer, because
 * no sky was measured. Null here always means "unknown", never "outdoors".
 *
 * ## Deliberately not used
 *
 *  - **Wi-Fi scan result count** (many access points in range suggests a building). It needs
 *    `ACCESS_WIFI_STATE`, which this app does not declare and will not add, so it is omitted.
 *  - **Coastal and underground-rail tagging.** Both were considered -- the coastal one is what
 *    would make the cross-border ducting hypothesis in docs/spectrum.md §4 testable -- and both
 *    are out of scope here, because they need region-specific external data (coastlines, rail
 *    alignments, building footprints) and nothing in this file may encode any geography. The gap
 *    is real and remains open.
 */
object EnvironmentContext {

    // ------------------------------------------------------------------ thresholds (heuristic)

    /**
     * How many of the strongest satellites the C/N0 feature averages. The top few rather than
     * all, because a sky full of weak low-elevation satellites is normal outdoors too; what a
     * roof removes is the strong, high ones. Four is also the minimum for a 3-D fix.
     */
    private const val TOP_N_FOR_CN0 = 4

    /**
     * Mean C/N0 of the strongest [TOP_N_FOR_CN0] at or above which the sky reads as open.
     * Phone-grade antennas typically report low-to-high 40s dB-Hz for clear-sky satellites; 40
     * sits at the bottom of that range, so a clear sky is not penalised for a mediocre antenna.
     */
    private const val CN0_OPEN_SKY_DBHZ = 40.0

    /**
     * Mean top-N C/N0 at or below which the sky reads as blocked. Indoor signals near a window
     * commonly sit around 25-35 dB-Hz and deep indoors fall to the low 20s or vanish; 28 is where
     * tracking is marginal on most phone receivers, which is a physical rather than a tuned line.
     */
    private const val CN0_BLOCKED_DBHZ = 28.0

    /**
     * Satellites used in the fix at or above which the sky reads as open. With several
     * constellations enabled a clear-sky phone commonly uses 15-30; 16 is conservative.
     */
    private const val USED_OPEN_SKY = 16

    /** Used-in-fix at or below which the sky reads as blocked: barely more than a minimal fix. */
    private const val USED_BLOCKED = 5

    /**
     * C/N0 is the more direct measure of attenuation; used-in-fix also depends on the receiver's
     * own selection policy and constellation settings. So signal strength carries more weight.
     */
    private const val WEIGHT_CN0 = 0.65
    private const val WEIGHT_USED = 0.35

    /**
     * Never report certainty from a heuristic. A clamp rather than a rescale, so the middle of
     * the range keeps its meaning.
     */
    private const val P_MIN = 0.05
    private const val P_MAX = 0.95

    /**
     * After the engine starts, a sparse sky is indistinguishable from an engine still acquiring.
     * Cold acquisition on a phone commonly takes tens of seconds, so a zero-satellite sky is not
     * read as blockage until this long after `onStarted`.
     */
    private const val ACQUISITION_GRACE_MS = 30_000L

    /** Status arrives about once a second while the engine runs; ~30 s smooths body blocking. */
    private const val WINDOW_MS = 30_000L

    /** Below this many status reports in the window there is too little to call anything. */
    private const val MIN_SAMPLES = 5

    /** No status for this long means the engine has gone quiet: the reading is no longer current. */
    private const val STALE_AFTER_MS = 60_000L

    /** How often staleness is re-checked. Only runs while the process is awake anyway. */
    private const val TICK_MS = 15_000L

    // ------------------------------------------------------------------ state

    enum class Confidence { NONE, LOW, MEDIUM, HIGH }

    /** One status report, reduced to what the heuristic uses. No satellite identity is kept. */
    data class SkySample(
        val elapsedMs: Long,
        /**
         * Satellites actually tracked (C/N0 > 0), not `satelliteCount`: the latter includes
         * satellites the almanac predicts but the receiver cannot hear, which a roof does not remove.
         */
        val visible: Int,
        val usedInFix: Int,
        /** Strongest C/N0 values in dB-Hz, descending, at most [TOP_N_FOR_CN0]. */
        val topCn0DbHz: List<Double>
    )

    data class State(
        /** 0..1, higher is more likely indoors. Null means unknown -- see [reason]. Never "outdoors". */
        val indoorProbability: Double? = null,
        val confidence: Confidence = Confidence.NONE,
        /** Why the probability is null, or the main caveat on it. */
        val reason: String? = "not started",

        // Inputs, as window medians/means, so a consumer can re-derive or re-threshold offline.
        val satellitesVisible: Int? = null,
        val satellitesUsedInFix: Int? = null,
        /** Mean of the strongest [TOP_N_FOR_CN0] C/N0 values across the window, dB-Hz. */
        val topCn0MeanDbHz: Double? = null,
        /** The most recent report's strongest C/N0 values, descending. */
        val latestTopCn0DbHz: List<Double> = emptyList(),
        val samplesInWindow: Int = 0,

        /** Wall-clock time of the most recent GNSS status report, or null if none yet. */
        val lastStatusAtMillis: Long? = null,
        /** Age of that report when this state was computed. */
        val inputAgeMs: Long? = null,
        val computedAtMillis: Long = System.currentTimeMillis(),

        /** Our status callback is registered (which does not mean the engine is on). */
        val listening: Boolean = false,
        /** The GNSS engine reported started and not since stopped -- by anyone. */
        val engineRunning: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    // ------------------------------------------------------------------ plumbing

    private var job: Job? = null
    private var appCtx: Context? = null
    private var lm: LocationManager? = null
    private var callback: GnssStatus.Callback? = null
    private var executor: ExecutorService? = null

    private val window = ArrayDeque<SkySample>()
    @Volatile private var engineStartedElapsed: Long? = null
    @Volatile private var engineRunning = false
    @Volatile private var lastStatusElapsed: Long? = null

    @Synchronized
    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        appCtx = ctx.applicationContext
        job = scope.launch {
            while (isActive) {
                // Re-asserted on a tick rather than subscribed once, for the same reason the
                // collector's location policy is: the gate's owner can change underneath us, and
                // an idempotent comparison is cheaper than getting a missed transition wrong for
                // a whole multi-day run.
                runCatching { gate() }
                runCatching { recompute() }
                delay(TICK_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        runCatching { unregister("stopped") }
        runCatching { executor?.shutdown() }
        executor = null
    }

    private fun hasFine(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Listen only while position is already being paid for. Never request position ourselves. */
    @Synchronized
    private fun gate() {
        val ctx = appCtx ?: return
        val positionRunning = runCatching { MapLocationCollector.state.value.running }.getOrDefault(false)
        if (!positionRunning) {
            unregister("position is not being requested, so the sky is not being measured")
            return
        }
        if (callback != null) return
        if (!hasFine(ctx)) {
            // GNSS status needs FINE. Coarse-only is a legitimate user choice; say so, don't guess.
            unregister("GNSS status needs precise location, which is not granted")
            return
        }
        register(ctx)
    }

    @SuppressLint("MissingPermission") // checked in gate() immediately before
    private fun register(ctx: Context) {
        val manager = ctx.getSystemService(LocationManager::class.java) ?: run {
            publishUnknown("no LocationManager on this device"); return
        }
        val exec = executor ?: Executors.newSingleThreadExecutor().also { executor = it }
        val cb = object : GnssStatus.Callback() {
            override fun onStarted() {
                runCatching {
                    engineRunning = true
                    engineStartedElapsed = SystemClock.elapsedRealtime()
                }
            }

            override fun onStopped() {
                runCatching {
                    engineRunning = false
                    engineStartedElapsed = null
                    recompute()
                }
            }

            override fun onSatelliteStatusChanged(status: GnssStatus) {
                runCatching { onStatus(status) }
            }
        }
        val ok = runCatching { manager.registerGnssStatusCallback(exec, cb) }
            .getOrElse { false }
        if (!ok) {
            publishUnknown("GNSS status callback was refused by the platform")
            return
        }
        lm = manager
        callback = cb
        _state.value = _state.value.copy(
            listening = true,
            reason = "listening; waiting for the GNSS engine to report (the fused provider may " +
                "be answering from Wi-Fi or cell without it)"
        )
    }

    @Synchronized
    private fun unregister(why: String) {
        callback?.let { cb -> runCatching { lm?.unregisterGnssStatusCallback(cb) } }
        val wasListening = callback != null
        callback = null
        lm = null
        engineRunning = false
        engineStartedElapsed = null
        lastStatusElapsed = null
        synchronized(window) { window.clear() }
        // Only publish on a transition, or when the reason genuinely changed, so the flow does not
        // emit a fresh object every tick while idle.
        if (wasListening || _state.value.reason != why || _state.value.indoorProbability != null) {
            publishUnknown(why)
        }
    }

    private fun onStatus(status: GnssStatus) {
        val now = SystemClock.elapsedRealtime()
        // Some receivers skip onStarted when the callback attaches to an engine already running.
        if (!engineRunning) {
            engineRunning = true
            if (engineStartedElapsed == null) engineStartedElapsed = now
        }
        val n = status.satelliteCount
        var used = 0
        val cn0 = ArrayList<Double>(n)
        for (i in 0 until n) {
            if (status.usedInFix(i)) used++
            val c = status.getCn0DbHz(i).toDouble()
            // 0 is what many receivers report for a satellite that is predicted but not tracked.
            // It is not a measurement of a weak signal, so it must not drag the mean down.
            if (c > 0.0) cn0 += c
        }
        cn0.sortDescending()
        val sample = SkySample(now, cn0.size, used, cn0.take(TOP_N_FOR_CN0))
        synchronized(window) {
            window.addLast(sample)
            while (window.isNotEmpty() && now - window.first().elapsedMs > WINDOW_MS) window.removeFirst()
        }
        lastStatusElapsed = now
        recompute()
    }

    private fun recompute() {
        if (callback == null) return
        val now = SystemClock.elapsedRealtime()
        val last = lastStatusElapsed
        val samples = synchronized(window) {
            while (window.isNotEmpty() && now - window.first().elapsedMs > WINDOW_MS) window.removeFirst()
            window.toList()
        }

        val base = _state.value.copy(
            listening = true,
            engineRunning = engineRunning,
            samplesInWindow = samples.size,
            lastStatusAtMillis = last?.let { System.currentTimeMillis() - (now - it) },
            inputAgeMs = last?.let { now - it },
            latestTopCn0DbHz = samples.lastOrNull()?.topCn0DbHz ?: emptyList(),
            computedAtMillis = System.currentTimeMillis()
        )

        fun unknown(why: String) {
            _state.value = base.copy(
                indoorProbability = null, confidence = Confidence.NONE, reason = why,
                satellitesVisible = null, satellitesUsedInFix = null, topCn0MeanDbHz = null
            )
        }

        if (last == null) {
            unknown(
                if (engineRunning) "GNSS engine running, no satellite status yet"
                else "GNSS engine not running: position is coming from Wi-Fi or cell, no sky measured"
            ); return
        }
        if (now - last > STALE_AFTER_MS) {
            unknown("last satellite status is ${(now - last) / 1000} s old; the engine has gone quiet")
            return
        }
        if (samples.size < MIN_SAMPLES) {
            unknown("only ${samples.size} status report(s) in the last ${WINDOW_MS / 1000} s"); return
        }
        val started = engineStartedElapsed
        val inGrace = started != null && now - started < ACQUISITION_GRACE_MS

        val visible = median(samples.map { it.visible })
        val used = median(samples.map { it.usedInFix })
        val topMeans = samples.filter { it.topCn0DbHz.isNotEmpty() }.map { it.topCn0DbHz.average() }
        val cn0Mean = if (topMeans.isEmpty()) null else topMeans.average()

        if (inGrace && (cn0Mean == null || used <= USED_BLOCKED)) {
            // A thin sky seconds after start is acquisition, not a roof.
            unknown("GNSS engine started ${(now - started!!) / 1000} s ago and is still acquiring")
            return
        }

        // Past the grace period, an engine that tracks nothing is itself strong evidence of
        // blocked sky: the receiver is on and hearing no satellite at all.
        val cn0Indoor = if (cn0Mean == null) 1.0 else scaleIndoor(cn0Mean, CN0_BLOCKED_DBHZ, CN0_OPEN_SKY_DBHZ)
        val usedIndoor = scaleIndoor(used.toDouble(), USED_BLOCKED.toDouble(), USED_OPEN_SKY.toDouble())
        val p = (WEIGHT_CN0 * cn0Indoor + WEIGHT_USED * usedIndoor).coerceIn(P_MIN, P_MAX)

        // Confidence is about the evidence, not the answer: a full window whose two features
        // agree is worth more than a thin one where they pull in opposite directions.
        val agreement = 1.0 - kotlin.math.abs(cn0Indoor - usedIndoor)
        val fullness = (samples.size.toDouble() / (WINDOW_MS / 1000)).coerceAtMost(1.0)
        val confidence = when {
            agreement >= 0.7 && fullness >= 0.6 -> Confidence.HIGH
            agreement >= 0.4 && fullness >= 0.3 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        _state.value = base.copy(
            indoorProbability = p,
            confidence = confidence,
            reason = "heuristic from GNSS sky view, uncalibrated; a bag or pocket reads like a wall",
            satellitesVisible = visible,
            satellitesUsedInFix = used,
            topCn0MeanDbHz = cn0Mean
        )
    }

    private fun publishUnknown(why: String) {
        _state.value = State(
            indoorProbability = null,
            confidence = Confidence.NONE,
            reason = why,
            listening = callback != null,
            engineRunning = false,
            computedAtMillis = System.currentTimeMillis()
        )
    }

    /** 1.0 at or below [blocked], 0.0 at or above [open], linear between. */
    private fun scaleIndoor(v: Double, blocked: Double, open: Double): Double =
        ((open - v) / (open - blocked)).coerceIn(0.0, 1.0)

    private fun median(xs: List<Int>): Int = xs.sorted().let { it[it.size / 2] }
}
