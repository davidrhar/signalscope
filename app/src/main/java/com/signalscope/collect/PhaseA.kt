package com.signalscope.collect

import android.content.Context
import android.telephony.TelephonyManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase A: is the landing distribution degenerate?
 *
 * We cannot choose a cell or a band. But after a detach the modem performs *initial cell
 * selection*, which is a different procedure from the priority-based *reselection* that normally
 * pins us to the capacity layer. So a forced cycle is a draw from a distribution of landing cells.
 *
 * This measures that distribution and nothing else. It does not try to improve anything. If every
 * trial lands on the same cell, the lever is dead here and we say so — which is the result, not a
 * failure. See docs/forced-reselection.md.
 *
 * **Safety.** The restore value is persisted to disk *before* the radio is touched, and
 * [recoverIfNeeded] restores it on next start. If this process is killed mid-trial the phone does
 * not stay on a reduced radio configuration.
 */
data class Trial(
    val index: Int,
    val beforeCi: Long?, val beforeBand: Int?, val beforeSinr: Int?,
    val landedCi: Long?, val landedBand: Int?,
    val timeToServiceMs: Long,
    val sinrAfterP50: Int?,
    val cellChangesIn60s: Int,
    val dwellMs: Long,
    val note: String
)

object PhaseA {

    data class Progress(
        val running: Boolean = false,
        val trial: Int = 0,
        val total: Int = 0,
        val phase: String = "",
        val trials: List<Trial> = emptyList(),
        val error: String? = null
    )

    val progress = MutableStateFlow(Progress())

    private const val PREFS = "phase_a"
    private const val KEY_RESTORE = "restore_bitmask"
    private const val KEY_TRIALS = "trials"

    /** Detach window. Long enough to force a real re-attach, short enough to be a blip. */
    private const val DETACH_MS = 4_000L
    private const val OBSERVE_MS = 60_000L
    private const val COOLDOWN_MS = 20_000L

    private val NAME_TO_BIT = mapOf(
        "GPRS" to TelephonyManager.NETWORK_TYPE_BITMASK_GPRS,
        "EDGE" to TelephonyManager.NETWORK_TYPE_BITMASK_EDGE,
        "GSM" to TelephonyManager.NETWORK_TYPE_BITMASK_GSM,
        "UMTS" to TelephonyManager.NETWORK_TYPE_BITMASK_UMTS,
        "HSDPA" to TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA,
        "HSUPA" to TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA,
        "HSPA" to TelephonyManager.NETWORK_TYPE_BITMASK_HSPA,
        "HSPA+" to TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP,
        "LTE" to TelephonyManager.NETWORK_TYPE_BITMASK_LTE,
        "LTE_CA" to TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA,
        "NR" to TelephonyManager.NETWORK_TYPE_BITMASK_NR
    )
    /** The RATs whose removal forces an LTE/NR detach. */
    private val DETACH_STRIP = listOf("LTE", "LTE_CA", "NR")

    /** Candidate encodings for the shell setter's bitmask argument. */
    enum class Fmt {
        BINARY { override fun encode(v: Long) = v.toString(2) },
        DECIMAL { override fun encode(v: Long) = v.toString() },
        HEX { override fun encode(v: Long) = "0x" + v.toString(16) };
        abstract fun encode(v: Long): String
    }

    private suspend fun readMask(): String =
        ShizukuBridge.exec("cmd phone get-allowed-network-types-for-users")
            .out.lines().lastOrNull()?.trim().orEmpty()

    /**
     * Finds a format the shell both ACCEPTS and ACTS ON.
     *
     * Exit code alone is not evidence: on the reference device the command returns success for an
     * accepted encoding and changes nothing at all — 110 consecutive reads across a full trial
     * showed one value and zero transitions. So each candidate is verified by setting a value that
     * should be observably different and reading it back.
     */
    private suspend fun discoverFormat(current: Long, probe: Long): Fmt? {
        val before = readMask()
        for (f in Fmt.entries) {
            val r = ShizukuBridge.exec(
                "cmd phone set-allowed-network-types-for-users ${f.encode(probe)}")
            val rejected = !r.ok || r.out.contains("No valid", true) ||
                    r.out.contains("Error", true) || r.out.contains("Exception", true)
            if (rejected) continue
            delay(1200)
            val after = readMask()
            // restore whatever we just did before judging
            ShizukuBridge.exec("cmd phone set-allowed-network-types-for-users ${f.encode(current)}")
            delay(600)
            if (after.isNotEmpty() && after != before) return f
        }
        return null
    }

    // ---------------------------------------------------------------- safety

    fun recoverIfNeeded(ctx: Context, scope: CoroutineScope) {
        val v = prefs(ctx).getString(KEY_RESTORE, null) ?: return
        scope.launch {
            val r = ShizukuBridge.exec("cmd phone set-allowed-network-types-for-users $v")
            if (r.ok) prefs(ctx).edit().remove(KEY_RESTORE).apply()
            progress.value = progress.value.copy(
                error = if (r.ok) "Radio configuration restored after an interrupted run."
                        else "COULD NOT RESTORE radio configuration ($v). Fix in Settings."
            )
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- run

    fun run(ctx: Context, scope: CoroutineScope, trials: Int = 10) {
        if (progress.value.running) return
        scope.launch { execute(ctx, trials) }
    }

    private suspend fun execute(ctx: Context, total: Int) {
        progress.value = Progress(running = true, total = total, phase = "checking preconditions")

        if (ShizukuBridge.state.value != ShizukuState.READY) {
            progress.value = Progress(error = "Shizuku is not ready."); return
        }
        // Never during real-time media: a 4-second detach IS the failure we are trying to prevent.
        // The classifier fails safe toward assuming real-time, so an unknown class blocks too.
        ActionTraffic.sample()
        val traffic = ActionTraffic.state.value
        if (traffic.klass.blocksDisruption) {
            progress.value = Progress(
                error = "Blocked: ${traffic.klass.label} is active (slack ${traffic.klass.slackLabel}). " +
                        "A 4-second detach is exactly the failure this is meant to prevent."
            ); return
        }

        val read = ShizukuBridge.exec("cmd phone get-allowed-network-types-for-users")
        if (!read.ok || read.out.isBlank()) {
            progress.value = Progress(error = "Could not read allowed network types: ${read.out}"); return
        }
        val names = read.out.lines().last().trim().split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val full = names.mapNotNull { NAME_TO_BIT[it] }.fold(0L) { a, b -> a or b }
        val reduced = names.filter { it !in DETACH_STRIP }
            .mapNotNull { NAME_TO_BIT[it] }.fold(0L) { a, b -> a or b }

        if (full == 0L) { progress.value = Progress(error = "Unrecognised bitmask: ${read.out}"); return }

        // The setter rejected a decimal with "No valid NETWORK_TYPES_BITMASK" -- a PARSE error,
        // not a permission error. Rather than guess the accepted encoding, probe it: set the
        // CURRENT value (a no-op) in each candidate format and keep whichever is accepted.
        progress.value = progress.value.copy(phase = "probing argument format")
        val fmt = discoverFormat(full, reduced)
        if (fmt == null) {
            progress.value = Progress(
                error = "The shell cannot change allowed network types on this device. " +
                        "Some encodings are accepted and return success, but the value never " +
                        "changes — verified by reading it back. Forced reselection via this " +
                        "lever is unavailable here."
            ); return
        }
        if (reduced == full) { progress.value = Progress(error = "No LTE/NR to strip; nothing to cycle."); return }

        val results = mutableListOf<Trial>()
        try {
            repeat(total) { i ->
                val before = LiveState.sims.value.values.firstOrNull { it.isDataSub }
                progress.value = progress.value.copy(
                    trial = i + 1, phase = "detaching", trials = results.toList())

                // Persist BEFORE touching the radio, so a kill mid-trial still restores.
                prefs(ctx).edit().putString(KEY_RESTORE, fmt.encode(full)).apply()

                val set = ShizukuBridge.exec(
                    "cmd phone set-allowed-network-types-for-users ${fmt.encode(reduced)}")
                if (!set.ok) {
                    results += Trial(i + 1, before?.ci, before?.band, before?.rssnr, null, null,
                        0, null, 0, 0, "actuation refused: ${set.out.take(80)}")
                    prefs(ctx).edit().remove(KEY_RESTORE).apply()
                    progress.value = progress.value.copy(trials = results.toList(),
                        error = "The shell cannot set network types on this device.")
                    return@repeat
                }

                // Verify the detach actually happened rather than trusting the exit code.
                delay(1_000)
                val duringMask = readMask()
                val reallyDetached = duringMask.isNotEmpty() && !duringMask.contains("LTE")

                delay(DETACH_MS - 1_000)
                progress.value = progress.value.copy(phase = "re-attaching")
                ShizukuBridge.exec("cmd phone set-allowed-network-types-for-users ${fmt.encode(full)}")
                prefs(ctx).edit().remove(KEY_RESTORE).apply()

                // Wait for service to come back.
                val t0 = System.currentTimeMillis()
                var landed: SimState? = null
                while (System.currentTimeMillis() - t0 < 60_000) {
                    val s = LiveState.sims.value.values.firstOrNull { it.isDataSub }
                    if (s?.serviceState == "IN_SERVICE" && s.ci != null) { landed = s; break }
                    delay(500)
                }
                val tts = System.currentTimeMillis() - t0

                // Observe the landing.
                progress.value = progress.value.copy(phase = "observing ${OBSERVE_MS / 1000}s")
                val sinrs = mutableListOf<Int>()
                var changes = 0
                var lastCi = landed?.ci
                var dwellStart = System.currentTimeMillis()
                var dwell = 0L
                val obsEnd = System.currentTimeMillis() + OBSERVE_MS
                while (System.currentTimeMillis() < obsEnd) {
                    val s = LiveState.sims.value.values.firstOrNull { it.isDataSub }
                    s?.rssnr?.let { sinrs += it }
                    if (s?.ci != null && s.ci != lastCi) {
                        changes++
                        if (dwell == 0L) dwell = System.currentTimeMillis() - dwellStart
                        lastCi = s.ci
                    }
                    delay(2_000)
                }
                if (dwell == 0L) dwell = OBSERVE_MS

                results += Trial(
                    index = i + 1,
                    beforeCi = before?.ci, beforeBand = before?.band, beforeSinr = before?.rssnr,
                    landedCi = landed?.ci, landedBand = landed?.band,
                    timeToServiceMs = tts,
                    sinrAfterP50 = sinrs.sorted().getOrNull(sinrs.size / 2),
                    cellChangesIn60s = changes,
                    dwellMs = dwell,
                    note = when {
                        !reallyDetached -> "NO-OP: command succeeded but LTE was never removed"
                        landed == null -> "did not regain service within 60 s"
                        else -> ""
                    }
                )
                progress.value = progress.value.copy(trials = results.toList(), phase = "cooldown")
                save(ctx, results)
                if (i < total - 1) delay(COOLDOWN_MS)
            }
        } catch (e: Exception) {
            progress.value = progress.value.copy(error = e.message ?: e.javaClass.simpleName)
        } finally {
            // Belt and braces: restore whatever happened above.
            ShizukuBridge.exec("cmd phone set-allowed-network-types-for-users ${fmt.encode(full)}")
            prefs(ctx).edit().remove(KEY_RESTORE).apply()
            progress.value = progress.value.copy(running = false, phase = "done",
                trials = results.toList())
        }
    }

    private fun save(ctx: Context, trials: List<Trial>) {
        val a = JSONArray()
        trials.forEach { t ->
            a.put(JSONObject().apply {
                put("i", t.index); put("beforeCi", t.beforeCi ?: JSONObject.NULL)
                put("landedCi", t.landedCi ?: JSONObject.NULL)
                put("landedBand", t.landedBand ?: JSONObject.NULL)
                put("ttsMs", t.timeToServiceMs); put("sinrP50", t.sinrAfterP50 ?: JSONObject.NULL)
                put("changes", t.cellChangesIn60s); put("dwellMs", t.dwellMs); put("note", t.note)
            })
        }
        prefs(ctx).edit().putString(KEY_TRIALS, a.toString()).apply()
    }

    /** Landing distribution, which is the whole point of the phase. */
    fun summary(trials: List<Trial>): String {
        if (trials.isEmpty()) return "no trials yet"
        val actuated = trials.filter {
            !it.note.startsWith("actuation refused") && !it.note.startsWith("NO-OP")
        }
        if (actuated.isEmpty())
            return "${trials.size} attempts, 0 actuated — the radio was never cycled.\n" +
                   "NO DATA. This says nothing about the landing distribution.\n" +
                   "The lever is unavailable, which is itself the Phase A result."
        val landed = actuated.mapNotNull { it.landedCi }
        if (landed.isEmpty())
            return "${actuated.size} cycles, none regained service in time. Inconclusive."
        val sites = landed.map { it shr 8 }.toSet()
        val bands = trials.mapNotNull { it.landedBand }.groupingBy { it }.eachCount()
        val distinct = landed.toSet().size
        return buildString {
            append("${actuated.size} actuated · ${distinct} distinct landing cell(s) · ${sites.size} site(s)\n")
            append("bands: " + bands.entries.joinToString(", ") { "B${it.key}×${it.value}" } + "\n")
            append(if (distinct <= 1)
                "DEGENERATE — every cycle returned the same cell. The lever is dead here."
            else
                "NON-DEGENERATE — cycling samples more than one cell, so rerolling has something to win.")
        }
    }
}
