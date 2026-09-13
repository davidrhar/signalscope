package com.signalscope.store

/**
 * Statistics over *time*, not over rows.
 *
 * `radio_sample` is not a uniform sample of anything. The passive callbacks write 15-37 rows a
 * minute while the screen is on; the active poll writes one row every 10 s (5 s or 3 s while
 * moving) and only when that push stream has gone quiet; with the screen off in Doze the poll's
 * timer is deferred to one row every 5-16 minutes. So the row density is a record of the *screen*
 * and of the platform's scheduling, and every median, percentile and "share of" figure computed
 * by counting rows silently weights the minutes somebody was looking at the phone. On the
 * reference data of 2026-09-13 poll rows were 11 % of the data SIM's rows and 31 % of its
 * observed time -- nearly a threefold under-weighting of exactly the unattended hours a
 * monitoring run exists to measure.
 *
 * The fix is the ordinary one for an irregularly sampled step signal: each reading stands for
 * the time until the next reading on the same subscription ("sample and hold"), capped at
 * [CAP_MS], and every statistic is taken over those durations.
 *
 * What this does NOT apply to: probe outcomes. A probe is fired by a timer as a discrete event,
 * and "3 of 40 probes failed" is a rate over attempts, which is what it claims to be. Weighting
 * probes by the gap to the next probe would turn a failure rate into something nobody defined.
 */
object TimeWeight {

    /**
     * The longest a single reading may stand for.
     *
     * 30 s. The collector is designed never to leave a subscription silent for long while it is
     * actually running: the stationary poll ticks every 10 s and writes whenever the push stream
     * has been quiet for 8 s, so a live collector's worst honest gap is one tick plus the 8 s
     * staleness allowance, about 18 s. 30 s allows one deferred tick on top of that. Anything
     * longer is not a long reading -- it is the platform having stopped us looking (Doze deferring
     * the poll overnight, the process frozen, a SIM out of service), and a gap is recorded as a gap
     * by leaving it out of [Weights.observedMs] rather than being handed to whichever reading
     * happened to precede it.
     *
     * Why a cap at all matters is measurable: on the reference data one data-SIM reading on the
     * coverage-layer band was followed by a 199-minute overnight gap. Uncapped, that single row
     * moved the band's share of time from 3 % to 19 %. With the cap anywhere from 10 s to 120 s
     * the share moves by under half a point, so the figure is robust to the exact choice here and
     * not to having no cap.
     */
    const val CAP_MS = 30_000L

    /**
     * Two readings belong to the same boot when their `wallMillis - elapsed` offsets agree to
     * within this.
     *
     * `elapsedNanos` is the join key (`data-model.md` §2) and it restarts from zero at every
     * boot, so ordering a week of rows by it interleaves the boots and invents gaps of a few
     * milliseconds between readings taken days apart. Within one boot the offset is constant
     * apart from NTP corrections, which are normally well under a second; across a reboot it
     * jumps by the whole uptime of the earlier boot. 60 s separates the two with room to spare.
     * A rare large clock correction mid-boot splits one boot into two, which costs one reading's
     * weight at the join -- the safe direction, since it can only lose time, never invent it.
     */
    const val BOOT_SPLIT_MS = 60_000L

    // ------------------------------------------------------------------------------ boots

    /** Assigns every (elapsed, wall) pair to a boot, clustering on the offset between the clocks. */
    class Boots(offsets: LongArray) {
        private val starts: LongArray

        init {
            val s = offsets.copyOf().also { it.sort() }
            val out = ArrayList<Long>()
            var prev: Long? = null
            for (o in s) {
                if (prev == null || o - prev > BOOT_SPLIT_MS) out.add(o)
                prev = o
            }
            starts = out.toLongArray()
        }

        /** Boot index for a reading. Offsets never seen at construction snap to the nearest cluster. */
        fun of(elapsedMs: Long, wallMs: Long): Int {
            if (starts.isEmpty()) return 0
            val o = wallMs - elapsedMs
            var lo = 0; var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (starts[mid] <= o) lo = mid else hi = mid - 1
            }
            return lo
        }

        companion object {
            fun of(elapsedMs: LongArray, wallMs: LongArray) =
                Boots(LongArray(elapsedMs.size) { wallMs[it] - elapsedMs[it] })
        }
    }

    // ------------------------------------------------------------------------------ weights

    /**
     * Per-reading durations for one table read.
     *
     * [ms] is aligned with the input arrays. [observedMs] is the time the readings actually
     * represent and [spanMs] the wall time from each stream's first reading to its last, so
     * [coverage] is the fraction of that wall time this data can speak for. Every derived figure
     * should travel with it: a median over 3 hours of a 20-hour run is a different claim from a
     * median over 20.
     */
    class Weights(
        val ms: LongArray,
        val observedMs: Long,
        val spanMs: Long,
        val observedByStream: Map<Int, Long>,
        val spanByStream: Map<Int, Long>
    ) {
        /** Null when there is no span to be a fraction of -- one reading, or none. */
        val coverage: Double? get() = if (spanMs <= 0) null else observedMs.toDouble() / spanMs

        fun coverageOf(stream: Int): Double? {
            val span = spanByStream[stream] ?: return null
            return if (span <= 0) null else (observedByStream[stream] ?: 0L).toDouble() / span
        }
    }

    /**
     * Weights readings by the capped time until the next reading on the same stream and boot.
     *
     * Input order does not matter. The last reading of each stream in each boot gets zero: how
     * long it held is not known, and guessing [CAP_MS] for it would add up to a phantom half-
     * minute per subscription per reboot.
     *
     * @param elapsedMs monotonic time of each reading, in ms
     * @param wallMs wall time of each reading, used only to separate boots and to measure span
     * @param stream the subscription (or any other key whose readings describe one signal)
     */
    fun weigh(
        elapsedMs: LongArray, wallMs: LongArray, stream: IntArray, capMs: Long = CAP_MS
    ): Weights {
        val n = elapsedMs.size
        val w = LongArray(n)
        if (n == 0) return Weights(w, 0, 0, emptyMap(), emptyMap())
        val boots = Boots.of(elapsedMs, wallMs)
        val boot = IntArray(n) { boots.of(elapsedMs[it], wallMs[it]) }
        val order = (0 until n).sortedWith(
            compareBy<Int>({ stream[it] }, { boot[it] }, { elapsedMs[it] })
        )
        val observed = HashMap<Int, Long>()
        val wallMin = HashMap<Int, Long>()
        val wallMax = HashMap<Int, Long>()
        for (k in order.indices) {
            val i = order[k]
            val s = stream[i]
            wallMin[s] = minOf(wallMin[s] ?: Long.MAX_VALUE, wallMs[i])
            wallMax[s] = maxOf(wallMax[s] ?: Long.MIN_VALUE, wallMs[i])
            if (k + 1 >= order.size) break
            val j = order[k + 1]
            if (stream[j] != s || boot[j] != boot[i]) continue
            val gap = elapsedMs[j] - elapsedMs[i]
            w[i] = gap.coerceIn(0L, capMs)
            observed[s] = (observed[s] ?: 0L) + w[i]
        }
        val span = wallMin.keys.associateWith { (wallMax.getValue(it) - wallMin.getValue(it)).coerceAtLeast(0L) }
        return Weights(w, observed.values.sum(), span.values.sum(), observed, span)
    }

    // ------------------------------------------------------------------------------ statistics

    /**
     * Weighted percentile: the smallest value whose cumulative weight reaches [q] of the total.
     *
     * Null when nothing carries weight -- a percentile of no time is not measured, and reporting
     * 0 dB for it would read as a (bad) measurement. With every weight equal this is the ordinary
     * nearest-rank percentile, so a row-based figure and a time-based one computed through here
     * differ only by their weights.
     */
    fun percentile(values: IntArray, weights: LongArray, q: Double): Int? {
        val idx = values.indices.filter { weights[it] > 0 }.sortedBy { values[it] }
        val total = idx.sumOf { weights[it] }
        if (total <= 0) return null
        val target = q.coerceIn(0.0, 1.0) * total
        var acc = 0L
        for (i in idx) {
            acc += weights[i]
            if (acc >= target) return values[i]
        }
        return values[idx.last()]
    }

    /** [percentile] over a value -> weight histogram. See [addTo] for why bins carry these. */
    fun percentile(hist: Map<Int, Long>, q: Double): Int? {
        val total = hist.values.sum()
        if (total <= 0) return null
        val target = q.coerceIn(0.0, 1.0) * total
        var acc = 0L
        var last: Int? = null
        for (k in hist.keys.sorted()) {
            val v = hist.getValue(k)
            if (v <= 0) continue
            acc += v
            last = k
            if (acc >= target) return k
        }
        return last
    }

    /** Share of weight satisfying something. Null over zero weight: "0 % of no time" is not a finding. */
    fun fraction(part: Long, total: Long): Double? =
        if (total <= 0) null else part.toDouble() / total

    /**
     * Kish's effective sample size, `(Σw)² / Σw²`, for putting an interval on a weighted fraction.
     *
     * A time-weighted fraction must not be given a Wilson interval on the raw row count: forty
     * one-second screen-on rows and one 30 s poll row are not 41 equal votes. For independent
     * readings with fixed weights the variance of the weighted mean is `Σw²·p(1-p) / (Σw)²`, which
     * is the unweighted formula at exactly this n. Readings are autocorrelated in practice, so
     * even this flatters the evidence; it is still far less wrong than the row count.
     */
    fun effectiveN(sumW: Long, sumWSq: Double): Int =
        if (sumW <= 0 || sumWSq <= 0.0) 0
        else Math.floor(sumW.toDouble() * sumW.toDouble() / sumWSq).toInt()

    /**
     * Adds [weight] to [key] in a mergeable histogram.
     *
     * Why histograms: a bin's median must survive the merge walk exactly. Signal readings are
     * whole dB, so a value -> milliseconds map is lossless, two bins combine by summing weights
     * per value, and the merged percentile is the percentile of the union -- never a median of
     * medians, which weights a bin of two readings the same as one of two thousand.
     */
    fun <K> addTo(hist: MutableMap<K, Long>, key: K, weight: Long) {
        hist[key] = (hist[key] ?: 0L) + weight
    }

    /** Sums histograms. The merge rule for every weighted field a bin carries. */
    fun <K> sum(hists: List<Map<K, Long>>): Map<K, Long> {
        val out = HashMap<K, Long>()
        for (h in hists) for ((k, v) in h) addTo(out, k, v)
        return out
    }

    /**
     * The key holding the most time. Ties go to either.
     *
     * Where keys exist but none holds any time -- a bin whose only reading was the last before a
     * gap -- one of them is still returned. These are labels (a PLMN, a RAT, a band), not scores,
     * so naming the one that was seen is honest, and returning null would turn a real PLMN into
     * "—" and quietly exempt the bin from the carrier partition in the merge walk.
     */
    fun <K> dominant(hist: Map<K, Long>): K? =
        hist.entries.filter { it.value > 0 }.maxByOrNull { it.value }?.key ?: hist.keys.firstOrNull()
}
