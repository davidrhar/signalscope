package com.signalscope.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalscope.collect.BearerWarmth
import com.signalscope.collect.CarrierFaults
import com.signalscope.collect.Journey
import com.signalscope.collect.Mobility
import com.signalscope.collect.HandoverPredictor
import com.signalscope.collect.InstrumentHealth
import com.signalscope.collect.CellProbe
import com.signalscope.collect.Fault
import com.signalscope.collect.LiveState
import com.signalscope.collect.WarmthBlock
import com.signalscope.collect.WarmthExperiment
import com.signalscope.store.PingPong
import com.signalscope.store.MapProbeJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Mono = FontFamily.Monospace

/**
 * The diagnostics, said out loud.
 *
 * Every panel here is the same shape: the plain sentence from [PlainLanguage] first, the numbers
 * underneath, and where a measurement is missing, what would produce it. Nothing in this file
 * decides what is true — that is the language layer's job, and it can be tested. This file only
 * arranges it, so a disagreement about a sentence is settled in one place.
 *
 * Insets are not handled here. The whole app sits inside one `windowInsetsPadding(safeDrawing)` in
 * `MainActivity`, and a panel that added its own would double it; these compose into the existing
 * scrolling columns exactly like the cards already there.
 *
 * Every panel renders with zero data, because that is what a fresh install is.
 */
@Composable
fun DiagnosticPanels() {
    // First, deliberately. If the instrument is not working then everything below it is a guess,
    // and the reader needs to know that before reading any of it -- not after.
    InstrumentHealthPanel()
    ConnectionHealthPanel()
    ProblemsFoundPanel()
    KeepingAwakePanel()
    // Leaving Wi-Fi sits next to keeping-awake because they are two halves of one behaviour:
    // one predicts the worst moment, the other is what it does about it.
    HandoverPredictionPanel()
    // Movement is its own fault, not a variation on the others -- sitting still the connection
    // fails by sleeping, moving it fails by being dropped between masts -- so it gets its own
    // panel rather than a line inside connection health.
    JourneyPanel()
    PingPongPanel()
    UpDownPanel()
    BatteryCostPanel()
}

// ================================================================== connection health

/**
 * Only the single-probe rows describe waking the connection up.
 *
 * `probe_result` also holds the uplink/downlink legs (`DOWN8K`, `UP8K`), and those are 8 KB
 * transfers: their duration is a throughput measurement, not the cost of waking a radio, and
 * averaging them in would quietly inflate every wake-up figure on this panel. They are filtered
 * *after* [MapProbeJoin.read] rather than before, because a transfer genuinely does leave the
 * bearer awake and that read is where coldness is decided — dropping them earlier would mark the
 * next probe cold when it was not.
 *
 * The probe kinds are `CellProbe`'s own (`TLS`, `TCP`, `DNS`, each optionally suffixed with an
 * address family), so nothing here is specific to a device or a carrier.
 */
private fun probeStatsFor(ctx: Context): PlainLanguage.ProbeStats {
    // CellProbe.isReachability, not a prefix test here. Transfer probes measure the cost of
    // moving 8 KB and reachability probes the cost of reaching the network at all; pooling them
    // corrupts every wake-up statistic on this screen, and a probe kind added later must not be
    // silently absorbed by a string match that nobody remembers to update.
    val rows = MapProbeJoin.read(ctx).filter { CellProbe.isReachability(it.probeType, it.errorCode) }
    return PlainLanguage.probeStats(
        rows.map {
            PlainLanguage.ProbeObservation(
                ok = it.ok,
                latencyMs = it.latencyMs,
                cold = it.cold,
                onBearer = it.onBearer,
                // "No address of this family" is a property of the connection's configuration,
                // not a failure to wake up. WarmthExperiment excludes it from the same endpoint.
                noAddressOfFamily = it.probeType.startsWith("DNS") && !it.ok
            )
        }
    )
}

/**
 * Did the connection work, and how long did waking it take.
 *
 * [compact] is the Timeline form: the verdict and two figures, sized to sit above a list rather
 * than to be read on its own.
 */
@Composable
fun ConnectionHealthPanel(compact: Boolean = false) {
    val ctx = LocalContext.current
    val counters by LiveState.counters.collectAsStateWithLifecycle()

    var stats by remember { mutableStateOf<PlainLanguage.ProbeStats?>(null) }
    var loaded by remember { mutableStateOf(false) }

    // Re-read as rows accumulate, coarsened so a busy collector does not re-scan the probe table
    // on every callback. The read is on the IO dispatcher and wrapped: a corrupt or mid-migration
    // database throws, and an unhandled throw inside a LaunchedEffect takes the activity down.
    LaunchedEffect(counters.radioRows / 50) {
        val next = withContext(Dispatchers.IO) { runCatching { probeStatsFor(ctx) }.getOrNull() }
        if (next != null) stats = next
        loaded = true
    }

    val s = stats
    val v = if (s == null) null else PlainLanguage.connectionHealth(s)

    SecHead(
        "Connection health",
        when {
            s == null -> if (loaded) "unreadable" else "reading…"
            s.coldTotal == 0 -> "no wake-ups yet"
            else -> "${s.coldTotal} wake-ups measured"
        }
    )

    if (v == null) {
        Card {
            Text(
                if (!loaded) "Reading the collected measurements…"
                else "The collected measurements could not be read, so nothing is claimed here. " +
                    "This is a storage problem, not a healthy connection.",
                color = T.Faint, fontSize = 11.sp, lineHeight = 16.sp
            )
        }
        return
    }

    AccentCard(toneColor(v.tone)) {
        if (compact) {
            Tag(if (v.measured) "CONNECTION" else "NOT MEASURED", toneColor(v.tone))
            Spacer(Modifier.height(8.dp))
            Lede(v.headline, if (v.measured) T.Text else T.Dim)
            v.toMeasure?.let { ToMeasure(it) }
            if (v.numbers.isNotEmpty()) {
                Spacer(Modifier.height(9.dp))
                Divider()
                Spacer(Modifier.height(5.dp))
                v.numbers.take(3).forEach { (k, value) -> ERow(k, value) }
            }
            return@AccentCard
        }

        VerdictBlock(v)

        // Stated only where this phone's own rows show the contrast. The excursion's 12.3 % is
        // not this user's measurement and is not presented as one.
        PlainLanguage.dormancyContrast(s!!)?.let { c ->
            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(10.dp))
            Lede(c.headline, toneColor(c.tone))
            Spacer(Modifier.height(6.dp))
            Prose(c.body)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            PlainLanguage.signalIsTheWeather(),
            color = T.Faint, fontSize = 10.5.sp, lineHeight = 15.sp
        )
    }
}

// ================================================================== keeping awake

@Composable
fun KeepingAwakePanel() {
    val w by BearerWarmth.state.collectAsStateWithLifecycle()
    val now = PlainLanguage.warmthState(w)
    val history = PlainLanguage.warmthHistory(w)

    SecHead(
        "Keeping the connection awake",
        if (w.active) "holding · ${w.heldSecondsThisSession}s" else "not holding"
    )
    AccentCard(toneColor(now.tone)) {
        VerdictBlock(now)

        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(10.dp))
        Text("SO FAR", color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(5.dp))
        VerdictBlock(history, tag = false)

        // The most recent holds, in the user's words rather than in trigger labels. Newest first,
        // because the question is nearly always "what just happened".
        val recent = w.recent.takeLast(5).reversed()
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("RECENT HOLDS", color = T.Faint, fontSize = 9.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(4.dp))
            recent.forEach { h ->
                ERow(
                    at(h.startWall),
                    "${PlainLanguage.triggerWords(h.trigger)} · held ${h.heldMs / 1000}s · " +
                        "${PlainLanguage.pct(h.coverage)} of it covered"
                )
            }
        }

        Spacer(Modifier.height(11.dp))
        Text(PlainLanguage.warmthScope(), color = T.Faint, fontSize = 10.5.sp, lineHeight = 15.sp)

        RawReport("rolling summary, in the collector's own words", BearerWarmth.summary(w))
    }
}

// ================================================================== problems found

@Composable
fun ProblemsFoundPanel() {
    val faults by CarrierFaults.faults.collectAsStateWithLifecycle()
    val tier2 by CarrierFaults.tier2Note.collectAsStateWithLifecycle()
    val running by LiveState.running.collectAsStateWithLifecycle()

    val active = faults.count { it.active }
    SecHead(
        "Problems found",
        when {
            faults.isEmpty() -> if (running) "none" else "not watching"
            active == 0 -> "${faults.size} stopped"
            else -> "$active active"
        }
    )

    if (faults.isEmpty()) {
        // With the watch-start time, so "none found" can say how long it took to be
        // sure -- ten seconds of watching and a whole evening are not the same claim.
        val v = PlainLanguage.noFaultsFound(running, CarrierFaults.watchingSinceMillis)
        AccentCard(toneColor(v.tone)) {
            VerdictBlock(v)
            PlainLanguage.howToSeeMore(tier2)?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = T.Faint, fontSize = 10.5.sp, lineHeight = 15.sp)
            }
        }
        return
    }

    faults.forEach { FaultCard(it) }
    PlainLanguage.howToSeeMore(tier2)?.let {
        Text(it, color = T.Faint, fontSize = 10.5.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * One fault, with the action attached.
 *
 * [CarrierFaults] writes both the description and the suggested action from the evidence it holds
 * — which SIM, which service, whether it is roaming, how strong the evidence is — and none of that
 * is recomputed here. This card presents them, and nothing about it names a carrier, a slot or a
 * profile: every noun on screen came out of the fault object.
 */
@Composable
private fun FaultCard(f: Fault) {
    val c = if (f.active) T.Bad else T.Dim
    AccentCard(c, Modifier.padding(bottom = 8.dp)) {
        Tag(if (f.active) "HAPPENING NOW" else "STOPPED", c)
        Spacer(Modifier.height(8.dp))
        Lede(PlainLanguage.faultHeadline(f))

        if (f.description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Prose(f.description)
        }

        f.clearedNote?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Prose(it, T.Faint)
        }

        if (f.suggestedAction.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("WHAT YOU CAN DO", color = T.Brand, fontSize = 9.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(4.dp))
            Prose(f.suggestedAction, T.Text)
        }

        Spacer(Modifier.height(10.dp))
        Divider()
        Spacer(Modifier.height(5.dp))
        ERow("seen", "${f.count} times · first ${at(f.firstSeenMillis)} · last ${at(f.lastSeenMillis)}")
        ERow("affects", f.apnType?.let { "the \"$it\" connection" }
            ?: "a connection the phone will not name to an ordinary app")
        ERow("reason given", f.rawCause ?: "none — the phone records no reason for this")
        ERow("evidence", if (f.tier == com.signalscope.collect.Tier.TIER2)
            "the phone's own telephony log, read with extra privileges"
        else "the connection states any app can see — the repetition is the evidence")
    }
}

// ================================================================== upload vs download

@Composable
fun UpDownPanel() {
    val ctx = LocalContext.current
    val counters by LiveState.counters.collectAsStateWithLifecycle()
    var summary by remember { mutableStateOf<CellProbe.AsymSummary?>(null) }

    // asymSummary reads every probe row, so it is kept off the tick and coarsened like the health
    // panel. It already swallows its own failures and returns null, which renders as not measured.
    LaunchedEffect(counters.radioRows / 50) {
        val next = withContext(Dispatchers.IO) { CellProbe.asymSummary(ctx) }
        if (next != null) summary = next
    }

    val v = PlainLanguage.upVersusDown(summary)
    SecHead("Sending vs receiving", summary?.let { "${it.complete} usable pairs" } ?: "not measured")
    AccentCard(toneColor(v.tone)) {
        VerdictBlock(v)
        summary?.let { RawReport("the measurement's own report", it.report) }
    }
}

// ================================================================== battery cost

@Composable
fun BatteryCostPanel() {
    val ctx = LocalContext.current
    val st by WarmthExperiment.state.collectAsStateWithLifecycle()
    var saved by remember { mutableStateOf<List<WarmthBlock>>(emptyList()) }

    // The run happens once, in the service, and may well have finished in a previous process --
    // so the saved blocks are read rather than waiting for a live run that will never come again.
    LaunchedEffect(st.blocks.size) {
        saved = withContext(Dispatchers.IO) {
            runCatching { WarmthExperiment.load(ctx) }.getOrDefault(emptyList())
        }
    }

    val blocks = if (st.blocks.isNotEmpty()) st.blocks else saved
    val endpoint = blocks.firstOrNull()?.energyEndpoint ?: st.energyEndpoint
    val summary =
        if (blocks.isEmpty()) null
        else runCatching { WarmthExperiment.summary(blocks, endpoint) }.getOrNull()
    val v = PlainLanguage.batteryCost(st.running, st.block, st.total, st.arm, blocks, summary)

    SecHead(
        "Battery cost of keeping it awake",
        when {
            st.running -> "measuring · block ${st.block}/${st.total}"
            blocks.isEmpty() -> "not measured"
            else -> "${blocks.size} blocks"
        }
    )
    AccentCard(toneColor(v.tone)) {
        VerdictBlock(v)
        st.note?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            ERow("run note", it, T.Warn)
        }
        summary?.let { RawReport("the measurement's own report", it) }
    }
}

// ================================================================== shared bits

private val hm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Wall time as a clock reading, or an explicit dash: a zero timestamp is not midnight. */
private fun at(ms: Long): String =
    if (ms <= 0L) "—"
    else runCatching {
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().format(hm)
    }.getOrDefault("—")

// ================================================================== journeys

/**
 * What happened while the phone was moving.
 *
 * Its own panel rather than a line inside connection health, because the two describe different
 * faults. Sitting still, the connection fails by going to sleep and struggling to wake. Moving,
 * it fails by being dropped by one mast before the next has taken it on. The app has measured the
 * first at length and the second not at all, and this is where that second answer will appear.
 */
@Composable
fun JourneyPanel() {
    val state by Mobility.state.collectAsStateWithLifecycle()
    val open by Mobility.current.collectAsStateWithLifecycle()
    val past by Mobility.journeys.collectAsStateWithLifecycle()

    // The open journey if there is one, otherwise the most recent finished one: while moving the
    // live record is the interesting thing, and afterwards the completed one is.
    val subject = open ?: past.lastOrNull()
    val v = PlainLanguage.journeySummary(subject)

    SecHead(
        "While moving",
        when {
            open != null -> "recording now"
            past.isEmpty() -> "no journeys yet"
            else -> "${past.size} journey${if (past.size == 1) "" else "s"}"
        }
    )
    AccentCard(toneColor(v.tone)) {
        VerdictBlock(v)

        // Movement state is shown even with no journey, because "the phone thinks it is
        // stationary" is itself the explanation for an empty panel.
        Spacer(Modifier.height(10.dp))
        Divider()
        Spacer(Modifier.height(5.dp))
        ERow("right now", when (state.cls) {
            Mobility.Cls.FAST -> "moving fast"
            Mobility.Cls.VEHICLE -> "travelling"
            Mobility.Cls.WALKING -> "walking"
            Mobility.Cls.STATIONARY -> "still"
            Mobility.Cls.UNKNOWN -> "not established yet"
        })
        ERow("how that is known", PlainLanguage.mobilityConfidence(state))
        state.speedMps?.let { ERow("speed", "%.0f km/h".format(it * 3.6)) }
        if (open != null) ERow("this journey", PlainLanguage.forWords(open!!.durationMs))

        // The caveat is published by the collector itself rather than retyped here, so the
        // wording cannot drift away from what the code actually does.
        Spacer(Modifier.height(8.dp))
        Prose(Mobility.INFERENCE, T.Faint)
    }
}

// ================================================================== leaving Wi-Fi

@Composable
fun HandoverPredictionPanel() {
    val s by HandoverPredictor.state.collectAsStateWithLifecycle()
    val v = PlainLanguage.handoverPrediction(s)
    SecHead("Leaving Wi-Fi", if (s.watching) "watching" else "not watching")
    AccentCard(toneColor(v.tone)) {
        VerdictBlock(v)
        s.note?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Prose(it, T.Faint)
        }
    }
}

// ================================================================== instrument health

/**
 * Whether the app can currently measure anything, shown above the measurements.
 *
 * It stays quiet when everything is fine -- one green line, no list -- because a panel that
 * always shouts gets skipped, and this one needs to be believed on the day it matters.
 */
@Composable
fun InstrumentHealthPanel() {
    val h by InstrumentHealth.state.collectAsStateWithLifecycle()
    if (h.checks.isEmpty()) return

    val tone = when (h.worst) {
        InstrumentHealth.Level.OK -> PlainLanguage.Tone.GOOD
        InstrumentHealth.Level.DEGRADED -> PlainLanguage.Tone.WATCH
        InstrumentHealth.Level.BROKEN -> PlainLanguage.Tone.BAD
        InstrumentHealth.Level.UNKNOWN -> PlainLanguage.Tone.ABSENT
    }
    val problems = h.checks.filter {
        it.level == InstrumentHealth.Level.DEGRADED || it.level == InstrumentHealth.Level.BROKEN
    }

    SecHead("Can the app measure?", if (problems.isEmpty()) "all inputs working"
            else "${problems.size} not working")
    AccentCard(toneColor(tone)) {
        Lede(h.headline, toneColor(tone))

        // Only the failures are listed. Naming the six things that are fine every time would
        // bury the one that is not.
        problems.forEach { c ->
            Spacer(Modifier.height(8.dp))
            Text(
                c.name.uppercase(),
                color = toneColor(
                    if (c.level == InstrumentHealth.Level.BROKEN) PlainLanguage.Tone.BAD
                    else PlainLanguage.Tone.WATCH
                ),
                fontSize = 9.sp, fontFamily = Mono,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
            )
            Spacer(Modifier.height(3.dp))
            Prose(c.detail)
            c.action?.let {
                Spacer(Modifier.height(4.dp))
                Prose(it, T.Text)
            }
        }

        if (problems.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Prose(
                "All " + h.checks.size + " inputs are arriving: the radio, network registration, " +
                    "the connection tests, and position where it is wanted. Anything reported " +
                    "below rests on measurements that were actually taken.",
                T.Dim
            )
        }
    }
}

// ================================================================== ping-pong

/**
 * Does bouncing between masts actually hurt?
 *
 * Its own panel because the answer so far is no, and a negative result that nobody can see is
 * indistinguishable from one nobody produced. The analysis walks every stored radio row, so it is
 * run off the main thread and only when the row count has moved meaningfully.
 */
@Composable
fun PingPongPanel() {
    val ctx = LocalContext.current
    val counters by LiveState.counters.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf<PingPong.Report?>(null) }

    LaunchedEffect(counters.radioRows / 500) {
        val next = withContext(Dispatchers.IO) { PingPong.analyse(ctx) }
        report = next
    }

    val r = report
    SecHead("Bouncing between masts", r?.let { "${it.pingPongEvents} returns seen" } ?: "checking")
    AccentCard(toneColor(if (r == null) PlainLanguage.Tone.ABSENT else PlainLanguage.Tone.NEUTRAL)) {
        if (r == null) {
            Lede("Working it out…", T.Dim)
        } else {
            RawReport("what the comparison found", PingPong.summary(r))
        }
    }
}
