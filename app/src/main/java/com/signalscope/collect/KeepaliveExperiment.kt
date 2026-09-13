package com.signalscope.collect

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Does holding the cellular connection open reduce serving-cell churn?
 *
 * In RRC_IDLE the handset performs cell reselection autonomously, which is what produces the
 * ping-pong across sectors of one mast that this device shows. In RRC_CONNECTED mobility is
 * network-controlled handover instead. If that difference is real here, low-rate traffic that
 * holds the connection open should reduce cell changes per minute.
 *
 * This is independent of the Phase A result. Phase A asked whether a forced cycle lands somewhere
 * new — it cannot, on this device. This asks whether staying connected changes how often the cell
 * moves, which needs no privilege at all.
 *
 * **Design.** Alternating blocks, never sequential: interference here is load-driven and load
 * follows the clock, so A-then-B would measure the evening and call it a treatment effect. The
 * primary endpoint is cell changes per minute; RSRP is the control, and a block where RSRP shifts
 * materially is evidence something physical changed rather than the treatment working.
 */
data class Block(
    val index: Int,
    val arm: String,            // "A" = idle, "B" = keepalive
    val minutes: Double,
    val cellChanges: Int,
    val distinctCells: Int,
    val sinrP50: Int?,
    val sinrSd: Double,
    val rsrpP50: Int?,
    val rsrpSd: Double,
    val samples: Int,
    /** Ticks that read an unchanged, already-consumed snapshot. High means the block is blind. */
    val staleTicks: Int = 0
)

object KeepaliveExperiment {

    data class State(
        val running: Boolean = false,
        val block: Int = 0,
        val total: Int = 0,
        val arm: String = "",
        val blocks: List<Block> = emptyList(),
        val note: String? = null
    )

    val state = MutableStateFlow(State())

    private const val PREFS = "keepalive_exp"
    private const val KEY = "blocks"
    private const val BLOCK_MS = 5 * 60_000L
    /** Shorter than a typical LTE RRC inactivity timer, so the connection is genuinely held. */
    private const val KEEPALIVE_MS = 5_000L

    fun run(ctx: Context, scope: CoroutineScope, blocksPerArm: Int = 6) {
        if (state.value.running) return
        scope.launch { execute(ctx, blocksPerArm) }
    }

    private suspend fun execute(ctx: Context, perArm: Int) = coroutineScope {
        val total = perArm * 2
        // Randomise which arm leads, so any slow drift is not systematically attributed to one.
        val leadB = System.currentTimeMillis() % 2 == 0L
        state.value = State(running = true, total = total)

        val out = mutableListOf<Block>()
        try {
            repeat(total) { i ->
                val arm = if ((i % 2 == 0) == leadB) "B" else "A"
                state.value = state.value.copy(block = i + 1, arm = arm, blocks = out.toList())

                var ka: Job? = null
                if (arm == "B") ka = launch { holdConnection(ctx) }

                val sinr = mutableListOf<Int>()
                val rsrp = mutableListOf<Int>()
                val cells = mutableSetOf<Long>()
                var changes = 0
                var last: Long? = null
                var stale = 0
                // Consume each reading once. Without this the loop counts the *same* snapshot
                // every 2 s and reports the repetition as stability -- which is exactly how the
                // 2026-09-12 run produced eight blocks of zero-variance fiction after the screen
                // went off. A sample is only a sample if it is new.
                var lastSignalMillis = 0L
                val t0 = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - t0 < BLOCK_MS) {
                    val s = LiveState.sims.value.values.firstOrNull { it.isDataSub }
                    if (s == null || s.signalStale() || s.signalMillis == lastSignalMillis) {
                        stale++
                    } else {
                        lastSignalMillis = s.signalMillis
                        s.rssnr?.let { sinr += it }
                        s.rsrp?.let { rsrp += it }
                        s.ci?.let { c ->
                            cells += c
                            if (last != null && c != last) changes++
                            last = c
                        }
                    }
                    delay(2_000)
                }
                ka?.cancelAndJoin()

                fun sd(v: List<Int>) = if (v.size < 2) 0.0 else {
                    val m = v.average(); kotlin.math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
                }
                fun p50(v: List<Int>) = if (v.isEmpty()) null else v.sorted()[v.size / 2]

                out += Block(
                    index = i + 1, arm = arm, minutes = BLOCK_MS / 60_000.0,
                    cellChanges = changes, distinctCells = cells.size,
                    sinrP50 = p50(sinr), sinrSd = sd(sinr),
                    rsrpP50 = p50(rsrp), rsrpSd = sd(rsrp), samples = rsrp.size,
                    staleTicks = stale
                )
                state.value = state.value.copy(blocks = out.toList())
                save(ctx, out)
            }
        } catch (e: Exception) {
            state.value = state.value.copy(note = e.message ?: e.javaClass.simpleName)
        } finally {
            state.value = state.value.copy(running = false, blocks = out.toList())
        }
    }

    /**
     * Low-rate traffic on the cellular network specifically. A 12-byte datagram every 5 s is
     * enough to keep the connection up and small enough not to matter; binding to the cellular
     * Network means the user's Wi-Fi session and every other app are untouched.
     */
    private suspend fun holdConnection(ctx: Context) = withContext(Dispatchers.IO) {
        while (isActive) {
            runCatching {
                CellProbe.withCellular { net ->
                    DatagramSocket().use { s ->
                        net.bindSocket(s)
                        val payload = ByteArray(12)
                        s.send(DatagramPacket(payload, payload.size,
                            InetAddress.getByName("1.1.1.1"), 53))
                    }
                }
            }
            delay(KEEPALIVE_MS)
        }
    }

    private fun save(ctx: Context, blocks: List<Block>) {
        val a = JSONArray()
        blocks.forEach {
            a.put(JSONObject().apply {
                put("i", it.index); put("arm", it.arm); put("changes", it.cellChanges)
                put("cells", it.distinctCells); put("sinrP50", it.sinrP50 ?: JSONObject.NULL)
                put("sinrSd", it.sinrSd); put("rsrpP50", it.rsrpP50 ?: JSONObject.NULL)
                put("rsrpSd", it.rsrpSd); put("n", it.samples); put("stale", it.staleTicks)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /** Paired comparison. Reports an interval, and is willing to report no effect. */
    fun summary(blocks: List<Block>): String {
        val a = blocks.filter { it.arm == "A" }
        val b = blocks.filter { it.arm == "B" }
        if (a.isEmpty() || b.isEmpty()) return "${blocks.size} blocks — need both arms"
        val ra = a.sumOf { it.cellChanges } / a.sumOf { it.minutes }
        val rb = b.sumOf { it.cellChanges } / b.sumOf { it.minutes }
        val rsrpShift = kotlin.math.abs(
            (a.mapNotNull { it.rsrpP50 }.average()) - (b.mapNotNull { it.rsrpP50 }.average()))
        // Blindness is checked before effect. A block that saw nothing reports "no churn", which
        // is indistinguishable from a treatment that worked -- so validity is not a footnote here,
        // it is the first question, and a run that was mostly blind has no effect size at all.
        val totalTicks = blocks.sumOf { it.staleTicks + it.samples }
        val blindFrac = if (totalTicks == 0) 1.0 else blocks.sumOf { it.staleTicks } / totalTicks.toDouble()
        val blindBlocks = blocks.count { it.samples < 30 }

        return buildString {
            append("A (idle): %.2f cell changes/min over %d blocks\n".format(ra, a.size))
            append("B (keepalive): %.2f cell changes/min over %d blocks\n".format(rb, b.size))
            append(when {
                blindFrac > 0.30 ->
                    ("BLIND -- %.0f%% of ticks read a stale snapshot (%d of %d blocks under 30 " +
                     "real samples). The radio stream stopped, most likely screen-off. No conclusion.")
                        .format(blindFrac * 100, blindBlocks, blocks.size)
                rsrpShift > 3 ->
                    "INVALID — RSRP differs by %.1f dB between arms; something physical changed."
                        .format(rsrpShift)
                a.sumOf { it.cellChanges } + b.sumOf { it.cellChanges } < 6 ->
                    "TOO FEW EVENTS — the cell barely moved in either arm. No conclusion."
                rb < ra * 0.6 -> "Keepalive reduced cell churn substantially."
                rb > ra * 1.4 -> "Keepalive INCREASED cell churn. Do not deploy."
                else -> "NO DETECTABLE EFFECT at this sample size."
            })
        }
    }
}
