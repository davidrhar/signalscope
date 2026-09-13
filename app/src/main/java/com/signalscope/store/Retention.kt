package com.signalscope.store

import android.content.Context
import java.io.File

/**
 * Bounded retention, as `data-model.md` §7 specifies it: *"Keep full resolution 7 days, roll up
 * to per-bin/per-hour aggregates after that, drop raw at 30 days."*
 *
 * Until this existed, nothing in the app deleted anything. Both databases grew for the life of
 * the install, which meant the stated policy was a document rather than a property of the
 * software — and the thing growing without limit is the sensitive part: `radio_sample` carries
 * serving-cell identity per timestamp, and `map_fix` carries a position bin per timestamp. Those
 * two joined on `elapsedNanos` are a movement history, and an unbounded one is a strictly larger
 * target for every threat further down the list (a backup, a device transfer, a forensic image,
 * an `adb backup` on an unlocked handset, a stolen phone).
 *
 * The first version of this file was the drop half only: a `DELETE` at 30 days, shipped ahead of
 * the rollup half because it is the half that bounds exposure. The rollup half is now here, in
 * [Compaction], and this file becomes the policy that drives it.
 *
 * ## Policy, not a constant
 *
 * Three numbers, and a rule about when each one wins.
 *
 * - **[RAW_MAX_AGE_MS] = 30 days.** `data-model.md` §7's line, unchanged in *where* it sits and
 *   changed in *what happens at it*: raw no longer vanishes there, it becomes a daily roll-up.
 *   Nothing identifying crosses it — see the identity line in [Compaction].
 * - **[RAW_FULL_AGE_MS] = 7 days.** §7's *"keep full resolution 7 days"*, used here as a floor
 *   rather than a deadline. Storage pressure may pull the raw boundary in from 30 days towards a
 *   week; it may not cross it. Below a week there is not enough raw left for `IncidentEngine` or a
 *   map build to say anything, and a retention policy that eats the present to save the past has
 *   the trade backwards.
 * - **[RAW_BUDGET_BYTES] = 64 MB**, on the measurement database's *in-use* pages. Where it comes
 *   from: the reference handset collected 18,900 `radio_sample` rows in two days — about 10,000 a
 *   day — and §7's own figure is ~200 bytes a row, so roughly 2.0 MB/day of radio plus ~0.5 MB/day
 *   across `registration_event`, `link_event` and `probe_result`. At rest that is ~2.5 MB/day and
 *   30 days is ~75 MB, already over. The worst case at the floor is what sets the number: §7 lever
 *   4 floors the poll at 10 s, so a subscription cannot exceed 8,640 samples/day and a dual-SIM
 *   handset cannot exceed ~17,300, which at 200 B is ~3.5 MB/day, ~24 MB over seven days, ~32 MB
 *   with the other three tables and index overhead. **64 MB therefore always holds the 7-day
 *   floor**, which means the ceiling can be satisfied by compaction alone and never needs to reach
 *   for a measurement. A device that is still over 64 MB with only a week of raw left is
 *   collecting faster than the documented cap allows, and that is a thing for a human to look at,
 *   not for a sweep to resolve by deleting.
 *
 * Schema 3 moves those figures without breaking the argument. Measured on a copy of the reference
 * device's database, the new radio columns add ~29 B a row, and `neighbour_cell` costs ~59 B a row
 * including its index. The collector gates neighbours to one snapshot a minute per subscription
 * (plus cell changes), six rows at most, so its ceiling is ~17,300 rows/day dual-SIM, ~1 MB/day,
 * ~7 MB at the 7-day floor -- still well inside 64 MB alongside radio at its own documented cap.
 * (The same measurement puts real radio rows nearer 70 B than §7's 200 B, so the budget above is
 * conservative.)
 *
 * Age governs under the ceiling; the ceiling governs above it, by pulling the age boundary in one
 * whole UTC day at a time. A phone that travels heavily hits the size limit long before thirty
 * days, which is exactly the case the ceiling exists for.
 *
 * ## What is never traded away
 *
 * `map-regions.md`'s asymmetry, restated where the policy lives: **a deleted basemap region can be
 * re-downloaded; a deleted measurement is gone.** So this file resolves storage pressure by
 * *compacting* measurements, never by deleting them to make room, and where compaction has run out
 * of room it reports pressure and names the basemap as the replaceable thing — it does not act.
 * [RegionStore] has no eviction path at all and this does not add one; the only way a region
 * leaves is a button the user presses.
 *
 * ## Why in-use pages rather than the file size
 *
 * SQLite does not shrink a database file when rows are deleted; the freed pages go on a freelist
 * and are reused by the next insert. So the file size is not a measure of how much data is held,
 * and a ceiling checked against it would compact everything down to the floor on every sweep and
 * still see no improvement. `(page_count - freelist_count) * page_size` is the number that
 * actually falls when a day is compacted, and it is the one the ceiling is checked against.
 *
 * There is deliberately no `VACUUM`. It would rewrite tens of megabytes, under Room's own
 * connection, to return space the collector is about to use again. The property that matters is
 * that the file stops *growing*, and reusing free pages delivers that.
 *
 * ## Why raw SQL rather than a DAO
 *
 * Same reason [IncidentStore] reads that way: adding DAO methods edits the `@Database` classes
 * and a schema-version bump would discard collected samples on upgrade. `DELETE` needs no schema
 * change. **Every bound is passed as a bind argument, never concatenated** — the cut-off is
 * computed locally and could not be attacker-controlled today, but a delete statement assembled
 * by string-building is the shape of the bug that matters later, and the parameterised form costs
 * nothing.
 */
object Retention {

    /** `data-model.md` §7: raw stops at 30 days. It is now compacted there rather than dropped. */
    const val RAW_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L

    /**
     * The line past which nothing identifying survives, in any tier.
     *
     * Deliberately equal to [RAW_MAX_AGE_MS]: the security review's P1/P2 findings are that
     * `radio_sample`'s serving-cell columns over time are a movement history and that retention is
     * what bounds it. This file does not move that line, and [Compaction] enforces it by dropping
     * cell identity and timing advance at exactly this age. `map_fix` is swept at the same age for
     * the same reason.
     */
    const val IDENTITY_MAX_AGE_MS = RAW_MAX_AGE_MS

    /** `data-model.md` §7: *"keep full resolution 7 days"* — the floor the ceiling may not cross. */
    const val RAW_FULL_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    /**
     * Two years for the daily roll-ups. They run at roughly 1–3 KB/day, so the cap costs under
     * 2 MB either way; it exists so that the honest answer to "how long does this app keep
     * anything" is a number rather than "for ever".
     */
    const val ROLLUP_MAX_AGE_MS = 730L * 24L * 60L * 60L * 1000L

    /** See the class comment for the arithmetic. In-use pages, not file size. */
    const val RAW_BUDGET_BYTES = 64L * 1024L * 1024L

    /**
     * One sweep's time budget. Compaction's unit of work is a whole UTC day and it checks the
     * clock between days, so hitting this always leaves a consistent state and the next sweep
     * simply carries on. It exists because this runs on a phone that gets killed mid-task, and a
     * sweep that could run for minutes on a first upgrade would be killed rather than finished.
     */
    const val SWEEP_BUDGET_MS = 20_000L

    data class Policy(
        val rawMaxAgeMs: Long = RAW_MAX_AGE_MS,
        val rawFullAgeMs: Long = RAW_FULL_AGE_MS,
        val identityMaxAgeMs: Long = IDENTITY_MAX_AGE_MS,
        val rollupMaxAgeMs: Long = ROLLUP_MAX_AGE_MS,
        val rawBudgetBytes: Long = RAW_BUDGET_BYTES,
        val budgetMs: Long = SWEEP_BUDGET_MS
    )

    /**
     * What the last sweep did, for the UI to show rather than moving data silently.
     *
     * The first four fields are rows *deleted*. `radio`, `reg` and `link` are now almost always
     * zero on a healthy device: those rows leave by being compacted, and [Compaction.Result] says
     * how many. They stay here because a row that leaves without being counted anywhere is the
     * thing this whole file exists to prevent.
     */
    data class Swept(
        val radio: Int,
        val reg: Int,
        val link: Int,
        val fixes: Int,
        /** `neighbour_cell` rows deleted: at the raw window, or with their radio day under pressure. */
        val neighbours: Int = 0,
        val compaction: Compaction.Result = Compaction.Result(),
        val pressure: Pressure? = null,
        /** Set when the ceiling could not be met without touching a measurement. It never does. */
        val overBudgetBy: Long = 0L
    ) {
        val total: Int get() = radio + reg + link + fixes + neighbours
    }

    /** Where the storage actually is, so a decision about it can be made on numbers. */
    data class Pressure(
        /** Measurement database pages actually in use — what the ceiling is checked against. */
        val dbInUseBytes: Long,
        /** What the file occupies on disk, which only ever goes up. Shown so the gap is visible. */
        val dbFileBytes: Long,
        val mapDbBytes: Long,
        val rollupBytes: Long,
        val budgetBytes: Long,
        /** Oldest UTC day that still has raw rows, or null when there are none. */
        val oldestRawDay: String?,
        /** Free space on the volume the basemaps live on, and what they cost. */
        val space: RegionStore.Space,
        /** The replaceable thing, named but never acted on. Null when there is nothing to suggest. */
        val replaceable: RegionStore.Region?
    ) {
        val overBudgetBy: Long get() = (dbInUseBytes - budgetBytes).coerceAtLeast(0L)
        val overBudget: Boolean get() = overBudgetBy > 0
        val totalBytes: Long get() = dbFileBytes + mapDbBytes + rollupBytes
    }

    // =================================================================================
    //  Measurement
    // =================================================================================

    /**
     * Bytes the measurement database actually holds, excluding free pages it will reuse.
     *
     * Falls back to the file size if the pragmas cannot be read — an over-estimate, which errs
     * towards compacting rather than towards letting the database grow unnoticed.
     */
    fun dbInUseBytes(ctx: Context): Long = runCatching {
        val db = Db.get(ctx).openHelper.readableDatabase
        fun pragma(name: String): Long = db.query("PRAGMA $name").use { c ->
            if (c.moveToNext() && !c.isNull(0)) c.getLong(0) else 0L
        }
        val pages = pragma("page_count")
        val free = pragma("freelist_count")
        val size = pragma("page_size")
        if (pages <= 0 || size <= 0) dbFileBytes(ctx) else (pages - free).coerceAtLeast(0) * size
    }.getOrElse { dbFileBytes(ctx) }

    /** The database plus its WAL and shm sidecars, which is what the user sees in app storage. */
    fun dbFileBytes(ctx: Context): Long = fileGroupBytes(ctx, "signalscope.db")

    fun mapDbBytes(ctx: Context): Long = fileGroupBytes(ctx, "signalscope-map.db")

    private fun fileGroupBytes(ctx: Context, name: String): Long = runCatching {
        val base = ctx.getDatabasePath(name) ?: return 0L
        val dir: File = base.parentFile ?: return base.length()
        (dir.listFiles { f -> f.isFile && f.name.startsWith(name) } ?: emptyArray())
            .sumOf { it.length() }
    }.getOrDefault(0L)

    fun pressure(ctx: Context, policy: Policy = Policy()): Pressure = Pressure(
        dbInUseBytes = dbInUseBytes(ctx),
        dbFileBytes = dbFileBytes(ctx),
        mapDbBytes = mapDbBytes(ctx),
        rollupBytes = Compaction.bytes(ctx),
        budgetBytes = policy.rawBudgetBytes,
        oldestRawDay = Compaction.oldestRawDay(ctx),
        space = runCatching { RegionStore.space(ctx) }
            .getOrDefault(RegionStore.Space(0L, 0L)),
        replaceable = runCatching { RegionStore.suggestion(ctx) }.getOrNull()
    )

    // =================================================================================
    //  Sweep
    // =================================================================================

    /**
     * Blocking; call from `Dispatchers.IO`. Never throws — a failed sweep must not take down the
     * collector that calls it on start.
     *
     * Order matters. Compaction runs first and at the age boundary, because that is the cheap
     * pass and it is what the ceiling pass then measures. The ceiling pass only pulls the boundary
     * in if the database is still over budget afterwards, so a device under the budget behaves
     * exactly as `data-model.md` §7 describes and never loses full resolution early.
     */
    fun sweep(
        ctx: Context,
        nowWall: Long = System.currentTimeMillis(),
        policy: Policy = Policy()
    ): Swept {
        val deadline = System.currentTimeMillis() + policy.budgetMs
        var compaction = Compaction.Result()

        // ---- pass 1: the policy line. Raw older than 30 days becomes a roll-up, and because that
        // is also the identity line, the same pass rolls it straight past cell identity into the
        // daily tier.
        runCatching {
            compaction += Compaction.run(
                ctx,
                boundaryMillis = nowWall - policy.rawMaxAgeMs,
                rollBeforeMillis = nowWall - policy.identityMaxAgeMs,
                pruneBeforeMillis = nowWall - policy.rollupMaxAgeMs,
                deadlineMillis = deadline,
                now = nowWall
            )
        }

        // ---- pass 2: the ceiling. Pull the raw boundary in a day at a time while the database is
        // over budget and there is still raw older than the 7-day floor. Each iteration compacts
        // exactly the oldest remaining day, so the loop terminates: either in-use bytes fall below
        // the budget, or the floor is reached, or the time budget runs out.
        val floor = nowWall - policy.rawFullAgeMs
        var guard = 0
        /** Whether pressure, not age, compacted anything -- the only case neighbours follow it. */
        var pulledIn = false
        while (System.currentTimeMillis() < deadline && guard++ < 60) {
            if (dbInUseBytes(ctx) <= policy.rawBudgetBytes) break
            val oldest = Compaction.oldestRawDay(ctx) ?: break
            val start = runCatching { Compaction.dayStart(nowWall) }.getOrDefault(0L)
            val oldestStart = runCatching {
                // Reconstructed rather than carried, so this file does not need its own date parser.
                Compaction.dayStart(
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .parse(oldest)?.time ?: return@runCatching -1L
                )
            }.getOrDefault(-1L)
            if (oldestStart < 0) break
            // Never the current day (it can still receive rows) and never past the floor.
            if (oldestStart >= start || oldestStart >= floor) break
            val r = Compaction.compactDay(ctx, oldest)
            compaction += r
            if (r.daysCompacted > 0) pulledIn = true
            if (r.daysCompacted == 0) break
        }

        // ---- pass 3: positions. map_fix is not rolled up, it is dropped, and it is dropped at the
        // identity line exactly as before. A roll-up of a position is still a position, so giving
        // `map_fix` a tier would have been the one change here that widened something. It does not
        // get one; see the note in Compaction.
        var fixes = 0
        runCatching {
            val mapDb = MapDb.get(ctx).openHelper.writableDatabase
            fixes = mapDb.delete(
                "map_fix", "wallMillis < ?",
                arrayOf<Any>(nowWall - policy.identityMaxAgeMs)
            )
        }

        // ---- pass 3b: neighbour cells. Raw only, no roll-up: a neighbour reading is PCI and
        // ARFCN relative to where the observer stood (data-model.md s3), and a histogram of PCIs
        // with the position and time removed says nothing anyone can use. So it is kept for the
        // raw window and then deleted, like map_fix.
        //
        // Under storage pressure it goes with its radio day. A neighbour row is only interpretable
        // joined to the radio_sample rows around it; once pass 2 has compacted a day those are
        // gone, and keeping the neighbours would spend the budget on rows nothing can read. The
        // cut-off is the oldest day that is still raw, and never newer than the 7-day floor, so
        // this cannot reach past what pass 2 was allowed to compact.
        var neighbours = 0
        runCatching {
            val db = Db.get(ctx).openHelper.writableDatabase
            var cutoff = nowWall - policy.rawMaxAgeMs
            if (pulledIn) {
                val oldestRawStart = Compaction.oldestRawDay(ctx)?.let { label ->
                    runCatching {
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .parse(label)?.time
                    }.getOrNull()
                }
                val pressureCut = minOf(oldestRawStart ?: floor, floor)
                if (pressureCut > cutoff) cutoff = pressureCut
            }
            neighbours = db.delete("neighbour_cell", "wallMillis < ?", arrayOf<Any>(cutoff))
        }

        // ---- pass 4: report. If the database is still over budget after all of that, compaction
        // has run out of room and the only remaining levers are a measurement or the basemap.
        // Measurements are not replaceable and basemaps are, so this names the basemap and stops.
        // Nothing here deletes either one to make room.
        val p = runCatching { pressure(ctx, policy) }.getOrNull()

        return Swept(
            radio = 0, reg = 0, link = 0, fixes = fixes, neighbours = neighbours,
            compaction = compaction,
            pressure = p,
            overBudgetBy = p?.overBudgetBy ?: 0L
        )
    }

    /**
     * Plain-language summary of the last sweep, in the voice `PlainLanguage` uses elsewhere.
     * Returns null when there is nothing worth saying, so a caller can skip the row entirely.
     */
    fun describe(s: Swept): String? {
        val parts = ArrayList<String>(4)
        if (s.compaction.rawRowsCompacted > 0) {
            parts.add("${s.compaction.rawRowsCompacted} raw rows summarised into " +
                "${s.compaction.bucketsWritten} roll-up buckets")
        }
        if (s.compaction.daysRolled > 0) {
            parts.add("${s.compaction.daysRolled} day${if (s.compaction.daysRolled == 1) "" else "s"} " +
                "past the 30-day line, cell identity dropped")
        }
        if (s.fixes > 0) parts.add("${s.fixes} position bins older than 30 days deleted")
        if (s.neighbours > 0) parts.add("${s.neighbours} neighbour-cell readings past the raw window deleted")
        if (s.compaction.monthsPruned > 0) parts.add("${s.compaction.monthsPruned} roll-up months past two years deleted")
        if (s.overBudgetBy > 0) {
            val mb = s.overBudgetBy / 1_000_000
            val region = s.pressure?.replaceable
            parts.add(
                "still ${mb} MB over the storage ceiling with a week of raw left" +
                    (region?.let { "; the basemap region \"${it.label}\" is ${it.bytes / 1_000_000} MB " +
                        "and can be downloaded again" } ?: "")
            )
        }
        s.compaction.error?.let { parts.add("one step failed ($it) and will be retried") }
        if (s.compaction.stoppedEarly) parts.add("stopped at the time budget; the rest runs next time")
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }
}
