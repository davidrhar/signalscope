package com.signalscope.store

import android.content.Context

/**
 * The outcome side of a map bin: `probe_result` joined to bins, plus the per-bin cell composition.
 *
 * Why the map needs this at all — `excursion-findings.md` §1 and §2. A stretch of real cellular
 * use at a median SINR any textbook calls bad produced no failures at all, while the same handset
 * with the radio dormant behind Wi-Fi failed cold wake-ups at good signal. Read those sections
 * with their dated correction attached: the cold-versus-warm gap survived a larger sample at
 * roughly half the size first reported, and mostly as added latency rather than outright failure.
 * What did not change is the direction of the argument — signal is not the outcome, and the only
 * table holding a real outcome for the *cellular* bearer is `probe_result`. Until this file
 * existed the map's quality verdict came from `link_event`, which describes whatever holds the
 * default route — usually Wi-Fi.
 *
 * It lives beside `MapBins.kt` rather than inside it only to keep that file readable; it is read
 * from nowhere else.
 */
object MapProbeJoin {

    /**
     * A probe is **cold** when the bearer had no probe traffic on it for this long beforehand.
     *
     * 30 s, and the number is a separator rather than a physical constant. `CellProbe` fires its
     * two probes (v4, v6) back to back, so the second one starts a few hundred milliseconds after
     * the first finishes and rides the connection the first one paid to establish — measured at
     * 457 ms mean for the first of a pair against 168 ms for the second. Cycles, on the other
     * hand, are 45 s apart at their densest (`CollectorService`: 45 s off Wi-Fi, 90 s or 5 min
     * on it). 30 s sits in that gap: it puts every first-of-pair in the cold set and every
     * second-of-pair in the warm set, which is exactly the split the excursion analysis used.
     *
     * It is a lower bound on warmth, not a proof of coldness: the radio can equally have been
     * woken by the user's own traffic moments earlier, and nothing in Phase 1 records that per
     * bin. `radio_sample.dataActivity` carries the dormancy flag and would tighten this, but it is
     * sampled on radio callbacks rather than at probe time, so it answers a slightly different
     * question and is left alone here.
     */
    const val COLD_GAP_MS = 30_000L

    /** `coverage-map.md` §1 class 4: `probe_p90_ms > 1500`. */
    const val SLOW_P90_MS = 1500

    /** `coverage-map.md` §1 class 4: `probe_success < 0.95`. */
    const val SUCCESS_FLOOR = 0.95

    /**
     * A p90 computed from one or two probes is just the maximum of one or two probes. Below this
     * many successful probes the latency limb of class 4 is not evaluated — the failure-rate limb
     * still is, because an observed failure is evidence on its own.
     */
    const val MIN_PROBES_FOR_LATENCY = 3

    /**
     * Failure-rate lower bound above which a bin is route loss rather than "validated but slow".
     * Deliberately read off the *lower* Wilson bound, so a bin needs several real failures to be
     * called red and a single one cannot do it.
     */
    const val FAIL_RATE_LOSS = 0.20

    /** One row of `probe_result`, already reduced to what a bin needs. */
    class ProbeRow(
        /** `elapsedNanos` in ms — the monotonic join key, `data-model.md` §2. */
        val t: Long,
        val wall: Long,
        val ok: Boolean,
        val latencyMs: Int,
        /**
         * Whether a cellular network existed to bind to. `CellProbe` records a row even when
         * `requestNetwork` has not produced one ("no cellular network", netId "—", 0 ms); that is
         * a statement about the device, not about the bearer at this place, so it is counted
         * separately and kept out of every rate.
         */
        val onBearer: Boolean,
        val cold: Boolean,
        val probeType: String,
        val errorCode: String?
    )

    /**
     * Reads `probe_result` through Room's own open helper, the same way [MapBinBuilder] reads
     * `radio_sample` and `link_event`: `store/Db.kt` belongs to another agent this phase, so no
     * DAO method is added for this.
     *
     * Coldness is decided here, in one pass, because it depends on the row order and not on the
     * bin. The gap measured is *previous probe's end to this probe's start* — `elapsedNanos` is
     * stamped when the probe finishes, so the start is `t - latencyMs`. Without that correction a
     * 5 s first-of-pair would push its own pair partner past a 30 s boundary set from starts.
     */
    fun read(ctx: Context): List<ProbeRow> {
        val db = Db.get(ctx).openHelper.readableDatabase
        val out = ArrayList<ProbeRow>()
        var lastEnd: Long? = null
        db.query(
            "SELECT elapsedNanos, wallMillis, netId, probeType, outcome, latencyMs, errorCode " +
                "FROM probe_result ORDER BY elapsedNanos ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val t = c.getLong(0) / 1_000_000L
                val netId = if (c.isNull(2)) "" else c.getString(2)
                val latency = c.getInt(5)
                val onBearer = netId.isNotEmpty() && netId != "—"
                val prev = lastEnd
                val cold = onBearer && (prev == null || (t - latency) - prev > COLD_GAP_MS)
                out.add(
                    ProbeRow(
                        t = t,
                        wall = c.getLong(1),
                        ok = !c.isNull(4) && c.getString(4) == "OK",
                        latencyMs = latency,
                        onBearer = onBearer,
                        cold = cold,
                        probeType = if (c.isNull(3)) "?" else c.getString(3),
                        errorCode = if (c.isNull(6)) null else c.getString(6)
                    )
                )
                // Only a probe that actually reached the bearer leaves it warm.
                if (onBearer) lastEnd = t
            }
        }
        return out
    }

    /**
     * Per-bin probe tallies. Counts only, so a merge is a sum and never an average of averages.
     *
     * Counted per PROBE, not weighted by time, and that is deliberate -- see [TimeWeight]. Radio
     * readings describe a continuous signal sampled at a rate the screen controls, so they are
     * weighted by the time each one stands for. A probe is a discrete attempt fired by a timer,
     * and its failure rate is failures over attempts, which is exactly what is claimed.
     */
    class Probes {
        var n = 0
        var fail = 0
        var noBearer = 0
        /**
         * Probes the app could not even attempt, because binding a socket to the cellular
         * network was refused. Neither a success nor a failure: an absence of measurement. They
         * are counted rather than dropped, because 248 of them counted as failures is what
         * painted 21 bins as route loss on a network that was working.
         */
        var instrument = 0
        var cold = 0
        var coldFail = 0

        /**
         * Latencies of the probes that SUCCEEDED. A failure's `latencyMs` is the timeout it hit
         * (6 s, or the 25 s outer bound), which is a property of `CellProbe`'s configuration and
         * would drag every percentile toward it. Failures are counted, never averaged in.
         */
        val okLatency = ArrayList<Int>()

        /** Failure reasons, grouped by [normaliseError] so a handful of them add up to something. */
        val errors = HashMap<String, Int>()

        fun add(p: ProbeRow) {
            // Before anything else: an instrument fault says nothing about this place.
            val kind = com.signalscope.collect.CellProbe.kindOf(p.probeType, p.errorCode)
            if (kind == com.signalscope.collect.CellProbe.Kind.INSTRUMENT) { instrument++; return }
            // A stall summary is not a sample of this place: the stall is already here as the
            // failed probe that opened it, and the summary was written because it failed.
            if (kind == com.signalscope.collect.CellProbe.Kind.RECOVERY) return
            if (!p.onBearer) { noBearer++; return }
            n++
            if (p.ok) {
                okLatency.add(p.latencyMs)
            } else {
                fail++
                val k = normaliseError(p.errorCode)
                errors[k] = (errors[k] ?: 0) + 1
            }
            if (p.cold) { cold++; if (!p.ok) coldFail++ }
        }
    }

    /**
     * `CellProbe` stores the exception class plus up to 60 characters of its message, so the raw
     * strings are nearly unique and never group. Cutting at the first colon leaves the part that
     * identifies the failure — "SSLHandshakeException", "ConnectException", "probe timed out" —
     * which is the level a bin can actually say something about.
     */
    fun normaliseError(raw: String?): String =
        raw?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() } ?: "unspecified"

    class Attribution(val perBin: Map<Long, Probes>, val unlocated: Int)

    /**
     * Attributes each probe to a bin by time, exactly as radio samples are attributed: the caller
     * supplies [binOf], which places a probe with `MapBinBuilder.Locator` -- a bracketing pair of
     * fixes in one bin, or the nearest fix inside its speed-scaled window -- or returns null. The
     * whole row is passed rather than a timestamp so the locator can tell boots apart by the wall
     * clock.
     *
     * Attribution is to the **bin**, not to a subscription. `probe_result` has no `subId` column
     * and deliberately no cell column either (see `store/Db.kt`), so probe outcomes cannot be
     * split between two SIMs sharing a place. They are the outcome of whichever bearer held the
     * cellular request, which is the data subscription. The bin sheet says so rather than
     * implying the number is per-SIM.
     */
    fun attribute(probes: List<ProbeRow>, binOf: (ProbeRow) -> Long?): Attribution {
        val per = HashMap<Long, Probes>()
        var unlocated = 0
        for (p in probes) {
            val bin = binOf(p)
            if (bin == null) { unlocated++; continue }
            per.getOrPut(bin) { Probes() }.add(p)
        }
        return Attribution(per, unlocated)
    }

    /**
     * Nearest-rank percentile over an already-sorted list. Returns null for an empty list rather
     * than 0: a latency of zero and a latency never measured are opposite findings.
     */
    fun percentile(sorted: List<Int>, q: Double): Int? {
        if (sorted.isEmpty()) return null
        val i = Math.round(q * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[i]
    }

    // =================================================================================
    //  Cell composition
    // =================================================================================

    /**
     * One serving cell's share of a bin.
     *
     * The point of carrying this is that "eight serving cells" and "two masts" are different
     * diagnoses. The excursion saw 8 cells across 2 sites (two eNBs) on bands 40 and 8;
     * without the decomposition that reads as chaos, and with it, it reads as two masts and a
     * handful of sectors.
     */
    data class CellShare(
        val rat: String,
        val ci: Long?,
        val pci: Int?,
        val band: Int?,
        /** Readings from this cell. Evidence only; shares are over [ms]. */
        val samples: Int,
        /** Time those readings stand for ([TimeWeight]). Summed on merge, like [samples]. */
        val ms: Long = 0L
    ) {
        /**
         * LTE packs the site and the sector into one Cell Identity: `CI = eNodeB << 8 | sector`.
         * The same decomposition is used on the Live screen (`MainActivity.CellCard`).
         *
         * Only for LTE, and only inside the 28-bit ECI range. NR's NCI has a configurable gNB-ID
         * length (22–32 bits), so the split point is not knowable from the identity alone and is
         * not guessed at here. Under NSA the serving identity is still the LTE anchor's, which is
         * why `rat` reads LTE there and the decomposition remains correct.
         */
        val enb: Long? get() = decomposable()?.let { it shr 8 }
        val sector: Int? get() = decomposable()?.let { (it and 0xFF).toInt() }

        private fun decomposable(): Long? =
            ci?.takeIf { rat.startsWith("LTE") && it in 0..0x0FFFFFFFL }

        /** "12345/33 · B40", "ci … · n78", or "pci 268" where the identity does not decompose. */
        val label: String
            get() {
                val e = enb
                val head = when {
                    e != null -> "$e/$sector"
                    ci != null -> "ci $ci"
                    pci != null -> "pci $pci"
                    else -> "unknown cell"
                }
                // The prefix follows the RAT: B40 and n40 are different carriers on the same
                // number, and printing "B" for both misreports every NR cell.
                return head + (MapBinBuilder.bandLabel(rat, band)?.let { " · $it" } ?: "")
            }
    }

    /**
     * Sums shares of the same cell across children -- both the reading count and the time, so the
     * merged share is exact. Descending by time, then readings, so [0] is dominant.
     */
    fun mergeCells(lists: List<List<CellShare>>): List<CellShare> {
        val by = HashMap<List<Any?>, Int>()
        val ms = HashMap<List<Any?>, Long>()
        val meta = HashMap<List<Any?>, CellShare>()
        for (l in lists) for (c in l) {
            val k = listOf(c.rat, c.ci, c.pci, c.band)
            by[k] = (by[k] ?: 0) + c.samples
            ms[k] = (ms[k] ?: 0L) + c.ms
            meta[k] = c
        }
        return by.entries
            .map { (k, n) -> meta.getValue(k).copy(samples = n, ms = ms[k] ?: 0L) }
            .sortedWith(compareByDescending<CellShare> { it.ms }.thenByDescending { it.samples })
    }
}
