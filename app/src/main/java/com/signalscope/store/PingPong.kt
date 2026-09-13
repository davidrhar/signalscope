package com.signalscope.store

import android.content.Context
import com.signalscope.collect.CellProbe
import com.signalscope.collect.Mobility

/**
 * Does ping-pong hurt?
 *
 * Ping-pong — the phone returning to a cell it has just left — is the most prevalent thing this
 * app measures while moving: two recorded journeys held 39 and 30 returns, and the map files 17
 * of 29 bins as [Outcome.RESELECT] on the strength of it. Nothing anywhere in the project has
 * ever tested whether it costs the user anything. If it does, the amber on that map is a fault
 * class. If it does not, the phone is doing exactly what a handset in RRC_IDLE is supposed to do
 * and the map is colouring in normal behaviour, which is worth knowing and worth recolouring.
 *
 * So this file is a test, not a detector, and it is built to be able to come back empty-handed.
 * The verdict [Verdict.NO_ASSOCIATION] is a first-class result here with its own interval and its
 * own sentence in [summary], not a fallback printed when nothing else matched.
 *
 * ## The design
 *
 * **Case.** A serving-cell change A→B followed by a change back to A inside
 * [Mobility.PING_PONG_MS]. That constant is reused rather than restated: 30 s is already the
 * project's definition of a return, it is what the journey counts in [Mobility] were built on,
 * and a second threshold here would mean the map, the journey table and this analysis were each
 * measuring a slightly different thing while sharing a name. The case is anchored on the
 * **return** leg, and the departure leg is then neither case nor control — counting it as a case
 * too would enter one physical event twice, and leaving it among the controls would put half of
 * a ping-pong into the set that is supposed to contain none.
 *
 * **Control.** A serving-cell change that is *not* part of any ping-pong, on the same
 * subscription, matched to a case on how many cell changes surround it within
 * [CONTROL_CONTEXT_MS]. The matching is the entire point. Ping-pong happens during churn, and
 * churn happens while moving, so a comparison against the phone's resting state would find that
 * ping-pong periods look worse and would be measuring travel. Matched this way the comparison is
 * ping-pong against **ordinary mobility of the same intensity** — a cell change that went
 * somewhere and stayed, with the same amount of movement around it.
 *
 * Matching is 1:1 without replacement, so every case that is reported has a control of its own
 * and the two arms have identical n. Cases that find no control are counted in
 * [Report.unmatchedCases] and reported, never quietly dropped: on the data this was written
 * against they were systematically the *highest*-churn returns, which is exactly where controls
 * run out, and hiding that would have hidden the most interesting part of the sample.
 *
 * **Outcome.** Any of a real probe failure, a validated-route loss on a cellular default route,
 * or a registration state other than IN_SERVICE, landing inside the same window relative to the
 * anchor for both arms. Latency is carried alongside as a continuous companion, because "slow"
 * is a harm that never crosses a failure threshold.
 *
 * ## The two ways this data lies
 *
 * Both are real and both have already produced published nonsense in this project:
 *
 *  1. **Instrument faults.** 248 rows whose `errorCode` names a socket binding failure describe
 *     this app failing to bind, not the network failing to work. Counted as failures they
 *     produced eight phantom failed handovers and 21 phantom route-loss bins. Every one is
 *     excluded here by [CellProbe.kindOf], before any rate is formed, and the excluded count
 *     travels in [Report.instrumentRowsExcluded] so the exclusion is visible rather than
 *     implied.
 *  2. **A frozen radio stream.** Rows repeat when nothing refreshes them, and a re-read snapshot
 *     is indistinguishable from a rock-steady cell. Each distinct reading is consumed once — the
 *     same rule [Mobility] and [KeepaliveExperiment] use, applied to stored rows — and
 *     [Report.timeCoverage] carries the result, because a run that saw nothing reports no ping-pong
 *     and no harm, which looks identical to a run that went perfectly.
 *
 * Everything here reads; nothing writes, migrates, or asks for a permission.
 */
object PingPong {

    // ------------------------------------------------------------------------------------------
    //  Thresholds. Every one of them says where it came from.
    // ------------------------------------------------------------------------------------------

    /**
     * How much surrounding time defines "comparable mobility" for matching.
     *
     * 2 minutes. Long enough to hold several cell changes at every rate this project has ever
     * recorded — 0.4/min parked, 2.1/min across the 25-minute excursion, 16/min at the walking
     * peak — and short enough to describe one stretch of movement rather than a whole journey.
     * A window much shorter would score most changes as "1" and match everything to everything.
     */
    const val CONTROL_CONTEXT_MS = 120_000L

    /**
     * How far apart two changes' surrounding churn may be and still be called comparable.
     *
     * Two changes, so a case sitting in nine surrounding changes can match a control in seven to
     * eleven. Tightening this to zero costs matched pairs without moving the estimate; loosening
     * it starts pairing a busy minute with a quiet one, which is the confound the matching exists
     * to remove. It is reported in [summary] so the reader can see what "comparable" meant.
     */
    const val CHURN_MATCH_TOLERANCE = 2

    /**
     * The outcome window, measured from the anchor, and identical for both arms.
     *
     * The lead is [Mobility.PING_PONG_MS] plus 6 s. A case is anchored on its return, and its
     * departure can be up to a full ping-pong window earlier, so the lead has to reach back over
     * the whole pair or half of every case would be unobserved. The extra 6 s is [Mobility]'s own
     * reason for looking backwards at all: a connection can collapse and the new serving cell
     * appear in the snapshot afterwards.
     *
     * The lag is 6 s, which is `CellProbe`'s timeout and the width of the stall this project has
     * already measured. A failure later than that is its own story.
     *
     * A control gets the same 42 s window around its own change. Equal exposure in both arms is
     * what makes the rates comparable; an outcome window sized to each case's actual pair length
     * would have handed the cases more chances to show harm than the controls.
     */
    const val OUTCOME_LEAD_MS = Mobility.PING_PONG_MS + 6_000L
    const val OUTCOME_LAG_MS = 6_000L

    /**
     * Below this fraction of wall time covered by fresh readings, the stream was too blind for a
     * count of cell changes to mean anything. See [Report.timeCoverage].
     */
    const val MIN_COVERAGE = 0.30

    /**
     * Fewer returns than this and there is no sample. 20 is not a power calculation — it is the
     * point below which a Wilson interval on a rate spans most of the unit interval and the
     * comparison could not distinguish anything from anything.
     */
    const val MIN_EVENTS = 20

    /** Same floor on the matched pairs, applied per comparison, including each split arm. */
    const val MIN_MATCHED = 20

    // ------------------------------------------------------------------------------------------
    //  Result types
    // ------------------------------------------------------------------------------------------

    enum class Verdict {
        /** A validity gate failed. No effect size is computed or reported. See [Report.invalid]. */
        INVALID,
        /** Both arms measured, intervals overlap. A finding, not an absence of one. */
        NO_ASSOCIATION,
        /** The case rate's lower bound sits above the control rate's upper bound. */
        HARMFUL,
        /** The reverse: returns sat beside *fewer* adverse outcomes than ordinary changes. */
        PROTECTIVE
    }

    /** Whether a return crossed sectors of one mast or crossed masts. */
    enum class Kind { SECTOR, MAST, UNDECOMPOSABLE }

    /** One arm's tally. Counts only, so the rate and its interval are formed in one place. */
    class Arm {
        var n = 0
        var withProbeFailure = 0
        var withRouteLoss = 0
        var withServiceDrop = 0
        /** Windows carrying at least one of the three. The primary endpoint. */
        var withAny = 0

        /** Latencies of probes that SUCCEEDED inside the windows, for the companion measure. */
        val okLatency = ArrayList<Int>()

        val harmFrac: Double get() = if (n == 0) 0.0 else withAny / n.toDouble()
        val harmCi: DoubleArray get() = MapBinBuilder.wilson(harmFrac, n)
        val latencyP50: Int? get() = MapProbeJoin.percentile(okLatency.sorted(), 0.50)
        val latencyP90: Int? get() = MapProbeJoin.percentile(okLatency.sorted(), 0.90)
    }

    /**
     * One case-versus-control test. [invalid] non-null means the gates stopped it before any
     * effect size existed, and [verdict] is then [Verdict.INVALID] — the ordering is deliberate
     * and matches `KeepaliveExperiment.summary`: validity is the first question, not a footnote.
     */
    class Comparison(
        val label: String,
        val cases: Arm,
        val controls: Arm,
        val caseChurnMedian: Int,
        val controlChurnMedian: Int,
        val invalid: String?
    ) {
        val verdict: Verdict
            get() {
                if (invalid != null) return Verdict.INVALID
                val a = cases.harmCi
                val b = controls.harmCi
                return when {
                    a[0] > b[1] -> Verdict.HARMFUL
                    a[1] < b[0] -> Verdict.PROTECTIVE
                    else -> Verdict.NO_ASSOCIATION
                }
            }

        /**
         * What an overlap does and does not rule out, in the units the reader cares about.
         *
         * "No detectable association" is only as strong as the sample behind it, and the honest
         * form of that statement is the case rate's own upper bound: a ping-pong harm rate above
         * this was not happening, and anything below it would not have shown at this n. Without
         * this line a null result reads as proof of safety, which it is not.
         */
        val caseUpperBoundPct: Double get() = cases.harmCi[1] * 100
    }

    /**
     * The whole analysis. Validity fields come first because they are read first.
     *
     * [invalid] non-null means no comparison was attempted at all: an empty database, a frozen
     * radio stream, or too few returns to test. A fresh install lands here, and says so.
     */
    class Report(
        val radioRows: Int,
        val freshRows: Int,
        val subscriptions: Int,
        val cellChanges: Int,
        val pingPongEvents: Int,
        val candidateControls: Int,
        val unmatchedCases: Int,
        /** Returns where either leg was read from a `poll` row. See [readChanges]. */
        val pollDerivedEvents: Int,
        val usableProbes: Int,
        val instrumentRowsExcluded: Int,
        val routeLossRows: Int,
        val serviceDropRows: Int,
        val overall: Comparison?,
        val sector: Comparison?,
        val mast: Comparison?,
        val invalid: String?,
        /** Set only when the analysis itself threw. Distinct from "no data". */
        val error: String? = null,
        /** Wall time within [TimeWeight.CAP_MS] of a fresh reading, summed over subscriptions. */
        val freshMs: Long = 0,
        /** Wall time from each subscription's first stored row to its last, summed. */
        val spanMs: Long = 0
    ) {
        /**
         * Fraction of stored radio ROWS that carried a reading nobody had already counted.
         *
         * A statement about the table, not about the time. Screen-on callbacks write dozens of
         * fresh rows a minute and a deferred overnight poll writes one repeated row every several
         * minutes, so this figure is dominated by whenever the screen was on. Kept because it
         * says how much of the table is repetition; not used as the gate.
         */
        val coverage: Double get() = if (radioRows == 0) 0.0 else freshRows / radioRows.toDouble()

        /**
         * Fraction of wall time during which a fresh reading was at most [TimeWeight.CAP_MS] old.
         *
         * This is the figure the gate reads, because the question the gate asks is about time:
         * could a cell change during this run have been seen? A change that happens while the
         * stream is frozen is missed whether the table holds one repeated row for that stretch or
         * a hundred. On the reference data of 2026-09-13 the two disagreed widely -- 72 % of rows
         * fresh against 48 % of time covered -- which is the row weighting this replaces.
         */
        val timeCoverage: Double get() = TimeWeight.fraction(freshMs, spanMs) ?: 0.0
    }

    // ------------------------------------------------------------------------------------------
    //  Entry point
    // ------------------------------------------------------------------------------------------

    /**
     * Runs the whole analysis over the stored rows. Safe to call on a fresh install, safe to call
     * from anywhere, and never throws: a database that cannot be opened comes back as a Report
     * with [Report.error] set, which the UI renders the same way it renders "not enough data".
     *
     * Reads go through Room's own open helper, exactly as [MapProbeJoin.read] and
     * [MapBinBuilder] do it — `store/Db.kt` is shared, and this analysis is not worth a DAO
     * method or the schema churn of one.
     *
     * Not cheap: it walks every radio row once. Call it off the main thread.
     */
    fun analyse(ctx: Context): Report = runCatching { analyseInner(ctx) }.getOrElse { e ->
        empty(e.message ?: e.javaClass.simpleName)
    }

    private fun empty(error: String?) = Report(
        radioRows = 0, freshRows = 0, subscriptions = 0, cellChanges = 0, pingPongEvents = 0,
        candidateControls = 0, unmatchedCases = 0, pollDerivedEvents = 0, usableProbes = 0,
        instrumentRowsExcluded = 0, routeLossRows = 0, serviceDropRows = 0,
        overall = null, sector = null, mast = null,
        invalid = "No radio samples stored yet — nothing to analyse.", error = error
    )

    private fun analyseInner(ctx: Context): Report {
        val db = Db.get(ctx).openHelper.readableDatabase
        val outcomes = readOutcomes(db)
        val scan = readChanges(db)

        if (scan.radioRows == 0) return empty(null)

        val changes = scan.changes
        val cases = changes.filter { it.isReturn }
        val controls = changes.filter { !it.partOfPingPong }

        cases.forEach { score(it, outcomes) }
        controls.forEach { score(it, outcomes) }

        val pairs = match(cases, controls)

        val gate = when {
            scan.freshRows == 0 ->
                "No fresh radio readings — every stored row repeats the one before it."
            (TimeWeight.fraction(scan.freshMs, scan.spanMs) ?: 0.0) < MIN_COVERAGE ->
                ("COVERAGE %.0f %% OF THE TIME — for most of the run no fresh radio reading was " +
                    "less than %d s old. Cell changes cannot be counted from a stream that blind, " +
                    "so neither can returns.")
                        .format((TimeWeight.fraction(scan.freshMs, scan.spanMs) ?: 0.0) * 100,
                            TimeWeight.CAP_MS / 1000)
            cases.size < MIN_EVENTS ->
                ("TOO FEW EVENTS — %d ping-pong returns across %d cell changes; %d are needed " +
                    "before a rate means anything.").format(cases.size, changes.size, MIN_EVENTS)
            pairs.isEmpty() ->
                ("NO MATCHED CONTROLS — %d returns, but no ordinary cell change with comparable " +
                    "surrounding churn to compare them against. The phone's non-ping-pong " +
                    "mobility does not overlap its ping-pong mobility in this data.")
                        .format(cases.size)
            else -> null
        }

        val overall = if (gate != null) null else compare("All returns", pairs)
        val sector = if (gate != null) null
            else compare("Sector returns (one mast)", pairs.filter { it.first.kind == Kind.SECTOR })
        val mast = if (gate != null) null
            else compare("Mast returns (two masts)", pairs.filter { it.first.kind == Kind.MAST })

        return Report(
            radioRows = scan.radioRows,
            freshRows = scan.freshRows,
            subscriptions = scan.subscriptions,
            cellChanges = changes.size,
            pingPongEvents = cases.size,
            candidateControls = controls.size,
            unmatchedCases = cases.size - pairs.size,
            pollDerivedEvents = cases.count { it.pollDerived },
            usableProbes = outcomes.usableProbes,
            instrumentRowsExcluded = outcomes.instrumentExcluded,
            routeLossRows = outcomes.routeLoss.size,
            serviceDropRows = outcomes.serviceDrops.values.sumOf { it.size },
            overall = overall, sector = sector, mast = mast,
            invalid = gate,
            freshMs = scan.freshMs,
            spanMs = scan.spanMs
        )
    }

    // ------------------------------------------------------------------------------------------
    //  Reading: the changes
    // ------------------------------------------------------------------------------------------

    /** One serving-cell change on one subscription, and what landed near it. */
    private class Change(
        val t: Long,
        val subId: Int,
        val fromCi: Long,
        val toCi: Long,
        /** The mast decomposition of the *departure* leg, which is the move that was undone. */
        var kind: Kind = Kind.UNDECOMPOSABLE,
        var partOfPingPong: Boolean = false,
        /** True on the return leg only. One physical ping-pong yields exactly one case. */
        var isReturn: Boolean = false,
        var pollDerived: Boolean = false,
        var churn: Int = 0,
        var probeFailure: Boolean = false,
        var routeLoss: Boolean = false,
        var serviceDrop: Boolean = false,
        val okLatency: MutableList<Int> = ArrayList()
    ) {
        val harm: Boolean get() = probeFailure || routeLoss || serviceDrop
    }

    private class Scan(
        val changes: List<Change>,
        val radioRows: Int,
        val freshRows: Int,
        val subscriptions: Int,
        val freshMs: Long,
        val spanMs: Long
    )

    /**
     * Walks `radio_sample` once and turns it into serving-cell changes per subscription.
     *
     * **Freshness.** A row whose entire radio payload equals the previous row's on the same
     * subscription is a re-read of a snapshot nobody refreshed, and is not a second reading. The
     * platform stops pushing to a background app when the screen goes off, so these are not rare:
     * they are most of what a quiet night stores. They cannot manufacture a cell change — an
     * identical row has an identical cell — but they dominate the denominator, and a coverage
     * figure computed without removing them would call a blind night well observed.
     *
     * **Per subscription, always.** The cell sequences of two SIMs interleaved in one table would
     * read as a change on every alternating row. Subscriptions are whatever the table contains;
     * nothing here knows or assumes how many there are, which one carries data, or who the
     * carrier is.
     *
     * **`mode`.** A `poll` row is an active pull and can return a cached `CellInfo` that the
     * platform has not refreshed — `data-model.md` §1 measured it up to 1440 s stale. A stale
     * pull showing the previous cell is the one way this data can *fabricate* a return, so any
     * event with a `poll` row on either leg is flagged and counted. It is reported rather than
     * dropped, because on the data this was written against the flagged events were under a tenth
     * of the total — the returns are overwhelmingly event-pushed on both legs — and dropping them
     * silently would have been a bigger distortion than keeping them visible.
     */
    private fun readChanges(db: androidx.sqlite.db.SupportSQLiteDatabase): Scan {
        var rows = 0
        var fresh = 0
        val subs = HashSet<Int>()

        // Per subscription: the last accepted payload, and what the last accepted row said.
        val lastPayload = HashMap<Int, List<Any?>>()
        val lastCi = HashMap<Int, Long>()
        val lastRat = HashMap<Int, String>()
        val lastMode = HashMap<Int, String>()
        val perSub = HashMap<Int, MutableList<Change>>()

        // Clocks of every row, and of the fresh ones, for the two coverage figures. Every row
        // sets the span, since a frozen stretch at either end is still part of the run; only
        // fresh rows carry weight.
        val allT = LongArrayBuilder(); val allWall = LongArrayBuilder(); val allSub = IntArrayBuilder()
        val frT = LongArrayBuilder(); val frWall = LongArrayBuilder(); val frSub = IntArrayBuilder()

        db.query(
            "SELECT elapsedNanos, subId, mode, rat, servingCi, servingPci, bandNum, " +
                "rsrp, rsrq, rssnr, wallMillis FROM radio_sample ORDER BY elapsedNanos ASC"
        ).use { c ->
            while (c.moveToNext()) {
                rows++
                val sub = c.getInt(1)
                subs += sub
                val tMs = c.getLong(0) / 1_000_000L
                val wall = c.getLong(10)
                allT.add(tMs); allWall.add(wall); allSub.add(sub)
                val mode = if (c.isNull(2)) "?" else c.getString(2)
                val rat = if (c.isNull(3)) "UNKNOWN" else c.getString(3)
                val ci = if (c.isNull(4)) null else c.getLong(4)
                val payload = listOf(
                    rat, ci,
                    if (c.isNull(5)) null else c.getInt(5),
                    if (c.isNull(6)) null else c.getInt(6),
                    if (c.isNull(7)) null else c.getInt(7),
                    if (c.isNull(8)) null else c.getInt(8),
                    if (c.isNull(9)) null else c.getInt(9)
                )
                if (payload == lastPayload[sub]) continue      // nothing refreshed this row
                lastPayload[sub] = payload
                fresh++
                frT.add(tMs); frWall.add(wall); frSub.add(sub)
                if (ci == null) continue                        // fresh, but says nothing about a cell

                val t = c.getLong(0) / 1_000_000L
                val prevCi = lastCi[sub]
                if (prevCi != null && prevCi != ci) {
                    val ch = Change(t = t, subId = sub, fromCi = prevCi, toCi = ci)
                    // The departure's own decomposition: was the cell we left a sector of the
                    // mast we moved to, or a different mast? CellShare owns that rule.
                    ch.kind = kindOf(lastRat[sub] ?: rat, prevCi, rat, ci)
                    ch.pollDerived = mode == "poll" || lastMode[sub] == "poll"
                    perSub.getOrPut(sub) { ArrayList() }.add(ch)
                }
                lastCi[sub] = ci
                lastRat[sub] = rat
                lastMode[sub] = mode
            }
        }

        val all = ArrayList<Change>()
        perSub.values.forEach { list ->
            markPingPong(list)
            markChurn(list)
            all += list
        }
        all.sortBy { it.t }

        // Each fresh reading stands for the time until the next fresh reading on its subscription,
        // capped: the same sample-and-hold rule the map uses, applied to freshness.
        val span = TimeWeight.weigh(allT.build(), allWall.build(), allSub.build()).spanMs
        val freshMs = TimeWeight.weigh(frT.build(), frWall.build(), frSub.build()).observedMs
        return Scan(all, rows, fresh, subs.size, freshMs, span)
    }

    /** Growable primitive arrays, so a week of rows is not boxed twice to be weighed. */
    private class LongArrayBuilder {
        private var a = LongArray(1024); private var n = 0
        fun add(v: Long) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun build(): LongArray = a.copyOf(n)
    }

    private class IntArrayBuilder {
        private var a = IntArray(1024); private var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun build(): IntArray = a.copyOf(n)
    }

    /**
     * Sector or mast, by [MapProbeJoin.CellShare]'s rule and no other.
     *
     * `CI = eNodeB << 8 | sector` holds for LTE inside the 28-bit ECI range and is not guessable
     * for NR, where the gNB-ID length is configurable. Rather than restate the test, a CellShare
     * is built for each end of the move and asked for its `enb`; where either end declines to
     * decompose the answer is [Kind.UNDECOMPOSABLE], which is an honest third category and not a
     * default into one of the other two.
     */
    private fun kindOf(fromRat: String, fromCi: Long, toRat: String, toCi: Long): Kind {
        val a = MapProbeJoin.CellShare(fromRat, fromCi, null, null, 0).enb
        val b = MapProbeJoin.CellShare(toRat, toCi, null, null, 0).enb
        return when {
            a == null || b == null -> Kind.UNDECOMPOSABLE
            a == b -> Kind.SECTOR
            else -> Kind.MAST
        }
    }

    /**
     * Finds returns inside [Mobility.PING_PONG_MS], the project's one definition of a ping-pong.
     *
     * Both legs are marked [Change.partOfPingPong] so neither can serve as a control, but only
     * the return carries [Change.isReturn] and becomes a case. A departure is paired with the
     * first return that undoes it: with three changes A→B→A→B the middle change closes one
     * ping-pong, and leaving the pairing greedy rather than counting every matching suffix stops
     * a flapping run of n changes from reporting n² events.
     */
    private fun markPingPong(list: List<Change>) {
        for (i in list.indices) {
            val dep = list[i]
            if (dep.isReturn) continue          // already spent closing an earlier departure
            for (j in i + 1 until list.size) {
                val ret = list[j]
                if (ret.t - dep.t > Mobility.PING_PONG_MS) break
                if (ret.toCi == dep.fromCi) {
                    dep.partOfPingPong = true
                    ret.partOfPingPong = true
                    ret.isReturn = true
                    // The physical event is the departure's move, so the case inherits its
                    // decomposition: the return is by definition the same pair of cells.
                    ret.kind = dep.kind
                    ret.pollDerived = ret.pollDerived || dep.pollDerived
                    break
                }
            }
        }
    }

    /** The matching variable: how much ordinary cell churn surrounds each change. */
    private fun markChurn(list: List<Change>) {
        var lo = 0
        var hi = 0
        for (i in list.indices) {
            val t = list[i].t
            while (lo < list.size && list[lo].t < t - CONTROL_CONTEXT_MS) lo++
            while (hi < list.size && list[hi].t <= t + CONTROL_CONTEXT_MS) hi++
            list[i].churn = hi - lo
        }
    }

    // ------------------------------------------------------------------------------------------
    //  Reading: the outcomes
    // ------------------------------------------------------------------------------------------

    private class Outcomes(
        /** Monotonic ms of every probe failure that describes the network. */
        val probeFailures: List<Long>,
        /** (t, latency) of every probe that succeeded, for the companion latency measure. */
        val probeOk: List<Pair<Long, Int>>,
        val routeLoss: List<Long>,
        val serviceDrops: Map<Int, List<Long>>,
        val usableProbes: Int,
        val instrumentExcluded: Int
    )

    private fun readOutcomes(db: androidx.sqlite.db.SupportSQLiteDatabase): Outcomes {
        val fails = ArrayList<Long>()
        val oks = ArrayList<Pair<Long, Int>>()
        var usable = 0
        var instrument = 0

        db.query(
            "SELECT elapsedNanos, netId, probeType, outcome, latencyMs, errorCode " +
                "FROM probe_result ORDER BY elapsedNanos ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val type = if (c.isNull(2)) "?" else c.getString(2)
                val err = if (c.isNull(5)) null else c.getString(5)
                // First, before any counting: a probe the app could not bind to the cellular
                // network describes this app. 248 of these read as network failures once and
                // produced eight phantom failed handovers and 21 phantom route-loss bins.
                if (CellProbe.kindOf(type, err) == CellProbe.Kind.INSTRUMENT) {
                    instrument++
                    continue
                }
                // Stall summaries are written because something failed, so they would put a second
                // outcome beside any cell change that happened to share the stall.
                if (CellProbe.kindOf(type, err) == CellProbe.Kind.RECOVERY) continue
                val netId = if (c.isNull(1)) "" else c.getString(1)
                // No cellular network existed to bind to. An absence of measurement, not a
                // failure at this moment — the same call MapProbeJoin.ProbeRow.onBearer makes.
                if (netId.isEmpty() || netId == "—") continue
                usable++
                val t = c.getLong(0) / 1_000_000L
                if (!c.isNull(3) && c.getString(3) == "OK") oks += t to c.getInt(4) else fails += t
            }
        }

        val loss = ArrayList<Long>()
        db.query(
            "SELECT elapsedNanos FROM link_event WHERE isDefault = 1 AND transport = 'CELLULAR' " +
                "AND validated = 0 ORDER BY elapsedNanos ASC"
        ).use { c -> while (c.moveToNext()) loss += c.getLong(0) / 1_000_000L }

        // Per subscription, because registration is the one outcome this schema can attribute to
        // a SIM. Probe and link rows cannot be: `probe_result` has no subId (store/Db.kt) and a
        // link describes whichever bearer held the cellular request.
        val drops = HashMap<Int, MutableList<Long>>()
        db.query(
            "SELECT elapsedNanos, subId FROM registration_event WHERE regState <> 'IN_SERVICE' " +
                "ORDER BY elapsedNanos ASC"
        ).use { c ->
            while (c.moveToNext()) {
                drops.getOrPut(c.getInt(1)) { ArrayList() } += c.getLong(0) / 1_000_000L
            }
        }

        return Outcomes(fails, oks, loss, drops, usable, instrument)
    }

    /** Marks one change with whatever landed in its window. Same window for both arms. */
    private fun score(ch: Change, o: Outcomes) {
        val lo = ch.t - OUTCOME_LEAD_MS
        val hi = ch.t + OUTCOME_LAG_MS
        ch.probeFailure = o.probeFailures.any { it in lo..hi }
        ch.routeLoss = o.routeLoss.any { it in lo..hi }
        ch.serviceDrop = o.serviceDrops[ch.subId]?.any { it in lo..hi } == true
        o.probeOk.forEach { (t, ms) -> if (t in lo..hi) ch.okLatency += ms }
    }

    // ------------------------------------------------------------------------------------------
    //  Matching and comparison
    // ------------------------------------------------------------------------------------------

    /**
     * 1:1 nearest-churn matching without replacement, within a subscription.
     *
     * Within a subscription because the two SIMs of a dual-SIM handset sit on different networks
     * with different churn, and a case on one matched to a control on the other would be
     * comparing carriers. Without replacement because reusing one quiet control for twenty cases
     * would shrink the control interval on evidence that is not there.
     */
    private fun match(cases: List<Change>, controls: List<Change>): List<Pair<Change, Change>> {
        val pool = HashMap<Pair<Int, Int>, ArrayDeque<Change>>()
        controls.forEach { pool.getOrPut(it.subId to it.churn) { ArrayDeque() }.addLast(it) }

        val out = ArrayList<Pair<Change, Change>>()
        for (cs in cases) {
            var found: Change? = null
            var d = 0
            while (found == null && d <= CHURN_MATCH_TOLERANCE) {
                for (delta in if (d == 0) listOf(0) else listOf(-d, d)) {
                    val q = pool[cs.subId to (cs.churn + delta)]
                    if (q != null && q.isNotEmpty()) { found = q.removeFirst(); break }
                }
                d++
            }
            found?.let { out += cs to it }
        }
        return out
    }

    private fun compare(label: String, pairs: List<Pair<Change, Change>>): Comparison {
        val cases = Arm()
        val controls = Arm()
        pairs.forEach { (a, b) -> tally(cases, a); tally(controls, b) }

        val invalid = if (pairs.size < MIN_MATCHED)
            "only %d matched pairs — under the %d this comparison needs, so no rate is quoted"
                .format(pairs.size, MIN_MATCHED)
        else if (cases.withAny == 0 && controls.withAny == 0)
            ("no probe failure, route loss or service drop landed beside ANY change in either " +
                "arm across %d pairs. There is no adverse outcome here to associate with " +
                "anything, which is an absence of harm in both arms rather than a measured " +
                "equality between them.").format(pairs.size)
        else null

        return Comparison(
            label = label,
            cases = cases, controls = controls,
            caseChurnMedian = median(pairs.map { it.first.churn }),
            controlChurnMedian = median(pairs.map { it.second.churn }),
            invalid = invalid
        )
    }

    private fun tally(arm: Arm, ch: Change) {
        arm.n++
        if (ch.probeFailure) arm.withProbeFailure++
        if (ch.routeLoss) arm.withRouteLoss++
        if (ch.serviceDrop) arm.withServiceDrop++
        if (ch.harm) arm.withAny++
        arm.okLatency += ch.okLatency
    }

    private fun median(v: List<Int>): Int = if (v.isEmpty()) 0 else v.sorted()[v.size / 2]

    // ------------------------------------------------------------------------------------------
    //  Reporting
    // ------------------------------------------------------------------------------------------

    /**
     * The report the UI shows. Validity first, then the effect, then what the effect rests on —
     * the order `Mobility.summary` and `KeepaliveExperiment.summary` both use, for the reason
     * both of them give: a run that saw nothing produces a clean-looking null result, so nothing
     * downstream may read a number before it has read whether the number means anything.
     */
    fun summary(r: Report): String = buildString {
        r.error?.let { append("Could not read the database: $it\n") }

        append("%,d radio rows, %,d of them a reading nobody had counted (%.0f %% of rows)\n"
            .format(r.radioRows, r.freshRows, r.coverage * 100))
        if (r.spanMs > 0) {
            append(("a fresh reading under %d s old for %.0f %% of the %.1f h the rows span " +
                "(coverage by time — the figure the verdict is gated on)\n")
                    .format(TimeWeight.CAP_MS / 1000, r.timeCoverage * 100, r.spanMs / 3_600_000.0))
        }
        if (r.radioRows > 0) {
            append("%d subscription(s), %,d serving-cell changes, %d ping-pong returns within %d s\n"
                .format(r.subscriptions, r.cellChanges, r.pingPongEvents,
                    Mobility.PING_PONG_MS / 1000))
        }
        append("excluded %,d instrument rows (socket bind failures, not network outcomes); "
            .format(r.instrumentRowsExcluded))
        append("%,d probes usable, %d route losses, %d service drops in the whole database\n"
            .format(r.usableProbes, r.routeLossRows, r.serviceDropRows))

        if (r.invalid != null) {
            append("\nNO VERDICT — ").append(r.invalid).append("\n")
            return@buildString
        }

        append("\nControl: an ordinary serving-cell change on the same subscription that was NOT ")
        append("returned to within %d s, matched to a case on the number of cell changes within "
            .format(Mobility.PING_PONG_MS / 1000))
        append("%d s either side of it (tolerance %d changes). The comparison is against ordinary "
            .format(CONTROL_CONTEXT_MS / 1000, CHURN_MATCH_TOLERANCE))
        append("mobility of the same intensity, not against sitting still.\n")
        if (r.unmatchedCases > 0) {
            append("%d of %d returns found no comparable control and are excluded from the rates.\n"
                .format(r.unmatchedCases, r.pingPongEvents))
        }
        if (r.pollDerivedEvents > 0) {
            append(("%d of the returns had a 'poll' row on a leg; a stale cached CellInfo can " +
                "fake a return, so they are flagged rather than trusted blindly.\n")
                .format(r.pollDerivedEvents))
        }

        listOfNotNull(r.overall, r.sector, r.mast).forEach { append('\n').append(line(it)) }
    }

    private fun line(c: Comparison): String = buildString {
        append(c.label).append(":\n")
        if (c.invalid != null) {
            append("  CANNOT TEST — ").append(c.invalid).append("\n")
            return@buildString
        }
        val a = c.cases.harmCi
        val b = c.controls.harmCi
        append("  ping-pong   %d/%d  %.1f %% [%.1f–%.1f]\n".format(
            c.cases.withAny, c.cases.n, c.cases.harmFrac * 100, a[0] * 100, a[1] * 100))
        append("  ordinary    %d/%d  %.1f %% [%.1f–%.1f]\n".format(
            c.controls.withAny, c.controls.n, c.controls.harmFrac * 100, b[0] * 100, b[1] * 100))
        append("  evidence — probe failure %d/%d, route loss %d/%d, service drop %d/%d ".format(
            c.cases.withProbeFailure, c.controls.withProbeFailure,
            c.cases.withRouteLoss, c.controls.withRouteLoss,
            c.cases.withServiceDrop, c.controls.withServiceDrop))
        append("(ping-pong/ordinary)\n")
        append("  churn matched at %d vs %d changes per %d s\n"
            .format(c.caseChurnMedian, c.controlChurnMedian, CONTROL_CONTEXT_MS * 2 / 1000))
        val cp50 = c.cases.latencyP50; val tp50 = c.controls.latencyP50
        if (cp50 != null && tp50 != null) {
            append("  successful-probe latency p50 %d vs %d ms, p90 %d vs %d ms (%d vs %d probes)\n"
                .format(cp50, tp50, c.cases.latencyP90 ?: 0, c.controls.latencyP90 ?: 0,
                    c.cases.okLatency.size, c.controls.okLatency.size))
        }
        append("  ").append(when (c.verdict) {
            Verdict.HARMFUL ->
                "PING-PONG IS ASSOCIATED WITH HARM here — the intervals do not overlap. This is " +
                    "an association in observational data, not a demonstrated cause."
            Verdict.PROTECTIVE ->
                "Returns sat beside FEWER adverse outcomes than ordinary cell changes — the " +
                    "intervals do not overlap in that direction. Treat as a signal that the " +
                    "matching is not capturing something, not as a benefit of ping-pong."
            Verdict.NO_ASSOCIATION ->
                ("NO DETECTABLE ASSOCIATION. The intervals overlap: on this data a ping-pong " +
                 "return is not distinguishable from an ordinary cell change of the same " +
                 "mobility. What %d pairs can exclude is a ping-pong harm rate above %.1f %%, " +
                 "against %.1f %% for ordinary changes; a smaller difference than that would " +
                 "not show here, so this is a bound and not a proof of harmlessness.")
                    .format(c.cases.n, c.caseUpperBoundPct, c.controls.harmFrac * 100)
            Verdict.INVALID -> "no verdict"
        }).append("\n")
    }
}
