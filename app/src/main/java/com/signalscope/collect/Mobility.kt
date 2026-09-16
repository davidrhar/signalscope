package com.signalscope.collect

import android.content.Context
import android.os.SystemClock
import com.signalscope.store.Db
import com.signalscope.store.MapDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * One completed journey, and what the radio did during it.
 *
 * A journey is the condition the project has never captured. Across everything collected the
 * phone has recorded **one** tracking area per subscription and a peak of 16 cell changes in a
 * minute, walking; the owner's complaint — WhatsApp and Teams calls that will not connect, or
 * connect and drop repeatedly — happens on a train, which no dataset here contains.
 *
 * ## Why the handover counts are named the way they are
 *
 * The finding behind the naming is that a *dormant* bearer pays to be woken where a bearer in use
 * does not. A call sends data continuously, so dormancy cannot be the train trigger: it
 * has to be a different way into the same fragile step, and the working hypothesis is a handover
 * that loses its race — measure the new cell, report it, receive the command, switch, all before
 * the old cell fades. Lose that and the connection is rebuilt from nothing.
 *
 * No public API reports a handover failure. `ServiceState` says what the registration *is*, not
 * what the modem attempted, and the RRC layer is invisible without privilege we do not have at
 * any tier tested. So these three counts are **inferences from correlation**, not readings, and
 * the field names say so:
 *
 *  - [suspectedFailedHandovers] — a serving-cell change with a service-state drop, a
 *    validated-route loss, or a probe failure beside it.
 *  - [apparentlyCleanHandovers] — a serving-cell change with none of those. All 54 cell changes
 *    in the 2026-09-12 excursion were of this kind and broke nothing, which is exactly why the
 *    instrument needed a way to tell the two apart.
 *  - [pingPongReturns] — the cell came back within [Mobility.PING_PONG_MS]. A third thing, and
 *    normal-ish while stationary; on a train cells do not come back.
 *
 * Any report built on these must carry the same caveat as [Mobility.INFERENCE]. They are
 * evidence, and the per-evidence breakdown is kept so a later analysis can see which signal did
 * the work rather than trusting a verdict.
 *
 * ## Coverage
 *
 * [coverage] is not a nicety. A frozen radio snapshot re-read on a timer reports zero variance
 * and zero cell changes, which is indistinguishable from a stable network — and that mistake
 * already destroyed a whole experiment in this project on 2026-09-12, when the screen went off
 * and 600 ticks read the same −91 dBm. Freshness is accounted exactly as [Excursion] does it:
 * each reading is consumed once, keyed on `SimState.signalMillis`, and the ratio travels with
 * the record so a quiet journey can be told from a blind one.
 */
data class Journey(
    val startWall: Long,
    val endWall: Long?,
    val durationMs: Long,
    /** Fastest class reached, and the best evidence any classification during it rested on. */
    val peakClass: String,
    val bestConfidence: String,
    val distinctCells: Int,
    val cellChanges: Int,
    /** Inferred, not measured. See the class comment. */
    val suspectedFailedHandovers: Int,
    val apparentlyCleanHandovers: Int,
    val pingPongReturns: Int,
    /** Which evidence each suspected failure rested on; a change can carry more than one. */
    val failedWithServiceDrop: Int,
    val failedWithValidationLoss: Int,
    val failedWithProbeFailure: Int,
    val tacChanges: Int,
    val bands: String,
    val probes: Int,
    val probeFailures: Int,
    val sinrP10: Int?,
    val sinrP50: Int?,
    val sinrP90: Int?,
    /** Only if location happened to be running; null is the normal case, not a fault. */
    val maxSpeedMps: Float?,
    /** Ticks that yielded a reading nobody had counted yet, and ticks total. */
    val freshTicks: Int = 0,
    val totalTicks: Int = 0
) {
    /** Fraction of ticks that saw a genuinely new reading. Low means the journey is fiction. */
    val coverage: Double get() = if (totalTicks == 0) 0.0 else freshTicks / totalTicks.toDouble()

    /** Per-minute rates are only worth quoting where coverage held up. */
    val cellChangesPerMin: Double
        get() = if (durationMs <= 0) 0.0 else cellChanges / (durationMs / 60_000.0)
}

/**
 * How fast the phone is moving, from whatever evidence exists, and what happened to the radio
 * while it moved.
 *
 * Three speed sources, in order of how much they are worth. All of them are optional, because
 * every one of them can be absent on a normal handset:
 *
 *  1. **A speed fix** — `Location.getSpeed()`, reaching us through the rows
 *     [MapLocationCollector] already writes. Only available while that collector is running,
 *     which [CollectorService]'s power gate decides; it is off on Wi-Fi at home by design.
 *  2. **A tracking-area change** — real geographic travel, and unambiguous on this dataset:
 *     across all collection to date the phone has logged exactly one TAC per subscription, so a
 *     second one means the phone left a tracking area, which is kilometres.
 *  3. **Cell churn** — changes and distinct cells per minute. Needs no location at all, works
 *     with `READ_PHONE_STATE` alone, and is therefore the fallback that actually matters, since
 *     location is gated and frequently off.
 *
 * The source and its confidence travel with the classification because they are not
 * interchangeable. A class derived from churn alone is a guess with a number attached: the
 * highest churn ever recorded here, 16 changes in a minute, was *walking*. Anything consuming
 * [State] must be willing to act differently on [Confidence.WEAK] than on [Confidence.MEASURED],
 * and nothing may treat [Cls.UNKNOWN] as [Cls.STATIONARY] — absent data is not good data.
 */
object Mobility {

    /** What every consumer of the handover counts has to repeat. */
    const val INFERENCE =
        "Handover classes are inferred from correlation between a serving-cell change and a " +
        "service drop, a validated-route loss or a probe failure. No public API reports a " +
        "handover failure; these are not measurements."

    enum class Cls { UNKNOWN, STATIONARY, WALKING, VEHICLE, FAST }

    enum class Source { NONE, CELL_CHURN, TAC_CHANGE, SPEED_FIX }

    /**
     * MEASURED: a fresh speed fix said so.
     * INDICATIVE: a tracking area changed — certainly travel, speed unknown.
     * WEAK: cell churn only — consistent with travel, and also with a bad corner of one room.
     * NONE: nothing fresh enough to classify with. The class is UNKNOWN and must stay that way.
     */
    enum class Confidence { NONE, WEAK, INDICATIVE, MEASURED }

    data class State(
        val running: Boolean = false,
        val cls: Cls = Cls.UNKNOWN,
        val source: Source = Source.NONE,
        val confidence: Confidence = Confidence.NONE,
        val speedMps: Float? = null,
        val speedFixAgeMs: Long? = null,
        val cellChangesPerMin: Double = 0.0,
        val distinctCellsPerMin: Double = 0.0,
        /** Tracking-area changes seen since [start], across every discovered subscription. */
        val tacChanges: Int = 0,
        val msSinceTacChange: Long? = null,
        /** How much observation the rates above rest on. */
        val windowSpanMs: Long = 0,
        val windowFreshTicks: Int = 0,
        val windowTotalTicks: Int = 0,
        val note: String? = null
    ) {
        /** The journey trigger, and what [TelephonyCollector] raises its cadence on. */
        val aboveWalking: Boolean get() = cls == Cls.VEHICLE || cls == Cls.FAST

        /** Fraction of recent ticks that carried a new reading. 0 means blind, not calm. */
        val windowCoverage: Double
            get() = if (windowTotalTicks == 0) 0.0 else
                windowFreshTicks / windowTotalTicks.toDouble()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** The journey being recorded, if any, and the last [KEEP] completed ones. */
    val current = MutableStateFlow<Journey?>(null)
    val journeys = MutableStateFlow<List<Journey>>(emptyList())

    private const val PREFS = "journeys"
    private const val KEY = "list"
    /** The journey in progress, rewritten periodically so a process kill does not erase it. */
    private const val KEY_OPEN = "open"
    private const val KEEP = 40
    /** Cheap -- one small string to SharedPreferences -- so often enough to lose little. */
    private const val CHECKPOINT_MS = 60_000L

    // ------------------------------------------------------------------------------------------
    //  Thresholds. Every number here has a source; where the source is an assumption it says so.
    // ------------------------------------------------------------------------------------------

    /**
     * 2 s, matching [ExcursionRecorder] so the two streams line up. Not arbitrary: the handover
     * evidence window is a few seconds wide, so a slower tick would stop being able to say
     * whether a service drop sat beside a cell change or a minute away from it. It is a `delay`
     * in a coroutine and takes no wakelock, so on a dozing device it simply runs less often —
     * which is visible in the coverage figure rather than silently distorting a rate.
     */
    private const val TICK_MS = 2_000L

    /** Rolling window for churn rates. Long enough for 0.4 changes/min to mean something. */
    private const val WINDOW_MS = 180_000L

    /** Below this much observation, churn rates are noise and the class stays UNKNOWN. */
    private const val MIN_WINDOW_MS = 60_000L

    /** A window in which most ticks read a stale snapshot cannot classify anything. */
    private const val MIN_WINDOW_COVERAGE = 0.30

    // Speed thresholds, m/s. Boundaries are chosen on dwell time in a cell, because that is what
    // the handover race is about, not on how the speed feels.
    /**
     * 0.5 m/s (1.8 km/h). The platform fused provider reports a few tenths of a m/s of noise on a
     * phone lying still, and no real gait is slower than this.
     */
    private const val SPEED_STATIONARY_MAX = 0.5f
    /**
     * 2.5 m/s (9 km/h). Average walking is ~1.4 m/s and brisk walking ~2.0; running starts above
     * 3. The bound is set past brisk so a hurried walk is not filed as a vehicle.
     */
    private const val SPEED_WALKING_MAX = 2.5f
    /**
     * 22 m/s (79 km/h, ~49 mph). An LTE macro cell is roughly 1–3 km across, so at 22 m/s it is
     * crossed in 45–135 s and handovers stop being occasional events. Below this — city traffic,
     * a bus, a bicycle — a cell lasts minutes and the measure-report-command-switch sequence has
     * time it does not have on a train. This is the boundary the whole task is about, so it is
     * deliberately the least arbitrary of the four.
     */
    private const val SPEED_VEHICLE_MAX = 22f

    /**
     * A speed fix older than this is not evidence of current speed. [MapLocationCollector]
     * requests updates every 20 s and writes a row on a bin change or once a minute, whichever
     * comes first, so a genuinely current fix can still be ~60 s old by the time we read it.
     * 120 s allows for that plus write lag and no more.
     */
    private const val SPEED_FIX_MAX_AGE_MS = 120_000L

    // Churn thresholds, per minute over [WINDOW_MS]. Sources: 0.4 changes/min measured parked at
    // home; 2.1/min measured across the 25-minute 2026-09-12 excursion (walking, 8 distinct cells
    // over two sites = 0.32 distinct/min); 16/min the highest ever recorded, also walking.
    /** Above the parked baseline of ~0.4/min with headroom for one noisy sector flip. */
    private const val CHANGES_PER_MIN_MOVING = 1.0
    /**
     * Distinct cells per minute, not changes per minute, is what separates travel from ping-pong:
     * flapping between two sectors of one mast produces a high change rate and a distinct-cell
     * rate bounded by the number of sectors on it. The walking excursion managed 0.32 distinct
     * cells/min, so 1.0/min is above anything this phone has recorded while not in a vehicle.
     */
    private const val DISTINCT_PER_MIN_VEHICLE = 1.0
    /**
     * 3 distinct cells/min is ~20 s of dwell each. Claimed as FAST, but only ever at
     * [Confidence.WEAK]: churn cannot see speed, and 16 changes/min has been recorded walking.
     * A speed fix or a TAC change is what upgrades this.
     */
    private const val DISTINCT_PER_MIN_FAST = 3.0

    /**
     * How long a tracking-area change keeps counting as evidence of travel. A TAC is tens of km
     * across on a typical LTE deployment, so crossing one is not a thing that happens twice a
     * minute; 5 minutes is a conservative guess at how long the movement it implies lasts, and it
     * is an assumption rather than a measurement because this dataset contains no TAC change at
     * all to calibrate against.
     */
    private const val TAC_MOVING_HOLD_MS = 300_000L

    /** A TAC read off a cell-identity snapshot older than this is not current. */
    private const val TAC_MAX_AGE_MS = 90_000L

    // Handover inference windows.
    /**
     * How long after a serving-cell change a failure signal still counts as being "beside" it.
     * 6 s because that is the size of the failure this project already measured: the cold-wake
     * failures were 6-second stalls, and [CellProbe]'s own timeout is 6 s. A drop that lands
     * inside that window is on the same event; one 30 s later is its own story.
     */
    private const val EVIDENCE_MS = 6_000L

    /**
     * Evidence is also accepted from just *before* the change, because the order is not fixed: a
     * connection can collapse and the new serving cell appear in the snapshot afterwards, which
     * at a 2 s tick is easily two ticks late.
     */
    private const val EVIDENCE_LOOKBACK_MS = 4_000L

    /**
     * Return to the previous cell inside this and it is ping-pong rather than progress. 30 s is
     * the reselection flapping this device already shows across sectors of one mast; a train does
     * not revisit a cell it has left. Also the delay before any change can be classified, since
     * the return has to be given time to happen.
     */
    const val PING_PONG_MS = 30_000L

    /** Probe rows are read in batches; asking every tick would be a query every 2 s. */
    private const val PROBE_POLL_MS = 10_000L

    /** Location is polled no faster than it is produced. */
    private const val FIX_POLL_MS = 15_000L

    /** Above walking starts a journey; this much continuous *not* above walking ends it. */
    private const val JOURNEY_END_QUIET_MS = 180_000L

    /**
     * A journey has to contain at least this much above-walking span to be kept. One churn burst
     * in a lift shaft is not a train, and a record of it would pollute the very comparison the
     * journey table exists to make.
     */
    private const val JOURNEY_MIN_ACTIVE_MS = 60_000L

    // ------------------------------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------------------------------

    private var job: Job? = null

    /**
     * Application context, kept so [stop] can write out a journey that is still open. Held as a
     * field rather than passed because the service calls `stop()` from `onDestroy()` with nothing
     * to hand us, and a journey that ends because the service was killed is still a journey.
     */
    @Volatile private var appCtx: Context? = null

    fun start(ctx: Context, scope: CoroutineScope) {
        if (job != null) return
        appCtx = ctx.applicationContext
        runCatching { load(ctx) }
        job = scope.launch {
            _state.value = _state.value.copy(running = true)
            // One tick throwing must not end mobility detection for the rest of the process.
            // Everything below touches the platform indirectly and Room directly.
            while (isActive) {
                runCatching { tick(ctx) }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        appCtx?.let { c -> runCatching { if (journeyStartElapsed != 0L) closeJourney(c, SystemClock.elapsedRealtime()) } }
        _state.value = _state.value.copy(running = false)
    }

    // ------------------------------------------------------------------------------------------
    //  Rolling state
    // ------------------------------------------------------------------------------------------

    /** (elapsed, ci) for each *fresh* reading in the window. */
    private val cellWindow = ArrayDeque<Pair<Long, Long>>()

    /** (elapsed, wasFresh) per tick in the window. Coverage, and the guard on the rates. */
    private val tickWindow = ArrayDeque<Pair<Long, Boolean>>()

    private var startedElapsed = 0L
    private var lastSignalMillis = 0L
    private var lastCi: Long? = null

    /** Per-subscription last TAC. Discovered at runtime; nothing about any SIM is assumed. */
    private val lastTac = mutableMapOf<Int, Int>()
    private var tacChanges = 0
    private var lastTacChangeElapsed = 0L

    private var lastServiceDropElapsed = 0L
    private var lastValidationLossElapsed = 0L

    private var lastProbeId = -1
    private var lastProbePollElapsed = 0L
    private var lastFixPollElapsed = 0L
    private var lastFixSpeed: Float? = null
    private var lastFixWall = 0L

    /**
     * A serving-cell change waiting to be classified. Held for [PING_PONG_MS] so the return has
     * a chance to arrive, with failure evidence accumulated over the first [EVIDENCE_MS].
     */
    private class CellChange(
        val atElapsed: Long,
        val atWall: Long,
        val fromCi: Long,
        val toCi: Long,
        var serviceDrop: Boolean = false,
        var validationLoss: Boolean = false,
        var probeFailure: Boolean = false,
        var returned: Boolean = false
    )

    private val pending = mutableListOf<CellChange>()

    // Journey accumulators. Reset on journey start, not on process start.
    private var journeyStartElapsed = 0L
    private var journeyStartWall = 0L
    private var lastAboveElapsed = 0L
    private var lastCheckpointElapsed = 0L
    private var jTicks = 0
    private var jFresh = 0
    private var jCellChanges = 0
    private val jCells = mutableSetOf<Long>()
    private val jBands = mutableSetOf<String>()
    private val jSinr = mutableListOf<Int>()
    private var jFailed = 0
    private var jClean = 0
    private var jPingPong = 0
    private var jFailServiceDrop = 0
    private var jFailValidation = 0
    private var jFailProbe = 0
    private var jTacBase = 0
    private var jProbes = 0
    private var jProbeFailures = 0
    private var jMaxSpeed: Float? = null
    private var jPeak = Cls.UNKNOWN
    private var jBestConfidence = Confidence.NONE

    // ------------------------------------------------------------------------------------------
    //  The tick
    // ------------------------------------------------------------------------------------------

    private suspend fun tick(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        if (startedElapsed == 0L) startedElapsed = now

        // The data subscription is the one carrying the call, so it is the one whose churn is
        // comparable with the 2.1 changes/min the excursion recorded. Falling back to the first
        // discovered subscription matters for real devices: on a single-SIM handset, or before
        // the default-data sub is known, `isDataSub` is false everywhere and a strict filter
        // would make this collector silently blind.
        val sims = LiveState.sims.value
        val sim = sims.values.firstOrNull { it.isDataSub } ?: sims.values.firstOrNull()
        val net = LiveState.net.value

        // ---- service-state and route evidence, sampled every tick -----------------------------
        // Recorded as timestamps rather than tested at classification time so a drop that lands
        // between two cell readings is not lost.
        //
        // These two can be trusted where signal strength cannot. The 2026-09-12 run established
        // that the platform stops pushing signal strength to a background app when the screen
        // goes off, while the service-state callbacks kept firing throughout -- so a drop is
        // event-driven and current, and does not need the freshness guard the radio fields do.
        // "—" is the initial value and means nobody has told us yet: it is not a drop, and is
        // deliberately not counted as one.
        if (sim != null && sim.serviceState != "IN_SERVICE" && sim.serviceState != "—") {
            lastServiceDropElapsed = now
        }
        // Only while cellular holds the default route: on Wi-Fi, `validated` describes the Wi-Fi
        // path and says nothing at all about the cellular bearer, which is the mistake every
        // measurement in this project made before the excursion.
        if (net.transport == "CELLULAR" && !net.validated) lastValidationLossElapsed = now

        // ---- freshness, exactly as Excursion accounts for it ----------------------------------
        // Consume each reading once, keyed on signalMillis. Re-reading an unchanged snapshot
        // every 2 s would report a frozen radio as a rock-steady network.
        val fresh = sim != null && !sim.signalStale() && sim.signalMillis != lastSignalMillis
        tickWindow.addLast(now to fresh)
        jTicks++

        if (fresh) {
            lastSignalMillis = sim.signalMillis
            jFresh++
            // "B40" for LTE, "n40" for NR: the bare number is the same for both and meant different radios.
            com.signalscope.store.Bands.label(sim.cellRat, sim.band)?.let { jBands += it }
            sim.rssnr?.let { jSinr += it }
            sim.ci?.let { ci ->
                cellWindow.addLast(now to ci)
                jCells += ci
                val prev = lastCi
                if (prev != null && ci != prev) {
                    jCellChanges++
                    onCellChange(now, prev, ci)
                }
                lastCi = ci
            }
        }

        // ---- tracking area, across every subscription -----------------------------------------
        // A TAC is only read from a cell-identity snapshot that is itself current; a stale one
        // would report the same area forever and never contradict anything.
        val nowWall = System.currentTimeMillis()
        sims.forEach { (subId, s) ->
            val tac = s.tac ?: return@forEach
            if (s.cellMillis == 0L || nowWall - s.cellMillis > TAC_MAX_AGE_MS) return@forEach
            val prev = lastTac[subId]
            lastTac[subId] = tac
            if (prev != null && prev != tac) {
                tacChanges++
                lastTacChangeElapsed = now
            }
        }

        // ---- probe outcomes, batched -----------------------------------------------------------
        if (now - lastProbePollElapsed >= PROBE_POLL_MS) {
            lastProbePollElapsed = now
            pollProbes(ctx)
        }

        // ---- speed fix, if location happens to be running --------------------------------------
        if (now - lastFixPollElapsed >= FIX_POLL_MS) {
            lastFixPollElapsed = now
            pollFix(ctx)
        }

        trimWindow(now)
        resolvePending(now)

        val st = classify(now)
        _state.value = st

        if (st.confidence > jBestConfidence) jBestConfidence = st.confidence
        if (st.cls.ordinal > jPeak.ordinal) jPeak = st.cls

        journeyBookkeeping(ctx, now, st)
    }

    private fun onCellChange(now: Long, fromCi: Long, toCi: Long) {
        val ev = CellChange(now, System.currentTimeMillis(), fromCi, toCi)
        // Look back as well as forward: the collapse can precede the new cell in the snapshot.
        if (now - lastServiceDropElapsed <= EVIDENCE_LOOKBACK_MS && lastServiceDropElapsed > 0) {
            ev.serviceDrop = true
        }
        if (now - lastValidationLossElapsed <= EVIDENCE_LOOKBACK_MS && lastValidationLossElapsed > 0) {
            ev.validationLoss = true
        }
        pending += ev

        // A return to the cell we just left, inside the window, is ping-pong rather than a pair
        // of independent handovers.
        pending.forEach { p ->
            if (p !== ev && p.fromCi == toCi && now - p.atElapsed <= PING_PONG_MS) p.returned = true
        }
    }

    /** Evidence accrues for [EVIDENCE_MS]; classification waits for the ping-pong window. */
    private fun resolvePending(now: Long) {
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val age = now - p.atElapsed
            if (age <= EVIDENCE_MS) {
                if (lastServiceDropElapsed >= p.atElapsed) p.serviceDrop = true
                if (lastValidationLossElapsed >= p.atElapsed) p.validationLoss = true
            }
            if (age < PING_PONG_MS) continue

            it.remove()
            when {
                p.returned -> jPingPong++
                p.serviceDrop || p.validationLoss || p.probeFailure -> {
                    jFailed++
                    if (p.serviceDrop) jFailServiceDrop++
                    if (p.validationLoss) jFailValidation++
                    if (p.probeFailure) jFailProbe++
                }
                else -> jClean++
            }
        }
    }

    private suspend fun pollProbes(ctx: Context) {
        val dao = runCatching { Db.get(ctx).dao() }.getOrNull() ?: return
        if (lastProbeId < 0) {
            // Seed from the row count rather than reading every historical probe. `probe_result`
            // is not swept by Retention, so the count is its highest id today; if that ever
            // stops being true the only consequence is a batch of old rows read once, and they
            // are attributed by wall time below, not by id, so nothing is miscounted.
            lastProbeId = runCatching { dao.probeCount() }.getOrDefault(0)
            return
        }
        val rows = runCatching { dao.probesSince(lastProbeId) }.getOrNull().orEmpty()
        rows.forEach { r ->
            if (r.id.toInt() > lastProbeId) lastProbeId = r.id.toInt()
            // A probe the app could not even bind to the cellular network says nothing about the
            // network, and counting it did real damage: on 2026-09-13 a dead network reservation
            // made every bind fail with EPERM for three hours, and every one of those was read
            // here as evidence of a failed handover. All eight "suspected failures" in that
            // night's longest journey came from it. An instrument fault is not a measurement.
            // excludedFromRates also covers stall summaries: a stall is already represented by the
            // failed probe that opened it, and counting its summary too would score one outage as
            // two failed handovers.
            if (CellProbe.excludedFromRates(r.probeType, r.errorCode)) {
                return@forEach
            }
            if (journeyStartElapsed != 0L && r.wallMillis >= journeyStartWall) {
                jProbes++
                if (r.outcome != "OK") jProbeFailures++
            }
            if (r.outcome == "OK") return@forEach
            // Any failed probe, including the 8 KB transfer probes: a transfer that could not
            // complete is as much evidence of a broken bearer as a connect that timed out.
            pending.forEach { p ->
                if (r.wallMillis >= p.atWall - EVIDENCE_LOOKBACK_MS &&
                    r.wallMillis <= p.atWall + EVIDENCE_MS
                ) p.probeFailure = true
            }
        }
    }

    /**
     * Read the speed off the most recent position row, and nothing else off it.
     *
     * `data-model.md` §5 — no raw coordinate is ever persisted, and none is read here either:
     * `binId`, `resolution` and the bin centre are left alone. Only `speedMps` is taken, which
     * describes motion rather than place. Nothing new is written, so this adds no trace.
     */
    private suspend fun pollFix(ctx: Context) {
        if (!MapLocationCollector.state.value.running) {
            lastFixSpeed = null
            return
        }
        val fix = runCatching { MapDb.get(ctx).fixes().latest() }.getOrNull() ?: return
        lastFixSpeed = fix.speedMps
        lastFixWall = fix.wallMillis
        val s = fix.speedMps
        if (s != null && (jMaxSpeed == null || s > jMaxSpeed!!)) jMaxSpeed = s
    }

    private fun trimWindow(now: Long) {
        while (cellWindow.isNotEmpty() && now - cellWindow.first().first > WINDOW_MS) {
            cellWindow.removeFirst()
        }
        while (tickWindow.isNotEmpty() && now - tickWindow.first().first > WINDOW_MS) {
            tickWindow.removeFirst()
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Classification
    // ------------------------------------------------------------------------------------------

    private fun classify(now: Long): State {
        val span = minOf(WINDOW_MS, now - startedElapsed)
        val total = tickWindow.size
        val freshCount = tickWindow.count { it.second }
        val coverage = if (total == 0) 0.0 else freshCount / total.toDouble()

        var changes = 0
        var prev: Long? = null
        val distinct = mutableSetOf<Long>()
        cellWindow.forEach { (_, ci) ->
            distinct += ci
            if (prev != null && ci != prev) changes++
            prev = ci
        }
        val minutes = span / 60_000.0
        val changeRate = if (minutes <= 0) 0.0 else changes / minutes
        val distinctRate = if (minutes <= 0) 0.0 else distinct.size / minutes

        val base = State(
            running = true,
            speedMps = lastFixSpeed,
            speedFixAgeMs = if (lastFixWall == 0L) null else System.currentTimeMillis() - lastFixWall,
            cellChangesPerMin = changeRate,
            distinctCellsPerMin = distinctRate,
            tacChanges = tacChanges,
            msSinceTacChange = if (lastTacChangeElapsed == 0L) null else now - lastTacChangeElapsed,
            windowSpanMs = span,
            windowFreshTicks = freshCount,
            windowTotalTicks = total
        )

        // 1. A fresh speed fix beats everything else, and is the only source that can name a
        //    speed rather than infer that there is one.
        val fixAge = if (lastFixWall == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastFixWall
        val speed = lastFixSpeed
        if (speed != null && fixAge <= SPEED_FIX_MAX_AGE_MS) {
            val cls = when {
                speed < SPEED_STATIONARY_MAX -> Cls.STATIONARY
                speed < SPEED_WALKING_MAX -> Cls.WALKING
                speed < SPEED_VEHICLE_MAX -> Cls.VEHICLE
                else -> Cls.FAST
            }
            return base.copy(
                cls = cls, source = Source.SPEED_FIX, confidence = Confidence.MEASURED,
                note = "speed fix ${"%.1f".format(speed)} m/s, ${fixAge / 1000} s old"
            )
        }

        // 2. A recent tracking-area change is certain travel with an unknown speed. It is
        //    reported as VEHICLE because that is the weakest class consistent with leaving a
        //    tracking area, and never as FAST, which it cannot support.
        val tacAge = if (lastTacChangeElapsed == 0L) Long.MAX_VALUE else now - lastTacChangeElapsed
        if (tacAge <= TAC_MOVING_HOLD_MS) {
            return base.copy(
                cls = Cls.VEHICLE, source = Source.TAC_CHANGE, confidence = Confidence.INDICATIVE,
                note = "tracking area changed ${tacAge / 1000} s ago; speed unknown"
            )
        }

        // 3. Churn only. Guarded twice before it is allowed to say anything: enough elapsed
        //    observation, and enough of it fresh. Without the second guard a screen-off phone
        //    that has stopped delivering readings reports zero churn, which reads as parked.
        if (span < MIN_WINDOW_MS) {
            return base.copy(
                cls = Cls.UNKNOWN, source = Source.NONE, confidence = Confidence.NONE,
                note = "only ${span / 1000} s of observation; no classification yet"
            )
        }
        if (coverage < MIN_WINDOW_COVERAGE) {
            return base.copy(
                cls = Cls.UNKNOWN, source = Source.NONE, confidence = Confidence.NONE,
                note = "blind: ${(coverage * 100).toInt()} % of ticks carried a new reading"
            )
        }

        val cls = when {
            distinctRate >= DISTINCT_PER_MIN_FAST -> Cls.FAST
            distinctRate >= DISTINCT_PER_MIN_VEHICLE -> Cls.VEHICLE
            changeRate >= CHANGES_PER_MIN_MOVING -> Cls.WALKING
            else -> Cls.STATIONARY
        }
        return base.copy(
            cls = cls, source = Source.CELL_CHURN, confidence = Confidence.WEAK,
            note = "cell churn only: ${"%.1f".format(changeRate)} changes/min, " +
                "${"%.1f".format(distinctRate)} distinct/min — consistent with this class, " +
                "not evidence of it (16 changes/min has been recorded walking)"
        )
    }

    // ------------------------------------------------------------------------------------------
    //  Journeys
    // ------------------------------------------------------------------------------------------

    private fun journeyBookkeeping(ctx: Context, now: Long, st: State) {
        if (st.aboveWalking) {
            lastAboveElapsed = now
            if (journeyStartElapsed == 0L) openJourney(now, st)
        }

        if (journeyStartElapsed == 0L) {
            current.value = null
            return
        }

        val open = snapshot(now, endWall = null)
        current.value = open

        // Checkpoint the open journey. A train ride is long enough that the process may well not
        // survive it -- the 6 h dataSync cap, a low-memory kill, a reboot -- and a journey that
        // only exists in memory until it closes is exactly the journey we would lose. The
        // checkpoint is promoted on next start and carries endWall = null to say it was never
        // closed cleanly, rather than pretending to a tidy end time.
        if (now - lastCheckpointElapsed >= CHECKPOINT_MS) {
            lastCheckpointElapsed = now
            runCatching {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_OPEN, toJson(open).toString()).apply()
            }
        }

        if (!st.aboveWalking && now - lastAboveElapsed >= JOURNEY_END_QUIET_MS) {
            closeJourney(ctx, now)
        }
    }

    /** Seeded from [st], because the tick that opens a journey is itself the first evidence. */
    private fun openJourney(now: Long, st: State) {
        journeyStartElapsed = now
        journeyStartWall = System.currentTimeMillis()
        lastAboveElapsed = now
        jTicks = 0; jFresh = 0; jCellChanges = 0
        jCells.clear(); jBands.clear(); jSinr.clear()
        jFailed = 0; jClean = 0; jPingPong = 0
        jFailServiceDrop = 0; jFailValidation = 0; jFailProbe = 0
        jProbes = 0; jProbeFailures = 0
        jMaxSpeed = null
        jTacBase = tacChanges
        jPeak = st.cls
        jBestConfidence = st.confidence
        lastCheckpointElapsed = 0L
    }

    private fun closeJourney(ctx: Context, now: Long) {
        val activeSpan = lastAboveElapsed - journeyStartElapsed
        val j = snapshot(now, endWall = System.currentTimeMillis())
        journeyStartElapsed = 0L
        current.value = null
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_OPEN).apply()
        }

        // One churn burst is not a journey. Note the test is on the above-walking span, not on
        // the total: every journey carries a JOURNEY_END_QUIET_MS tail by construction, so a
        // duration test would pass for a single stray tick.
        if (activeSpan < JOURNEY_MIN_ACTIVE_MS) return

        journeys.value = (journeys.value + j).takeLast(KEEP)
        runCatching { save(ctx) }
    }

    /**
     * The journey as it stands. The counts include the quiet tail that ends a journey, because
     * the ticks in that tail were counted too — duration and counts describe the same window, so
     * any rate derived from them is consistent.
     */
    private fun snapshot(now: Long, endWall: Long?): Journey {
        val s = jSinr.sorted()
        fun p(q: Double) = if (s.isEmpty()) null else s[minOf(s.size - 1, (s.size * q).toInt())]
        val dur = now - journeyStartElapsed
        return Journey(
            startWall = journeyStartWall,
            endWall = endWall,
            durationMs = dur,
            peakClass = jPeak.name,
            bestConfidence = jBestConfidence.name,
            distinctCells = jCells.size,
            cellChanges = jCellChanges,
            suspectedFailedHandovers = jFailed,
            apparentlyCleanHandovers = jClean,
            pingPongReturns = jPingPong,
            failedWithServiceDrop = jFailServiceDrop,
            failedWithValidationLoss = jFailValidation,
            failedWithProbeFailure = jFailProbe,
            tacChanges = tacChanges - jTacBase,
            bands = com.signalscope.store.Bands.sortLabels(jBands).joinToString(","),
            probes = jProbes,
            probeFailures = jProbeFailures,
            sinrP10 = p(0.10), sinrP50 = p(0.50), sinrP90 = p(0.90),
            maxSpeedMps = jMaxSpeed,
            freshTicks = jFresh,
            totalTicks = jTicks
        )
    }

    /**
     * A summary that refuses to overstate what it has. Coverage is checked before anything else,
     * because blindness has to be ruled out before any count means anything: a journey that saw
     * nothing reports no handovers, which looks exactly like a journey that went perfectly.
     */
    fun summary(j: Journey): String = buildString {
        append("%.1f min, %s (%s)\n".format(j.durationMs / 60_000.0, j.peakClass, j.bestConfidence))
        append("%d cells, %d changes (%.1f/min)\n"
            .format(j.distinctCells, j.cellChanges, j.cellChangesPerMin))
        if (j.coverage < 0.30) {
            append("COVERAGE %.0f %% — the radio stream was mostly stale, most likely screen-off. "
                .format(j.coverage * 100))
            append("Handover counts below are not interpretable.\n")
        } else {
            append("coverage %.0f %% of %d ticks\n".format(j.coverage * 100, j.totalTicks))
        }
        append("suspected failed %d · apparently clean %d · ping-pong %d\n"
            .format(j.suspectedFailedHandovers, j.apparentlyCleanHandovers, j.pingPongReturns))
        append("evidence: service drop %d, validation loss %d, probe failure %d\n"
            .format(j.failedWithServiceDrop, j.failedWithValidationLoss, j.failedWithProbeFailure))
        append(INFERENCE)
    }

    // ------------------------------------------------------------------------------------------
    //  Persistence. A journey recorded with nobody watching has to survive to be read later.
    // ------------------------------------------------------------------------------------------

    private fun toJson(j: Journey) = JSONObject().apply {
        put("start", j.startWall)
        put("end", j.endWall ?: JSONObject.NULL)
        put("dur", j.durationMs)
        put("peak", j.peakClass); put("conf", j.bestConfidence)
        put("cells", j.distinctCells); put("changes", j.cellChanges)
        // The three inferred classes. Named "suspected"/"apparently" in storage too, so nothing
        // reading this file back can mistake them for a handover-failure readout.
        put("suspectedFailed", j.suspectedFailedHandovers)
        put("apparentlyClean", j.apparentlyCleanHandovers)
        put("pingPong", j.pingPongReturns)
        put("evServiceDrop", j.failedWithServiceDrop)
        put("evValidationLoss", j.failedWithValidationLoss)
        put("evProbeFailure", j.failedWithProbeFailure)
        put("tacChanges", j.tacChanges)
        put("bands", j.bands)
        put("probes", j.probes); put("probeFails", j.probeFailures)
        put("sinrP10", j.sinrP10 ?: JSONObject.NULL)
        put("sinrP50", j.sinrP50 ?: JSONObject.NULL)
        put("sinrP90", j.sinrP90 ?: JSONObject.NULL)
        put("maxSpeed", j.maxSpeedMps?.toDouble() ?: JSONObject.NULL)
        put("fresh", j.freshTicks); put("ticks", j.totalTicks)
    }

    private fun fromJson(o: JSONObject) = Journey(
        startWall = o.optLong("start"),
        endWall = if (o.isNull("end")) null else o.optLong("end"),
        durationMs = o.optLong("dur"),
        peakClass = o.optString("peak", Cls.UNKNOWN.name),
        bestConfidence = o.optString("conf", Confidence.NONE.name),
        distinctCells = o.optInt("cells"),
        cellChanges = o.optInt("changes"),
        suspectedFailedHandovers = o.optInt("suspectedFailed"),
        apparentlyCleanHandovers = o.optInt("apparentlyClean"),
        pingPongReturns = o.optInt("pingPong"),
        failedWithServiceDrop = o.optInt("evServiceDrop"),
        failedWithValidationLoss = o.optInt("evValidationLoss"),
        failedWithProbeFailure = o.optInt("evProbeFailure"),
        tacChanges = o.optInt("tacChanges"),
        bands = o.optString("bands"),
        probes = o.optInt("probes"),
        probeFailures = o.optInt("probeFails"),
        sinrP10 = if (o.isNull("sinrP10")) null else o.optInt("sinrP10"),
        sinrP50 = if (o.isNull("sinrP50")) null else o.optInt("sinrP50"),
        sinrP90 = if (o.isNull("sinrP90")) null else o.optInt("sinrP90"),
        maxSpeedMps = if (o.isNull("maxSpeed")) null else o.optDouble("maxSpeed").toFloat(),
        freshTicks = o.optInt("fresh"),
        totalTicks = o.optInt("ticks")
    )

    private fun save(ctx: Context) {
        val a = JSONArray()
        journeys.value.forEach { a.put(toJson(it)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, a.toString()).apply()
    }

    private fun load(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { raw ->
            runCatching {
                val a = JSONArray(raw)
                journeys.value = (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
            }
        }
        // Promote a checkpoint left by a journey the process did not outlive. Kept only if it
        // had reached the minimum length, and left with endWall = null so it is legible as an
        // interrupted record rather than a complete one.
        prefs.getString(KEY_OPEN, null)?.let { raw ->
            runCatching {
                val j = fromJson(JSONObject(raw))
                if (j.durationMs >= JOURNEY_MIN_ACTIVE_MS) {
                    journeys.value = (journeys.value + j).takeLast(KEEP)
                    save(ctx)
                }
            }
            prefs.edit().remove(KEY_OPEN).apply()
        }
    }
}
