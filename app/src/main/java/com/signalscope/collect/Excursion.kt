package com.signalscope.collect

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records what happens while the phone is **off Wi-Fi**, autonomously, for later readback.
 *
 * Everything measured so far was of an *unused* cellular bearer: Wi-Fi held the default route, so
 * nothing rode on cellular and `validated` described the Wi-Fi path. The condition that actually
 * matters — a call or a video on cellular, away from the house — has never been captured.
 *
 * It cannot be captured interactively either, because leaving Wi-Fi ends the debugging session
 * that would be watching. So the app has to do it alone: detect the transition, escalate, record,
 * and have a summary waiting when Wi-Fi returns.
 *
 * The two transport transitions are recorded deliberately at high resolution. Wi-Fi→cellular and
 * cellular→Wi-Fi are the socket-killers behind "Teams reconnected", and both are invisible from a
 * Wi-Fi-tethered session by construction.
 */
data class Excursion(
    val startWall: Long,
    val endWall: Long?,
    val durationMs: Long,
    val ipChanges: Int,
    val validatedLosses: Int,
    val cellChanges: Int,
    val distinctCells: Int,
    val bands: String,
    val probes: Int,
    val probeFailures: Int,
    val probeP50: Int?,
    val probeP90: Int?,
    val sinrP10: Int?,
    val sinrP50: Int?,
    val worstGapMs: Long,
    /** Radio ticks that yielded a fresh reading, and ticks total. Coverage, not a nicety. */
    val freshTicks: Int = 0,
    val totalTicks: Int = 0
) {
    /** Fraction of the session in which the radio stream was actually alive. */
    val coverage: Double get() = if (totalTicks == 0) 0.0 else freshTicks / totalTicks.toDouble()
}

object ExcursionRecorder {

    val current = MutableStateFlow<Excursion?>(null)
    val sessions = MutableStateFlow<List<Excursion>>(emptyList())

    private const val PREFS = "excursions"
    private const val KEY = "list"

    fun start(ctx: Context, scope: CoroutineScope) {
        load(ctx)
        scope.launch { watch(ctx) }
    }

    private suspend fun watch(ctx: Context) {
        var onCellular = false
        var t0 = 0L
        var ipChanges = 0; var valLosses = 0; var cellChanges = 0
        var lastIp: String? = null; var lastVal = true; var lastCi: Long? = null
        val cells = mutableSetOf<Long>(); val bands = mutableSetOf<String>()
        val sinr = mutableListOf<Int>()
        var gapStart = 0L; var worstGap = 0L
        var probeBase = 0
        // Freshness bookkeeping. The radio fields are a snapshot, not a stream: re-reading an
        // unchanged one every 2 s inflates the SINR distribution with duplicates and turns a
        // frozen serving cell into an apparently rock-steady one. Count only new readings, and
        // keep the coverage ratio so a quiet session can be told apart from a blind one.
        var lastSignalMillis = 0L
        var freshTicks = 0; var totalTicks = 0

        while (true) {
            val net = LiveState.net.value
            val sim = LiveState.sims.value.values.firstOrNull { it.isDataSub }
            val cellularIsDefault = net.transport == "CELLULAR"

            if (cellularIsDefault && !onCellular) {
                // Left Wi-Fi. Everything from here is the condition we actually care about.
                onCellular = true; t0 = SystemClock.elapsedRealtime()
                ipChanges = 0; valLosses = 0; cellChanges = 0
                cells.clear(); bands.clear(); sinr.clear()
                lastIp = net.v4 ?: net.v6; lastVal = net.validated; lastCi = sim?.ci
                worstGap = 0; gapStart = 0
                lastSignalMillis = 0L; freshTicks = 0; totalTicks = 0
                probeBase = runCatching {
                    com.signalscope.store.Db.get(ctx).dao().probeCount()
                }.getOrDefault(0)
            }

            if (onCellular) {
                val ip = net.v4 ?: net.v6
                if (ip != null && lastIp != null && ip != lastIp) ipChanges++
                if (ip != null) lastIp = ip

                if (lastVal && !net.validated) { valLosses++; gapStart = SystemClock.elapsedRealtime() }
                if (!lastVal && net.validated && gapStart > 0) {
                    worstGap = maxOf(worstGap, SystemClock.elapsedRealtime() - gapStart); gapStart = 0
                }
                lastVal = net.validated

                totalTicks++
                if (sim != null && !sim.signalStale() && sim.signalMillis != lastSignalMillis) {
                    lastSignalMillis = sim.signalMillis
                    freshTicks++
                    sim.ci?.let { c ->
                        cells += c
                        if (lastCi != null && c != lastCi) cellChanges++
                        lastCi = c
                    }
                    // "B40" for LTE, "n40" for NR: the bare number is the same for both and meant different radios.
                    com.signalscope.store.Bands.label(sim.cellRat, sim.band)?.let { bands += it }
                    sim.rssnr?.let { sinr += it }
                }
            }

            if (!cellularIsDefault && onCellular) {
                // Back on Wi-Fi. Close the session and leave the summary for readback.
                onCellular = false
                val dur = SystemClock.elapsedRealtime() - t0
                if (dur > 30_000) {   // ignore momentary flaps
                    val dao = runCatching { com.signalscope.store.Db.get(ctx).dao() }.getOrNull()
                    val probes = runCatching { dao?.probesSince(probeBase) }.getOrNull().orEmpty()
                    // Connect-latency probes only. The asymmetry probe writes UP8K/DOWN8K rows
                    // whose latency is the time to move 8 KB, hundreds of ms against a TLS
                    // connect's ~150 -- roughly 6 to 10 of ~70 rows in a 25-minute excursion,
                    // which is enough to move p90 and make it describe nothing in particular.
                    val lat = probes
                        .filter { it.outcome == "OK" && CellProbe.isReachability(it.probeType, it.errorCode) }
                        .map { it.latencyMs }.sorted()
                    fun p(v: List<Int>, q: Double) =
                        if (v.isEmpty()) null else v[minOf(v.size - 1, (v.size * q).toInt())]
                    val s = sinr.sorted()
                    val e = Excursion(
                        startWall = System.currentTimeMillis() - dur,
                        endWall = System.currentTimeMillis(),
                        durationMs = dur,
                        ipChanges = ipChanges, validatedLosses = valLosses,
                        cellChanges = cellChanges, distinctCells = cells.size,
                        bands = com.signalscope.store.Bands.sortLabels(bands).joinToString(","),
                        probes = probes.size,
                        // Instrument faults excluded: a bind the app could not make is not a
                        // failure of the bearer, and counting it is what made three excursions
                        // report a 100 % failure rate on a network that was working.
                        probeFailures = probes.count {
                            it.outcome != "OK" &&
                                !CellProbe.excludedFromRates(it.probeType, it.errorCode)
                        },
                        probeP50 = p(lat, 0.5), probeP90 = p(lat, 0.9),
                        sinrP10 = p(s, 0.10), sinrP50 = p(s, 0.50),
                        worstGapMs = worstGap,
                        freshTicks = freshTicks, totalTicks = totalTicks
                    )
                    sessions.value = (sessions.value + e).takeLast(50)
                    save(ctx)
                }
            }

            current.value = if (onCellular) Excursion(
                startWall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - t0),
                endWall = null, durationMs = SystemClock.elapsedRealtime() - t0,
                ipChanges = ipChanges, validatedLosses = valLosses, cellChanges = cellChanges,
                distinctCells = cells.size, bands = com.signalscope.store.Bands.sortLabels(bands).joinToString(","),
                probes = 0, probeFailures = 0, probeP50 = null, probeP90 = null,
                sinrP10 = null, sinrP50 = sinr.sorted().getOrNull(sinr.size / 2),
                worstGapMs = worstGap,
                freshTicks = freshTicks, totalTicks = totalTicks
            ) else null

            delay(2_000)
        }
    }

    private fun save(ctx: Context) {
        val a = JSONArray()
        sessions.value.forEach {
            a.put(JSONObject().apply {
                put("start", it.startWall); put("dur", it.durationMs)
                put("ipChanges", it.ipChanges); put("valLosses", it.validatedLosses)
                put("cellChanges", it.cellChanges); put("cells", it.distinctCells)
                put("bands", it.bands); put("probes", it.probes); put("fails", it.probeFailures)
                put("p50", it.probeP50 ?: JSONObject.NULL); put("p90", it.probeP90 ?: JSONObject.NULL)
                put("sinrP10", it.sinrP10 ?: JSONObject.NULL)
                put("sinrP50", it.sinrP50 ?: JSONObject.NULL)
                put("worstGapMs", it.worstGapMs)
                put("fresh", it.freshTicks); put("ticks", it.totalTicks)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    private fun load(ctx: Context) {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching {
            val a = JSONArray(raw)
            sessions.value = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Excursion(
                    o.optLong("start"), null, o.optLong("dur"),
                    o.optInt("ipChanges"), o.optInt("valLosses"), o.optInt("cellChanges"),
                    o.optInt("cells"), o.optString("bands"), o.optInt("probes"), o.optInt("fails"),
                    if (o.isNull("p50")) null else o.optInt("p50"),
                    if (o.isNull("p90")) null else o.optInt("p90"),
                    if (o.isNull("sinrP10")) null else o.optInt("sinrP10"),
                    if (o.isNull("sinrP50")) null else o.optInt("sinrP50"),
                    o.optLong("worstGapMs"),
                    o.optInt("fresh"), o.optInt("ticks")
                )
            }
        }
    }
}
