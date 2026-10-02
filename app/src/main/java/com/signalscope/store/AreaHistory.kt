package com.signalscope.store

import android.content.Context
import com.signalscope.collect.FixBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * What this place, and the places next to it, have been like at this time of day.
 *
 * ## Written here rather than folded from stored rows
 *
 * The mast record is folded out of `radio_sample`, which it can be because every row carries its
 * own cell id. No row carries a position: the app does not store one, and [FixBuffer] holds fixes
 * in memory only. So a bin's record has to be accumulated as the readings happen, pairing each one
 * with the current fix, which is what [offer] does.
 *
 * That makes it slightly less precise than the map's own binning, which brackets each sample
 * between the fixes either side of it. For an hourly share of readings below usable SINR the
 * difference does not matter; for anything drawn on a map it would, which is why this does not
 * feed the map.
 *
 * ## Why resolution 8
 *
 * About 460 m across -- the same coarsening a contribution gets, and for the same reason. It is
 * also simply what works: at the 66 m the map draws at, a bin collects too few readings in any one
 * hour to say anything, and the record would be a thousand places that each know nothing.
 */
object AreaHistory {

    const val RES = Contribution.SHARE_RES

    /** Below this an answer is silence, not reassurance. Matches [SiteHistory]'s floor. */
    private const val MIN_SAMPLES = 80L
    private const val BAD = 0.25

    private const val FIX_MAX_AGE_MS = 60_000L
    private const val FLUSH_EVERY_MS = 2 * 60_000L
    private const val DAY_MS = 86_400_000L

    private class Acc { var samples = 0L; var below = 0L; var day = 0 }

    private val pending = HashMap<Pair<Long, Int>, Acc>()
    private var lastFlush = 0L

    /**
     * One reading, placed where the phone currently is.
     *
     * Dropped when there is no fix or the fix is stale: a reading placed in the wrong bin is worse
     * than a reading not placed at all, because the record is what a later warning is based on.
     */
    @Synchronized
    fun offer(ctx: Context, wallMillis: Long, sinrDb: Int?) {
        if (sinrDb == null) return
        val fix = FixBuffer.latest() ?: return
        if (wallMillis - fix.wallMillis > FIX_MAX_AGE_MS) return
        val bin = runCatching { MapHex.cellToParent(fix.binId, RES) }.getOrNull() ?: return

        val cal = Calendar.getInstance().apply { timeInMillis = wallMillis }
        val a = pending.getOrPut(bin to cal.get(Calendar.HOUR_OF_DAY)) { Acc() }
        a.samples++
        if (sinrDb < 0) a.below++
        a.day = maxOf(a.day, (wallMillis / DAY_MS).toInt())

    }

    /**
     * Move what has accumulated into the table.
     *
     * Called from the same five-minute loop that rolls up map bins, rather than owning a timer:
     * the two do the same kind of work on the same data and there is no reason for a second one.
     * Pending counts live in memory until then, and losing them to a kill costs a few minutes of
     * one bin's record.
     */
    suspend fun flush(ctx: Context): Int = withContext(Dispatchers.IO) {
        val snapshot = synchronized(this@AreaHistory) {
            if (pending.isEmpty()) return@withContext 0
            HashMap(pending).also { pending.clear() }
        }
        runCatching {
            val dao = Db.get(ctx).dao()
            val prior = dao.binHours(snapshot.keys.map { it.first }.distinct())
                .associateBy { it.binId to it.hourBucket }
            dao.upsertBinHours(snapshot.map { (k, a) ->
                val p = prior[k]
                BinHourStat(
                    binId = k.first, hourBucket = k.second,
                    samples = (p?.samples ?: 0L) + a.samples,
                    belowZero = (p?.belowZero ?: 0L) + a.below,
                    lastSeenDay = maxOf(p?.lastSeenDay ?: 0, a.day)
                )
            })
            snapshot.size
        }.getOrDefault(0)
    }

    data class Verdict(val bad: Boolean, val share: Double, val samples: Long, val ahead: Boolean)

    /**
     * This bin, and the ring around it.
     *
     * [ahead] is true when the current bin is fine but a neighbouring one is not. That is the one
     * genuinely predictive thing in the whole project: a bin has neighbours and a mast does not, so
     * this can be said before the trouble starts rather than as it starts.
     */
    suspend fun here(ctx: Context, hour: Int): Verdict? = withContext(Dispatchers.IO) {
        val fix = FixBuffer.latest() ?: return@withContext null
        val bin = runCatching { MapHex.cellToParent(fix.binId, RES) }.getOrNull()
            ?: return@withContext null
        val ring = MapHex.neighbours(bin)
        val rows = runCatching { Db.get(ctx).dao().binHours(listOf(bin) + ring) }
            .getOrDefault(emptyList())
            .filter { it.hourBucket == hour }
        val mine = rows.firstOrNull { it.binId == bin }
        val near = rows.filter { it.binId != bin && it.samples >= MIN_SAMPLES }

        val mineBad = mine != null && mine.samples >= MIN_SAMPLES &&
            mine.belowZero.toDouble() / mine.samples >= BAD
        val nearBad = near.any { it.belowZero.toDouble() / it.samples >= BAD }
        if (!mineBad && !nearBad) return@withContext null
        val n = mine?.takeIf { it.samples >= MIN_SAMPLES }
        Verdict(
            bad = mineBad,
            share = if (n != null) n.belowZero.toDouble() / n.samples
            else near.maxOf { it.belowZero.toDouble() / it.samples },
            samples = n?.samples ?: near.sumOf { it.samples },
            ahead = !mineBad && nearBad
        )
    }

    suspend fun sweep(ctx: Context, maxAgeMs: Long): Int = runCatching {
        Db.get(ctx).dao().sweepBinHours(((System.currentTimeMillis() - maxAgeMs) / DAY_MS).toInt())
    }.getOrDefault(0)
}
