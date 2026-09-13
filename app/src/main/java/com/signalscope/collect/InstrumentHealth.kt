package com.signalscope.collect

import android.content.Context
import com.signalscope.store.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Does this app currently know what it thinks it knows?
 *
 * ## Why this exists
 *
 * Over two days of development the largest source of wrong answers in this project was not the
 * network. It was the instrument, three times, and each failure produced a confident, plausible,
 * completely false result:
 *
 *  - **Screen-off blindness.** Passive telephony callbacks stop when the screen goes off. An A/B
 *    experiment re-read the same frozen snapshot six hundred times and reported eight consecutive
 *    blocks of "zero cell changes, zero variance" -- which reads exactly like an unusually stable
 *    network, and was an absence of measurement.
 *  - **The unused bearer.** While Wi-Fi held the default route, `validated` described Wi-Fi, so
 *    every cell in a whole phase reported 100 % validated. Nothing was riding on cellular and
 *    there was no cellular outcome to report.
 *  - **The dead reservation.** Binding a socket to the cellular network began failing with EPERM
 *    and kept failing for three hours. Every refused bind was recorded as a failed probe, so the
 *    data claimed a total cellular outage across four journeys and three excursions. The network
 *    was fine.
 *
 * Every one of those was found by accident, and each had been visible on disk for a long time
 * before anyone looked. That is the argument for this file: the checks are cheap, the failures
 * are silent by nature, and noticing by luck is not a strategy.
 *
 * ## The rule it enforces
 *
 * A measurement that cannot be made must never look like a measurement that came back clean.
 * Every check here answers one question -- is this input actually flowing? -- and says so in
 * language a reader can act on, because the fix is usually theirs (unlock the phone, turn off a
 * VPN, grant location) rather than the app's.
 */
object InstrumentHealth {

    enum class Level { OK, DEGRADED, BROKEN, UNKNOWN }

    /**
     * One input, and whether it is arriving.
     *
     * [level] is UNKNOWN when the check does not apply right now rather than when it fails --
     * "location is off because you are sitting still on Wi-Fi" is correct behaviour and must not
     * be coloured as a fault, or the display becomes noise and stops being read.
     */
    data class Check(
        val name: String,
        val level: Level,
        val detail: String,
        /** What the user could do, where there is anything. */
        val action: String? = null
    )

    data class State(
        val checks: List<Check> = emptyList(),
        val worst: Level = Level.UNKNOWN,
        val headline: String = "Not checked yet.",
        val lastRunWall: Long = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null

    /** Often enough to catch a stall inside a minute, cheap enough to ignore: all reads are local. */
    private const val INTERVAL_MS = 30_000L

    /** Long enough for the service to finish onCreate and the first callbacks to arrive. */
    private const val STARTUP_GRACE_MS = 20_000L

    /** A probe cycle is 45 s off Wi-Fi and up to 5 min on it, so this spans several either way. */
    private const val PROBE_WINDOW_MS = 20 * 60_000L

    /** Above this share of refused binds the probe stream is describing us, not the network. */
    private const val INSTRUMENT_SHARE_BAD = 0.25

    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            // Give the collectors a moment before the first judgement. Started last in onCreate, this
            // loop still ran before the service marked itself running, so every start logged
            // "Collecting: BROKEN" for its first half-minute -- which in a multi-day log would read as
            // an outage at every reboot.
            delay(STARTUP_GRACE_MS)
            while (isActive) {
                runCatching { _state.value = evaluate(app) }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun evaluate(ctx: Context): State {
        val checks = ArrayList<Check>(6)

        // ---- the collector itself ------------------------------------------------------------
        val running = LiveState.running.value
        checks += Check(
            "Collecting",
            if (running) Level.OK else Level.BROKEN,
            if (running) "The background service is running." else "The background service is not running.",
            if (running) null else "Open the app once; it restarts the collector."
        )

        // ---- radio readings ------------------------------------------------------------------
        // The screen-off failure in full: a stale snapshot re-read on a timer is not a sample, and
        // every rate derived from row density is wrong while this is true.
        val sim = LiveState.sims.value.values.firstOrNull { it.isDataSub }
            ?: LiveState.sims.value.values.firstOrNull()
        checks += when {
            sim == null -> Check(
                "Signal readings", Level.UNKNOWN,
                "No subscription has reported yet.",
                "If this persists, check that the app has phone permission."
            )
            !sim.signalStale() -> Check(
                "Signal readings", Level.OK,
                "Fresh, updated ${ageWords(sim.signalMillis)} ago."
            )
            else -> Check(
                "Signal readings", Level.DEGRADED,
                "Last reading was ${ageWords(sim.signalMillis)} ago. Readings older than a few " +
                    "seconds are not used for measurement, so anything computed now would be " +
                    "describing a gap rather than the network.",
                "This usually clears by itself; the app polls the radio directly when the push " +
                    "stream goes quiet."
            )
        }

        // ---- service state -------------------------------------------------------------------
        // Event-driven, so silence is normal; only a very long silence is evidence of anything.
        if (sim != null) {
            checks += Check(
                "Network registration",
                if (sim.serviceStale()) Level.DEGRADED else Level.OK,
                if (sim.serviceStale())
                    "No registration update for ${ageWords(sim.serviceMillis)}."
                else "Reported ${ageWords(sim.serviceMillis)} ago."
            )
        }

        // ---- can we measure the cellular bearer at all? ---------------------------------------
        // This is the check that would have caught the three-hour EPERM outage on the first tick.
        val probes = runCatching {
            val since = System.currentTimeMillis() - PROBE_WINDOW_MS
            Db.get(ctx).dao().probesSinceWall(since)
        }.getOrDefault(emptyList())
        val instrumentRows = probes.count {
            CellProbe.kindOf(it.probeType, it.errorCode) == CellProbe.Kind.INSTRUMENT
        }
        val share = if (probes.isEmpty()) 0.0 else instrumentRows.toDouble() / probes.size
        checks += when {
            CellProbe.instrumentBroken || share > INSTRUMENT_SHARE_BAD -> Check(
                "Testing the mobile connection", Level.BROKEN,
                "$instrumentRows of ${probes.size} recent tests could not be started at all -- " +
                    "the app was refused permission to use the mobile connection directly. Those " +
                    "are not network failures and are excluded from every result, but while this " +
                    "lasts the mobile connection is going unmeasured.",
                "It repairs itself. If it persists, reopening the app clears it."
            )
            probes.isEmpty() -> Check(
                "Testing the mobile connection", Level.UNKNOWN,
                "No connection tests in the last ${PROBE_WINDOW_MS / 60_000} minutes.",
                "Tests run more often when you are off Wi-Fi, which is when they matter."
            )
            else -> Check(
                "Testing the mobile connection", Level.OK,
                "${probes.size} tests in the last ${PROBE_WINDOW_MS / 60_000} minutes" +
                    if (instrumentRows > 0) ", $instrumentRows of them not startable." else "."
            )
        }

        // ---- is a tunnel hiding the bearer? ----------------------------------------------------
        // Reported only when resolution actually FAILED. A VPN whose underlying bearer we can see
        // is not a problem, and saying so every time one is running would train the reader to
        // ignore this panel.
        val net = LiveState.net.value
        if (net.transport == "VPN") {
            checks += Check(
                "VPN", Level.DEGRADED,
                "A VPN is active and will not say what connection it is using, so the app cannot " +
                    "tell whether you are on Wi-Fi or mobile. Several measurements only run on " +
                    "mobile, and they will stay switched off until it can.",
                "Turning the VPN off restores full measurement. Nothing is lost while it is on, " +
                    "there is simply less collected."
            )
        } else if (net.vpn) {
            checks += Check(
                "VPN", Level.OK,
                "A VPN is active; the connection underneath it is ${net.transport.lowercase()}, " +
                    "so measurement is unaffected. Latency figures include the tunnel."
            )
        }

        // ---- position ---------------------------------------------------------------------------
        val fix = runCatching { MapLocationCollector.state.value }.getOrNull()
        val wantLocation = net.transport == "CELLULAR" ||
            runCatching { Mobility.state.value.aboveWalking }.getOrDefault(false)
        checks += when {
            fix == null -> Check("Position", Level.UNKNOWN, "Not started.")
            !MapLocationCollector.hasPermission(ctx) -> Check(
                "Position", Level.DEGRADED,
                "Location permission is not granted, so measurements cannot be placed on the map " +
                    "and the map cannot grow.",
                "Grant location access to this app."
            )
            !wantLocation -> Check(
                "Position", Level.UNKNOWN,
                "Off on purpose: you are on Wi-Fi and not moving, so your position is not " +
                    "changing and a fix would cost battery for nothing."
            )
            fix.running -> Check("Position", Level.OK, "${fix.fixCount} fixes recorded.")
            else -> Check(
                "Position", Level.DEGRADED,
                "Position should be recording right now and is not." +
                    (fix.note?.let { " $it" } ?: ""),
                "Check that location is switched on for the phone as well as for the app."
            )
        }

        // BROKEN beats DEGRADED beats OK; UNKNOWN never sets the headline, because a check that
        // does not apply is not a fault and must not colour the whole panel.
        val worst = when {
            checks.any { it.level == Level.BROKEN } -> Level.BROKEN
            checks.any { it.level == Level.DEGRADED } -> Level.DEGRADED
            checks.any { it.level == Level.OK } -> Level.OK
            else -> Level.UNKNOWN
        }
        val headline = when (worst) {
            Level.OK -> "Everything this app measures with is working."
            Level.DEGRADED -> "Measuring, with something missing."
            Level.BROKEN -> "Not measuring properly right now."
            Level.UNKNOWN -> "Nothing to check yet."
        }
        val now = System.currentTimeMillis()
        persistChanges(ctx, checks, now)
        return State(checks, worst, headline, now)
    }

    /** Level last written per check, so only transitions are stored. */
    private val lastWritten = HashMap<String, Level>()

    /**
     * Write a row whenever a check changes level, and every check once when collection starts.
     *
     * This is what lets a multi-day monitoring run be analysed honestly afterwards. The state flow
     * above lives only in memory, so without this a week of data would carry no record of which
     * hours the radio stream was blind, the cellular bearer unbindable, or position paused after a
     * restart -- and those hours would read as measurements. Transitions only, so a healthy week
     * costs a handful of rows rather than one per check every thirty seconds.
     */
    private suspend fun persistChanges(ctx: Context, checks: List<Check>, now: Long) {
        val dao = runCatching { Db.get(ctx).dao() }.getOrNull() ?: return
        for (c in checks) {
            if (lastWritten[c.name] == c.level) continue
            lastWritten[c.name] = c.level
            runCatching {
                dao.insertInstrumentEvent(
                    com.signalscope.store.InstrumentEvent(
                        wallMillis = now, check = c.name, level = c.level.name, detail = c.detail.take(240)
                    )
                )
            }
        }
    }

    private fun ageWords(stampWall: Long): String {
        if (stampWall <= 0L) return "never"
        val ms = System.currentTimeMillis() - stampWall
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h"
        }
    }
}
