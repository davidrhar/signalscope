package com.signalscope.collect

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.signalscope.store.Db
import com.signalscope.store.ProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * What does holding the bearer warm **cost**, and what does it **buy**?
 *
 * [BearerWarmth] is deployable on the strength of `excursion-findings.md`: a cold probe on a
 * dormant bearer failed 12.3 % of the time, a cold probe on a bearer already carrying traffic
 * failed 0 of 36 (P ≈ 0.008). The effect is not the open question any more. The price is. This
 * harness is therefore a cost experiment with a reliability check attached, not a "does it work"
 * experiment.
 *
 * **Design, copied from [KeepaliveExperiment] because the reasons have not changed.** Alternating
 * blocks, never sequential arms, with a randomised lead arm: load follows the clock, the battery
 * curve follows the clock, and an A-then-B run would measure the evening and call it a treatment
 * effect. Each radio reading is consumed once, keyed on [SimState.signalMillis], and every tick
 * that re-reads an already-consumed snapshot is counted. Validity is reported *before* effect: a
 * run that was mostly blind gets no effect size at all. That rule exists because on 2026-09-12 an
 * experiment read the same frozen snapshot 600 times after the screen went off and recorded eight
 * blocks of zero-variance fiction.
 *
 * **The treatment is measured, not assumed.** Arm B asks [BearerWarmth.holdNow] to hold and then
 * counts the ticks on which warmth actually reported itself active; arm A closes any manual window
 * and counts the same thing, which catches the case where warmth's own triggers (a call arriving
 * mid-run) contaminated the control. A block where the treatment did not happen is reported as
 * such rather than averaged in.
 */
data class WarmthBlock(
    val index: Int,
    /** "A" = no warmth, "B" = warmth held. */
    val arm: String,
    val minutes: Double,

    // ---- cost
    /** Which energy endpoint this device actually reports: CHARGE_COUNTER, CAPACITY, or none. */
    val energyEndpoint: String,
    /** µAh consumed over the block, positive = drained. Null when the counter is unavailable. */
    val drainUah: Long?,
    /** Whole percent consumed over the block. Coarse; see [BLOCK_JUSTIFICATION]. */
    val drainPct: Int?,
    /** True if the charger was seen at any point. A charging block cannot measure drain at all. */
    val charging: Boolean,

    // ---- benefit
    /** First probe of each probe cycle — the one that pays promotion on a dormant bearer. */
    val firstProbes: Int,
    val firstProbeFailures: Int,
    val firstProbeMeanMs: Int?,
    /** "No address of this family" rows: a property of the bearer, not a transition failure. */
    val dnsOnlyRows: Int,

    // ---- treatment fidelity
    /** Milliseconds of the block on which [BearerWarmth] reported itself actively holding. */
    val heldMs: Long,

    // ---- validity
    val ticks: Int,
    /** Ticks that read an unchanged, already-consumed snapshot. High means the block is blind. */
    val staleTicks: Int,
    /** Ticks on which cellular held the default route. Warmth is gated off for the rest. */
    val cellularTicks: Int,
    val rsrpP50: Int?
) {
    val blindFraction: Double get() = if (ticks == 0) 1.0 else staleTicks / ticks.toDouble()
    val cellularFraction: Double get() = if (ticks == 0) 0.0 else cellularTicks / ticks.toDouble()
    val heldFraction: Double
        get() = if (minutes <= 0.0) 0.0 else (heldMs / (minutes * 60_000.0)).coerceAtMost(1.0)
}

object WarmthExperiment {

    data class State(
        val running: Boolean = false,
        val block: Int = 0,
        val total: Int = 0,
        val arm: String = "",
        val energyEndpoint: String = "not discovered",
        val blocks: List<WarmthBlock> = emptyList(),
        val note: String? = null
    )

    val state = MutableStateFlow(State())

    private const val PREFS = "warmth_exp"
    private const val KEY = "blocks"

    /**
     * Ten minutes per block, which is the floor rather than a preference.
     *
     * The cost endpoint is charge drawn, and it has to move by more than the counter's own
     * granularity. Holding the bearer out of dormancy plausibly costs on the order of 10–20 mA;
     * ten minutes at 15 mA is 2.5 mAh, i.e. ~2500 µAh, which clears the ~1000 µAh quantisation
     * that charge-counter implementations commonly report and clears tick-to-tick noise. Shorter
     * blocks would return a difference of two quantisation steps and call it a measurement.
     *
     * BATTERY_PROPERTY_CAPACITY cannot do this job at any practical block length: it is whole
     * percent, and 1 % of a typical cell is ~45 mAh — three hours at the draw above. So on a device
     * that only reports CAPACITY this experiment says the cost is not measurable here instead of
     * dividing two integers and presenting the result.
     *
     * The benefit endpoint agrees with the choice. `CollectorService` probes every 45 s off Wi-Fi,
     * so a ten-minute block holds ~13 probe cycles, ~39 per arm at three blocks each; at the
     * measured 12.3 % dormant failure rate that is ~4.8 expected failures in the control, which is
     * the smallest sample from which the 0 % arm means anything at all.
     */
    private const val BLOCK_MS = 10 * 60_000L
    const val BLOCK_JUSTIFICATION = "10 min: charge counter must move past its own quantisation"

    /** Same 2 s cadence as the other experiments, so staleness is comparable across them. */
    private const val TICK_MS = 2_000L

    /**
     * Two probes fired back to back are one cycle; cycles are ≥45 s apart. Anything arriving
     * within this window of the previous row belongs to the same cycle and is therefore riding a
     * connection the first probe already established.
     */
    private const val CYCLE_GAP_MS = 15_000L

    /** Above this fraction of stale ticks the run is blind and gets no effect size. */
    private const val BLIND_LIMIT = 0.30

    /** Below this fraction of cellular-default ticks the treatment was gated off too often. */
    private const val CELLULAR_FLOOR = 0.70

    /** Arm B must actually have been held for most of the block, or there is no treatment. */
    private const val FIDELITY_FLOOR = 0.70

    /** Arm A above this is contaminated: warmth's own triggers fired during the control. */
    private const val CONTAMINATION_LIMIT = 0.20

    fun run(ctx: Context, scope: CoroutineScope, blocksPerArm: Int = 3) {
        if (state.value.running) return
        // Three per arm is one hour. Long enough for six charge-counter deltas and ~78 probe
        // cycles; short enough that the phone is plausibly in one state of use throughout.
        scope.launch { execute(ctx, scope, blocksPerArm) }
    }

    private suspend fun execute(ctx: Context, scope: CoroutineScope, perArm: Int) = coroutineScope {
        val total = perArm * 2
        // Randomise which arm leads, so any slow drift -- and the battery curve is nothing but
        // slow drift -- is not systematically attributed to one arm.
        val leadB = System.currentTimeMillis() % 2 == 0L
        val endpoint = discoverEnergyEndpoint(ctx)
        state.value = State(running = true, total = total, energyEndpoint = endpoint)

        val out = mutableListOf<WarmthBlock>()
        try {
            repeat(total) { i ->
                val arm = if ((i % 2 == 0) == leadB) "B" else "A"
                state.value = state.value.copy(block = i + 1, arm = arm, blocks = out.toList())

                val startWall = System.currentTimeMillis()
                val e0 = readEnergy(ctx)
                // probeCount() is the base id, the same approximation ExcursionRecorder uses. The
                // wall-time filter below makes it exact either way: nothing older than this block
                // can be counted even if the count and the max id have drifted apart.
                val probeBase = runCatching { Db.get(ctx).dao().probeCount() }.getOrDefault(0)

                if (arm == "B") {
                    // Ask for a hold slightly longer than the block so the window cannot lapse a
                    // tick early. It is closed explicitly at the end of the block.
                    BearerWarmth.holdNow(ctx, scope, "experiment", BLOCK_MS + 30_000L)
                } else {
                    // Close any window a previous B block left open. This does not disable
                    // warmth's own triggers -- those are measured as contamination instead.
                    BearerWarmth.holdNow(ctx, scope, "experiment-off", 0L)
                }

                var ticks = 0; var stale = 0; var cellular = 0; var heldTicks = 0
                var chargingSeen = false
                val rsrp = mutableListOf<Int>()
                // Consume each reading once, keyed on the reading's own timestamp. Without this the
                // loop counts the same snapshot every 2 s and reports the repetition as data.
                var lastSignalMillis = 0L

                val t0 = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - t0 < BLOCK_MS) {
                    ticks++
                    if (runCatching { LiveState.net.value.transport }.getOrNull() == "CELLULAR") cellular++
                    if (runCatching { BearerWarmth.state.value.active }.getOrDefault(false)) heldTicks++

                    val s = LiveState.sims.value.values.firstOrNull { it.isDataSub }
                    if (s == null || s.signalStale() || s.signalMillis == lastSignalMillis) {
                        stale++
                    } else {
                        lastSignalMillis = s.signalMillis
                        s.rsrp?.let { rsrp += it }
                    }

                    // Charging at any point destroys the drain endpoint for this block, so it is
                    // recorded as a property of the block rather than checked once at the start.
                    // Every 15th tick (~30 s): the sticky read is a binder call and a charger
                    // plugged in for less than 30 s would not move the counter measurably anyway.
                    if (ticks % 15 == 1 && isCharging(ctx)) chargingSeen = true

                    delay(TICK_MS)
                }

                if (arm == "B") BearerWarmth.holdNow(ctx, scope, "experiment-off", 0L)

                val e1 = readEnergy(ctx)
                val rows = runCatching {
                    Db.get(ctx).dao().probesSinceWall(startWall)
                }.getOrDefault(emptyList())
                val cold = coldProbes(rows)
                val okLat = cold.filter { it.outcome == "OK" }.map { it.latencyMs }

                out += WarmthBlock(
                    index = i + 1, arm = arm, minutes = BLOCK_MS / 60_000.0,
                    energyEndpoint = endpoint,
                    drainUah = if (e0.uah != null && e1.uah != null) e0.uah - e1.uah else null,
                    drainPct = if (e0.pct != null && e1.pct != null) e0.pct - e1.pct else null,
                    charging = chargingSeen,
                    firstProbes = cold.size,
                    firstProbeFailures = cold.count { it.outcome != "OK" },
                    firstProbeMeanMs = if (okLat.isEmpty()) null else okLat.average().toInt(),
                    dnsOnlyRows = rows.count { it.probeType.startsWith("DNS") && it.outcome != "OK" },
                    heldMs = heldTicks * TICK_MS,
                    ticks = ticks, staleTicks = stale, cellularTicks = cellular,
                    rsrpP50 = if (rsrp.isEmpty()) null else rsrp.sorted()[rsrp.size / 2]
                )
                state.value = state.value.copy(blocks = out.toList())
                save(ctx, out, endpoint)
            }
        } catch (e: Exception) {
            state.value = state.value.copy(note = e.message ?: e.javaClass.simpleName)
        } finally {
            // Never leave the radio held because the run ended badly.
            runCatching { BearerWarmth.holdNow(ctx, scope, "experiment-off", 0L) }
            state.value = state.value.copy(running = false, blocks = out.toList())
        }
    }

    // ---------------------------------------------------------------- endpoints

    private data class Energy(val uah: Long?, val pct: Int?)

    /**
     * Which endpoint this handset actually reports — discovered, never assumed.
     *
     * `BATTERY_PROPERTY_CHARGE_COUNTER` is µAh and is the only endpoint fine enough to see a
     * ten-minute difference, but plenty of devices return `Int.MIN_VALUE` (the platform's "not
     * supported") or a constant zero for it. `BATTERY_PROPERTY_CAPACITY` is whole percent and is
     * always there, and is too coarse to be an answer — so a CAPACITY-only device gets a stated
     * inability rather than a fabricated drain figure.
     */
    private fun discoverEnergyEndpoint(ctx: Context): String {
        val e = readEnergy(ctx)
        return when {
            e.uah != null -> "CHARGE_COUNTER"
            e.pct != null -> "CAPACITY"
            else -> "unavailable"
        }
    }

    private fun readEnergy(ctx: Context): Energy {
        val bm = runCatching { ctx.getSystemService(BatteryManager::class.java) }.getOrNull()
        val cc = runCatching {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }.getOrNull()
        // Int.MIN_VALUE is the documented "not supported"; 0 is what several OEM HALs return
        // instead, and a counter pinned at zero is not a measurement.
        val uah = if (cc != null && cc != Int.MIN_VALUE && cc > 0) cc.toLong() else null

        val propPct = runCatching {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
        val pct = if (propPct != null && propPct in 0..100) propPct else runCatching {
            val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) level * 100 / scale else null
        }.getOrNull()

        return Energy(uah, pct)
    }

    private fun isCharging(ctx: Context): Boolean = runCatching {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    /**
     * The first probe of each cycle — the one that pays the idle→connected transition when the
     * bearer is dormant, and the only row comparable across arms.
     *
     * `CellProbe.probeAndRecord` fires two probes back to back and the second rides the connection
     * the first established (measured: 457 ms mean for the first, 168 ms for the second). Rows are
     * therefore grouped by arrival gap, and only the head of each group is kept. Under warmth no
     * probe is truly cold, which is exactly the effect being measured, so the endpoint is defined
     * by position in the cycle rather than by an assumption about the radio's state.
     */
    private fun coldProbes(rows: List<ProbeResult>): List<ProbeResult> {
        val sorted = rows.sortedBy { it.elapsedNanos }
        val out = mutableListOf<ProbeResult>()
        var prev = Long.MIN_VALUE
        for (r in sorted) {
            val ms = r.elapsedNanos / 1_000_000
            if (prev == Long.MIN_VALUE || ms - prev > CYCLE_GAP_MS) out += r
            prev = ms
        }
        // Heads are chosen from every row, because any of them may have woken the radio -- but only
        // a reachability head is kept. A cycle whose head was an app fault or a stall summary is
        // dropped rather than letting the next row stand in: that row rode the connection the head
        // opened, so promoting it would put a warm probe in a cold slot. Without this a refused
        // bind became a cycle's cold probe and was scored as the bearer failing to wake.
        return out
            .filter { CellProbe.isReachability(it.probeType, it.errorCode) }
            // A "no address of this family" row is a DNS outcome, not an idle→connected failure.
            .filterNot { it.probeType.startsWith("DNS") && it.outcome != "OK" }
    }

    // ---------------------------------------------------------------- readback

    private fun save(ctx: Context, blocks: List<WarmthBlock>, endpoint: String) {
        val a = JSONArray()
        blocks.forEach {
            a.put(JSONObject().apply {
                put("i", it.index); put("arm", it.arm); put("min", it.minutes)
                put("endpoint", it.energyEndpoint)
                put("uah", it.drainUah ?: JSONObject.NULL)
                put("pct", it.drainPct ?: JSONObject.NULL)
                put("charging", it.charging)
                put("cold", it.firstProbes); put("coldFail", it.firstProbeFailures)
                put("coldMs", it.firstProbeMeanMs ?: JSONObject.NULL)
                put("dnsOnly", it.dnsOnlyRows)
                put("heldMs", it.heldMs)
                put("ticks", it.ticks); put("stale", it.staleTicks); put("cell", it.cellularTicks)
                put("rsrpP50", it.rsrpP50 ?: JSONObject.NULL)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONObject().apply {
                put("endpoint", endpoint); put("blocks", a)
            }.toString()).apply()
    }

    fun load(ctx: Context): List<WarmthBlock> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val o = JSONObject(raw)
            val a = o.optJSONArray("blocks") ?: JSONArray()
            (0 until a.length()).map { i ->
                val b = a.getJSONObject(i)
                WarmthBlock(
                    index = b.optInt("i"), arm = b.optString("arm"),
                    minutes = b.optDouble("min", BLOCK_MS / 60_000.0),
                    energyEndpoint = b.optString("endpoint", "unavailable"),
                    drainUah = if (b.isNull("uah")) null else b.optLong("uah"),
                    drainPct = if (b.isNull("pct")) null else b.optInt("pct"),
                    charging = b.optBoolean("charging"),
                    firstProbes = b.optInt("cold"), firstProbeFailures = b.optInt("coldFail"),
                    firstProbeMeanMs = if (b.isNull("coldMs")) null else b.optInt("coldMs"),
                    dnsOnlyRows = b.optInt("dnsOnly"),
                    heldMs = b.optLong("heldMs"),
                    ticks = b.optInt("ticks"), staleTicks = b.optInt("stale"),
                    cellularTicks = b.optInt("cell"),
                    rsrpP50 = if (b.isNull("rsrpP50")) null else b.optInt("rsrpP50")
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Validity first, effect second, and willing to say there isn't one.
     *
     * Every gate below has a specific failure behind it. A blind run reports "nothing changed" for
     * both arms and looks like a null result. A run that spent most of its time on Wi-Fi measured a
     * treatment that was gated off and cold probes on a bearer nobody was using — the same mistake
     * the whole project made before the excursion. A charging block has no drain to measure. And an
     * arm B that was never actually held is not a treatment arm.
     */
    fun summary(blocks: List<WarmthBlock>, endpoint: String = "CHARGE_COUNTER"): String {
        if (blocks.isEmpty()) return "no blocks — nothing to report"
        val a = blocks.filter { it.arm == "A" }
        val b = blocks.filter { it.arm == "B" }
        if (a.isEmpty() || b.isEmpty()) return "${blocks.size} blocks — need both arms"

        val ticks = blocks.sumOf { it.ticks }
        val blind = if (ticks == 0) 1.0 else blocks.sumOf { it.staleTicks } / ticks.toDouble()
        val cellFrac = if (ticks == 0) 0.0 else blocks.sumOf { it.cellularTicks } / ticks.toDouble()
        val bFidelity = b.map { it.heldFraction }.average()
        val aHeld = a.map { it.heldFraction }.average()

        return buildString {
            append("endpoint: $endpoint · $BLOCK_JUSTIFICATION\n")
            append("coverage: %.0f%% of ticks fresh, %.0f%% on cellular\n"
                .format((1 - blind) * 100, cellFrac * 100))
            append("treatment: arm B held %.0f%% of its blocks, arm A %.0f%%\n"
                .format(bFidelity * 100, aHeld * 100))

            // ---- validity gates, in order. Any of these and there is no effect size.
            if (blind > BLIND_LIMIT) {
                append(("BLIND — %.0f%% of ticks read a stale snapshot. The radio stream stopped, " +
                    "most likely screen-off. No conclusion, and no effect size.")
                    .format(blind * 100))
                return@buildString
            }
            if (cellFrac < CELLULAR_FLOOR) {
                append(("INVALID — only %.0f%% of the run had cellular as the default route. " +
                    "Warmth is gated off on Wi-Fi, so most of this measured no treatment at all.")
                    .format(cellFrac * 100))
                return@buildString
            }
            if (bFidelity < FIDELITY_FLOOR) {
                append(("NO TREATMENT — arm B was only held %.0f%% of the time (needs %.0f%%). " +
                    "Most likely a gate refused: check battery and transport.")
                    .format(bFidelity * 100, FIDELITY_FLOOR * 100))
                return@buildString
            }
            if (aHeld > CONTAMINATION_LIMIT) {
                append(("CONTAMINATED CONTROL — arm A was held %.0f%% of the time by warmth's own " +
                    "triggers (a call or a handover during the control). The arms are not distinct.")
                    .format(aHeld * 100))
                return@buildString
            }

            // ---- cost
            val aCost = a.filterNot { it.charging }
            val bCost = b.filterNot { it.charging }
            val discarded = blocks.count { it.charging }
            if (discarded > 0) append("$discarded block(s) discarded from the cost: charger seen\n")

            val aUah = aCost.mapNotNull { it.drainUah }
            val bUah = bCost.mapNotNull { it.drainUah }
            when {
                aCost.isEmpty() || bCost.isEmpty() ->
                    append("COST UNMEASURED — no discharging block left in one arm.\n")
                aUah.isNotEmpty() && bUah.isNotEmpty() && (aUah + bUah).any { it != 0L } -> {
                    val ra = aUah.average() / (aCost.first().minutes / 60.0)   // µAh per hour = µA
                    val rb = bUah.average() / (bCost.first().minutes / 60.0)
                    append("cost: A %.1f mA, B %.1f mA — warmth costs %+.1f mA\n"
                        .format(ra / 1000.0, rb / 1000.0, (rb - ra) / 1000.0))
                    append(if (kotlin.math.abs(rb - ra) < 1000.0)
                        "  (under 1 mA apart — at this block length that is indistinguishable from noise)\n"
                    else "")
                }
                else -> {
                    val aPct = aCost.mapNotNull { it.drainPct }
                    val bPct = bCost.mapNotNull { it.drainPct }
                    append(("COST NOT MEASURABLE — the charge counter never moved, and CAPACITY is " +
                        "whole percent (A %s, B %s over %.0f min blocks), which cannot resolve a " +
                        "difference this size. Re-run with longer blocks or on a device that " +
                        "reports CHARGE_COUNTER.\n")
                        .format(aPct.joinToString("/"), bPct.joinToString("/"),
                            blocks.first().minutes))
                }
            }

            // ---- benefit
            val an = a.sumOf { it.firstProbes }; val af = a.sumOf { it.firstProbeFailures }
            val bn = b.sumOf { it.firstProbes }; val bf = b.sumOf { it.firstProbeFailures }
            append("benefit: first-probe failures A %d/%d, B %d/%d\n".format(af, an, bf, bn))
            append(when {
                an < 20 || bn < 20 ->
                    "TOO FEW PROBES — %d and %d first probes. The reliability endpoint says nothing yet."
                        .format(an, bn)
                af == 0 && bf == 0 ->
                    "NO FAILURES IN EITHER ARM — nothing to improve in this run; the failure class " +
                    "the findings measured did not occur here."
                af > 0 && bf == 0 ->
                    "Warmth removed the first-probe failures seen in the control (%d/%d → 0/%d)."
                        .format(af, an, bn)
                bf.toDouble() / bn > af.toDouble() / an ->
                    "Warmth did NOT reduce first-probe failures. Do not deploy on this evidence."
                else -> "NO DETECTABLE EFFECT on first-probe failures at this sample size."
            })

            val rs = blocks.mapNotNull { it.rsrpP50 }
            if (rs.isNotEmpty()) {
                val ra = a.mapNotNull { it.rsrpP50 }
                val rb = b.mapNotNull { it.rsrpP50 }
                if (ra.isNotEmpty() && rb.isNotEmpty()) {
                    val shift = kotlin.math.abs(ra.average() - rb.average())
                    append("\ncontrol: RSRP differs by %.1f dB between arms%s"
                        .format(shift, if (shift > 3) " — something physical changed" else ""))
                }
            }
        }
    }
}
