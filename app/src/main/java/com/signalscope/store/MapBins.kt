package com.signalscope.store

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Position fixes, already binned.
 *
 * `data-model.md` §5: no raw coordinate is ever written. The row carries the bin, the resolution
 * the fix's accuracy justified, and the accuracy itself — nothing that reconstructs a trace at
 * finer detail than the bin. Doing it at write time rather than export time means the database
 * never *contains* a precise movement trace, so there is nothing to leak later.
 *
 * This lives in its own database file rather than in `Db.kt`, because `Db.kt` belongs to another
 * agent this phase. The right home is `radio_sample.positionBinId` as the schema specifies; see
 * the note on [MapBinBuilder.build] for what the split costs.
 */
data class MapFix(
    val id: Long = 0,
    /**
     * When the position was *measured*, on the `elapsedRealtimeNanos` clock — the join key, per
     * `data-model.md` §2. Rows written before 2026-09-13 carry the time the fix was *delivered*
     * instead, which for a cached last-known fix could be hours later; see [MapLocationCollector].
     */
    val elapsedNanos: Long,
    /** Wall time of the same instant, derived from [elapsedNanos] so the two clocks agree per boot. */
    val wallMillis: Long,
    val binId: Long,
    val resolution: Int,
    val accuracyM: Float,
    val speedMps: Float?
)

// =====================================================================================
//  Outcome classes, causes, colours — coverage-map.md §1
// =====================================================================================

/**
 * Seven mutually exclusive outcome classes, evaluated in order, first match wins.
 *
 * Ordering class 1 before class 5 is the whole thesis restated as a switch statement: a bin that
 * loses the route is red even at -77 dBm.
 */
enum class Outcome(val code: Int, val label: String, val short: String, val colour: String) {
    UNSURVEYED(0, "Not enough evidence", "THIN", "#1b212b"),
    ROUTE_LOSS(1, "Route loss", "ROUTE LOSS", "#b03a42"),
    ANCHOR(2, "Anchor unstable", "ANCHOR", "#b8862f"),
    RESELECT(3, "Reselection churn — no measured penalty", "RESELECT", "#5b7fa6"),
    SLOW(4, "Validated but slow", "SLOW", "#a8642c"),
    NSA(5, "5G NSA stable", "5G NSA", "#6b4bb8"),
    LTE(6, "LTE solid", "LTE SOLID", "#2f9e6b");

    companion object {
        fun of(code: Int) = entries.first { it.code == code }
    }
}

/** Severity ranking, used only by the anti-erasure guard ("no child contradicts"). */
/**
 * How bad each outcome class is, for the merge walk and for the legend's ordering.
 *
 * Class 3 (reselection ping-pong) was ranked ABOVE both healthy classes on the assumption that
 * churn is harm. It was measured on 2026-09-13 and it is not: across 144 matched pairs, a
 * ping-pong return was followed by an adverse outcome 1.4 % of the time [0.4-4.9] against 1.5 %
 * for an ordinary cell change of the same churn intensity [0.3-8.2] -- intervals almost entirely
 * overlapping, zero validated-route losses and zero service drops beside any change in either
 * arm, and successful-probe latency actually slightly BETTER inside ping-pong windows (p50 132 ms
 * against 164 ms). Nine analysis variants were swept and every one overlapped.
 *
 * So it is ranked level with the healthy classes rather than above them: still worth naming,
 * because it describes something real the phone is doing, but not worth outranking a bin that is
 * actually failing. The study can only exclude a harm rate above about 4.9 %, so this is
 * "no measured penalty", not "proven harmless", and the class is kept rather than deleted.
 */
private val SEVERITY = mapOf(0 to 0, 6 to 1, 5 to 1, 3 to 1, 2 to 3, 4 to 3, 1 to 4)

val CAUSES = mapOf(
    1 to "Radio coverage loss",
    2 to "5G NSA anchor thrash",
    3 to "Cell reselection ping-pong (no measured penalty)",
    4 to "PS attach / PDN failure",
    5 to "Data SUSPENDED",
    6 to "Connected-but-broken path",
    7 to "Wi-Fi <-> cellular thrash",
    8 to "Device-side",
    9 to "Carrier-side"
)

// =====================================================================================
//  The bin
// =====================================================================================

/**
 * One published bin. Mirrors `bin_rollup` in `data-model.md` §3, plus the record of the merge
 * that `adaptive-aggregation.md` requires every published bin to carry.
 */
data class Bin(
    val id: Long,
    val res: Int,
    /**
     * `bin_rollup.subId`. Bins are keyed per subscription and **never merged across carriers** —
     * the two SIMs on this handset sit on different PLMNs and different bands, and averaging them
     * would produce a bin describing no network that exists.
     */
    val subId: Int,
    /** Samples in this cell that belong to the *other* subscription, and are not in this bin. */
    val otherSubSamples: Int,
    /**
     * Rows behind this bin. An *evidence* count -- it gates [MapBinBuilder.N_LOCAL] and the crowd
     * threshold, which ask "how many readings", and it is never the denominator of a statistic.
     * Every share and percentile below is over [observedMs] instead; see [TimeWeight] for why.
     */
    val nObs: Int,
    /** A SET, never a count. Union on merge; cardinality at the end. */
    val contributors: Set<String>,
    /**
     * Time these readings stand for, and the sum of the squared per-reading weights.
     *
     * Both are sums over readings, so a merge adds them and every fraction below is recomputed
     * from the merged totals: exact, never an average of averages. The squares exist only for
     * [effectiveN].
     */
    val observedMs: Long,
    val observedMsSq: Double,
    /** Of [observedMs], time the **default route** — whatever held it — was validated. */
    val validatedMs: Long,
    /** Default route was cellular, for this many readings and this much time. */
    val cellRouteSamples: Int,
    val cellRouteMs: Long,
    /** Of [cellRouteMs], time it was validated. */
    val cellValidatedMs: Long,
    /** Readings / time whose default route was Wi-Fi — what [validatedFrac] usually describes. */
    val wifiRouteSamples: Int,
    val wifiRouteMs: Long,
    val suspendedMs: Long,
    /** Cellular-bound probes attributed to this bin. 0 = the bearer was never exercised here. */
    val probeN: Int,
    val probeFail: Int,
    /** Probes that could not be attempted at all — an app fault, not a property of this bin. */
    val probeInstrument: Int = 0,
    /** Probes that found no cellular network at all: about the device, not about this place. */
    val probeNoBearer: Int,
    /** Sorted latencies of the probes that succeeded. Failures carry a timeout, not a latency. */
    val probeOkLatencyMs: List<Int>,
    /** Probes that arrived on a bearer with no probe traffic in the previous 30 s. */
    val coldProbeN: Int,
    val coldProbeFail: Int,
    /** Why the failures failed, grouped and counted. Empty where nothing failed. */
    val probeErrors: Map<String, Int>,
    /** Which sites/sectors served this bin and in what proportion of time; dominant first. */
    val cells: List<MapProbeJoin.CellShare>,
    /** Default-route transport changes attributed to this bin. A count, so it sums. */
    val flaps: Int,
    /** Serving-cell changes seen inside this bin. A count, so it sums. */
    val cellChanges: Int,
    /** Null where no NR leg exists. An absent measurement is not a perfect score. */
    val nrAnchor: Double?,
    /** Σ(speed × weight) over readings whose fix carried a speed, and the weight behind it. */
    val speedMpsMs: Double,
    val speedMs: Long,
    /** Value -> milliseconds. Histograms rather than medians so a merge stays exact. */
    val rsrpHist: Map<Int, Long>,
    val sinrHist: Map<Int, Long>,
    /** RAT, band label ("B40", "n78") and PLMN -> milliseconds. The label carries the RAT. */
    val ratMs: Map<String, Long>,
    val bandMs: Map<String, Long>,
    val plmnMs: Map<String, Long>,
    val topCause: Int,
    val causeShare: Double,
    val cls: Int,
    /** res-10-equivalent units of ground actually surveyed under this cell. */
    val childUnits: Long,
    val leafCount: Int,
    val mergeReason: String,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long
) {
    /** `anchor_stability` = min of whichever of the two anchor metrics are non-null. */
    val anchorStability: Double get() = nrAnchor?.let { min(it, transportAnchor) } ?: transportAnchor
    val contributorCount: Int get() = contributors.size
    /** Would this bin clear the crowdsourcing gate if it were ever uploaded? */
    val publishable: Boolean get() = contributorCount >= MapBinBuilder.K_ANON && nObs >= MapBinBuilder.N_CROWD

    // ------------------------------------------------------------------ time-weighted signal
    //
    // Derived from the carried sums on every read, so a merged bin can never hold a stale or
    // averaged copy of any of them.

    /** Kish effective n over the time weights — the n for any interval on a fraction here. */
    val effectiveN: Int get() = TimeWeight.effectiveN(observedMs, observedMsSq)

    /**
     * Share of observed time the **default route** — whatever held it — was validated.
     *
     * Kept, because it is the field `coverage-map.md` specifies and it is genuinely the headline
     * for the route the device was using. It is *not* a statement about the cellular bearer, and
     * for most bins it is not even about cellular: while Wi-Fi holds the default route this reads
     * ~1.00 no matter what the modem is doing, which is exactly the instrument gap
     * `excursion-findings.md` found in the probe path. Read [cellValidatedFrac] for the bearer
     * this bin claims to describe. 0.0 over no observed time only because the merge walk's
     * Wilson test needs a number; [effectiveN] is then 0 and the interval is the whole of [0,1].
     */
    val validatedFrac: Double get() = TimeWeight.fraction(validatedMs, observedMs) ?: 0.0

    val suspendFrac: Double get() = TimeWeight.fraction(suspendedMs, observedMs) ?: 0.0

    /** Observed minutes, floored at one second so a rate over a lone reading is finite. */
    private val observedMin: Double get() = max(observedMs / 60_000.0, 1.0 / 60.0)

    /**
     * 1 − (default-route flaps per observed minute / ceiling).
     *
     * Over observed time rather than first-to-last timestamp: a bin crossed on Monday and again
     * on Friday is not four days of stable route, and a rate computed over that span would read
     * as near-perfect stability on almost no evidence.
     */
    val transportAnchor: Double
        get() = (1.0 - (flaps / observedMin) / MapBinBuilder.FLAP_CEILING).coerceIn(0.0, 1.0)

    val reselectRate: Double get() = cellChanges / observedMin

    /**
     * Time-weighted mean speed. 0 where no fix carried a speed -- read [speedKnown] before
     * treating a 0 as stillness, because on a network-provider fix it is usually absence.
     */
    val speedKph: Double get() = if (speedMs <= 0) 0.0 else speedMpsMs / speedMs * 3.6
    val speedKnown: Boolean get() = speedMs > 0

    val rsrpP50: Int? get() = TimeWeight.percentile(rsrpHist, 0.50)
    val sinrP50: Int? get() = TimeWeight.percentile(sinrHist, 0.50)

    val rat: String get() = TimeWeight.dominant(ratMs) ?: "UNKNOWN"
    /** Dominant band by time, already labelled for its RAT. Null where no band was recorded. */
    val band: String? get() = TimeWeight.dominant(bandMs)
    val plmn: String get() = TimeWeight.dominant(plmnMs) ?: "—"

    // ------------------------------------------------------------------ measured outcome
    //
    // Every one of these is null or false when the measurement is absent, never a good value.
    // That is the whole point of the rewrite: a bin recorded behind Wi-Fi has a beautiful
    // validatedFrac and no evidence whatsoever about the cellular bearer.

    /**
     * Was anything actually riding on the cellular bearer here?
     *
     * Two independent kinds of evidence count: the default route was cellular for at least one
     * sample, or a cellular-bound probe landed in this bin. Without either, the bin knows what
     * the modem *heard* and nothing about whether data *worked*.
     */
    val hasCellEvidence: Boolean get() = cellRouteSamples > 0 || probeN > 0

    /**
     * `validated_frac` restricted to the time cellular actually carried the route. Null = not
     * measured, including the edge where cellular-route readings exist but stand for no time.
     */
    val cellValidatedFrac: Double? get() = TimeWeight.fraction(cellValidatedMs, cellRouteMs)

    /**
     * Probe rates are per PROBE, deliberately, and must stay that way.
     *
     * A probe is a discrete attempt fired by a timer, not a reading of a continuous signal, so
     * "failures / attempts" is exactly the quantity being claimed. Weighting probes by the time to
     * the next probe -- the fix [TimeWeight] applies to radio readings -- would make a failure
     * followed by a long quiet spell count for more than one followed by a retry, which describes
     * the scheduler and not the network.
     */
    val probeFailFrac: Double? get() = if (probeN == 0) null else probeFail.toDouble() / probeN

    /** Wilson interval on the failure rate. `[0,1]` when there is nothing to constrain it. */
    val probeFailCi: DoubleArray get() = MapBinBuilder.wilson(probeFailFrac ?: 0.0, probeN)

    val probeP50Ms: Int? get() = MapProbeJoin.percentile(probeOkLatencyMs, 0.50)
    val probeP90Ms: Int? get() = MapProbeJoin.percentile(probeOkLatencyMs, 0.90)

    /**
     * Cold-wake-up success rate: the single number this bin exists to carry.
     *
     * `excursion-findings.md` §2 measured a dormant bearer failing cold probes at good signal
     * where an in-use one did not, and its dated correction then halved that gap and recast it as
     * mostly latency. Either way the cost falls on the idle→connected transition, so the rate
     * closest to what a user feels is this one and not the overall probe rate, which is diluted by
     * the warm second-of-pair probes that never pay promotion.
     */
    val coldSuccessFrac: Double?
        get() = if (coldProbeN == 0) null
        else (coldProbeN - coldProbeFail).toDouble() / coldProbeN

    val coldSuccessCi: DoubleArray
        get() = MapBinBuilder.wilson(coldSuccessFrac ?: 0.0, coldProbeN)

    /** The most common failure reason here, with how many of the failures it accounts for. */
    val probeTopError: Pair<String, Int>?
        get() = probeErrors.maxByOrNull { it.value }?.let { it.key to it.value }

    /** Total samples behind [cells]. Evidence count only; shares are over [cellMs]. */
    val cellSamples: Int get() = cells.sumOf { it.samples }

    /** Total time behind [cells]; the denominator for each share. */
    val cellMs: Long get() = cells.sumOf { it.ms }
}

/** Everything the Map screen needs, computed once off the UI thread. */
data class MapModel(
    val bins: List<Bin>,
    val geoJson: String,
    val leafCount: Int,
    val radioRows: Int,
    val linkRows: Int,
    val fixRows: Int,
    val probeRows: Int,
    /** Samples no position fix could honestly place. See [MapBinBuilder.Locator]. */
    val unlocated: Int,
    /** Probes no fix could place — the same honesty, on the outcome side. */
    val probeUnlocated: Int,
    val currentBin: Long?,
    val buildMs: Long,
    val contrast: Contrast,
    val resCounts: Map<Int, Int>,
    val clsCounts: Map<Int, Int>,
    val error: String? = null,
    /**
     * Time the radio readings stand for, the part of it that landed in a bin, and the wall time
     * they were spread over (summed per subscription). The time-weighted counterparts of
     * [radioRows] and [unlocated]; see [TimeWeight].
     */
    val observedMs: Long = 0,
    val locatedMs: Long = 0,
    val spanMs: Long = 0,
    /**
     * Bins that came from `bin_agg` rather than from this pass over the raw rows.
     *
     * Every other counter in this model describes the *live* build -- how many samples it placed,
     * how many fixes it had. Once persisted aggregates were merged in, those counters stopped
     * describing what the map draws: after a day of collecting the strip read "14/22379 samples
     * binned, 0 % of time, 1 fix" while fifty-two bins were on screen, because the day was on disk
     * and only the last few minutes were live. A status line that under-reports its own map by
     * three orders of magnitude is the failure this project keeps writing rules against, so the
     * restored count is carried and shown rather than left implicit.
     */
    val restoredBins: Int = 0
) {
    val isEmpty: Boolean get() = bins.isEmpty()

    /** Fraction of the wall time the readings represent at all. Null with nothing to span. */
    val radioCoverage: Double? get() = TimeWeight.fraction(observedMs, spanMs)

    /** Fraction of observed time that has a bin. Null with no observed time. */
    val locatedFrac: Double? get() = TimeWeight.fraction(locatedMs, observedMs)

    /** Bins with enough samples to classify but no cellular-bound evidence to classify them by. */
    val notMeasured: Int get() = bins.count { it.nObs >= MapBinBuilder.N_LOCAL && !it.hasCellEvidence }
    val coldProbes: Int get() = bins.sumOf { it.coldProbeN }
    val coldProbeFailures: Int get() = bins.sumOf { it.coldProbeFail }

    companion object {
        fun empty(err: String? = null) = MapModel(
            emptyList(), """{"type":"FeatureCollection","features":[]}""",
            0, 0, 0, 0, 0, 0, 0, null, 0, Contrast(0, 0, 0, 0, 0, null), emptyMap(), emptyMap(), err
        )
    }
}

/** The RSRP-versus-outcome disagreement, quantified. This is the exhibit. */
data class Contrast(
    val surveyed: Int,
    val thin: Int,
    val bad: Int,
    val badFullBars: Int,
    val badWeak: Int,
    val medianBadRsrp: Int?
)

// =====================================================================================
//  Build
// =====================================================================================

object MapBinBuilder {

    const val RULE_VERSION = "adaptive/v1+areal"

    /** `coverage-map.md` §3. The crowd gate — evaluated, displayed, and *not* applied locally. */
    const val K_ANON = 5
    const val N_CROWD = 30

    /**
     * The local gate. A personal map has exactly one contributor by construction, so applying
     * k >= 5 to it would paint every bin grey forever and ship a map that says nothing on the
     * device that measured it. `coverage-map.md` is explicit that the k-threshold governs
     * *publication* ("it stays grey for everyone" is about the shared layer) and that the user's
     * own bins "are shown to the user from local data anyway". So: classify locally on evidence,
     * and mark separately whether the bin would ever clear the crowd gate.
     */
    const val N_LOCAL = 8

    // ---------------------------------------------------------------- the match window
    //
    // A sample is placed by position fixes taken near it in time, and "near" has to be measured
    // in metres, not seconds. The window used to be a flat 120 s, which at 20 m/s is 2.4 km -- a
    // sample taken on one side of a town painted onto a bin on the other. So the window is now the
    // time the phone needs to cover [DISPLACEMENT_BUDGET_M] at the speed its fix reported, held
    // between a moving floor and a stationary ceiling, plus one rule that needs no speed at all.

    /**
     * How far a sample may have been from its fix's position before the pairing is dishonest.
     *
     * 50 m: under the ~66 m edge of the finest bin this map stores, so a sample paired inside the
     * budget lands in the bin it was taken in or an immediate neighbour, never further.
     */
    const val DISPLACEMENT_BUDGET_M = 50.0

    /**
     * The floor, used for any fix that reports movement or reports no speed at all.
     *
     * 10 s, half of `MapLocationCollector`'s 20 s request interval: while fixes are flowing every
     * sample is within 10 s of one, so a tighter floor would discard samples for the fix cadence's
     * sake rather than for accuracy. At 20 m/s that bounds the error at 200 m. An unknown speed
     * gets the floor, not the ceiling: most network-provider fixes carry no speed, and absent
     * evidence of stillness is not stillness.
     */
    const val MOVING_WINDOW_MS = 10_000L

    /**
     * The ceiling, reached only by a fix that reports genuine stillness -- below
     * [DISPLACEMENT_BUDGET_M] per two minutes, about 0.4 m/s, which is under the drift a fused
     * fix shows standing still. 120 s is the old flat window, now kept for the one case where it
     * was ever honest.
     */
    const val STATIONARY_WINDOW_MS = 120_000L

    /**
     * A sample between two fixes in the SAME bin is in that bin, if the fixes are this close.
     *
     * This needs no speed, which matters because most fixes have none. `MapLocationCollector`
     * writes a fix whenever the bin changes, and writes the last fix in the old bin as it leaves,
     * so two consecutive rows in one bin mean every fix delivered between them was in that bin
     * too. The only thing that can break it is leaving and returning while no fix was delivered,
     * which the cap bounds: three minutes is three of the collector's 60 s write gaps, and a
     * longer bracket means updates were paused (location gated off on Wi-Fi, or deferred), where
     * presence across the pause would be an assumption.
     */
    const val BRACKET_MAX_MS = 180_000L

    /** Areal coverage: a parent is only claimed when this much of it was actually surveyed. */
    const val MIN_PARENT_COVERAGE = 0.35

    /** Default-route transport changes per minute that would take the anchor metric to zero. */
    const val FLAP_CEILING = 4.0

    /**
     * The platform's default LTE bar thresholds (`CarrierConfigManager`
     * `lte_rsrp_thresholds_int_array`), which this project's reference handset also reported;
     * params=1, so the bar is RSRP alone. A carrier that overrides them draws its bars differently,
     * and nothing here can read the override without privilege — see `data-model.md` §8.
     */
    val LTE_BAR_THRESHOLDS = intArrayOf(-128, -118, -108, -98)

    fun carrierBars(rsrp: Int?): Int {
        if (rsrp == null) return 0
        var b = 0
        for (t in LTE_BAR_THRESHOLDS) if (rsrp >= t) b++
        return b
    }

    // ---------------------------------------------------------------- raw reads

    private class Radio(
        val t: Long, val wall: Long, val subId: Int, val rat: String, val ci: Long?,
        val pci: Int?, val band: Int?, val plmn: String, val rsrp: Int?, val sinr: Int?
    )

    private class Link(
        val t: Long, val wall: Long, val transport: String, val validated: Boolean,
        val notSuspended: Boolean
    )

    /**
     * Reads the collector's tables through Room's own open helper rather than through a DAO.
     *
     * `CollectorDao` exposes only counts and `store/Db.kt` is another agent's file this phase, so
     * the query lives here. It is the same database connection Room is already using, so there is
     * no second writer and no lock contention. Only columns the shipped schema has always had are
     * named, so a migration adding columns elsewhere cannot break this read.
     */
    /**
     * @param sinceWall when set, only samples at or after this wall time.
     *
     * The aggregator runs every few minutes and needs a few minutes of rows; without a bound it
     * would read the entire table -- hundreds of thousands of rows at full retention -- on a timer,
     * in the background, forever. The interactive build still reads everything, because it has to
     * place whatever the fix buffer covers and the user is looking at the result.
     */
    private fun readRadio(ctx: Context, sinceWall: Long? = null): List<Radio> {
        val db = Db.get(ctx).openHelper.readableDatabase
        val out = ArrayList<Radio>()
        val sql = "SELECT elapsedNanos, wallMillis, subId, rat, servingCi, servingPci, bandNum, " +
            "mcc, mnc, rsrp, rssnr FROM radio_sample" +
            (if (sinceWall != null) " WHERE wallMillis >= $sinceWall" else "") +
            " ORDER BY elapsedNanos ASC"
        db.query(sql).use { c ->
            while (c.moveToNext()) {
                val mcc = if (c.isNull(7)) "" else c.getString(7)
                val mnc = if (c.isNull(8)) "" else c.getString(8)
                out.add(
                    Radio(
                        t = c.getLong(0) / 1_000_000L,
                        wall = c.getLong(1),
                        subId = c.getInt(2),
                        rat = if (c.isNull(3)) "UNKNOWN" else c.getString(3),
                        ci = if (c.isNull(4)) null else c.getLong(4),
                        pci = if (c.isNull(5)) null else c.getInt(5),
                        band = if (c.isNull(6)) null else c.getInt(6),
                        plmn = if (mcc.isEmpty()) "—" else "$mcc-$mnc",
                        rsrp = if (c.isNull(9)) null else c.getInt(9),
                        sinr = if (c.isNull(10)) null else c.getInt(10)
                    )
                )
            }
        }
        return out
    }

    private fun readLinks(ctx: Context): List<Link> {
        val db = Db.get(ctx).openHelper.readableDatabase
        val out = ArrayList<Link>()
        db.query(
            "SELECT elapsedNanos, wallMillis, transport, validated, notSuspended FROM link_event " +
                "WHERE isDefault = 1 ORDER BY elapsedNanos ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Link(
                        t = c.getLong(0) / 1_000_000L,
                        wall = c.getLong(1),
                        transport = if (c.isNull(2)) "?" else c.getString(2),
                        validated = c.getInt(3) != 0,
                        notSuspended = c.getInt(4) != 0
                    )
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- band labels

    /**
     * "B40" for LTE, "n78" for NR, "band 5" where the RAT does not say which numbering applies.
     *
     * The number alone is ambiguous by construction: 3GPP numbers LTE and NR bands independently,
     * and several collide on purpose (B40 and n40 are the same spectrum on different RATs), so
     * printing every band as "B" misreports every NR cell. The prefix is taken from the RAT the
     * row recorded. Under NSA the serving identity is the LTE anchor's, so a RAT reading NR_NSA
     * still labels its band "B"; only standalone NR ("NR", "NR_SA") is "n". Any other RAT --
     * WCDMA, GSM, or none recorded -- gets the plain word rather than a guess.
     */
    fun bandLabel(rat: String?, band: Int?): String? {
        if (band == null) return null
        val r = rat ?: ""
        return when {
            r == "NR" || r.startsWith("NR_SA") -> "n$band"
            r.startsWith("LTE") || r.startsWith("NR_NSA") -> "B$band"
            else -> "band $band"
        }
    }

    // ---------------------------------------------------------------- placing samples

    /**
     * Places a timestamp in a bin using the position fixes around it, or declines to.
     *
     * Per boot, because `elapsedNanos` restarts at every boot: without the split, a sample from
     * the first hour after a reboot would find its nearest fix in the first hour of the *previous*
     * boot, days earlier. See [TimeWeight.Boots].
     *
     * Two rules, first match wins:
     *  1. **Bracketed.** The fix before and the fix after are in the same bin and no more than
     *     [BRACKET_MAX_MS] apart: the sample is in that bin. No speed is needed.
     *  2. **Nearest within its window.** Otherwise the nearer of the two, if the sample falls
     *     inside that fix's own speed-scaled window ([windowFor]).
     *
     * Anything else is unlocated. Counted and reported by the caller, never forced onto a distant
     * bin -- a sample in the wrong bin is worse than a sample in none, because it is invisible.
     */
    class Locator(fixes: List<MapFix>, private val boots: TimeWeight.Boots) {

        class Hit(val binId: Long, val speedMps: Float?)

        private val byBoot: Map<Int, List<MapFix>> = fixes
            .groupBy { boots.of(it.elapsedNanos / 1_000_000L, it.wallMillis) }
            .mapValues { (_, v) -> v.sortedBy { it.elapsedNanos } }
        private val times: Map<Int, LongArray> =
            byBoot.mapValues { (_, v) -> LongArray(v.size) { v[it].elapsedNanos / 1_000_000L } }

        fun locate(t: Long, wall: Long): Hit? {
            val boot = boots.of(t, wall)
            val fs = byBoot[boot] ?: return null
            val ts = times.getValue(boot)
            // Index of the first fix strictly after t; the one before it is at or before t.
            var lo = 0; var hi = ts.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (ts[mid] <= t) lo = mid + 1 else hi = mid
            }
            val prev = if (lo > 0) fs[lo - 1] else null
            val next = if (lo < fs.size) fs[lo] else null

            if (prev != null && next != null && prev.binId == next.binId &&
                (next.elapsedNanos - prev.elapsedNanos) / 1_000_000L <= BRACKET_MAX_MS
            ) {
                val speeds = listOfNotNull(prev.speedMps, next.speedMps)
                return Hit(prev.binId, if (speeds.isEmpty()) null else speeds.average().toFloat())
            }

            val dPrev = prev?.let { t - it.elapsedNanos / 1_000_000L }
            val dNext = next?.let { it.elapsedNanos / 1_000_000L - t }
            val okPrev = prev != null && dPrev!! <= windowFor(prev.speedMps)
            val okNext = next != null && dNext!! <= windowFor(next.speedMps)
            val pick = when {
                okPrev && okNext -> if (dPrev!! <= dNext!!) prev else next
                okPrev -> prev
                okNext -> next
                else -> null
            } ?: return null
            return Hit(pick.binId, pick.speedMps)
        }
    }

    /**
     * The match window for one fix: time to cover [DISPLACEMENT_BUDGET_M] at its speed, clamped
     * to [[MOVING_WINDOW_MS], [STATIONARY_WINDOW_MS]]. Unknown speed takes the floor.
     */
    fun windowFor(speedMps: Float?): Long {
        val v = speedMps ?: return MOVING_WINDOW_MS
        if (v.isNaN() || v < 0f) return MOVING_WINDOW_MS
        if (v == 0f) return STATIONARY_WINDOW_MS
        return (DISPLACEMENT_BUDGET_M / v * 1000.0).toLong()
            .coerceIn(MOVING_WINDOW_MS, STATIONARY_WINDOW_MS)
    }

    // ---------------------------------------------------------------- leaves

    /**
     * One leaf under construction. Every statistic is accumulated as a sum -- counts, milliseconds,
     * or a value -> milliseconds histogram -- so the leaf, and every merge above it, derives its
     * fractions and percentiles from totals and nothing is ever averaged.
     */
    private class Acc(val binId: Long, val subId: Int) {
        var n = 0
        var ms = 0L
        var msSq = 0.0
        var validatedMs = 0L
        var suspendedMs = 0L
        var cellRoute = 0
        var cellRouteMs = 0L
        var cellValidatedMs = 0L
        var wifiSamples = 0
        var wifiMs = 0L
        /** Serving cell -> samples / ms. Keyed on the identity, so sectors of one mast stay separate. */
        val cells = HashMap<List<Any?>, Int>()
        val cellMs = HashMap<List<Any?>, Long>()
        val cellMeta = HashMap<List<Any?>, MapProbeJoin.CellShare>()
        val rsrp = HashMap<Int, Long>()
        val sinr = HashMap<Int, Long>()
        val rats = HashMap<String, Long>()
        val bands = HashMap<String, Long>()
        val plmns = HashMap<String, Long>()
        var cellChanges = 0
        var lastCell: Long? = null
        var wallMin = Long.MAX_VALUE
        var wallMax = Long.MIN_VALUE
        var speedMpsMs = 0.0
        var speedMs = 0L
    }

    /**
     * Builds every leaf, runs the merge walk, and renders GeoJSON.
     *
     * The join is by time, not by a foreign key: `radio_sample` has no `positionBinId` column in
     * the shipped schema (`data-model.md` §3 specifies one; `store/Db.kt` does not have it yet,
     * and that file is not ours to change). So each sample is placed by [Locator]. This is
     * strictly worse than binning at sample-write time: samples collected while no fix was being
     * taken have no bin and are reported as *unlocated*, in rows and in time, rather than quietly
     * dropped. When `positionBinId` lands, this whole join goes away.
     */
    /**
     * Merge two views of the same place into one.
     *
     * [combine] already does exactly this for rolling children into a parent; handing it the bin's
     * own id and resolution merges a bin restored from disk with the live one instead. Exposed so
     * [BinAggregator] does not reimplement a merge that has to stay identical to the builder's.
     */
    fun combineSame(views: List<Bin>): Bin =
        if (views.size == 1) views.first()
        else combine(views, views.first().id, views.first().res, "restored+live")

    /** Leaf bins for one explicit set of fixes, without touching what is persisted. */
    suspend fun binsFrom(ctx: Context, fixes: List<MapFix>): List<Bin> = runCatching {
        if (fixes.isEmpty()) return emptyList()
        // A minute either side of the fixes being consumed. The locator brackets a sample between
        // the fixes around it, so a sample just outside the span can still be placed by the fix at
        // its edge; reading exactly the span would drop those.
        val since = fixes.minOf { it.wallMillis } - 60_000L
        val radio = readRadio(ctx, since)
        val links = runCatching { readLinks(ctx) }.getOrDefault(emptyList())
        val probes = runCatching { MapProbeJoin.read(ctx) }.getOrDefault(emptyList())
        buildFrom(fixes, radio, links, probes, System.currentTimeMillis()).bins
    }.getOrDefault(emptyList())

    suspend fun build(ctx: Context): MapModel {
        val t0 = System.currentTimeMillis()
        return try {
            val fixes = com.signalscope.collect.FixBuffer.all()
            val radio = readRadio(ctx)
            val links = runCatching { readLinks(ctx) }.getOrDefault(emptyList())
            // The map must never take the app down, and one missing table must not cost the other
            // two their bins: a device that upgraded before probe_result existed still has radio
            // samples worth binning. So this read fails to an empty list, not to an empty map.
            val probes = runCatching { MapProbeJoin.read(ctx) }.getOrDefault(emptyList())
            val live = buildFrom(fixes, radio, links, probes, t0)
            // Everything already folded to disk, merged back in. The two sets are disjoint --
            // BinAggregator drains the fixes it consumes -- so this adds history rather than
            // double counting it.
            val stored = runCatching {
                Db.get(ctx).dao().allBinAgg().mapNotNull { BinCodec.decode(it.blob) }
            }.getOrDefault(emptyList())
            if (stored.isEmpty()) live else withStored(live, stored, t0)
        } catch (e: Throwable) {
            MapModel.empty(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Fold what is on disk into what was just built.
     *
     * The two sets are disjoint -- [BinAggregator] drains the fixes it consumes -- so bins sharing
     * an id are two views of one place and merge exactly, and bins appearing in only one pass
     * through untouched.
     *
     * [mergeWalk] is deliberately NOT re-run here. It rolls children into parents against
     * thresholds, and applying it again to output it has already produced would let a bin's
     * resolution drift coarser on every rebuild -- a map that quietly loses detail the longer it
     * runs. Stored and live bins therefore keep whatever resolution each was published at, and a
     * parent and child can briefly overlap until the next flush puts them in the same pass. Small,
     * transient, and visible, which is the right way round for this to be wrong.
     */
    private fun withStored(live: MapModel, stored: List<Bin>, t0: Long): MapModel {
        val merged = (live.bins + stored)
            .groupBy { it.id to it.subId }
            .map { (_, views) -> combineSame(views) }

        val resCounts = merged.groupingBy { it.res }.eachCount()
        val clsCounts = merged.groupingBy { it.cls }.eachCount()
        val surveyed = merged.filter { it.cls != 0 }
        val bad = surveyed.filter { it.cls == 1 }

        return live.copy(
            bins = merged.sortedByDescending { it.nObs },
            restoredBins = stored.size,
            geoJson = geoJson(merged),
            buildMs = System.currentTimeMillis() - t0,
            resCounts = resCounts,
            clsCounts = clsCounts,
            contrast = Contrast(
                surveyed = surveyed.size,
                thin = clsCounts[0] ?: 0,
                bad = bad.size,
                badFullBars = bad.count { carrierBars(it.rsrpP50) >= 4 },
                badWeak = bad.count { (it.rsrpP50 ?: 0) < -108 },
                medianBadRsrp = pooledPercentile(bad, 0.5) { it.rsrpHist }
            )
        )
    }

    private fun buildFrom(
        fixes: List<MapFix>, radio: List<Radio>, links: List<Link>,
        probes: List<MapProbeJoin.ProbeRow>, t0: Long
    ): MapModel {
        // Time weights over every stored reading, located or not, so that the coverage figure and
        // the unlocated time describe the whole record and not just the part that found a bin.
        val weights = TimeWeight.weigh(
            LongArray(radio.size) { radio[it].t },
            LongArray(radio.size) { radio[it].wall },
            IntArray(radio.size) { radio[it].subId }
        )

        if (fixes.isEmpty() || radio.isEmpty()) {
            return MapModel.empty().copy(
                radioRows = radio.size, linkRows = links.size, fixRows = fixes.size,
                probeRows = probes.size,
                unlocated = if (fixes.isEmpty()) radio.size else 0,
                probeUnlocated = if (fixes.isEmpty()) probes.size else 0,
                currentBin = fixes.lastOrNull()?.binId,
                buildMs = System.currentTimeMillis() - t0,
                observedMs = weights.observedMs, locatedMs = 0, spanMs = weights.spanMs
            )
        }

        // One boot partition over every clock that joins, so a fix and a sample from the same
        // boot always agree on which boot that was.
        val offsets = ArrayList<Long>(radio.size + fixes.size + links.size + probes.size)
        radio.forEach { offsets += it.wall - it.t }
        fixes.forEach { offsets += it.wallMillis - it.elapsedNanos / 1_000_000L }
        links.forEach { offsets += it.wall - it.t }
        probes.forEach { offsets += it.wall - it.t }
        val boots = TimeWeight.Boots(offsets.toLongArray())
        val locator = Locator(fixes, boots)

        // Keyed per (cell, subscription): `data-model.md` makes the PLMN a hard partition.
        val acc = HashMap<Pair<Long, Int>, Acc>()
        var unlocated = 0
        var locatedMs = 0L

        // Walk both streams in (boot, time) order. Validated / suspended / transport state is a
        // step function of the default route, and a step from one boot must not carry into the
        // next: the route state before a reboot says nothing about the route after it.
        val radioOrder = radio.indices.sortedWith(
            compareBy<Int>({ boots.of(radio[it].t, radio[it].wall) }, { radio[it].t })
        )
        val linkBoot = IntArray(links.size) { boots.of(links[it].t, links[it].wall) }
        val linkOrder = links.indices.sortedWith(compareBy<Int>({ linkBoot[it] }, { links[it].t }))
        var li = 0
        var cur: Link? = null
        var curBoot = -1

        for (i in radioOrder) {
            val s = radio[i]
            val sBoot = boots.of(s.t, s.wall)
            while (li < linkOrder.size) {
                val l = linkOrder[li]
                if (linkBoot[l] > sBoot || (linkBoot[l] == sBoot && links[l].t > s.t)) break
                cur = links[l]; curBoot = linkBoot[l]; li++
            }
            val route = if (curBoot == sBoot) cur else null

            val hit = locator.locate(s.t, s.wall)
            if (hit == null) { unlocated++; continue }
            val w = weights.ms[i]
            locatedMs += w

            val a = acc.getOrPut(hit.binId to s.subId) { Acc(hit.binId, s.subId) }
            a.n++
            a.ms += w
            a.msSq += w.toDouble() * w.toDouble()
            if (route?.validated == true) a.validatedMs += w
            if (route != null && !route.notSuspended) a.suspendedMs += w
            if (route?.transport == "WIFI") { a.wifiSamples++; a.wifiMs += w }
            // The honesty counter. "CELLULAR" only: a VPN over cellular reports TRANSPORT_VPN and
            // hides what it rides on, so it is not counted as cellular evidence — not measured is
            // the safe answer and a wrong "good" is the unsafe one.
            if (route?.transport == "CELLULAR") {
                a.cellRoute++
                a.cellRouteMs += w
                if (route.validated) a.cellValidatedMs += w
            }
            // Cell composition. PCI is the fallback label only: `coverage-map.md` is explicit that
            // PCI is reused and is a local label, never a key — which is fine here, because this
            // list is only ever read on the device that recorded it.
            if (s.ci != null || s.pci != null) {
                val share = MapProbeJoin.CellShare(s.rat, s.ci, s.pci, s.band, 1)
                val k = listOf<Any?>(share.rat, share.ci, share.pci, share.band)
                a.cells[k] = (a.cells[k] ?: 0) + 1
                TimeWeight.addTo(a.cellMs, k, w)
                a.cellMeta[k] = share
            }
            s.rsrp?.let { TimeWeight.addTo(a.rsrp, it, w) }
            s.sinr?.let { TimeWeight.addTo(a.sinr, it, w) }
            TimeWeight.addTo(a.rats, s.rat, w)
            bandLabel(s.rat, s.band)?.let { TimeWeight.addTo(a.bands, it, w) }
            TimeWeight.addTo(a.plmns, s.plmn, w)
            val cell = s.ci ?: s.pci?.toLong()?.let { -it }
            if (cell != null) {
                if (a.lastCell != null && a.lastCell != cell) a.cellChanges++
                a.lastCell = cell
            }
            a.wallMin = min(a.wallMin, s.wall); a.wallMax = max(a.wallMax, s.wall)
            hit.speedMps?.let { a.speedMpsMs += it.toDouble() * w; a.speedMs += w }
        }

        // Default-route transport flaps, attributed to whichever bin was current at the time.
        val flapsPerBin = HashMap<Long, Int>()
        var prevTransport: String? = null
        var prevBoot = -1
        for (l in linkOrder) {
            val link = links[l]
            if (linkBoot[l] != prevBoot) { prevTransport = null; prevBoot = linkBoot[l] }
            if (prevTransport != null && prevTransport != link.transport) {
                locator.locate(link.t, link.wall)?.let { hit ->
                    flapsPerBin[hit.binId] = (flapsPerBin[hit.binId] ?: 0) + 1
                }
            }
            prevTransport = link.transport
        }

        // Probes are placed by the same Locator, on the monotonic clock `data-model.md` §2
        // specifies for exactly this. Attribution is per bin rather than per (bin, subscription):
        // probe_result carries no subId.
        val probeJoin = MapProbeJoin.attribute(probes) { p -> locator.locate(p.t, p.wall)?.binId }

        // One cell can hold a leaf per subscription. They are never averaged together; the map
        // draws the one with more observed time and the detail sheet says how much it is not
        // showing.
        val perCell = acc.values.groupBy { it.binId }
        val leaves = perCell.map { (binId, subs) ->
            val winner = subs.maxWithOrNull(compareBy<Acc>({ it.ms }, { it.n }))!!
            val others = subs.filter { it !== winner }.sumOf { it.n }
            toLeaf(winner, flapsPerBin[winner.binId] ?: 0, others, probeJoin.perBin[binId])
        }
        val published = mergeWalk(leaves)

        val resCounts = published.groupingBy { it.res }.eachCount()
        val clsCounts = published.groupingBy { it.cls }.eachCount()
        val surveyed = published.filter { it.cls != 0 }
        val bad = surveyed.filter { it.cls == 1 }

        return MapModel(
            bins = published.sortedByDescending { it.nObs },
            geoJson = geoJson(published),
            leafCount = leaves.size,
            radioRows = radio.size,
            linkRows = links.size,
            fixRows = fixes.size,
            probeRows = probes.size,
            unlocated = unlocated,
            probeUnlocated = probeJoin.unlocated,
            currentBin = fixes.maxByOrNull { it.wallMillis }?.binId,
            buildMs = System.currentTimeMillis() - t0,
            contrast = Contrast(
                surveyed = surveyed.size,
                thin = clsCounts[0] ?: 0,
                bad = bad.size,
                badFullBars = bad.count { carrierBars(it.rsrpP50) >= 4 },
                badWeak = bad.count { (it.rsrpP50 ?: 0) < -108 },
                // Pooled over the failing bins' time, not a median of their medians.
                medianBadRsrp = pooledPercentile(bad, 0.5) { it.rsrpHist }
            ),
            resCounts = resCounts,
            clsCounts = clsCounts,
            observedMs = weights.observedMs,
            locatedMs = locatedMs,
            spanMs = weights.spanMs
        )
    }

    /**
     * A percentile over the union of several bins' readings, by time.
     *
     * Bins are disjoint -- every reading was placed in exactly one leaf -- so summing their
     * histograms is the histogram of the union and the result is exact.
     */
    fun pooledPercentile(bins: List<Bin>, q: Double, hist: (Bin) -> Map<Int, Long>): Int? =
        TimeWeight.percentile(TimeWeight.sum(bins.map(hist)), q)

    /** Observed time behind a pooled histogram, so the figure can carry its own evidence. */
    fun pooledMs(bins: List<Bin>, hist: (Bin) -> Map<Int, Long>): Long =
        bins.sumOf { b -> hist(b).values.sum() }

    private fun toLeaf(
        a: Acc, flaps: Int, otherSubSamples: Int, probes: MapProbeJoin.Probes?
    ): Bin {
        val res = MapHex.resolutionOf(a.binId)
        val b = Bin(
            id = a.binId,
            res = res,
            subId = a.subId,
            otherSubSamples = otherSubSamples,
            nObs = a.n,
            contributors = setOf(DEVICE_CONTRIBUTOR),
            observedMs = a.ms,
            observedMsSq = a.msSq,
            validatedMs = a.validatedMs,
            cellRouteSamples = a.cellRoute,
            cellRouteMs = a.cellRouteMs,
            cellValidatedMs = a.cellValidatedMs,
            wifiRouteSamples = a.wifiSamples,
            wifiRouteMs = a.wifiMs,
            suspendedMs = a.suspendedMs,
            probeN = probes?.n ?: 0,
            probeFail = probes?.fail ?: 0,
            probeInstrument = probes?.instrument ?: 0,
            probeNoBearer = probes?.noBearer ?: 0,
            probeOkLatencyMs = probes?.okLatency?.sorted() ?: emptyList(),
            coldProbeN = probes?.cold ?: 0,
            coldProbeFail = probes?.coldFail ?: 0,
            probeErrors = probes?.errors?.toMap() ?: emptyMap(),
            cells = a.cells.entries
                .map { (k, n) -> a.cellMeta.getValue(k).copy(samples = n, ms = a.cellMs[k] ?: 0L) }
                .sortedWith(compareByDescending<MapProbeJoin.CellShare> { it.ms }.thenByDescending { it.samples }),
            flaps = flaps,
            cellChanges = a.cellChanges,
            // Null, never 1.0. An absent measurement is not a perfect score, and Phase 1 records
            // no NR_NSA <-> LTE transition counter to compute it from even when a NR leg exists.
            nrAnchor = null,
            speedMpsMs = a.speedMpsMs,
            speedMs = a.speedMs,
            rsrpHist = a.rsrp,
            sinrHist = a.sinr,
            ratMs = a.rats,
            bandMs = a.bands,
            plmnMs = a.plmns,
            topCause = 0,
            causeShare = 0.0,
            cls = 0,
            childUnits = MapHex.childUnits(res),
            leafCount = 1,
            mergeReason = "leaf — as recorded",
            firstSeenMillis = a.wallMin,
            lastSeenMillis = a.wallMax
        )
        val withCause = b.copy(topCause = cause(b), causeShare = 1.0)
        return withCause.copy(cls = classify(withCause).code)
    }

    /**
     * `coverage-map.md` §1, first match wins — rewired onto measured outcomes.
     *
     * Two things changed from the Phase-1 version of this function, and both are corrections
     * rather than additions.
     *
     * **Route loss is read from cellular-bound evidence only.** `validatedFrac` describes the
     * default route, and while Wi-Fi holds that route it describes Wi-Fi: a bin recorded at home
     * reported ~1.00 validated and was classified LTE SOLID on the strength of a bearer nothing
     * was riding on. That is the instrument gap `excursion-findings.md` found and fixed in the
     * probe path, arriving here. So the condition now reads [Bin.cellValidatedFrac], and a bin
     * with no cellular-bound evidence at all is UNSURVEYED — "not measured", not "good". This is
     * the one place where a wrong answer would be worst, because the wrong answer is green.
     *
     * **Class 4 is reachable.** `probe_result` exists now, so "validated but slow" can be found,
     * and on this network it is the class that matters most: the excursion measured no failures
     * at all while the bearer was in use, against cold wake-ups failing at good signal. A bin
     * whose cold probes fail is a bin where apps stall on resume, and nothing in the signal
     * metrics shows it.
     *
     * Signal is still not in this function at all — see [rsrpColour] and [sinrColour], which keep
     * it as a descriptive layer where it belongs.
     */
    /**
     * The ordered rule list itself, evaluated first-match-wins by [classify].
     *
     * It is a list rather than a `when` so that [verdictReason] can name the condition that
     * actually fired without restating the order somewhere else. A bin sheet that explains the
     * colour wrongly is worse than one that does not explain it, and the only way to guarantee it
     * cannot drift is to have one list.
     *
     * Class 4's two limbs are `coverage-map.md` §1: `probe_p90_ms > 1500` or
     * `probe_success < 0.95`. The cold-wake-up limb is tested before the pooled one because the
     * pooled rate is diluted by the warm second-of-pair probes, which never pay an RRC promotion
     * — 168 ms mean against 457 ms — so a bin can look fine pooled while every cold wake-up in it
     * stalls.
     *
     * The class *order* is still the doc's and has not been re-sorted, even though there is a real
     * argument for lifting class 4 above reselection churn now: the excursion measured 2.1 cell
     * changes a minute alongside zero failures, so churn is the weaker finding of the two. Left
     * alone because the ordering is a published contract, because a bin whose cold wake-ups fail
     * is non-green under either order, and because [verdictReason] names the condition that
     * actually fired — so the choice hides nothing from the user.
     */
    private fun rules(b: Bin): List<Triple<Boolean, Outcome, String>> {
        val p90 = b.probeP90Ms
        return listOf(
            Triple(b.nObs < N_LOCAL, Outcome.UNSURVEYED, "n < $N_LOCAL samples in this bin"),
            // Measured the radio, never measured the bearer. Grey, and the sheet says which.
            Triple(
                !b.hasCellEvidence, Outcome.UNSURVEYED,
                "no cellular-route sample and no cellular-bound probe — not measured"
            ),
            Triple(
                b.cellValidatedFrac?.let { it < 0.80 } == true, Outcome.ROUTE_LOSS,
                "validated < 0.80 over the samples cellular actually carried"
            ),
            // Failures this dense are loss rather than slowness. Read off the *lower* Wilson
            // bound so one failure in a handful of probes cannot paint a bin red — the interval
            // has to clear the threshold, not the point estimate.
            Triple(
                b.probeN > 0 && b.probeFailCi[0] > MapProbeJoin.FAIL_RATE_LOSS, Outcome.ROUTE_LOSS,
                "probe failure rate lower bound > ${MapProbeJoin.FAIL_RATE_LOSS}"
            ),
            Triple(b.anchorStability < 0.60, Outcome.ANCHOR, "anchor stability < 0.60"),
            Triple(
                b.reselectRate > 2 && b.speedKph < 5, Outcome.RESELECT,
                "reselect > 2/min below 5 km/h"
            ),
            Triple(
                b.coldSuccessFrac?.let { it < MapProbeJoin.SUCCESS_FLOOR } == true, Outcome.SLOW,
                "cold wake-up success < ${MapProbeJoin.SUCCESS_FLOOR}"
            ),
            Triple(
                b.probeFailFrac?.let { it > 1 - MapProbeJoin.SUCCESS_FLOOR } == true, Outcome.SLOW,
                "probe success < ${MapProbeJoin.SUCCESS_FLOOR}"
            ),
            Triple(
                p90 != null && b.probeOkLatencyMs.size >= MapProbeJoin.MIN_PROBES_FOR_LATENCY &&
                    p90 > MapProbeJoin.SLOW_P90_MS,
                Outcome.SLOW, "probe p90 ${p90 ?: 0} ms > ${MapProbeJoin.SLOW_P90_MS} ms"
            ),
            Triple(b.rat.startsWith("NR"), Outcome.NSA, "NR serving and every outcome test passed")
        )
    }

    private fun classify(b: Bin): Outcome =
        rules(b).firstOrNull { it.first }?.second ?: Outcome.LTE

    /** Which condition produced this bin's class. Read by the bin sheet, never by the classifier. */
    fun verdictReason(b: Bin): String =
        rules(b).firstOrNull { it.first }?.third ?: "every outcome that was measured here passed"

    /**
     * Dominant cause. Measured outcomes first; signal is only ever a discriminator *within* an
     * already-measured failure, never the thing that names one.
     *
     * The old version ended `(rsrpP50 ?: 0) < -115 -> 1`, which named radio coverage loss from
     * signal alone in a bin that had never lost anything. A median SINR of 0 dB produced zero
     * failures across 25 minutes of real use, so that inference is gone.
     */
    private fun cause(b: Bin): Int = when {
        // Nothing rode on the bearer here, so there is no cause to name. Saying "none dominant"
        // is the honest output; picking cause 1 off a weak RSRP would be inventing a finding.
        !b.hasCellEvidence -> 0
        b.suspendFrac > 0.10 -> 5
        // Route loss on the cellular bearer. Signal decides which kind: a bin that lost the route
        // at -120 dBm lost coverage, and one that lost it at -90 dBm had a path that broke while
        // the radio was fine, which is a completely different repair.
        b.cellValidatedFrac?.let { it < 0.80 } == true ->
            if ((b.rsrpP50 ?: 0) < -115) 1 else 6
        // Validated, and probes still failed: cause 6, connected-but-broken path. On this network
        // it is specifically the cold wake-up, which failed at good signal where the in-use bearer
        // did not (excursion-findings.md §2, and read its correction with it).
        b.probeFail > 0 -> 6
        b.reselectRate > 2 && b.speedKph < 5 -> 3
        // Wi-Fi held the route for most of the time spent here, and the route flapped.
        b.flaps > 0 && b.wifiRouteMs * 2 > b.observedMs -> 7
        else -> 0
    }

    /** Single device, so one contributor. Modelled as a set anyway — the merge rule needs it to be. */
    private const val DEVICE_CONTRIBUTOR = "self"

    // ---------------------------------------------------------------- the merge walk

    /**
     * `adaptive-aggregation.md`, with the areal-coverage condition evaluated **first**.
     *
     * Walk finest -> res 6. Merge a parent's children when all of:
     *   0. at least [MIN_PARENT_COVERAGE] of the parent was actually surveyed — the guard that
     *      stops the map inventing coverage over ground nobody has crossed;
     *   1. below k, merge upward unconditionally — but only *after* condition 0 passes, because
     *      coverage wins over the privacy threshold: a bin that cannot reach k without claiming
     *      unsurveyed ground stays unpublished rather than growing until it qualifies;
     *   2. all children share the dominant outcome class;
     *   3. Wilson score intervals on `validated_frac` overlap pairwise;
     *   4. no well-evidenced child sits in a worse class than the merged parent would.
     *
     * Leaves may sit at different resolutions, because storage resolution follows fix accuracy.
     * A leaf enters the walk when the walk reaches its own resolution.
     */
    /**
     * Reconcile leaves whose storage resolutions overlap, before the merge walk runs.
     *
     * Not in any doc, and it falls straight out of "bin resolution follows fix accuracy": stand
     * still while the fix quality drifts from ±30 m to ±100 m and the same patch of ground is
     * recorded once at res 10 and again at res 9 — a small hexagon sitting inside a large one,
     * both claiming the same place, drawn on top of each other.
     *
     * The asymmetry from `adaptive-aggregation.md` settles it: merging upward is always possible,
     * splitting downward never is. So the finer leaf rolls up into the coarser one, which is the
     * only resolution at which both observations are honest. The reverse — publishing the coarse
     * fix's samples at res 10 — would assert precision the fix never had.
     */
    private fun collapseOverlaps(leaves: List<Bin>): List<Bin> {
        if (leaves.size < 2) return leaves
        val byId = LinkedHashMap<Long, Bin>()
        leaves.forEach { byId[it.id] = it }
        val finest = leaves.maxOf { it.res }
        val coarsest = leaves.minOf { it.res }
        if (finest == coarsest) return leaves
        for (r in finest downTo coarsest + 1) {
            for (leaf in byId.values.filter { it.res == r }) {
                var pr = r - 1
                var host: Bin? = null
                while (pr >= coarsest && host == null) {
                    host = byId[MapHex.cellToParent(leaf.id, pr)]
                    pr--
                }
                // The PLMN partition outranks the overlap. The merge walk has this guard; this
                // roll-up did not, so a coarse leaf on one carrier could absorb a fine leaf on
                // another and publish a bin describing neither. Two hexagons drawn on top of each
                // other is a cosmetic fault; averaging two carriers is a wrong measurement.
                if (host != null && host.plmn == leaf.plmn) {
                    byId.remove(leaf.id)
                    byId[host.id] = combine(
                        listOf(host, leaf), host.id, host.res,
                        "a coarser fix already claims this ground — finer leaf rolled up"
                    )
                }
            }
        }
        return byId.values.toList()
    }

    private fun mergeWalk(input: List<Bin>): List<Bin> {
        if (input.isEmpty()) return emptyList()
        val leaves = collapseOverlaps(input)
        val published = ArrayList<Bin>()
        val byRes = leaves.groupBy { it.res }
        val finest = leaves.maxOf { it.res }
        var current = (byRes[finest] ?: emptyList()).toMutableList()

        var res = finest
        while (res > MapHex.RES_FLOOR) {
            if (current.isEmpty()) break
            val groups = current.groupBy { MapHex.cellToParent(it.id, res - 1) }
            val next = ArrayList<Bin>()
            for ((parent, kids) in groups) {
                val possible = MapHex.childUnits(res - 1).toDouble()
                val present = kids.sumOf { it.childUnits }.toDouble()
                val covered = present / possible

                val anyThin = kids.any { it.contributorCount < K_ANON || it.nObs < N_CROWD }

                var merge = false
                var reason: String
                if (covered < MIN_PARENT_COVERAGE) {
                    reason = "only ${(covered * 100).toInt()}% of the parent cell was surveyed"
                } else if (kids.distinctBy { it.plmn }.size > 1) {
                    // `data-model.md`: plmn is a hard partition. Averaging two carriers produces
                    // a bin that describes no network anyone is actually on.
                    reason = "children sit on different carriers — bins never merge across PLMNs"
                } else if (anyThin) {
                    merge = true
                    reason = "below k — merged unconditionally"
                } else {
                    val cls0 = kids[0].cls
                    val sameClass = kids.all { it.cls == cls0 }
                    // Condition 3 stays on `validated_frac` as `adaptive-aggregation.md` specifies
                    // it. Not switched to the cellular-restricted fraction: that is null in most
                    // bins, every interval would be [0,1], and a gate that always passes is not a
                    // gate. The honest metric governs the *verdict* (see classify); this governs
                    // whether two neighbours may be described by one number.
                    // On the effective n of the time weights, not the row count: forty screen-on
                    // rows in one second are not forty independent observations.
                    val ivs = kids.map { wilson(it.validatedFrac, it.effectiveN) }
                    var wilsonOk = true
                    outer@ for (i in ivs.indices) {
                        for (j in i + 1 until ivs.size) {
                            if (!overlap(ivs[i], ivs[j])) { wilsonOk = false; break@outer }
                        }
                    }
                    val cand = combine(kids, parent, res - 1, "")
                    val contradicts = kids.any {
                        it.contributorCount >= K_ANON &&
                            (SEVERITY[it.cls] ?: 0) > (SEVERITY[cand.cls] ?: 0)
                    }
                    reason = when {
                        !sameClass -> "children disagree on outcome class"
                        !wilsonOk -> "Wilson intervals do not overlap"
                        contradicts -> "a well-evidenced worse child blocks the merge"
                        else -> { merge = true; "homogeneous — class agrees, Wilson overlaps" }
                    }
                }

                if (merge) {
                    next.add(combine(kids, parent, res - 1, reason))
                } else {
                    kids.forEach { published.add(it.copy(mergeReason = "held here — $reason")) }
                }
            }
            res--
            // Leaves recorded coarser than the walk's current level join in here.
            byRes[res]?.forEach { leaf ->
                val at = next.indexOfFirst { it.id == leaf.id }
                if (at >= 0) {
                    next[at] = combine(
                        listOf(next[at], leaf), leaf.id, res,
                        "same cell, mixed storage resolution"
                    )
                } else next.add(leaf)
            }
            current = next
        }
        published.addAll(current)
        return published
    }

    /** Wilson score interval. Not the normal approximation: at n = 12 near 0 or 1 that is wrong. */
    fun wilson(p: Double, n: Int, z: Double = 1.96): DoubleArray {
        if (n <= 0) return doubleArrayOf(0.0, 1.0)
        val z2 = z * z
        val d = 1 + z2 / n
        val c = p + z2 / (2 * n)
        val m = z * sqrt((p * (1 - p) + z2 / (4 * n)) / n)
        return doubleArrayOf(
            ((c - m) / d).coerceIn(0.0, 1.0),
            ((c + m) / d).coerceIn(0.0, 1.0)
        )
    }

    private fun overlap(a: DoubleArray, b: DoubleArray) = a[0] <= b[1] && b[0] <= a[1]

    /**
     * Combines bins into one. Every field is either a sum, a set union, a histogram sum or a
     * min/max -- never an average of the children's derived figures.
     *
     * That is what makes time weighting survive the walk. A child carries `observedMs`,
     * `validatedMs`, `cellRouteMs`, value -> ms histograms for RSRP and SINR, and so on; the
     * parent sums them, and its fractions and medians are then recomputed by [Bin]'s getters from
     * the merged totals. Because every reading was placed in exactly one leaf, the parent's
     * figures are identical to what a single pass over all of its readings would have produced,
     * however many levels of merging lie between -- which a median of medians, or a fraction
     * averaged by row count, never is.
     */
    private fun combine(kids: List<Bin>, parent: Long, res: Int, reason: String): Bin {
        val n = max(kids.sumOf { it.nObs }, 1)
        val observed = kids.sumOf { it.observedMs }

        // Contributor sets are UNIONED, never summed. Summing would let k-anonymity be cleared
        // by aggregation alone — one person walking a long route "becoming" five contributors.
        val contribs = HashSet<String>()
        kids.forEach { contribs.addAll(it.contributors) }

        val causeW = HashMap<Int, Long>()
        kids.forEach { if (it.topCause != 0) TimeWeight.addTo(causeW, it.topCause, it.observedMs) }
        val top = causeW.maxByOrNull { it.value }

        val dominant = kids.maxWithOrNull(compareBy<Bin>({ it.observedMs }, { it.nObs }))!!
        // nrAnchor has no underlying counts to sum -- Phase 1 never produces one (see toLeaf) --
        // so if it ever does, children without it are left out rather than counted as 1.0.
        val nrKids = kids.filter { it.nrAnchor != null }
        val nrW = nrKids.sumOf { it.observedMs }
        val merged = Bin(
            id = parent,
            res = res,
            subId = dominant.subId,
            otherSubSamples = kids.sumOf { it.otherSubSamples },
            nObs = n,
            contributors = contribs,
            observedMs = observed,
            observedMsSq = kids.sumOf { it.observedMsSq },
            validatedMs = kids.sumOf { it.validatedMs },
            cellRouteSamples = kids.sumOf { it.cellRouteSamples },
            cellRouteMs = kids.sumOf { it.cellRouteMs },
            cellValidatedMs = kids.sumOf { it.cellValidatedMs },
            wifiRouteSamples = kids.sumOf { it.wifiRouteSamples },
            wifiRouteMs = kids.sumOf { it.wifiRouteMs },
            suspendedMs = kids.sumOf { it.suspendedMs },
            probeN = kids.sumOf { it.probeN },
            probeFail = kids.sumOf { it.probeFail },
            probeInstrument = kids.sumOf { it.probeInstrument },
            probeNoBearer = kids.sumOf { it.probeNoBearer },
            // Each probe was attributed to exactly one leaf, so concatenating is not double
            // counting, and the merged percentiles are exact rather than a median of medians.
            probeOkLatencyMs = kids.flatMap { it.probeOkLatencyMs }.sorted(),
            coldProbeN = kids.sumOf { it.coldProbeN },
            coldProbeFail = kids.sumOf { it.coldProbeFail },
            probeErrors = kids.flatMap { it.probeErrors.entries }
                .groupingBy { it.key }.fold(0) { acc, e -> acc + e.value },
            cells = MapProbeJoin.mergeCells(kids.map { it.cells }),
            flaps = kids.sumOf { it.flaps },
            cellChanges = kids.sumOf { it.cellChanges },
            nrAnchor = if (nrKids.isEmpty()) null
                else if (nrW > 0) nrKids.sumOf { it.nrAnchor!! * it.observedMs } / nrW
                else nrKids.sumOf { it.nrAnchor!! } / nrKids.size,
            speedMpsMs = kids.sumOf { it.speedMpsMs },
            speedMs = kids.sumOf { it.speedMs },
            rsrpHist = TimeWeight.sum(kids.map { it.rsrpHist }),
            sinrHist = TimeWeight.sum(kids.map { it.sinrHist }),
            ratMs = TimeWeight.sum(kids.map { it.ratMs }),
            bandMs = TimeWeight.sum(kids.map { it.bandMs }),
            plmnMs = TimeWeight.sum(kids.map { it.plmnMs }),
            topCause = top?.key ?: 0,
            causeShare = top?.let { TimeWeight.fraction(it.value, observed) } ?: 0.0,
            cls = 0,
            // Capped: a cell cannot hold more surveyed ground than it contains. Without the cap a
            // finer leaf rolled into a coarser one would inflate the areal-coverage numerator.
            childUnits = min(kids.sumOf { it.childUnits }, MapHex.childUnits(res)),
            leafCount = kids.sumOf { it.leafCount },
            mergeReason = reason,
            firstSeenMillis = kids.minOf { it.firstSeenMillis },
            lastSeenMillis = kids.maxOf { it.lastSeenMillis }
        )
        return merged.copy(cls = classify(merged).code)
    }

    // ---------------------------------------------------------------- paint

    /** "Confidence is opacity, never colour." */
    fun confidenceOpacity(b: Bin): Double {
        if (b.cls == 0) return 0.30
        val t = ((Math.log10(max(b.nObs, 1).toDouble()) - 0.9) / 2.3).coerceIn(0.0, 1.0)
        return 0.34 + 0.46 * t
    }

    /**
     * The RSRP layer renders the bar computation from [LTE_BAR_THRESHOLDS] — `params=1`, i.e.
     * RSRP alone. It is the map the network would draw of itself on default carrier config, and
     * it is here so the disagreement with the quality layer is visible in one frame rather than
     * argued about.
     */
    private val BAR_COLOUR = arrayOf("#8e2f28", "#c0562b", "#d8b32e", "#69b53a", "#1f9e5a")

    fun rsrpColour(b: Bin) = if (b.rsrpP50 == null) "#2a3240" else BAR_COLOUR[carrierBars(b.rsrpP50)]

    /**
     * SINR is deliberately NOT on the carrier's scale. RSRP is coloured by the operator's own bar
     * formula so the two layers can be compared; SINR is the independent check on that formula,
     * so colouring it by anything the carrier supplies would defeat the comparison.
     */
    fun sinrColour(b: Bin) = when (b.sinrP50) {
        null -> "#2a3240"
        in 20..Int.MAX_VALUE -> "#2f9e6b"
        in 13..19 -> "#46b07a"
        in 5..12 -> "#b8862f"
        in 0..4 -> "#a8642c"
        else -> "#b03a42"
    }

    /**
     * A band's colour, derived from its label rather than looked up in a table.
     *
     * The old table named five band numbers, which is one country's spectrum written into a
     * public app, and it could not tell B40 from n40. Hashing the label ("B40", "n40") gives every
     * band a stable colour on every device with nothing to maintain. Two bands can land on the same
     * colour; the legend lists each label beside its swatch, so a collision is visible rather than
     * misleading.
     */
    private val BAND_PALETTE = arrayOf(
        "#4da3ff", "#b388ff", "#3fc4bd", "#ffb648", "#6b4bb8", "#ff8a5b", "#69b53a", "#e06c9f"
    )

    fun bandColour(label: String?): String =
        if (label == null) "#2a3240"
        else BAND_PALETTE[Math.floorMod(label.hashCode(), BAND_PALETTE.size)]

    fun bandColour(b: Bin): String = bandColour(b.band)

    fun anchorColour(b: Bin): String {
        val a = b.anchorStability
        return when {
            a >= 0.95 -> "#2f9e6b"
            a >= 0.88 -> "#5aa85a"
            a >= 0.75 -> "#cfa338"
            a >= 0.55 -> "#a8642c"
            else -> "#b03a42"
        }
    }

    val RES_COLOUR = mapOf(
        10 to "#4da3ff", 9 to "#3fc4bd", 8 to "#ffb648", 7 to "#ff8a5b", 6 to "#b388ff"
    )

    fun resColour(res: Int) = RES_COLOUR[res] ?: "#5c6677"

    /**
     * Every layer's colour and opacity is precomputed onto the feature, so switching layers is
     * one paint-property swap and never a re-upload of the source. The web prototype measured
     * that at under a millisecond; the same trick applies here.
     */
    /**
     * Distinct masts serving a bin. See the note at the call site for why this is sites rather
     * than cells, and why an identity that will not decompose is counted alone.
     */
    private fun siteCount(b: Bin): Int {
        if (b.cells.isEmpty()) return 0
        val sites = HashSet<String>()
        b.cells.forEach { c ->
            val e = c.enb
            sites += if (e != null) "e$e" else "c${c.ci ?: c.pci ?: c.hashCode()}"
        }
        return sites.size
    }

    private fun geoJson(bins: List<Bin>): String {
        val sb = StringBuilder(1024)
        sb.append("""{"type":"FeatureCollection","features":[""")
        bins.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            val op = confidenceOpacity(b)
            sb.append("""{"type":"Feature","properties":{""")
            sb.append(""""id":"${b.id}","res":${b.res},"cls":${b.cls},""")
            sb.append(""""c_quality":"${Outcome.of(b.cls).colour}",""")
            sb.append(""""c_rsrp":"${rsrpColour(b)}",""")
            sb.append(""""c_sinr":"${sinrColour(b)}",""")
            sb.append(""""c_band":"${bandColour(b)}",""")
            sb.append(""""c_anchor":"${anchorColour(b)}",""")
            sb.append(""""c_res":"${resColour(b.res)}",""")
            sb.append(""""o_quality":${fmt(op)},""")
            sb.append(""""o_rsrp":${if (b.cls == 0) 0.30 else 0.72},""")
            sb.append(""""o_band":${if (b.cls == 0) 0.30 else 0.62},""")
            sb.append(""""o_anchor":${if (b.cls == 0) 0.30 else 0.72},""")
            sb.append(""""o_res":0.62,""")
            // "looks fine on power, unusable in practice" — outlined on the RSRP layer.
            // Class 4 joins class 1 here now that probe latency exists: four bars and a cold
            // wake-up that fails is the same lie as four bars and no route, and it is the one the
            // excursion actually measured.
            sb.append(
                """"lie":${if ((b.cls == 1 || b.cls == 4) && carrierBars(b.rsrpP50) >= 4) 1 else 0},"""
            )
            // How many distinct MASTS served this bin, not how many cells. A three-sector site
            // reports three cell identities and is one thing in the world, and the count the map
            // draws should be the count of things in the world. Where an identity does not
            // decompose -- NR, whose gNB-ID length is not knowable from the identity alone -- the
            // cell is counted on its own rather than guessed at, which can only over-count, never
            // invent a mast that is not there.
            sb.append(""""sites":${siteCount(b)},""")
            // The network this bin is mostly on, as a filterable property. Comparing operators is
            // the entire reason a shared map is worth building, and until now the PLMN reached the
            // renderer nowhere at all -- it existed only as text in the detail sheet, so the map
            // could colour by band but never answer "show me just this network".
            sb.append(""""plmn":"${b.plmn ?: ""}"""")
            sb.append("""},"geometry":{"type":"Polygon","coordinates":[[""")
            MapHex.cellToBoundary(b.id).forEachIndexed { j, p ->
                if (j > 0) sb.append(',')
                sb.append('[').append(fmt6(p[0])).append(',').append(fmt6(p[1])).append(']')
            }
            sb.append("]]}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    // Locale.US deliberately: a comma decimal separator would emit JSON that parses as an extra
    // coordinate, and the failure would be silent and geographic.
    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
    private fun fmt6(v: Double) = String.format(java.util.Locale.US, "%.6f", v)
}
