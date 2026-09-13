package com.signalscope.store

import android.content.Context
import com.signalscope.collect.CellProbe
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Tiered compaction: history that costs less as it ages, without the history disappearing.
 *
 * ## Why
 *
 * Two days of collection on the reference handset produced 18,900 `radio_sample` rows, 3,800
 * `registration_event`, 1,480 `link_event` and 660 `probe_result` — about 10,000 radio rows a day,
 * and rising, because polling densifies while the phone is moving. At `data-model.md` §7's own
 * figure of ~200 bytes a row that is roughly 2.0 MB/day of radio plus ~0.5 MB/day of everything
 * else, so a full 30-day window is 75 MB at rest and comfortably over 100 MB for someone who
 * travels. [Retention] used to resolve that by deleting raw rows at 30 days, which bounds the size
 * and throws the history away.
 *
 * A month-old second-by-second RSRP trace is not worth its bytes. The *distribution* it came from
 * is: that is what every panel in this app actually derives — percentiles, churn rates, probe
 * outcome rates per cell, per band, per subscription. So old raw becomes a roll-up of exactly
 * those, and the roll-up is one to two orders of magnitude smaller.
 *
 * ## The tiers, and where each boundary came from
 *
 * | Tier | Age | Contents | Rate |
 * |---|---|---|---|
 * | 0 raw | 0 – 30 d (floor 7 d under pressure) | `radio_sample` &c. exactly as collected | ~2.5 MB/day |
 * | 1 hour | only the part of 0–30 d that pressure pulled in | hourly buckets, keyed incl. cell | ~10–60 KB/day |
 * | 2 day | 30 d – 730 d | daily buckets, **no cell, no timing advance** | ~1–3 KB/day |
 *
 * - **30 days** is not a new number. `data-model.md` §7 says raw is dropped there and the security
 *   review's P2 finding is that this line is what bounds the movement history `radio_sample`
 *   constitutes. This file does not move that line. It changes what is *on the near side* of it
 *   from "nothing" to "an hourly roll-up", and what is on the far side from "nothing" to "a
 *   per-carrier, per-band distribution with no cell in it".
 * - **7 days** is §7's *"keep full resolution 7 days"*, used here as a floor rather than a
 *   deadline: the storage ceiling in [Retention] is allowed to pull the raw boundary in from 30
 *   days towards 7, and is not allowed to cross it. Below a week there is no longer enough raw
 *   for the incident engine or a map rebuild to mean anything.
 * - **730 days** exists so that nothing in this app is literally unbounded. Tier 2 runs at ~1–3 KB
 *   a day, so two years is under 2 MB — the cap costs nothing and it means the honest answer to
 *   "how long does this app keep anything" is a number.
 *
 * ## The identity line — the rule that makes the tiering safe
 *
 * **A tier may only survive past the age of the tier it replaces if it is strictly less
 * identifying than that tier.**
 *
 * `radio_sample`'s serving-cell columns over time are a movement history — the security review
 * says so, and says it is collected under `READ_PHONE_STATE` whether or not location was ever
 * granted. Retention is what bounds it. So:
 *
 * - Tier 1 keeps cell identity (`servingCi`/`servingPci`) and `timingAdvance`, which is a range
 *   from the mast and therefore also a position signal. It is allowed to, because it never lives
 *   past 30 days — the same window the raw rows it replaced already occupied. It is a strict
 *   narrowing: per-second becomes per-hour.
 * - Tier 2 lives past 30 days, so it drops cell identity and timing advance entirely. What
 *   survives is `(subscription, PLMN, band, RAT)` with counts and distributions, plus *how many*
 *   distinct cells were seen and *how often* the serving cell changed — churn without the trace.
 *
 * Neither tier carries a position bin. `map_fix` already holds binned position and [Retention]
 * already sweeps it at 30 days; putting a bin in a roll-up would be the one change here that
 * widened something, so there is none. The cost is stated in the report: `MapBinBuilder` reads raw
 * and therefore only sees the raw window.
 *
 * ## Roll-ups are mergeable, which is the whole design constraint
 *
 * The rule `MapBins` already follows: **store counts and enough to recompute, never an average of
 * averages.** Concretely, every field here is one of four shapes, and each has a defined merge:
 *
 * | Shape | Merge | Example |
 * |---|---|---|
 * | count | sum | `n`, `cellChanges`, `probeFail` |
 * | sum + count pair | sum both, divide at the end | `neighbourSum`/`neighbourN` |
 * | histogram (sparse `value -> count`) | sum per key | `rsrp`, `rsrq`, `rssnr`, probe latency |
 * | set | union, cardinality at the end | the covered-minute mask |
 *
 * There is no mean and no percentile stored anywhere — only the histograms they are computed from.
 * Because the radio quality fields arrive as whole dBm/dB integers, a **1-unit** histogram is a
 * lossless reordering of the sample multiset, so [percentile] over a merged histogram returns
 * exactly what the same nearest-rank rule ([MapProbeJoin.percentile]) would have returned over the
 * raw rows. Merging a month of them is exact, not approximate.
 *
 * ## Absent data must not read as good data
 *
 * Every bucket carries how many samples it came from (`n`) and what coverage they represent
 * (`coveredMinutes` out of the period's length). A bucket built from four samples in one minute of
 * an hour is visibly not a bucket built from 400 samples across 55 minutes, and nothing in the
 * format lets the two look alike. Per-field null counts are carried separately (`nRsrp` &c.)
 * because a sample with no RSRP is not a sample with a good RSRP — the same reason `nrAnchor` is
 * null rather than 1.0 in `data-model.md` §3.
 *
 * ## On-disk format
 *
 * ```
 * filesDir/rollup/hour/2026-09-05.jsonl.gz     one file per UTC day of hourly buckets
 * filesDir/rollup/day/2026-09.jsonl.gz         one file per UTC month of daily buckets
 * filesDir/rollup/.tmp/                        scratch; anything here is incomplete by definition
 * ```
 *
 * `filesDir`, not `getExternalFilesDir` — [RegionStore] puts basemaps on external storage because
 * they are large and re-downloadable. Measurements are neither.
 *
 * Each file is gzip over UTF-8 line-delimited JSON. Line 0 is a header; every later line is one
 * bucket. Gzip because this data is extremely repetitive and the platform already has the codec;
 * line-delimited because it makes the file readable with `zcat | head` by someone who has never
 * seen this code.
 *
 * Header:
 * ```json
 * {"f":"signalscope-rollup","v":1,"tier":"hour","period":"2026-09-05","rule":"rollup/v1",
 *  "consumed":{"radio_sample":18930,"registration_event":3801,"link_event":1482,"probe_result":661},
 *  "absorbed":[], "writtenMillis":1757000000000, "buckets":42}
 * ```
 *
 * `consumed` is the highest `rowid` of each raw table that this file provably contains. `absorbed`
 * is the list of day periods a tier-2 file has taken in. Together they are the idempotency
 * mechanism; see [compactDay].
 *
 * Bucket lines are tagged by `k`: `"r"` radio, `"g"` registration, `"l"` link, `"p"` probe,
 * `"i"` instrument health (from rule v2; a reader that predates it skips the line). Field
 * names are short because they repeat once per line; each is documented on the data class it
 * decodes into.
 *
 * ## Idempotency, and surviving being killed
 *
 * Compaction runs on a phone that gets killed mid-task, so "run it twice" is the normal case, not
 * the error case. Two properties do the work:
 *
 * 1. **The unit of work is one complete UTC day, and a day is only ever compacted once it can no
 *    longer receive rows.** UTC rather than local time because a local-time day boundary moves
 *    when the user crosses a timezone or the clock changes, which would make "2026-09-05" mean two
 *    different windows on two different runs.
 * 2. **A raw row is deleted only after a file on disk provably contains it, and the proof is its
 *    `rowid`.** Each pass reads rows with `rowid > consumed`, folds them into whatever the file
 *    already holds, writes the file atomically (temp + rename), and only then deletes raw rows
 *    with `rowid <= consumed`. Every kill point is safe:
 *    - before the rename: nothing changed; the rerun reads the same rows and produces the same
 *      file, because folding is a pure function of the rows.
 *    - after the rename, before the delete: the rerun reads rows above the new watermark, finds
 *      none, rewrites an identical file and redoes the delete. **It cannot double-count, because
 *      the file is replaced rather than appended to and the rows it would double-count are below
 *      the watermark.**
 *    - mid-delete: the remaining rows are deleted next time.
 *
 *    `rowid` is `AUTOINCREMENT`, so it is monotonic even across deletes; a row written later can
 *    never land below a watermark. That is what makes the watermark a safe fence rather than an
 *    optimisation.
 *
 * The tier-1 → tier-2 step uses the same shape with `absorbed` instead of `consumed`: absorb a day
 * only if it is not already listed, write the month file, then delete the day file.
 *
 * The one thing an interruption can cost is a single serving-cell change at a resumption boundary,
 * if raw rows for an already-compacted day appear afterwards (a corrected clock is the only real
 * way that happens). `cellChanges` can then undercount by one. It can never overcount, and it can
 * never double-count, which is the property that matters.
 *
 * ## Everything here is in runCatching
 *
 * Compaction must never crash the app. Every entry point returns a result object and swallows
 * failures into it; a corrupt or truncated roll-up file is skipped, not thrown, and a day whose
 * fold fails simply keeps its raw rows until the next sweep.
 */
object Compaction {

    const val FORMAT = "signalscope-rollup"
    const val FORMAT_VERSION = 1

    /**
     * Bumped when the fold changes meaning, so a reader can tell mixed vintages apart.
     *
     * v2: radio buckets key on the channel-derived band *and the band's own technology*
     * (`brat`), where v1 keyed on `getBands()[0]`, which could be empty and stored B40 and n40 as
     * the same 40. v2 also carries the NR SS triple separately from LTE, the quality-flag counts,
     * and a fifth bucket kind, `"i"`, for instrument health. A v1 bucket and a v2 bucket for the
     * same LTE band still merge, because for LTE the reported and derived bands agreed; a v1
     * bucket has no `brat` and so does not merge with a v2 one, which is the safe direction.
     */
    const val RULE_VERSION = "rollup/v2"

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val MINUTE_MS = 60L * 1000L

    enum class Tier(val dir: String) { HOUR("hour"), DAY("day") }

    /**
     * Tables whose rows become roll-ups. `instrument_event` is one because the periods the
     * instrument was blind are exactly what a two-year roll-up must still be able to say -- a
     * month of clean-looking daily buckets that were really a month of screen-off blindness is the
     * failure this project keeps rediscovering. `neighbour_cell` is deliberately absent: it has
     * no roll-up, and [Retention] sweeps it at the raw window.
     */
    private val RAW_TABLES = listOf("radio_sample", "registration_event", "link_event", "probe_result",
        "instrument_event")

    // =================================================================================
    //  Paths
    // =================================================================================

    fun root(ctx: Context): File = File(ctx.filesDir, "rollup").apply { mkdirs() }
    private fun tierDir(ctx: Context, t: Tier): File = File(root(ctx), t.dir).apply { mkdirs() }
    private fun tmpDir(ctx: Context): File = File(root(ctx), ".tmp").apply { mkdirs() }
    private fun fileFor(ctx: Context, t: Tier, period: String) =
        File(tierDir(ctx, t), "$period.jsonl.gz")

    /** Total bytes the roll-ups occupy. Shown next to the database size so the trade is visible. */
    fun bytes(ctx: Context): Long = runCatching {
        root(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    fun periods(ctx: Context, t: Tier): List<String> = runCatching {
        (tierDir(ctx, t).listFiles { f -> f.isFile && f.name.endsWith(".jsonl.gz") } ?: emptyArray())
            .map { it.name.removeSuffix(".jsonl.gz") }.sorted()
    }.getOrDefault(emptyList())

    // =================================================================================
    //  UTC period arithmetic
    // =================================================================================

    private fun utc(fmt: String) = SimpleDateFormat(fmt, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Unix time has no leap seconds, so a UTC day is exactly 86 400 000 ms from the epoch. */
    fun dayStart(millis: Long): Long = Math.floorDiv(millis, DAY_MS) * DAY_MS
    fun hourStart(millis: Long): Long = Math.floorDiv(millis, HOUR_MS) * HOUR_MS

    fun dayLabel(millis: Long): String = utc("yyyy-MM-dd").format(java.util.Date(dayStart(millis)))
    fun monthLabel(millis: Long): String = utc("yyyy-MM").format(java.util.Date(millis))

    private fun dayLabelStart(label: String): Long? =
        runCatching { utc("yyyy-MM-dd").parse(label)?.time }.getOrNull()

    private fun monthLabelStart(label: String): Long? =
        runCatching { utc("yyyy-MM").parse(label)?.time }.getOrNull()

    // =================================================================================
    //  Mergeable primitives
    // =================================================================================

    /**
     * Sparse histogram: value -> how many samples had it.
     *
     * The only distribution structure in the format. Merging is a per-key sum, which is
     * associative and commutative, so buckets can be combined in any order and any grouping and
     * give the same answer — that is what "mergeable" has to mean for a roll-up that gets merged
     * again at the next tier.
     */
    fun histAdd(h: HashMap<Int, Int>, v: Int) { h[v] = (h[v] ?: 0) + 1 }

    fun histMerge(a: Map<Int, Int>, b: Map<Int, Int>): Map<Int, Int> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = HashMap<Int, Int>(a)
        for ((k, v) in b) out[k] = (out[k] ?: 0) + v
        return out
    }

    fun histN(h: Map<Int, Int>): Int = h.values.sum()

    /**
     * Nearest-rank percentile over a histogram — deliberately the same rule as
     * [MapProbeJoin.percentile] over a sorted list, so the two agree to the value.
     *
     * Null for an empty histogram rather than 0: a value of zero and a value never measured are
     * opposite findings.
     */
    fun percentile(h: Map<Int, Int>, q: Double): Int? {
        val n = histN(h)
        if (n == 0) return null
        val target = Math.round(q * (n - 1)).toInt().coerceIn(0, n - 1)
        var seen = 0
        for (k in h.keys.sorted()) {
            seen += h.getValue(k)
            if (seen > target) return k
        }
        return h.keys.maxOrNull()
    }

    private fun tallyMerge(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = HashMap<String, Int>(a)
        for ((k, v) in b) out[k] = (out[k] ?: 0) + v
        return out
    }

    private fun tallyAdd(m: HashMap<String, Int>, k: String) { m[k] = (m[k] ?: 0) + 1 }

    /**
     * Probe latency bins: 10 ms up to a second, 100 ms up to ten, then one overflow bin.
     *
     * Unlike the radio fields these are not already quantised, so this one histogram *is* lossy —
     * to 10 ms below a second, which is well inside the noise of a TLS handshake over a mobile
     * bearer, and to 100 ms above it, where the finding is "slow" and the third digit is not
     * evidence. The key is the bin's lower edge in milliseconds so the file reads plainly.
     */
    fun latencyBin(ms: Int): Int = when {
        ms <= 0 -> 0
        ms < 1_000 -> (ms / 10) * 10
        ms < 10_000 -> (ms / 100) * 100
        else -> 10_000
    }

    // =================================================================================
    //  Buckets
    // =================================================================================

    /**
     * One hour (tier 1) or one day (tier 2) of radio samples for one cell — or, at tier 2, for one
     * carrier/band/RAT with the cell dropped.
     *
     * `t` is the period start in wall milliseconds UTC. Everything else is a count, a sum+count
     * pair, a histogram or a set, so the whole bucket merges.
     */
    data class RadioBucket(
        val t: Long,
        val subId: Int,
        /** `mcc+mnc`. A hard partition: buckets never merge across carriers, as `bin_rollup` says. */
        val plmn: String,
        val rat: String,
        val band: Int?,
        /** Serving Cell Identity. **Null at tier 2 by construction** — see the identity line above. */
        val ci: Long?,
        /** Physical Cell Id, the identity available when [ci] is not. Also null at tier 2. */
        val pci: Int?,
        val n: Int,
        /**
         * Distinct minutes of the period that had at least one sample — **coverage**, the field
         * that stops a thin bucket reading like a well-measured one. Out of 60 at tier 1, out of
         * 1440 at tier 2.
         */
        val coveredMinutes: Int,
        /**
         * The minute set itself, as a 60-bit mask, at tier 1 only.
         *
         * Carried because coverage is a *set*, not a count: two partial folds of the same hour
         * must union rather than add, exactly as `MapBins` unions contributor sets rather than
         * summing them. At tier 2 the contributing hours are disjoint by construction (a minute
         * belongs to exactly one hour), so the count alone merges correctly and the mask is 0.
         */
        val minuteMask: Long,
        /** Distinct hours that contributed, at tier 2. 0 at tier 1, where the answer is always 1. */
        val coveredHours: Int,
        /** Serving-cell changes attributed to this bucket, counted on the ordered raw scan. */
        val cellChanges: Int,
        /**
         * Distinct serving cells, at tier 2 where the identities themselves are gone.
         *
         * **A cardinality, valid only within its own period.** Summing it across days would count
         * a cell that was seen on both days twice; nothing in this file sums it, and neither
         * should a reader.
         */
        val distinctCells: Int,
        val firstMillis: Long,
        val lastMillis: Long,
        /** 1 dBm bins, so percentiles off it are exact. Absent values are not in it. */
        val rsrp: Map<Int, Int>,
        val rsrq: Map<Int, Int>,
        val rssnr: Map<Int, Int>,
        /** AOSP `SignalStrength.getLevel()`. */
        val level: Map<Int, Int>,
        /** The OEM's own bar, measured to disagree with [level]. Kept separate for that reason. */
        val vendorLevel: Map<Int, Int>,
        /**
         * Timing advance, a range from the mast and therefore a position signal.
         * **Empty at tier 2**, with cell identity, for the reason in the class comment.
         */
        val timingAdvance: Map<Int, Int>,
        /** Sum and count, never a mean: a mean cannot be merged without reweighting. */
        val neighbourSum: Long,
        val neighbourN: Int,
        /**
         * `LTE` or `NR`: the table [band] belongs to, and part of the key, because B40 and n40 are
         * both 40. Null only where a pre-v2 row carried neither a stored cell RAT nor a channel.
         */
        val bandRat: String? = null,
        /** NR SS-RSRP / SS-RSRQ / SS-SINR, 1 dB bins, kept apart from the LTE triple above. */
        val ssRsrp: Map<Int, Int> = emptyMap(),
        val ssRsrq: Map<Int, Int> = emptyMap(),
        val ssSinr: Map<Int, Int> = emptyMap(),
        /**
         * Samples with a real NR signal entry, out of [nrAssessed] -- NOT out of [n]. Rows written
         * before the NR check existed are in [n] but not [nrAssessed], so "no NR" is never inferred
         * from "never looked".
         */
        val nrN: Int = 0,
        val nrAssessed: Int = 0,
        /**
         * [Quality] bit index -> samples carrying that bit, out of [flagsAssessed]. Same reason for
         * the separate denominator: an unassessed sample is not a clean one.
         */
        val flags: Map<Int, Int> = emptyMap(),
        val flagsAssessed: Int = 0
    ) {
        val rsrpN: Int get() = histN(rsrp)
        val rsrqN: Int get() = histN(rsrq)
        val rssnrN: Int get() = histN(rssnr)
        val rsrpP50: Int? get() = percentile(rsrp, 0.50)
        val rsrpP10: Int? get() = percentile(rsrp, 0.10)
        val rsrpP90: Int? get() = percentile(rsrp, 0.90)
        val rssnrP50: Int? get() = percentile(rssnr, 0.50)
        /** Null when nothing was measured, never 0.0. */
        val neighbourMean: Double? get() = if (neighbourN == 0) null else neighbourSum.toDouble() / neighbourN
        val cellLabel: String get() = when {
            ci != null -> "ci $ci"
            pci != null -> "pci $pci"
            else -> "unknown cell"
        }
    }

    /** One period of registration events for one (subscription, domain, transport). */
    data class RegBucket(
        val t: Long,
        val subId: Int,
        val domain: String,
        val transportType: String,
        val n: Int,
        val coveredMinutes: Int,
        val minuteMask: Long,
        val coveredHours: Int,
        /** `regState` -> count. The denominator for every "in service" fraction. */
        val regState: Map<String, Int>,
        /** `rejectCause` -> count. The carrier's own reason, kept verbatim. */
        val rejectCause: Map<String, Int>,
        val nrState: Map<String, Int>,
        val accessNetwork: Map<String, Int>,
        val overrideNetworkType: Map<String, Int>,
        val roamingSamples: Int,
        val dataState: Map<String, Int>
    )

    /**
     * One period of link events for one (transport, isDefault).
     *
     * `netId`, `v4Address`, `v6Address`, `dnsServers` and `interfaceName` are deliberately **not**
     * rolled up. A global IPv6 address and a DNS server set identify a household or an ISP far
     * more directly than anything else this app stores, and no panel derives anything from them in
     * aggregate. The roll-up is where they stop.
     */
    data class LinkBucket(
        val t: Long,
        val transport: String,
        val isDefault: Boolean,
        val n: Int,
        val coveredMinutes: Int,
        val minuteMask: Long,
        val coveredHours: Int,
        val validated: Int,
        val notSuspended: Int,
        val metered: Int,
        /** `data-model.md` §3 calls this the highest-value single field in the schema. */
        val addressChanged: Int,
        val hasClat: Int,
        val mtu: Map<Int, Int>
    )

    /**
     * One period of probe outcomes for one ([CellProbe.Kind], `probeType`).
     *
     * Kind is part of the key and never collapsed. `INSTRUMENT` rows are the ones where this app
     * could not bind a socket at all: they describe an app fault, not the network, and folding
     * them into a failure rate is exactly the mistake that once made three excursions look like a
     * total cellular outage. They stay separable here for the same reason.
     */
    data class ProbeBucket(
        val t: Long,
        val kind: String,
        val probeType: String,
        val n: Int,
        val ok: Int,
        val fail: Int,
        /**
         * Latencies of the **successes only**. A failure carries a timeout, not a latency, and
         * averaging one in drags every percentile toward the timeout — `MapProbeJoin` makes the
         * same exclusion for the same reason.
         */
        val okLatency: Map<Int, Int>,
        /** `errorCode` -> count. Why the failures failed, which is usually the finding. */
        val errors: Map<String, Int>
    ) {
        val okLatencyP50: Int? get() = percentile(okLatency, 0.50)
        val okLatencyP90: Int? get() = percentile(okLatency, 0.90)
        /** Null when nothing was attempted: no probes is not a 100 % success rate. */
        val successRate: Double? get() = if (n == 0) null else ok.toDouble() / n
    }

    /**
     * One period of instrument-health verdicts for one (check, level).
     *
     * `detail` is not carried: it is free text written for a person looking at today, and a
     * roll-up only needs to say *that* a check was DEGRADED or BROKEN, for how many minutes.
     */
    data class InstrumentBucket(
        val t: Long,
        val check: String,
        val level: String,
        val n: Int,
        val coveredMinutes: Int,
        val minuteMask: Long,
        val coveredHours: Int
    )

    /** A whole roll-up file, decoded. */
    data class RollupFile(
        val tier: Tier,
        val period: String,
        val formatVersion: Int,
        val ruleVersion: String,
        val writtenMillis: Long,
        /** Table -> highest raw rowid this file provably contains. */
        val consumed: Map<String, Long>,
        /** Tier-2 only: the day periods already folded in. */
        val absorbed: Set<String>,
        val radio: List<RadioBucket>,
        val reg: List<RegBucket>,
        val link: List<LinkBucket>,
        val probe: List<ProbeBucket>,
        val instrument: List<InstrumentBucket> = emptyList()
    ) {
        val bucketCount: Int get() = radio.size + reg.size + link.size + probe.size + instrument.size
        val samples: Int get() = radio.sumOf { it.n } + reg.sumOf { it.n } +
            link.sumOf { it.n } + probe.sumOf { it.n } + instrument.sumOf { it.n }

        companion object {
            fun empty(tier: Tier, period: String) = RollupFile(
                tier, period, FORMAT_VERSION, RULE_VERSION, 0L,
                emptyMap(), emptySet(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        }
    }

    // =================================================================================
    //  Merge
    // =================================================================================

    private fun mergeRadio(a: RadioBucket, b: RadioBucket): RadioBucket {
        // Sets union, counts sum. Merging two folds of the same tier-1 hour must not double-count
        // a minute that both saw, which is the entire reason the mask exists.
        val mask = a.minuteMask or b.minuteMask
        return a.copy(
            n = a.n + b.n,
            minuteMask = mask,
            coveredMinutes = if (mask != 0L) java.lang.Long.bitCount(mask)
                             else a.coveredMinutes + b.coveredMinutes,
            coveredHours = a.coveredHours + b.coveredHours,
            cellChanges = a.cellChanges + b.cellChanges,
            distinctCells = a.distinctCells + b.distinctCells,
            firstMillis = minOf(a.firstMillis, b.firstMillis),
            lastMillis = maxOf(a.lastMillis, b.lastMillis),
            rsrp = histMerge(a.rsrp, b.rsrp),
            rsrq = histMerge(a.rsrq, b.rsrq),
            rssnr = histMerge(a.rssnr, b.rssnr),
            level = histMerge(a.level, b.level),
            vendorLevel = histMerge(a.vendorLevel, b.vendorLevel),
            timingAdvance = histMerge(a.timingAdvance, b.timingAdvance),
            neighbourSum = a.neighbourSum + b.neighbourSum,
            neighbourN = a.neighbourN + b.neighbourN,
            ssRsrp = histMerge(a.ssRsrp, b.ssRsrp),
            ssRsrq = histMerge(a.ssRsrq, b.ssRsrq),
            ssSinr = histMerge(a.ssSinr, b.ssSinr),
            nrN = a.nrN + b.nrN,
            nrAssessed = a.nrAssessed + b.nrAssessed,
            flags = histMerge(a.flags, b.flags),
            flagsAssessed = a.flagsAssessed + b.flagsAssessed
        )
    }

    private fun mergeInstrument(a: InstrumentBucket, b: InstrumentBucket): InstrumentBucket {
        val mask = a.minuteMask or b.minuteMask
        return a.copy(
            n = a.n + b.n,
            minuteMask = mask,
            coveredMinutes = if (mask != 0L) java.lang.Long.bitCount(mask)
                             else a.coveredMinutes + b.coveredMinutes,
            coveredHours = a.coveredHours + b.coveredHours
        )
    }

    private fun mergeReg(a: RegBucket, b: RegBucket): RegBucket {
        val mask = a.minuteMask or b.minuteMask
        return a.copy(
            n = a.n + b.n,
            minuteMask = mask,
            coveredMinutes = if (mask != 0L) java.lang.Long.bitCount(mask)
                             else a.coveredMinutes + b.coveredMinutes,
            coveredHours = a.coveredHours + b.coveredHours,
            regState = tallyMerge(a.regState, b.regState),
            rejectCause = tallyMerge(a.rejectCause, b.rejectCause),
            nrState = tallyMerge(a.nrState, b.nrState),
            accessNetwork = tallyMerge(a.accessNetwork, b.accessNetwork),
            overrideNetworkType = tallyMerge(a.overrideNetworkType, b.overrideNetworkType),
            roamingSamples = a.roamingSamples + b.roamingSamples,
            dataState = tallyMerge(a.dataState, b.dataState)
        )
    }

    private fun mergeLink(a: LinkBucket, b: LinkBucket): LinkBucket {
        val mask = a.minuteMask or b.minuteMask
        return a.copy(
            n = a.n + b.n,
            minuteMask = mask,
            coveredMinutes = if (mask != 0L) java.lang.Long.bitCount(mask)
                             else a.coveredMinutes + b.coveredMinutes,
            coveredHours = a.coveredHours + b.coveredHours,
            validated = a.validated + b.validated,
            notSuspended = a.notSuspended + b.notSuspended,
            metered = a.metered + b.metered,
            addressChanged = a.addressChanged + b.addressChanged,
            hasClat = a.hasClat + b.hasClat,
            mtu = histMerge(a.mtu, b.mtu)
        )
    }

    private fun mergeProbe(a: ProbeBucket, b: ProbeBucket) = a.copy(
        n = a.n + b.n,
        ok = a.ok + b.ok,
        fail = a.fail + b.fail,
        okLatency = histMerge(a.okLatency, b.okLatency),
        errors = tallyMerge(a.errors, b.errors)
    )

    private fun radioKey(b: RadioBucket) = listOf(b.t, b.subId, b.plmn, b.rat, b.bandRat, b.band, b.ci, b.pci)
    private fun instrumentKey(b: InstrumentBucket) = listOf(b.t, b.check, b.level)
    private fun regKey(b: RegBucket) = listOf(b.t, b.subId, b.domain, b.transportType)
    private fun linkKey(b: LinkBucket) = listOf(b.t, b.transport, b.isDefault)
    private fun probeKey(b: ProbeBucket) = listOf(b.t, b.kind, b.probeType)

    private fun <T> foldBy(items: List<T>, key: (T) -> Any, merge: (T, T) -> T): List<T> {
        val out = LinkedHashMap<Any, T>()
        for (i in items) {
            val k = key(i)
            val cur = out[k]
            out[k] = if (cur == null) i else merge(cur, i)
        }
        return out.values.toList()
    }

    /**
     * Merge any two roll-up files' buckets. Public because this is how a caller answers "the last
     * ninety days, per band" without the format having to anticipate the question.
     */
    fun mergeRadio(all: List<RadioBucket>): List<RadioBucket> = foldBy(all, ::radioKey, ::mergeRadio)
    fun mergeReg(all: List<RegBucket>): List<RegBucket> = foldBy(all, ::regKey, ::mergeReg)
    fun mergeLink(all: List<LinkBucket>): List<LinkBucket> = foldBy(all, ::linkKey, ::mergeLink)
    fun mergeProbe(all: List<ProbeBucket>): List<ProbeBucket> = foldBy(all, ::probeKey, ::mergeProbe)
    fun mergeInstrument(all: List<InstrumentBucket>): List<InstrumentBucket> =
        foldBy(all, ::instrumentKey, ::mergeInstrument)

    // =================================================================================
    //  JSON encode / decode
    // =================================================================================

    private fun histJson(h: Map<Int, Int>): JSONObject? {
        if (h.isEmpty()) return null
        val o = JSONObject()
        for (k in h.keys.sorted()) o.put(k.toString(), h.getValue(k))
        return o
    }

    private fun tallyJson(m: Map<String, Int>): JSONObject? {
        if (m.isEmpty()) return null
        val o = JSONObject()
        for (k in m.keys.sorted()) o.put(k, m.getValue(k))
        return o
    }

    private fun histOf(o: JSONObject?, name: String): Map<Int, Int> {
        val h = o?.optJSONObject(name) ?: return emptyMap()
        val out = HashMap<Int, Int>()
        for (k in h.keys()) runCatching { out[k.toInt()] = h.getInt(k) }
        return out
    }

    private fun tallyOf(o: JSONObject?, name: String): Map<String, Int> {
        val h = o?.optJSONObject(name) ?: return emptyMap()
        val out = HashMap<String, Int>()
        for (k in h.keys()) runCatching { out[k] = h.getInt(k) }
        return out
    }

    // No putOpt extension here: JSONObject already has a member of that name that puts only when
    // both arguments are non-null, and a member always wins over an extension -- so a local one
    // compiles, is never called, and leaves a warning that trains the reader to ignore warnings.
    private fun JSONObject.putNullable(name: String, v: Any?) { if (v != null) put(name, v) }

    private fun encode(b: RadioBucket) = JSONObject().apply {
        put("k", "r"); put("t", b.t); put("sub", b.subId); put("plmn", b.plmn); put("rat", b.rat)
        putNullable("band", b.band); putNullable("ci", b.ci); putNullable("pci", b.pci)
        put("n", b.n); put("cov", b.coveredMinutes)
        if (b.minuteMask != 0L) put("cmask", java.lang.Long.toHexString(b.minuteMask))
        if (b.coveredHours != 0) put("hrs", b.coveredHours)
        if (b.cellChanges != 0) put("chg", b.cellChanges)
        if (b.distinctCells != 0) put("cells", b.distinctCells)
        put("first", b.firstMillis); put("last", b.lastMillis)
        putOpt("rsrp", histJson(b.rsrp)); putOpt("rsrq", histJson(b.rsrq))
        putOpt("snr", histJson(b.rssnr)); putOpt("lvl", histJson(b.level))
        putOpt("vlvl", histJson(b.vendorLevel)); putOpt("ta", histJson(b.timingAdvance))
        if (b.neighbourN > 0) { put("nbrSum", b.neighbourSum); put("nbrN", b.neighbourN) }
        putNullable("brat", b.bandRat)
        putOpt("ssrsrp", histJson(b.ssRsrp)); putOpt("ssrsrq", histJson(b.ssRsrq))
        putOpt("sssinr", histJson(b.ssSinr))
        if (b.nrAssessed > 0) { put("nr", b.nrN); put("nrA", b.nrAssessed) }
        if (b.flagsAssessed > 0) { putOpt("qf", histJson(b.flags)); put("qfA", b.flagsAssessed) }
    }

    private fun encode(b: InstrumentBucket) = JSONObject().apply {
        put("k", "i"); put("t", b.t); put("check", b.check); put("lvl", b.level)
        put("n", b.n); put("cov", b.coveredMinutes)
        if (b.minuteMask != 0L) put("cmask", java.lang.Long.toHexString(b.minuteMask))
        if (b.coveredHours != 0) put("hrs", b.coveredHours)
    }

    private fun decodeInstrument(o: JSONObject) = InstrumentBucket(
        t = o.getLong("t"), check = o.optString("check"), level = o.optString("lvl"),
        n = o.optInt("n"), coveredMinutes = o.optInt("cov"),
        minuteMask = runCatching { java.lang.Long.parseUnsignedLong(o.optString("cmask", "0"), 16) }
            .getOrDefault(0L),
        coveredHours = o.optInt("hrs")
    )

    private fun decodeRadio(o: JSONObject) = RadioBucket(
        t = o.getLong("t"), subId = o.optInt("sub", -1), plmn = o.optString("plmn"),
        rat = o.optString("rat", "UNKNOWN"),
        band = if (o.has("band")) o.optInt("band") else null,
        ci = if (o.has("ci")) o.optLong("ci") else null,
        pci = if (o.has("pci")) o.optInt("pci") else null,
        n = o.optInt("n"), coveredMinutes = o.optInt("cov"),
        minuteMask = runCatching { java.lang.Long.parseUnsignedLong(o.optString("cmask", "0"), 16) }
            .getOrDefault(0L),
        coveredHours = o.optInt("hrs"), cellChanges = o.optInt("chg"),
        distinctCells = o.optInt("cells"),
        firstMillis = o.optLong("first"), lastMillis = o.optLong("last"),
        rsrp = histOf(o, "rsrp"), rsrq = histOf(o, "rsrq"), rssnr = histOf(o, "snr"),
        level = histOf(o, "lvl"), vendorLevel = histOf(o, "vlvl"), timingAdvance = histOf(o, "ta"),
        neighbourSum = o.optLong("nbrSum"), neighbourN = o.optInt("nbrN"),
        bandRat = if (o.has("brat")) o.optString("brat") else null,
        ssRsrp = histOf(o, "ssrsrp"), ssRsrq = histOf(o, "ssrsrq"), ssSinr = histOf(o, "sssinr"),
        nrN = o.optInt("nr"), nrAssessed = o.optInt("nrA"),
        flags = histOf(o, "qf"), flagsAssessed = o.optInt("qfA")
    )

    private fun encode(b: RegBucket) = JSONObject().apply {
        put("k", "g"); put("t", b.t); put("sub", b.subId); put("dom", b.domain); put("tr", b.transportType)
        put("n", b.n); put("cov", b.coveredMinutes)
        if (b.minuteMask != 0L) put("cmask", java.lang.Long.toHexString(b.minuteMask))
        if (b.coveredHours != 0) put("hrs", b.coveredHours)
        putOpt("state", tallyJson(b.regState)); putOpt("rej", tallyJson(b.rejectCause))
        putOpt("nr", tallyJson(b.nrState)); putOpt("ant", tallyJson(b.accessNetwork))
        putOpt("ovr", tallyJson(b.overrideNetworkType)); putOpt("ds", tallyJson(b.dataState))
        if (b.roamingSamples != 0) put("roam", b.roamingSamples)
    }

    private fun decodeReg(o: JSONObject) = RegBucket(
        t = o.getLong("t"), subId = o.optInt("sub", -1), domain = o.optString("dom"),
        transportType = o.optString("tr"), n = o.optInt("n"), coveredMinutes = o.optInt("cov"),
        minuteMask = runCatching { java.lang.Long.parseUnsignedLong(o.optString("cmask", "0"), 16) }
            .getOrDefault(0L),
        coveredHours = o.optInt("hrs"),
        regState = tallyOf(o, "state"), rejectCause = tallyOf(o, "rej"), nrState = tallyOf(o, "nr"),
        accessNetwork = tallyOf(o, "ant"), overrideNetworkType = tallyOf(o, "ovr"),
        roamingSamples = o.optInt("roam"), dataState = tallyOf(o, "ds")
    )

    private fun encode(b: LinkBucket) = JSONObject().apply {
        put("k", "l"); put("t", b.t); put("tr", b.transport); put("def", b.isDefault)
        put("n", b.n); put("cov", b.coveredMinutes)
        if (b.minuteMask != 0L) put("cmask", java.lang.Long.toHexString(b.minuteMask))
        if (b.coveredHours != 0) put("hrs", b.coveredHours)
        put("val", b.validated); put("nsus", b.notSuspended); put("met", b.metered)
        put("addrChg", b.addressChanged); put("clat", b.hasClat)
        putOpt("mtu", histJson(b.mtu))
    }

    private fun decodeLink(o: JSONObject) = LinkBucket(
        t = o.getLong("t"), transport = o.optString("tr"), isDefault = o.optBoolean("def"),
        n = o.optInt("n"), coveredMinutes = o.optInt("cov"),
        minuteMask = runCatching { java.lang.Long.parseUnsignedLong(o.optString("cmask", "0"), 16) }
            .getOrDefault(0L),
        coveredHours = o.optInt("hrs"),
        validated = o.optInt("val"), notSuspended = o.optInt("nsus"), metered = o.optInt("met"),
        addressChanged = o.optInt("addrChg"), hasClat = o.optInt("clat"), mtu = histOf(o, "mtu")
    )

    private fun encode(b: ProbeBucket) = JSONObject().apply {
        put("k", "p"); put("t", b.t); put("kind", b.kind); put("type", b.probeType)
        put("n", b.n); put("ok", b.ok); put("fail", b.fail)
        putOpt("lat", histJson(b.okLatency)); putOpt("err", tallyJson(b.errors))
    }

    private fun decodeProbe(o: JSONObject) = ProbeBucket(
        t = o.getLong("t"), kind = o.optString("kind", CellProbe.Kind.UNKNOWN.name),
        probeType = o.optString("type"), n = o.optInt("n"), ok = o.optInt("ok"),
        fail = o.optInt("fail"), okLatency = histOf(o, "lat"), errors = tallyOf(o, "err")
    )

    // =================================================================================
    //  Read / write
    // =================================================================================

    /**
     * Read one roll-up file back. Null if absent; a partial file if it is truncated.
     *
     * A truncated last line is tolerated rather than fatal: it is exactly what a kill during a
     * write would leave, and losing the last bucket of a period is better than a reader that
     * throws. The atomic write below means this should not happen at all — this is the belt.
     */
    fun read(ctx: Context, tier: Tier, period: String): RollupFile? = runCatching {
        val f = fileFor(ctx, tier, period)
        if (!f.exists()) return null
        var header: JSONObject? = null
        val radio = ArrayList<RadioBucket>()
        val reg = ArrayList<RegBucket>()
        val link = ArrayList<LinkBucket>()
        val probe = ArrayList<ProbeBucket>()
        val instrument = ArrayList<InstrumentBucket>()
        BufferedReader(InputStreamReader(GZIPInputStream(f.inputStream()), Charsets.UTF_8))
            .use { r ->
                var line: String? = r.readLine()
                while (line != null) {
                    val s = line
                    line = r.readLine()
                    if (s.isBlank()) continue
                    val o = runCatching { JSONObject(s) }.getOrNull() ?: continue
                    if (header == null && o.optString("f") == FORMAT) { header = o; continue }
                    when (o.optString("k")) {
                        "r" -> runCatching { radio.add(decodeRadio(o)) }
                        "g" -> runCatching { reg.add(decodeReg(o)) }
                        "l" -> runCatching { link.add(decodeLink(o)) }
                        "p" -> runCatching { probe.add(decodeProbe(o)) }
                        "i" -> runCatching { instrument.add(decodeInstrument(o)) }
                    }
                }
            }
        val h = header
        val consumed = HashMap<String, Long>()
        h?.optJSONObject("consumed")?.let { c ->
            for (k in c.keys()) runCatching { consumed[k] = c.getLong(k) }
        }
        val absorbed = HashSet<String>()
        h?.optJSONArray("absorbed")?.let { a ->
            for (i in 0 until a.length()) runCatching { absorbed.add(a.getString(i)) }
        }
        RollupFile(
            tier = tier, period = period,
            formatVersion = h?.optInt("v", FORMAT_VERSION) ?: FORMAT_VERSION,
            ruleVersion = h?.optString("rule", RULE_VERSION) ?: RULE_VERSION,
            writtenMillis = h?.optLong("writtenMillis") ?: 0L,
            consumed = consumed, absorbed = absorbed,
            radio = radio, reg = reg, link = link, probe = probe, instrument = instrument
        )
    }.getOrNull()

    /** Every bucket in a tier, already merged across periods. Blocking; call from `Dispatchers.IO`. */
    fun readAll(ctx: Context, tier: Tier): RollupFile = runCatching {
        val files = periods(ctx, tier).mapNotNull { read(ctx, tier, it) }
        RollupFile(
            tier = tier, period = "*", formatVersion = FORMAT_VERSION, ruleVersion = RULE_VERSION,
            writtenMillis = files.maxOfOrNull { it.writtenMillis } ?: 0L,
            consumed = emptyMap(), absorbed = files.flatMap { it.absorbed }.toSet(),
            radio = mergeRadio(files.flatMap { it.radio }),
            reg = mergeReg(files.flatMap { it.reg }),
            link = mergeLink(files.flatMap { it.link }),
            probe = mergeProbe(files.flatMap { it.probe }),
            instrument = mergeInstrument(files.flatMap { it.instrument })
        )
    }.getOrElse { RollupFile.empty(tier, "*") }

    /**
     * Temp file, then rename. The rename is atomic within one filesystem, so a reader either sees
     * the previous complete file or the new complete one and never a half-written period — which
     * is what makes "write, then delete the raw rows" a safe commit order.
     */
    private fun writeAtomic(ctx: Context, tier: Tier, period: String, f: RollupFile): Boolean =
        runCatching {
            val tmp = File(tmpDir(ctx), "$period.${System.nanoTime()}.part")
            OutputStreamWriter(GZIPOutputStream(tmp.outputStream()), Charsets.UTF_8).use { w ->
                val h = JSONObject().apply {
                    put("f", FORMAT); put("v", FORMAT_VERSION); put("tier", tier.dir)
                    put("period", period); put("rule", RULE_VERSION)
                    put("writtenMillis", System.currentTimeMillis())
                    put("buckets", f.bucketCount)
                    if (f.consumed.isNotEmpty()) put("consumed", JSONObject().apply {
                        for ((k, v) in f.consumed.entries.sortedBy { it.key }) put(k, v)
                    })
                    if (f.absorbed.isNotEmpty()) put("absorbed", org.json.JSONArray(f.absorbed.sorted()))
                }
                w.write(h.toString()); w.write("\n")
                for (b in f.radio) { w.write(encode(b).toString()); w.write("\n") }
                for (b in f.reg) { w.write(encode(b).toString()); w.write("\n") }
                for (b in f.link) { w.write(encode(b).toString()); w.write("\n") }
                for (b in f.probe) { w.write(encode(b).toString()); w.write("\n") }
                for (b in f.instrument) { w.write(encode(b).toString()); w.write("\n") }
            }
            val dst = fileFor(ctx, tier, period)
            if (!tmp.renameTo(dst)) {
                // Same directory tree, so this should not happen; copy rather than lose the work,
                // and accept that the copy is not atomic. The raw rows are deleted only on true.
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)

    /** Anything in `.tmp` is by definition an interrupted write. Cleared on every run. */
    private fun clearTmp(ctx: Context) {
        runCatching { tmpDir(ctx).listFiles()?.forEach { it.delete() } }
    }

    // =================================================================================
    //  Fold: raw rows -> hourly buckets
    // =================================================================================

    private class RadioAcc {
        var n = 0; var mask = 0L; var changes = 0
        var first = Long.MAX_VALUE; var last = Long.MIN_VALUE
        val rsrp = HashMap<Int, Int>(); val rsrq = HashMap<Int, Int>(); val snr = HashMap<Int, Int>()
        val lvl = HashMap<Int, Int>(); val vlvl = HashMap<Int, Int>(); val ta = HashMap<Int, Int>()
        var nbrSum = 0L; var nbrN = 0
        val ssRsrp = HashMap<Int, Int>(); val ssRsrq = HashMap<Int, Int>(); val ssSinr = HashMap<Int, Int>()
        var nrN = 0; var nrAssessed = 0
        val flags = HashMap<Int, Int>(); var flagsAssessed = 0
    }

    private class InstrumentAcc { var n = 0; var mask = 0L }

    private class RegAcc {
        var n = 0; var mask = 0L; var roam = 0
        val state = HashMap<String, Int>(); val rej = HashMap<String, Int>()
        val nr = HashMap<String, Int>(); val ant = HashMap<String, Int>()
        val ovr = HashMap<String, Int>(); val ds = HashMap<String, Int>()
    }

    private class LinkAcc {
        var n = 0; var mask = 0L
        var validated = 0; var notSuspended = 0; var metered = 0; var addrChg = 0; var clat = 0
        val mtu = HashMap<Int, Int>()
    }

    private class ProbeAcc {
        var n = 0; var ok = 0; var fail = 0
        val lat = HashMap<Int, Int>(); val err = HashMap<String, Int>()
    }

    private fun minuteBit(millis: Long): Long =
        1L shl ((Math.floorDiv(millis, MINUTE_MS) % 60L).toInt())

    /** `null` rather than `0`: `data-model.md` §3 forbids a sentinel standing in for a measurement. */
    private fun intOrNull(c: android.database.Cursor, i: Int): Int? =
        if (c.isNull(i)) null else c.getInt(i)

    private fun longOrNull(c: android.database.Cursor, i: Int): Long? =
        if (c.isNull(i)) null else c.getLong(i)

    private fun strOrEmpty(c: android.database.Cursor, i: Int): String =
        if (c.isNull(i)) "" else c.getString(i)

    /**
     * Result of one compaction run, for the UI to show rather than moving data silently.
     *
     * `bytesFreed` is the estimated raw-row bytes removed, at `data-model.md` §7's own ~200 B/row.
     */
    data class Result(
        val daysCompacted: Int = 0,
        val daysRolled: Int = 0,
        val monthsPruned: Int = 0,
        val rawRowsCompacted: Int = 0,
        val bucketsWritten: Int = 0,
        val stoppedEarly: Boolean = false,
        val error: String? = null
    ) {
        val didWork: Boolean get() = daysCompacted > 0 || daysRolled > 0 || monthsPruned > 0
        operator fun plus(o: Result) = Result(
            daysCompacted + o.daysCompacted, daysRolled + o.daysRolled,
            monthsPruned + o.monthsPruned, rawRowsCompacted + o.rawRowsCompacted,
            bucketsWritten + o.bucketsWritten, stoppedEarly || o.stoppedEarly,
            error ?: o.error
        )
    }

    /**
     * Fold one UTC day of raw rows into its tier-1 file, then delete exactly the rows the file
     * now provably contains.
     *
     * Returns the number of raw rows folded; 0 means there was nothing new, which is the normal
     * answer for a day that has already been compacted and is what makes re-running free.
     *
     * The caller must only pass a day that can no longer receive rows. See the class comment for
     * why the `rowid` watermark makes every kill point safe.
     */
    fun compactDay(ctx: Context, dayLabel: String): Result = runCatching {
        val d0 = dayLabelStart(dayLabel) ?: return Result(error = "bad day $dayLabel")
        val d1 = d0 + DAY_MS
        val existing = read(ctx, Tier.HOUR, dayLabel) ?: RollupFile.empty(Tier.HOUR, dayLabel)
        val db = Db.get(ctx).openHelper.writableDatabase

        val consumed = HashMap<String, Long>(existing.consumed)
        fun mark(table: String) = consumed[table] ?: 0L

        val radioAcc = HashMap<List<Any?>, RadioAcc>()
        val regAcc = HashMap<List<Any?>, RegAcc>()
        val linkAcc = HashMap<List<Any?>, LinkAcc>()
        val probeAcc = HashMap<List<Any?>, ProbeAcc>()
        var rows = 0

        // ---------------------------------------------------------------- radio_sample
        //
        // Ordered by rowid, which is also collection order, so the serving-cell comparison that
        // produces `cellChanges` sees the samples in the order they happened. Every bound is a
        // bind argument, never concatenated -- the same rule Retention states and for the same
        // reason: a statement assembled by string-building is the shape of the bug that matters
        // later, and the parameterised form costs nothing.
        run {
            val lastCell = HashMap<Int, String>()
            var maxId = mark("radio_sample")
            db.query(
                "SELECT id, wallMillis, subId, rat, servingCi, servingPci, bandNum, mcc, mnc, " +
                    "rsrp, rsrq, rssnr, level, vendorLevel, timingAdvance, neighbourCount, " +
                    "bandDerived, cellRat, servingArfcn, ssRsrp, ssRsrq, ssSinr, nrPresent, qualityFlags " +
                    "FROM radio_sample WHERE wallMillis >= ? AND wallMillis < ? AND id > ? " +
                    "ORDER BY id ASC",
                arrayOf<Any>(d0, d1, mark("radio_sample"))
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id > maxId) maxId = id
                    val wall = c.getLong(1)
                    val sub = c.getInt(2)
                    val rat = strOrEmpty(c, 3).ifEmpty { "UNKNOWN" }
                    val ci = longOrNull(c, 4)
                    val pci = intOrNull(c, 5)
                    // Band keyed on the channel, with its own technology. Rows from before schema 3
                    // have no bandDerived, so they are derived here from the channel they did store
                    // -- the same table, the same answer the collector now gives -- rather than
                    // trusting getBands()[0]. Only a row with no channel at all falls back to
                    // bandNum, and it gets no band RAT, so it cannot merge with a derived bucket.
                    val mccStr = if (c.isNull(7)) null else c.getString(7)
                    val storedBand = intOrNull(c, 16)
                    val storedRat = if (c.isNull(17)) null else c.getString(17)
                    val legacy = if (storedBand == null)
                        runCatching { Bands.deriveLegacy(intOrNull(c, 18), mccStr) }.getOrNull() else null
                    val band = storedBand ?: legacy?.band ?: intOrNull(c, 6)
                    val bandRat = when {
                        storedBand != null -> storedRat
                        legacy != null -> legacy.rat
                        else -> null
                    }
                    val plmn = strOrEmpty(c, 7) + strOrEmpty(c, 8)
                    val key = listOf(hourStart(wall), sub, plmn, rat, bandRat, band, ci, pci)
                    val a = radioAcc.getOrPut(key) { RadioAcc() }
                    a.n++
                    a.mask = a.mask or minuteBit(wall)
                    if (wall < a.first) a.first = wall
                    if (wall > a.last) a.last = wall
                    intOrNull(c, 9)?.let { histAdd(a.rsrp, it) }
                    intOrNull(c, 10)?.let { histAdd(a.rsrq, it) }
                    intOrNull(c, 11)?.let { histAdd(a.snr, it) }
                    intOrNull(c, 12)?.let { histAdd(a.lvl, it) }
                    intOrNull(c, 13)?.let { histAdd(a.vlvl, it) }
                    intOrNull(c, 14)?.let { histAdd(a.ta, it) }
                    intOrNull(c, 15)?.let { a.nbrSum += it; a.nbrN++ }
                    intOrNull(c, 19)?.let { histAdd(a.ssRsrp, it) }
                    intOrNull(c, 20)?.let { histAdd(a.ssRsrq, it) }
                    intOrNull(c, 21)?.let { histAdd(a.ssSinr, it) }
                    intOrNull(c, 22)?.let { a.nrAssessed++; if (it != 0) a.nrN++ }
                    intOrNull(c, 23)?.let { q ->
                        a.flagsAssessed++
                        for (bit in 0 until 31) if (q and (1 shl bit) != 0) histAdd(a.flags, bit)
                    }
                    // A change is attributed to the bucket of the sample that arrived on the new
                    // cell, so summing changes over every bucket of a period gives that period's
                    // total. An unknown identity is not a cell change -- it is a missing reading,
                    // and counting it as churn would invent reselection that never happened.
                    val cell = when {
                        ci != null -> "c$ci"
                        pci != null -> "p$pci"
                        else -> null
                    }
                    if (cell != null) {
                        val prev = lastCell.put(sub, cell)
                        if (prev != null && prev != cell) a.changes++
                    }
                    rows++
                }
            }
            consumed["radio_sample"] = maxId
        }

        // ---------------------------------------------------------------- registration_event
        run {
            var maxId = mark("registration_event")
            db.query(
                "SELECT id, wallMillis, subId, domain, transportType, accessNetworkTechnology, " +
                    "regState, rejectCause, nrState, overrideNetworkType, roaming, dataState " +
                    "FROM registration_event WHERE wallMillis >= ? AND wallMillis < ? AND id > ? " +
                    "ORDER BY id ASC",
                arrayOf<Any>(d0, d1, mark("registration_event"))
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id > maxId) maxId = id
                    val wall = c.getLong(1)
                    val key = listOf(hourStart(wall), c.getInt(2), strOrEmpty(c, 3), strOrEmpty(c, 4))
                    val a = regAcc.getOrPut(key) { RegAcc() }
                    a.n++
                    a.mask = a.mask or minuteBit(wall)
                    tallyAdd(a.state, strOrEmpty(c, 6).ifEmpty { "UNKNOWN" })
                    intOrNull(c, 7)?.let { tallyAdd(a.rej, it.toString()) }
                    if (!c.isNull(8)) tallyAdd(a.nr, c.getString(8))
                    if (!c.isNull(5)) tallyAdd(a.ant, c.getString(5))
                    if (!c.isNull(9)) tallyAdd(a.ovr, c.getString(9))
                    if (c.getInt(10) != 0) a.roam++
                    intOrNull(c, 11)?.let { tallyAdd(a.ds, it.toString()) }
                    rows++
                }
            }
            consumed["registration_event"] = maxId
        }

        // ---------------------------------------------------------------- link_event
        //
        // netId, v4Address, v6Address, dnsServers and interfaceName are not selected at all. See
        // LinkBucket: they are the most directly identifying columns the app stores and no
        // aggregate derives anything from them.
        run {
            var maxId = mark("link_event")
            db.query(
                "SELECT id, wallMillis, transport, isDefault, validated, notSuspended, metered, " +
                    "addressChanged, mtu, hasClat FROM link_event " +
                    "WHERE wallMillis >= ? AND wallMillis < ? AND id > ? ORDER BY id ASC",
                arrayOf<Any>(d0, d1, mark("link_event"))
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id > maxId) maxId = id
                    val wall = c.getLong(1)
                    val key = listOf(hourStart(wall), strOrEmpty(c, 2), c.getInt(3) != 0)
                    val a = linkAcc.getOrPut(key) { LinkAcc() }
                    a.n++
                    a.mask = a.mask or minuteBit(wall)
                    if (c.getInt(4) != 0) a.validated++
                    if (c.getInt(5) != 0) a.notSuspended++
                    if (c.getInt(6) != 0) a.metered++
                    if (c.getInt(7) != 0) a.addrChg++
                    if (c.getInt(9) != 0) a.clat++
                    intOrNull(c, 8)?.let { histAdd(a.mtu, it) }
                    rows++
                }
            }
            consumed["link_event"] = maxId
        }

        // ---------------------------------------------------------------- probe_result
        run {
            var maxId = mark("probe_result")
            db.query(
                "SELECT id, wallMillis, probeType, outcome, latencyMs, errorCode FROM probe_result " +
                    "WHERE wallMillis >= ? AND wallMillis < ? AND id > ? ORDER BY id ASC",
                arrayOf<Any>(d0, d1, mark("probe_result"))
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (id > maxId) maxId = id
                    val wall = c.getLong(1)
                    val type = strOrEmpty(c, 2)
                    val err = if (c.isNull(5)) null else c.getString(5)
                    // Classified through CellProbe rather than by a startsWith test here: the
                    // whole point of kindOf() living in one place is that a probe kind added
                    // later is not silently absorbed by one filter and dropped by another. The
                    // error text is part of the classification, which is how the 248 pre-BINDFAIL
                    // rows still read as an app fault rather than a network failure.
                    val kind = CellProbe.kindOf(type, err)
                    val key = listOf(hourStart(wall), kind.name, type)
                    val a = probeAcc.getOrPut(key) { ProbeAcc() }
                    a.n++
                    val ok = strOrEmpty(c, 3) == "OK"
                    if (ok) {
                        a.ok++
                        histAdd(a.lat, latencyBin(c.getInt(4)))
                    } else {
                        a.fail++
                        tallyAdd(a.err, err ?: "unspecified")
                    }
                    rows++
                }
            }
            consumed["probe_result"] = maxId
        }

        // ---------------------------------------------------------------- instrument_event
        //
        // `detail` is not selected: see InstrumentBucket.
        val instrumentAcc = HashMap<List<Any?>, InstrumentAcc>()
        run {
            var maxId = mark("instrument_event")
            runCatching {
                db.query(
                    "SELECT id, wallMillis, `check`, level FROM instrument_event " +
                        "WHERE wallMillis >= ? AND wallMillis < ? AND id > ? ORDER BY id ASC",
                    arrayOf<Any>(d0, d1, mark("instrument_event"))
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (id > maxId) maxId = id
                        val wall = c.getLong(1)
                        val key = listOf(hourStart(wall), strOrEmpty(c, 2), strOrEmpty(c, 3).ifEmpty { "UNKNOWN" })
                        val a = instrumentAcc.getOrPut(key) { InstrumentAcc() }
                        a.n++
                        a.mask = a.mask or minuteBit(wall)
                        rows++
                    }
                }
            }
            consumed["instrument_event"] = maxId
        }

        if (rows == 0 && existing.writtenMillis != 0L) {
            // Already compacted and nothing new arrived. Still redo the delete: this is the
            // "killed after the write, before the delete" path, and it must converge.
            deleteConsumed(db, d0, d1, existing.consumed)
            return Result(rawRowsCompacted = 0)
        }
        if (rows == 0) return Result()

        val fresh = RollupFile(
            tier = Tier.HOUR, period = dayLabel, formatVersion = FORMAT_VERSION,
            ruleVersion = RULE_VERSION, writtenMillis = System.currentTimeMillis(),
            consumed = consumed, absorbed = emptySet(),
            radio = radioAcc.entries.map { (k, a) ->
                RadioBucket(
                    t = k[0] as Long, subId = k[1] as Int, plmn = k[2] as String,
                    rat = k[3] as String, bandRat = k[4] as String?, band = k[5] as Int?,
                    ci = k[6] as Long?, pci = k[7] as Int?,
                    n = a.n, coveredMinutes = java.lang.Long.bitCount(a.mask), minuteMask = a.mask,
                    coveredHours = 0, cellChanges = a.changes, distinctCells = 0,
                    firstMillis = a.first, lastMillis = a.last,
                    rsrp = a.rsrp, rsrq = a.rsrq, rssnr = a.snr, level = a.lvl,
                    vendorLevel = a.vlvl, timingAdvance = a.ta,
                    neighbourSum = a.nbrSum, neighbourN = a.nbrN,
                    ssRsrp = a.ssRsrp, ssRsrq = a.ssRsrq, ssSinr = a.ssSinr,
                    nrN = a.nrN, nrAssessed = a.nrAssessed,
                    flags = a.flags, flagsAssessed = a.flagsAssessed
                )
            },
            reg = regAcc.entries.map { (k, a) ->
                RegBucket(
                    t = k[0] as Long, subId = k[1] as Int, domain = k[2] as String,
                    transportType = k[3] as String, n = a.n,
                    coveredMinutes = java.lang.Long.bitCount(a.mask), minuteMask = a.mask,
                    coveredHours = 0, regState = a.state, rejectCause = a.rej, nrState = a.nr,
                    accessNetwork = a.ant, overrideNetworkType = a.ovr,
                    roamingSamples = a.roam, dataState = a.ds
                )
            },
            link = linkAcc.entries.map { (k, a) ->
                LinkBucket(
                    t = k[0] as Long, transport = k[1] as String, isDefault = k[2] as Boolean,
                    n = a.n, coveredMinutes = java.lang.Long.bitCount(a.mask), minuteMask = a.mask,
                    coveredHours = 0, validated = a.validated, notSuspended = a.notSuspended,
                    metered = a.metered, addressChanged = a.addrChg, hasClat = a.clat, mtu = a.mtu
                )
            },
            probe = probeAcc.entries.map { (k, a) ->
                ProbeBucket(
                    t = k[0] as Long, kind = k[1] as String, probeType = k[2] as String,
                    n = a.n, ok = a.ok, fail = a.fail, okLatency = a.lat, errors = a.err
                )
            },
            instrument = instrumentAcc.entries.map { (k, a) ->
                InstrumentBucket(
                    t = k[0] as Long, check = k[1] as String, level = k[2] as String,
                    n = a.n, coveredMinutes = java.lang.Long.bitCount(a.mask), minuteMask = a.mask,
                    coveredHours = 0
                )
            }
        )

        // Merge with what the file already held, so a second pass over a day that gained late rows
        // adds to it instead of replacing it.
        val merged = fresh.copy(
            radio = mergeRadio(existing.radio + fresh.radio),
            reg = mergeReg(existing.reg + fresh.reg),
            link = mergeLink(existing.link + fresh.link),
            probe = mergeProbe(existing.probe + fresh.probe),
            instrument = mergeInstrument(existing.instrument + fresh.instrument)
        )

        if (!writeAtomic(ctx, Tier.HOUR, dayLabel, merged)) {
            // Nothing was deleted, so the day is simply still raw and the next sweep retries.
            return Result(error = "write failed for $dayLabel")
        }
        deleteConsumed(db, d0, d1, consumed)
        Result(daysCompacted = 1, rawRowsCompacted = rows, bucketsWritten = merged.bucketCount)
    }.getOrElse { Result(error = "${it.javaClass.simpleName} compacting $dayLabel") }

    /**
     * Delete exactly the rows the roll-up provably contains — inside the day window **and** at or
     * below the watermark. The `id <=` half is what makes this safe to repeat and what stops it
     * ever removing a row nothing counted.
     */
    private fun deleteConsumed(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        d0: Long, d1: Long, consumed: Map<String, Long>
    ) {
        for (t in RAW_TABLES) {
            val mark = consumed[t] ?: continue
            if (mark <= 0L) continue
            runCatching {
                db.delete(t, "wallMillis >= ? AND wallMillis < ? AND id <= ?",
                    arrayOf<Any>(d0, d1, mark))
            }
        }
    }

    // =================================================================================
    //  Roll: hourly -> daily, dropping identity
    // =================================================================================

    /**
     * Fold one tier-1 day into its tier-2 month, then delete the day file.
     *
     * This is where cell identity and timing advance stop. See the identity line in the class
     * comment: tier 2 outlives the 30-day boundary that bounds the movement history `radio_sample`
     * constitutes, so it may not carry anything that reconstitutes one. What replaces the identity
     * is `distinctCells` and `cellChanges` — how much churn there was, not where.
     *
     * Idempotent through `absorbed`: a day already listed in the month header is skipped, and the
     * day file is deleted afterwards, so a kill between the two converges on the next run.
     */
    fun rollDay(ctx: Context, dayLabel: String): Result = runCatching {
        val d0 = dayLabelStart(dayLabel) ?: return Result(error = "bad day $dayLabel")
        val month = monthLabel(d0)
        val src = read(ctx, Tier.HOUR, dayLabel)
        if (src == null) return Result()
        val dst = read(ctx, Tier.DAY, month) ?: RollupFile.empty(Tier.DAY, month)

        if (dayLabel in dst.absorbed) {
            // Already folded in; the only outstanding work is removing the source.
            //
            // Note what this costs in the one edge case it has: if raw rows for an
            // already-rolled day turned up afterwards (only a corrected clock really does that),
            // [compactDay] will have rewritten this hour file to include both the old buckets and
            // the new rows, and folding the whole file in again would double-count the old part.
            // So the late rows are dropped here rather than counted twice. Losing a handful of
            // month-old samples is recoverable arithmetic; a roll-up that silently counts a period
            // twice is a number nobody can ever trust again.
            fileFor(ctx, Tier.HOUR, dayLabel).delete()
            return Result(daysRolled = 0)
        }

        // Distinct cells must be counted while the identities still exist. Per (sub, plmn, band,
        // rat) so the number means "cells this carrier served on this band that day", which is
        // what the churn panels ask.
        val cellSets = HashMap<List<Any?>, HashSet<String>>()
        for (b in src.radio) {
            val id = b.ci?.let { "c$it" } ?: b.pci?.let { "p$it" } ?: continue
            cellSets.getOrPut(listOf(b.subId, b.plmn, b.rat, b.bandRat, b.band)) { HashSet() }.add(id)
        }

        val radio = mergeRadio(src.radio.map { b ->
            b.copy(
                t = d0, ci = null, pci = null,
                // Each source hour contributes a disjoint minute set -- a minute belongs to
                // exactly one hour -- so the day's covered minutes is the sum of the hours', and
                // the mask is no longer needed. Carrying the mask up would be wrong anyway: it is
                // 60 bits and a day has 1440 minutes.
                minuteMask = 0L,
                coveredHours = 1,
                timingAdvance = emptyMap(),
                distinctCells = 0
            )
        }).map { b ->
            b.copy(distinctCells = cellSets[listOf(b.subId, b.plmn, b.rat, b.bandRat, b.band)]?.size ?: 0)
        }

        val merged = RollupFile(
            tier = Tier.DAY, period = month, formatVersion = FORMAT_VERSION,
            ruleVersion = RULE_VERSION, writtenMillis = System.currentTimeMillis(),
            consumed = emptyMap(), absorbed = dst.absorbed + dayLabel,
            radio = mergeRadio(dst.radio + radio),
            reg = mergeReg(dst.reg + src.reg.map { it.copy(t = d0, minuteMask = 0L, coveredHours = 1) }),
            link = mergeLink(dst.link + src.link.map { it.copy(t = d0, minuteMask = 0L, coveredHours = 1) }),
            probe = mergeProbe(dst.probe + src.probe.map { it.copy(t = d0) }),
            instrument = mergeInstrument(dst.instrument +
                src.instrument.map { it.copy(t = d0, minuteMask = 0L, coveredHours = 1) })
        )

        if (!writeAtomic(ctx, Tier.DAY, month, merged)) return Result(error = "write failed for $month")
        fileFor(ctx, Tier.HOUR, dayLabel).delete()
        Result(daysRolled = 1, bucketsWritten = merged.bucketCount)
    }.getOrElse { Result(error = "${it.javaClass.simpleName} rolling $dayLabel") }

    // =================================================================================
    //  Drive
    // =================================================================================

    /** The oldest UTC day that still has raw rows, or null if every table is empty. */
    fun oldestRawDay(ctx: Context): String? = runCatching {
        val db = Db.get(ctx).openHelper.readableDatabase
        var oldest = Long.MAX_VALUE
        for (t in RAW_TABLES) {
            runCatching {
                db.query("SELECT MIN(wallMillis) FROM $t").use { c ->
                    // Table names here are compile-time constants from RAW_TABLES, never input.
                    if (c.moveToNext() && !c.isNull(0)) {
                        val v = c.getLong(0)
                        if (v > 0 && v < oldest) oldest = v
                    }
                }
            }
        }
        if (oldest == Long.MAX_VALUE) null else dayLabel(oldest)
    }.getOrNull()

    /**
     * Compact every complete UTC day strictly older than [boundaryMillis], then roll and prune.
     *
     * [boundaryMillis] is the age line the caller wants raw to stop at; [Retention] moves it
     * between the 30-day policy line and the 7-day floor according to storage pressure.
     *
     * [deadlineMillis] bounds one run. The unit of work is a whole day and the loop checks between
     * days, so stopping early always leaves a consistent state: either a day is raw, or it is a
     * roll-up, never half of each.
     */
    fun run(
        ctx: Context,
        boundaryMillis: Long,
        rollBeforeMillis: Long,
        pruneBeforeMillis: Long,
        deadlineMillis: Long = Long.MAX_VALUE,
        now: Long = System.currentTimeMillis()
    ): Result = synchronized(this) {
        runCatching {
            clearTmp(ctx)
            var out = Result()

            // A day is only compactable once it can no longer receive rows, so never today's.
            val today = dayStart(now)
            val limit = minOf(boundaryMillis, today)
            var guard = 0
            while (System.currentTimeMillis() < deadlineMillis && guard++ < 400) {
                val day = oldestRawDay(ctx) ?: break
                val start = dayLabelStart(day) ?: break
                if (start >= limit) break
                val r = compactDay(ctx, day)
                out += r
                // A day that produced no work and still has rows would spin forever; stop instead.
                if (r.rawRowsCompacted == 0 && r.daysCompacted == 0) break
            }
            if (System.currentTimeMillis() >= deadlineMillis) out = out.copy(stoppedEarly = true)

            // Tier 1 -> tier 2 for everything past the identity line.
            for (day in periods(ctx, Tier.HOUR)) {
                if (System.currentTimeMillis() >= deadlineMillis) { out = out.copy(stoppedEarly = true); break }
                val start = dayLabelStart(day) ?: continue
                if (start >= rollBeforeMillis) continue
                out += rollDay(ctx, day)
            }

            // Tier 2 has a cap too, so that nothing in this app is unbounded.
            for (month in periods(ctx, Tier.DAY)) {
                val start = monthLabelStart(month) ?: continue
                // Prune on the month's END, so a month is only dropped once all of it is past the cap.
                val end = start + 31L * DAY_MS
                if (end < pruneBeforeMillis) {
                    if (fileFor(ctx, Tier.DAY, month).delete()) out += Result(monthsPruned = 1)
                }
            }
            out
        }.getOrElse { Result(error = it.javaClass.simpleName) }
    }
}
