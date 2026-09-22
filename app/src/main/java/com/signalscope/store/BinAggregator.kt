package com.signalscope.store

import android.content.Context
import com.signalscope.collect.FixBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Turn the fixes held in memory into bins on disk, and forget the fixes.
 *
 * Runs on a timer from the collector, not from the Map tab: a user who never opens the map still
 * accumulates a map, and a user who does must not be the reason their history survives.
 *
 * ## The one rule that keeps the arithmetic honest
 *
 * Aggregation **consumes**. Fixes older than [LAG_MS] are drained out of [FixBuffer], bins are
 * built from them, and those bins are merged into `bin_agg`. The drained fixes are gone, so the
 * same reading can never be counted once on disk and again live. This is deliberately stronger
 * than a high-water mark: a mark has to be updated correctly after a partial failure, and this
 * cannot be wrong, only incomplete.
 *
 * [LAG_MS] keeps the most recent minutes in memory so the map has something to draw between
 * flushes, and so a bin the phone is currently sitting in is not repeatedly written mid-visit.
 */
object BinAggregator {

    /** How often to fold memory into disk. */
    private const val EVERY_MS = 5 * 60_000L

    /** Fixes younger than this stay live, so the map is not blank between flushes. */
    private const val LAG_MS = 3 * 60_000L

    /** Whole days since the epoch, matching [BinAgg.lastSeenDay]. */
    private const val DAY_MS = 86_400_000L

    private var job: Job? = null

    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            while (isActive) {
                delay(EVERY_MS)
                runCatching { flush(app) }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /**
     * @return bins written, or null if there was nothing to do or it failed.
     *
     * Failure is swallowed by the caller on purpose: losing a flush costs a few minutes of map
     * history, and is not worth taking the collector down for.
     */
    suspend fun flush(ctx: Context): Int? {
        val cutoff = System.currentTimeMillis() - LAG_MS
        val consumed = FixBuffer.drainThrough(cutoff)
        if (consumed.isEmpty()) return null

        val fresh = MapBinBuilder.binsFrom(ctx, consumed)
        if (fresh.isEmpty()) return 0

        val dao = Db.get(ctx).dao()
        val existing = dao.allBinAgg()
            .mapNotNull { row -> BinCodec.decode(row.blob)?.let { (row.binId to row.subId) to it } }
            .toMap()

        val merged = fresh.map { b ->
            val prior = existing[b.id to b.subId]
            // combine() is the same merge the builder uses to roll children into a parent. Called
            // with the bin's own id and resolution it merges two views of one place, which is what
            // a restored bin and a fresh one are.
            if (prior == null) b else MapBinBuilder.combineSame(listOf(prior, b))
        }

        dao.upsertBinAgg(merged.map {
            BinAgg(
                binId = it.id, subId = it.subId, blob = BinCodec.encode(it),
                lastSeenDay = (it.lastSeenMillis / DAY_MS).toInt()
            )
        })
        return merged.size
    }

    /** Drop bins not seen inside the retention window. Same policy as the raw tables. */
    suspend fun sweep(ctx: Context, maxAgeMs: Long): Int = runCatching {
        val cutoffDay = ((System.currentTimeMillis() - maxAgeMs) / DAY_MS).toInt()
        Db.get(ctx).dao().sweepBinAgg(cutoffDay)
    }.getOrDefault(0)
}
