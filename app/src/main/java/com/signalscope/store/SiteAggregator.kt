package com.signalscope.store

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Fold new readings into what each mast's record already says.
 *
 * ## Why incremental
 *
 * The first version of this answered "what has this mast been like?" by scanning up to a month of
 * `radio_sample` every time the serving cell changed. Three things wrong with that: it grows
 * slower as the database grows, it repeats the same work on every cell change, and the answer
 * expires -- raw rows are swept at 30 days, so a mast the phone has known for six months would
 * quietly forget five of them.
 *
 * This reads only rows newer than the watermark and adds them to a standing total, so the record
 * outlives the rows it was built from and the work done is proportional to what is new.
 *
 * ## The watermark, and why it is only ever advanced after a successful write
 *
 * A crash between "write the totals" and "advance the mark" costs a re-count of a few minutes of
 * rows, which is harmless because the write is idempotent per batch -- the same rows added twice
 * would not be, which is exactly why the mark moves last and never first.
 */
object SiteAggregator {

    private const val PREFS = "site_agg"
    private const val KEY_MARK = "watermark_wall"
    private const val KEY_VER = "fold_version"

    /**
     * Bumped whenever a change alters which rows the fold counts or how it counts them.
     *
     * The record is a running total, so a corrected rule cannot be applied to it -- only to rows
     * not yet read, which leaves a total that is part right and part wrong and no way to tell
     * which part. Clearing it and folding again from the start is the only honest answer, and it
     * costs nothing but the catch-up: the raw rows are still there.
     *
     * 2: counts a reading as LTE by the SERVING CELL's technology, not by whichever bearer was
     *    carrying data. Version 1 read `rat` and so discarded every reading taken while Wi-Fi
     *    calling was up, each of which had a perfectly usable LTE cell id on it.
     *
     * 3: starts again from the moment of the upgrade rather than from the oldest stored row,
     *    which no other version has had to do. Rows written before the carrier-aggregation fix
     *    in TelephonyCollector.applyServing carry a serving cell that is the primary on some
     *    readings and the secondary on others, so a mast's record built from them is a blend of
     *    two masts on two bands. That cannot be repaired by re-reading: the rows do not say which
     *    reading was which. Discarding the record and waiting for correct rows is the only honest
     *    answer, and it costs a few days of silence from the mast card.
     */
    private const val FOLD_VERSION = 3

    /** Versions whose upgrade must skip the stored backlog instead of re-reading it. */
    private val SKIP_BACKLOG = setOf(3)

    private const val EVERY_MS = 10 * 60_000L
    private const val DAY_MS = 86_400_000L

    /** Rows per pass. A phone that has been offline for days catches up over several passes. */
    private const val BATCH = 20_000

    private var job: Job? = null

    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            while (isActive) {
                runCatching { fold(app) }
                delay(EVERY_MS)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** @return rows folded. */
    suspend fun fold(ctx: Context): Int = runCatching {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dao = Db.get(ctx).dao()

        if (prefs.getInt(KEY_VER, 1) != FOLD_VERSION) {
            runCatching {
                Db.get(ctx).openHelper.writableDatabase.execSQL("DELETE FROM `site_stat`")
            }
            val e = prefs.edit().putInt(KEY_VER, FOLD_VERSION)
            if (FOLD_VERSION in SKIP_BACKLOG) e.putLong(KEY_MARK, System.currentTimeMillis())
            else e.remove(KEY_MARK)
            e.apply()
        }

        val mark = prefs.getLong(KEY_MARK, 0L)
        val db = Db.get(ctx).openHelper.readableDatabase

        // (plmn, site, hour) -> running totals for this pass.
        val acc = HashMap<Triple<String, Long, Int>, Acc>()
        var maxWall = mark
        var read = 0

        db.query(
            "SELECT wallMillis, servingCi, rssnr, rsrq, mcc, mnc, rat, cellRat FROM radio_sample " +
                "WHERE wallMillis > $mark ORDER BY wallMillis ASC LIMIT $BATCH"
        ).use { c ->
            val cal = java.util.Calendar.getInstance()
            while (c.moveToNext()) {
                read++
                val wall = c.getLong(0)
                if (wall > maxWall) maxWall = wall
                if (c.isNull(1) || c.isNull(2)) continue
                // cellRat is the SERVING CELL's technology; rat is whichever bearer was carrying
                // data, which reads IWLAN whenever Wi-Fi calling is up. Keying a mast record on
                // the latter dropped every reading taken over Wi-Fi calling even though each one
                // had a usable LTE cell id attached. rat is the fallback only for rows written
                // before cellRat existed as a column.
                val rat = when {
                    !c.isNull(7) -> c.getString(7)
                    !c.isNull(6) -> c.getString(6)
                    else -> ""
                }
                // LTE only: NR packs the gNB-ID with a configurable length, so site and sector
                // cannot be separated from the identity alone and are not guessed at.
                if (!rat.startsWith("LTE")) continue
                val ci = c.getLong(1)
                if (ci !in 0..0x0FFFFFFFL) continue
                val mcc = if (c.isNull(4)) "" else c.getString(4)
                val mnc = if (c.isNull(5)) "" else c.getString(5)
                if (mcc.isEmpty()) continue

                cal.timeInMillis = wall
                val key = Triple("$mcc-$mnc", ci shr 8, cal.get(java.util.Calendar.HOUR_OF_DAY))
                val a = acc.getOrPut(key) { Acc() }
                a.samples++
                if (c.getInt(2) < 0) a.belowZero++
                if (!c.isNull(3)) {
                    val q = c.getInt(3)
                    a.rsrq[q] = (a.rsrq[q] ?: 0) + 1
                }
                a.lastDay = maxOf(a.lastDay, (wall / DAY_MS).toInt())
            }
        }
        if (acc.isEmpty()) {
            // Still advance: rows with no usable cell identity are read once and never again.
            if (maxWall > mark) prefs.edit().putLong(KEY_MARK, maxWall).apply()
            return@runCatching read
        }

        // Merge with what is already stored, one mast at a time.
        val merged = ArrayList<SiteStat>(acc.size)
        for ((key, a) in acc) {
            val (plmn, site, hour) = key
            val prior = dao.siteStats(plmn, site).firstOrNull { it.hourBucket == hour }
            val hist = HashMap<Int, Long>()
            prior?.let { decodeHist(it.rsrqHist, hist) }
            a.rsrq.forEach { (v, n) -> hist[v] = (hist[v] ?: 0L) + n }
            merged += SiteStat(
                plmn = plmn, site = site, hourBucket = hour,
                samples = (prior?.samples ?: 0L) + a.samples,
                belowZero = (prior?.belowZero ?: 0L) + a.belowZero,
                rsrqHist = encodeHist(hist),
                lastSeenDay = maxOf(prior?.lastSeenDay ?: 0, a.lastDay)
            )
        }
        dao.upsertSiteStats(merged)
        // Last, never first. A crash before this costs a re-count of a few minutes; a crash after
        // an early advance would lose those readings from every mast's record permanently.
        prefs.edit().putLong(KEY_MARK, maxWall).apply()
        read
    }.getOrDefault(0)

    /**
     * What the mast record currently holds, and how far behind the fold is.
     *
     * Without this the feature is unobservable: a mast card that stays quiet looks identical
     * whether the record is empty, still catching up, or simply says this mast is fine. That is
     * fine for someone using the app and useless for anyone checking it works.
     */
    data class Status(val masts: Int, val slots: Int, val samples: Long, val behind: Long)

    suspend fun status(ctx: Context): Status = runCatching {
        val rows = Db.get(ctx).dao().allSiteStats()
        val mark = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_MARK, 0L)
        val behind = Db.get(ctx).openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM radio_sample WHERE wallMillis > $mark")
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        Status(
            masts = rows.map { it.site }.distinct().size,
            slots = rows.size,
            samples = rows.sumOf { it.samples },
            behind = behind
        )
    }.getOrDefault(Status(0, 0, 0L, 0L))

    /** Forget every mast's record, and start the fold again from nothing. */
    fun forget(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_MARK).putInt(KEY_VER, FOLD_VERSION).apply()
    }

    private class Acc {
        var samples = 0L
        var belowZero = 0L
        var lastDay = 0
        val rsrq = HashMap<Int, Long>()
    }

    internal fun encodeHist(m: Map<Int, Long>): String =
        JSONObject().apply { m.forEach { (k, v) -> put(k.toString(), v) } }.toString()

    internal fun decodeHist(s: String, into: HashMap<Int, Long>) {
        runCatching {
            val o = JSONObject(s)
            o.keys().forEach { k -> k.toIntOrNull()?.let { into[it] = o.optLong(k) } }
        }
    }
}
